package com.actioncombat.combat;

import com.actioncombat.ActionCombat;
import com.actioncombat.CombatConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared runtime timing and per-clip authored phases, generated from animation data by
 * {@code tools/generate_attack_timings.mjs} and shipped as a data file both sides read.
 * <p>
 * Playback is 1:1 with the retargeted clip: 0.3s slash then 0.1s recovery.
 * Pre-input opens at 0.2s; the next attack may start at 0.3s.
 * <p>
 * Loaded from the classpath rather than the resource manager so the dedicated server
 * and gametests can read it without a resource reload. A missing or unparsable entry
 * falls back to ratio-derived authored phases with the same runtime cadence.
 */
public final class AttackTimings {
    private static final String PATH = "/actioncombat/attack_timings.json";

    /** "<animationsId>/<clip>" -> timing. */
    private static final Map<String, Timing> TIMINGS = new ConcurrentHashMap<>();
    private static final Map<String, Timing> FP_TIMINGS = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    /** Runtime values precede the original clip's contact span and total length. */
    public record Timing(float activeStart, float activeEnd,
                         float comboStart, float comboEnd,
                         float cancelStart, float length,
                         float authoredActiveStart, float authoredActiveEnd, float authoredLength) {

        public int activeStartTick() {
            return Math.round(activeStart * 20.0F);
        }

        public int activeEndTick() {
            return Math.max(activeStartTick() + 1, Math.round(activeEnd * 20.0F));
        }

        public int comboStartTick() {
            return Math.round(comboStart * 20.0F);
        }

        public int comboEndTick() {
            return Math.max(comboStartTick() + 1, Math.round(comboEnd * 20.0F));
        }

        public int cancelStartTick() {
            return Math.round(cancelStart * 20.0F);
        }

        public float cancelSeconds() {
            return cancelStartTick() / 20.0F;
        }

        /** Cadence is uniform; the packet still carries an interval for compatibility. */
        public Timing withInterval(int intervalTicks) {
            return this;
        }

        public float authoredCancelStart() {
            return CombatConstants.ATTACK_SLASH_SECONDS;
        }

        /** The retargeted clip already contains slash + recovery, so wall-clock is 1:1. */
        public double animationSeconds(double elapsed) {
            if (Double.isNaN(elapsed) || elapsed <= 0.0) return 0.0;
            if (elapsed >= length) return authoredLength;
            if (length <= 0.0F) return 0.0;
            return elapsed / length * authoredLength;
        }
    }

    /**
     * Wall-clock playback length of a clip: its runtime length plus, for dual-wield
     * swings, the extra hold on the final frame. The attack's cancel window is a
     * separate value and does not change.
     */
    public static float playbackSeconds(WeaponCategory category, float clipSeconds) {
        return clipSeconds + (category == WeaponCategory.DOUBLE
                ? CombatConstants.DUAL_ATTACK_LAST_FRAME_HOLD_SECONDS : 0.0F);
    }

    /** Base and upper-body clip names share a runtime clock and authored phase mapping. */
    public static Timing of(WeaponCategory category, String clip) {
        return of(category, clip, CombatConstants.ATTACK_DURATION_SECONDS);
    }

    public static Timing of(WeaponCategory category, String clip, float durationSeconds) {
        ensureLoaded();
        String base = baseClip(clip);
        Timing timing = TIMINGS.get(category.animationsId + "/" + base);
        return timing != null ? timing : fallback(authoredDuration(category, base, durationSeconds));
    }

    /** Single-sword FP has its own authored poses; dual FP copies the third-person clips. */
    public static Timing firstPersonOf(WeaponCategory category, String clip) {
        if (category == WeaponCategory.DOUBLE) return of(category, clip);
        ensureLoaded();
        Timing timing = FP_TIMINGS.get(category.animationsId + "/" + baseClip(clip));
        return timing != null ? timing : of(category, clip);
    }

    private static String baseClip(String clip) {
        return clip != null && clip.endsWith("_upper")
                ? clip.substring(0, clip.length() - "_upper".length()) : clip;
    }

    private static Timing fallback(float duration) {
        float safeDuration = Float.isFinite(duration) && duration > 0.0F
                ? duration : CombatConstants.ATTACK_DURATION_SECONDS;
        return uniform(safeDuration * CombatConstants.COMBO_WINDOW_START,
                safeDuration * CombatConstants.COMBO_WINDOW_END, safeDuration);
    }

    private static Timing uniform(float authoredStart, float authoredEnd, float authoredLength) {
        return new Timing(
                CombatConstants.ATTACK_ACTIVE_START_SECONDS,
                CombatConstants.ATTACK_ACTIVE_END_SECONDS,
                CombatConstants.COMBO_WINDOW_START,
                CombatConstants.ATTACK_DURATION_SECONDS,
                CombatConstants.ATTACK_INTERVAL_SECONDS,
                CombatConstants.ATTACK_DURATION_SECONDS,
                authoredStart, authoredEnd, authoredLength);
    }

    private static float authoredDuration(WeaponCategory category, String clip, float fallback) {
        if (WeaponCategory.DUAL_ATTACK.equals(clip)) return CombatConstants.ATTACK_DURATION_SECONDS;
        for (SwingSide side : SwingSide.values()) {
            WeaponCategory.SwingChain chain = category.chain(side);
            for (int hand = 0; hand < chain.length(); hand++) {
                if (chain.clip(hand).equals(clip)) return chain.duration(hand);
            }
        }
        return fallback;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (AttackTimings.class) {
            if (loaded) return;
            load();
            loaded = true;
        }
    }

    private static void load() {
        try (InputStream in = AttackTimings.class.getResourceAsStream(PATH)) {
            if (in == null) {
                ActionCombat.LOGGER.warn("{} not found; using fallback authored phases", PATH);
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

            for (var fileEntry : root.entrySet()) {
                if (!fileEntry.getValue().isJsonObject()) continue; // skips _comment
                String animationsId = fileEntry.getKey();
                for (var clipEntry : fileEntry.getValue().getAsJsonObject().entrySet()) {
                    JsonObject t = clipEntry.getValue().getAsJsonObject();
                    TIMINGS.put(animationsId + "/" + clipEntry.getKey(), uniform(
                            t.get("authoredActiveStart").getAsFloat(),
                            t.get("authoredActiveEnd").getAsFloat(),
                            t.get("authoredLength").getAsFloat()));
                    if (t.has("firstPerson")) {
                        JsonObject fp = t.getAsJsonObject("firstPerson");
                        FP_TIMINGS.put(animationsId + "/" + clipEntry.getKey(), uniform(
                                fp.get("authoredActiveStart").getAsFloat(),
                                fp.get("authoredActiveEnd").getAsFloat(),
                                fp.get("authoredLength").getAsFloat()));
                    }
                }
            }
            ActionCombat.LOGGER.debug("loaded {} attack timings", TIMINGS.size());
        } catch (Exception e) {
            ActionCombat.LOGGER.error("failed to read {}; using fallback authored phases", PATH, e);
        }
    }

    /** Number of loaded entries, for verification. */
    public static int size() {
        ensureLoaded();
        return TIMINGS.size();
    }

    private AttackTimings() {}
}

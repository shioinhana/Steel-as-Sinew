package org.fsrpg.movement;

import org.fsrpg.progression.FootworkRules;
import org.fsrpg.progression.FootworkStyle;

/**
 * Transient, server-owned ability clocks for one player. Learning and selection credits live in
 * progression; everything here is reconstructible and must never be persisted.
 *
 * <p>Air-jump charges are restored only by a real landing transition, never by input messages.
 */
public final class FootworkState {
    private long rabbitReady, swanReady, rabbitUntil, swanUntil, lastJump = Long.MIN_VALUE;
    private long specialReady, combinationReady;
    private long lastRequest = -1, lastStyleTick = Long.MIN_VALUE, sequence;
    private int jumpsUsed;
    private boolean grounded;

    /** Monotonic request de-duplication; replayed or reordered intents are ignored. */
    public boolean request(long id) {
        if (id < 1 || id <= lastRequest) return false;
        lastRequest = id;
        return true;
    }
    /** Landing (false -> true) is the only event that restores the finite air-jump allowance. */
    public void ground(boolean value) {
        if (value && !grounded) jumpsUsed = 0;
        grounded = value;
    }
    public boolean grounded() { return grounded; }

    public boolean styleReady(FootworkStyle style, long tick) {
        if (style == null || style == FootworkStyle.NONE || tick == lastStyleTick) return false;
        return tick >= (style == FootworkStyle.RABBIT ? rabbitReady : swanReady);
    }
    /**
     * Accepting a school starts its invulnerability / combination window and its cooldown. If Light
     * Body III removed the school cooldown, only the same-tick guard still applies.
     */
    public void styleAccepted(FootworkStyle style, int lightTier, long tick, FootworkRules rules) {
        if (style == null) return;
        lastStyleTick = tick;
        boolean noCooldown = lightTier >= 3 && rules.lightThirdRemovesCooldown();
        if (style == FootworkStyle.RABBIT) {
            rabbitUntil = tick + rules.rabbitInvulnerabilityTicks();
            rabbitReady = tick + (noCooldown ? 0 : rules.rabbitCooldownTicks());
        } else if (style == FootworkStyle.FLYING_SWAN) {
            swanUntil = tick + rules.flyingSwanWindowTicks();
            swanReady = tick + (noCooldown ? 0 : rules.flyingSwanCooldownTicks());
        }
    }
    /** Records a real jump (vanilla, cloud step, charged jump or stomp) for the shared interval rule. */
    public void onJump(long tick) { lastJump = Math.max(lastJump, tick); }
    public boolean jumpReady(long tick, FootworkRules rules) {
        return lastJump == Long.MIN_VALUE || tick - lastJump >= rules.jumpIntervalTicks();
    }
    public boolean airJumpReady(int cloudTier, long tick, FootworkRules rules) {
        return cloudTier > jumpsUsed && jumpReady(tick, rules);
    }
    public void jumpAccepted(boolean air, long tick, FootworkRules rules) {
        if (air) jumpsUsed++;
        onJump(tick);
    }
    /** Stomp/wall kick shares a cooldown and is also a jump, but never consumes an air-jump charge. */
    public boolean specialReady(long tick, FootworkRules rules) {
        return jumpReady(tick, rules) && tick >= specialReady;
    }
    public void specialAccepted(long tick, FootworkRules rules) {
        specialReady = tick + rules.specialCooldownTicks();
        onJump(tick);
    }
    public boolean invulnerable(long tick) { return tick < rabbitUntil; }
    public boolean combinationWindow(long tick) { return invulnerable(tick) || tick < swanUntil; }
    /** Flash-thrust combination: once per window start and never more often than its own cooldown. */
    public boolean takeCombination(long tick, FootworkRules rules) {
        if (!combinationWindow(tick) || tick < combinationReady) return false;
        combinationReady = tick + rules.combinationCooldownTicks();
        return true;
    }
    public long nextSequence() { return ++sequence; }
    public int jumpsUsed() { return jumpsUsed; }
    public long rabbitRemaining(long tick) { return Math.max(0, rabbitReady - tick); }
    public long swanRemaining(long tick) { return Math.max(0, swanReady - tick); }
    public long specialRemaining(long tick) { return Math.max(0, specialReady - tick); }
    public long combinationRemaining(long tick) { return Math.max(0, combinationReady - tick); }
}

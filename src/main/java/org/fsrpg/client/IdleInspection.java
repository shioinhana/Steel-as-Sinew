package org.fsrpg.client;

import java.util.Random;
import java.util.function.ToDoubleFunction;

/** Cosmetic game-clock scheduler. Never dispatches combat intents or touches resources. */
public final class IdleInspection {
    public static final String BLADE = "rapier_inspect_blade", FLOURISH = "rapier_inspect_flourish";
    private final Random random;
    private double deadline = Double.NaN, started, lastTick = Double.NaN, blockedUntil;
    private double configuredMin = Double.NaN, configuredMax;
    private String action = "", previous = "";
    public IdleInspection(long seed) { random = new Random(seed); }
    public String action() { return action; }
    public double started() { return started; }
    public double deadline() { return deadline; }
    public void interrupt(double now) {
        action = ""; deadline = Double.NaN; blockedUntil = now + 2;
    }
    public void update(double now, boolean eligible, double minimumSeconds, double maximumSeconds,
                       ToDoubleFunction<String> durationSeconds) {
        if (!Double.isFinite(now)) return;
        double min = Math.clamp(Double.isFinite(minimumSeconds) ? minimumSeconds : 10, 1, 600);
        double max = Math.clamp(Double.isFinite(maximumSeconds) ? maximumSeconds : 30, min, 600);
        // Lost visibility, a clock rewind, or a configuration edit starts a fresh stationary interval.
        if (Double.isFinite(lastTick) && (now < lastTick || now - lastTick > 5)
                || min != configuredMin || max != configuredMax) {
            action = ""; deadline = Double.NaN;
        }
        lastTick = now; configuredMin = min; configuredMax = max;
        if (!eligible || now < blockedUntil) { action = ""; deadline = Double.NaN; return; }
        if (!action.isEmpty()) {
            double duration = durationSeconds.applyAsDouble(action);
            if (!Double.isFinite(duration) || duration <= 0 || now - started >= duration * 20) {
                action = ""; deadline = Double.NaN;
            } else return;
        }
        if (!Double.isFinite(deadline)) deadline = now + (min + random.nextDouble() * (max - min)) * 20;
        if (now < deadline) return;
        String next = previous.isEmpty() ? (random.nextBoolean() ? BLADE : FLOURISH) : previous.equals(BLADE) ? FLOURISH : BLADE;
        double duration = durationSeconds.applyAsDouble(next);
        if (!Double.isFinite(duration) || duration <= 0) { deadline = Double.NaN; return; }
        action = previous = next; started = now;
    }
}

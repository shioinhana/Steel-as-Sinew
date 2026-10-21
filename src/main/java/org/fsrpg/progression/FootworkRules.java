package org.fsrpg.progression;

/**
 * Historical movement parameters, carried over from the legacy ActionCombat cultivation config.
 * Distances are blocks, impulses m/s, and all durations/cooldowns server ticks (20/s).
 */
public record FootworkRules(int selectionPoints, double rabbitDistance, int rabbitInvulnerabilityTicks,
        int rabbitCooldownTicks, double flyingSwanCurveLinear, int flyingSwanCooldownTicks,
        int flyingSwanWindowTicks, int jumpIntervalTicks, double chargedJumpHeightMultiplier,
        double wallRange, double targetRange, double targetDamageShare, int specialCooldownTicks,
        double airDragFactor, double jumpImpulseMps, boolean lightThirdRemovesCooldown,
        int combinationCooldownTicks, double flashThrustDamageShare) {
    public FootworkRules {
        integer(selectionPoints, 1, 1_000_000_000, "selection interval");
        range(rabbitDistance, .01, 64, "rabbit distance");
        integer(rabbitInvulnerabilityTicks, 1, 72_000, "rabbit duration");
        cooldown(rabbitCooldownTicks);
        range(flyingSwanCurveLinear, 0, 100, "flying swan curve");
        cooldown(flyingSwanCooldownTicks);
        integer(flyingSwanWindowTicks, 1, 72_000, "combination window");
        integer(jumpIntervalTicks, 1, 72_000, "jump interval");
        range(chargedJumpHeightMultiplier, 1, 100, "charged jump height multiplier");
        range(wallRange, .01, 64, "wall range");
        range(targetRange, .01, 64, "target range");
        range(targetDamageShare, 0, 1, "target health damage share");
        cooldown(specialCooldownTicks);
        range(airDragFactor, 0, 1, "air drag factor");
        range(jumpImpulseMps, 0, 100, "jump impulse");
        cooldown(combinationCooldownTicks);
        range(flashThrustDamageShare, 0, 1, "flash thrust damage share");
    }
    /** Published 0.1.1 values: 3-block dash, 0.4 s/0.6 s, 0.5 drag, 1.5 s, 4-tick chain, 24-tick stomp. */
    public static FootworkRules defaults() {
        return new FootworkRules(150, 3, 8, 12, .5, 30, 8, 4, 2, 2, 4, .02, 24,
                .5, 10, true, 100, .5);
    }
    private static void cooldown(int value) { integer(value, 0, 72_000, "cooldown"); }
    private static void integer(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("invalid footwork " + name);
    }
    private static void range(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("invalid footwork " + name);
    }
}

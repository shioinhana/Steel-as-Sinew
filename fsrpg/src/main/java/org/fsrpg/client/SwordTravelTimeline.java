package org.fsrpg.client;

/** Maps a server-owned cumulative sword advance onto the authored full-body clip. */
public final class SwordTravelTimeline {
    private SwordTravelTimeline() {}

    /**
     * Both arguments use the same distance unit. Use the expected advance so a
     * blocked action still finishes; actual advance belongs to foot support.
     * A zero-distance defense/held charge must retain its separate phase clock.
     */
    public static double sourceFraction(double expectedAdvance, double totalDistance) {
        if (!Double.isFinite(expectedAdvance) || !Double.isFinite(totalDistance)
                || totalDistance <= 0 || expectedAdvance <= 0) return 0;
        if (expectedAdvance >= totalDistance) return 1;
        double progress = expectedAdvance / totalDistance;
        if (progress <= .20) return .26 * inverseSmoothstep(progress / .20);
        if (progress <= .85) return .26 + .60 * inverseSmoothstep((progress - .20) / .65);
        return .86 + .14 * inverseSmoothstep((progress - .85) / .15);
    }

    private static double inverseSmoothstep(double value) {
        if (value <= 0) return 0;
        if (value >= 1) return 1;
        // Inverse of u*u*(3-2*u) on [0,1]; no iterative clock or sampling state.
        return .5 - Math.sin(Math.asin(1 - 2 * value) / 3);
    }
}

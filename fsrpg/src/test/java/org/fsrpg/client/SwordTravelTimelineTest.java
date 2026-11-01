package org.fsrpg.client;

import org.fsrpg.combat.SkillDefinition;
import org.fsrpg.combat.SkillInput;
import org.fsrpg.combat.SwordMotionPlan;

public final class SwordTravelTimelineTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, double epsilon, String message) {
        check(Math.abs(actual - expected) <= epsilon, message + ": " + actual + " != " + expected);
    }
    private static SkillDefinition skill(int w, int a, int r) {
        return new SkillDefinition("timeline_test", "timeline_test", "sword", SkillInput.LEFT,
                w, a, r, 0, 0, 0, 1, 4, 120, 0, true, true, true, "rapier_fullbody.fsvoxel", "test");
    }
    private static double sourceAtTick(int tick, SkillDefinition skill) {
        int w = skill.windupTicks(), a = skill.activeTicks();
        if (tick <= w) return .26 * tick / w;
        if (tick <= w + a) return .26 + .60 * (tick - w) / a;
        return .86 + .14 * (tick - w - a) / skill.recoveryTicks();
    }
    private static double authoredTravel(double source) {
        if (source <= .26) return .20 * smooth(source / .26);
        if (source <= .86) return .20 + .65 * smooth((source - .26) / .60);
        return .85 + .15 * smooth((source - .86) / .14);
    }
    private static double smooth(double x) { return x * x * (3 - 2 * x); }

    public static void main(String[] args) {
        double[] distances = {.25, .32, .35, .40, .45, .55, .65, .85};
        for (double distance : distances) {
            for (var skill : new SkillDefinition[]{skill(3, 3, 3), skill(4, 4, 4), skill(2, 5, 9), skill(3000, 5000, 2000)}) {
                double previous = -1;
                for (int tick = 0; tick <= skill.durationTicks(); tick++) {
                    double progress = SwordMotionPlan.progress(tick, skill);
                    double source = SwordTravelTimeline.sourceFraction(progress * distance, distance);
                    near(source, sourceAtTick(tick, skill), 2e-8, "real server curve round trip");
                    near(authoredTravel(source), progress, 2e-15, "authored curve agrees with server advance");
                    check(source >= previous, "source timeline cannot reverse");
                    check(source >= 0 && source <= 1, "bounded source fraction");
                    previous = source;
                }
            }
            near(SwordTravelTimeline.sourceFraction(0, distance), 0, 0, "origin");
            near(SwordTravelTimeline.sourceFraction(.20 * distance, distance), .26, 2e-8, "windup boundary");
            near(SwordTravelTimeline.sourceFraction(.85 * distance, distance), .86, 2e-8, "active handoff boundary");
            near(SwordTravelTimeline.sourceFraction(distance, distance), 1, 0, "endpoint");
            for (double boundary : new double[]{.20, .85}) {
                double at = SwordTravelTimeline.sourceFraction(boundary * distance, distance);
                double left = SwordTravelTimeline.sourceFraction(Math.nextDown(boundary * distance), distance);
                double right = SwordTravelTimeline.sourceFraction(Math.nextUp(boundary * distance), distance);
                check(left <= at && at <= right, "continuous boundary remains monotonic");
                near(left, right, 2e-8, "no source jump across adjacent-double boundary");
            }
            near(SwordTravelTimeline.sourceFraction(-distance, distance), 0, 0, "negative receipt clamps");
            near(SwordTravelTimeline.sourceFraction(distance * 2, distance), 1, 0, "overshoot clamps");
            // Receipts interpolate distance linearly; mapping must reproduce that
            // exact distance instead of advancing an unrelated local phase clock.
            for (int i = 0; i <= 1200; i++) {
                double progress = i / 1200.0;
                double source = SwordTravelTimeline.sourceFraction(progress * distance, distance);
                near(authoredTravel(source) * distance, progress * distance, 2e-15, "interpolated receipt round trip");
            }
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            near(SwordTravelTimeline.sourceFraction(invalid, 1), 0, 0, "invalid advance");
            near(SwordTravelTimeline.sourceFraction(.5, invalid), 0, 0, "invalid distance");
        }
        near(SwordTravelTimeline.sourceFraction(.5, 0), 0, 0, "stationary action has no travel clock");
        near(SwordTravelTimeline.sourceFraction(.5, -1), 0, 0, "negative distance rejected");
        near(SwordTravelTimeline.sourceFraction(Double.MAX_VALUE, .5), 1, 0, "finite extreme advance bounded");
        System.out.println("SwordTravelTimelineTest passed " + checks + " checks");
    }
}

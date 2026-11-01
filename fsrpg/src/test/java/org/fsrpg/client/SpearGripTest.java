package org.fsrpg.client;

import java.util.Map;
import org.joml.Matrix4f;

/** 0.6.1: the two hands slide along the pole instead of moving as one rigid block. */
public final class SpearGripTest {
    private static int checks, samples;

    public static void main(String[] args) {
        separationFollowsThePoles();
        workingTheWeaponOpensTheGrip();
        carriedPoleKeepsAuthoredSeparation();
        staysInsideReach();
        System.out.println("SpearGripTest passed: " + samples + " samples / " + checks + " checks");
    }

    /** A rig whose palms are 58 cm apart must report 58, not an invented value. */
    private static void separationFollowsThePoles() {
        for (float expected : new float[]{50, 58, 66}) {
            var world = hands(expected);
            near(SpearGrip.authored(world), expected, .001f, "Authored separation is measured off the rig");
            samples++;
        }
        near(SpearGrip.authored(Map.of()), SpearGrip.RESTING_CM, .001f, "A rig without hands falls back to the resting value");
    }

    /** Working the weapon must move the hands; a held pole must not. */
    private static void workingTheWeaponOpensTheGrip() {
        float resting = SpearGrip.solve(SpearGrip.RESTING_CM, 0, 0);
        near(resting, SpearGrip.RESTING_CM, .001f, "A stationary carry keeps the authored separation");
        float worked = SpearGrip.solve(SpearGrip.RESTING_CM, 450, 12);
        check(worked > resting + 2, "Working the weapon opens the grip: " + worked);
        check(worked <= SpearGrip.MAXIMUM_CM + .001f, "Grip stays inside its band: " + worked);
        // The change has to be continuous, or the arms would snap between frames. Slope is bounded
        // by the drive curve, so no single centimetre of travel may move the grip more than that.
        float previous = -1;
        for (float travel = 0; travel <= 600; travel += .5f) {
            float spacing = SpearGrip.solve(SpearGrip.RESTING_CM, travel, 0);
            if (previous > 0) check(spacing - previous < .5f, "Grip moves continuously: " + spacing);
            previous = spacing;
            samples++;
        }
    }

    /** An authored carry that already uses a wider or narrower hold is respected as the base. */
    private static void carriedPoleKeepsAuthoredSeparation() {
        for (float authored : new float[]{46, 58, 68}) {
            float solved = SpearGrip.solve(authored, 0, 0);
            near(solved, authored, .001f, "Authored carry is preserved at " + authored);
        }
        samples++;
    }

    /** Every solved separation must stay inside the band the arms and solver can serve. */
    private static void staysInsideReach() {
        check(SpearGrip.MINIMUM_CM < SpearGrip.RESTING_CM && SpearGrip.RESTING_CM < SpearGrip.MAXIMUM_CM,
                "Resting separation sits inside the band");
        near(SpearGrip.clamp(0), SpearGrip.MINIMUM_CM, .001f, "An impossible narrow grip is clamped");
        near(SpearGrip.clamp(1000), SpearGrip.MAXIMUM_CM, .001f, "An impossible wide grip is clamped");
        near(SpearGrip.clamp(Float.NaN), SpearGrip.RESTING_CM, .001f, "A non-finite grip falls back");
        for (float authored = SpearGrip.MINIMUM_CM; authored <= SpearGrip.MAXIMUM_CM; authored += 1) {
            for (float travel = 0; travel <= 500; travel += 25) {
                float spacing = SpearGrip.solve(authored, travel, travel * .5f);
                check(spacing >= SpearGrip.MINIMUM_CM - .001f && spacing <= SpearGrip.MAXIMUM_CM + .001f,
                        "Solved grip stays in band: " + spacing);
                samples++;
            }
        }
    }

    private static Map<String, Matrix4f> hands(float separationCm) {
        return Map.of("right_hand", new Matrix4f().translate(0, 0, 0),
                "left_hand", new Matrix4f().translate(0, 0, separationCm));
    }

    private static void near(float actual, float expected, float tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance, label + ": " + actual + " vs " + expected);
    }

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}

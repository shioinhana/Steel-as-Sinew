package org.fsrpg.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;

/**
 * 0.6.2: the aim reaches the legs. Aiming down folds both knees while the soles stay planted,
 * so the stance carries the weapon instead of the actor standing straight and leaning over it.
 */
public final class SpearStanceTest {
    private static int checks, samples;
    private static final Path ASSETS = Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");

    public static void main(String[] args) throws Exception {
        crouchFollowsTheAim();
        crouchStaysBounded();
        kneesFoldWithTheCrouch();
        solesStayPlanted();
        System.out.println("SpearStanceTest passed: " + samples + " samples / " + checks + " checks");
    }

    /** Descending aim must sink the body; raised aim may rise, and level aim must do neither. */
    private static void crouchFollowsTheAim() {
        near(SpearMotion.aimCrouchCm(0, 6), 0, .001, "Level aim does not move the pelvis");
        check(SpearMotion.aimCrouchCm(30, 6) > .5f, "Aiming down sinks the body: " + SpearMotion.aimCrouchCm(30, 6));
        check(SpearMotion.aimCrouchCm(-30, 6) < -.5f, "Aiming up rises: " + SpearMotion.aimCrouchCm(-30, 6));
        check(SpearMotion.aimCrouchCm(30, 6) > SpearMotion.aimCrouchCm(10, 6), "Steeper downward aim sinks further");
        check(SpearMotion.aimCrouchCm(-30, 6) < SpearMotion.aimCrouchCm(-10, 6), "Steeper upward aim rises further");
        samples++;
    }

    /** The value feeds a leg solve, so it must never be non-finite or exceed the configured bound. */
    private static void crouchStaysBounded() {
        near(SpearMotion.aimCrouchCm(Double.NaN, 6), 0, .001, "Non-finite pitch is ignored");
        near(SpearMotion.aimCrouchCm(90, 0), 0, .001, "Zero maximum disables the crouch");
        near(SpearMotion.aimCrouchCm(90, Float.NaN), 0, .001, "Non-finite maximum is ignored");
        float previous = Float.NEGATIVE_INFINITY;
        for (double pitch = -90; pitch <= 90; pitch += 1) {
            float crouch = SpearMotion.aimCrouchCm(pitch, 6);
            check(Float.isFinite(crouch), "Crouch is finite at pitch " + pitch);
            check(Math.abs(crouch) <= 6.001f, "Crouch stays within its bound at pitch " + pitch + ": " + crouch);
            // Outside the calibrated +-45 degrees the response saturates, so it may only be
            // non-decreasing there; inside it has to keep responding to the aim.
            check(crouch >= previous - .0001f, "Crouch never reverses in pitch at " + pitch);
            if (Math.abs(pitch) < 44) check(crouch > previous + .0001f, "Crouch still responds at pitch " + pitch);
            previous = crouch;
            samples++;
        }
    }

    /** The whole point: the knees actually bend when the aim sinks, and the pose stays valid. */
    private static void kneesFoldWithTheCrouch() throws Exception {
        ModelDocument spear = ModelLoader.load(ASSETS.resolve("spear.fsvoxel"));
        double level = kneeAt(spear, 0), down = kneeAt(spear, 35), up = kneeAt(spear, -35);
        check(down > level, "Aiming down bends the knee more than level: " + down + " vs " + level);
        check(up < level, "Aiming up straightens the knee: " + up + " vs " + level);
        samples++;
    }

    private static double kneeAt(ModelDocument spear, double pitch) {
        var supported = solve(spear, pitch);
        // The authored stance is asymmetric (one foot forward), so the two knees only have to be
        // valid, not equal.
        for (var leg : List.of(supported.right(), supported.left())) {
            double knee = leg.knee();
            check(Float.isFinite((float) knee) && knee >= 0 && knee < Math.PI,
                    "Knee stays valid at pitch " + pitch + ": " + knee);
        }
        return Math.max(supported.right().knee(), supported.left().knee());
    }

    /** A stance change must not lift a planted foot off the floor. */
    private static void solesStayPlanted() throws Exception {
        ModelDocument spear = ModelLoader.load(ASSETS.resolve("spear.fsvoxel"));
        for (double pitch = -45; pitch <= 45; pitch += 5) {
            var supported = solve(spear, pitch);
            for (var foot : List.of(supported.right(), supported.left()))
                near(foot.sole().y, 24, .0001, "Sole stays on the floor at pitch " + pitch);
            samples++;
        }
    }

    private static Locomotion.LowerBody solve(ModelDocument spear, double pitch) {
        float crouch = SpearMotion.aimCrouchCm(pitch, 6);
        var clip = spear.rig().clips().get(PoseLayers.DEFAULT_STANCE);
        var world = spear.worldMatrices(spear.rig().sampleLocal(List.of(clip), 0, Map.of()));
        return Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 1,
                0, 0, 0, 0, 1, .72f, 48, new PresentationMotion.Balance(0, 0, 0), 0, 0, crouch);
    }

    private static void near(double actual, double expected, double tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance, label + ": " + actual);
    }

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}

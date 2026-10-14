package org.fsrpg.client;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Directional inertia, breathing and guard silhouettes with the real exported rig. */
public final class PresentationMotionTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var motion = new PresentationMotion();
        near(motion.breathingSeconds(10, 1), 0, "actor breath starts at draw presentation");
        near(motion.breathingSeconds(30, 1), 1, "breathing advances across render ticks");
        near(motion.breathingSeconds(30, 1), 1, "armor and item samples share breath time");
        near(motion.breathingSeconds(90, 1), 4, "returning from an attack keeps the original breathing phase");
        near(motion.breathingSeconds(110, 2), 10, "breathing speed remains configurable");
        near(motion.breathingSeconds(2, 1), 0, "a restarted world clock resets breathing safely");
        motion.observePosition(10, 0, 0, 0, .2);
        motion.observePosition(11, 0, .28, 0, 0);
        near(motion.velocityZ(), .28, "position history survives reset engine interpolation fields");
        motion.observePosition(11, 0, .28, 0, 0);
        near(motion.velocityZ(), .28, "repeated render does not erase tick velocity");
        motion.observePosition(12, 100, 100, 0, 0);
        near(motion.velocityZ(), 0, "teleport rejected by position observer");
        motion.advance(0, 0, .28, 0, true, true, 1, 3);
        near(motion.forwardLean(), 0, "first render does not snap into lean");
        motion.advance(3, 0, .28, 0, true, true, 1, 3);
        check(motion.forwardLean() > .6 && motion.forwardLean() < .7, "acceleration eases toward the velocity");
        float before = motion.forwardLean();
        motion.advance(3, 0, -.28, 0, false, true, 1, 3);
        near(motion.forwardLean(), before, "skin/armor/item resubmission cannot advance inertia twice");
        for (int t = 4; t < 31; t++) motion.advance(t, 0, .28, 0, true, true, 1, 3);
        check(motion.forwardLean() > .999 && motion.runWeight() > .999, "sprinting reaches authored upright carry");
        motion.advance(32, 100, 100, 0, false, true, 1, 3);
        check(motion.forwardLean() < 1 && motion.lateralLean() == 0, "teleport does not introduce sideways tilt");
        for (int t = 33; t < 64; t++) motion.advance(t, 0, 0, 0, false, true, 0, 3);
        check(Math.abs(motion.forwardLean()) < .001 && motion.runWeight() == 0, "stopping returns to breathing stance smoothly");
        for (int yaw : new int[]{0, 90, 180, -90}) {
            double r = Math.toRadians(yaw);
            var facing = new PresentationMotion();
            for (int t = 0; t < 40; t++) facing.advance(t, -.28 * Math.sin(r), .28 * Math.cos(r), yaw, true, true, 1, 3);
            check(facing.forwardLean() > .999 && Math.abs(facing.lateralLean()) < .0001, "forward lean respects body yaw " + yaw);
        }
        var right = new PresentationMotion();
        for (int t = 0; t < 40; t++) right.advance(t, -.28, 0, 0, false, true, 1, 3);
        check(right.lateralLean() < -.999, "right movement tilts toward actor right (-FS X)");
        var diagonal = new PresentationMotion();
        for (int t = 0; t < 40; t++) diagonal.advance(t, -.28, -.28, 0, true, true, 1, 3);
        check(Math.hypot(diagonal.lateralLean(), diagonal.forwardLean()) <= 1.00001, "diagonal movement obeys total lean limit");
        for (int t = 40; t < 80; t++) diagonal.advance(t, .28, .28, 0, true, false, 1, 3);
        check(Math.abs(diagonal.lateralLean()) < .001 && Math.abs(diagonal.forwardLean()) < .001 && diagonal.runWeight() == 0, "airborne sprint clears grounded carry and tilt");

        ModelDocument model;
        try (var input = PresentationMotionTest.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/rapier.fsvoxel")) {
            model = ModelLoader.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var neutral = pose(model, "rapier_idle", 0);
        var neutralWorld = model.worldMatrices(neutral);
        var stride = PresentationMotion.stride(neutral, 2, 1, 1);
        var strideWorld = model.worldMatrices(stride);
        check(stride.get("body").position().y() < neutral.get("body").position().y(), "stride compresses torso slightly");
        check(strideWorld.get("right_leg").equals(neutralWorld.get("right_leg"), .00001f), "stride counter-sway does not displace grounded leg roots");
        check(strideWorld.get("head").transformDirection(new Vector3f(0,1,0)).normalize().dot(strideWorld.get("body").transformDirection(new Vector3f(0,1,0)).normalize()) > .9999, "head follows stride tilt instead of standing rigidly upright");
        var sprintTilt=model.worldMatrices(PresentationMotion.lean(neutral,0,18));
        check(sprintTilt.get("head").getTranslation(new Vector3f()).z - neutralWorld.get("head").getTranslation(new Vector3f()).z > 20,
                "full sprint creates a readable greater-than-20cm shoulder shift");
        for (float x : new float[]{-7, 0, 7}) for (float z : new float[]{-7, 0, 7}) {
            var leaning = PresentationMotion.lean(neutral, x, z);
            var world = model.worldMatrices(leaning);
            check(neutral.get("body").position().equals(leaning.get("body").position()), "lean pivots at the waist without sliding hips");
            check(world.get("right_leg").equals(neutralWorld.get("right_leg"), .00001f) && world.get("left_leg").equals(neutralWorld.get("left_leg"), .00001f), "lean leaves native gait roots unchanged");
            check(world.get("head").transformDirection(new Vector3f(0,1,0)).normalize().dot(world.get("body").transformDirection(new Vector3f(0,1,0)).normalize()) > .99999, "head and body lean together");
            near(world.get("right_forearm").getTranslation(new Vector3f()).distance(world.get("right_hand").getTranslation(new Vector3f())), 25, "lean preserves forearm length and attachment");
        }
        var stabilized=model.worldMatrices(PresentationMotion.lean(neutral,7,10,0));
        check(Math.abs(stabilized.get("head").getUnnormalizedRotation(new Quaternionf()).dot(neutralWorld.get("head").getUnnormalizedRotation(new Quaternionf()))) > .99999,"optional zero head-follow retains configurable stabilization");
        var shift=right.balance(6,1);
        check(shift.x() < -5.9 && Math.abs(shift.z())<.001,"pelvis offset follows actual movement");
        var shifted=PresentationMotion.shiftTorso(neutral,shift);
        near(shifted.get("body").position().x()-neutral.get("body").position().x(),shift.x(),"torso and pelvis use identical translation");
        var authored = new HashMap<>(neutral);
        var originalBody = neutral.get("body");
        authored.put("body", new Pose(originalBody.rotation(), new V(4, originalBody.position().y() - 3, 6), originalBody.scale()));
        var combinedHip = PresentationMotion.withAuthoredHipShift(authored, originalBody.position(), new PresentationMotion.Balance(2, -.1f, 3));
        near(combinedHip.x(), 6, "authored attack side shift and movement shift both carry the hips");
        near(combinedHip.z(), 9, "authored forward weight reaches the leg roots");
        near(Math.sqrt(combinedHip.x()*combinedHip.x() + Math.pow(75 + combinedHip.y(), 2) + combinedHip.z()*combinedHip.z()), 75,
                "hip height follows the planted-leg radius");
        authored.put("body", new Pose(originalBody.rotation(), new V(4, originalBody.position().y() + 3, 6), originalBody.scale()));
        var breathHip = PresentationMotion.withAuthoredHipShift(authored, originalBody.position(), new PresentationMotion.Balance(2, -.1f, 3));
        near(breathHip.y(), combinedHip.y(), "breathing rise never lifts the leg roots or soles");
        for (float dx : new float[]{-10, 0, 10}) for (float dz : new float[]{-8, 0, 8}) {
            authored.put("body", new Pose(originalBody.rotation(), new V(dx, originalBody.position().y(), dz), originalBody.scale()));
            var hip = PresentationMotion.withAuthoredHipShift(authored, originalBody.position(), new PresentationMotion.Balance(0, 0, 0));
            var plantedLeg = new Locomotion.PartPose(-1.9f, 12, 0, 0, .65f, 0);
            var planted = PresentationMotion.balanceLeg(plantedLeg, hip);
            Vector3f originalSole = plantedLeg.rotation().transform(new Vector3f(0, 12, 0)).add(plantedLeg.x(), plantedLeg.y(), plantedLeg.z());
            Vector3f shiftedSole = planted.rotation().transform(new Vector3f(0, 12, 0)).add(planted.x(), planted.y(), planted.z());
            check(originalSole.distance(shiftedSole) < .00001f, "joint weight shift plants a standing sole at " + dx + ", " + dz);
            var walkingLeg = new Locomotion.PartPose(-1.9f, 12, 0, .6f, -.12f, .035f);
            var walking = PresentationMotion.balanceLeg(walkingLeg, hip);
            Quaternionf tilt = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f(-hip.x(), 75 + hip.y(), hip.z()).normalize());
            Quaternionf restored = tilt.invert().mul(walking.rotation());
            check(Math.abs(restored.dot(walkingLeg.rotation())) > .99999f, "weight shift retains the native walking phase at " + dx + ", " + dz);
        }
        var inhale = pose(model, "rapier_idle", 1); var exhale = pose(model, "rapier_idle", 3);
        check(inhale.get("body").position().y() - exhale.get("body").position().y() > 1.7, "authored chest has visible restrained breathing");
        check(model.rig().clips().get("rapier_idle").duration() == 4, "breathing uses a natural four-second loop");
        near(PresentationMotion.breathing(neutral, inhale, 0).get("body").position().y(), neutral.get("body").position().y(), "zero breath scale disables rise");
        near(PresentationMotion.breathing(neutral, inhale, 2).get("body").position().y() - neutral.get("body").position().y(), 2.8, "breath amplitude is configurable");
        var run = model.worldMatrices(pose(model, "rapier_run", 0));
        uprightRunCarry(model);
        check(run.get("right_hand").transformDirection(HeldItemTransform.rapierBladeAxis()).normalize().y > .98, "running holds the official-item blade axis upright");
        check(run.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)).z > 35, "running palm remains ahead of chest");
        var light = model.worldMatrices(pose(model, "rapier_guard", 0));
        var heavy = model.worldMatrices(pose(model, "rapier_guard_heavy", 0));
        var lightAxis = light.get("right_hand").transformDirection(HeldItemTransform.rapierBladeAxis()).normalize();
        var heavyAxis = heavy.get("right_hand").transformDirection(HeldItemTransform.rapierBladeAxis()).normalize();
        check(lightAxis.x > .5 && lightAxis.y > .65 && heavyAxis.x < -.5 && heavyAxis.y > .65, "light and heavy guard blade diagonals face opposite upper corners");
        check(light.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)).x < -15
                && heavy.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)).x > 5, "guard hands use right-low and left-low positions");
        check(model.rig().clips().get("rapier_riposte").layer().equals("upper") && model.rig().clips().get("rapier_reprise").layer().equals("full"), "quick riposte and combined reprise have distinct movement layers");
        var a = model.worldMatrices(pose(model, "rapier_cross", .455));
        var b = model.worldMatrices(pose(model, "rapier_cross", .465));
        check(a.get("right_hand").getTranslation(new Vector3f()).distance(b.get("right_hand").getTranslation(new Vector3f())) > .5,
                "crosscut carries motion through its middle arc rather than stopping at a key");
        for (String weapon : new String[]{"rapier", "dual_blade", "spear"}) {
            ModelDocument weaponModel;
            try (var input = PresentationMotionTest.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/" + weapon + ".fsvoxel")) {
                weaponModel = ModelLoader.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            String idleAction = weapon.equals("dual_blade") ? "dual_idle_00" : weapon + "_idle";
            String carryAction = weapon.equals("dual_blade") ? "dual_run" : weapon + "_run";
            check(PoseLayers.breathingAction(idleAction), weapon + " idle participates in actor breathing");
            check(carryAction.equals(PoseLayers.runAction(weaponModel.rig(), idleAction)), weapon + " uses its own authored sprint carry");
            var weaponIdle = pose(weaponModel, idleAction, 0);
            var baseWorld = weaponModel.worldMatrices(weaponIdle);
            var movedWorld = weaponModel.worldMatrices(PresentationMotion.shiftTorso(
                    PresentationMotion.lean(weaponIdle, 7, 12), new PresentationMotion.Balance(4, -.15f, 3)));
            check(movedWorld.get("head").getTranslation(new Vector3f()).distance(baseWorld.get("head").getTranslation(new Vector3f())) > 16,
                    weapon + " walking moves head with torso lean and pelvis shift visibly");
            check(movedWorld.get("right_leg").equals(baseWorld.get("right_leg"), .00001f)
                    && movedWorld.get("left_leg").equals(baseWorld.get("left_leg"), .00001f),
                    weapon + " torso inertia preserves lower-body pose for native gait composition");
        }
        System.out.println("PresentationMotionTest passed: " + checks + " checks");
    }
    private static Map<String, Pose> pose(ModelDocument model, String action, double seconds) {
        return new PoseLayers.Playback(model.rig(), action).sample(seconds, 0, 0, 0, 0, .25f, 55).pose();
    }
    private static void uprightRunCarry(ModelDocument model) {
        for (float lateral : new float[]{-18, 0, 18}) for (float forward : new float[]{-18, 0, 18}) {
            var tilted = PresentationMotion.lean(pose(model, "rapier_run", .23), lateral, forward);
            var before = model.worldMatrices(tilted);
            Vector3f bladeBefore = before.get("right_hand").transformDirection(HeldItemTransform.rapierBladeAxis()).normalize();
            double angleBefore = Math.acos(Math.clamp(bladeBefore.y, -1, 1));
            for (float weight : new float[]{0, .25f, .5f, .75f, 1}) {
                var steady = PresentationMotion.uprightGrip(tilted, before, weight);
                var world = model.worldMatrices(steady);
                Vector3f blade = world.get("right_hand").transformDirection(HeldItemTransform.rapierBladeAxis()).normalize();
                double angle = Math.acos(Math.clamp(blade.y, -1, 1));
                check(Math.abs(angle - angleBefore * (1 - weight)) < .001,
                        "run blend steadily raises the actual blade across directional lean");
                check(steady.get("right_forearm").equals(tilted.get("right_forearm")),
                        "upright carry keeps the authored elbow hinge rather than bending it to compensate");
                check(steady.get("right_hand").equals(tilted.get("right_hand")),
                        "upright carry never adds wrist flexion or forearm twist");
                Vector3f wristAxis = steady.get("right_hand").fsQuaternion().transform(new Vector3f(0, -1, 0));
                check(Math.toDegrees(Math.acos(Math.clamp(-wristAxis.y, -1, 1))) <= 26,
                        "upright rapier carry keeps the configured anatomical wrist range");
                for (String unchanged : new String[]{"body", "head", "left_arm", "right_leg", "left_leg"})
                    check(world.get(unchanged).equals(before.get(unchanged), .0001f),
                            "blade stabilization retains body lean, head follow and native lower pose: " + unchanged);
                near(world.get("right_arm").getTranslation(new Vector3f()).distance(before.get("right_arm").getTranslation(new Vector3f())), 0,
                        "upper arm rotates about the attached shoulder");
                near(world.get("right_arm").getTranslation(new Vector3f()).distance(world.get("right_forearm").getTranslation(new Vector3f())), 37.5,
                        "shoulder correction retains upper arm length");
                near(world.get("right_forearm").getTranslation(new Vector3f()).distance(world.get("right_hand").getTranslation(new Vector3f())), 25,
                        "shoulder correction retains forearm length and continuous wrist attachment");
            }
        }
    }
    private static void near(double actual, double expected, String label) { check(Math.abs(actual - expected) < .0001, label); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}

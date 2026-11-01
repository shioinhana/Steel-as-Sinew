package org.fsrpg.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Source upper-body fidelity plus the real spear's lower-body support and action weights. */
public final class SwordMotionTest {
    private static int checks, samples;
    public static void main(String[] args) throws Exception {
        Path assets = Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        ModelDocument sword = ModelLoader.load(assets.resolve("legacy_rapier.fsvoxel"));
        ModelDocument spear = ModelLoader.load(assets.resolve("spear.fsvoxel"));
        check(SwordMotion.applies(sword) && !SwordMotion.applies(spear), "Only the imported sword uses the combination");
        var upperBones = List.of("head", "right_arm", "right_forearm", "right_hand", "right_item", "left_arm", "left_forearm", "left_hand", "left_item");
        var strokes = List.of("rapier_idle", "rapier_draw", "legacy_rapier_attack1_upper", "legacy_rapier_attack2_upper",
                "legacy_rapier_attack3_upper", "legacy_rapier_attack4_upper", "legacy_rapier_rapier_thrust_hit_upper",
                "legacy_rapier_rapier_thrust_miss_upper", "legacy_rapier_rapier_dodge_forward", "rapier_guard");
        for (String id : strokes) {
            var clip = sword.rig().clips().get(id);
            check(clip != null, "Source stroke present " + id);
            int frames = Math.max(60, (int) Math.ceil(clip.duration() * 60));
            for (int n = 0; n <= frames; n++) {
                double time = clip.duration() * n / frames;
                Map<String, Pose> old = SwordMotion.sampleUpper(sword, clip, time, 1.13, 0, 0);
                Map<String, Pose> authoredStroke = sword.rig().sampleLocal(List.of(clip), time, Map.of());
                if (!SwordMotion.source(clip).equals("static_idle"))
                    for (String bone : upperBones) if (clip.tracks().containsKey(bone) && !bone.equals("head"))
                        check(old.get(bone).equals(authoredStroke.get(bone)), "Stroke preserves imported source joint " + id + " / " + bone);
                Map<String, Matrix4f> oldWorld = sword.worldMatrices(old);
                Map<String, Pose> waist = SwordMotion.waist(sword, old);
                Map<String, Matrix4f> waistWorld = sword.worldMatrices(waist);
                for (String bone : upperBones) check(oldWorld.get(bone).equals(waistWorld.get(bone), .0002f),
                        "Waist conversion retains the original upper world " + id + " / " + bone);
                near(oldWorld.get("body").transformPosition(new Vector3f(0, 43.75f, 0)).distance(
                        waistWorld.get("body").transformPosition(new Vector3f(0, 75, 0))), 0, .0002,
                        "Torso skin top retains its original source point");
                var lower = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE)
                        .sample(0, 0, 0, 0, -23, .25f, 55);
                var lowerWorld = spear.worldMatrices(lower.pose());
                var combined = SwordMotion.lower(sword, waist, lowerWorld);
                var combinedWorld = sword.worldMatrices(combined);
                for (String bone : upperBones) check(combinedWorld.get(bone).equals(waistWorld.get(bone), .0002f),
                        "Spear lower cannot alter the old sword upper " + bone);
                for (String side : List.of("right", "left")) check(combinedWorld.get(side + "_leg").equals(lowerWorld.get(side + "_leg"), .0002f),
                        "Sword uses actual current spear authored foot " + side);
                samples++;
            }
        }
        var idle = sword.rig().clips().get("rapier_idle");
        var original = SwordMotion.sampleUpper(sword, idle, 1.13, 1.13, 0, 0);
        var authored = sword.rig().sampleLocal(List.of(idle), 1.13, Map.of());
        for (String side : List.of("right", "left")) for (String joint : List.of("_arm", "_forearm", "_hand", "_item"))
            check(original.get(side + joint).equals(authored.get(side + joint)), "READY retains the original static sword joint " + side + joint);
        supportedMovement(sword, spear, original);
        System.out.println("SwordMotionTest passed: " + samples + " source samples / " + checks + " checks");
    }

    private static void supportedMovement(ModelDocument sword, ModelDocument spear, Map<String, Pose> original) {
        var upper = SwordMotion.waist(sword, original);
        var lower = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE).sample(0, 0, 0, 0, 0, .25f, 55);
        var combined = SwordMotion.lower(sword, upper, spear.worldMatrices(lower.pose()));
        double worstUpperPosition = 0, worstGripPosition = 0, maximumDrop = 0;
        double minimumLift = Double.POSITIVE_INFINITY;
        for (float actorYaw : new float[]{-90, 0, 90, 180})
            for (float dx : new float[]{-1, 0, 1}) for (float forward : new float[]{-1, 0, 1}) {
            if (dx == 0 && forward == 0) continue;
            float length = (float) Math.hypot(dx, forward);
            float x = dx / length, z = forward / length;
            double yaw = Math.toRadians(actorYaw);
            double vx = (x * Math.cos(yaw) - z * Math.sin(yaw)) * .28;
            double vz = (x * Math.sin(yaw) + z * Math.cos(yaw)) * .28;
            PresentationMotion motion = new PresentationMotion();
            var turn = new SpearTurn.State();
            for (int t = 0; t <= 60; t++) motion.advance(t, vx, vz, actorYaw, true, true, 1, 1.4f);
            near(motion.lateralLean(), x, .0001, "Actual velocity is body-local lateral, independent of camera");
            near(motion.forwardLean(), z, .0001, "Actual velocity is body-local forward, independent of camera");
            float previousPhase = -1;
            float greatestLift = 0;
            for (int frame = 0; frame < 180; frame++) {
                double tick = 60 + frame / 3.;
                motion.observePosition(frame / 3, tick * vx, tick * vz, vx, vz);
                float phase = motion.gaitPhase(tick, 1, 0, SwordMotion.MAXIMUM_STRIDE_SCALE);
                // The live sword path keeps all upper joints at the authored values; only the
                // common support translation moves them. Neither sprint nor attack adds a lean.
                var pose = combined;
                var world = sword.worldMatrices(pose);
                var hips = PresentationMotion.withAuthoredHipShift(pose, SwordMotion.waistRest(), new PresentationMotion.Balance(0, 0, 0));
                var supported = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                        phase, .16f, 1, motion.lateralLean(), motion.forwardLean(), SwordMotion.MAXIMUM_STRIDE_SCALE, 48, hips,
                        pose.get("body").position().y() - SwordMotion.waistRest().y(), motion.gaitLane());
                supported = Locomotion.turn(supported, turn.advance(tick,
                        -SwordMotion.bodyYaw(sword, pose) * SwordMotion.STANCE_FOLLOW, actorYaw, 1), 1);
                var finalPose = PresentationMotion.shiftTorso(pose, new PresentationMotion.Balance(0, -supported.pelvisDropCm(), 0));
                var finalWorld = sword.worldMatrices(finalPose);
                float drop = supported.pelvisDropCm();
                maximumDrop = Math.max(maximumDrop, drop);
                for (String bone : List.of("body", "head", "right_arm", "right_forearm", "right_hand", "right_item",
                        "left_arm", "left_forearm", "left_hand", "left_item")) {
                    Matrix4f expected = new Matrix4f(world.get(bone));
                    expected.m31(expected.m31() - drop);
                    check(finalWorld.get(bone).equals(expected, .0003f), "Only common vertical support translates source upper " + bone);
                    worstUpperPosition = Math.max(worstUpperPosition, expected.getTranslation(new Vector3f())
                            .distance(finalWorld.get(bone).getTranslation(new Vector3f())));
                }
                for (String side : List.of("right", "left")) {
                    Matrix4f originalGrip = new Matrix4f(world.get(side + "_hand")).invert().mul(world.get(side + "_item"));
                    Matrix4f finalGrip = new Matrix4f(finalWorld.get(side + "_hand")).invert().mul(finalWorld.get(side + "_item"));
                    check(originalGrip.equals(finalGrip, .0003f), "Native ItemStack retains original hand-local grip " + side);
                    worstGripPosition = Math.max(worstGripPosition, originalGrip.getTranslation(new Vector3f())
                            .distance(finalGrip.getTranslation(new Vector3f())));
                }
                near(supported.directionX(), x, .00001, "Turning pelvis cannot redirect side step");
                near(supported.directionForward(), z, .00001, "Turning pelvis cannot redirect forward/back step");
                check(supported.right().support() == 1 || supported.left().support() == 1, "At least one foot supports the body");
                var surface = new TorsoDeformation(finalWorld.get("body"), supported);
                var r = supported.right().pose(); var l = supported.left().pose();
                var pelvis = new Vector3f((r.x() + l.x()) * .5f, (r.y() + l.y()) * .5f, (r.z() + l.z()) * .5f);
                for (float width : new float[]{4, 4.25f, 4.5f}) for (float depth : new float[]{2, 2.25f, 2.5f}) {
                    var waistband = surface.point(width, 12, depth).mul(16);
                    var expectedWaist = new Quaternionf().rotationY(supported.pelvisYaw()).transform(new Vector3f(width, 0, depth)).add(pelvis);
                    near(waistband.distance(expectedWaist), 0, .0003, "Skin/clothing/armor waistband stays attached to pelvis");
                    var point = finalWorld.get("body").transformPosition(new Vector3f(width * 6.25f, 75, -depth * 6.25f));
                    point.set(point.x / 6.25f, (150 - point.y) / 6.25f, -point.z / 6.25f);
                    near(surface.point(width, 0, depth).mul(16).distance(point), 0, .0003, "Rigid source chest stays unchanged for all layers");
                }
                for (var foot : List.of(supported.right(), supported.left())) {
                    check(Float.isFinite(foot.knee()), "Finite supported knee");
                    check(foot.knee() >= 0 && foot.knee() < Math.PI, "Positive supported knee");
                    if (foot.support() == 1) near(foot.sole().y, 24, .00001, "Planted foot stays on floor");
                    greatestLift = Math.max(greatestLift, 24 - foot.sole().y);
                    var p = foot.pose();
                    var deformed = new LegDeformation(p.x(), p.y(), p.z(), p.xRot(), p.yRot(), p.zRot(), foot.knee(), true, supported.pelvisYaw());
                    near(deformed.point(0, 12, 0).mul(16).distance(foot.sole()), 0, .002, "Rendered boot reaches actual ground target");
                }
                if (frame > 0 && phase != previousPhase) samples++;
                previousPhase = phase;
            }
            minimumLift = Math.min(minimumLift, greatestLift);
        }
        check(minimumLift > .5, "All eight body-local directions visibly lift recovering foot");
        check(maximumDrop < 15, "Walking support is a modest shared vertical translation: " + maximumDrop + " cm");
        System.out.printf("SWORD SOURCE upper-world error after common Y translation %.7f cm; hand/item %.7f cm; support drop max %.3f cm; all-direction foot lift min %.3f px%n",
                worstUpperPosition, worstGripPosition, maximumDrop, minimumLift);
        near(Locomotion.lowerWeight(1, false, .25f), 0, .00001, "Upper sword strokes retain full current movement");
        near(Locomotion.lowerWeight(1, true, .25f), .25, .00001, "Full actions retain current full-body movement weight");
    }
    private static void near(double actual, double expected, double tolerance, String label) { check(Math.abs(actual - expected) <= tolerance, label + ": " + actual); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}

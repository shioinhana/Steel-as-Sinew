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
            for (int n = 0; n <= 60; n++) {
                double time = clip.duration() * n / 60;
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
        var baseWorld = sword.worldMatrices(combined);
        for (float dx : new float[]{-1, 0, 1}) for (float forward : new float[]{-1, 0, 1}) {
            if (dx == 0 && forward == 0) continue;
            float length = (float) Math.hypot(dx, forward);
            float x = dx / length, z = forward / length;
            PresentationMotion motion = new PresentationMotion();
            for (int t = 0; t <= 60; t++) motion.advance(t, x * .28, z * .28, 0, true, true, 1, 1.4f);
            var lean = motion.applyLean(combined, SwordMotion.MAXIMUM_MOVEMENT_LEAN, 1, 1);
            var leanWorld = sword.worldMatrices(lean);
            Quaternionf before = baseWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            Quaternionf after = leanWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            double angle = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(before.dot(after)), -1, 1)));
            check(angle <= 4.01, "All directional lean remains at most four degrees");
            for (String side : List.of("right", "left")) for (String joint : List.of("_arm", "_forearm", "_hand"))
                check(lean.get(side + joint).equals(upper.get(side + joint)), "Movement carries the original sword arm joint");
            var balance = motion.balance(SwordMotion.MAXIMUM_HIP_SHIFT_CM, 1);
            check(Math.hypot(balance.x(), balance.z()) <= 1.50001, "Small movement hip shift");
            float previousPhase = -1;
            for (int frame = 0; frame < 180; frame++) {
                double tick = 60 + frame / 3.;
                motion.observePosition(frame, tick * x * .28, tick * z * .28, x * .28, z * .28);
                float phase = motion.gaitPhase(tick, 1, 0, .72f);
                var stride = PresentationMotion.stride(lean, phase, 1, SwordMotion.STRIDE_BODY_SCALE);
                var world = sword.worldMatrices(stride);
                var hips = PresentationMotion.withAuthoredHipShift(stride, SwordMotion.waistRest(), balance);
                var supported = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                        phase, .16f, 1, x, z, .72f, 48, hips,
                        stride.get("body").position().y() - SwordMotion.waistRest().y(), motion.gaitLane());
                for (var foot : List.of(supported.right(), supported.left())) {
                    check(Float.isFinite(foot.knee()), "Finite supported knee");
                    check(foot.knee() >= 0 && foot.knee() < Math.PI, "Positive supported knee");
                    if (foot.support() == 1) near(foot.sole().y, 24, .00001, "Planted foot stays on floor");
                }
                if (frame > 0 && phase != previousPhase) samples++;
                previousPhase = phase;
            }
        }
        near(Locomotion.lowerWeight(1, false, .25f), 0, .00001, "Upper sword strokes retain full current movement");
        near(Locomotion.lowerWeight(1, true, .25f), .25, .00001, "Full actions retain current full-body movement weight");
    }
    private static void near(double actual, double expected, double tolerance, String label) { check(Math.abs(actual - expected) <= tolerance, label + ": " + actual); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}

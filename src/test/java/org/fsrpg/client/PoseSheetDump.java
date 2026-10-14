package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Dev tool: replays server-timed weapon sequences through the real sword/dual pose code and
 * writes per-frame world joints for tools/render-pose-sheet.py. Not a pass/fail test. */
public final class PoseSheetDump {
    record Step(String action, int windup, int active, int recovery, double holdTicks) {}
    private static final double HZ = 60;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/pose-sheet/poses.json");
        Path assets = Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        ModelDocument sword = ModelLoader.load(assets.resolve("legacy_rapier.fsvoxel"));
        ModelDocument dual = ModelLoader.load(assets.resolve("legacy_dual_blade.fsvoxel"));
        ModelDocument spear = ModelLoader.load(assets.resolve("spear.fsvoxel"));
        var root = new JsonObject();
        root.add("sword_combo", swordSequence(sword, spear, List.of(
                new Step("rapier_idle", 0, 0, 0, 10),
                new Step("legacy_rapier_attack1_upper", 3, 4, 1, 0),
                new Step("legacy_rapier_attack2_upper", 3, 4, 1, 0),
                new Step("legacy_rapier_attack3_upper", 3, 4, 1, 0),
                new Step("legacy_rapier_attack4_upper", 4, 5, 1, 0),
                new Step("rapier_idle", 0, 0, 0, 14))));
        root.add("sword_thrust", swordSequence(sword, spear, List.of(
                new Step("rapier_idle", 0, 0, 0, 8),
                new Step("legacy_rapier_rapier_thrust_hit_upper", 7, 2, 2, 0),
                new Step("rapier_idle", 0, 0, 0, 14))));
        root.add("dual_combo", dualSequence(dual, List.of(
                new Step("dual_idle_11", 0, 0, 0, 8),
                new Step("legacy_dual_blade_attack_1_upper", 3, 6, 1, 0),
                new Step("dual_idle_01", 0, 0, 0, 4),
                new Step("legacy_dual_blade_attack2_upper", 3, 6, 1, 0),
                new Step("dual_idle_00", 0, 0, 0, 4),
                new Step("legacy_dual_blade_attack3_upper", 3, 6, 1, 0),
                new Step("dual_idle_02", 0, 0, 0, 4),
                new Step("legacy_dual_blade_attack4_upper", 3, 6, 1, 0),
                new Step("dual_idle_22", 0, 0, 0, 12))));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, new GsonBuilder().create().toJson(root));
        System.out.println("PoseSheetDump wrote " + out.toAbsolutePath());
    }

    /** Mirrors PlayerAnimations.frame for a stationary, grounded stock-skin sword actor. */
    static JsonArray swordSequence(ModelDocument sword, ModelDocument spear, List<Step> steps) {
        var frames = new JsonArray();
        var lowerPlayback = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE);
        Map<String, Pose> lastPose = null, transitionFrom = null, lastBase = null;
        PoseLayers.OffsetDecay swordDecay = null;
        Map<String, Pose> swordPending = null, swordVelocity = null;
        double tick = 0, transitionStarted = 0, transitionTicks = 0;
        for (var step : steps) {
            var clip = sword.rig().clips().get(step.action());
            int total = step.windup() + step.active() + step.recovery();
            boolean attack = total > 0;
            double length = attack ? total : step.holdTicks();
            var knots = attack ? PoseRemap.knots(sword.rig(), clip) : null;
            var hold = PoseRemap.holdPose(sword.rig(), clip);
            if (lastPose != null) {
                transitionFrom = lastPose; transitionStarted = tick;
                transitionTicks = PoseLayers.transitionTicks(clip, .12, step.windup(), attack);
                if (attack) transitionTicks = PoseLayers.entryWindupTicks(transitionTicks, 0, step.windup());
                swordPending = transitionFrom;
                swordDecay = null;
                swordVelocity = lastBase;
            }
            double begin = tick;
            for (; tick < begin + length - 1e-9; tick += 20 / HZ) {
                double elapsed = tick - begin;
                var attackPlan = attack ? PoseRemap.plan(knots, clip, elapsed, step.windup(), step.active(), step.recovery())
                        : new PoseRemap.Attack(tick / 20, 0);
                double seconds = attackPlan.clipTime();
                var upper = SwordMotion.sampleUpper(sword, clip, seconds, tick / 20, 0, 0);
                if (attackPlan.anticipation() > 0 && hold != null) upper = PoseRemap.anticipate(upper, hold, attackPlan.anticipation());
                upper = SwordMotion.waist(sword, upper);
                var lower = lowerPlayback.sample(tick / 20, tick / 20, tick, 0, 0, .25f, 55);
                Map<String, Pose> pose = SwordMotion.stance(sword, upper, spear.worldMatrices(lower.pose()),
                        SwordMotion.bodyYaw(sword, upper));
                if (attack) pose = SwordMotion.strikeLean(sword, pose, SwordMotion.STRIKE_LEAN * Math.min(1, (float) elapsed / 3f));
                var supported = Locomotion.supported(sword.worldMatrices(pose).get("right_leg"), sword.worldMatrices(pose).get("left_leg"), 1, 0, 0, 0, 0, 1, .72f, 48,
                        PresentationMotion.withAuthoredHipShift(pose, SwordMotion.waistRest(), new PresentationMotion.Balance(0, 0, 0)),
                        pose.get("body").position().y() - SwordMotion.waistRest().y(), 0);
                var base = PresentationMotion.shiftTorso(pose, new PresentationMotion.Balance(0, -supported.pelvisDropCm(), 0));
                lastBase = base;
                if (transitionFrom != null && transitionTicks > 0) {
                    float weight = PoseLayers.transitionWeight(tick, transitionStarted, transitionTicks);
                    if (weight < 1) {
                        if (weight <= 0) pose = lastPose == null ? base : lastPose;
                        else {
                            if (swordPending != null) { swordDecay = PoseLayers.OffsetDecay.of(swordPending, base, swordVelocity); swordPending = null; }
                            pose = swordDecay == null ? base : swordDecay.blend(base, weight);
                        }
                    } else { transitionFrom = null; swordDecay = null; pose = base; }
                } else pose = base;
                lastPose = pose;
                var world = sword.worldMatrices(pose);
                frames.add(frame(tick, step.action(), attack ? phase(elapsed, step) : "READY", world, supported, rapierBlade(world.get("right_item")), null));
            }
        }
        return frames;
    }

    /** Mirrors LegacyMotion.sample plus the new offset-decay bridge for a stationary actor. */
    static JsonArray dualSequence(ModelDocument dual, List<Step> steps) {
        var frames = new JsonArray();
        for (var sheet : dualStream(dual, steps)) {
            var world = dual.worldMatrices(sheet.worldPose());
            frames.add(frame(sheet.tick(), sheet.action(), sheet.phase(), world, null,
                    dualBlade(world.get("right_item"), false), dualBlade(world.get("left_item"), true)));
        }
        return frames;
    }

    /** The final pose of one dumped frame, for continuity checks over a whole sequence. */
    record SheetFrame(String action, String phase, double tick, Map<String, Pose> worldPose) {}

    /** Replays a dual sequence and returns the local pose of every frame. */
    static List<SheetFrame> dualStream(ModelDocument dual, List<Step> steps) {
        var frames = new java.util.ArrayList<SheetFrame>();
        double tick = 0;
        Map<String, Pose> lastBase = null, pending = null;
        PoseLayers.OffsetDecay decay = null;
        double started = 0, duration = 0;
        String current = "";
        Map<String, Pose> previousPose = null;
        for (var step : steps) {
            var clip = dual.rig().clips().get(step.action());
            int total = step.windup() + step.active() + step.recovery();
            boolean attack = total > 0;
            double length = attack ? total : step.holdTicks();
            if (!step.action().equals(current)) {
                current = step.action();
                if (lastBase != null) {
                    pending = lastBase; decay = null;
                    started = tick;
                    duration = PoseLayers.transitionTicks(clip, .12, step.windup(), attack);
                }
            }
            double begin = tick;
            String idle = attack ? "dual_idle_11" : step.action();
            for (; tick < begin + length - 1e-9; tick += 20 / HZ) {
                double elapsed = tick - begin;
                double seconds = attack ? PoseRemap.warp(dual.rig(), clip).clipTime(elapsed / total) : tick / 20;
                var pose = new HashMap<>(dual.rig().sampleLocal(List.of(dual.rig().clips().get(idle)), tick / 20, Map.of()));
                if (attack) {
                    var sampled = dual.rig().sampleLocal(List.of(clip), seconds, Map.of());
                    for (String id : clip.tracks().keySet()) pose.put(id, sampled.get(id));
                }
                Map<String, Pose> base = Map.copyOf(pose);
                if (pending != null) { decay = PoseLayers.OffsetDecay.of(pending, base, lastBase); pending = null; }
                lastBase = base;
                if (decay != null && duration > 0) {
                    float weight = PoseLayers.transitionWeight(tick, started, duration);
                    if (weight <= 0) pose = previousPose == null ? new HashMap<>(base) : new HashMap<>(previousPose);
                    else pose = new HashMap<>(decay.blend(base, weight));
                    if (weight >= 1) decay = null;
                }
                previousPose = Map.copyOf(pose);
                frames.add(new SheetFrame(step.action(), attack ? phase(elapsed, step) : "READY", tick, previousPose));
            }
        }
        return frames;
    }

    static String phase(double elapsed, Step step) {
        return elapsed < step.windup() ? "WINDUP" : elapsed < step.windup() + step.active() ? "ACTIVE" : "RECOVERY";
    }

    static JsonObject frame(double tick, String action, String phase, Map<String, Matrix4f> world,
                            Locomotion.LowerBody lower, Vector3f[] blade, Vector3f[] leftBlade) {
        var f = new JsonObject();
        f.addProperty("tick", tick); f.addProperty("action", action); f.addProperty("phase", phase);
        var bones = new JsonObject();
        for (String id : List.of("body", "head", "right_arm", "right_forearm", "right_hand", "left_arm", "left_forearm", "left_hand", "right_leg", "left_leg", "right_foreleg", "left_foreleg")) {
            var m = world.get(id); if (m == null) continue;
            var a = new JsonArray(); float[] v = new float[16]; m.get(v);
            for (float x : v) a.add(x);
            bones.add(id, a);
        }
        f.add("bones", bones);
        if (lower != null) {
            var legs = new JsonObject();
            for (var side : List.of("right", "left")) {
                var leg = side.equals("right") ? lower.right() : lower.left();
                var o = new JsonObject();
                var p = leg.pose();
                o.add("hip", vec(fs(new Vector3f(p.x(), p.y(), p.z()))));
                o.add("sole", vec(fs(leg.sole())));
                o.addProperty("yaw", -p.yRot());
                legs.add(side, o);
            }
            f.add("legs", legs);
        }
        if (blade != null) f.add("blade", pair(blade));
        if (leftBlade != null) f.add("leftBlade", pair(leftBlade));
        return f;
    }

    private static JsonArray pair(Vector3f[] blade) { var a = new JsonArray(); a.add(vec(blade[0])); a.add(vec(blade[1])); return a; }
    private static JsonArray vec(Vector3f v) { var a = new JsonArray(); a.add(v.x); a.add(v.y); a.add(v.z); return a; }
    /** Native pixels (head origin, Y down, -Z forward) to FS centimeters. */
    static Vector3f fs(Vector3f nativePx) { return new Vector3f(nativePx.x * 6.25f, 150 - nativePx.y * 6.25f, -nativePx.z * 6.25f); }
    /** Native meters to FS centimeters. */
    static Vector3f fsMeters(Vector3f m) { return new Vector3f(m.x * 100, 150 - m.y * 100, -m.z * 100); }

    static Vector3f[] rapierBlade(Matrix4f item) {
        if (item == null) return null;
        var m = HeldItemTransform.legacyLocator(item).mul(HeldItemTransform.legacyRapierItem()).translate(-.5f, -.5f, -.5f);
        return new Vector3f[]{fsMeters(m.transformPosition(new Vector3f(2 / 16f, 2 / 16f, .5f))),
                fsMeters(m.transformPosition(new Vector3f(15 / 16f, 15 / 16f, .5f)))};
    }

    static Vector3f[] dualBlade(Matrix4f item, boolean left) {
        if (item == null) return null;
        float deg = (float) (Math.PI / 180);
        var display = left ? new Matrix4f().translation(0, .25f, .03125f).rotateXYZ(0, 90 * deg, -55 * deg).scale(.85f).translate(-.5f, -.5f, -.5f)
                : HeldItemTransform.standardDisplay();
        var m = HeldItemTransform.legacyLocator(item).mul(HeldItemTransform.legacyDualItem()).mul(display);
        return new Vector3f[]{fsMeters(m.transformPosition(new Vector3f(2 / 16f, 2 / 16f, .5f))),
                fsMeters(m.transformPosition(new Vector3f(15 / 16f, 15 / 16f, .5f)))};
    }

}

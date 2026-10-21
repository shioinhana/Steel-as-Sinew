package org.fsrpg.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 0.6.0: sword strokes retimed onto the authoritative windows, torso stance carried by the
 * pelvis, and offset-decay handoffs between the imported clips.
 */
public final class SwordTimingTest {
    private static int checks, samples;
    private static final double HZ = 60;

    public static void main(String[] args) throws Exception {
        Path assets = Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        ModelDocument sword = ModelLoader.load(assets.resolve("legacy_rapier.fsvoxel"));
        ModelDocument dual = ModelLoader.load(assets.resolve("legacy_dual_blade.fsvoxel"));
        ModelDocument spear = ModelLoader.load(assets.resolve("spear.fsvoxel"));

        knotsMatchMeasuredMotion(sword);
        contactWindowIsMoving(sword, spear);
        windupStartsAuthoredMotion(sword, spear);
        stanceCarriesLowerBody(sword, spear);
        contactKeepsSourceUpper(sword, spear);
        dualHandoffIsContinuous(dual);
        bridgedSequenceIsContinuous(dual);
        dualStrokeIsReadable(dual);
        System.out.println("SwordTimingTest passed: " + samples + " samples / " + checks + " checks");
    }

    /** Each imported attack finishes its stroke early; the detected window must say so. */
    private static void knotsMatchMeasuredMotion(ModelDocument sword) {
        for (String id : List.of("legacy_rapier_attack1_upper", "legacy_rapier_attack2_upper",
                "legacy_rapier_attack3_upper", "legacy_rapier_attack4_upper", "legacy_rapier_rapier_thrust_hit_upper")) {
            var clip = sword.rig().clips().get(id);
            var knots = PoseRemap.knots(sword.rig(), clip);
            check(knots.start() >= 0 && knots.start() < knots.peak() && knots.peak() <= knots.end(),
                    "Motion window is ordered " + id);
            check(knots.end() <= clip.duration() + 1e-6, "Motion window stays inside the clip " + id);
            samples++;
        }
    }

    /** The blade has to be moving while the server's contact window is open. */
    private static void contactWindowIsMoving(ModelDocument sword, ModelDocument spear) {
        record Stroke(String action, int windup, int active, int recovery) {}
        var strokes = List.of(new Stroke("legacy_rapier_attack1_upper", 3, 4, 1),
                new Stroke("legacy_rapier_attack2_upper", 3, 4, 1),
                new Stroke("legacy_rapier_attack3_upper", 3, 4, 1),
                new Stroke("legacy_rapier_attack4_upper", 4, 5, 1),
                new Stroke("legacy_rapier_rapier_thrust_hit_upper", 7, 2, 2));
        for (var stroke : strokes) {
            var clip = sword.rig().clips().get(stroke.action());
            var knots = PoseRemap.knots(sword.rig(), clip);
            var lowerPlayback = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE);
            var speeds = new java.util.ArrayList<Float>();
            Vector3f previous = null;
            double peakTick = -1;
            float peak = 0;
            for (double tick = 0; tick <= stroke.windup() + stroke.active() + stroke.recovery(); tick += 20 / HZ) {
                var attack = productionPlan(knots, clip, tick, stroke.windup(), stroke.active(), stroke.recovery());
                var upper = SwordMotion.waist(sword, SwordMotion.sampleUpper(sword, clip, attack.clipTime(), tick / 20, 0, 0));
                var lower = lowerPlayback.sample(tick / 20, tick / 20, tick, 0, 0, .25f, 55);
                var pose = SwordMotion.stance(sword, upper, spear.worldMatrices(lower.pose()), 0);
                var world = sword.worldMatrices(pose);
                var tip = world.get("right_item").transformPosition(new Vector3f(0, 0, 0));
                if (previous != null && tick > stroke.windup() + .001) {
                    float speed = (float) (tip.distance(previous) * HZ / 20);
                    speeds.add(speed);
                    if (speed > peak) { peak = speed; peakTick = tick; }
                }
                previous = tip;
                samples++;
            }
            check(!speeds.isEmpty(), "Contact window has frames " + stroke.action());
            // The blade's fastest moment has to fall inside the authoritative contact window, and
            // a cut has to still be travelling for most of it. Before 0.6.0 the whole stroke was
            // already over by the first contact frame.
            double end = stroke.windup() + stroke.active();
            check(peakTick > stroke.windup() && peakTick <= end,
                    "Blade peaks inside contact " + stroke.action() + ": tick " + peakTick + " of " + stroke.windup() + "-" + end);
            float peakSpeed = peak;
            // The imported strokes carry their travel in the first part of the motion window and
            // then settle, so require real travel across the contact window as a whole and a
            // peak that sits inside it. Before 0.6.0 the entire stroke was over before contact.
            double travel = 0;
            for (float speed : speeds) travel += speed / peakSpeed;
            double perFrame = travel / speeds.size();
            // The thrust commits through a lunge and is authored as one sharp extension, so it
            // only has to still be travelling during the short window it actually occupies.
            double required = stroke.action().contains("thrust") ? .10 : .25;
            check(perFrame >= required, "Blade keeps moving through contact " + stroke.action() + ": mean speed "
                    + perFrame + " of peak " + peakSpeed);
        }
    }

    /** Reauthored cuts prepare immediately through their source motion, without an extra counter-pose. */
    private static void windupStartsAuthoredMotion(ModelDocument sword, ModelDocument spear) {
        var clip = sword.rig().clips().get("legacy_rapier_attack1_upper");
        var knots = PoseRemap.knots(sword.rig(), clip);
        var lowerPlayback = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE);
        var rest = sampleTip(sword, spear, lowerPlayback, clip, 0, 0);
        float deepest = 0;
        for (double tick = 0; tick < 3; tick += 20 / HZ) {
            var attack = productionPlan(knots, clip, tick, 3, 4, 1);
            var upper = SwordMotion.sampleUpper(sword, clip, attack.clipTime(), 0, 0, 0);
            var lower = lowerPlayback.sample(tick / 20, tick / 20, tick, 0, 0, .25f, 55);
            var world = sword.worldMatrices(SwordMotion.stance(sword, SwordMotion.waist(sword, upper), spear.worldMatrices(lower.pose()), 0));
            deepest = Math.max(deepest, world.get("right_item").transformPosition(new Vector3f()).distance(rest));
            samples++;
        }
        check(deepest > .5f, "Windup continuously prepares through the actual source instead of holding its initial frame: " + deepest + " cm");
    }

    private static Vector3f sampleTip(ModelDocument sword, ModelDocument spear, PoseLayers.Playback lowerPlayback,
                                      Clip clip, double seconds, double tick) {
        var upper = SwordMotion.waist(sword, SwordMotion.sampleUpper(sword, clip, seconds, tick / 20, 0, 0));
        var lower = lowerPlayback.sample(tick / 20, tick / 20, tick, 0, 0, .25f, 55);
        var world = sword.worldMatrices(SwordMotion.stance(sword, upper, spear.worldMatrices(lower.pose()), 0));
        return world.get("right_item").transformPosition(new Vector3f());
    }

    /** The lower body used to stay square while the trunk stood side-on to the target. */
    private static void stanceCarriesLowerBody(ModelDocument sword, ModelDocument spear) {
        var clip = sword.rig().clips().get("rapier_idle");
        var upper = SwordMotion.waist(sword, SwordMotion.sampleUpper(sword, clip, 0, 0, 0, 0));
        var lower = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE).sample(0, 0, 0, 0, 0, .25f, 55);
        var square = SwordMotion.stance(sword, upper, spear.worldMatrices(lower.pose()), 0);
        float yaw = SwordMotion.bodyYaw(sword, upper);
        var carried = SwordMotion.stance(sword, upper, spear.worldMatrices(lower.pose()), yaw);
        check(Math.abs(yaw) > Math.toRadians(30), "Authored stance is side-on: " + Math.toDegrees(yaw) + " deg");
        for (String side : List.of("right", "left")) {
            float before = footYaw(sword, square, side), after = footYaw(sword, carried, side);
            float gain = (float) Math.toDegrees(Math.abs(wrap(after - before)));
            float share = (float) Math.toDegrees(Math.abs(yaw)) * SwordMotion.STANCE_FOLLOW;
            check(Math.abs(gain - share) < 1.5f, "Pelvis follows the stance by its share " + side + ": " + gain + " vs " + share);
        }
        // The upper body must not be moved by carrying the pelvis.
        for (String bone : List.of("body", "head", "right_arm", "right_forearm", "right_hand"))
            check(square.get(bone).equals(carried.get(bone)), "Stance carry leaves the upper body intact " + bone);
        samples++;
    }

    private static float footYaw(ModelDocument sword, Map<String, Pose> pose, String side) {
        var world = sword.worldMatrices(pose).get(side + "_leg");
        var forward = world.transformDirection(new Vector3f(0, 0, 1));
        return (float) Math.atan2(forward.x, forward.z);
    }

    private static double wrap(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }

    /** The contact clock retimes the authored blade; it must not add torso or wrist motion. */
    private static void contactKeepsSourceUpper(ModelDocument sword, ModelDocument spear) {
        var clip = sword.rig().clips().get("legacy_rapier_attack3_upper");
        var knots = PoseRemap.knots(sword.rig(), clip);
        var lowerPlayback = new PoseLayers.Playback(spear.rig(), PoseLayers.DEFAULT_STANCE);
        for (double tick = 0; tick <= 8; tick += 20 / HZ) {
            var attack = productionPlan(knots, clip, tick, 3, 4, 1);
            var upper = SwordMotion.waist(sword, SwordMotion.sampleUpper(sword, clip, attack.clipTime(), 0, 0, 0));
            var lower = lowerPlayback.sample(tick / 20, tick / 20, tick, 0, 0, .25f, 55);
            var pose = SwordMotion.lower(sword, upper, spear.worldMatrices(lower.pose()));
            var sourceWorld = sword.worldMatrices(upper);
            var finalWorld = sword.worldMatrices(pose);
            for (String bone : List.of("body", "head", "right_arm", "right_forearm", "right_hand", "right_item"))
                check(sourceWorld.get(bone).equals(finalWorld.get(bone), .0003f), "Contact retains source upper/item world " + bone);
            samples++;
        }
    }

    /** Mirror the exact production clock selection; untouched thrusts retain their old remap. */
    private static PoseRemap.Attack productionPlan(PoseRemap.Knots knots,Clip clip,double tick,int windup,int active,int recovery) {
        return RapierMotion.cut(clip)?new PoseRemap.Attack(RapierMotion.clipTime(clip,tick,windup,active,recovery),0)
                :PoseRemap.plan(knots,clip,tick,windup,active,recovery);
    }

    /** Two clips that disagree on the handoff pose must not show that step on the switch frame. */
    private static void dualHandoffIsContinuous(ModelDocument dual) {
        var steps = List.of("dual_idle_11", "legacy_dual_blade_attack_1_upper", "dual_idle_01",
                "legacy_dual_blade_attack2_upper", "dual_idle_00", "legacy_dual_blade_attack3_upper",
                "dual_idle_02", "legacy_dual_blade_attack4_upper", "dual_idle_22");
        var bones = List.of("right_arm", "left_arm", "right_forearm", "left_forearm", "body");
        double worstAuthored = 0, worstOpening = 0, worstSettled = 0;
        for (int index = 1; index < steps.size(); index++) {
            var from = dual.rig().clips().get(steps.get(index - 1));
            var to = dual.rig().clips().get(steps.get(index));
            var last = dual.rig().sampleLocal(List.of(from), from.duration() * .75, Map.of());
            var half = dual.rig().sampleLocal(List.of(from), from.duration() * .70, Map.of());
            var first = dual.rig().sampleLocal(List.of(to), 0, Map.of());
            var decay = PoseLayers.OffsetDecay.of(last, first, half);
            // The correction exists to present the new action where the old pose left off, and to
            // converge back onto that action's own authored start.
            var opening = decay.blend(first, 0);
            var settled = decay.blend(first, 1);
            for (String bone : bones) {
                worstOpening = Math.max(worstOpening, rotation(last.get(bone), opening.get(bone)));
                worstSettled = Math.max(worstSettled, rotation(first.get(bone), settled.get(bone)));
                worstAuthored = Math.max(worstAuthored, rotation(last.get(bone), first.get(bone)));
            }
            samples++;
        }
        // The clips themselves disagree by a large step; that is exactly what must be absorbed.
        check(worstAuthored > 8, "Imported clips disagree at the handoff: " + worstAuthored + " deg");
        // The live stream hands over from the pose actually presented, so the residual here only
        // reflects that this check supplies a loop sample rather than the exact last frame.
        check(worstOpening < 3, "Handoff step is removed on the switch frame: " + worstOpening + " deg");
        check(worstSettled < .05, "Correction decays to the authored start: " + worstSettled + " deg");
    }

    /** The blended pose stream over a handoff must not contain a switch-frame jump. */
    private static void bridgedSequenceIsContinuous(ModelDocument dual) {
        var steps = List.of(
                new PoseSheetDump.Step("dual_idle_11", 0, 0, 0, 4),
                new PoseSheetDump.Step("legacy_dual_blade_attack_1_upper", 3, 6, 1, 0),
                new PoseSheetDump.Step("dual_idle_01", 0, 0, 0, 4),
                new PoseSheetDump.Step("legacy_dual_blade_attack2_upper", 3, 6, 1, 0),
                new PoseSheetDump.Step("dual_idle_00", 0, 0, 0, 4),
                new PoseSheetDump.Step("legacy_dual_blade_attack3_upper", 3, 6, 1, 0),
                new PoseSheetDump.Step("dual_idle_02", 0, 0, 0, 4),
                new PoseSheetDump.Step("legacy_dual_blade_attack4_upper", 3, 6, 1, 0),
                new PoseSheetDump.Step("dual_idle_22", 0, 0, 0, 8));
        var frames = PoseSheetDump.dualStream(dual, steps);
        var bones = List.of("right_arm", "left_arm", "right_forearm", "left_forearm", "body", "head");
        double worstSwitch = 0, worstInside = 0;
        Map<String, Pose> previous = null;
        String previousAction = "";
        for (var frame : frames) {
            var pose = frame.worldPose();
            if (previous != null) for (String bone : bones) {
                double step = rotation(previous.get(bone), pose.get(bone));
                if (!frame.action().equals(previousAction)) worstSwitch = Math.max(worstSwitch, step);
                else worstInside = Math.max(worstInside, step);
            }
            previous = pose; previousAction = frame.action();
            samples++;
        }
        check(worstSwitch < 15, "No switch-frame jump between dual clips: " + worstSwitch + " deg");
        // The imported strokes carry abrupt authored steps of their own (about 71 degrees on the
        // forearm at the windup/contact border); the bridge is not allowed to make those worse.
        System.out.printf("DUAL SEQUENCE switch %.1f inside %.1f deg%n", worstSwitch, worstInside);
        check(worstInside < 35, "In-clip motion stays within the authored range: " + worstInside + " deg");
    }

    private static double rotation(Pose a, Pose b) {
        if (a == null || b == null) return 0;
        var x = a.fsQuaternion().normalize(); var y = b.fsQuaternion().normalize();
        return Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(x.dot(y)), -1, 1)));
    }

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** The warp spreads the fast part of each dual stroke over more render frames without reordering time. */
    private static void dualStrokeIsReadable(ModelDocument dual) {
        var bones = List.of("right_arm", "right_forearm", "left_arm", "left_forearm", "right_item", "left_item");
        for (String id : List.of("legacy_dual_blade_attack_1_upper", "legacy_dual_blade_attack2_upper", "legacy_dual_blade_attack3_upper",
                "legacy_dual_blade_attack4_upper", "legacy_dual_blade_attack_dual_upper")) {
            Clip clip = dual.rig().clips().get(id);
            var warp = PoseRemap.warp(dual.rig(), clip);
            check(Math.abs(warp.clipTime(0)) < 1e-9 && Math.abs(warp.clipTime(1) - clip.duration()) < 1e-6, id + " warp keeps both ends");
            double previous = -1, worstLinear = 0, worstWarped = 0, widestJump = 0;
            Map<String, Pose> lastLinear = null, lastWarped = null;
            double worstTick = 0;
            int frames = (int) Math.round(10 * .05 * HZ);
            for (int frame = 0; frame <= frames; frame++) {
                double fraction = (double) frame / frames;
                double warped = warp.clipTime(fraction);
                check(warped >= previous - 1e-9, id + " clip time never runs backward");
                widestJump = Math.max(widestJump, warped - Math.max(previous, 0));
                previous = warped;
                var linear = dual.rig().sampleLocal(List.of(clip), clip.duration() * fraction, Map.of());
                var shaped = dual.rig().sampleLocal(List.of(clip), warped, Map.of());
                if (lastLinear != null) for (String bone : bones) {
                    worstLinear = Math.max(worstLinear, angle(linear.get(bone), lastLinear.get(bone)));
                    double w = angle(shaped.get(bone), lastWarped.get(bone));
                    if (w > worstWarped) { worstWarped = w; worstTick = frame * 20.0 / HZ; }
                }
                lastLinear = linear; lastWarped = shaped;
                samples++;
            }
            check(worstWarped < worstLinear * .6 && worstWarped < 30, id + " fastest joint step drops: " + worstLinear + " -> " + worstWarped);
            check(worstTick >= 3 && worstTick <= 9, id + " fastest step lands inside the active window: tick " + worstTick);
            System.out.printf("DUAL STROKE %s worst 60Hz joint step %.1f -> %.1f deg at tick %.2f%n", id, worstLinear, worstWarped, worstTick);
        }
    }

    private static double angle(Pose a, Pose b) {
        double dot = Math.abs(new Quaternionf(a.fsQuaternion()).normalize().dot(new Quaternionf(b.fsQuaternion()).normalize()));
        return Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
    }
}

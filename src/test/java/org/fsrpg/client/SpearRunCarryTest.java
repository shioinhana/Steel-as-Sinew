package org.fsrpg.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Real stock/slim FK, complete-pole clearance, closed official grip and free running arm. */
public final class SpearRunCarryTest {
    private static int checks;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static float angle(Quaternionf a, Quaternionf b) {
        return (float)Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(a.dot(b)), 0, 1)));
    }
    public static void main(String[] args) throws Exception {
        state();
        var model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        returnTarget(model);
        cadence(model);
        stride(model);
        for (boolean slim : List.of(false, true)) for (float yaw : new float[]{-40, 0, 40}) for (float pitch : new float[]{-35, 0, 35}) {
            Map<String, Pose> source = new PoseLayers.Playback(model.rig(), "spear_idle").sample(.3, .3, 6, 0, 0, .25f, 55).pose();
            if (slim) {
                source = new HashMap<>(source);
                for (String side : List.of("right", "left")) {
                    var old = source.get(side + "_arm"); var p = old.position();
                    source.put(side + "_arm", new Pose(old.rotation(), new V(p.x() + (side.equals("right") ? 3.125f : -3.125f), p.y(), p.z()), old.scale()));
                }
            }
            var raw = SpearMotion.readySource(model.worldMatrices(source), -12, 36, 50);
            var reference = SpearAimPose.apply(model, source, new AttackAim.Point(yaw, pitch, 10), 0, 1.62f,
                    raw.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)), null, null, new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true));
            var previous = reference;
            var runPlayback = new PoseLayers.Playback(model.rig(), "spear_run");
            Vector3f firstFree = null, farFree = null;
            for (int frame = 0; frame <= 240; frame++) {
                float weight = frame / 240f;
                var movingSource = new HashMap<>(source);
                var authoredRun = runPlayback.sample(frame / 60., frame / 60., frame / 3., .25f, 0, .25f, 55).pose();
                movingSource.put("body", authoredRun.get("body"));
                var pose = SpearRunCarry.apply(model, movingSource, reference, weight, frame / 12f);
                var world = model.worldMatrices(pose); var measured = SpearRunCarry.measure(world);
                String where = "skin=" + slim + " yaw=" + yaw + " pitch=" + pitch + " frame=" + frame;
                check(measured.clearShaft(), "entire actual pole stays outside torso/head " + where + " rear=" + measured.chestRear() + " axis=" + measured.chestAxis() + " body=" + pose.get("body").rotation() + " head=" + pose.get("head").rotation());
                var shoulder = world.get("right_arm").getTranslation(new Vector3f());
                var elbow = world.get("right_forearm").getTranslation(new Vector3f());
                check(Math.abs(shoulder.distance(elbow) - 37.5f) < .002f, "upper bone never stretches " + where);
                var wristAt = world.get("right_hand").getTranslation(new Vector3f());
                check(Math.abs(elbow.distance(wristAt) - 25) < .003f, "physical forearm stays 25cm " + where);
                check(Math.abs(wristAt.distance(measured.rear()) - 6.25f) < .003f, "independent palm offset stays 6.25cm " + where);
                var hand = world.get("right_hand");
                check(Math.abs(measured.axis().dot(hand.transformDirection(new Vector3f(0, -1, 0)).normalize())) < .00001f,
                        "shaft crosses the real fist at ninety degrees " + where);
                for (String bone : List.of("right_arm", "right_forearm", "right_hand"))
                    check(angle(previous.get(bone).fsQuaternion(), pose.get(bone).fsQuaternion()) < 13,
                            "analytic elbow branch never swaps " + where + " " + bone + " step=" + angle(previous.get(bone).fsQuaternion(), pose.get(bone).fsQuaternion()));
                var wrist = pose.get("right_hand").fsQuaternion();
                check(angle(new Quaternionf(), wrist) <= 150.2f, "independent wrist stays within its rotation stop " + where);
                if (weight > .97) check(measured.freeHandDistanceCm() > 55, "left hand truly leaves the shaft " + where);
                if (frame == 240) {
                    check(measured.chestRear().z < -29 && measured.chestRear().y < 27, "right palm stays low behind waist " + where);
                    check(measured.chestAxis().y < -.23f && measured.chestAxis().z < -.94f && measured.axis().y < -.03f,
                            "official spear tip points behind and below, never up " + where);
                    var chestElbow = new Matrix4f(world.get("body")).invert().transformPosition(new Vector3f(elbow));
                    check(chestElbow.z < -25 && chestElbow.y < 60, "right elbow reaches behind below its shoulder " + where);
                    check(angle(new Quaternionf(), wrist) < 40, "steady carry wrist is relaxed " + where);
                    var first = SpearRunCarry.apply(model, source, reference, 1, 0);
                    var second = SpearRunCarry.apply(model, source, reference, 1, (float)Math.PI / .6662f);
                    firstFree = SpearRunCarry.palm(model.worldMatrices(first), "left");
                    farFree = SpearRunCarry.palm(model.worldMatrices(second), "left");
                    check(firstFree.distance(farFree) > 50 && farFree.z > firstFree.z + 40,
                            "left hand swings forward with the opposite forward foot " + where);
                    check(first.get("left_forearm").rotation().x() < -60 && second.get("left_forearm").rotation().x() < -60,
                            "free elbow flexes anatomically forward " + where);
                    check(SpearRunCarry.palm(model.worldMatrices(first), "right").distance(SpearRunCarry.palm(model.worldMatrices(second), "right")) > 1,
                            "carried spear has small stride inertia rather than a welded grip " + where);
                    officialGrip(world, where);
                }
                previous = pose;
            }
        }
        System.out.println("SpearRunCarryTest passed: " + checks + " checks; real stock/slim source, 18 starting aims, 240-step low trailing route, separate forearm/wrist/palm, complete pole clearance and opposite-phase left run arm");
    }
    private static void cadence(org.fsvoxel.render.ModelDocument model) {
        for (int fps : new int[]{30, 60, 120}) {
            float peakJoint = 0, peakPalm = 0, peakAxis = 0;
            for (float yaw : new float[]{-40, 0, 40}) for (float pitch : new float[]{-35, 0, 35}) {
                var source = new PoseLayers.Playback(model.rig(), "spear_idle").sample(.3, .3, 6, 0, 0, .25f, 55).pose();
                var raw = SpearMotion.readySource(model.worldMatrices(source), -12, 36, 50);
                var reference = SpearAimPose.apply(model, source, new AttackAim.Point(yaw, pitch, 10), 0, 1.62f,
                        raw.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)), null, null,
                        new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true));
                var state = new SpearRunCarry.State(); state.remember(reference);
                var previous = reference; var previousWorld = model.worldMatrices(previous);
                for (int frame = 0; frame <= fps * 3; frame++) {
                    double tick = frame * 20.0 / fps;
                    boolean run = tick < 18 || (tick >= 20 && tick < 22) || (tick >= 32 && tick < 38);
                    float weight = state.advance(tick, "READY", run, true, run ? .28 : 0);
                    var pose = weight > 0 ? SpearRunCarry.apply(model, source, reference, weight, (float)tick) : reference;
                    var world = model.worldMatrices(pose);
                    check(SpearAimPose.hasClearShaft(world), "real cadence start/stop keeps the whole shaft clear");
                    for (String bone : List.of("right_arm", "right_forearm", "right_hand", "left_arm", "body"))
                        peakJoint = Math.max(peakJoint, angle(previous.get(bone).fsQuaternion(), pose.get(bone).fsQuaternion()));
                    peakPalm = Math.max(peakPalm, SpearRunCarry.palm(world, "right").distance(SpearRunCarry.palm(previousWorld, "right")));
                    var axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                    var oldAxis = previousWorld.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                    peakAxis = Math.max(peakAxis, (float)Math.toDegrees(Math.acos(Math.clamp(axis.dot(oldAxis), -1, 1))));
                    previous = pose; previousWorld = world;
                }
            }
            check(peakJoint < 2400f / fps, "no elbow/wrist branch exchange at real " + fps + "Hz: " + peakJoint);
            check(peakPalm < 1400f / fps, "bounded actual grip travel at " + fps + "Hz: " + peakPalm);
            check(peakAxis < 1500f / fps, "bounded official pole turn at " + fps + "Hz: " + peakAxis);
            System.out.println("Carry cadence " + fps + "Hz: max joint=" + peakJoint + "deg, grip=" + peakPalm + "cm, pole=" + peakAxis + "deg");
        }
    }
    private static void stride(org.fsvoxel.render.ModelDocument model) {
        var source = new PoseLayers.Playback(model.rig(), "spear_idle").sample(.3, .3, 6, 0, 0, .25f, 55).pose();
        var raw = SpearMotion.readySource(model.worldMatrices(source), -12, 36, 50);
        var reference = SpearAimPose.apply(model, source, new AttackAim.Point(0, 0, 10), 0, 1.62f,
                raw.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)), null, null,
                new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true));
        var world = model.worldMatrices(source);
        for (float lateral : new float[]{-1, 0, 1}) for (float forward : new float[]{-1, 0, 1}) {
            for (int frame = 0; frame < 180; frame++) {
                float phase = frame * (float)(2 * Math.PI) / 180 / .6662f;
                var pose = SpearRunCarry.apply(model, source, reference, 1, phase, lateral, forward);
                var carried = model.worldMatrices(pose); var measured = SpearRunCarry.measure(carried);
                check(measured.clearShaft() && measured.axis().y < -.03f, "whole cycle keeps world spear tip below grip despite chest lean");
                check(measured.chestRear().z < -29 && measured.chestRear().y < 27, "every stride retains a low rear hand");
                var lower = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                        phase, .28f, 1, lateral, forward, .8f, 55, new PresentationMotion.Balance(0, 0, 0));
                check(Math.abs(lower.right().pose().y() - lower.left().pose().y()) < .001f,
                        "both supported legs continue to share one pelvis");
                officialGrip(carried, "stride frame=" + frame);
            }
        }
        var back = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                0, .28f, 1, 0, 1, .8f, 55, new PresentationMotion.Balance(0, 0, 0));
        var front = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                (float)Math.PI / .6662f, .28f, 1, 0, 1, .8f, 55, new PresentationMotion.Balance(0, 0, 0));
        check(front.right().sole().z < back.right().sole().z - 8,
                "left-arm forward phase is the actual opposite right-foot forward phase");
    }
    private static void returnTarget(org.fsvoxel.render.ModelDocument model) {
        Map<String, Pose> source = new PoseLayers.Playback(model.rig(), "spear_idle").sample(.3, .3, 6, 0, 0, .25f, 55).pose();
        var raw = SpearMotion.readySource(model.worldMatrices(source), -12, 36, 50);
        var seed = raw.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
        var limits = new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true);
        var down = SpearAimPose.apply(model, source, new AttackAim.Point(-40, 35, 10), 0, 1.62f, seed, null, null, limits);
        var up = SpearAimPose.apply(model, source, new AttackAim.Point(40, -35, 10), 0, 1.62f, seed, null, null, limits);
        for (var start : List.of(down, up)) for (int fps : new int[]{30, 60, 120}) {
            var goal = start == down ? up : down;
            var state = new SpearRunCarry.State(); state.remember(start);
            for (int frame = 0; frame <= fps * SpearRunCarry.ENTER_TICKS / 20; frame++) state.advance(frame * 20.0 / fps, "READY", true, true, .28);
            check(!state.returning(), "steady back carry never requests normal aim solving");
            var previous = model.worldMatrices(SpearRunCarry.apply(model, source, start, 1, 0));
            var goalWorld = model.worldMatrices(goal);
            boolean returned = false;
            for (int frame = 1; frame <= fps; frame++) {
                float weight = state.advance(SpearRunCarry.ENTER_TICKS + frame * 20.0 / fps, "READY", false, true, 0);
                // Production refreshes the pure normal target during the visible
                // return, instead of discovering the changed cursor at weight 0.
                state.remember(goal);
                var pose = weight > 0 ? SpearRunCarry.apply(model, source, state.reference(), weight, 0) : goal;
                var world = model.worldMatrices(pose);
                check(SpearAimPose.hasClearShaft(world), "changed return aim keeps complete pole outside the actor at " + fps + " Hz");
                if (weight == 0) {
                    var priorAxis = previous.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                    var targetAxis = goalWorld.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                    float finalAngle = (float)Math.toDegrees(Math.acos(Math.clamp(priorAxis.dot(targetAxis), -1, 1)));
                    check(finalAngle < 8, "last return frame joins the new aim smoothly at " + fps + " Hz: " + finalAngle);
                    check(SpearRunCarry.palm(previous, "right").distance(SpearRunCarry.palm(goalWorld, "right")) < 5,
                            "last return frame joins the new right grip within five centimeters at " + fps + " Hz");
                    check(SpearRunCarry.leftShaftDistance(world) < .01f, "changed return aim ends in the exact real two-hand grip");
                    returned = true; break;
                }
                check(state.returning(), "only visible return frames request normal aim solving");
                previous = world;
            }
            check(returned, "return completes after a changed aim at " + fps + " Hz");
        }
    }
    private static void officialGrip(Map<String, Matrix4f> world, String where) {
        float deg = (float)Math.PI / 180;
        var display = new Matrix4f().translation(0, 2f / 16, 2f / 16).rotateXYZ(5 * deg, 270 * deg, -40 * deg)
                .scale(1.7f, 1.7f, .85f).translate(-.5f, -.5f, -.5f);
        var actual = HeldItemTransform.spearCarrier(world.get("right_hand")).mul(display);
        var nativeSpace = new Matrix4f().translation(0, 1.5f, 0).scale(.01f, -.01f, -.01f);
        var expected = nativeSpace.transformPosition(SpearRunCarry.palm(world, "right"));
        check(actual.transformPosition(new Vector3f(.81f, .19f, .5f)).distance(expected) < .00001f,
                "official ItemStack rear handle stays inside real right fist " + where);
        var axis = nativeSpace.transformDirection(world.get("right_hand").transformDirection(new Vector3f(0, 0, 1))).normalize();
        check(actual.transformDirection(new Vector3f(-1, 1, 0)).normalize().distance(axis) < .00001f,
                "official ItemStack full shaft follows the solved back carry axis " + where);
    }
    private static void state() {
        for (boolean sprint : new boolean[]{false, true}) for (boolean ground : new boolean[]{false, true})
            for (double speed : new double[]{0, .12, .2, Double.NaN, 3})
                check(SpearRunCarry.eligible("READY", sprint, ground, speed) == (sprint && ground && speed == .2), "speed alone or air cannot enter carry");
        for (int fps : new int[]{30, 60, 120}) {
            var state = new SpearRunCarry.State();
            state.advance(0, "READY", true, true, .28);
            for (int frame = 1; frame <= fps * SpearRunCarry.ENTER_TICKS / 20; frame++) {
                double tick = frame * 20.0 / fps;
                float first = state.advance(tick, "READY", true, true, .28);
                check(state.advance(tick, "READY", true, true, .28) == first, "skin/armor/item same frame cannot advance carry");
            }
            check(state.weight() > .999, "carry settles at its declared duration at every render rate");
            check(state.advance(SpearRunCarry.ENTER_TICKS + 1, "READY", false, false, 0) > .999, "air retains takeoff carry");
            for (int frame = 1; frame <= fps; frame++) state.advance(SpearRunCarry.ENTER_TICKS + 1 + frame * 20.0 / fps, "READY", false, true, 0);
            check(state.weight() == 0, "stopping returns to two hands at its declared duration");
        }
        for (String phase : List.of("WINDUP", "ACTIVE", "RECOVERY", "GUARDING", "STUNNED", "DODGING", "SHEATHING")) {
            var state = new SpearRunCarry.State(); state.advance(0, "READY", true, true, .28); state.advance(2, "READY", true, true, .28); state.advance(4, "READY", true, true, .28);
            check(state.advance(6.1, phase, true, true, .28) == 0, "combat action owns hands immediately: " + phase);
        }
    }
}

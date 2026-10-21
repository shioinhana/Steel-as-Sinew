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
    private static float minimumElevation = 90, maximumElevation, minimumCrossY = Float.POSITIVE_INFINITY, maximumCrossY;
    private static float minimumWorldAxisY = 1, minimumRearZ, maximumRearZ = -100, minimumFreeDistance = Float.POSITIVE_INFINITY;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static float angle(Quaternionf a, Quaternionf b) {
        return (float)Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(a.dot(b)), 0, 1)));
    }
    public static void main(String[] args) throws Exception {
        state();
        var model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        returnTarget(model);
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
                check(measured.clearShaft(), "entire actual pole stays outside torso/head " + where + " rear=" + measured.chestRear() + " axis=" + measured.chestAxis() + " headRear=" + new Matrix4f(world.get("head")).invert().transformPosition(new Vector3f(measured.rear())) + " headAxis=" + new Matrix4f(world.get("head")).invert().transformDirection(new Vector3f(measured.axis())) + " body=" + pose.get("body").rotation() + " head=" + pose.get("head").rotation());
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
                independentGeometry(world, weight, slim, where);
                supportedWaist(world, frame / 12f, where);
                if (frame == 0) check(SpearRunCarry.leftShaftDistance(world) < .01f, "zero carry preserves the exact normal two-hand hold " + where);
                var wrist = pose.get("right_hand").fsQuaternion();
                check(angle(new Quaternionf(), wrist) <= 150.2f, "independent wrist stays within its rotation stop " + where);
                if (weight > .97) check(measured.freeHandDistanceCm() > 55, "left hand truly leaves the shaft " + where);
                if (frame == 240) {
                    check(measured.chestRear().z < -30 && measured.chestRear().y > 15 && measured.chestRear().y < 50, "right palm stays behind the right waist " + where);
                    check(measured.chestAxis().x > .55f && measured.chestAxis().y > .5f && Math.abs(measured.chestAxis().z) < .15f && measured.axis().y > .35f,
                            "official spear tip rises diagonally across the back toward the left shoulder " + where);
                    var chestElbow = new Matrix4f(world.get("body")).invert().transformPosition(new Vector3f(elbow));
                    check(chestElbow.z < -20 && chestElbow.y < 75, "right elbow reaches behind below its shoulder " + where);
                    check(angle(new Quaternionf(), wrist) < 60, "steady carry wrist is relaxed " + where);
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
            for (int frame = 0; frame < 180; frame++) {
                float phase = frame * (float)(2 * Math.PI) / 180 / .6662f;
                var world = model.worldMatrices(SpearRunCarry.apply(model, source, reference, 1, phase));
                String where = "full cycle slim=" + slim + " yaw=" + yaw + " pitch=" + pitch + " frame=" + frame;
                check(SpearAimPose.hasClearShaft(world), "full starting-aim cycle clears complete pole " + where);
                diagonal(SpearRunCarry.measure(world), where);
                independentGeometry(world, 1, slim, where);
                supportedWaist(world, phase, where);
                officialGrip(world, where);
            }
        }
        cadence(model);
        System.out.println("Diagonal measurements: elevation=" + minimumElevation + ".." + maximumElevation
                + "deg, center-back crossing y=" + minimumCrossY + ".." + maximumCrossY + "cm, rear z=" + minimumRearZ + ".." + maximumRearZ
                + "cm, world axis y >= " + minimumWorldAxisY + ", free palm distance >= " + minimumFreeDistance + "cm");
        System.out.println("SpearRunCarryTest passed: " + checks + " checks; real stock/slim source, 18 starting aims, 240-step diagonal back route, complete stock/slim gait cycles and cadence, independent chest/head/13 lumbar sections/free arm, exact official grip");
    }
    private static void cadence(org.fsvoxel.render.ModelDocument model) {
        for (int fps : new int[]{30, 60, 120}) {
            float peakJoint = 0, peakPalm = 0, peakAxis = 0;
            String peakWhere = "";
            for (boolean slim : List.of(false, true)) for (float yaw : new float[]{-40, 0, 40}) for (float pitch : new float[]{-35, 0, 35}) {
                var source = new PoseLayers.Playback(model.rig(), "spear_idle").sample(.3, .3, 6, 0, 0, .25f, 55).pose();
                source = skin(source, slim);
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
                    independentGeometry(world, weight, slim, "cadence=" + fps + " tick=" + tick);
                    supportedWaist(world, (float)tick, "cadence=" + fps + " tick=" + tick);
                    for (String bone : List.of("right_arm", "right_forearm", "right_hand", "left_arm", "body")) {
                        float step = angle(previous.get(bone).fsQuaternion(), pose.get(bone).fsQuaternion());
                        if (step > peakJoint) { peakJoint = step; peakWhere = "slim=" + slim + " " + bone + " tick=" + tick + " weight=" + weight + " yaw=" + yaw + " pitch=" + pitch; }
                    }
                    peakPalm = Math.max(peakPalm, SpearRunCarry.palm(world, "right").distance(SpearRunCarry.palm(previousWorld, "right")));
                    var axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                    var oldAxis = previousWorld.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                    peakAxis = Math.max(peakAxis, (float)Math.toDegrees(Math.acos(Math.clamp(axis.dot(oldAxis), -1, 1))));
                    previous = pose; previousWorld = world;
                }
            }
            System.out.println("Cadence peak " + fps + "Hz: " + peakWhere + " joint=" + peakJoint + " grip=" + peakPalm + " axis=" + peakAxis);
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
                diagonal(measured, "directional stride frame=" + frame);
                independentGeometry(carried, 1, false, "directional stride");
                check(measured.clearShaft() && measured.axis().y > .35f, "whole cycle keeps the spear inclined above the left shoulder despite chest lean");
                check(measured.chestRear().z < -30 && measured.chestRear().y > 15 && measured.chestRear().y < 50, "every stride retains the right rear hand");
                var lower = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                        phase, .28f, 1, lateral, forward, .8f, 55, new PresentationMotion.Balance(0, 0, 0));
                check(Math.abs(lower.right().pose().y() - lower.left().pose().y()) < .001f,
                        "both supported legs continue to share one pelvis");
                waist(carried, lower, "directional stride frame=" + frame);
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
    private static Map<String, Pose> skin(Map<String, Pose> source, boolean slim) {
        if (!slim) return source;
        var result = new HashMap<>(source);
        for (String side : List.of("right", "left")) {
            var old = source.get(side + "_arm"); var p = old.position();
            result.put(side + "_arm", new Pose(old.rotation(), new V(p.x() + (side.equals("right") ? 3.125f : -3.125f), p.y(), p.z()), old.scale()));
        }
        return result;
    }
    private static void diagonal(SpearRunCarry.Measurements measured, String where) {
        var rear = measured.chestRear(); var axis = measured.chestAxis();
        float elevation = (float)Math.toDegrees(Math.asin(Math.clamp(axis.y, -1, 1)));
        var crossing = new Vector3f(rear).fma(-rear.x / axis.x, axis);
        check(axis.x > .55f && axis.y > .5f && Math.abs(axis.z) < .15f && elevation >= 30 && elevation <= 60,
                "shaft is a 30..60 degree diagonal in the back plane " + where);
        check(rear.x < -30 && rear.z < -30 && rear.y > 15 && rear.y < 50,
                "right fist remains behind the right waist " + where);
        check(crossing.y >= 50 && crossing.y <= 100 && crossing.z < -30,
                "shaft crosses the center of the back below/above the shoulder line " + where + " crossing=" + crossing);
        check(measured.axis().y > .35f, "spear remains inclined in world space " + where);
        check(measured.freeHandDistanceCm() > 55, "free left fist stays away from the spear " + where);
        minimumElevation = Math.min(minimumElevation, elevation); maximumElevation = Math.max(maximumElevation, elevation);
        minimumCrossY = Math.min(minimumCrossY, crossing.y); maximumCrossY = Math.max(maximumCrossY, crossing.y);
        minimumWorldAxisY = Math.min(minimumWorldAxisY, measured.axis().y);
        minimumRearZ = Math.min(minimumRearZ, rear.z); maximumRearZ = Math.max(maximumRearZ, rear.z);
        minimumFreeDistance = Math.min(minimumFreeDistance, measured.freeHandDistanceCm());
    }
    /** Independent slab intersections against actual chest, head and released arm boxes. */
    private static void independentGeometry(Map<String, Matrix4f> world, float weight, boolean slim, String where) {
        var rear = SpearRunCarry.palm(world, "right");
        var axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
        var a = new Vector3f(rear).fma(-60, axis); var b = new Vector3f(rear).fma(178, axis);
        check(!box(world.get("body"), a, b, new Vector3f(-27, -2, -14.5f), new Vector3f(27, 77, 14.5f)), "independent chest box clears pole " + where);
        check(!box(world.get("head"), a, b, new Vector3f(-27, -2, -27), new Vector3f(27, 52, 27)), "independent head box clears pole " + where);
        if (weight >= .68f) {
            float halfWidth = slim ? 9.375f : 12.5f;
            check(!box(world.get("left_arm"), a, b, new Vector3f(-halfWidth - 2, -39.5f, -14.5f), new Vector3f(halfWidth + 2, 2, 14.5f)),
                    "released left upper arm clears whole pole " + where);
            check(!box(world.get("left_forearm"), a, b, new Vector3f(-halfWidth - 2, -27, -14.5f), new Vector3f(halfWidth + 2, 2, 14.5f)),
                    "released left forearm clears whole pole " + where);
        }
    }
    private static boolean box(Matrix4f frame, Vector3f from, Vector3f to, Vector3f minimum, Vector3f maximum) {
        var inverse = new Matrix4f(frame).invert();
        var p = inverse.transformPosition(new Vector3f(from)); var d = inverse.transformPosition(new Vector3f(to)).sub(p);
        float enter = 0, leave = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d.get(axis)) < 1e-6f) { if (p.get(axis) < minimum.get(axis) || p.get(axis) > maximum.get(axis)) return false; }
            else {
                float a = (minimum.get(axis) - p.get(axis)) / d.get(axis), b = (maximum.get(axis) - p.get(axis)) / d.get(axis);
                enter = Math.max(enter, Math.min(a, b)); leave = Math.min(leave, Math.max(a, b));
                if (enter > leave) return false;
            }
        }
        return true;
    }
    private static void supportedWaist(Map<String, Matrix4f> world, float phase, String where) {
        var lower = Locomotion.supported(world.get("right_leg"), world.get("left_leg"), 0,
                phase, .28f, 1, 0, 1, .8f, 55, new PresentationMotion.Balance(0, 0, 0));
        waist(world, lower, where);
    }
    /** Reconstruct all thirteen rendered lumbar cross sections in native pixels. */
    private static void waist(Map<String, Matrix4f> world, Locomotion.LowerBody lower, String where) {
        var rear = SpearRunCarry.palm(world, "right");
        var axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
        var a = nativePixels(new Vector3f(rear).fma(-60, axis)); var b = nativePixels(new Vector3f(rear).fma(178, axis));
        var q = world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
        var chest = new Quaternionf(q.x, -q.y, -q.z, q.w);
        var r = lower.right().pose(); var l = lower.left().pose();
        var pelvis = new Vector3f((r.x() + l.x()) / 2, (r.y() + l.y()) / 2, (r.z() + l.z()) / 2);
        for (int section = 0; section <= 12; section++) {
            float y = 6 + section * .5f, blend = section / 12f; blend = blend * blend * (3 - 2 * blend);
            var center = nativePixels(world.get("body").transformPosition(new Vector3f(0, 75 - y * 6.25f, 0)))
                    .lerp(new Vector3f(pelvis).add(0, y - 12, 0), blend);
            var rotation = new Quaternionf(chest).slerp(new Quaternionf().rotationY(lower.pelvisYaw()), blend);
            var frame = new Matrix4f().translationRotate(center, rotation);
            // Half a source-pixel covers the gap to either adjacent mesh ring;
            // the additional 2 cm is the actual pole radius.
            check(!box(frame, a, b, new Vector3f(-4.32f, -.57f, -2.32f), new Vector3f(4.32f, .57f, 2.32f)),
                    "independent rendered waist section " + section + " clears whole pole " + where);
        }
    }
    private static Vector3f nativePixels(Vector3f p) { return new Vector3f(p.x / 6.25f, (150 - p.y) / 6.25f, -p.z / 6.25f); }
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

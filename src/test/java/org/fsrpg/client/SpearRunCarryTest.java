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
                check(Math.abs(elbow.distance(measured.rear()) - 31.25f) < .003f, "forearm and closed palm remain exact " + where);
                var hand = world.get("right_hand");
                check(Math.abs(measured.axis().dot(hand.transformDirection(new Vector3f(0, -1, 0)).normalize())) < .00001f,
                        "shaft crosses the real fist at ninety degrees " + where);
                for (String bone : List.of("right_arm", "right_forearm", "right_hand"))
                    check(angle(previous.get(bone).fsQuaternion(), pose.get(bone).fsQuaternion()) < 9,
                            "analytic elbow branch never swaps " + where + " " + bone + " step=" + angle(previous.get(bone).fsQuaternion(), pose.get(bone).fsQuaternion()));
                var wrist = pose.get("right_hand").fsQuaternion();
                float wristTurn = (float)Math.toDegrees(Math.IEEEremainder(2 * Math.atan2(wrist.y, wrist.w), Math.PI * 2));
                check(Math.abs(wristTurn) <= 150.1f, "distributed pronation respects wrist hard stop " + where + " wrist=" + wristTurn);
                if (weight > .9) check(measured.freeHandDistanceCm() > 45, "left hand truly leaves the shaft " + where);
                if (frame == 240) {
                    check(measured.chestRear().z < -30, "gripped pole is behind the body " + where);
                    check(measured.chestAxis().y > .95f && measured.chestAxis().x > .2f,
                            "rear pole lies diagonally up the back " + where);
                    var first = SpearRunCarry.apply(model, source, reference, 1, 0);
                    var second = SpearRunCarry.apply(model, source, reference, 1, (float)Math.PI / .6662f);
                    firstFree = SpearRunCarry.palm(model.worldMatrices(first), "left");
                    farFree = SpearRunCarry.palm(model.worldMatrices(second), "left");
                    check(firstFree.distance(farFree) > 35, "free left arm swings over an actual running cycle " + where);
                    check(SpearRunCarry.palm(model.worldMatrices(first), "right").distance(SpearRunCarry.palm(model.worldMatrices(second), "right")) < .001,
                            "right hand retains the exact back grip through the run cycle " + where);
                    officialGrip(world, where);
                }
                previous = pose;
            }
        }
        System.out.println("SpearRunCarryTest passed: " + checks + " checks; real stock/slim source, 18 starting aims, 240-step continuous lift/back route, complete pole clearance and free left run arm");
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
            for (int frame = 0; frame <= fps / 5; frame++) state.advance(frame * 20.0 / fps, "READY", true, true, .28);
            check(!state.returning(), "steady back carry never requests normal aim solving");
            var previous = model.worldMatrices(SpearRunCarry.apply(model, source, start, 1, 0));
            var goalWorld = model.worldMatrices(goal);
            boolean returned = false;
            for (int frame = 1; frame <= fps; frame++) {
                float weight = state.advance(4 + frame * 20.0 / fps, "READY", false, true, 0);
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
                    check(finalAngle < 5, "last return frame joins the new aim smoothly at " + fps + " Hz: " + finalAngle);
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
            for (int frame = 1; frame <= fps / 5; frame++) {
                double tick = frame * 20.0 / fps;
                float first = state.advance(tick, "READY", true, true, .28);
                check(state.advance(tick, "READY", true, true, .28) == first, "skin/armor/item same frame cannot advance carry");
            }
            check(state.weight() > .999, "carry settles in .2 seconds at every render rate");
            check(state.advance(5, "READY", false, false, 0) > .999, "air retains takeoff carry");
            state.advance(8, "READY", false, true, 0); state.advance(9, "READY", false, true, 0);
            check(state.weight() == 0, "stopping returns to two hands in .15 seconds");
        }
        for (String phase : List.of("WINDUP", "ACTIVE", "RECOVERY", "GUARDING", "STUNNED", "DODGING", "SHEATHING")) {
            var state = new SpearRunCarry.State(); state.advance(0, "READY", true, true, .28); state.advance(4, "READY", true, true, .28); state.advance(6, "READY", true, true, .28);
            check(state.advance(6.1, phase, true, true, .28) == 0, "combat action owns hands immediately: " + phase);
        }
    }
}

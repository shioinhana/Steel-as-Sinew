package org.fsrpg.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Actual native hips/boot surfaces and source stance independence, without an entity. */
public final class InspectionSupportTest {
    private static int checks;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static float wrap(float value) { return (float)Math.atan2(Math.sin(value), Math.cos(value)); }
    private static Vector3f hip(Locomotion.SupportedLeg leg) { var p = leg.pose(); return new Vector3f(p.x(), p.y(), p.z()); }
    private static Vector3f center(Locomotion.LowerBody body) { return hip(body.right()).add(hip(body.left())).mul(.5f); }
    private static LegDeformation skin(Locomotion.SupportedLeg leg, float pelvisYaw) {
        var p = leg.pose(); return new LegDeformation(p.x(), p.y(), p.z(), p.xRot(), p.yRot(), p.zRot(), leg.knee(), true, pelvisYaw);
    }
    private static Map<String, Pose> captured(float stance) {
        var result = new HashMap<String, Pose>();
        result.put("right_leg", new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationYXZ(stance, .08f, -.04f)), new V(-13, 75, -18), new V(1, 1, 1)));
        result.put("left_leg", new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationYXZ(-stance * .2f, -.05f, .02f)), new V(13, 75, 20), new V(1, 1, 1)));
        return Map.copyOf(result);
    }
    private static Map<String, Pose> inspected(Map<String, Pose> source, float fsYaw) {
        var result = new HashMap<>(source);
        for (String side : List.of("right_leg", "left_leg")) {
            var p = source.get(side);
            result.put(side, new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationY(fsYaw).mul(p.fsQuaternion())), p.position(), p.scale()));
        }
        return Map.copyOf(result);
    }
    public static void main(String[] args) {
        for (float pelvis : new float[]{-1.2f, -.6f, 0, .5f, 1.2f})
            for (float sourceStance : new float[]{-(float)Math.toRadians(65), -.5f, 0, .7f})
                for (float gesture : new float[]{-.20f, -.10f, -.02f, 0, .02f, .10f, .20f})
                    for (float balance : new float[]{-3, 0, 3}) {
                        var raw = Locomotion.supported(new Matrix4f().translation(-13, 75, -18).rotateY(sourceStance),
                                new Matrix4f().translation(13, 75, 20).rotateY(-sourceStance * .2f), 1, 0, 0, 0, 0, 1,
                                .72f, 48, new PresentationMotion.Balance(balance, 0, balance * .3f));
                        var base = Locomotion.turn(raw, new SpearTurn.Feet(pelvis, pelvis, pelvis, 0, 0, 1, 1), 0);
                        var before = captured(sourceStance); var after = inspected(before, gesture);
                        var moved = InspectionSupport.apply(base, before, after);
                        String where = "pelvis=" + pelvis + " stance=" + sourceStance + " gesture=" + gesture;
                        if (gesture == 0) { check(moved == base, "zero gesture returns original supported instance " + where); continue; }
                        float nativeDelta = -gesture;
                        check(Math.abs(InspectionSupport.additionalYaw(before, after) - nativeDelta) < .00001f,
                                "only common gesture yaw is extracted and converted to native handedness " + where);
                        check(Math.abs(wrap(moved.pelvisYaw() - base.pelvisYaw() - nativeDelta)) < .00001f,
                                "source stance and existing pelvis are not added twice " + where);
                        check(center(base).distance(center(moved)) < .00001f, "shared pelvis stays attached at the same location " + where);
                        var right = hip(moved.right()); var left = hip(moved.left());
                        check(Math.abs(right.distance(left) - 3.8f) < .00001f, "normal/slim native hip separation is retained " + where);
                        check(Math.abs(wrap((float)Math.atan2(-(left.z - right.z), left.x - right.x) - moved.pelvisYaw())) < .00001f,
                                "actual native hip line follows the inspection " + where);
                        check(Math.abs(moved.pelvisDropCm() - base.pelvisDropCm()) < .0001f, "rigid grounded yaw does not invent vertical pumping " + where);
                        check(moved.directionX() == base.directionX() && moved.directionForward() == base.directionForward() && moved.phase() == base.phase(),
                                "cosmetic support cannot alter movement phase or direction " + where);
                        int side = 0;
                        for (var old : List.of(base.right(), base.left())) {
                            var actual = side++ == 0 ? moved.right() : moved.left();
                            var expected = new Vector3f(old.sole()).sub(center(base)); float y = expected.y;
                            expected.y = 0; new Quaternionf().rotationY(nativeDelta).transform(expected); expected.y = y; expected.add(center(base));
                            check(actual.sole().distance(expected) < .00001f, "sole receives the extra yaw once about the common pelvis " + where);
                            check(actual.support() == old.support() && actual.sole().y == old.sole().y, "planted support and floor height remain intact " + where);
                            var mesh = skin(actual, moved.pelvisYaw());
                            Vector3f cap = mesh.point(0, 0, 0).mul(16), knee = mesh.point(0, 6, 0).mul(16), boot = mesh.point(0, 12, 0).mul(16);
                            check(cap.distance(hip(actual)) < .0001f && boot.distance(actual.sole()) < .001f,
                                    "real deformed hip cap and boot follow solved targets " + where);
                            check(Math.abs(cap.distance(knee) - 6) < .001f && Math.abs(knee.distance(boot) - 6) < .001f,
                                    "actual thigh/shin keep their six pixel lengths " + where);
                            for (float x : new float[]{-2.25f, 2.25f}) for (float z : new float[]{-2.25f, 2.25f})
                                check(Math.abs(mesh.point(x, 12.25f, z).mul(16).y - 24) < .001f,
                                        "actual planted boot corners remain on the floor " + where);
                        }
                        check(InspectionSupport.apply(base, before, after).equals(moved), "repeated sample has no accumulated history " + where);
                        check(base.pelvisYaw() == pelvis, "input lower support is never mutated " + where);
                    }
        var source = captured(-1);
        check(InspectionSupport.apply(null, source, source) == null, "absent support remains absent");
        var base = Locomotion.supported(null, null, 1, 0, 0, 0, 0, 1, .72f, 48, new PresentationMotion.Balance(0, 0, 0));
        check(InspectionSupport.apply(base, Map.of(), Map.of()) == base, "missing gesture legs leave support unchanged");
        check(Math.abs(InspectionSupport.additionalYaw(source, inspected(source, .5f)) + InspectionSupport.MAXIMUM_TURN_RADIANS) < .00001f,
                "custom turns remain inside supported cosmetic limit");
        System.out.println("InspectionSupportTest passed: " + checks + " checks; native yaw, 420 stance/gesture/balance combinations, exact hips, actual soles and six-pixel bones");
    }
}

package org.fsrpg.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Low trailing Chinese-spear run: the real right hand stays behind the waist. */
public final class SpearRunCarry {
    public static final double MINIMUM_SPEED = .16;
    public static final double ENTER_TICKS = 14, EXIT_TICKS = 12;
    private static final float UPPER = 37.5f, FORE = 25, PALM = 6.25f;
    private static final List<String> ARMS = List.of("right_arm", "right_forearm", "right_hand", "left_arm", "left_forearm", "left_hand");
    private SpearRunCarry() {}

    /** The real sprint flag and displacement are both required; air keeps its takeoff frame. */
    public static boolean eligible(String phase, boolean sprinting, boolean grounded, double horizontalSpeed) {
        return phase.equals("READY") && sprinting && grounded && Double.isFinite(horizontalSpeed)
                && horizontalSpeed >= MINIMUM_SPEED && horizontalSpeed < 1.5;
    }
    public static final class State {
        private double tick = Double.NaN;
        private float progress;
        private boolean returning;
        private float elapsed;
        private Map<String, Pose> reference;
        public float weight() {
            float ramp = .14f, velocity = 1 / (1 - ramp);
            if (progress < ramp) return velocity * progress * progress / (2 * ramp);
            if (progress > 1 - ramp) return 1 - velocity * (1 - progress) * (1 - progress) / (2 * ramp);
            return velocity * (progress - ramp / 2);
        }
        public Map<String, Pose> reference() { return reference; }
        public boolean returning() { return returning; }
        public void remember(Map<String, Pose> pose) { reference = Map.copyOf(pose); }
        /** Filter the aim before solving it; blending solved arm poses can pierce the torso. */
        public float returnAimWeight() {
            return progress == 0 ? 1 : Math.clamp(elapsed / Math.max(.2f, progress * (float)EXIT_TICKS), 0, 1);
        }
        public float advance(double now, String phase, boolean sprinting, boolean grounded, double speed) {
            if (!Double.isFinite(now)) return weight();
            if (!phase.equals("READY")) { progress = 0; returning = false; tick = now; return 0; }
            if (Double.isNaN(tick) || now < tick) { tick = now; progress = 0; }
            double dt = Math.clamp(now - tick, 0, 2); tick = now; elapsed = (float)dt;
            if (!grounded) { returning = false; return weight(); }
            boolean wanted = eligible(phase, sprinting, true, speed);
            progress = (float)Math.clamp(progress + dt * (wanted ? 1 / ENTER_TICKS : -1 / EXIT_TICKS), 0, 1);
            if (progress < .00001f) progress = 0;
            else if (progress > .99999f) progress = 1;
            returning = !wanted && progress > 0;
            return weight();
        }
    }

    /** Carry follows the same distance-driven phase as the supported feet. */
    public static Map<String, Pose> apply(ModelDocument model, Map<String, Pose> source, Map<String, Pose> reference,
                                         float weight, float walkPhase) {
        return apply(model, source, reference, weight, walkPhase, 0, 1);
    }

    /** One two-bone solve, with a separate wrist and exact six-centimeter palm offset. */
    public static Map<String, Pose> apply(ModelDocument model, Map<String, Pose> source, Map<String, Pose> reference,
                                         float weight, float walkPhase, float lateral, float forward) {
        if (reference == null) return source;
        weight = Math.clamp(weight, 0, 1);
        var result = new HashMap<>(source);
        for (String bone : ARMS) result.put(bone, reference.get(bone));
        float cycle = walkPhase * .6662f;
        float stride = (float)Math.sin(cycle), landing = (float)Math.sin(2 * cycle);
        // A forward-running chest, small counter-rotation and delayed pole response
        // are all on the foot clock; the old combat-facing run clip cannot add yaw.
        var runChest = new Quaternionf().rotationXYZ(radians(9 * forward + 1.1f * landing),
                radians(-3.2f * stride), radians(-5 * lateral + .9f * stride));
        put(result, "body", reference.get("body").fsQuaternion().slerp(runChest, weight));
        var referenceWorld = model.worldMatrices(reference);
        put(result, "head", result.get("body").fsQuaternion().invert()
                .mul(referenceWorld.get("head").getUnnormalizedRotation(new Quaternionf()).normalize()));
        var inverse = new Matrix4f(referenceWorld.get("body")).invert();
        var startPalm = inverse.transformPosition(palm(referenceWorld, "right"));
        var startHand = inverse.getUnnormalizedRotation(new Quaternionf()).normalize()
                .mul(referenceWorld.get("right_hand").getUnnormalizedRotation(new Quaternionf()).normalize());
        var startAxis = startHand.transform(new Vector3f(0, 0, 1));
        var shoulder = result.get("right_arm").position().vector();
        // The spear tip trails behind and slightly below the right hand. In body
        // space +Z is forward: a negative Z axis is the actual official spear tip.
        float lag = (float)Math.sin(cycle - .35f);
        var endPalm = new Vector3f(shoulder.x - 6.5f + .7f * lag, shoulder.y - 51 + 1.1f * landing,
                -32 + 1.4f * lag);
        var endAxis = new Vector3f(-.08f + .012f * lag, -.26f + .014f * landing, -.99f).normalize();
        float outward = smoother(Math.clamp(weight / .20f, 0, 1));
        float level = smoother(Math.clamp((weight - .20f) / .32f, 0, 1));
        float turn = smoother(Math.clamp((weight - .52f) / .24f, 0, 1));
        float trail = smoother(Math.clamp((weight - .76f) / .24f, 0, 1));
        var corner = new Vector3f(shoulder.x - 45.5f, shoulder.y - 15, 28);
        var outside = new Vector3f(shoulder.x - 54.5f, shoulder.y - 25, 0);
        var goal = new Vector3f(startPalm).lerp(corner, outward).lerp(outside, level).lerp(endPalm, trail);
        var axis = direction(startAxis, new Vector3f(0, 0, 1), level);
        axis = direction(axis, new Vector3f(-1, 0, 0), turn);
        axis = direction(axis, endAxis, trail);
        // The 60 cm butt must also pass outside the waist during the side sweep.
        if (axis.x < 0 && weight > .51f) goal.x = Math.min(goal.x, -32 + 60 * axis.x);
        var hand = transportedHand(startHand, startAxis, axis, level);
        var startWrist = inverse.transformPosition(referenceWorld.get("right_hand").getTranslation(new Vector3f()));
        var startElbow = inverse.transformPosition(referenceWorld.get("right_forearm").getTranslation(new Vector3f()));
        arm(result, goal, hand, startWrist, startElbow, endPalm, endAxis, reference.get("right_hand").fsQuaternion(), weight);

        // Negative local X is anatomical elbow flexion for this neutral left arm.
        // The previous positive flexion folded the free forearm behind the body.
        float reach = (float)Math.cos(cycle);
        var leftUpper = new Quaternionf().rotationXYZ(radians(5 + 35 * reach), radians(-3 * stride), radians(7));
        var leftFore = new Quaternionf().rotationX(radians(-79 + 10 * reach));
        put(result, "left_arm", reference.get("left_arm").fsQuaternion().slerp(leftUpper, weight));
        put(result, "left_forearm", reference.get("left_forearm").fsQuaternion().slerp(leftFore, weight));
        put(result, "left_hand", reference.get("left_hand").fsQuaternion().slerp(new Quaternionf(), weight));
        return Map.copyOf(result);
    }

    private static Vector3f direction(Vector3f from, Vector3f to, float weight) {
        if (weight <= 0) return new Vector3f(from);
        if (weight >= 1) return new Vector3f(to);
        return new Quaternionf().slerp(new Quaternionf().rotationTo(from, to), weight).transform(new Vector3f(from)).normalize();
    }
    private static Quaternionf transportedHand(Quaternionf start, Vector3f from, Vector3f axis, float weight) {
        var hand = new Quaternionf().rotationTo(from, axis).mul(start).normalize();
        if (weight >= 1) {
            var up = new Vector3f(0, 1, 0).fma(-axis.y, axis).normalize();
            var x = new Vector3f(up).cross(axis).normalize();
            return new Quaternionf().setFromNormalized(new Matrix3f(x, up, axis));
        }
        // Resolve roll once at the forward waypoint: recomputing a wrapped roll
        // against every intermediate axis flips the wrist at the +/-180 boundary.
        var atForward = new Quaternionf().rotationTo(from, new Vector3f(0, 0, 1)).mul(start);
        var forwardUp = atForward.transform(new Vector3f(0, 1, 0));
        float turn = (float)Math.atan2(forwardUp.x, forwardUp.y);
        return new Quaternionf().rotationAxis(turn * weight, axis).mul(hand).normalize();
    }
    private static float radians(float degrees) { return (float)Math.toRadians(degrees); }
    private static float smooth(float value) { return value * value * (3 - 2 * value); }
    private static float smoother(float value) { return value * value * value * (10 + value * (-15 + 6 * value)); }
    public static Vector3f palm(Map<String, Matrix4f> world, String side) {
        return world.get(side + "_hand").transformPosition(new Vector3f(0, -6.25f, 0));
    }
    public static float leftShaftDistance(Map<String, Matrix4f> world) {
        var delta = palm(world, "left").sub(palm(world, "right"));
        var axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
        return delta.fma(-delta.dot(axis), axis).length();
    }
    public record Measurements(Vector3f rear, Vector3f axis, Vector3f chestRear, Vector3f chestAxis,
                               float freeHandDistanceCm, boolean clearShaft) {}
    public static Measurements measure(Map<String, Matrix4f> world) {
        var rear = palm(world, "right");
        var axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
        var inverse = new Matrix4f(world.get("body")).invert();
        return new Measurements(rear, axis, inverse.transformPosition(new Vector3f(rear)),
                inverse.transformDirection(new Vector3f(axis)).normalize(), leftShaftDistance(world), SpearAimPose.hasClearShaft(world));
    }
    /** Physical upper arm and forearm; palm orientation is an independent wrist. */
    private static void arm(Map<String, Pose> result, Vector3f palm, Quaternionf hand,
                            Vector3f startWrist, Vector3f startElbow, Vector3f endPalm, Vector3f endAxis, Quaternionf startWristRotation, float weight) {
        var shoulder = result.get("right_arm").position().vector();
        var wrist = new Vector3f(palm).add(hand.transform(new Vector3f(0, PALM, 0)));
        var delta = new Vector3f(wrist).sub(shoulder);
        float distance = delta.length();
        if (!Float.isFinite(distance) || distance > UPPER + FORE - .01f || distance < UPPER - FORE + .01f)
            throw new IllegalArgumentException("Unreachable carry wrist at " + weight + ": " + distance + " palm=" + palm);
        var direction = delta.div(distance);
        float along = (UPPER * UPPER - FORE * FORE + distance * distance) / (2 * distance);
        float radius = (float)Math.sqrt(Math.max(0, UPPER * UPPER - along * along));
        var oldDirection = new Vector3f(startWrist).sub(shoulder).normalize();
        var oldPole = new Vector3f(startElbow).sub(shoulder);
        oldPole.fma(-oldPole.dot(oldDirection), oldDirection).normalize();
        var pole = new Quaternionf().rotationTo(oldDirection, direction).transform(new Vector3f(oldPole));
        var endUp = new Vector3f(0, 1, 0).fma(-endAxis.y, endAxis).normalize();
        var endDirection = new Vector3f(endPalm).fma(PALM, endUp).sub(shoulder).normalize();
        var endPole = new Vector3f(endUp).add(-.08f, 0, 0);
        endPole.fma(-endPole.dot(endDirection), endDirection).normalize();
        var transported = new Quaternionf().rotationTo(oldDirection, endDirection).transform(new Vector3f(oldPole));
        float roll = (float)Math.atan2(endDirection.dot(new Vector3f(transported).cross(endPole)), transported.dot(endPole));
        new Quaternionf().rotationAxis(roll * smooth(weight), direction).transform(pole);
        var elbow = new Vector3f(shoulder).fma(along, direction).fma(radius, pole);
        var upper = new Vector3f(elbow).sub(shoulder).div(UPPER);
        var fore = new Vector3f(wrist).sub(elbow).div(FORE);
        var hinge = new Vector3f(upper).cross(fore).normalize();
        var y = new Vector3f(upper).negate();
        var z = new Vector3f(hinge).cross(y).normalize();
        var upperRotation = new Quaternionf().setFromNormalized(new Matrix3f(hinge, y, z));
        // Align forearm roll to the hand directly. Splitting a freshly wrapped
        // atan2 twist would exchange +/-180-degree branches during a half-turn.
        // The only decaying offset is the known starting wrist pronation.
        var foreWorld = new Quaternionf().rotationTo(hand.transform(new Vector3f(0, 1, 0)), new Vector3f(fore).negate()).mul(hand);
        float initialTwist = (float)Math.IEEEremainder(2 * Math.atan2(startWristRotation.y, startWristRotation.w), 2 * Math.PI);
        float rollWeight = smooth(Math.clamp(weight / .45f, 0, 1));
        float wristRoll = initialTwist + (radians(-20) - initialTwist) * rollWeight;
        foreWorld.rotateY(-wristRoll);
        put(result, "right_arm", upperRotation);
        put(result, "right_forearm", new Quaternionf(upperRotation).invert().mul(foreWorld));
        put(result, "right_hand", new Quaternionf(foreWorld).invert().mul(hand));
    }
    private static void put(Map<String, Pose> pose, String bone, Quaternionf rotation) {
        var old = pose.get(bone);
        pose.put(bone, new Pose(FsvoxAnimation.fromQuaternion(rotation.normalize()), old.position(), old.scale()));
    }
}

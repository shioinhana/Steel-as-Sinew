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

/** One rear hand carries the official pole; the opposite arm remains free to run. */
public final class SpearRunCarry {
    public static final double MINIMUM_SPEED = .16;
    public static final double ENTER_TICKS = 4, EXIT_TICKS = 3;
    private static final float UPPER = 37.5f, PALM_LINK = 31.25f;
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
        private Map<String, Pose> reference;
        public float weight() { return progress * progress * (3 - 2 * progress); }
        public Map<String, Pose> reference() { return reference; }
        public boolean returning() { return returning; }
        public void remember(Map<String, Pose> pose) { reference = Map.copyOf(pose); }
        public float advance(double now, String phase, boolean sprinting, boolean grounded, double speed) {
            if (!Double.isFinite(now)) return weight();
            if (!phase.equals("READY")) { progress = 0; returning = false; tick = now; return 0; }
            if (Double.isNaN(tick) || now < tick) { tick = now; progress = 0; }
            double dt = Math.clamp(now - tick, 0, 2); tick = now;
            if (!grounded) { returning = false; return weight(); }
            boolean wanted = eligible(phase, sprinting, true, speed);
            progress = (float)Math.clamp(progress + dt * (wanted ? 1 / ENTER_TICKS : -1 / EXIT_TICKS), 0, 1);
            if (progress < .00001f) progress = 0;
            else if (progress > .99999f) progress = 1;
            returning = !wanted && progress > 0;
            return weight();
        }
    }

    /** No search or mutable rig history: at most two analytic right-elbow branches. */
    public static Map<String, Pose> apply(ModelDocument model, Map<String, Pose> source, Map<String, Pose> reference,
                                         float weight, float walkPhase) {
        if (reference == null) return source;
        weight = Math.clamp(weight, 0, 1);
        var result = new HashMap<>(source);
        for (String bone : ARMS) result.put(bone, reference.get(bone));
        // Returning from back carry cannot preserve the last run's side-facing chest.
        // Both end points keep the same root/waist translation and real shoulder roots.
        var body = source.get("body");
        var angles = body.fsQuaternion().getEulerAnglesXYZ(new Vector3f());
        var square = new Quaternionf().rotationXYZ(angles.x, 0, angles.z);
        put(result, "body", reference.get("body").fsQuaternion().slerp(square, weight));
        var referenceWorld = model.worldMatrices(reference);
        // Square the running chest without turning the face away from travel.
        // Keeping the source's fencing counter-yaw here also rotates the head box
        // across the lifted pole while the chest is on its way back to square.
        put(result, "head", result.get("body").fsQuaternion().invert()
                .mul(referenceWorld.get("head").getUnnormalizedRotation(new Quaternionf()).normalize()));
        var inverse = new Matrix4f(referenceWorld.get("body")).invert();
        var startPalm = inverse.transformPosition(palm(referenceWorld, "right"));
        var startAxis = inverse.transformDirection(referenceWorld.get("right_hand").transformDirection(new Vector3f(0, 0, 1))).normalize();
        var shoulder = source.get("right_arm").position();
        var endPalm = new Vector3f(shoulder.x() - 8.5f, shoulder.y() - 27, -40);
        // Lift the complete shaft outside the right shoulder before taking it behind
        // the back. The last portion lays it diagonally across the rear body plane.
        float outward = smoother(Math.clamp(weight / .22f, 0, 1));
        float lift = smooth(Math.clamp((weight - .22f) / .42f, 0, 1));
        float behind = smooth(Math.clamp((weight - .64f) / .22f, 0, 1));
        float settle = smooth(Math.clamp((weight - .86f) / .14f, 0, 1));
        var sidePalm = new Vector3f(shoulder.x() - 28, shoulder.y() - 27, 40);
        var outerPalm = new Vector3f(startPalm); outerPalm.x = shoulder.x() - 28; outerPalm.z = 40;
        var goal = new Vector3f(startPalm).lerp(outerPalm, outward).lerp(sidePalm, lift);
        goal.z += (endPalm.z - sidePalm.z) * behind;
        goal.x += (endPalm.x - sidePalm.x) * settle;
        var axis = new Vector3f(startAxis).lerp(new Vector3f(0, 1, 0), lift).normalize()
                .lerp(new Vector3f(.24f, .9707729f, 0), settle).normalize();
        // The closed fist's forearm stays perpendicular to the shaft. Its exact
        // reach is an annulus around the shaft, so project analytically rather
        // than stretching a limb or asking the expensive two-hand search.
        goal = reachablePalm(goal, new Vector3f(shoulder.x(), shoulder.y(), shoulder.z()), axis);
        var startShoulder = reference.get("right_arm").position();
        var startDelta = new Vector3f(startPalm).sub(startShoulder.x(), startShoulder.y(), startShoulder.z());
        var startRadial = new Vector3f(startDelta).fma(-startDelta.dot(startAxis), startAxis).normalize();
        var startTangent = new Vector3f(startAxis).cross(startRadial).normalize();
        var startFore = inverse.transformDirection(referenceWorld.get("right_forearm").transformDirection(new Vector3f(0, -1, 0))).normalize();
        float branch = startFore.dot(startTangent) >= 0 ? 1 : -1;
        closedArm(result, "right", goal, axis, branch, weight);

        // A genuinely free arm: counter-swing against the running foot, bent elbow,
        // neutral hand. There is no left-hand shaft target or two-hand IK here.
        var left = new HashMap<>(result);
        put(left, "left_arm", new Quaternionf().rotationXYZ((float)Math.toRadians(-18 + 34 * Math.cos(walkPhase * .6662f)), 0, -.10f));
        put(left, "left_forearm", new Quaternionf().rotationX((float)Math.toRadians(72)));
        put(left, "left_hand", new Quaternionf());
        for (String bone : List.of("left_arm", "left_forearm", "left_hand")) {
            var from = reference.get(bone); var to = left.get(bone);
            put(result, bone, from.fsQuaternion().slerp(to.fsQuaternion(), weight));
        }
        return Map.copyOf(result);
    }

    private static float smooth(float value) { return value * value * (3 - 2 * value); }
    private static float smoother(float value) { return value * value * value * (10 + value * (-15 + 6 * value)); }
    private static Vector3f reachablePalm(Vector3f goal, Vector3f shoulder, Vector3f axis) {
        var delta = new Vector3f(goal).sub(shoulder);
        float along = Math.clamp(delta.dot(axis), -UPPER + .05f, UPPER - .05f);
        var radial = new Vector3f(delta).fma(-delta.dot(axis), axis);
        float length = radial.length();
        float range = (float)Math.sqrt(UPPER * UPPER - along * along);
        float wanted = Math.clamp(length, Math.max(.05f, PALM_LINK - range) + .005f, PALM_LINK + range - .005f);
        return new Vector3f(shoulder).fma(along, axis).add(radial.mul(wanted / Math.max(.0001f, length)));
    }
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
    private static void closedArm(Map<String, Pose> result, String side, Vector3f palm, Vector3f shaft, float branch, float weight) {
        var at = result.get(side + "_arm").position();
        var shoulder = new Vector3f(at.x(), at.y(), at.z());
        var delta = new Vector3f(palm).sub(shoulder);
        var radial = new Vector3f(delta).fma(-delta.dot(shaft), shaft);
        float distance = radial.length();
        float cosine = (delta.lengthSquared() + PALM_LINK * PALM_LINK - UPPER * UPPER) / (2 * PALM_LINK * distance);
        // Endpoint and the routed path are authored inside this analytic reach ring.
        // Reject a bad asset/shoulder layout instead of stretching bone lengths.
        if (distance < .001f || Math.abs(cosine) > 1.0001f) throw new IllegalArgumentException("Unreachable rear spear grip at weight=" + weight + " palm=" + palm + " axis=" + shaft + " cosine=" + cosine);
        radial.div(distance); cosine = Math.clamp(cosine, -1, 1);
        float sine = (float)Math.sqrt(Math.max(0, 1 - cosine * cosine));
        var tangent = new Vector3f(shaft).cross(radial).normalize();
        // Preserve the same analytic elbow root for the whole route. Re-ranking by
        // elbow X at the vertical pole exchanges the two roots by over 120 degrees.
        var fore = new Vector3f(radial).mul(cosine).fma(branch * sine, tangent);
        var upper = new Vector3f(delta).fma(-PALM_LINK, fore).div(UPPER).normalize();
        var hinge = new Vector3f(upper).cross(fore).normalize();
        var y = new Vector3f(upper).negate();
        var z = new Vector3f(hinge).cross(y).normalize();
        put(result, side + "_arm", new Quaternionf().setFromNormalized(new Matrix3f(hinge, y, z)));
        float bend = (float)Math.acos(Math.clamp(upper.dot(fore), -1, 1));
        var lowerZ = new Vector3f(hinge).cross(new Vector3f(fore).negate());
        float twist = (float)Math.atan2(shaft.dot(hinge), shaft.dot(lowerZ));
        // Pronation belongs to the forearm as well as the palm. Its local Y axis
        // is the bone direction, so distributing roll cannot move the real grip.
        float pronation = twist * .5f * smooth(Math.clamp(weight / .22f, 0, 1));
        put(result, side + "_forearm", new Quaternionf().rotationX(bend).rotateY(pronation));
        put(result, side + "_hand", new Quaternionf().rotationY(twist - pronation));
    }
    private static void put(Map<String, Pose> pose, String bone, Quaternionf rotation) {
        var old = pose.get(bone);
        pose.put(bone, new Pose(FsvoxAnimation.fromQuaternion(rotation.normalize()), old.position(), old.scale()));
    }
}

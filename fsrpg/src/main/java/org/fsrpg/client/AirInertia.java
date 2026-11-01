package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A small, non-periodic airborne response. It never writes entity motion or combat state. */
public final class AirInertia {
    public static final double RESPONSE_TICKS = 1.8;
    public static final float MAXIMUM_BODY_PITCH_DEGREES = 3;
    public static final float MAXIMUM_BODY_ROLL_DEGREES = 1.6f;
    public static final float MAXIMUM_LEG_PITCH_DEGREES = 4;
    public static final float MAXIMUM_LEG_ROLL_DEGREES = 1.5f;
    private AirInertia() {}

    /** Angles are FS world degrees; there is no translation and no animation oscillator. */
    public record Offset(float pitchDegrees, float rollDegrees, float legPitchDegrees, float legRollDegrees) {
        public Offset {
            pitchDegrees = safeAngle(pitchDegrees, MAXIMUM_BODY_PITCH_DEGREES);
            rollDegrees = safeAngle(rollDegrees, MAXIMUM_BODY_ROLL_DEGREES);
            legPitchDegrees = safeAngle(legPitchDegrees, MAXIMUM_LEG_PITCH_DEGREES);
            legRollDegrees = safeAngle(legRollDegrees, MAXIMUM_LEG_ROLL_DEGREES);
        }
        public static final Offset ZERO = new Offset(0, 0, 0, 0);
        public boolean active() { return pitchDegrees != 0 || rollDegrees != 0 || legPitchDegrees != 0 || legRollDegrees != 0; }
    }
    public record Result(Map<String, Pose> local, Map<String, Matrix4f> world, Locomotion.LowerBody lower) {}

    public static final class State {
        private long motionTick = Long.MIN_VALUE;
        private double previousX, previousY, previousZ;
        private final double[] velocity = new double[3], acceleration = new double[3];
        private final double[] filteredVelocity = new double[3], filteredAcceleration = new double[3];
        private String key = "";
        private boolean airborne;
        private double takeoffTick, cachedRenderTick = Double.NaN;
        private Offset cached = Offset.ZERO;

        /**
         * Call once per entity simulation tick, including grounded ticks. Positions
         * are actual entity positions; fallback is blocks/tick, only for the first
         * observation. Do not derive Y from render interpolation or getDeltaMovement
         * after gravity has already prepared the next simulation step.
         */
        public void observeMotion(long simulationTick, double x, double y, double z,
                                  double fallbackX, double fallbackY, double fallbackZ) {
            if (simulationTick == motionTick) return;
            long gap = simulationTick - motionTick;
            boolean first = motionTick == Long.MIN_VALUE || gap <= 0 || gap > 5;
            double[] next = first ? new double[]{fallbackX, fallbackY, fallbackZ}
                    : new double[]{(x - previousX) / gap, (y - previousY) / gap, (z - previousZ) / gap};
            boolean valid = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Double.isFinite(next[0]) && Double.isFinite(next[1]) && Double.isFinite(next[2])
                    && Math.hypot(next[0], next[2]) <= 1.75 && Math.abs(next[1]) <= 4;
            if (!valid) { next = new double[3]; first = true; airborne = false; }
            for (int i = 0; i < 3; i++) {
                if (first) { filteredVelocity[i] = filteredAcceleration[i] = acceleration[i] = 0; }
                else {
                    filteredVelocity[i] = response(filteredVelocity[i], velocity[i], gap);
                    filteredAcceleration[i] = response(filteredAcceleration[i], acceleration[i], gap);
                    acceleration[i] = Math.clamp((next[i] - velocity[i]) / gap, -.5, .5);
                }
                velocity[i] = next[i];
            }
            motionTick = simulationTick; previousX = x; previousY = y; previousZ = z;
        }

        /**
         * renderTick is the monotonic presentation clock (20 ticks/second).
         * partialTick is the native current simulation interpolation fraction 0..1.
         * Filters advance on observation ticks and are sampled analytically between
         * them; extra skin/armor/item submissions cannot advance the response.
         */
        public Offset sample(String presentationKey, double renderTick, float partialTick, boolean inAir,
                             String phase, float bodyYawDegrees, float strength) {
            if (presentationKey == null || !presentationKey.equals(key) || !Double.isFinite(renderTick)
                    || (!Double.isNaN(cachedRenderTick) && renderTick < cachedRenderTick)) {
                key = presentationKey == null ? "" : presentationKey;
                airborne = false; cachedRenderTick = Double.NaN; cached = Offset.ZERO;
            }
            if (!inAir || motionTick == Long.MIN_VALUE || !Float.isFinite(strength) || strength <= 0) {
                airborne = false; cachedRenderTick = renderTick; return cached = Offset.ZERO;
            }
            float phaseWeight = "READY".equals(phase) ? 1 : phase != null && AirPose.attack(phase) ? .25f : 0;
            if (phaseWeight == 0 || !Float.isFinite(bodyYawDegrees)) { cachedRenderTick = renderTick; return cached = Offset.ZERO; }
            if (renderTick == cachedRenderTick) return cached;
            cachedRenderTick = renderTick;
            double partial = Float.isFinite(partialTick) ? Math.clamp(partialTick, 0, 1) : 0;
            double now = motionTick + partial;
            if (!airborne) { airborne = true; takeoffTick = motionTick; }
            double yaw = Math.toRadians(bodyYawDegrees);
            double vx = response(filteredVelocity[0], velocity[0], partial);
            double vy = response(filteredVelocity[1], velocity[1], partial);
            double vz = response(filteredVelocity[2], velocity[2], partial);
            double ax = response(filteredAcceleration[0], acceleration[0], partial);
            double ay = response(filteredAcceleration[1], acceleration[1], partial);
            double az = response(filteredAcceleration[2], acceleration[2], partial);
            double side = Math.tanh((vx * Math.cos(yaw) + vz * Math.sin(yaw)) / .35);
            double forward = Math.tanh((-vx * Math.sin(yaw) + vz * Math.cos(yaw)) / .35);
            double sideAcceleration = Math.tanh((ax * Math.cos(yaw) + az * Math.sin(yaw)) / .10);
            double vertical = Math.tanh(vy / .35), verticalAcceleration = Math.tanh(ay / .10);
            double entry = Math.clamp((now - takeoffTick) / 2.5, 0, 1);
            float weight = (float)(entry * entry * (3 - 2 * entry)) * Math.clamp(strength, 0, 1) * phaseWeight;
            return cached = new Offset(
                    bounded(-1.9 * vertical + .65 * forward + .45 * verticalAcceleration, MAXIMUM_BODY_PITCH_DEGREES) * weight,
                    bounded(-1.1 * side - .35 * sideAcceleration, MAXIMUM_BODY_ROLL_DEGREES) * weight,
                    bounded(3.0 * vertical - .6 * forward + .35 * verticalAcceleration, MAXIMUM_LEG_PITCH_DEGREES) * weight,
                    bounded(.7 * side + .3 * sideAcceleration, MAXIMUM_LEG_ROLL_DEGREES) * weight);
        }
        public void clear() {
            motionTick = Long.MIN_VALUE; previousX = previousY = previousZ = 0;
            java.util.Arrays.fill(velocity, 0); java.util.Arrays.fill(acceleration, 0);
            java.util.Arrays.fill(filteredVelocity, 0); java.util.Arrays.fill(filteredAcceleration, 0);
            key = ""; airborne = false; cachedRenderTick = Double.NaN; cached = Offset.ZERO;
        }
    }

    private static double response(double initial, double target, double ticks) {
        return target + (initial - target) * Math.exp(-Math.max(0, ticks) / RESPONSE_TICKS);
    }
    private static float bounded(double value, float maximum) { return (float)Math.clamp(value, -maximum, maximum); }
    private static float safeAngle(float value, float maximum) { return Float.isFinite(value) ? Math.clamp(value, -maximum, maximum) : 0; }

    /**
     * Apply AFTER takeoff retention and AirPoseJoin. Record only that undecorated
     * base with AirPose.State.presented; never feed this result back as its input.
     * The entire upper/grip/item chain receives one waist-pivot rigid transform.
     */
    public static Result apply(ModelDocument model, Map<String, Pose> source, Map<String, Matrix4f> sourceWorld,
                               Locomotion.LowerBody lower, Offset offset) {
        if (offset == null || !offset.active() || !source.containsKey("body")) return new Result(source, sourceWorld, lower);
        Map<String, Pose> result = new HashMap<>(source);
        boolean oldCenter = model.rig().id().startsWith("fsrpg.legacy_") && !SwordMotion.applies(model);
        Vector3f waist = oldCenter ? LegacyWaistFit.nativeWaist(sourceWorld.get("body"))
                : sourceWorld.get("body").getTranslation(new Vector3f());
        Quaternionf upperRotation = safeUpperRotation(model, sourceWorld, waist, offset);
        Matrix4f transform = new Matrix4f().translation(waist).rotate(upperRotation).translate(new Vector3f(waist).negate());
        var upper = PoseLayers.masks(model.rig()).get("upper");
        for (String part : upper) {
            if (upper.contains(model.rig().parts().get(part).parent()) || !sourceWorld.containsKey(part)) continue;
            putWorld(model, result, sourceWorld, part, new Matrix4f(transform).mul(sourceWorld.get(part)));
        }
        // The head stays at its attached neck. A pole's checked head clearance
        // needs the complete rigid transport; other weapons keep a steadier gaze.
        Map<String, Matrix4f> moved = model.worldMatrices(result);
        if (sourceWorld.containsKey("head")) {
            float headWeight = model.rig().id().equals("fsrpg.spear") ? 1 : .65f;
            Quaternionf headFollow = new Quaternionf().slerp(upperRotation, headWeight)
                    .mul(sourceWorld.get("head").getUnnormalizedRotation(new Quaternionf()).normalize());
            Matrix4f head = new Matrix4f(moved.get("head"));
            head.set3x3(new Matrix4f().rotation(headFollow).scale(moved.get("head").getScale(new Vector3f())));
            putWorld(model, result, moved, "head", head);
        }
        Quaternionf legRotation = rotation(offset.legPitchDegrees(), offset.legRollDegrees());
        for (String part : new String[]{"right_leg", "left_leg"}) if (sourceWorld.containsKey(part)) {
            Matrix4f before = sourceWorld.get(part);
            Vector3f hip = before.getTranslation(new Vector3f());
            Matrix4f after = new Matrix4f().translation(hip).rotate(legRotation)
                    .translate(new Vector3f(hip).negate()).mul(before);
            putWorld(model, result, sourceWorld, part, after);
        }
        Locomotion.LowerBody movedLower = lower;
        if (lower != null) {
            Quaternionf nativeLeg = new Quaternionf(legRotation.x, -legRotation.y, -legRotation.z, legRotation.w);
            movedLower = new Locomotion.LowerBody(moveLeg(lower.right(), nativeLeg), moveLeg(lower.left(), nativeLeg),
                    lower.pelvisDropCm(), lower.directionX(), lower.directionForward(), lower.phase(), lower.pelvisYaw());
        }
        Map<String, Pose> immutable = Map.copyOf(result);
        return new Result(immutable, model.worldMatrices(immutable), movedLower);
    }
    private static Quaternionf safeUpperRotation(ModelDocument model, Map<String, Matrix4f> world, Vector3f waist, Offset offset) {
        Quaternionf full = rotation(offset.pitchDegrees(), offset.rollDegrees());
        if (!model.rig().id().equals("fsrpg.spear")) return full;
        if (clearTransport(world, waist, full)) return full;
        if (!SpearAimPose.hasClearShaft(world)) return new Quaternionf();
        // Whole-chain transport preserves box clearance, but the existing lumbar
        // envelope grows slightly with chest tilt. At a marginal ready grip only
        // the cosmetic torso tilt yields. This has eight fixed cheap tests at
        // most, no IK, no pose-map rebuild and no state-dependent search budget.
        float low = 0, high = 1;
        for (int i = 0; i < 6; i++) {
            float middle = (low + high) * .5f;
            if (clearTransport(world, waist, rotation(offset.pitchDegrees() * middle, offset.rollDegrees() * middle))) low = middle;
            else high = middle;
        }
        float safe = low * .98f;
        return rotation(offset.pitchDegrees() * safe, offset.rollDegrees() * safe);
    }
    private static boolean clearTransport(Map<String, Matrix4f> world, Vector3f waist, Quaternionf rotation) {
        Matrix4f transform = new Matrix4f().translation(waist).rotate(rotation).translate(new Vector3f(waist).negate());
        return SpearAimPose.hasClearShaft(Map.of("body", new Matrix4f(transform).mul(world.get("body")),
                "head", new Matrix4f(transform).mul(world.get("head")), "right_hand", new Matrix4f(transform).mul(world.get("right_hand"))));
    }
    private static Locomotion.SupportedLeg moveLeg(Locomotion.SupportedLeg leg, Quaternionf rotation) {
        var p = leg.pose();
        Vector3f angles = new Quaternionf(rotation).mul(p.rotation()).getEulerAnglesZYX(new Vector3f());
        Vector3f hip = new Vector3f(p.x(), p.y(), p.z());
        Vector3f sole = rotation.transform(new Vector3f(leg.sole()).sub(hip)).add(hip);
        return new Locomotion.SupportedLeg(new Locomotion.PartPose(p.x(), p.y(), p.z(), angles.x, angles.y, angles.z),
                leg.knee(), leg.support(), sole);
    }
    private static Quaternionf rotation(float pitchDegrees, float rollDegrees) {
        return new Quaternionf().rotationXYZ((float)Math.toRadians(pitchDegrees), 0, (float)Math.toRadians(rollDegrees));
    }
    private static void putWorld(ModelDocument model, Map<String, Pose> result, Map<String, Matrix4f> world,
                                 String part, Matrix4f desiredWorld) {
        Pose old = result.get(part); if (old == null) return;
        Matrix4f parent = world.get(model.rig().parts().get(part).parent());
        Matrix4f local = parent == null ? desiredWorld : new Matrix4f(parent).invert().mul(desiredWorld);
        Vector3f at = local.getTranslation(new Vector3f());
        Quaternionf orientation = local.getUnnormalizedRotation(new Quaternionf()).normalize();
        result.put(part, new Pose(FsvoxAnimation.fromQuaternion(orientation), new V(at.x, at.y, at.z), old.scale()));
    }
}

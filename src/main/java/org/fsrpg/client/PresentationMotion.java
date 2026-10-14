package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix4f;

/** Small visual inertia around the waist. It never moves an entity or its hit box. */
public final class PresentationMotion {
    private double previousTick = Double.NaN;
    private double breathStarted = Double.NaN;
    private float x, forward, run;
    private long positionTick = Long.MIN_VALUE;
    private double previousX, previousZ, velocityX, velocityZ;
    private double previousGaitTick = Double.NaN, gaitRadians;
    private float gaitLane;
    /** Stable per-tick displacement; engine interpolation fields can be reset by render setup. */
    public void observePosition(long tick, double worldX, double worldZ, double fallbackX, double fallbackZ) {
        if (tick == positionTick) return;
        long gap = tick - positionTick;
        if (positionTick == Long.MIN_VALUE || gap <= 0 || gap > 5) {
            velocityX = fallbackX; velocityZ = fallbackZ;
        } else {
            velocityX = (worldX - previousX) / gap;
            velocityZ = (worldZ - previousZ) / gap;
        }
        positionTick = tick; previousX = worldX; previousZ = worldZ;
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityZ) || Math.hypot(velocityX, velocityZ) > 1.5)
            velocityX = velocityZ = 0;
    }
    public double velocityX() { return velocityX; }
    public double velocityZ() { return velocityZ; }
    public float runWeight() { return run; }
    public float lateralLean() { return x; }
    public float forwardLean() { return forward; }

    /** A planted foot retreats by actual travel distance during its support half-cycle. */
    public float gaitPhase(double renderTick, float moving, float lowerWeight, float strideScale) {
        double dt = Double.isNaN(previousGaitTick) ? 0 : Math.clamp(renderTick - previousGaitTick, 0, 5);
        previousGaitTick = renderTick;
        float targetLane=Math.signum(x*(Math.abs(forward)>.15f?forward:1));
        gaitLane+=(targetLane-gaitLane)*(float)(1-Math.exp(-dt/2));
        float contribution = Math.clamp(moving * (1 - lowerWeight), 0, 1);
        double speedPixelsPerTick = Math.hypot(velocityX, velocityZ) * 16;
        if (contribution > .05f && speedPixelsPerTick > .001)
            gaitRadians += dt * speedPixelsPerTick * Math.PI / (2 * Locomotion.stepReach(strideScale) * Math.max(.25f, contribution));
        return (float) ((gaitRadians % (Math.PI * 2)) / .6662);
    }
    public float gaitLane() { return gaitLane; }

    /** One actor clock survives attacks, guard and persistent dual-hand stance changes. */
    public double breathingSeconds(double renderTick, double rate) {
        if (Double.isNaN(breathStarted) || renderTick < breathStarted) breathStarted = renderTick;
        return Math.max(0, renderTick - breathStarted) * rate / 20.0;
    }

    public void advance(double renderTick, double worldX, double worldZ, float bodyYaw,
                        boolean sprinting, boolean grounded, float movement, float responseTicks) {
        if (!Double.isNaN(previousTick) && renderTick <= previousTick) return;
        double dt = Double.isNaN(previousTick) ? 0 : Math.min(5, renderTick - previousTick);
        previousTick = renderTick;
        // Teleports and disconnected remote interpolation must not throw the torso sideways.
        double speed = Math.hypot(worldX, worldZ);
        if (!grounded || speed > 1.5 || !Double.isFinite(speed)) worldX = worldZ = 0;
        double yaw = Math.toRadians(bodyYaw);
        // Native Y/Z conversion plus LivingEntityRenderer's (-1,-1,1) and yaw leave FS +X as world +X at yaw zero.
        float targetX = (float) ((worldX * Math.cos(yaw) + worldZ * Math.sin(yaw)) / .28);
        float targetForward = (float) ((-worldX * Math.sin(yaw) + worldZ * Math.cos(yaw)) / .28);
        float length = (float) Math.hypot(targetX, targetForward);
        if (length > 1) { targetX /= length; targetForward /= length; }
        float alpha = (float) (1 - Math.exp(-dt / Math.max(.1, responseTicks)));
        x += (targetX - x) * alpha;
        forward += (targetForward - forward) * alpha;
        float targetRun = sprinting && grounded ? Math.clamp(movement, 0, 1) : 0;
        run += (targetRun - run) * alpha;
        if (Math.abs(run - targetRun) < .0001f) run = targetRun;
    }

    public Map<String, Pose> applyLean(Map<String, Pose> source, float degrees, float strength) {
        return lean(source, x * degrees * strength, forward * degrees * strength);
    }
    public Map<String, Pose> applyLean(Map<String, Pose> source, float degrees, float strength, float headFollow) {
        return lean(source, x * degrees * strength, forward * degrees * strength, headFollow);
    }
    public record Balance(float x, float y, float z) {}
    public Balance balance(float maximumCm, float strength) {
        float dx=x*maximumCm*strength, dz=forward*maximumCm*strength;
        return horizontalBalance(dx, dz);
    }
    private static Balance horizontalBalance(float dx, float dz) {
        float dy=(float)Math.sqrt(Math.max(0,75*75-dx*dx-dz*dz))-75;
        return new Balance(dx,dy,dz);
    }

    /** Upper-body attack weight also carries the hips; breathing never lifts the feet. */
    public static Balance withAuthoredHipShift(Map<String, Pose> pose, V restBody, Balance motion) {
        Pose body = pose.get("body"); if (body == null) return motion;
        return horizontalBalance(body.position().x() - restBody.x() + motion.x(),
                body.position().z() - restBody.z() + motion.z());
    }

    /** Rotate a shifted hip back toward its planted sole while retaining the native gait rotation. */
    public static Locomotion.PartPose balanceLeg(Locomotion.PartPose leg, Balance balance) {
        Quaternionf tilt = new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f(-balance.x(), 75 + balance.y(), balance.z()).normalize());
        Vector3f angles = tilt.mul(leg.rotation()).getEulerAnglesZYX(new Vector3f());
        return new Locomotion.PartPose(leg.x() + balance.x() / 6.25f, leg.y() - balance.y() / 6.25f,
                leg.z() - balance.z() / 6.25f, angles.x, angles.y, angles.z);
    }
    public static Map<String, Pose> shiftTorso(Map<String, Pose> source, Balance balance) {
        Pose body=source.get("body"); if(body==null) return source;
        var at=body.position(); var result=new HashMap<>(source);
        result.put("body",new Pose(body.rotation(),new V(at.x()+balance.x(),at.y()+balance.y(),at.z()+balance.z()),body.scale()));
        return Map.copyOf(result);
    }
    /** The shoulder steadies the running blade; the authored elbow and wrist stay intact. */
    public static Map<String, Pose> uprightGrip(Map<String, Pose> source, Map<String, Matrix4f> world, float weight) {
        Pose arm=source.get("right_arm");
        if(arm==null || weight<=.0001f || !world.containsKey("right_hand") || !world.containsKey("right_arm")) return source;
        Quaternionf handWorld=world.get("right_hand").getUnnormalizedRotation(new Quaternionf()).normalize();
        Vector3f blade=handWorld.transform(HeldItemTransform.rapierBladeAxis());
        Quaternionf correction=new Quaternionf().slerp(new Quaternionf().rotationTo(blade,new Vector3f(0,1,0)),Math.clamp(weight,0,1));
        Quaternionf armWorld=world.get("right_arm").getUnnormalizedRotation(new Quaternionf()).normalize();
        Quaternionf parentWorld=new Quaternionf(armWorld).mul(new Quaternionf(arm.fsQuaternion()).invert());
        Quaternionf local=parentWorld.invert().mul(correction).mul(armWorld).normalize();
        var result=new HashMap<>(source);
        result.put("right_arm",new Pose(FsvoxAnimation.fromQuaternion(local),arm.position(),arm.scale()));
        return Map.copyOf(result);
    }

    /** Oppose the actual leg phase with the shoulders; the feet stay under native locomotion. */
    public static Map<String, Pose> stride(Map<String, Pose> source, float phase, float movement, float strength) {
        float weight = Math.clamp(movement, 0, 1) * strength;
        Pose body = source.get("body");
        if (body == null || weight <= .0001f) return source;
        float cycle = phase * .6662f;
        float twist = (float) Math.sin(cycle) * 4.5f * weight;
        Map<String, Pose> result = new HashMap<>(lean(source, (float) Math.sin(cycle) * 1.8f * weight, 0));
        Pose tilted = result.get("body");
        Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians(twist));
        Quaternionf rotation = turn.mul(tilted.fsQuaternion());
        var at = tilted.position();
        // At most 2 cm compression: no floating hips or large visual displacement of the hit box.
        float drop = (.5f + .5f * (float) Math.cos(cycle * 2)) * 2f * weight;
        result.put("body", new Pose(FsvoxAnimation.fromQuaternion(rotation), new V(at.x(), at.y() - drop, at.z()), tilted.scale()));
        Pose head = result.get("head");
        if (head != null) {
            Quaternionf compensated = new Quaternionf(rotation).invert().mul(tilted.fsQuaternion()).mul(head.fsQuaternion());
            result.put("head", new Pose(FsvoxAnimation.fromQuaternion(compensated), head.position(), head.scale()));
        }
        return Map.copyOf(result);
    }

    public static Map<String, Pose> lean(Map<String, Pose> source, float lateralDegrees, float forwardDegrees) {
        return lean(source,lateralDegrees,forwardDegrees,1);
    }
    public static Map<String, Pose> lean(Map<String, Pose> source, float lateralDegrees, float forwardDegrees, float headFollow) {
        Pose body = source.get("body");
        if (body == null || Math.abs(lateralDegrees) + Math.abs(forwardDegrees) < .0001f) return source;
        Quaternionf tilt = new Quaternionf().rotationXYZ((float) Math.toRadians(forwardDegrees), 0,
                (float) Math.toRadians(-lateralDegrees));
        Quaternionf oldBody = body.fsQuaternion(), newBody = new Quaternionf(tilt).mul(oldBody);
        Map<String, Pose> result = new HashMap<>(source);
        result.put("body", new Pose(FsvoxAnimation.fromQuaternion(newBody), body.position(), body.scale()));
        Pose head = source.get("head");
        if (head != null && headFollow < .9999f) {
            Quaternionf followed = new Quaternionf().slerp(tilt, Math.clamp(headFollow,0,1));
            Quaternionf headRotation = new Quaternionf(newBody).invert().mul(followed).mul(oldBody).mul(head.fsQuaternion());
            result.put("head", new Pose(FsvoxAnimation.fromQuaternion(headRotation), head.position(), head.scale()));
        }
        return Map.copyOf(result);
    }

    public static Map<String, Pose> blend(Map<String, Pose> from, Map<String, Pose> to, float weight) {
        if (weight <= 0) return from;
        if (weight >= 1) return to;
        Map<String, Pose> result = new HashMap<>(from);
        to.forEach((id, pose) -> result.put(id, from.getOrDefault(id, pose).mix(pose, weight, true)));
        return Map.copyOf(result);
    }

    /** Scale the authored breath relative to its neutral first frame, including the arm follow-through. */
    public static Map<String, Pose> breathing(Map<String, Pose> neutral, Map<String, Pose> sample, float scale) {
        if (scale == 1) return sample;
        Map<String, Pose> result = new HashMap<>(sample);
        for (var entry : sample.entrySet()) {
            Pose rest = neutral.get(entry.getKey()), pose = entry.getValue();
            if (rest == null) continue;
            Vector3f position = rest.position().vector().lerp(pose.position().vector(), scale);
            Quaternionf rotation = rest.fsQuaternion().slerp(pose.fsQuaternion(), scale);
            result.put(entry.getKey(), new Pose(FsvoxAnimation.fromQuaternion(rotation),
                    new V(position.x, position.y, position.z), pose.scale()));
        }
        return Map.copyOf(result);
    }
}

package org.fsrpg.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Render-only lower-body composition; native gait is a base pose, never an added Euler angle. */
public final class Locomotion {
    private Locomotion() {}

    /** Minecraft ModelPart uses ZYX rotations, unlike FSVOXEL's local XYZ rotations. */
    public record PartPose(float x, float y, float z, float xRot, float yRot, float zRot) {
        public Quaternionf rotation() { return new Quaternionf().rotationZYX(zRot, yRot, xRot); }
    }

    public record SupportedLeg(PartPose pose, float knee, float support, Vector3f sole) {}
    public record LowerBody(SupportedLeg right, SupportedLeg left, float pelvisDropCm,
                            float directionX, float directionForward, float phase,float pelvisYaw) {
        public LowerBody(SupportedLeg right,SupportedLeg left,float pelvisDropCm,float directionX,float directionForward,float phase) {
            this(right,left,pelvisDropCm,directionX,directionForward,phase,0);
        }
    }

    /** Authored leg translations describe where to plant a foot, never a detached hip. */
    public static Vector3f authoredSole(Matrix4f leg, boolean left) {
        if (leg == null) return new Vector3f(left ? 1.9f : -1.9f, 24, 0);
        var p = leg.transformPosition(new Vector3f(0, -75, 0));
        return new Vector3f(p.x / 6.25f, 24, -p.z / 6.25f);
    }

    /** Two feet share one pelvis; only the recovering foot leaves the floor. Native units are pixels. */
    public static LowerBody supported(Matrix4f right, Matrix4f left, float lowerWeight,
                                      float phase, float walkSpeed, float moving, float directionX, float directionForward,
                                      float strideScale, float kneeDegrees, PresentationMotion.Balance balance) {
        float lane = Math.signum(directionX * (Math.abs(directionForward) > .15f ? directionForward : 1));
        return supported(right,left,lowerWeight,phase,walkSpeed,moving,directionX,directionForward,strideScale,kneeDegrees,balance,0,lane);
    }
    public static LowerBody supported(Matrix4f right, Matrix4f left, float lowerWeight,
                                      float phase, float walkSpeed, float moving, float directionX, float directionForward,
                                      float strideScale, float kneeDegrees, PresentationMotion.Balance balance, float authoredBodyYcm, float lane) {
        return supported(right,left,lowerWeight,phase,walkSpeed,moving,directionX,directionForward,strideScale,kneeDegrees,balance,authoredBodyYcm,lane,0);
    }
    /**
     * @param crouchCm lowers (positive) or raises (negative) the pelvis; soles stay planted.
     */
    public static LowerBody supported(Matrix4f right, Matrix4f left, float lowerWeight,
                                      float phase, float walkSpeed, float moving, float directionX, float directionForward,
                                      float strideScale, float kneeDegrees, PresentationMotion.Balance balance, float authoredBodyYcm, float lane,
                                      float crouchCm) {
        float magnitude = (float) Math.hypot(directionX, directionForward);
        float dx = magnitude > .001f ? directionX / magnitude : 0;
        float df = magnitude > .001f ? directionForward / magnitude : 1;
        float gait = Math.clamp(moving * (1 - lowerWeight), 0, 1);
        float reach = stepReach(strideScale) * moving;
        float lift = 12 * (1 - (float) Math.cos(Math.toRadians(kneeDegrees) * .5)) * moving;
        float cycle = phase * .6662f;
        var r = foot(authoredSole(right, false), false, lowerWeight, cycle, reach, lift, dx, df, lane);
        var l = foot(authoredSole(left, true), true, lowerWeight, cycle + (float) Math.PI, reach, lift, dx, df, lane);
        float hipX = balance.x() / 6.25f, hipZ = -balance.z() / 6.25f;
        float crouch = Float.isFinite(crouchCm) ? crouchCm / 6.25f : 0;
        // The planted foot determines pelvis height. The swing foot can fold its knee independently.
        float lowest = Math.max(requiredDrop(r, -1.9f + hipX, hipZ), requiredDrop(l, 1.9f + hipX, hipZ));
        float drop = lowest + Math.max(.12f, authoredBodyYcm / 6.25f + .02f);
        float hipY = 12 + drop - authoredBodyYcm / 6.25f;
        if (crouch != 0) {
            // hipY grows downward: a larger drop folds the knees, and never below the reach floor.
            drop = Math.max(lowest + authoredBodyYcm / 6.25f + .02f, drop + crouch);
            hipY = 12 + drop - authoredBodyYcm / 6.25f;
        }
        float rightYaw = right == null ? 0 : -right.getUnnormalizedRotation(new Quaternionf()).getEulerAnglesXYZ(new Vector3f()).y * lowerWeight;
        float leftYaw = left == null ? 0 : -left.getUnnormalizedRotation(new Quaternionf()).getEulerAnglesXYZ(new Vector3f()).y * lowerWeight;
        return new LowerBody(solve(new Vector3f(-1.9f + hipX, hipY, hipZ), r, rightYaw, support(cycle,gait)),
                solve(new Vector3f(1.9f + hipX, hipY, hipZ), l, leftYaw, support(cycle+(float)Math.PI,gait)), drop * 6.25f, dx, df, cycle);
    }

    /** Comfortable step reach; speed increases cadence instead of stretching the leg without limit. */
    public static float stepReach(float strideScale) { return Math.clamp(7 * strideScale, 2.8f, 6.5f); }

    /** Re-solve both attached hips after pelvis turn; planted soles keep their previous heading. */
    public static LowerBody turn(LowerBody base, SpearTurn.Feet turn, float moving) {
        Vector3f center=new Vector3f((base.right.pose.x+base.left.pose.x)*.5f,
                base.right.pose.y,(base.right.pose.z+base.left.pose.z)*.5f);
        Vector3f rHip=rotate(new Vector3f(-1.9f,0,0),turn.pelvisYaw()).add(center);
        Vector3f lHip=rotate(new Vector3f(1.9f,0,0),turn.pelvisYaw()).add(center);
        Vector3f r=turnSole(base.right.sole,center,false,turn.rightYaw(),moving,turn.rightLift());
        Vector3f l=turnSole(base.left.sole,center,true,turn.leftYaw(),moving,turn.leftLift());
        float bodyY=12+base.pelvisDropCm/6.25f-center.y;
        float drop=Math.max(requiredDrop(r,rHip.x,rHip.z),requiredDrop(l,lHip.x,lHip.z))+Math.max(.12f,bodyY+.02f);
        rHip.y=lHip.y=12+drop-bodyY;
        return new LowerBody(solve(rHip,r,base.right.pose.yRot+turn.rightYaw(),Math.min(base.right.support,turn.rightSupport())),
                solve(lHip,l,base.left.pose.yRot+turn.leftYaw(),Math.min(base.left.support,turn.leftSupport())),
                drop*6.25f,base.directionX,base.directionForward,base.phase,turn.pelvisYaw());
    }
    private static Vector3f turnSole(Vector3f sole,Vector3f center,boolean left,float yaw,float moving,float lift) {
        Vector3f at=new Vector3f(sole);
        if(moving>.001f) {
            var side=new Vector3f(left?2.3f:-2.3f,0,0);
            at.add(rotate(new Vector3f(side),yaw).sub(side));
        } else {
            float y=at.y; at.sub(center);at.y=0; rotate(at,yaw).add(center);at.y=y-lift;
        }
        return at;
    }
    private static Vector3f rotate(Vector3f at,float yaw) { return new Quaternionf().rotationY(yaw).transform(at); }
    private static float support(float phase,float moving) {
        if(moving<.001f)return 1;
        double t=(phase%(Math.PI*2)+Math.PI*2)%(Math.PI*2);
        return t>=Math.PI?1:0;
    }

    private static Vector3f foot(Vector3f authored, boolean left, float weight, float cycle,
                                 float reach, float lift, float dx, float forward, float lane) {
        float t = (float) ((cycle % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2));
        boolean swing = t < Math.PI;
        float u = swing ? t / (float) Math.PI : (t - (float) Math.PI) / (float) Math.PI;
        // Both ends match the planted foot's backward velocity. A cosine swing
        // stopped relative to the pelvis at contact, then snapped to ground speed.
        float along = swing ? swingTravel(u) : 1 - 2 * u;
        float height = swing ? (float) Math.pow(Math.sin(Math.PI * u), 2) * lift : 0;
        float side = left ? 1 : -1;
        var moving = new Vector3f(side * 2.3f + dx * reach * along,
                24 - height, -forward * reach * along + side * lane * 2.5f * Math.min(1,Math.abs(dx)*2));
        // Sideways feet pass in separate fore/aft lanes, rather than clamping a planted foot in X.
        moving.lerp(authored, Math.clamp(weight, 0, 1));
        return moving;
    }

    static float swingTravel(float u) {
        float t=u>.5f?1-u:u;
        float value=-1+t*(-2+t*(12-8*t));
        return u>.5f?-value:value;
    }

    private static float requiredDrop(Vector3f foot, float hipX, float hipZ) {
        float horizontal = (foot.x - hipX) * (foot.x - hipX) + (foot.z - hipZ) * (foot.z - hipZ);
        return Math.max(0, foot.y - 12 - (float) Math.sqrt(Math.max(1, 144 - horizontal)));
    }

    private static SupportedLeg solve(Vector3f hip, Vector3f sole, float yaw, float support) {
        Vector3f y = new Vector3f(sole).sub(hip);
        float distance = y.length();
        if (distance > 12.001f || distance < .001f) throw new IllegalArgumentException("Unreachable supported leg: " + distance);
        float knee = 2 * (float) Math.acos(Math.clamp(distance / 12, 0, 1));
        y.normalize();
        Vector3f z = new Vector3f((float) Math.sin(yaw), 0, (float) Math.cos(yaw));
        z.sub(new Vector3f(y).mul(z.dot(y))).normalize();
        Vector3f x = new Vector3f(y).cross(z).normalize();
        z.set(x).cross(y).normalize();
        Quaternionf rotation = new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, z));
        Vector3f a = rotation.getEulerAnglesZYX(new Vector3f());
        return new SupportedLeg(new PartPose(hip.x, hip.y, hip.z, a.x, a.y, a.z), knee, support, new Vector3f(sole));
    }

    public static float movementTarget(float walkSpeed) {
        float t = Math.clamp((Math.abs(walkSpeed) - .015f) / .065f, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** The time constant is in render ticks; repeated skin/armor/item submissions do not advance it. */
    public static float smoothMovement(float previous, float target, double renderTicks) {
        if (renderTicks <= 0) return previous;
        float result = previous + (target - previous) * (float) (1 - Math.exp(-renderTicks / 2.0));
        return Math.abs(result - target) < .0001f ? target : result;
    }

    public static float lowerWeight(float moving, boolean fullBody, float movingFullBodyWeight) {
        float standing = 1 - Math.clamp(moving, 0, 1);
        return standing + (fullBody ? movingFullBodyWeight : 0) * (1 - standing);
    }

    public static PartPose mix(PartPose nativePose, PartPose animated, float weight) {
        if (weight <= 0) return nativePose;
        if (weight >= 1) return animated;
        Vector3f angles = nativePose.rotation().slerp(animated.rotation(), weight).getEulerAnglesZYX(new Vector3f());
        return new PartPose(nativePose.x + (animated.x - nativePose.x) * weight,
                nativePose.y + (animated.y - nativePose.y) * weight,
                nativePose.z + (animated.z - nativePose.z) * weight, angles.x, angles.y, angles.z);
    }
}

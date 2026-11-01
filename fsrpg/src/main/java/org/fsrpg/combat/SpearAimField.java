package org.fsrpg.combat;

/** Shared cursor/packet field. The grip keeps its authored side of the forward torso. */
public final class SpearAimField {
    public static final double MAX_YAW_DEGREES = 45;
    public static final double MAX_PITCH_DEGREES = 40;
    public static final double CAMERA_PITCH_DEGREES = 24;
    public static final double MIN_FORWARD_DISTANCE = 4.5;
    private static final double YAW_TAN = Math.tan(Math.toRadians(MAX_YAW_DEGREES));
    private static final double PITCH_TAN = Math.tan(Math.toRadians(MAX_PITCH_DEGREES));
    private SpearAimField() {}
    public static boolean contains(AttackAim.Point aim, double bodyYaw) {
        return Math.abs(AttackAim.wrap(aim.yaw()-bodyYaw)) <= MAX_YAW_DEGREES + .0001
                && Math.abs(aim.pitch()) <= MAX_PITCH_DEGREES + .0001;
    }
    /** Preserve a real near hit's eye direction; only extend animation depth to keep the grip on its side. */
    public static AttackAim.Point target(double x,double y,double z,double bodyYaw) {
        double length=Math.sqrt(x*x+y*y+z*z);
        if(!Double.isFinite(length) || !Double.isFinite(bodyYaw) || length<.000001)return null;
        double yaw=AttackAim.wrap(Math.toDegrees(Math.atan2(-x,z))),pitch=-Math.toDegrees(Math.asin(Math.clamp(y/length,-1,1)));
        if(Math.abs(AttackAim.wrap(yaw-bodyYaw))>MAX_YAW_DEGREES+.0001 || Math.abs(pitch)>MAX_PITCH_DEGREES+.0001)return null;
        double forward=(z*Math.cos(Math.toRadians(bodyYaw))-x*Math.sin(Math.toRadians(bodyYaw)))/length;
        if(forward<=0)return null;
        var point=new AttackAim.Point(yaw,pitch,Math.clamp(Math.max(length,MIN_FORWARD_DISTANCE/forward),.5,96));
        return contains(point,bodyYaw)?point:null;
    }
    /** Great-circle entry/return bridges must remain in the same rectangle as their endpoints. */
    public static AttackAim.Point constrain(AttackAim.Point point,double bodyYaw) {
        double relative=Math.clamp(AttackAim.wrap(point.yaw()-bodyYaw),-MAX_YAW_DEGREES,MAX_YAW_DEGREES);
        double pitch=Math.clamp(point.pitch(),-MAX_PITCH_DEGREES,MAX_PITCH_DEGREES);
        double forward=Math.cos(Math.toRadians(relative))*Math.cos(Math.toRadians(pitch));
        return new AttackAim.Point(AttackAim.wrap(bodyYaw+relative),pitch,Math.max(point.distance(),MIN_FORWARD_DISTANCE/forward));
    }
    /** Conservative rectangular pyramid; every point of an admitted screen rectangle is legal. */
    public static boolean containsVector(double x, double y, double forward) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(forward) && forward > 0
                && Math.abs(x) <= forward * YAW_TAN + .000001
                && Math.abs(y) <= forward * PITCH_TAN + .000001;
    }
}

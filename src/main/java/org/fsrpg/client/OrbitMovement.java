package org.fsrpg.client;

/** Camera-relative input expressed in an independently facing actor's local basis. */
public final class OrbitMovement {
    private OrbitMovement() {}
    public record Input(float left,float forward) {}
    public static Input relative(float left,float forward,float cameraYaw,float actorYaw){
        double delta=Math.toRadians(cameraYaw-actorYaw),c=Math.cos(delta),s=Math.sin(delta);
        return new Input((float)(left*c-forward*s),(float)(left*s+forward*c));
    }
    public static float facing(float cameraYaw,float left,float forward){
        return (float)((cameraYaw-Math.toDegrees(Math.atan2(left,forward))+540)%360-180);
    }
}

package org.fsrpg.client;

import org.fsrpg.combat.SpearAimField;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Screen rectangle intersected with the near and far limits of the actual third-person ray. */
public final class SpearCursorField {
    public record Frame(double left, double right, double top, double bottom) {
        public double clampX(double x) { return Math.clamp(x,left,right); }
        public double clampY(double y) { return Math.clamp(y,top,bottom); }
    }
    public record Move(double x,double y,double overflowX,double overflowY) {}
    public static Move move(Frame frame,double x,double y,double dx,double dy) {
        // FOV/camera changes resize the box, but repositioning its cursor is not new mouse input.
        double rawX=frame.clampX(x)+dx,rawY=frame.clampY(y)+dy;
        double nextX=frame.clampX(rawX),nextY=frame.clampY(rawY);
        return new Move(nextX,nextY,rawX-nextX,rawY-nextY);
    }
    /** Keep saved client preferences; expand only their current screen allowance. */
    public static double expandedWidth(double configuredWidth) { return Math.min(.96,configuredWidth*3); }
    public static double expandedHeight(double configuredHeight) { return Math.min(.96,configuredHeight*2); }
    private SpearCursorField() {}
    public static Vector3f ray(double x,double y,double height,double fov,Quaternionf cameraRotation) {
        double tan=Math.tan(Math.toRadians(fov)/2);
        return new Vector3f((float)(x*2/height*tan),(float)(-y*2/height*tan),-1).normalize().rotate(cameraRotation);
    }
    public static Frame frame(double width,double height,double fov,double requestedWidth,double requestedHeight,
                              Quaternionf cameraRotation,Vector3f cameraFromEye,double bodyYaw) {
        var localRotation=new Quaternionf().rotationY((float)Math.toRadians(bodyYaw));
        var origin=new Vector3f(cameraFromEye).rotate(localRotation);
        var rotation=new Quaternionf(localRotation).mul(cameraRotation);
        double maxX=width*requestedWidth/2,maxY=height*requestedHeight/2;
        // Pitch limits depend on the center ray, so the top and bottom shrink separately.
        double top=-edge(maxY,v->legal(0,-v,height,fov,rotation,origin));
        double bottom=edge(maxY,v->legal(0,v,height,fov,rotation,origin));
        double left=-edge(maxX,v->legal(-v,top,height,fov,rotation,origin)&&legal(-v,bottom,height,fov,rotation,origin));
        double right=edge(maxX,v->legal(v,top,height,fov,rotation,origin)&&legal(v,bottom,height,fov,rotation,origin));
        return new Frame(left,right,top,bottom);
    }
    private interface Legal { boolean test(double value); }
    private static double edge(double maximum,Legal legal) {
        if(!legal.test(0))return 0;
        double low=0,high=maximum;
        for(int i=0;i<32;i++){double middle=(low+high)/2;if(legal.test(middle))low=middle;else high=middle;}
        // Stay inside the field after float camera rotation and HUD integer rounding.
        return Math.max(0,low-.02);
    }
    private static boolean legal(double x,double y,double height,double fov,Quaternionf rotation,Vector3f origin) {
        var direction=ray(x,y,height,fov,rotation);
        if(!SpearAimField.containsVector(direction.x,direction.y,direction.z))return false;
        double t=(SpearAimField.MIN_FORWARD_DISTANCE-origin.z)/direction.z;
        return t>=0 && SpearAimField.containsVector(origin.x+t*direction.x,origin.y+t*direction.y,SpearAimField.MIN_FORWARD_DISTANCE);
    }
    public static double minimumRayDistance(Vector3f originFromEye,Vector3f direction,double bodyYaw) {
        var rotation=new Quaternionf().rotationY((float)Math.toRadians(bodyYaw));
        var origin=new Vector3f(originFromEye).rotate(rotation);
        var ray=new Vector3f(direction).rotate(rotation);
        return ray.z>0 ? Math.max(0,(SpearAimField.MIN_FORWARD_DISTANCE-origin.z)/ray.z) : Double.POSITIVE_INFINITY;
    }
}

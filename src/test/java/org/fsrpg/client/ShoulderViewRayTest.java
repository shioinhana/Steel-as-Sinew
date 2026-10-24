package org.fsrpg.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/** Independent forward projection verifies the inverse ray over real view effects. */
public final class ShoulderViewRayTest {
    private static int checks;
    public static void main(String[] args) {
        for(float fov:new float[]{30,65,90,110})for(float aspect:new float[]{4f/3,16f/9,21f/9})
            for(float yaw:new float[]{-175,-60,0,80,179})for(float pitch:new float[]{-75,0,75})
                for(int effect=0;effect<4;effect++)verify(fov,aspect,yaw,pitch,effect);
        var origin=new ShoulderViewRay.Point(0,0,0);
        check(ShoulderViewRay.from(origin,new Matrix4f(),new Matrix4f().zero())==null,"singular matrix rejected");
        check(ShoulderViewRay.from(new ShoulderViewRay.Point(Double.NaN,0,0),new Matrix4f(),new Matrix4f())==null,"non-finite eye rejected");
        check(ShoulderViewRay.from(origin,new Matrix4f(),new Matrix4f().m00(Float.NaN))==null,"non-finite projection rejected");
        var plain=ShoulderViewRay.from(origin,new Matrix4f(),new Matrix4f().perspective(1.1f,1.6f,.05f,500));
        near(plain.origin().x(),0,1e-10,"plain eye x");near(plain.origin().y(),0,1e-10,"plain eye y");
        near(plain.direction().z(),-1,1e-10,"plain native forward");
        check(!plain.project(new ShoulderViewRay.Point(0,0,5)).inFront(),"behind-camera reticle hidden");
        System.out.println("ShoulderViewRayTest passed: "+checks+" checks");
    }
    private static void verify(float fov,float aspect,float yaw,float pitch,int effect) {
        var camera=new ShoulderViewRay.Point(12_345_678.25,97.62,-23_456_789.5);
        var rotation=new Quaternionf().rotationYXZ((float)Math.toRadians(yaw),(float)Math.toRadians(pitch),.035f);
        var view=new Matrix4f().rotation(rotation.conjugate(new Quaternionf()));
        var projection=new Matrix4f().perspective((float)Math.toRadians(fov),aspect,.05f,1024);
        if(effect>=1)projection.translate(.08f,-.05f,0).rotateZ(.03f).rotateX(.045f);
        if(effect>=2)projection.rotateY(-1.2f).rotateZ(-.15f).rotateY(1.2f);
        if(effect>=3)projection.rotate(.6f,0,.70710677f,.70710677f).scale(1.08f,1,1).rotate(-.6f,0,.70710677f,.70710677f);
        var savedView=new Matrix4f(view);var savedProjection=new Matrix4f(projection);
        var frame=ShoulderViewRay.from(camera,view,projection);
        check(frame!=null,"invertible visible frame");
        check(view.equals(savedView)&&projection.equals(savedProjection),"renderer matrices preserved");
        var d=frame.direction();near(d.x()*d.x()+d.y()*d.y()+d.z()*d.z(),1,1e-10,"unit world ray");
        for(double distance:new double[]{.1,.5,3,5,64}) {
            var p=new ShoulderViewRay.Point(frame.origin().x()+d.x()*distance,frame.origin().y()+d.y()*distance,frame.origin().z()+d.z()*distance);
            // Separate float forward path matches the native renderer, not the inverse implementation.
            var clip=new Vector4f((float)(p.x()-camera.x()),(float)(p.y()-camera.y()),(float)(p.z()-camera.z()),1);
            view.transform(clip);projection.transform(clip);
            check(clip.w>0,"ray points in front");
            near(clip.x/clip.w,0,3e-5,"reticle x remains screen center with view effects");
            near(clip.y/clip.w,0,3e-5,"reticle y remains screen center with view effects");
            var projected=frame.project(p);
            check(projected.inFront(),"native directional target projection visible");
            near(projected.x(),clip.x/clip.w,3e-5,"HUD projection x agrees with native world");
            near(projected.y(),clip.y/clip.w,3e-5,"HUD projection y agrees with native world");
        }
    }
    private static void near(double actual,double expected,double epsilon,String message) {
        check(Double.isFinite(actual)&&Math.abs(actual-expected)<=epsilon,message+": "+actual);
    }
    private static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
}

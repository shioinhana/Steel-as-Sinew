package org.fsrpg.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Checks the actual skin surface at the waist, hip caps and original chest corners. */
public final class TorsoConnectionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        for(float yaw:new float[]{-1.8f,-1,-.4f,0,.4f,1,1.8f})for(float bank:new float[]{-.3f,0,.3f})for(float pelvisYaw:new float[]{-.9f,0,.9f}) {
            var body=new Matrix4f().translation(2,74,-3).rotateY(-yaw).rotateZ(bank);
            var feet=new SpearTurn.Feet(pelvisYaw,pelvisYaw,pelvisYaw,0,0,1,1);
            var lower=Locomotion.turn(Locomotion.supported(null,null,1,0,0,0,0,1,.72f,48,new PresentationMotion.Balance(.32f,0,.48f)),feet,0);
            var torso=new TorsoDeformation(body,lower);
            var r=lower.right().pose();var l=lower.left().pose();
            var center=new Vector3f((r.x()+l.x())/2,(r.y()+l.y())/2,(r.z()+l.z())/2);
            var pelvis=new Quaternionf().rotationY(pelvisYaw);
            for(float x:new float[]{-4,4})for(float z:new float[]{-2,2}) {
                check(torso.point(x,12,z).mul(16).distance(pelvis.transform(new Vector3f(x,0,z)).add(center))<.0002f,"Rendered waist misses pelvis");
                var source=body.transformPosition(new Vector3f(x*6.25f,75,-z*6.25f));
                source.set(source.x/6.25f,(150-source.y)/6.25f,-source.z/6.25f);
                check(torso.point(x,0,z).mul(16).distance(source)<.0002f,"Chest or shoulder surface changes");
            }
            for(var leg:new Locomotion.SupportedLeg[]{lower.right(),lower.left()}) {
                var p=leg.pose();var skin=new LegDeformation(p.x(),p.y(),p.z(),p.xRot(),p.yRot(),p.zRot(),leg.knee(),true,pelvisYaw);
                for(float x:new float[]{-2,2})for(float z:new float[]{-2,2}) {
                    var expected=pelvis.transform(new Vector3f(x,0,z)).add(p.x(),p.y(),p.z());
                    check(skin.point(x,0,z).mul(16).distance(expected)<.0002f,"Upper leg cap twists away from waistband");
                }
            }
            for(float y=0;y<=12;y+=.25f) {
                var x=torso.point(4,y,0).sub(torso.point(-4,y,0)).mul(16);
                var z=torso.point(0,y,2).sub(torso.point(0,y,-2)).mul(16);
                check(Math.abs(x.length()-8)<.001f&&Math.abs(z.length()-4)<.001f&&Math.abs(x.dot(z))<.001f,"Lumbar cross-section collapses or stretches");
                var n=torso.normal(4,y,0,1,0,0);
                check(Float.isFinite(n.x)&&Math.abs(n.length()-1)<.001f,"Invalid twisted torso normal");
            }
        }
        System.out.println("TorsoConnectionTest passed: "+checks+" checks (actual torso waist, hip caps, shoulder preservation, volume and normals)");
    }
}

package org.fsrpg.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix4f;

public final class LegDeformationTest {
    private static int checks;
    public static void main(String[] args) {
        for(float hip:new float[]{-.65f,0,.65f}) for(float yaw:new float[]{-.6f,0,.6f}) {
            var neutral=new LegDeformation(-1.9f,12,0,hip,yaw,0,0);
            var rotation=new Quaternionf().rotationZYX(0,yaw,hip);
            for(float y:new float[]{0,5.5f,6,6.5f,12}) for(float x:new float[]{-2.25f,2.25f}) {
                var expected=rotation.transform(new Vector3f(x,y,2.25f)).add(-1.9f,12,0).div(16);
                check(neutral.point(x,y,2.25f).distance(expected)<.00001,"zero flexion matches original inflated armor cube");
            }
            for(float bend:new float[]{.2f,.6f,1.2f}) {
                var leg=new LegDeformation(-1.9f,12,0,hip,yaw,0,bend);
                for(float y:new float[]{0,5.5f,5.75f,6,6.25f,6.5f,12})
                    check(Math.abs(leg.point(-2.25f,y,0).distance(leg.point(2.25f,y,0))-4.5/16)<.00001,"knee section preserves leg and armor width");
                for(float seam:new float[]{5.5f,6,6.5f})
                    check(leg.point(2,seam-.0001f,2).distance(leg.point(2,seam+.0001f,2))<.0001,"knee band has no disconnected seam");
            }
        }
        var bent=new LegDeformation(0,12,0,0,0,0,1);
        check(bent.point(0,12,0).y<1.5f,"recovering foot lifts off ground");
        check(Math.abs(bent.point(0,12,0).z)<.00001,"half-angle hip compensation preserves foot trajectory direction");
        for(int i=0;i<120;i++) {
            float phase=i*.1f, right=LegDeformation.knee(phase,1,48,false), left=LegDeformation.knee(phase,1,48,true);
            check(right==0 || left==0,"support leg remains straight while other knee recovers");
            check(right<=Math.toRadians(48)+.00001&&left<=Math.toRadians(48)+.00001,"knee respects configured maximum");
        }
        check(LegDeformation.knee(1,0,48,false)==0,"standing pose remains authored and unbent");
        supportedGait();
        System.out.println("LegDeformationTest passed: "+checks+" checks");
    }
    private static void supportedGait() {
        float h=.0001f;
        check(Math.abs((Locomotion.swingTravel(h)-Locomotion.swingTravel(0))/h+2)<.01,
                "liftoff retains the planted foot velocity instead of stopping against the pelvis");
        check(Math.abs((Locomotion.swingTravel(1)-Locomotion.swingTravel(1-h))/h+2)<.01,
                "touchdown meets ground-relative support velocity without a velocity discontinuity");
        var easyStand=Locomotion.supported(new Matrix4f().translation(-11.875f,75,0),
                new Matrix4f().translation(11.875f,75,0),1,0,0,0,0,1,.72f,48,new PresentationMotion.Balance(0,0,0));
        check(easyStand.right().knee()<Math.toRadians(18)&&easyStand.left().knee()<Math.toRadians(18),
                "ordinary ready stance has relaxed knees, not a permanent crouch");
        var right=new Matrix4f().translation(-16.875f,75,9).rotateY(-.2f);
        var left=new Matrix4f().translation(16.875f,75,-9).rotateY(.2f);
        var balance=new PresentationMotion.Balance(3,0,4);
        var standing=Locomotion.supported(right,left,1,0,0,0,0,1,.72f,48,balance);
        check(Math.abs(standing.right().pose().x()-(-1.9f+3/6.25f))<.0001,"legacy authored hip offset is treated as foot intent, never hip displacement");
        check(standing.right().sole().distance(Locomotion.authoredSole(right,false))<.0001,"legacy authored stance retains its foot location");
        for(int direction=0;direction<8;direction++)for(int i=0;i<=96;i++) {
            float angle=direction*(float)Math.PI/4;
            var gait=Locomotion.supported(right,left,0,i*.12f,1,1,(float)Math.sin(angle),(float)Math.cos(angle),.72f,48,balance);
            check(gait.right().support()+gait.left().support()>=1,"one real sole is always planted");
            check(gait.right().sole().distance(gait.left().sole())>4.49,"feet pass in separate lanes with room for the boot width");
            check(Math.abs(gait.left().pose().x()-gait.right().pose().x()-3.8f)<.0001,"both hips stay attached to one pelvis");
            for(var solved:new Locomotion.SupportedLeg[]{gait.right(),gait.left()}) {
                var p=solved.pose();var skin=new LegDeformation(p.x(),p.y(),p.z(),p.xRot(),p.yRot(),p.zRot(),solved.knee(),true);
                var hip=skin.point(0,0,0).mul(16);var knee=skin.point(0,6,0).mul(16);var sole=skin.point(0,12,0).mul(16);
                check(Math.abs(hip.distance(knee)-6)<.0001&&Math.abs(knee.distance(sole)-6)<.0001,"inverse support preserves both six-pixel bones");
                check(sole.distance(solved.sole())<.0001,"actual skin sole reaches the directional foot target");
                for(float x:new float[]{-2.25f,2.25f})for(float z:new float[]{-2.25f,2.25f}) {
                    var corner=skin.point(x,12.25f,z).mul(16);
                    check(Math.abs(corner.y-sole.y)<.0001&&corner.y<=24.0001f,"skin and inflated boot corners share a flat, nonpenetrating sole");
                }
                check(skin.point(-2.25f,12.25f,0).distance(skin.point(2.25f,12.25f,0))*16>4.4999,"ankle adjustment retains armor width");
            }
        }
        var sideways=Locomotion.supported(right,left,0,0,1,1,1,0,.72f,48,new PresentationMotion.Balance(0,0,0));
        var sidewaysLater=Locomotion.supported(right,left,0,.5f,1,1,1,0,.72f,48,new PresentationMotion.Balance(0,0,0));
        check(Math.abs(sideways.right().sole().z()-sidewaysLater.right().sole().z())<.0001,"pure strafe travels laterally within a fixed fore/aft lane");
        for(float bodyY:new float[]{-4,-1.7f,0,1.7f,4}) {
            var lower=Locomotion.supported(right,left,1,0,0,0,0,1,.72f,48,balance,bodyY,0);
            float waistNativeY=12+(lower.pelvisDropCm()-bodyY)/6.25f;
            check(Math.abs(lower.right().pose().y()-waistNativeY)<.0001,"authored breath and crouch move waist and both hips together");
        }
        for(float lowerWeight:new float[]{0,.25f})for(int direction=0;direction<8;direction++) {
            float a=direction*(float)Math.PI/4, dx=(float)Math.sin(a), df=(float)Math.cos(a);
            var clock=new PresentationMotion();clock.observePosition(0,0,0,dx*.216,df*.216);
            Locomotion.LowerBody previous=null;float previousTime=0;
            for(int i=0;i<150;i++) {
                float time=i/3f,phase=clock.gaitPhase(time,1,lowerWeight,.72f);
                var lower=Locomotion.supported(right,left,lowerWeight,phase,1,1,dx,df,.72f,48,new PresentationMotion.Balance(0,0,0));
                if(previous!=null&&lower.phase()>previous.phase()) {
                    var before=new Locomotion.SupportedLeg[]{previous.right(),previous.left()};var after=new Locomotion.SupportedLeg[]{lower.right(),lower.left()};
                    for(int j=0;j<2;j++)if(before[j].support()==1&&after[j].support()==1) {
                        float worldX=after[j].sole().x()/16+dx*.216f*time-(before[j].sole().x()/16+dx*.216f*previousTime);
                        float worldZ=-after[j].sole().z()/16+df*.216f*time-(-before[j].sole().z()/16+df*.216f*previousTime);
                        check(Math.hypot(worldX,worldZ)<.00001,"constant travel leaves the actual support sole stationary in world XZ: direction="+direction+" blend="+lowerWeight+" i="+i+" foot="+j+" drift="+worldX+","+worldZ+" phases="+previous.phase()+","+lower.phase());
                    }
                }
                previous=lower;previousTime=time;
            }
        }
    }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}

package org.fsrpg.client;

import org.joml.Vector3f;

/** Ground contact and hip attachment are checked from the deformed skin, not IK targets. */
public final class SpearTurnTest {
    private static int checks;
    private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    private static Vector3f rendered(Locomotion.SupportedLeg leg,float x,float y,float z) {
        var p=leg.pose();return new LegDeformation(p.x(),p.y(),p.z(),p.xRot(),p.yRot(),p.zRot(),leg.knee(),true).point(x,y,z).mul(16);
    }
    public static void main(String[] args) {
        for(float direction:new float[]{-1,1}) {
            var state=new SpearTurn.State();float maximumLift=0,previousYaw=0;int rightSwings=0,leftSwings=0;
            for(int frame=0;frame<=480;frame++) {
                double tick=frame/3.;float yaw=direction*(float)Math.toRadians(frame<240?65:-40);
                var feet=state.advance(tick,yaw,0,0);
                check(feet.equals(state.advance(tick,yaw,0,0)),"Repeated skin/armor sample advances turn");
                check(feet.rightSupport()==1||feet.leftSupport()==1,"Both turning feet airborne");
                maximumLift=Math.max(maximumLift,Math.max(feet.rightLift(),feet.leftLift()));
                if(feet.rightLift()>.1f)rightSwings++;if(feet.leftLift()>.1f)leftSwings++;
                check(Math.abs(feet.pelvisYaw()-previousYaw)<Math.toRadians(2.1),"Pelvis snaps at 60Hz");previousYaw=feet.pelvisYaw();
                var base=Locomotion.supported(null,null,1,0,0,0,0,1,.72f,48,new PresentationMotion.Balance(0,0,0));
                var lower=Locomotion.turn(base,feet,0);
                var r=lower.right().pose();var l=lower.left().pose();
                check(Math.abs(Math.hypot(l.x()-r.x(),l.z()-r.z())-3.8)<.0001,"Hips detached from shared pelvis");
                check(Math.abs(Math.atan2(-(l.z()-r.z()),l.x()-r.x())-feet.pelvisYaw())<.0001,"Hip line fails to follow pelvis yaw");
                for(var leg:new Locomotion.SupportedLeg[]{lower.right(),lower.left()}) {
                    check(rendered(leg,0,12,0).distance(leg.sole())<.01,"Rendered boot differs from sole target");
                    for(float x:new float[]{-2.25f,2.25f})for(float z:new float[]{-2.25f,2.25f}) {
                        float y=rendered(leg,x,12.25f,z).y;
                        check(y<=24.03f,"Boot penetrates floor");
                        if(leg.support()>.5f)check(Math.abs(y-24)<.03,"Planted boot corner floats");
                    }
                }
                if(frame==239||frame==480)check(Math.abs(feet.pelvisYaw()-yaw)<.02&&Math.abs(feet.rightYaw()-yaw)<.08&&Math.abs(feet.leftYaw()-yaw)<.08,"Feet do not finish following waist");
            }
            check(maximumLift>.8f&&maximumLift<1,"Turn lacks modest foot lift");
            check(rightSwings>30&&leftSwings>30,"Turn does not alternate both feet");
        }
        for(float dx:new float[]{-1,0,1})for(float forward:new float[]{-1,1}) {
            var state=new SpearTurn.State();
            for(int frame=0;frame<120;frame++) {
                float phase=frame*.1f;
                var feet=state.advance(frame/3.,.6f,0,1);
                var base=Locomotion.supported(null,null,0,phase,.6f,1,dx,forward,.72f,48,new PresentationMotion.Balance(0,0,0));
                var lower=Locomotion.turn(base,feet,1);
                check(lower.directionX()==base.directionX()&&lower.directionForward()==base.directionForward(),"Waist turn changes actual movement vector");
                check(lower.right().support()==1||lower.left().support()==1,"Walking turn removes supporting foot");
                check(feet.rightLift()==0&&feet.leftLift()==0,"Idle turning lift applied on top of walking gait");
            }
        }
        boolean rejected=false;try{new SpearTurn.Settings(Float.NaN,6,.5f,.9f);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Non-finite settings accepted");
        System.out.println("SpearTurnTest passed: "+checks+" checks (rendered boot soles, shared rotated pelvis, alternating turn, movement direction, same-frame stability)");
    }
}

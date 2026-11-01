package org.fsrpg.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Reauthored pose, authoritative phase timing, combined waist inertia and supported skin seam checks. */
public final class RapierMotionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double a,double b,double tolerance,String message){check(Math.abs(a-b)<=tolerance,message+" actual="+a+" expected="+b);}
    private static double angle(Pose a,Pose b){return Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(a.fsQuaternion().normalize().dot(b.fsQuaternion().normalize())),0,1)));}
    private static Map<String,Pose> sample(ModelDocument model,int cut,double tick){var clip=model.rig().clips().get("legacy_rapier_attack"+cut+"_upper");return LegacyPlayback.sample(model,clip,RapierMotion.clipTime(clip,tick,cut==5?4:3,3,cut==5?5:3));}
    private static Vector3f bladeAxis(boolean fp){var m=new Matrix4f().scaling(-1,1,-1);if(fp)m.rotateZ((float)Math.toRadians(41.987212496));else m.mul(HeldItemTransform.legacyRapierItem());return m.transformDirection(new Vector3f(1,1,0)).normalize();}
    public static void main(String[] args)throws Exception{
        Path assets=Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");ModelDocument sword=ModelLoader.load(assets.resolve("legacy_rapier.fsvoxel")),fp=ModelLoader.load(assets.resolve("legacy_rapier_fp.fsvoxel")),spear=ModelLoader.load(assets.resolve("spear.fsvoxel"));
        for(ModelDocument model:List.of(sword,fp))for(int cut=1;cut<=5;cut++){
            var clip=model.rig().clips().get("legacy_rapier_attack"+cut+"_upper");double w=cut==5?4:3,a=3,r=cut==5?5:3,total=w+a+r;check(RapierMotion.cut(clip),"New cut is explicitly tagged without changing other weapons");
            near(RapierMotion.clipTime(clip,0,w,a,r),0,1e-10,"Preparation begins at the first source frame");near(RapierMotion.clipTime(clip,w,w,a,r)/clip.duration(),.26,1e-9,"Preparation ends on authoritative ACTIVE boundary");near(RapierMotion.clipTime(clip,w+a,w,a,r)/clip.duration(),.86,1e-9,"Follow-through remains inside authoritative ACTIVE");near(RapierMotion.clipTime(clip,total,w,a,r),clip.duration(),1e-9,"Recovery reaches the authored handoff");
            double previous=-1,peakSpeed=0,peakTick=-1;Vector3f last=null;double preparationTravel=0;
            for(int n=0;n<=total*12;n++){double tick=n/12.;double seconds=RapierMotion.clipTime(clip,tick,w,a,r);check(seconds>=previous,"Clip time never rewinds");previous=seconds;var pose=sample(model,cut,tick);var world=model.worldMatrices(pose);Vector3f tip=world.get("right_item").transformPosition(bladeAxis(model==fp).mul(75));
                if(last!=null){double speed=tip.distance(last)*240;if(speed>peakSpeed){peakSpeed=speed;peakTick=tick;}if(tick<=1)preparationTravel+=tip.distance(last);}last=tip;
                check(pose.get("right_forearm").rotation().vector().isFinite(),"Elbow stays finite");
                var actualBlade=world.get("right_item").transformDirection(bladeAxis(model==fp)).normalize();
                var forearm=world.get("right_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
                check(forearm.dot(actualBlade)>=Math.cos(Math.toRadians(34.01)),"Every actual combat-clock sample preserves the rigid-grip wrist bound");
            }
            check(preparationTravel>.5,"First fifty milliseconds visibly prepare the blade instead of holding a frame");check(angle(sample(model,cut,0).get("right_arm"),sample(model,cut,.33).get("right_arm"))>.1,"First native sixty-Hz frame already moves the shoulder");check(peakSpeed>20&&peakTick>=w&&peakTick<=w+a,"Fastest blade sample lies inside actual server ACTIVE: cut="+cut+" tick="+peakTick);
            for(double boundary:new double[]{w,w+a}){double epsilon=1e-4,at=RapierMotion.clipTime(clip,boundary,w,a,r);double before=(at-RapierMotion.clipTime(clip,boundary-epsilon,w,a,r))/epsilon,after=(RapierMotion.clipTime(clip,boundary+epsilon,w,a,r)-at)/epsilon;near(before,after,.0001,"Source clock velocity remains continuous across combat phase packets");}
            var end=LegacyPlayback.sample(model,clip,clip.duration()*.86);var next=sample(model,cut%5+1,0);
            var handoffBones=model==fp?List.of("right_arm","right_forearm","right_item","left_arm"):List.of("body","head","hip","right_arm","right_forearm","right_hand","right_item","left_arm","left_forearm","left_hand");
            for(String bone:handoffBones){near(angle(end.get(bone),next.get(bone)),0,.25,"Combo handoff preserves joint orientation "+bone);near(end.get(bone).position().vector().distance(next.get(bone).position().vector()),0,.001,"Combo handoff preserves locator position "+bone);}
        }
        check(RapierMotion.fencing(sword.rig().clips().get("legacy_rapier_attack5_upper")),"Complete five-phrase fencing chain is authored");check(!RapierMotion.cut(spear.rig().clips().get("spear_idle")),"Spear is unaffected");
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(float reaction:new float[]{-3,0,3}){
            var combined=RapierMotion.combine(x,z,4,1,reaction);check(Math.hypot(combined.lateral(),combined.forward())<=5.00001,"Movement plus opposite attack reaction has one bounded vector");if(x==0&&z==0)near(combined.lateral(),reaction,.0001,"Still attack reaction has its intended direction");if(reaction==0){check(Math.signum(combined.lateral())==x,"Real left/right travel selects the corresponding lean");check(Math.signum(combined.forward())==z,"Real forward/back travel selects the corresponding lean");}
        }
        near(RapierMotion.combine(1,0,3,1,-3).lateral(),0,1e-6,"Opposite movement and stroke reaction cancel rather than overwrite");
        var state=new RapierMotion.State();state.advance(0,new RapierMotion.Lean(4,0),1.4);var at=state.advance(1,new RapierMotion.Lean(4,0),1.4);check(at.lateral()>0&&at.lateral()<4,"Waist approaches movement smoothly");check(state.advance(1,new RapierMotion.Lean(-4,0),1.4).equals(at),"Repeated same-tick sampling cannot integrate the lean twice");
        var clip=sword.rig().clips().get("legacy_rapier_attack2_upper");for(int n=0;n<=48;n++){double tick=n/6.;var upper=SwordMotion.waist(sword,SwordMotion.sampleUpper(sword,clip,RapierMotion.clipTime(clip,tick,3,4,1),0,0,0));var lower=new PoseLayers.Playback(spear.rig(),PoseLayers.DEFAULT_STANCE).sample(0,0,0,0,0,.25f,55);var pose=SwordMotion.lower(sword,upper,spear.worldMatrices(lower.pose()));var lean=RapierMotion.combine(.6f,-.3f,4,1,RapierMotion.reaction(clip,RapierMotion.clipTime(clip,tick,3,4,1)));pose=PresentationMotion.lean(pose,lean.lateral(),lean.forward(),.6f);var balance=RapierMotion.balance(lean,2);var world=sword.worldMatrices(pose);var supported=Locomotion.supported(world.get("right_leg"),world.get("left_leg"),0,(float)tick,.16f,1,.6f,-.3f,.55f,48,PresentationMotion.withAuthoredHipShift(pose,SwordMotion.waistRest(),balance),pose.get("body").position().y()-SwordMotion.waistRest().y(),0);pose=PresentationMotion.shiftTorso(pose,new PresentationMotion.Balance(balance.x(),balance.y()-supported.pelvisDropCm(),balance.z()));world=sword.worldMatrices(pose);var surface=new TorsoDeformation(world.get("body"),supported);var r=supported.right().pose();var l=supported.left().pose();var pelvis=new Vector3f((r.x()+l.x())*.5f,(r.y()+l.y())*.5f,(r.z()+l.z())*.5f);
            for(float x:new float[]{-4,0,4})for(float z:new float[]{-2,2}){var waist=new Quaternionf().rotationY(supported.pelvisYaw()).transform(new Vector3f(x,0,z)).add(pelvis);near(surface.point(x,12,z).mul(16).distance(waist),0,.0005,"Composed recoil/movement keeps the rendered waist attached to supported pelvis");}
        }
        System.out.println("RapierMotionTest passed: "+checks+" checks");
    }
}

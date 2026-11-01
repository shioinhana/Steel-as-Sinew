package org.fsrpg.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Actual official blade axis, anatomical FK and the five authoritative phase handoffs. */
public final class RapierFencingTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double a,double b,double tolerance,String message){check(Math.abs(a-b)<=tolerance,message+" actual="+a+" expected="+b);}
    private static Clip clip(ModelDocument m,int stage){return m.rig().clips().get("legacy_rapier_attack"+stage+"_upper");}
    private static Map<String,Pose> pose(ModelDocument m,Clip c,double seconds){return m.rig().id().endsWith("_fp")?LegacyPlayback.sample(m,c,seconds):SwordMotion.sampleUpper(m,c,seconds,0,0,0);}
    private static Vector3f axis(boolean fp){
        var carrier=new Matrix4f().scaling(-1,1,-1);
        if(fp)carrier.rotateZ((float)Math.toRadians(41.987212496));else carrier.mul(HeldItemTransform.legacyRapierItem());
        return carrier.transformDirection(new Vector3f(1,1,0)).normalize();
    }
    private static Vector3f tip(ModelDocument m,Map<String,Pose> p,boolean fp){return m.worldMatrices(p).get("right_item").transformPosition(axis(fp).mul(75));}
    public static void main(String[] args)throws Exception{
        Path assets=Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        for(boolean fp:new boolean[]{false,true}){
            var model=ModelLoader.load(assets.resolve("legacy_rapier"+(fp?"_fp":"")+".fsvoxel"));
            var guard=pose(model,model.rig().clips().get("legacy_rapier_static_idle"),0);
            check(model.worldMatrices(guard).get("right_item").transformDirection(axis(fp)).normalize().z>.98,"Actual vanilla sprite points along threat line in guard");
            for(int stage=1;stage<=5;stage++){
                var c=clip(model,stage);int w=stage==5?4:3,a=3,r=stage==5?5:3;double total=w+a+r;
                check(RapierMotion.fencing(c),"Five phrases use authored fencing clock");
                near(RapierMotion.clipTime(c,w,w,a,r)/c.duration(),.26,1e-9,"Preparation aligns with real ACTIVE");
                near(RapierMotion.clipTime(c,w+a,w,a,r)/c.duration(),.86,1e-9,"ACTIVE end is the queued handoff");
                double peak=0,peakAt=-1;Vector3f last=null;
                for(int n=0;n<=total*12;n++){
                    double tick=n/12.;var p=pose(model,c,RapierMotion.clipTime(c,tick,w,a,r));var world=model.worldMatrices(p);var point=tip(model,p,fp);
                    if(last!=null&&point.distance(last)>peak){peak=point.distance(last);peakAt=tick;}last=point;
                    near(world.get("right_arm").getTranslation(new Vector3f()).distance(world.get("right_forearm").getTranslation(new Vector3f())),37.5,.001,"Upper arm retains anatomical length");
                    near(world.get("right_forearm").getTranslation(new Vector3f()).distance(world.get(fp?"right_item":"right_hand").getTranslation(new Vector3f())),37.5,.001,"Forearm stays connected to real wrist");
                    var actualBlade=world.get("right_item").transformDirection(axis(fp)).normalize();
                    var forearmDirection=world.get("right_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
                    check(forearmDirection.dot(actualBlade)>=Math.cos(Math.toRadians(34.01)),"Official sword and forearm keep a bounded real wrist flexion: "+stage+" fp="+fp+" tick="+tick);
                    check(actualBlade.z>.68,"Blade keeps a forward threat through the four stroke directions");
                    var elbow=p.get("right_forearm").rotation();
                    // Source frames have exactly zero lateral hinge rotation;
                    // quaternion interpolation of changing flexion + pronation
                    // produces a tiny off-plane component between those frames.
                    var hinge=new org.joml.Quaternionf(p.get("right_forearm").fsQuaternion()).rotateY((float)Math.toRadians(-elbow.y())).transform(new Vector3f(1,0,0));
                    check(elbow.x()>=-135.01&&elbow.x()<=-9.99&&Math.abs(elbow.y())<=30.01&&hinge.x>=Math.cos(Math.toRadians(.10)),"Elbow remains a positive hinge with less than 0.1 degree native interpolation deviation: "+elbow+" stage="+stage+" fp="+fp+" tick="+tick);
                    if(!fp){
                        near(world.get("right_hand").getTranslation(new Vector3f()).distance(world.get("right_item").getTranslation(new Vector3f())),0,.001,"Official locator remains in hand");
                        near(Math.abs(p.get("right_item").fsQuaternion().dot(guard.get("right_item").fsQuaternion())),1,.00001,"Rigid official grip never hides wrist folding in an animated item locator");
                        var torso=world.get("body").transformDirection(new Vector3f(0,0,1));
                        double yaw=Math.toDegrees(Math.atan2(torso.x,torso.z));
                        check(yaw>54&&yaw<75,"Short return turns through the torso without exposing the back");
                    }
                }
                check(peakAt>=w&&peakAt<=w+a,"Fastest actual blade sample is ACTIVE: stage="+stage+" fp="+fp+" tick="+peakAt);
                var contact=pose(model,c,c.duration()*.56);var world=model.worldMatrices(contact);var blade=world.get("right_item").transformDirection(axis(fp)).normalize();
                check(blade.z>.85,"Contact sword keeps a forward component while cuts retain their distinct diagonals: stage="+stage+" fp="+fp+" blade="+blade);
                var prepared=tip(model,pose(model,c,c.duration()*.26),fp);var extended=tip(model,contact,fp);var travel=new Vector3f(extended).sub(prepared);
                switch(stage){
                    case 1,5 -> check(travel.z>(fp?12:23),"Thrust clearly advances on the opponent line: "+travel);
                    case 2 -> check(travel.x>(fp?20:45)&&travel.y>(fp?30:65),"Rising cut has a real low-to-high diagonal official blade trajectory: "+travel);
                    case 3 -> check(travel.x<(fp?-25:-40)&&travel.y<(fp?-40:-65),"Descending cut reverses both diagonal coordinates: "+travel);
                    case 4 -> check(travel.x>(fp?45:80)&&Math.abs(travel.y)<30,"Short return cut sweeps horizontally without a whole-body spin: "+travel);
                }
                var activeEnd=pose(model,c,c.duration()*.86);var next=pose(model,clip(model,stage%5+1),0);var recovered=pose(model,c,c.duration());
                var handoffBones=fp?List.of("right_arm","right_forearm","right_item","left_arm"):List.of("body","head","hip","right_arm","right_forearm","right_hand","right_item","left_arm","left_forearm","left_hand");
                for(String bone:handoffBones){
                    near(Math.abs(activeEnd.get(bone).fsQuaternion().dot(next.get(bone).fsQuaternion())),1,.00001,"Queued ACTIVE handoff does not reset to guard");
                    near(Math.abs(recovered.get(bone).fsQuaternion().dot(guard.get(bone).fsQuaternion())),1,.00001,"Unqueued recovery returns to fencing guard");
                }
                check(RapierMotion.directHandoff(c,clip(model,stage%5+1),w+a,w,a),"Queued active-end chain bypasses entry bridge");
                check(!RapierMotion.directHandoff(c,clip(model,stage%5+1),total,w,a),"Late post-recovery click starts from actual resting pose");
                for(boolean left:new boolean[]{false,true}){
                    var base=new Matrix4f().translation(left?12.5f:-12.5f,75,0);
                    var endFoot=RapierMotion.fencingFoot(base,left,c,c.duration()*.86,0);
                    var nextFoot=RapierMotion.fencingFoot(base,left,clip(model,stage%5+1),0,0);
                    near(endFoot.getTranslation(new Vector3f()).distance(nextFoot.getTranslation(new Vector3f())),0,.0001,
                            "Every actual ACTIVE-end sole target matches next phrase, including fifth-to-first");
                    var nearEnd=RapierMotion.fencingFoot(base,left,c,c.duration()*(.86-1e-4),0);
                    near(nearEnd.getTranslation(new Vector3f()).distance(endFoot.getTranslation(new Vector3f())),0,.0001,
                            "Foot withdrawal arrives smoothly before the queued boundary");
                }
                for(int n=0;n<=48;n++)check(Math.hypot(RapierMotion.recoil(c,c.duration()*n/48.).lateral(),RapierMotion.recoil(c,c.duration()*n/48.).forward())<=3.00001,"Every reaction vector stays gentle");
            }
            for(String branch:List.of("legacy_rapier_rapier_thrust_hit_upper","rapier_riposte","rapier_reprise")){
                var c=model.rig().clips().get(branch);check(c!=null&&RapierMotion.fencing(c),"Right-hand and conditional branches use the same reviewed attack family");
                var start=tip(model,pose(model,c,c.duration()*.26),fp);var end=tip(model,pose(model,c,c.duration()*.56),fp);
                check(end.z-start.z>(fp?12:23),"Conditional branch keeps a genuine forward thrust: "+branch);
                check(!RapierMotion.directHandoff(c,clip(model,1),6,3,3),"Conditional branches blend from the actual pose instead of pretending to be a queued five-stage handoff");
            }
            for(String charge:List.of("rapier_charge","rapier_full_charge"))check(!RapierMotion.fencing(model.rig().clips().get(charge)),"Holding charge does not accidentally use attack phase mapping");
        }
        near(RapierMotion.combine(0,1,3,1,new RapierMotion.Lean(0,-3)).forward(),0,1e-6,"Forward travel and thrust recoil compose instead of overwriting");
        var sword=ModelLoader.load(assets.resolve("legacy_rapier.fsvoxel"));var source=new Matrix4f();var c=clip(sword,5);
        var guard=RapierMotion.fencingFoot(source,false,c,0,0);var extended=RapierMotion.fencingFoot(source,false,c,c.duration()*.56,0);
        check(extended.m32()-guard.m32()>20,"Final thrust adds supported forward sole extension");
        near(RapierMotion.fencingFoot(source,false,c,c.duration()*.56,1).m32(),0,1e-7,"Full native movement owns the gait without a second planted lunge");
        var supported=Locomotion.supported(new Matrix4f().translation(-12.5f,75,extended.m32()),new Matrix4f().translation(12.5f,75,-12),1,0,0,0,0,0,.55f,48,new PresentationMotion.Balance(0,0,0));
        near(supported.right().sole().y,24,.0001,"Lunge front sole stays grounded");near(supported.left().sole().y,24,.0001,"Lunge rear sole stays grounded");
        attachedWaist(sword,ModelLoader.load(assets.resolve("spear.fsvoxel")));
        System.out.println("RapierFencingTest passed: "+checks+" actual blade/phase/attachment checks");
    }
    private static void attachedWaist(ModelDocument sword,ModelDocument spear){
        for(int stage=1;stage<=5;stage++)for(int n=0;n<=24;n++){
            var c=clip(sword,stage);double seconds=c.duration()*n/24.;
            var upper=SwordMotion.waist(sword,SwordMotion.sampleUpper(sword,c,seconds,0,0,0));
            var lower=new PoseLayers.Playback(spear.rig(),PoseLayers.DEFAULT_STANCE).sample(0,0,0,0,0,.25f,55);
            var p=SwordMotion.lower(sword,upper,spear.worldMatrices(lower.pose()));
            var lean=RapierMotion.combine(.6f,-.3f,4,1,RapierMotion.recoil(c,seconds));
            p=PresentationMotion.lean(p,lean.lateral(),lean.forward(),.6f);var balance=RapierMotion.balance(lean,2);var world=sword.worldMatrices(p);
            var right=RapierMotion.fencingFoot(world.get("right_leg"),false,c,seconds,0);
            var left=RapierMotion.fencingFoot(world.get("left_leg"),true,c,seconds,0);
            var support=Locomotion.supported(right,left,1,0,0,0,0,0,.55f,48,
                    PresentationMotion.withAuthoredHipShift(p,SwordMotion.waistRest(),balance),p.get("body").position().y()-SwordMotion.waistRest().y(),0);
            near(support.right().sole().y,24,.0001,"Every phrase keeps the planted front sole grounded");
            near(support.left().sole().y,24,.0001,"Every phrase keeps the planted rear sole grounded");
            p=PresentationMotion.shiftTorso(p,new PresentationMotion.Balance(balance.x(),balance.y()-support.pelvisDropCm(),balance.z()));
            var surface=new TorsoDeformation(sword.worldMatrices(p).get("body"),support);
            var r=support.right().pose();var l=support.left().pose();var pelvis=new Vector3f((r.x()+l.x())*.5f,(r.y()+l.y())*.5f,(r.z()+l.z())*.5f);
            for(float x:new float[]{-4,0,4})for(float z:new float[]{-2,2})
                near(surface.point(x,12,z).mul(16).distance(new Vector3f(x,0,z).add(pelvis)),0,.0005,"Rendered waist remains attached through real lunge and composed movement/recoil");
        }
    }
}

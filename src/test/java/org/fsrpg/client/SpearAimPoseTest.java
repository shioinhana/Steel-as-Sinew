package org.fsrpg.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Anatomical and rendered-grip invariants over the real normal/slim spear clips. */
public final class SpearAimPoseTest {
    private static int checks;
    private static float maximumChestUpError;
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static Map<String,Pose> sample(ModelDocument model,String action,double time,boolean slim) {
        var pose=new HashMap<>(new PoseLayers.Playback(model.rig(),action).sample(time,0,time*20,0,0,.25f,55).pose());
        if(slim)for(String side:List.of("left","right")) {
            var old=pose.get(side+"_arm");var p=old.position();
            pose.put(side+"_arm",new Pose(old.rotation(),new V(p.x()+(side.equals("right")?3.125f:-3.125f),p.y(),p.z()),old.scale()));
        }
        return pose;
    }
    private static Vector3f palm(Map<String,Matrix4f> world,String side) {
        return world.get(side+"_hand").transformPosition(new Vector3f(0,-6.25f,0));
    }
    private static float angle(Quaternionf a,Quaternionf b) {
        return (float)java.lang.Math.toDegrees(2*java.lang.Math.acos(java.lang.Math.clamp(java.lang.Math.abs(a.dot(b)),0,1)));
    }
    private static void elbow(Map<String,Pose> pose,String side,String where) {
        var q=pose.get(side+"_forearm").fsQuaternion();
        float bend=(float)java.lang.Math.IEEEremainder(java.lang.Math.toDegrees(2*java.lang.Math.atan2(q.x,q.w)),360);
        check(bend>=9.97f&&bend<=135.03f,"elbow keeps its anatomical 10..135 degree flexion "+where+" "+side+" bend="+bend);
        check(q.transform(new Vector3f(1,0,0)).x>.99999f,"elbow remains a pure local X hinge "+where+" "+side);
    }
    /** Counter-yaw preserves the sightline until the neck limit requires a bounded turn. */
    private static void head(Map<String,Pose> posed,Map<String,Matrix4f> before,Map<String,Matrix4f> world,Map<String,Matrix4f> previous,String where) {
        var oldHead=before.get("head").getUnnormalizedRotation(new Quaternionf()).normalize();
        var desired=world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize().invert().mul(oldHead).normalize();
        var desiredSight=desired.transform(new Vector3f(0,0,1));
        float desiredYaw=(float)java.lang.Math.atan2(desiredSight.x,desiredSight.z),limit=(float)java.lang.Math.toRadians(SpearConstraints.Limits.DEFAULT.neckYawDegrees());
        var local=posed.get("head").fsQuaternion().normalize();var sight=local.transform(new Vector3f(0,0,1));
        float localYaw=(float)java.lang.Math.atan2(sight.x,sight.z);
        check(java.lang.Math.abs(localYaw)<=limit+.0002f,"local neck yaw stays inside its configured limit "+where);
        check(java.lang.Math.abs(desiredSight.y-sight.y)<.0001f,"neck limit retains authored head pitch "+where);
        check(java.lang.Math.abs(desired.transform(new Vector3f(0,1,0)).y-local.transform(new Vector3f(0,1,0)).y)<.0001f,"neck limit retains authored head tilt "+where);
        if(previous==null) {
            var expected=new Quaternionf().rotationY(java.lang.Math.clamp(desiredYaw,-limit,limit)-desiredYaw).mul(desired);
            check(angle(expected,local)<.1f,"static neck uses the nearest legal yaw while retaining head tilt "+where);
            if(java.lang.Math.abs(desiredYaw)<limit-.0002f)check(angle(oldHead,world.get("head").getUnnormalizedRotation(new Quaternionf()).normalize())<.1f,"legal static counter-yaw retains authored head direction "+where);
        } else {
            var priorLocal=previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize().invert()
                    .mul(previous.get("head").getUnnormalizedRotation(new Quaternionf()).normalize());
            var priorSight=priorLocal.transform(new Vector3f(0,0,1));
            float priorYaw=(float)java.lang.Math.atan2(priorSight.x,priorSight.z),step=(float)java.lang.Math.toRadians(10);
            check(java.lang.Math.abs(java.lang.Math.toDegrees(localYaw-priorYaw))<=10.1,"sequential local neck yaw changes at most 10 degrees "+where);
            // Independent feasible interval: intersect anatomical stops with the current
            // frame's velocity bound, retaining the target's connected angular branch.
            double unwrapped=priorYaw+java.lang.Math.atan2(java.lang.Math.sin(desiredYaw-priorYaw),java.lang.Math.cos(desiredYaw-priorYaw));
            float low=java.lang.Math.max(-limit,priorYaw-step),high=java.lang.Math.min(limit,priorYaw+step);
            float nearest=(float)java.lang.Math.clamp(unwrapped,low,high);
            var expected=new Quaternionf().rotationY(nearest-desiredYaw).mul(desired);
            check(angle(expected,local)<.1f,"sequential neck approaches the nearest reachable yaw on its connected branch "+where);
            if(java.lang.Math.abs(unwrapped)<limit-.0002f&&java.lang.Math.abs(unwrapped-priorYaw)<step-.0002f)
                check(angle(oldHead,world.get("head").getUnnormalizedRotation(new Quaternionf()).normalize())<.1f,"reachable legal sequential yaw retains the authored sightline "+where);
        }
    }
    /** Independently sample the minimum legal chest yaw for the final actual pole ray. */
    private static float nearestFeasibleChestYaw(Map<String,Matrix4f> source,Vector3f shaft) {
        var up=source.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
        var forward=source.get("body").transformDirection(new Vector3f(0,0,1)).normalize();
        var separation=source.get("left_arm").getTranslation(new Vector3f()).sub(source.get("right_arm").getTranslation(new Vector3f()));
        float nearest=Float.POSITIVE_INFINITY;
        for(int tenth=-1800;tenth<=1800;tenth++) {
            float degrees=tenth/10f;var turn=new Quaternionf().rotationAxis((float)java.lang.Math.toRadians(degrees),up);
            if(turn.transform(new Vector3f(separation)).dot(shaft)>=53.99f&&turn.transform(new Vector3f(forward)).dot(shaft)>=-.02f)nearest=java.lang.Math.min(nearest,java.lang.Math.abs(degrees));
        }
        return nearest;
    }
    private static Map<String,Matrix4f> verify(ModelDocument model,Map<String,Pose> source,AttackAim.Point aim,String where) {
        return verify(model,source,aim,where,null,null,90);
    }
    private static Map<String,Matrix4f> verify(ModelDocument model,Map<String,Pose> source,AttackAim.Point aim,String where,Vector3f seed,Map<String,Matrix4f> previous,float wristLimit) {
        var before=model.worldMatrices(source);
        var chest=previous==null?null:previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
        var posed=previous==null?SpearAimPose.apply(model,source,aim,0,1.62f,seed)
                :SpearAimPose.apply(model,source,aim,0,1.62f,seed,chest,previous,SpearConstraints.Limits.DEFAULT);
        var world=model.worldMatrices(posed);
        Vector3f rear=palm(world,"right"),front=palm(world,"left"),shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
        var separation=new Vector3f(front).sub(rear);
        check(java.lang.Math.abs(separation.length()-58)<.02,"final palms retain exact 58cm separation "+where);
        check(new Vector3f(separation).cross(shaft).length()<.02 && separation.dot(shaft)>0,"front fist lies on the rendered pole "+where);
        double yaw=java.lang.Math.toRadians(aim.yaw()),pitch=java.lang.Math.toRadians(aim.pitch()),range=aim.distance()*100;
        var target=new Vector3f((float)(-java.lang.Math.sin(yaw)*java.lang.Math.cos(pitch)*range),(float)(162-java.lang.Math.sin(pitch)*range),(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch)*range));
        check(new Vector3f(target).sub(rear).normalize().dot(shaft)>.99999f,"rear grip targets the exact clicked ray with hand/eye parallax "+where);
        for(String side:List.of("right","left")) {
            var fore=world.get(side+"_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
            var fist=world.get(side+"_hand").transformDirection(new Vector3f(0,-1,0)).normalize();
            check(fore.dot(fist)>.99999f,"fist continues forearm without folded wrist "+where+" "+side);
            var localPole=world.get(side+"_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            check(java.lang.Math.abs(fist.dot(localPole))<.0001f,"pole crosses fist sideways rather than extending the fingers "+where+" "+side);
            check(localPole.dot(shaft)>.99999f,"both fists' transverse axes follow the actual rendered pole "+where+" "+side);
            var wrist=posed.get(side+"_hand").fsQuaternion();
            check(java.lang.Math.abs(wrist.x)<.0001f && java.lang.Math.abs(wrist.z)<.0001f,"wrist permits pure pronation only "+where+" "+side);
            float twist=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w));twist=(twist+540)%360-180;
            check(java.lang.Math.abs(twist)<=wristLimit+.01f,"fist stays inside its "+wristLimit+" degree pronation bound "+where+" "+side+" twist="+twist);
            elbow(posed,side,where);
            var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());var elbow=world.get(side+"_forearm").getTranslation(new Vector3f());var wristPoint=world.get(side+"_hand").getTranslation(new Vector3f());
            check(java.lang.Math.abs(shoulder.distance(elbow)-37.5)<.01 && java.lang.Math.abs(elbow.distance(wristPoint)-25)<.01,"arm links retain stock/slim physical lengths "+where+" "+side);
        }
        var oldUp=before.get("body").transformDirection(new Vector3f(0,1,0)).normalize();var newUp=world.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
        maximumChestUpError=java.lang.Math.max(maximumChestUpError,oldUp.distance(newUp));
        if(java.lang.Math.abs(aim.pitch())<=60)check(oldUp.distance(newUp)<.001f,"aim preserves authored chest lean/breathing despite necessary yaw "+where+" error="+oldUp.distance(newUp));
        else {
            float assistedTilt=(float)java.lang.Math.toDegrees(java.lang.Math.acos(java.lang.Math.clamp(oldUp.dot(newUp),-1,1)));
            check(assistedTilt<=18.1f,"severe aim uses at most 18 degrees of bounded torso assist "+where+" tilt="+assistedTilt);
        }
        check(before.get("body").getTranslation(new Vector3f()).distance(world.get("body").getTranslation(new Vector3f()))<.0001f,"aim preserves authored chest weight shift "+where);
        head(posed,before,world,previous,where);
        for(String part:List.of("right_leg","left_leg"))check(before.get(part).equals(world.get(part),.00001f),"aim preserves independent lower-body layer "+where+" "+part);
        return world;
    }
    public static void main(String[] arguments)throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        for(boolean slim:List.of(false,true))for(String clip:List.of("spear_idle","spear_thrust","spear_sweep")) {
            var duration=model.rig().clips().get(clip).duration();
            for(double time:new double[]{0,.24,.405,.47,.72,.999}) {
                var source=sample(model,clip,java.lang.Math.min(time,duration-.00001),slim);
                for(int yaw:new int[]{-55,-33,0,33,55})for(int pitch:new int[]{-40,-20,0,20,40})
                    verify(model,source,new AttackAim.Point(yaw,pitch,3),clip+" "+(slim?"slim":"stock")+" t="+time+" yaw="+yaw+" pitch="+pitch);
            }
            // A slow pointer sweep must not choose an opposite elbow branch in adjacent views.
            for(int pitch:new int[]{-40,0,40}) {
                var source=sample(model,clip,java.lang.Math.min(.405,duration-.00001),slim);Map<String,Matrix4f> previous=null;
                for(int yaw=-55;yaw<=55;yaw++) {
                    var world=verify(model,source,new AttackAim.Point(yaw,pitch,3),clip+" continuous yaw="+yaw+" pitch="+pitch,previous==null?null:palm(previous,"right"),previous,90);
                    if(previous!=null)for(String side:List.of("left","right"))for(String suffix:List.of("_arm","_forearm","_hand")) {
                        String bone=side+suffix;float difference=angle(previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize());
                        if(difference>=15)for(var diagnostic:List.of(previous,world)) {
                            var shaft=diagnostic.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                            var delta=palm(diagnostic,side).sub(diagnostic.get(side+"_arm").getTranslation(new Vector3f()));
                            System.out.println("aim diagnostic "+side+" a="+delta.dot(shaft)+" b="+new Vector3f(delta).sub(new Vector3f(shaft).mul(delta.dot(shaft))).length()+" body="+diagnostic.get("body").getUnnormalizedRotation(new Quaternionf())+" fore="+diagnostic.get(side+"_forearm").getUnnormalizedRotation(new Quaternionf())+" palm="+palm(diagnostic,side));
                        }
                        check(difference<15,"slow aim has no visible elbow/forearm branch flip "+clip+" "+bone+" yaw="+yaw+" pitch="+pitch+" angle="+difference);
                    }
                    previous=world;
                }
            }
        }
        behindTargetRecovery(model);
        severeAim(model);
        neckWrapRegression();
        capturedReaimRegression(model);
        returnToStance(model);
        System.out.println("SpearAimPoseTest passed: "+checks+" checks (horizontal grip, exact links, normal/slim, aim, leaning chest and continuous elbows); max body-up float error="+maximumChestUpError);
    }
    private static void severeAim(ModelDocument model) {
        for(boolean slim:List.of(false,true))for(String clip:List.of("spear_idle","spear_thrust","spear_sweep"))
            for(int yaw:new int[]{-150,0,150})for(int pitch:new int[]{-80,80})
                verify(model,sample(model,clip,.405,slim),new AttackAim.Point(yaw,pitch,60),"severe "+clip+" slim="+slim+" yaw="+yaw+" pitch="+pitch,null,null,150);
    }
    /** Exercise both atan2 wrap directions with a tilted head already at its legal stop. */
    private static void neckWrapRegression()throws ReflectiveOperationException {
        var method=SpearAimPose.class.getDeclaredMethod("head",Map.class,Quaternionf.class,double.class,float.class,Map.class,SpearConstraints.Limits.class);
        method.setAccessible(true);
        for(int sign:new int[]{-1,1}) {
            var initial=new Quaternionf().rotationY((float)java.lang.Math.toRadians(sign*85)).rotateX((float)java.lang.Math.toRadians(11)).rotateZ((float)java.lang.Math.toRadians(7));
            Map<String,Matrix4f> previous=Map.of("body",new Matrix4f(),"head",new Matrix4f().rotation(initial));
            float priorStop=sign*85;
            for(int yaw:new int[]{170,175,179,181,185,190,200,220,240,270,300,330,360}) {
                var authored=new Quaternionf().rotationY((float)java.lang.Math.toRadians(sign*yaw)).rotateX((float)java.lang.Math.toRadians(11)).rotateZ((float)java.lang.Math.toRadians(7));
                var pose=new HashMap<String,Pose>();pose.put("head",new Pose(org.fsvoxel.animation.FsvoxAnimation.fromQuaternion(authored),new V(0,0,0),new V(1,1,1)));
                method.invoke(null,pose,authored,0d,0f,previous,SpearConstraints.Limits.DEFAULT);
                var world=Map.of("body",new Matrix4f(),"head",new Matrix4f().rotation(pose.get("head").fsQuaternion()));
                head(pose,Map.of("body",new Matrix4f(),"head",new Matrix4f().rotation(authored)),world,previous,"neck wrap sign="+sign+" yaw="+yaw);
                if(yaw<=240) {
                    var sight=pose.get("head").fsQuaternion().transform(new Vector3f(0,0,1));
                    float actual=(float)java.lang.Math.toDegrees(java.lang.Math.atan2(sight.x,sight.z));
                    check(java.lang.Math.abs(actual-priorStop)<.03f,"head remains at the same stop across the 180-degree target wrap sign="+sign+" yaw="+yaw+" actual="+actual);
                }
                previous=world;
            }
            // An independent instantaneous clamp would select opposite stops here.
            var first=new Quaternionf().rotationY((float)java.lang.Math.toRadians(sign*179)).transform(new Vector3f(0,0,1));
            var second=new Quaternionf().rotationY((float)java.lang.Math.toRadians(sign*181)).transform(new Vector3f(0,0,1));
            double oldFirst=java.lang.Math.clamp(java.lang.Math.toDegrees(java.lang.Math.atan2(first.x,first.z)),-85,85);
            double oldSecond=java.lang.Math.clamp(java.lang.Math.toDegrees(java.lang.Math.atan2(second.x,second.z)),-85,85);
            check(java.lang.Math.abs(oldFirst-oldSecond)>169.9,"neck-wrap fixture distinguishes the previous 170-degree stop switch");
        }
    }
    /** Real 05 failure: a distant held sweep behind the actor must not switch chest yaw at recovery. */
    private static void behindTargetRecovery(ModelDocument model) {
        for(boolean slim:List.of(false,true))for(float bodyYaw:new float[]{50,-50}) {
            var aim=new AttackAim.Point(bodyYaw>0?144.765769:-144.765769,-8.434355,60.18877);
            Map<String,Matrix4f> previous=null;
            Vector3f previousSourceRear=null;
            Quaternionf previousSourceChest=null;
            double duration=model.rig().clips().get("spear_sweep").duration();
            for(int frame=0;frame<=duration*120;frame++) {
                var source=sample(model,"spear_sweep",frame/120.0,slim);
                var sourceWorld=model.worldMatrices(source);
                var sourceRear=palm(sourceWorld,"right");
                var sourceChest=sourceWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
                Vector3f rearSeed=previous==null?null:palm(previous,"right").add(new Vector3f(sourceRear).sub(previousSourceRear));
                Quaternionf chestSeed=previous==null?null:new Quaternionf(sourceChest).mul(new Quaternionf(previousSourceChest).invert()).mul(previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize());
                var posed=SpearAimPose.apply(model,source,aim,bodyYaw,1.62f,rearSeed,chestSeed,previous,SpearConstraints.Limits.DEFAULT);
                var world=model.worldMatrices(posed);
                var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                Vector3f rear=palm(world,"right"),front=palm(world,"left");
                check(java.lang.Math.abs(rear.distance(front)-58)<.02f,"rearward recovery retains 58cm two-hand pole frame="+frame);
                check(new Vector3f(front).sub(rear).cross(shaft).length()<.02f,"rearward recovery keeps front fist on official pole frame="+frame);
                for(String side:List.of("left","right")) {
                    var wrist=posed.get(side+"_hand").fsQuaternion();
                    float twist=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w));twist=(twist+540)%360-180;
                    check(java.lang.Math.abs(twist)<=90.01f,"rearward recovery retains natural wrist pronation frame="+frame+" "+side);
                    check(java.lang.Math.abs(wrist.x)<.0001f && java.lang.Math.abs(wrist.z)<.0001f,"rearward recovery has straight fist/forearm frame="+frame+" "+side);
                    elbow(posed,side,"rearward recovery frame="+frame);
                }
                head(posed,sourceWorld,world,previous,"rearward recovery frame="+frame);
                double yaw=java.lang.Math.toRadians(aim.yaw()-bodyYaw),pitch=java.lang.Math.toRadians(aim.pitch()),range=aim.distance()*100;
                var target=new Vector3f((float)(-java.lang.Math.sin(yaw)*java.lang.Math.cos(pitch)*range),(float)(162-java.lang.Math.sin(pitch)*range),(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch)*range));
                check(new Vector3f(target).sub(rear).normalize().dot(shaft)>.99999f,"rearward sweep retains the raw server aim throughout recovery frame="+frame);
                if(previous!=null) {
                    float bodyStep=angle(previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize());
                    check(bodyStep<10,"same rearward aim keeps a connected chest branch through recovery frame="+frame+" step="+bodyStep);
                    for(String side:List.of("left","right"))for(String suffix:List.of("_arm","_forearm","_hand")) {
                        String bone=side+suffix;float step=angle(previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize());
                        check(step<30,"rearward recovery has no visible arm/hand flip frame="+frame+" "+bone+" step="+step);
                    }
                }
                previous=world;previousSourceRear=new Vector3f(sourceRear);previousSourceChest=new Quaternionf(sourceChest);
            }
        }
    }

    /** Exact 07 native trace: slow READY return interrupted by a 113-degree new thrust. */
    private static void capturedReaimRegression(ModelDocument model) {
        float[] oldPeaks=replayCaptured07(model,true),newPeaks=replayCaptured07(model,false);
        for(int bone=0;bone<CAPTURED_BONES.length;bone++)
            check(newPeaks[bone]<45,"07 physical reaim keeps every world joint continuous across READY/WINDUP/ACTIVE/RECOVERY "
                    +CAPTURED_BONES[bone]+" peak="+newPeaks[bone]);
        System.out.println("07 trace world-joint peaks old="+java.util.Arrays.toString(oldPeaks)
                +" new="+java.util.Arrays.toString(newPeaks)+" bones="+java.util.Arrays.toString(CAPTURED_BONES));
    }
    private static float[] replayCaptured07(ModelDocument model,boolean legacySmoothstep) {
        // Preserve the actual source sockets, clocks and aim trace. Regenerate its initial
        // solved pose under the current limits rather than injecting an old illegal pose.
        Map<String,Matrix4f> previous=null;
        float[] peaks=new float[CAPTURED_BONES.length];
        double[] first=CAPTURED_TRACE[0];
        Vector3f previousSourceRear=new Vector3f((float)first[6],(float)first[7],(float)first[8]);
        Quaternionf previousSourceChest=new Quaternionf((float)first[9],(float)first[10],(float)first[11],(float)first[12]);
        double previousTick=first[1];
        var from=new AttackAim.Point(113.67654274655331,-6.623032213271799,48.229309072204465);
        var target=new AttackAim.Point(0,0,4.5);
        for(int frame=0;frame<CAPTURED_TRACE.length;frame++) {
            double[] row=CAPTURED_TRACE[frame];int phase=(int)row[2];
            // Captured actor uses the slim skin: final shoulders are +/-34.375cm.
            var source=new HashMap<>(sample(model,phase==0?"spear_idle":"spear_thrust",row[3],true));
            var sourceChest=new Quaternionf((float)row[9],(float)row[10],(float)row[11],(float)row[12]);
            var sourceBody=source.get("body");
            source.put("body",new Pose(org.fsvoxel.animation.FsvoxAnimation.fromQuaternion(sourceChest),
                    new V((float)row[17],(float)row[13],(float)row[18]),sourceBody.scale()));
            // These are the observed post-retarget local sockets; replay must not
            // substitute a sampled author's shoulders for the captured native skin.
            for(String side:List.of("right","left")) {
                var arm=source.get(side+"_arm");
                source.put(side+"_arm",new Pose(arm.rotation(),new V(side.equals("right")?-34.375f:34.375f,75,0),arm.scale()));
            }
            var sourceRear=new Vector3f((float)row[6],(float)row[7],(float)row[8]);
            var rearSeed=previous==null?null:PoseLayers.gripSeed(sourceRear,previousSourceRear,palm(previous,"right"));
            var chestSeed=previous==null?null:PoseLayers.chestSeed(sourceChest,previousSourceChest,
                    previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),
                    (row[1]-previousTick)/20,phase==0||phase==3?90:0);
            var aim=new AttackAim.Point(row[14],row[15],row[16]);
            if(phase==1) {
                float weight=legacySmoothstep?PoseLayers.transitionWeight(row[4],0,row[5])
                        :PoseLayers.windupAimWeight(row[4],0,row[5]);
                aim=PoseLayers.blendAim(from,target,weight);
            }
            var before=model.worldMatrices(source);
            var posed=SpearAimPose.apply(model,source,aim,50,1.62f,rearSeed,chestSeed,previous,SpearConstraints.Limits.DEFAULT);
            var world=model.worldMatrices(posed);
            var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            Vector3f rear=palm(world,"right"),front=palm(world,"left");
            String label="07 "+(legacySmoothstep?"old":"new")+" native frame="+(int)row[0];
            check(java.lang.Math.abs(rear.distance(front)-58)<.02f,"captured handoff has exact 58cm "+label);
            check(new Vector3f(front).sub(rear).cross(shaft).length()<.02f,"captured handoff has both grips on shaft "+label);
            double yaw=java.lang.Math.toRadians(aim.yaw()-50),pitch=java.lang.Math.toRadians(aim.pitch()),range=aim.distance()*100;
            var clicked=new Vector3f((float)(-java.lang.Math.sin(yaw)*java.lang.Math.cos(pitch)*range),
                    (float)(162-java.lang.Math.sin(pitch)*range),(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch)*range));
            check(new Vector3f(clicked).sub(rear).normalize().dot(shaft)>.99999f,"captured handoff hits the precise target "+label);
            if(phase==2)check(java.lang.Math.abs(aim.yaw()-row[14])<.00001 && java.lang.Math.abs(aim.pitch()-row[15])<.00001,
                    "first and subsequent ACTIVE frames use the exact server aim "+label);
            for(String side:List.of("left","right")) {
                var wrist=posed.get(side+"_hand").fsQuaternion();
                float twist=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w));twist=(twist+540)%360-180;
                check(java.lang.Math.abs(twist)<=90.01f,"captured handoff pronation stays anatomical "+label+" "+side);
                check(java.lang.Math.abs(wrist.x)<.0001f && java.lang.Math.abs(wrist.z)<.0001f,
                        "captured handoff retains straight fists "+label+" "+side);
                elbow(posed,side,label);
            }
            head(posed,before,world,previous,label);
            check(before.get("body").transformDirection(new Vector3f(0,1,0)).normalize()
                    .distance(world.get("body").transformDirection(new Vector3f(0,1,0)).normalize())<.001f,
                    "captured handoff retains authored leaning/breathing "+label);
            check(before.get("body").getTranslation(new Vector3f()).distance(world.get("body").getTranslation(new Vector3f()))<.0001f,
                    "captured handoff retains authored torso displacement "+label);
            if(previous!=null)for(int bone=0;bone<CAPTURED_BONES.length;bone++) {
                String name=CAPTURED_BONES[bone];
                float step=angle(previous.get(name).getUnnormalizedRotation(new Quaternionf()).normalize(),
                        world.get(name).getUnnormalizedRotation(new Quaternionf()).normalize());
                peaks[bone]=java.lang.Math.max(peaks[bone],step);
                if(!legacySmoothstep)check(step<(name.equals("body")?10:45),
                        "captured handoff has no joint jump at either phase boundary "+label+" "+name+" step="+step);
            }
            previous=world;previousSourceRear=sourceRear;previousSourceChest=sourceChest;previousTick=row[1];
        }
        return peaks;
    }
    private static final String[] CAPTURED_BONES={"body","right_arm","right_forearm","left_hand","left_arm","left_forearm","right_hand"};
    // Original RenderFrameEvent.Post matrices, order adjusted below to keep left-hand failure readable.
    private static final float[][] CAPTURED_INITIAL={
        {-0.26172018f,0.0008380249f,0.9651434f,0f,-0.0015288737f,0.999998f,-0.001282877f,0f,-0.96514255f,-0.0018113372f,-0.2617184f,0f,0f,75.17593f,0f,1f},
        {-0.91515225f,0.3821925f,0.12816107f,0f,0.34805274f,0.90955496f,-0.227088f,0f,-0.20336087f,-0.16321331f,-0.96540445f,0f,8.881966f,150.14697f,-33.273018f,1f},
        {-0.91515225f,0.3821925f,0.12816107f,0f,-0.31402537f,-0.4765749f,-0.821136f,0f,-0.25275367f,-0.7917103f,0.55615675f,0f,-4.1700125f,116.038666f,-24.757217f,1f},
        {-0.14814466f,-0.988157f,-0.03998314f,0f,0.2784548f,-0.08047134f,0.95707196f,0f,-0.9489551f,0.13065168f,0.2870786f,0f,-47.65579f,138.00647f,23.53555f,1f},
        {0.3363012f,-0.9252309f,-0.17563914f,0f,0.8422165f,0.37893066f,-0.38351357f,0f,0.4213937f,-0.018950086f,0.90667963f,0f,-9.111298f,150.20459f,33.08059f,1f},
        {0.3363012f,-0.9252309f,-0.17563914f,0f,0.27845484f,-0.080471344f,0.957072f,0f,-0.8996467f,-0.37077206f,0.23057246f,0f,-40.694416f,135.99469f,47.46235f,1f},
        {-0.029531717f,0.86937124f,-0.49327618f,0f,-0.31402537f,-0.4765749f,-0.821136f,0f,-0.9489551f,0.1306517f,0.28707874f,0f,3.6806216f,127.95304f,-4.228817f,1f}
    };
    // frame, sampledTick, phase(READY/W/A/R), clipSeconds, bridgeElapsed, bridgeTicks,
    // sourceRear xyz, sourceChest xyzw, rootY, rendered aim yaw/pitch/distance, rootX/Z.
    // Provenance: verification/weapons-r2/seventh-windup-handoff-review/render-capture.json, F31..85.
    private static final double[][] CAPTURED_TRACE={
        {31,8692.259993076324,0,4.066000041365624,4.42000013589859,18.126407833340977,-2.8571546,123.18088,16.149406,-0.0006074844,-0.043619256,0.0007916891,0.9990478,75.17593,123.14053375980723,-7.174424046911952,51.869935168635365,0,0},
        {32,8692.579993069172,0,4.082000041007996,4.740000128746033,18.126407833340977,-2.884686,123.22475,16.125898,-0.00075401523,-0.043619186,0.0009826513,0.99904746,75.21836,120.24544763941753,-7.005750297937313,50.75624792331308,0,0},
        {33,8692.919993042946,0,4.099000039696693,5.080000102519989,18.126407833340977,-2.9139276,123.27096,16.101692,-0.00090918475,-0.04361937,0.0011848724,0.9990471,75.2633,117.0281503032694,-6.818303852240151,49.518611853545565,0,0},
        {34,8693.259993076324,0,4.1160000413656235,5.42000013589859,18.126407833340977,-2.9427347,123.31713,16.077364,-0.0010637074,-0.043619335,0.0013862501,0.99904674,75.308044,113.67654274655331,-6.623032213271799,48.229309072204465,0,0},
        {35,8693.69999307394,1,0,0,3.036515069937993,-2.9427347,123.31713,16.077364,-0.0010637075,-0.043619335,0.00138625,0.99904674,75.308044,113.67654274655331,-6.623032213271799,48.229309072204465,0,0},
        {36,8694.039993047714,1,0.020399998426437378,0.3399999737739563,3.036515069937993,-2.94842,123.296196,16.079094,-0.0010313148,-0.043800484,0.0013183805,0.99903893,75.281166,109.72008574792926,-6.392520785679087,46.707331655021235,-0.005509936,-0.0046233837},
        {37,8694.359993040562,1,0.039599997997283934,0.6599999666213989,3.036515069937993,-3.119352,123.27397,15.999506,-0.0013254097,-0.045580015,0.0014577144,0.99895877,75.19472,99.89986609678317,-5.820374329431261,42.92967084737918,-0.059432834,-0.049870055},
        {38,8694.699993014336,1,0.059999996423721315,0.9999999403953552,3.036515069937993,-3.7555156,123.312454,15.677482,-0.0026645071,-0.05151646,0.0024374872,0.99866563,75.01246,84.81056310920701,-4.941240100434262,37.12508902174247,-0.23835559,-0.20000404},
        {39,8695.039993047714,1,0.08549999892711639,1.3399999737739563,3.036515069937993,-5.1819763,123.49232,14.926093,-0.0058347406,-0.06440708,0.005080981,0.99789375,74.700516,66.80228810243648,-3.8920404802345647,30.1976314777172,-0.6232771,-0.52299154},
        {40,8695.37999305129,1,0.11099999919533729,1.679999977350235,3.036515069937993,-7.0432262,123.7801,13.900593,-0.010066112,-0.081179954,0.008948167,0.9966085,74.324394,47.79002100089383,-2.784346188883833,22.8839563415874,-1.1158575,-0.9363154},
        {41,8695.719993054867,1,0.1364999994635582,2.0199999809265137,3.036515069937993,-8.536171,124.05799,13.019848,-0.013641634,-0.094676025,0.0125171775,0.995336,74.0279,29.688729871613873,-1.7297272555976377,15.920717178314739,-1.4976,-1.2566353},
        {42,8696.05999302864,1,0.16199999749660493,2.35999995470047,3.036515069937993,-9.930964,124.28712,12.025126,-0.016439864,-0.1037934,0.015506856,0.9943422,73.81826,14.413392945106125,-0.8397543017367735,10.04457146256577,-1.7293724,-1.4511155},
        {43,8696.399993032217,1,0.18749999776482582,2.6999999582767487,3.036515069937993,-12.380745,124.53264,10.059466,-0.019564802,-0.11316865,0.018945819,0.9932025,73.59164,3.87897151275547,-0.22599696175332884,5.992170152832216,-1.9488999,-1.6353208},
        {44,8696.739993035793,1,0.21299999803304673,3.0399999618530273,3.036515069937993,-14.94475,124.78662,7.932621,-0.022508254,-0.12170005,0.022390211,0.99205905,73.36317,0,0,4.5,-2.134485,-1.7910453},
        {45,8697.079993009567,1,0.23849999606609346,3.3799999356269836,3.036515069937993,-17.18703,125.01799,6.052717,-0.024741394,-0.1279727,0.025242085,0.9911477,73.15274,0,0,4.5,-2.2569592,-1.8938134},
        {46,8697.419993013144,1,0.26399999633431437,3.7199999392032623,3.036515069937993,-18.781784,125.19338,4.720747,-0.025757642,-0.13000457,0.026952239,0.9908123,72.99167,0,0,4.5,-2.2779012,-1.9113855},
        {47,8697.739992976189,1,0.28799999356269834,4.039999902248383,3.036515069937993,-18.018448,125.158356,5.325778,-0.022354068,-0.11580826,0.024380881,0.99272066,73.00993,0,0,4.5,-1.8905602,-1.586368},
        {48,8698.079993009567,2,0.3128249968215823,4.379999935626984,3.036515069937993,-13.050197,125.18497,8.999195,-0.01259657,-0.09159484,0.012688176,0.99563587,72.89547,0,0,4.5,-1.0268395,-0.8616205},
        {49,8698.419993013144,2,0.3357749970629811,4.719999939203262,3.036515069937993,-6.3549433,125.298004,13.886297,0.00009710989,-0.06656372,-0.0022853077,0.9977796,72.67028,0,0,4.5,-0.04401769,-0.03693522},
        {50,8698.739992976189,2,0.3573749945685267,5.039999902248383,3.036515069937993,0.20745158,125.44278,18.660255,0.014032168,-0.041934486,-0.01698697,0.9988774,72.43378,0,0,4.5,0.9451662,0.79308844},
        {51,8699.079993009567,2,0.3803249968215823,5.379999935626984,3.036515069937993,5.7162123,127.00775,22.172785,0.02955215,-0.016408358,-0.03156654,0.9989299,72.19597,0,0,4.5,1.9983948,1.6768521},
        {52,8699.419993013144,2,0.4032749970629811,5.719999939203262,3.036515069937993,9.122319,131.17902,23.665464,0.044282217,0.0066376324,-0.043428466,0.9980526,72.01434,0,0,4.5,2.9966848,2.5145166},
        {53,8699.739992976189,2,0.42487499456852673,6.039999902248383,3.036515069937993,11.022807,131.63889,24.776365,0.056451753,0.025612855,-0.051254407,0.99675983,71.924355,0,0,4.5,3.922875,3.2916822},
        {54,8700.079993009567,2,0.4478249968215823,6.379999935626984,3.036515069937993,12.561972,132.49384,25.822636,0.06689955,0.04216802,-0.056547713,0.99526316,71.91527,0,0,4.5,4.844671,4.0651608},
        {55,8700.419993013144,2,0.4707749970629811,6.719999939203262,3.036515069937993,13.357702,133.15115,26.350811,0.07203693,0.05058927,-0.059094515,0.9943638,72.00948,0,0,4.5,5.352646,4.491402},
        {56,8700.739992976189,2,0.49237499456852674,7.039999902248383,3.036515069937993,13.55085,133.39511,26.384256,0.07222294,0.051425207,-0.060019698,0.99425197,72.18253,0,0,4.5,5.4352717,4.5607333},
        {57,8701.079993009567,2,0.5153249968215823,7.379999935626984,3.036515069937993,13.448543,133.49461,26.187473,0.06977274,0.048409045,-0.06007339,0.9945751,72.422874,0,0,4.5,5.327687,4.470459},
        {58,8701.419993013144,2,0.5382749970629811,7.719999939203262,3.036515069937993,13.051905,133.52725,25.803616,0.06543568,0.042752203,-0.059035815,0.99519104,72.6837,0,0,4.5,5.0652113,4.2502165},
        {59,8701.739992976189,2,0.5598749945685266,8.039999902248383,3.036515069937993,12.488284,133.61919,25.359444,0.06078892,0.03687667,-0.057177857,0.9958291,72.907486,0,0,4.5,4.751103,3.9866476},
        {60,8702.079993009567,3,0.581347219410042,8.379999935626984,3.036515069937993,11.76531,133.85397,24.86601,0.056094274,0.031383518,-0.054496378,0.9964431,73.090065,0,0,4.5,4.403619,3.695074},
        {61,8702.419993013144,3,0.6016527751791808,8.719999939203262,3.036515069937993,10.861442,134.20808,24.298525,0.051000994,0.025622156,-0.051033147,0.9970647,73.24772,0,0,4.5,3.9974458,3.3542547},
        {62,8702.739992976189,3,0.6177777777777778,9.039999902248383,3.036515069937993,10.028417,134.55096,23.796707,0.046585694,0.020613732,-0.047804378,0.9975569,73.36884,0,0,4.5,3.6320941,3.0476882},
        {63,8703.079993009567,3,0.6340222238169776,9.379999935626984,3.036515069937993,9.123677,134.93437,23.268696,0.041998472,0.015359639,-0.04426454,0.9980185,73.48631,0,0,4.5,3.2433481,2.7214916},
        {64,8703.419993013144,3,0.6502666684322886,9.719999939203262,3.036515069937993,8.192977,135.33463,22.739986,0.037447706,0.010072767,-0.04058251,0.9984234,73.598434,0,0,4.5,2.8508809,2.3921726},
        {65,8703.75999301672,3,0.6665111130475998,10.059999942779541,3.036515069937993,7.3078003,135.5348,22.19535,0.033103757,0.0049314206,-0.036911506,0.99875796,73.70437,0,0,4.5,2.4719076,2.0741763},
        {66,8704.099992990494,3,0.6827555562390222,10.399999916553497,3.036515069937993,6.846607,133.76274,21.258762,0.029133266,0.00012326229,-0.033416457,0.9990168,73.80326,0,0,4.5,2.1241937,1.7824098},
        {67,8704.419992983341,3,0.6980444447861778,10.71999990940094,3.036515069937993,6.5644164,132.16228,20.370493,0.025886068,-0.0039231777,-0.030446354,0.9991935,73.889084,0,0,4.5,1.8420172,1.5456356},
        {68,8704.759992957115,3,0.7142888879776,11.059999883174896,3.036515069937993,6.1291747,130.73181,19.7163,0.023063423,-0.0075732474,-0.027767735,0.9993196,73.972275,0,0,4.5,1.6032795,1.3453109},
        {69,8705.099992990494,3,0.7295777793725331,11.399999916553497,3.036515069937993,5.0384927,130.0243,19.827494,0.020936446,-0.010465326,-0.025657043,0.99939686,74.04514,0,0,4.5,1.4325621,1.2020621},
        {70,8705.43999299407,3,0.7458222239878443,11.739999920129776,3.036515069937993,3.8158882,129.49707,20.033464,0.018825771,-0.013433785,-0.023473296,0.99945694,74.12307,0,0,4.5,1.2688853,1.064721},
        {71,8705.779992997646,3,0.7620666686031553,12.079999923706055,3.036515069937993,2.7466588,128.79984,20.095343,0.016787238,-0.016381236,-0.02128016,0.9994985,74.202286,0,0,4.5,1.1150525,0.9356399},
        {72,8706.11999297142,3,0.7783111117945777,12.419999897480011,3.036515069937993,1.9757205,127.47563,19.841616,0.014825831,-0.019290265,-0.019092746,0.9995217,74.28204,0,0,4.5,0.97077817,0.8145794},
        {73,8706.439992964268,3,0.7936000003417333,12.739999890327454,3.036515069937993,1.3745046,126.15499,19.482838,0.013058939,-0.021971788,-0.017056905,0.9995278,74.35685,0,0,4.5,0.843894,0.708111},
        {74,8706.779992938042,3,0.8098444435331557,13.07999986410141,3.036515069937993,0.7771816,124.926155,19.101822,0.0112717105,-0.02474135,-0.014935629,0.99951875,74.4353,0,0,4.5,0.7184159,0.60282236},
        {75,8707.11999297142,3,0.8260888895723555,13.419999897480011,3.036515069937993,0.22424686,123.8483,18.723783,0.00958622,-0.027407318,-0.012876826,0.99949545,74.5119,0,0,4.5,0.6027432,0.5057615},
        {76,8707.439992964268,3,0.8413777781195111,13.739999890327454,3.036515069937993,-0.24834895,123.35666,18.36424,0.008100193,-0.029800687,-0.0110140685,0.99946237,74.58155,0,0,4.5,0.50291944,0.42199942},
        {77,8707.799992978573,3,0.8585777788029776,14.099999904632568,3.036515069937993,-0.7245964,123.28615,17.970932,0.0065526622,-0.032336235,-0.009025814,0.9994148,74.65624,0,0,4.5,0.40116516,0.33661747},
        {78,8708.139992952347,3,0.8748222219944,14.439999878406525,3.036515069937993,-1.1349517,123.23113,17.628147,0.005220255,-0.034556538,-0.0072733867,0.99936265,74.72236,0,0,4.5,0.3154224,0.26467076},
        {79,8708.479992955923,3,0.8910666666097111,14.779999881982803,3.036515069937993,-1.5043347,123.18037,17.31651,0.0040211724,-0.036584165,-0.0056630503,0.99930644,74.78335,0,0,4.5,0.23982267,0.20123507},
        {80,8708.8199929595,3,0.9073111112250223,15.119999885559082,3.036515069937993,-1.830231,123.134476,17.038715,0.0029632405,-0.03839737,-0.004214912,0.9992493,74.838394,0,0,4.5,0.17445134,0.14638203},
        {81,8709.139992952347,3,0.9225999997721778,15.439999878406525,3.036515069937993,-2.0953946,123.09637,16.81091,0.002102384,-0.03989073,-0.0030166642,0.9991973,74.884094,0,0,4.5,0.1222668,0.102594},
        {82,8709.499992966652,3,0.9398000004556444,15.79999989271164,3.036515069937993,-2.3415809,123.06054,16.597597,0.0013022127,-0.041294787,-0.0018851049,0.9991444,74.9274,0,0,4.5,0.074717194,0.06269516},
        {83,8709.83999300003,3,0.9560444464948442,16.13999992609024,3.036515069937993,-2.5221977,123.03373,16.439869,0.00071528903,-0.042335052,-0.0010433126,0.99910265,74.95973,0,0,4.5,0.040524002,0.03400367},
        {84,8710.17999303341,3,0.972288892534044,16.47999995946884,3.036515069937993,-2.64957,123.014175,16.32795,0.00030182675,-0.043074753,-0.00044317334,0.9990717,74.98286,0,0,4.5,0.016889596,0.014172051},
        {85,8710.499993026257,3,0.9875777810811996,16.799999952316284,3.036515069937993,-2.7190185,123.003426,16.266716,0.00007638323,-0.04348114,-0.0001128071,0.99905425,74.99563,0,0,4.5,0.004222803,0.0035433518}
    };


    /** Rearward camera targets must release the old chest branch without teleporting either fist. */
    private static void returnToStance(ModelDocument model) {
        for(boolean slim:List.of(false,true))for(boolean cameraFixture:List.of(false,true))
                for(double returnRate:new double[]{90,240})for(double aimRate:new double[]{240,720}) {
            float bodyYaw=50;
            var from=new AttackAim.Point(144.765769,-8.434355,60.18877);
            var target=new AttackAim.Point(cameraFixture?0:50,0,3);
            double duration=PoseLayers.aimTransitionTicks(from,target,
                    java.lang.Math.max(.12,model.rig().clips().get("spear_idle").transition())*20,aimRate);
            String label="return stock="+!slim+" camera="+cameraFixture+" chestRate="+returnRate+" aimRate="+aimRate;
            Map<String,Matrix4f> previous=null;
            Vector3f previousSourceRear=null;
            Quaternionf previousSourceChest=null;
            for(int frame=0;frame<=8*120;frame++) {
                var source=sample(model,"spear_idle",frame/120.0,slim);
                // Actual 06 capture has model-space source chest yaw -5: authored -55 + view 50.
                if(cameraFixture)source=PoseLayers.aim(model.rig(),source,50,1);
                var sourceWorld=model.worldMatrices(source);
                var sourceRear=palm(sourceWorld,"right");
                var sourceChest=sourceWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
                Vector3f rearSeed=previous==null?null:PoseLayers.gripSeed(sourceRear,previousSourceRear,palm(previous,"right"));
                Quaternionf chestSeed=previous==null
                        ?new Quaternionf(sourceChest).rotateY((float)java.lang.Math.toRadians(cameraFixture?-136.5:0))
                        :PoseLayers.chestSeed(sourceChest,previousSourceChest,
                                previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),1/120.0,returnRate);
                // Match the runtime READY bridge; an instantaneous 145-degree target turn is not a rendered input.
                float weight=PoseLayers.transitionWeight(frame/6.0,2*20,duration);
                var aim=PoseLayers.blendAim(from,target,weight);
                var posed=SpearAimPose.apply(model,source,aim,bodyYaw,1.62f,rearSeed,chestSeed,previous,SpearConstraints.Limits.DEFAULT);
                var world=model.worldMatrices(posed);
                var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                Vector3f rear=palm(world,"right"),front=palm(world,"left");
                check(java.lang.Math.abs(rear.distance(front)-58)<.02f,"aim return retains 58cm "+label+" frame="+frame);
                check(new Vector3f(front).sub(rear).cross(shaft).length()<.02f,"aim return retains both transverse grips "+label+" frame="+frame);
                for(String side:List.of("left","right")) {
                    var wrist=posed.get(side+"_hand").fsQuaternion();
                    float twist=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w));twist=(twist+540)%360-180;
                    check(java.lang.Math.abs(twist)<=90.01f,"aim return has natural pronation "+label+" frame="+frame+" "+side);
                    check(java.lang.Math.abs(wrist.x)<.0001f && java.lang.Math.abs(wrist.z)<.0001f,"aim return keeps the fist straight "+label+" frame="+frame+" "+side);
                    elbow(posed,side,"aim return "+label+" frame="+frame);
                }
                head(posed,sourceWorld,world,previous,"aim return "+label+" frame="+frame);
                double yaw=java.lang.Math.toRadians(aim.yaw()-bodyYaw),pitch=java.lang.Math.toRadians(aim.pitch()),range=aim.distance()*100;
                var clicked=new Vector3f((float)(-java.lang.Math.sin(yaw)*java.lang.Math.cos(pitch)*range),
                        (float)(162-java.lang.Math.sin(pitch)*range),(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch)*range));
                check(new Vector3f(clicked).sub(rear).normalize().dot(shaft)>.99999f,"aim return stays accurate "+label+" frame="+frame);
                var sourceUp=sourceWorld.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
                check(sourceUp.distance(world.get("body").transformDirection(new Vector3f(0,1,0)).normalize())<.001f,
                        "aim return retains breathing and lean "+label+" frame="+frame);
                if(previous!=null) {
                    float step=angle(previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),
                            world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize());
                    check(step<10,"aim return never changes chest branch "+label+" frame="+frame+" step="+step);
                    for(String side:List.of("left","right"))for(String suffix:List.of("_arm","_forearm","_hand")) {
                        String bone=side+suffix;step=angle(previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),
                                world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize());
                        check(step<30,"aim return has no visible arm jump "+label+" frame="+frame+" "+bone+" step="+step);
                    }
                }
                if(frame==8*120) {
                    var actualChest=world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
                    if(angle(sourceChest,actualChest)>=.5f)System.out.println("return stance diagnostic "+label+" authoredChestError="+angle(sourceChest,actualChest)+" facing="+world.get("body").transformDirection(new Vector3f(0,0,1)).normalize().dot(shaft));
                    float nearest=nearestFeasibleChestYaw(sourceWorld,shaft);
                    check(Float.isFinite(nearest),"front target has a legal forward-facing side-on stance "+label);
                    check(angle(sourceChest,actualChest)<nearest+.5f,"front target returns to the nearest feasible authored stance "+label+" actual="+angle(sourceChest,actualChest)+" minimum="+nearest);
                    float shoulderAlong=world.get("left_arm").getTranslation(new Vector3f()).sub(world.get("right_arm").getTranslation(new Vector3f())).dot(shaft);
                    check(shoulderAlong>=53.98f,"final side-on stance retains transverse-grip shoulder reach "+label+" projection="+shoulderAlong);
                    float facing=world.get("body").transformDirection(new Vector3f(0,0,1)).normalize().dot(shaft);
                    check(facing>.35f && facing<.85f,"front target keeps a readable side-on chest rather than facing away "+label+" dot="+facing);
                }
                previous=world;previousSourceRear=sourceRear;previousSourceChest=sourceChest;
            }
        }
    }
}

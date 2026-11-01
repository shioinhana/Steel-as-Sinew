package org.fsrpg.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Regression for start/stop pivot switching and persistent, bounded gait response. */
public final class RapierFlowMotionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        var neutral=new PresentationMotion.Balance(0,0,0);
        var feet=new SpearTurn.Feet(-.57f,-.57f,-.57f,.4f,0,0,1);
        for(float yaw:new float[]{-.75f,-.3f,.3f,.75f}){
            feet=new SpearTurn.Feet(yaw,yaw,yaw,.4f,0,0,1);
            var base=Locomotion.supported(new Matrix4f().translation(-12.5f,75,16),new Matrix4f().translation(12.5f,75,-8),
                    1,0,0,0,0,1,.55f,48,neutral);
            Locomotion.LowerBody previous=null;
            for(int n=0;n<=1000;n++){
                float moving=n/1000f;var lower=Locomotion.turn(base,feet,moving);
                if(previous!=null){
                    check(lower.right().sole().distance(previous.right().sole())<.02,"Starting movement must not switch sole pivots abruptly");
                    check(lower.left().sole().distance(previous.left().sole())<.02,"Stopping movement must recover the planted stance continuously");
                }
                var r=lower.right().pose();var l=lower.left().pose();
                check(Math.abs(new Vector3f(r.x(),r.y(),r.z()).distance(new Vector3f(l.x(),l.y(),l.z()))-3.8)<.0001,"Both hips remain attached through the pivot blend");
                check(lower.right().sole().y<=24&&lower.left().sole().y<=24,"No sole crosses below the floor");
                previous=lower;
            }
        }
        var turn=new SpearTurn.State();turn.advance(0,0,0,0);
        SpearTurn.Feet before=null;
        for(int i=1;i<=12;i++)before=turn.advance(i/3.,.5f,0,0);
        var after=turn.advance(4+1./60,.5f,0,.0011f);
        check(Math.abs(after.rightYaw()-before.rightYaw())<.003,"Tiny movement preserves the turning foot yaw");
        check(Math.abs(after.rightLift()-before.rightLift())<.003,"Tiny movement preserves the turning foot lift");
        check(after.equals(turn.advance(4+1./60,.5f,0,1)),"Skin and armor share the same sampled turn");
        var pulse=new SpearTurn.State();pulse.advance(0,0,0,0);pulse.advance(1,.4f,0,0);
        var lifted=pulse.advance(4,.4f,0,0);var starting=pulse.advance(4.1,.4f,0,.002f);
        var stopping=pulse.advance(4.2,.4f,0,0);
        check(lifted.rightLift()>.8f,"The pulse fixture starts halfway through a turning step");
        check(Math.abs(starting.rightLift()-stopping.rightLift())<.03,"A tiny move-stop pulse keeps the same footstep instead of dropping the foot");
        for(int step=0;step<=40;step++)for(int n=0;n<48;n++){
            float moving=step/40f;
            var base=Locomotion.supported(null,null,1-moving,n*.2f,.16f,moving,0,1,.55f,48,neutral);
            var merged=Locomotion.turn(base,new SpearTurn.Feet(.4f,.4f,.2f,.8f,0,0,1),moving);
            check(merged.right().support()==1||merged.left().support()==1,"Turn-to-travel merge always has a real supporting foot");
            check(Math.abs(Math.max(merged.right().sole().y,merged.left().sole().y)-24)<.0001,"Support means actual floor contact, not a blend weight");
        }
        for(float phase=0;phase<20;phase+=.1f){
            var idle=RapierMotion.gait(phase,0,.2f,1);var frozen=RapierMotion.gait(phase,1,0,1);
            check(Math.hypot(idle.lateral(),idle.forward())==0,"Idle has no gait sway");
            check(Math.hypot(frozen.lateral(),frozen.forward())==0,"Frozen travel cannot keep rocking");
            var gait=RapierMotion.gait(phase,1,.2f,1);
            check(Math.abs(gait.lateral())<=.65&&Math.abs(gait.forward())<=.181,"Gait stays subtle");
            var combined=RapierMotion.combine(1,1,4,1,RapierMotion.add(gait,new RapierMotion.Lean(3,-3)));
            check(Math.hypot(combined.lateral(),combined.forward())<=5.00001,"Movement, gait and strike share one lean limit");
        }
        var state=new RapierMotion.State();RapierMotion.Lean last=new RapierMotion.Lean(0,0);
        for(int n=0;n<=240;n++){
            double tick=n/12.;float weight=n<90?1:n<150?.45f:0;
            var sample=state.advance(tick,RapierMotion.add(new RapierMotion.Lean(3*weight,0),RapierMotion.gait(n*.08f,weight,.2f,weight)),1.4);
            check(Math.abs(sample.lateral()-last.lateral())<.25,"Attack/stop changes ease through the inherited waist state");
            check(state.advance(tick,new RapierMotion.Lean(-5,5),1.4).equals(sample),"One timestamp cannot integrate twice");last=sample;
        }
        check(Math.abs(last.lateral())<.02,"Stopped stance settles without residual sway");
        viewBranches();
        branchJointContinuity();
        System.out.println("RapierFlowMotionTest passed: "+checks+" checks");
    }
    private static void branchJointContinuity()throws Exception{
        var model=org.fsvoxel.render.ModelLoader.load(java.nio.file.Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/legacy_rapier.fsvoxel"));
        var targets=model.rig().clips().values().stream().filter(c->java.util.Set.of("charged-thrust","riposte","reprise").contains(RapierMotion.phrase(c))).toList();
        check(!targets.isEmpty(),"The branch regression uses the shipped conditional clips");
        for(int cut=1;cut<=5;cut++)for(var target:targets){
            int windup=7,active=2,recovery=4;
            if(RapierMotion.phrase(target).equals("riposte")){windup=3;recovery=8;}
            if(RapierMotion.phrase(target).equals("reprise")){windup=5;active=3;recovery=10;}
            double duration=Math.min(1.25,PoseLayers.entryWindupTicks(PoseLayers.transitionTicks(target,.15,windup,true),1,windup));
            var source=model.rig().clips().get("legacy_rapier_attack"+cut+"_upper");
            var outgoing=LegacyPlayback.sample(model,source,source.duration()*.86);
            var previous=outgoing;
            // Reproduce a branch received one tick into WINDUP, at native 40 Hz.
            for(int frame=0;frame<=4;frame++){
                double tick=frame*.5;
                var authored=LegacyPlayback.sample(model,target,RapierMotion.clipTime(target,1+tick,windup,active,recovery));
                var pose=RapierMotion.bridge(outgoing,authored,PoseLayers.transitionWeight(tick,0,duration));
                if(frame==0)check(pose.equals(outgoing),"New TP binding presents the real outgoing pose before any correction");
                if(tick>=duration)check(pose.equals(authored),"Branch settles before contact on the unchanged target clock");
                var world=model.worldMatrices(pose);
                for(String side:java.util.List.of("right","left")){
                    var joint=pose.get(side+"_hand").fsQuaternion().normalize();
                    var old=previous.get(side+"_hand").fsQuaternion().normalize();
                    double step=Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(joint.dot(old)),0,1)));
                    check(step<=35.001,"Branch wrist cannot jump or rebound over 35 degrees per native frame: cut="+cut+" target="+target.id()+" step="+step);
                    var hand=world.get(side+"_hand").transformDirection(new Vector3f(0,-1,0)).normalize();
                    var forearm=world.get(side+"_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
                    check(hand.dot(forearm)>=Math.cos(Math.toRadians(34.05)),"Intermediate branch wrist stays inside the authored cone");
                }
                previous=pose;
            }
        }
    }
    private static void viewBranches()throws Exception{
        var model=org.fsvoxel.render.ModelLoader.load(java.nio.file.Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/legacy_rapier_fp.fsvoxel"));
        var cut=model.rig().clips().get("legacy_rapier_attack3_upper");
        var thrust=model.rig().clips().get("rapier_thrust_hit");
        var bridge=new RapierViewMotion();
        var outgoing=LegacyPlayback.sample(model,cut,cut.duration()*.86);
        bridge.sample(model,cut,1,"ACTIVE",3,3,6,0,outgoing);
        var authored=LegacyPlayback.sample(model,thrust,0);
        var entered=bridge.sample(model,thrust,2,"WINDUP",7,2,0,1./3,authored);
        for(String id:java.util.List.of("right_arm","right_forearm","right_item"))
            check(Math.abs(entered.get(id).fsQuaternion().dot(outgoing.get(id).fsQuaternion()))>.99999,"FP branch starts from the actual wrist, not a neutral reset: "+id);
        check(bridge.sample(model,thrust,2,"WINDUP",7,2,0,1./3,authored).equals(entered),"FP offhand render cannot double-advance transition");
        var contact=LegacyPlayback.sample(model,thrust,thrust.duration()*.26);
        var arrived=bridge.sample(model,thrust,3,"ACTIVE",7,2,7,2,contact);
        check(arrived.equals(contact),"FP bridge ends before authoritative contact without weakening the strike");
        var restart=new RapierViewMotion();
        restart.sample(model,cut,1,"WINDUP",3,3,1,0,outgoing);
        var restarted=restart.sample(model,cut,2,"WINDUP",3,3,0,.1,authored);
        check(Math.abs(restarted.get("right_arm").fsQuaternion().dot(outgoing.get("right_arm").fsQuaternion()))>.99999,"A repeated same-name WINDUP with a new sequence gets a fresh FP bridge");
        var pending=new PoseLayers.PendingAction<>("next",2,"rapier_thrust_hit","WINDUP",7,0,6);
        check(!pending.ready(1),"Next FP attack waits for the old contact endpoint");
        check(pending.holdElapsed(7,1)==6&&!pending.ready(1),"FP presents the exact old contact once");
        check(pending.ready(1.01),"The next FP render can release the queued branch without an avatar submission");
    }
}

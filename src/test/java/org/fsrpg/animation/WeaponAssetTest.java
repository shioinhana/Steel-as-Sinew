package org.fsrpg.animation;
import java.util.*;
import java.nio.file.*;
import org.fsvoxel.render.ModelLoader;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsrpg.client.PoseLayers;
import org.fsrpg.client.HeldItemTransform;
import org.fsrpg.client.SpearAimPose;
import org.fsrpg.combat.AttackAim;
import org.joml.*;
public final class WeaponAssetTest {
    static int checks;
    static void check(boolean x,String label){if(!x)throw new AssertionError(label);checks++;}
    public static void main(String[] args)throws Exception {
        for(String name:List.of("dual_blade","spear")) {
            var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/"+name+".fsvoxel"));
            check(model.rig().parts().size()==12,"same stock/slim skin rig");
            check(model.nodes().get("rapier").mesh().faces().isEmpty(),"real held items only");
            if(name.equals("dual_blade")) { naturalCarry(model); configuredCutContinuity(model); cuts(model); singleCuts(model); activeMarkerHandoffs(model); }
            for(var clip:model.rig().clips().values()) {
                if(clip.layer().equals("lower"))continue;
                var play=new PoseLayers.Playback(model.rig(),clip.id());
                var previousWrist=new HashMap<String,Quaternionf>();
                var previousVisibleHand=new HashMap<String,Quaternionf>();
                for(int frame=0;frame<=(int)(clip.duration()*60);frame++) {
                    var pose=play.sample(frame/60.0,0,frame/3.0,0,0,.25f,55).pose();
                    for(String side:List.of("left","right")) {
                        var q=pose.get(side+"_forearm").fsQuaternion();
                        if(name.equals("dual_blade")) {
                            float bend=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(q.x,q.w));
                            float pronation=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(q.y,q.w));
                            bend=(bend+540)%360-180;pronation=(pronation+540)%360-180;
                            var reconstructed=new Quaternionf().rotateX((float)java.lang.Math.toRadians(bend)).rotateY((float)java.lang.Math.toRadians(pronation));
                            check(rotationDifference(q,reconstructed)<.1f,"dual forearm uses only anatomical elbow flexion and longitudinal pronation "+clip.id()+" "+side);
                            check(bend<=-9.9f&&bend>=-110.1f,"dual elbow flexes forwards on its negative-X hinge "+clip.id()+" "+side+" bend="+bend);
                            check(java.lang.Math.abs(pronation)<=35.01f,"dual forearm turns the edge within its 35-degree limit "+clip.id()+" "+side+" pronation="+pronation);
                        } else check(q.transform(new Vector3f(1,0,0)).x>.999f,"hinge-only elbow "+clip.id());
                    }
                    if(name.equals("dual_blade")) {
                        var world=model.worldMatrices(pose);
                        for(String side:List.of("left","right")) {
                            var fore=world.get(side+"_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
                            var fist=world.get(side+"_hand").transformDirection(new Vector3f(0,-1,0)).normalize();
                            var hilt=world.get(side+"_hand").transformDirection(HeldItemTransform.dualBladeAxis()).normalize();
                            float swing=(float)java.lang.Math.toDegrees(java.lang.Math.acos(java.lang.Math.clamp(fore.dot(fist),-1,1)));
                            check(swing<=26,"dual wrist continues the forearm without an extreme bend "+clip.id()+" "+side+" frame="+frame+" swing="+swing);
                            check(java.lang.Math.abs(hilt.dot(fist)-(float)java.lang.Math.cos(java.lang.Math.toRadians(50)))<.0001f,
                                    "dual hilt retains its fixed 50-degree grip without wrist compensation "+clip.id()+" "+side);
                            var wrist=pose.get(side+"_hand").fsQuaternion();
                            float twist=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w));
                            twist=(twist+540)%360-180;
                            check(rotationDifference(wrist,new Quaternionf())<.1f,"dual wrist stays completely neutral while the forearm turns the blade "+clip.id()+" "+side+" frame="+frame);
                            var previous=previousWrist.put(side,new Quaternionf(wrist));
                            if(previous!=null) {
                                float change=(float)java.lang.Math.toDegrees(2*java.lang.Math.acos(java.lang.Math.clamp(java.lang.Math.abs(previous.dot(wrist)),0,1)));
                                check(change<30,"dual wrist has no sudden local flip between actual 60Hz samples "+clip.id()+" "+side+" frame="+frame+" degrees="+change);
                            }
                            var visible=world.get(side+"_hand").getUnnormalizedRotation(new Quaternionf()).normalize();
                            var before=previousVisibleHand.put(side,new Quaternionf(visible));
                            if(before!=null) {
                                float change=(float)java.lang.Math.toDegrees(2*java.lang.Math.acos(java.lang.Math.clamp(java.lang.Math.abs(before.dot(visible)),0,1)));
                                check(change<30,"visible dual fist has no elbow-branch flip between actual 60Hz samples "+clip.id()+" "+side+" frame="+frame+" degrees="+change);
                            }
                        }
                    }
                    if(name.equals("spear")) {
                        var world=model.worldMatrices(pose);
                        var right=world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
                        var left=world.get("left_hand").transformPosition(new Vector3f(0,-6.25f,0));
                        var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                        check(java.lang.Math.abs(right.distance(left)-58)<.05,"fixed 58cm rear-to-mid grip");
                        check(new Vector3f(left).sub(right).normalize().dot(shaft)>.999,"both palms lie on same shaft");
                        for(String side:List.of("right","left")) {
                            var fore=world.get(side+"_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
                            var fist=world.get(side+"_hand").transformDirection(new Vector3f(0,-1,0)).normalize();
                            check(fore.dot(fist)>.99999f,"authored spear fist continues forearm without wrist fold "+clip.id()+" "+side+" frame="+frame);
                            var wrist=pose.get(side+"_hand").fsQuaternion();
                            check(java.lang.Math.abs(wrist.x)<.0001f && java.lang.Math.abs(wrist.z)<.0001f,"authored spear wrist permits only longitudinal pronation "+clip.id()+" "+side);
                            float twist=(float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w));twist=(twist+540)%360-180;
                            check(java.lang.Math.abs(twist)<=90.01f,"authored spear sleeve cannot twist beyond 90 degrees "+clip.id()+" "+side+" frame="+frame+" twist="+twist);
                        }
                    }
                }
                if(name.equals("dual_blade") && clip.loop()) {
                    // Sampling exactly duration wraps to frame zero and hides a discontinuous final pose.
                    var first=model.worldMatrices(sample(model,clip.id(),0));
                    var last=model.worldMatrices(sample(model,clip.id(),clip.duration()-.00001));
                    for(String part:List.of("body","head","left_arm","left_forearm","left_hand","right_arm","right_forearm","right_hand")) {
                        float angle=rotationDifference(first.get(part).getUnnormalizedRotation(new Quaternionf()).normalize(),
                                last.get(part).getUnnormalizedRotation(new Quaternionf()).normalize());
                        check(angle<.25f,"dual loop has no accumulated elbow/hand orientation drift "+clip.id()+" "+part+" degrees="+angle);
                        float drift=first.get(part).getTranslation(new Vector3f()).distance(last.get(part).getTranslation(new Vector3f()));
                        check(drift<.2f,"dual loop closes its joint positions smoothly "+clip.id()+" "+part+" driftCm="+drift);
                    }
                }
            }
            if(name.equals("dual_blade")) carryEndpoints(model);
            breathing(model,name.equals("spear")?"spear_idle":"dual_idle_00");
            if(name.equals("spear")) {
                thrust(model);
                aimedGrips(model);
                var base=new PoseLayers.Playback(model.rig(),"spear_thrust").sample(.4,0,0,0,0,.25f,55).pose();
                for(int yaw=-55;yaw<=55;yaw+=11)for(int pitch=-40;pitch<=40;pitch+=10) {
                    var point=new org.fsrpg.combat.AttackAim.Point(yaw,pitch,3);
                    var posed=org.fsrpg.client.SpearAimPose.apply(model,base,point,0,1.62f);
                    var hand=model.worldMatrices(posed).get("right_hand");
                    var palm=hand.transformPosition(new Vector3f(0,-6.25f,0));
                    var axis=hand.transformDirection(new Vector3f(0,0,1)).normalize();
                    double y=java.lang.Math.toRadians(yaw),p=java.lang.Math.toRadians(pitch);
                    var target=new Vector3f((float)(-java.lang.Math.sin(y)*java.lang.Math.cos(p)*300),(float)(162-java.lang.Math.sin(p)*300),(float)(java.lang.Math.cos(y)*java.lang.Math.cos(p)*300));
                    check(new Vector3f(target).sub(palm).normalize().dot(axis)>.99999,"shaft aims from palm at cursor target, correcting eye/hand parallax");
                }
            }
        }
        float deg=(float)(java.lang.Math.PI/180);
        var display=new Matrix4f().translation(0,2f/16,2f/16).rotateXYZ(5*deg,270*deg,-40*deg).scale(1.7f,1.7f,.85f).translate(-.5f,-.5f,-.5f);
        var wrist=new Matrix4f().rotateX(.7f).rotateY(-.3f);
        var matrix=HeldItemTransform.spearCarrier(wrist).mul(display);
        var officialTip=matrix.transformDirection(new Vector3f(-1,1,0)).normalize();
        var expected=HeldItemTransform.palm(wrist).transformDirection(new Vector3f(0,0,1)).normalize();
        check(officialTip.dot(expected)>.9999,"official spear texture tip follows authored shaft direction");
        check(matrix.transformPosition(new Vector3f(.81f,.19f,.5f)).distance(HeldItemTransform.palm(wrist).getTranslation(new Vector3f()))<.0001,"official shaft rear grip in actual palm");
        System.out.println("WeaponAssetTest passed: "+checks+" checks");
    }

    /** Four real 08 handoffs: a new combo input arrived before the old rendered cut finished. */
    static void activeMarkerHandoffs(ModelDocument model) {
        String[] actions={"dual_ll","dual_rr","dual_ll","dual_rr"};
        String[] nextActions={"dual_rl","dual_lr","dual_rl","dual_lr"};
        // Immutable RenderFrameEvent.Post inputs from eighth-active-handoff-review/dual-active-handoff-trace.json.
        double[] oldElapsed={6.520000137388706,6.360000118613243,6.944999862462282,6.773750126361847};
        double[] oldClip={.5268000123649835,.5124000106751918,.5650499876216053,.5496375113725662};
        double[] packetStep={.36000001430511475,.3400000035762787,.3400000333786011,.3400000035762787};
        for(int scenario=0;scenario<actions.length;scenario++) {
            String action=actions[scenario],nextAction=nextActions[scenario];
            String side=action.startsWith("dual_l")?"left":"right";
            var clip=model.rig().clips().get(action);
            double marker=clip.events().stream().filter(e->e.name().equals("active_end")).findFirst().orElseThrow().time();
            check(java.lang.Math.abs(PoseLayers.phaseTime(clip,oldElapsed[scenario],4,3,7)-oldClip[scenario])<.000001,
                    "08 actual elapsed reproduces the truncated authored cut "+action+" case="+scenario);
            check(oldClip[scenario]<marker,"08 regression really arrives before the authored active endpoint "+action);
            var oldClock=new PoseLayers.AttackClock(oldElapsed[scenario],0,4,3);
            double tick=packetStep[scenario];
            var pending=new PoseLayers.PendingAction<>(nextAction,76,nextAction,"WINDUP",4,tick,7);
            double projected=oldClock.sample(tick);
            check(PoseLayers.protectedActive(oldElapsed[scenario],projected,4,3),
                    "ordinary combo retains the real old cut, even on a frame crossing its endpoint "+action);
            double previousClip=oldClip[scenario];boolean endpoint=false;
            float previousAngle=Float.NaN;float direction=action.endsWith("l")?1:-1;
            for(int frame=0;frame<8;frame++) {
                projected=oldClock.sample(tick);
                check(java.lang.Math.abs(projected-(oldElapsed[scenario]+tick))<.000001,
                        "pending new sequence does not contaminate the old 1x contact clock "+action);
                double held=pending.holdElapsed(projected,tick);
                double clipSeconds=PoseLayers.phaseTime(clip,held,4,3,7);
                check(clipSeconds>=previousClip && clipSeconds<=marker+.000001,
                        "queued input completes every remaining active frame monotonically "+action);
                var world=model.worldMatrices(sample(model,action,clipSeconds));
                var tip=swordTip(world.get(side+"_hand"));var chest=world.get("body").getTranslation(new Vector3f());
                float angle=(float)java.lang.Math.toDegrees(java.lang.Math.atan2((tip.x-chest.x)*direction,tip.z-chest.z));
                if(!Float.isNaN(previousAngle))check((angle-previousAngle+540)%360-180>=-.3f,
                        "protected tail keeps the actual official blade travelling in the cut direction "+action);
                check(!pending.ready(tick),"skin and armor cannot flush the queued action on the endpoint's own render frame "+action);
                if(held==7) {
                    endpoint=true;
                    var endpointWorld=model.worldMatrices(sample(model,action,marker));
                    check(tip.distance(swordTip(endpointWorld.get(side+"_hand")))<.001f,
                            "the actual blade reaches its complete authored active endpoint before handoff "+action);
                    check(java.lang.Math.abs(clipSeconds-marker)<.000001,"marker is actually sampled, rather than skipped "+action);
                    break;
                }
                previousClip=clipSeconds;previousAngle=angle;tick+=.34;
            }
            check(endpoint,"every recorded 08 truncation finishes its protected active tail "+action);
            // The latest authoritative slot is consumed once; an older packet must not replay a stale combo.
            double measured=tick+.1;
            check(pending.offer(nextAction+"-latest",77,nextAction,"WINDUP",3,measured),"latest queued sequence replaces stale intent "+action);
            check(!pending.offer("stale",76,nextAction,"WINDUP",4,measured+.01),"older queued combo cannot overwrite the latest slot "+action);
            check(pending.sequence()==77 && pending.value().equals(nextAction+"-latest"),"handoff consumes the latest intent exactly "+action);
            check(pending.offer(nextAction+"-latest",77,nextAction,"WINDUP",3,measured+.01),"duplicate latest packet remains harmless "+action);
            check(pending.phaseReceivedTick()==measured,"duplicate queued packet cannot restart its authoritative windup age "+action);
            double flush=measured+.34;
            check(pending.ready(flush),"next native render can hand off after the complete old endpoint "+action);
            double nextElapsed=pending.elapsed(flush,4,3,7);
            check(java.lang.Math.abs(nextElapsed-1.34)<.000001,"new binding starts at latest server elapsed, rather than restarting anticipation "+action);
            double entry=PoseLayers.entryWindupTicks(3.2,nextElapsed,4);
            check(entry<=4-nextElapsed+.000001,"entry blending cannot hide the next cut's own active beginning "+action);
            var nextClip=model.rig().clips().get(nextAction);
            double nextSeconds=PoseLayers.phaseTime(nextClip,nextElapsed,4,3,7);
            double nextBegin=nextClip.events().stream().filter(e->e.name().equals("active_begin")).findFirst().orElseThrow().time();
            check(nextSeconds>0 && nextSeconds<nextBegin,"latest queued cut advances in its real remaining windup "+nextAction);
        }
        for(String phase:List.of("DEAD","STUN","DODGE","GUARD","HOLSTERED"))
            check(!PoseLayers.ordinaryHandoff(phase),"forced state bypasses active-tail protection immediately "+phase);
    }
    static Map<String,Pose> sample(ModelDocument model,String action,double seconds) {
        return new PoseLayers.Playback(model.rig(),action).sample(seconds,0,seconds*20,0,0,.25f,55).pose();
    }
    static Vector3f palm(Map<String,Matrix4f> world,String side) {
        return world.get(side+"_hand").transformPosition(new Vector3f(0,-6.25f,0));
    }
    static Vector3f shaft(Map<String,Matrix4f> world,String side) {
        return world.get(side+"_hand").transformDirection(new Vector3f(0,0,1)).normalize();
    }
    static float pitch(Matrix4f matrix) {
        var up=matrix.transformDirection(new Vector3f(0,1,0)).normalize();
        return (float)java.lang.Math.toDegrees(java.lang.Math.atan2(up.z,up.y));
    }
    static float yaw(Matrix4f matrix) {
        var forward=matrix.transformDirection(new Vector3f(0,0,1)).normalize();
        return (float)java.lang.Math.toDegrees(java.lang.Math.atan2(forward.x,forward.z));
    }
    static boolean sameRotation(Quaternionf first,Quaternionf second) {
        return java.lang.Math.abs(first.normalize().dot(second.normalize()))>.999999f;
    }
    static float rotationDifference(Quaternionf first,Quaternionf second) {
        return (float)java.lang.Math.toDegrees(2*java.lang.Math.acos(java.lang.Math.clamp(java.lang.Math.abs(first.dot(second)),0,1)));
    }
    /** Measure a point on the actual vanilla sword sprite after its unchanged display transform. */
    static Vector3f swordTip(Matrix4f wrist) {
        var nativeTip=HeldItemTransform.dualCarrier(wrist).mul(HeldItemTransform.standardDisplay())
                .transformPosition(new Vector3f(14f/16,14f/16,.5f));
        return new Vector3f(nativeTip.x*100,150-nativeTip.y*100,-nativeTip.z*100);
    }
    static void naturalCarry(ModelDocument model) {
        var neutral=model.worldMatrices(sample(model,"dual_idle_00",0));
        for(String side:List.of("left","right")) {
            float sign=side.equals("left")?1:-1;
            var shoulder=neutral.get(side+"_arm").getTranslation(new Vector3f());
            var elbow=neutral.get(side+"_forearm").getTranslation(new Vector3f());
            var hand=palm(neutral,side);
            check(shoulder.y-elbow.y>34,"neutral upper arm hangs beside the torso rather than lifting the elbow "+side);
            check(hand.y>78&&hand.y<95&&hand.x*sign>35&&hand.x*sign<55,"neutral fist rests beside the hip "+side+" "+hand);
            check(hand.z-elbow.z>6,"neutral elbow bends forwards, with the palm ahead of the elbow "+side);
            check(java.lang.Math.abs(elbow.x-shoulder.x)<8,"neutral elbow stays close to its shoulder line "+side);
            check(neutral.get(side+"_hand").transformDirection(new Vector3f(0,-1,0)).normalize()
                    .dot(neutral.get(side+"_forearm").transformDirection(new Vector3f(0,-1,0)).normalize())>.99999f,
                    "neutral wrist continues the forearm without bending to point the sword "+side);
        }
        for(String[] state:new String[][]{{"dual_idle_11","right"},{"dual_idle_22","left"}}) {
            var world=model.worldMatrices(sample(model,state[0],0));
            check(palm(world,state[1]).z>32,"crossed carry stays in front of the chest instead of threading through it "+state[0]);
        }
    }
    static void configuredCutContinuity(ModelDocument model) throws Exception {
        // Keep old-rig configured continuity coverage; imported action names are checked in LegacyAssetTest.
        var skills=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/defaults/legacy-0.4/skills.json"))).getAsJsonArray();
        for(var value:skills) {
            var skill=value.getAsJsonObject();String action=skill.get("action").getAsString();
            if(!List.of("dual_ll","dual_rl","dual_rr","dual_lr").contains(action))continue;
            int windup=skill.get("windupTicks").getAsInt(),active=skill.get("activeTicks").getAsInt(),recovery=skill.get("recoveryTicks").getAsInt();
            var clip=model.rig().clips().get(action);Map<String,Matrix4f> previous=null;float maximum=0;
            for(int frame=0;frame<=(windup+active+recovery)*3;frame++) {
                var world=model.worldMatrices(sample(model,action,PoseLayers.phaseTime(clip,frame/3.0,windup,active,recovery)));
                for(String side:List.of("left","right")) {
                    var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
                    var elbow=world.get(side+"_forearm").getTranslation(new Vector3f());
                    var wrist=world.get(side+"_hand").getTranslation(new Vector3f());
                    check(java.lang.Math.abs(shoulder.distance(elbow)-37.5)<.001f&&java.lang.Math.abs(elbow.distance(wrist)-25)<.001f,
                            "actual configured 60Hz playback retains arm lengths "+action+" "+side+" frame="+frame);
                    if(previous!=null)for(String suffix:List.of("_arm","_forearm","_hand")) {
                        String part=side+suffix;
                        float angle=rotationDifference(previous.get(part).getUnnormalizedRotation(new Quaternionf()).normalize(),world.get(part).getUnnormalizedRotation(new Quaternionf()).normalize());
                        maximum=java.lang.Math.max(maximum,angle);
                        check(angle<30,"configured 60Hz playback has no hidden joint snap "+action+" "+part+" frame="+frame+" degrees="+angle);
                    }
                }
                previous=world;
            }
            System.out.println("Configured dual 60Hz max joint step "+action+" = "+maximum+" degrees");
        }
    }
    static void cuts(ModelDocument model) {
        for(String action:List.of("dual_ll","dual_rl","dual_rr","dual_lr")) {
            String side=action.startsWith("dual_l")?"left":"right";
            float sign=action.endsWith("l")?1:-1;
            var clip=model.rig().clips().get(action);
            double begin=clip.events().stream().filter(e->e.name().equals("active_begin")).findFirst().orElseThrow().time();
            double end=clip.events().stream().filter(e->e.name().equals("active_end")).findFirst().orElseThrow().time();
            float first=0,last=0,previousAcross=Float.NEGATIVE_INFINITY,frontReach=0;
            float previousAngle=Float.NaN,minAcross=Float.POSITIVE_INFINITY,maxAcross=Float.NEGATIVE_INFINITY,minTipY=Float.POSITIVE_INFINITY,maxTipY=Float.NEGATIVE_INFINITY;
            var entryTip=swordTip(model.worldMatrices(sample(model,action,begin)).get(side+"_hand"));
            var exitTip=swordTip(model.worldMatrices(sample(model,action,end)).get(side+"_hand"));
            var cuttingDirection=new Vector3f(exitTip).sub(entryTip);cuttingDirection.z=0;cuttingDirection.normalize();
            Vector3f previousTip=null;
            // Include interpolated half-frames, rather than inspecting only authored control poses.
            for(int frame=0;frame<=64;frame++) {
                var world=model.worldMatrices(sample(model,action,begin+(end-begin)*frame/64));
                var tip=swordTip(world.get(side+"_hand"));float across=tip.x*sign;
                var chest=world.get("body").getTranslation(new Vector3f());
                float angle=(float)java.lang.Math.toDegrees(java.lang.Math.atan2((tip.x-chest.x)*sign,tip.z-chest.z));
                if(frame==0)first=across;
                float advance=Float.isNaN(previousAngle)?0:(angle-previousAngle+540)%360-180;
                // A descending diagonal cut need not have monotonic horizontal yaw.
                // It must still progress through the target in its across-and-down plane.
                if(previousTip!=null)check(new Vector3f(tip).sub(previousTip).dot(cuttingDirection)>=-.05f,
                        "blade does not reverse along the requested diagonal cut "+action+" frame="+frame);
                previousTip=new Vector3f(tip);
                if(previousAcross<=0 && across>=0)frontReach=tip.z;
                minAcross=java.lang.Math.min(minAcross,across);maxAcross=java.lang.Math.max(maxAcross,across);
                minTipY=java.lang.Math.min(minTipY,tip.y);maxTipY=java.lang.Math.max(maxTipY,tip.y);
                previousAcross=last=across;previousAngle=angle;
            }
            check(first<0 && last>40,"diagonal cut crosses the enemy-facing center in its requested lateral direction "+action+" "+first+".."+last);
            check(java.lang.Math.hypot(maxAcross-minAcross,maxTipY-minTipY)>120,"actual sword tip has a broad diagonal cut across and down "+action+" x="+(maxAcross-minAcross)+" y="+(maxTipY-minTipY));
            check(frontReach>80,"sword crosses center in front of the actor, rather than cutting behind the chest "+action+" reach="+frontReach);
            float faceSlip=0,minimumPalmY=Float.POSITIVE_INFINITY,signedSlip=0,totalAxial=0,totalTransverse=0;double slipAt=0;
            for(int i=0;i<=24;i++) {
                double t=begin+(end-begin)*(.1+.8*i/24.0),h=1.0/600;
                var before=model.worldMatrices(sample(model,action,t-h));
                var after=model.worldMatrices(sample(model,action,t+h));
                var current=model.worldMatrices(sample(model,action,t));
                var velocity=swordTip(after.get(side+"_hand")).sub(swordTip(before.get(side+"_hand"))).normalize();
                var normal=HeldItemTransform.dualCarrier(current.get(side+"_hand")).mul(HeldItemTransform.standardDisplay())
                        .transformDirection(new Vector3f(0,0,1));
                normal.set(normal.x,-normal.y,-normal.z).normalize();
                float projection=normal.dot(velocity);
                if(java.lang.Math.abs(projection)>faceSlip){faceSlip=java.lang.Math.abs(projection);signedSlip=projection;slipAt=t;}
                minimumPalmY=java.lang.Math.min(minimumPalmY,palm(current,side).y);
                var middleBefore=swordTip(before.get(side+"_hand")).add(palm(before,side)).mul(.5f);
                var middleAfter=swordTip(after.get(side+"_hand")).add(palm(after,side)).mul(.5f);
                var middleVelocity=middleAfter.sub(middleBefore).normalize();
                var blade=swordTip(current.get(side+"_hand")).sub(palm(current,side)).normalize();
                float axial=java.lang.Math.abs(middleVelocity.dot(blade));
                totalAxial+=axial;totalTransverse+=(float)java.lang.Math.sqrt(java.lang.Math.max(0,1-axial*axial));
            }
            check(faceSlip<.35f,"official blade cuts along its edge plane rather than slapping with its wide face "+action+" slip="+faceSlip+" signed="+signedSlip+" time="+slipAt);
            check(minimumPalmY>90,"descending slash ends at the abdomen, above the hip and knee "+action+" height="+minimumPalmY);
            check(totalTransverse>totalAxial*1.2f&&totalTransverse/25>.75f,"blade midpoint travels predominantly across its shaft, not in a thrust "+action+" transverse="+(totalTransverse/25)+" axial="+(totalAxial/25));
            var startPalm=palm(model.worldMatrices(sample(model,action,begin)),side);
            var endPalm=palm(model.worldMatrices(sample(model,action,end)),side);
            float lateral=(endPalm.x-startPalm.x)*sign,minDepth=Float.POSITIVE_INFINITY,maxDepth=Float.NEGATIVE_INFINITY;
            for(int i=0;i<=64;i++) {
                var hand=palm(model.worldMatrices(sample(model,action,begin+(end-begin)*i/64)),side);
                minDepth=java.lang.Math.min(minDepth,hand.z);maxDepth=java.lang.Math.max(maxDepth,hand.z);
            }
            check(lateral>50&&startPalm.y-endPalm.y>25,"grip drives a real lateral downward slash "+action+" lateral="+lateral+" drop="+(startPalm.y-endPalm.y));
            check(maxDepth-minDepth<lateral*.65f,"grip does not first lunge outwards to imitate a slash "+action+" depth="+(maxDepth-minDepth)+" lateral="+lateral);
            System.out.println("Dual actual blade "+action+" face="+faceSlip+" midpoint transverse/axial="+(totalTransverse/25)+"/"+(totalAxial/25)+" grip lateral/drop/depth="+lateral+"/"+(startPalm.y-endPalm.y)+"/"+(maxDepth-minDepth));
            float contactStartY=swordTip(model.worldMatrices(sample(model,action,begin)).get(side+"_hand")).y;
            float contactEndY=swordTip(model.worldMatrices(sample(model,action,end)).get(side+"_hand")).y;
            check(contactStartY-contactEndY>70,
                    "both hands continue naturally into a downward diagonal cut "+action);
            float minimumTurn=Float.POSITIVE_INFINITY,maximumTurn=Float.NEGATIVE_INFINITY;
            float minimumLean=Float.POSITIVE_INFINITY,maximumLean=Float.NEGATIVE_INFINITY;
            for(int frame=0;frame<=120;frame++) {
                var world=model.worldMatrices(sample(model,action,frame/120.0));
                float turn=yaw(world.get("body"))*sign,lean=pitch(world.get("body"));
                minimumTurn=java.lang.Math.min(minimumTurn,turn);maximumTurn=java.lang.Math.max(maximumTurn,turn);
                minimumLean=java.lang.Math.min(minimumLean,lean);maximumLean=java.lang.Math.max(maximumLean,lean);
            }
            check(minimumTurn<-10 && maximumTurn>15 && maximumTurn-minimumTurn>35,"chest winds against the cut and drives through it "+action);
            check(minimumLean<-1 && maximumLean>5,"cut transfers weight from anticipation into visible forward lean "+action+" "+minimumLean+".."+maximumLean);
        }
    }
    static void singleCuts(ModelDocument model) {
        var ready=model.worldMatrices(sample(model,"dual_idle_00",0));
        for(String action:List.of("dual_ll","dual_rr")) {
            String side=action.equals("dual_ll")?"left":"right",support=side.equals("left")?"right":"left";
            for(double time:new double[]{0,1}) {
                var world=model.worldMatrices(sample(model,action,time));
                for(String part:List.of("body","left_arm","left_forearm","left_hand","right_arm","right_forearm","right_hand")) {
                    check(world.get(part).getTranslation(new Vector3f()).distance(ready.get(part).getTranslation(new Vector3f()))<.1f,
                            "single cut returns both arms to ready for another independent click "+action+" "+part+" "+time);
                    check(rotationDifference(world.get(part).getUnnormalizedRotation(new Quaternionf()).normalize(),ready.get(part).getUnnormalizedRotation(new Quaternionf()).normalize())<.1f,
                            "single cut closes its ready orientations "+action+" "+part+" "+time);
                }
            }
            var loaded=sample(model,action,.23);var released=sample(model,action,.48);
            float loadedBend=java.lang.Math.abs((float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(loaded.get(side+"_forearm").fsQuaternion().x,loaded.get(side+"_forearm").fsQuaternion().w)));
            float releasedBend=java.lang.Math.abs((float)java.lang.Math.toDegrees(2*java.lang.Math.atan2(released.get(side+"_forearm").fsQuaternion().x,released.get(side+"_forearm").fsQuaternion().w)));
            check(loadedBend-releasedBend>20,"elbow visibly unfolds after the loaded preparation "+action);
            var loadedWorld=model.worldMatrices(loaded);var releasedWorld=model.worldMatrices(released);
            check(rotationDifference(loadedWorld.get(side+"_arm").getUnnormalizedRotation(new Quaternionf()).normalize(),releasedWorld.get(side+"_arm").getUnnormalizedRotation(new Quaternionf()).normalize())>25
                            && loadedWorld.get(side+"_forearm").getTranslation(new Vector3f()).distance(releasedWorld.get(side+"_forearm").getTranslation(new Vector3f()))>20,
                    "shoulder drive carries the upper arm and elbow through the slash "+action);
            check(rotationDifference(sample(model,action,.5).get(support+"_forearm").fsQuaternion(),sample(model,"dual_idle_00",0).get(support+"_forearm").fsQuaternion())>15,
                    "support arm counters the cut with a controlled elbow bend "+action);
        }
        for(int frame=0;frame<=120;frame++) {
            var left=model.worldMatrices(sample(model,"dual_ll",frame/120.0));
            var right=model.worldMatrices(sample(model,"dual_rr",frame/120.0));
            var mirrored=palm(left,"left");mirrored.x=-mirrored.x;
            check(mirrored.distance(palm(right,"right"))<.15f,"left and right clicks use equally broad mirrored grip arcs "+frame);
        }
    }
    static void carryEndpoints(ModelDocument model) {
        for(String[] endpoint:new String[][]{{"dual_ll","dual_idle_00","left"},{"dual_rl","dual_idle_01","right"},
                {"dual_rr","dual_idle_00","right"},{"dual_lr","dual_idle_20","left"}}) {
            var finished=model.worldMatrices(sample(model,endpoint[0],model.rig().clips().get(endpoint[0]).duration()));
            var carry=model.worldMatrices(sample(model,endpoint[1],0));
            check(palm(finished,endpoint[2]).distance(palm(carry,endpoint[2]))<.2f,"cut finishes at its ready carrying grip "+endpoint[0]);
            for(String suffix:List.of("_arm","_forearm","_hand")) {
                String part=endpoint[2]+suffix;
                float angle=rotationDifference(finished.get(part).getUnnormalizedRotation(new Quaternionf()).normalize(),
                        carry.get(part).getUnnormalizedRotation(new Quaternionf()).normalize());
                check(angle<2,"cut settles into the carrying pose without selecting the other elbow branch "+endpoint[0]+" "+part+" degrees="+angle);
            }
        }
    }
    static void breathing(ModelDocument model,String action) {
        float minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        float minPitch=Float.POSITIVE_INFINITY,maxPitch=Float.NEGATIVE_INFINITY;
        float minHead=Float.POSITIVE_INFINITY,maxHead=Float.NEGATIVE_INFINITY;
        for(int frame=0;frame<=480;frame++) {
            var world=model.worldMatrices(sample(model,action,frame/120.0));
            float y=world.get("body").m31(),p=pitch(world.get("body")),head=pitch(world.get("head"));
            minY=java.lang.Math.min(minY,y);maxY=java.lang.Math.max(maxY,y);
            minPitch=java.lang.Math.min(minPitch,p);maxPitch=java.lang.Math.max(maxPitch,p);
            minHead=java.lang.Math.min(minHead,head);maxHead=java.lang.Math.max(maxHead,head);
            var bodyUp=world.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
            var headUp=world.get("head").transformDirection(new Vector3f(0,1,0)).normalize();
            check(bodyUp.dot(headUp)>.998f,"head follows the breathing chest instead of staying rigidly upright "+action);
        }
        check(maxY-minY>2,"complete breathing cycle visibly raises and lowers the chest "+action);
        check(maxPitch-minPitch>1.5f,"breathing changes the chest posture, not only whole-body height "+action);
        check(maxHead-minHead>1.2f,"breathing carries naturally into the head "+action);
        var first=sample(model,action,0);var last=sample(model,action,4-.00001);
        for(String part:List.of("body","head","right_arm","left_arm")) {
            check(first.get(part).position().vector().distance(last.get(part).position().vector())<.05f
                    && sameRotation(first.get(part).fsQuaternion(),last.get(part).fsQuaternion()),
                    "breathing closes smoothly without a loop jump "+action+" "+part);
        }
    }
    static Vector3f shoulderRelative(Map<String,Matrix4f> world,String side) {
        var offset=palm(world,side).sub(world.get(side+"_arm").getTranslation(new Vector3f()));
        return world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize().invert().transform(offset);
    }
    static void thrust(ModelDocument model) {
        var neutral=model.worldMatrices(sample(model,"spear_thrust",0));
        var end=model.worldMatrices(sample(model,"spear_thrust",1));
        float initial=palm(neutral,"right").z,minimum=initial,maximum=initial;
        Map<String,Matrix4f> withdrawn=neutral,extended=neutral;
        double withdrawTime=0,extendTime=0;
        for(int frame=0;frame<=120;frame++) {
            double time=frame/120.0;var world=model.worldMatrices(sample(model,"spear_thrust",time));
            float z=palm(world,"right").z;
            if(z<minimum){minimum=z;withdrawn=world;withdrawTime=time;}
            if(z>maximum){maximum=z;extended=world;extendTime=time;}
        }
        check(initial-minimum>12,"rear hand visibly withdraws the shaft before thrusting");
        check(maximum-initial>18 && maximum-minimum>35,"rear hand sends the shaft out with a distinct useful thrust stroke");
        check(withdrawTime<extendTime && withdrawTime<.35 && extendTime>.35 && extendTime<.65,"withdrawal precedes the contact drive");
        check(palm(end,"right").distance(palm(neutral,"right"))<.2f,"thrust retracts to its two-handed ready grip");
        float torsoStroke=withdrawn.get("body").getTranslation(new Vector3f()).distance(extended.get("body").getTranslation(new Vector3f()));
        float armStroke=shoulderRelative(withdrawn,"right").distance(shoulderRelative(extended,"right"));
        check(armStroke>15 && armStroke>torsoStroke*1.5f,"hand drive relative to the shoulder exceeds torso sliding: arm="+armStroke+" torso="+torsoStroke);
        check(torsoStroke<(maximum-minimum)*.4f,"torso supports the thrust without replacing hand extension");
        check(pitch(extended.get("body"))>7 && pitch(withdrawn.get("body"))<-2,"thrust uses backward preparation and forward body weight");
    }
    /** The slim shoulder offset is an input rig fixture; assertions inspect the final solved world geometry. */
    static Map<String,Pose> slim(Map<String,Pose> original) {
        var result=new HashMap<>(original);
        for(String side:List.of("right","left")) {
            var at=original.get(side+"_arm");var p=at.position();
            result.put(side+"_arm",new Pose(at.rotation(),new V(p.x()+(side.equals("right")?3.125f:-3.125f),p.y(),p.z()),at.scale()));
        }
        return result;
    }
    static void aimedGrips(ModelDocument model) {
        for(boolean narrow:List.of(false,true))for(double time:new double[]{0,.24,.405,.47,.72,1}) {
            var authored=sample(model,"spear_thrust",time);var base=narrow?slim(authored):authored;
            for(int yaw:new int[]{-55,-33,0,33,55})for(int pitch:new int[]{-40,-20,0,20,40}) {
                var point=new AttackAim.Point(yaw,pitch,3);
                var posed=SpearAimPose.apply(model,base,point,0,1.62f);var world=model.worldMatrices(posed);
                var rear=palm(world,"right");var front=palm(world,"left");var axis=shaft(world,"right");
                var between=new Vector3f(front).sub(rear);float spacing=between.length();
                float offAxis=new Vector3f(between).sub(new Vector3f(axis).mul(between.dot(axis))).length();
                String where=(narrow?"slim":"stock")+" t="+time+" yaw="+yaw+" pitch="+pitch;
                check(java.lang.Math.abs(spacing-58)<.1f,"final skin rig retains both grips' 58cm spacing "+where+" spacing="+spacing);
                check(offAxis<.1f && between.dot(axis)>0,"final front palm stays on the rendered shaft "+where+" error="+offAxis);
                var beforeUp=model.worldMatrices(base).get("body").transformDirection(new Vector3f(0,1,0)).normalize();
                var afterUp=world.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
                check(beforeUp.distance(afterUp)<.001f,"necessary spear yaw preserves authored chest lean and breath "+where);
                check(base.get("body").position().vector().distance(posed.get("body").position().vector())<.001f,"aim preserves authored chest weight transfer "+where);
                for(String part:List.of("right_leg","left_leg")) {
                    var before=base.get(part);var after=posed.get(part);
                    check(before.position().vector().distance(after.position().vector())<.001f
                            && sameRotation(before.fsQuaternion(),after.fsQuaternion()),
                            "aim leaves authored chest/head weight and lower-body layer intact "+part+" "+where);
                }
                double y=java.lang.Math.toRadians(yaw),p=java.lang.Math.toRadians(pitch);
                var target=new Vector3f((float)(-java.lang.Math.sin(y)*java.lang.Math.cos(p)*300),(float)(162-java.lang.Math.sin(p)*300),(float)(java.lang.Math.cos(y)*java.lang.Math.cos(p)*300));
                check(new Vector3f(target).sub(rear).normalize().dot(axis)>.99999f,"final skin grip aims the actual spear at the selected point "+where);
            }
        }
    }
}

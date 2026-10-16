package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.joml.Vector3f;

/** Exercise the bounded live solver, including repeated four-tick thrusts and abrupt field corners. */
public final class SpearRealtimeTest {
    public static void main(String[] args)throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        var report=new JsonObject();var groups=new JsonArray();report.add("groups",groups);
        boolean legacy=Boolean.getBoolean("fsrpg.realtimeLegacy");
        long fallbackStart=legacy?0:SpearAimPose.realtimeFallbackCalls();
        int total=0,unsafe=0,unreachable=0,penetrating=0,maxTrials=0,maxProjections=0,maxBridges=0;float maxGripError=0,maxAxisError=0,maxStep=0,maxContact=0;double sumContact=0;
        for(String action:List.of("spear_idle","spear_thrust","spear_sweep","short_thrust"))for(String track:List.of("front","sweep","corners")) {
            var playback=new PoseLayers.Playback(model.rig(),action.equals("short_thrust")?"spear_thrust":action);
            var history=new SpearMotion.State();var group=new JsonObject();groups.add(group);
            group.addProperty("action",action);group.addProperty("track",track);
            int failures=0,exceptions=0,limbFailures=0,groupPoses=0;float groupGrip=0,groupAxis=0,groupStep=0,groupContact=0;double groupContactSum=0;
            org.joml.Quaternionf prior=null;
            for(int frame=0;frame<Integer.getInteger("fsrpg.realtimeFrames",240);frame++) {
                double seconds=frame/60.,tick=frame/3.;boolean ready=action.equals("spear_idle");
                double sourceTime=action.equals("short_thrust")?(seconds%.2)/.2*playback.clip().duration():seconds%playback.clip().duration();
                var source=playback.sample(sourceTime,seconds,tick,0,0,.25f,55).pose();
                var raw=model.worldMatrices(source);
                raw=ready?SpearMotion.readySource(raw,-12,22,50):SpearMotion.underarmSource(raw);
                double yaw=track.equals("front")?0:track.equals("sweep")?SpearAimField.MAX_YAW_DEGREES*Math.sin(seconds*Math.PI):((frame/8)%2==0?-1:1)*SpearAimField.MAX_YAW_DEGREES;
                double pitch=track.equals("front")?0:track.equals("sweep")?SpearAimField.MAX_PITCH_DEGREES*Math.sin(seconds*Math.PI*2):((frame/16)%2==0?-1:1)*SpearAimField.MAX_PITCH_DEGREES;
                var aim=new AttackAim.Point(yaw,pitch,4.5/(Math.cos(Math.toRadians(yaw))*Math.cos(Math.toRadians(pitch))));
                var seed=history.prepare(raw,tick,ready?90:0,ready?SpearAimPose.authoredFidelity(aim,0):1);
                var limits=new SpearConstraints.Limits(135,120,1.5f,85,18,true).withCarry(ready);
                try {
                    var solved=SpearAimPose.apply(model,source,aim,0,1.62f,seed.rear(),seed.chest(),seed.actual(),seed.continuity(),seed.authoredMotion(),limits);
                    if(!legacy){maxTrials=Math.max(maxTrials,SpearAimPose.lastRealtimeTrials());maxProjections=Math.max(maxProjections,SpearAimPose.lastRealtimeProjections());maxBridges=Math.max(maxBridges,SpearAimPose.lastRealtimeBridges());}
                    var world=model.worldMatrices(solved);history.commit(world,tick);total++;groupPoses++;
                    var rear=world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
                    var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                    var front=world.get("left_hand").transformPosition(new Vector3f(0,-6.25f,0));
                    float grip=front.distance(new Vector3f(rear).fma(limits.gripSpacingCm(),shaft));
                    var target=new Vector3f((float)(-Math.sin(Math.toRadians(yaw))*Math.cos(Math.toRadians(pitch))*aim.distance()*100),
                            (float)(162-Math.sin(Math.toRadians(pitch))*aim.distance()*100),(float)(Math.cos(Math.toRadians(yaw))*Math.cos(Math.toRadians(pitch))*aim.distance()*100));
                    float axis=shaft.cross(new Vector3f(target).sub(rear).normalize(),new Vector3f()).length();
                    groupGrip=Math.max(groupGrip,grip);groupAxis=Math.max(groupAxis,axis);
                    var rotation=world.get("right_hand").getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
                    if(prior!=null)groupStep=Math.max(groupStep,(float)Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(prior.dot(rotation)),0,1))));prior=rotation;
                    var space=new SpearConstraints.Space(world,limits);
                    if(!space.shaftClear(rear,shaft))failures++;
                    for(String side:List.of("right","left")) {
                        var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
                        var elbow=world.get(side+"_forearm").getTranslation(new Vector3f());
                        var palm=world.get(side+"_hand").transformPosition(new Vector3f(0,-6.25f,0));
                        float contact=space.deepArmContact(shoulder,elbow,palm);
                        groupContact=Math.max(groupContact,contact);groupContactSum+=contact;
                        if(contact>.101f)limbFailures++;
                    }
                }catch(IllegalArgumentException exception){exceptions++;if(Boolean.getBoolean("fsrpg.realtimeDebug")&&exceptions<3)System.out.println(action+" "+track+" "+frame+" "+exception);}
            }
            group.addProperty("unsafeShaftFrames",failures);group.addProperty("unreachableFrames",exceptions);
            group.addProperty("penetratingArms",limbFailures);penetrating+=limbFailures;
            group.addProperty("maximumDeepContactScore",groupContact);group.addProperty("meanDeepContactScore",groupContactSum/(groupPoses*2));
            maxContact=Math.max(maxContact,groupContact);sumContact+=groupContactSum;
            group.addProperty("maximumGripErrorCm",groupGrip);group.addProperty("maximumAxisCrossError",groupAxis);group.addProperty("maximumHandRotationStepDegrees",groupStep);
            unsafe+=failures;unreachable+=exceptions;maxGripError=Math.max(maxGripError,groupGrip);maxAxisError=Math.max(maxAxisError,groupAxis);maxStep=Math.max(maxStep,groupStep);
            System.out.println(group);
        }
        report.addProperty("poses",total);report.addProperty("unsafeShaftFrames",unsafe);report.addProperty("unreachableFrames",unreachable);
        report.addProperty("penetratingArms",penetrating);
        report.addProperty("maximumDeepContactScore",maxContact);report.addProperty("meanDeepContactScore",sumContact/(total*2));
        report.addProperty("maximumTrialsPerPose",maxTrials);report.addProperty("maximumProjectionIterationsPerPose",maxProjections);
        int trialBudget=legacy?Integer.MAX_VALUE:SpearAimPose.realtimeTrialBudget(),projectionBudget=legacy?Integer.MAX_VALUE:SpearAimPose.realtimeProjectionBudget();
        report.addProperty("fastPathTrialBudget",trialBudget);report.addProperty("fastPathProjectionIterationBudget",projectionBudget);
        report.addProperty("maximumAnalyticBridgeCandidates",maxBridges);report.addProperty("analyticBridgeCandidateBudget",legacy?0:SpearAimPose.realtimeBridgeBudget());
        report.addProperty("continuationFallbackSolves",legacy?0:SpearAimPose.realtimeFallbackCalls()-fallbackStart);
        report.addProperty("budgetScope","Local projection trials and analytic bridge candidates have separate fixed budgets; the live path has no global continuation fallback");
        report.addProperty("maximumGripErrorCm",maxGripError);report.addProperty("maximumAxisCrossError",maxAxisError);report.addProperty("maximumHandRotationStepDegrees",maxStep);
        Path output=Path.of(args.length>0?args[0]:"verification/spear-realtime-20261016/quality.json");Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        if(unsafe>0||unreachable>0||maxGripError>.03f||maxAxisError>.001f||maxTrials>trialBudget||maxProjections>projectionBudget||(!legacy&&maxBridges>SpearAimPose.realtimeBridgeBudget()))throw new AssertionError(report);
    }
}

package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/** Separate regression for steep-to-level pitch, coupled cursor circles and attack handoffs. */
public final class SpearPitchContinuityTest {
    private static final List<String> BONES=List.of("body","head","right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand");
    private static final Map<String,JsonObject> GROUPS=new LinkedHashMap<>();
    private static final JsonArray FAILURES=new JsonArray();
    private static int checks,failures,poses;
    private static void require(boolean condition,String label,JsonObject where) {
        checks++;if(!condition){failures++;if(FAILURES.size()<30){var entry=where.deepCopy();entry.addProperty("failure",label);FAILURES.add(entry);}}
    }
    private static float angle(Quaternionf a,Quaternionf b) {return (float)Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(a.dot(b)),0,1)));}
    private static Vector3f palm(Map<String,Matrix4f> world) {return world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));}
    private static Quaternionf chest(Map<String,Matrix4f> world) {return world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();}
    private static void peak(String group,String key,float value,JsonObject where) {
        var summary=GROUPS.computeIfAbsent(group,k->new JsonObject());
        if(!summary.has(key)||summary.get(key).getAsFloat()<value){summary.addProperty(key,value);summary.add(key+"Where",where.deepCopy());}
    }
    private static JsonObject where(String group,boolean slim,int frame,AttackAim.Point aim,String clip,String phase) {
        var out=new JsonObject();out.addProperty("group",group);out.addProperty("slim",slim);out.addProperty("frame",frame);
        out.addProperty("yaw",aim.yaw());out.addProperty("pitch",aim.pitch());out.addProperty("distance",aim.distance());out.addProperty("clip",clip);out.addProperty("phase",phase);return out;
    }
    private static Map<String,Pose> source(ModelDocument model,String clip,double seconds,double viewYaw,boolean slim) {
        var pose=new HashMap<>(new PoseLayers.Playback(model.rig(),clip).sample(seconds,seconds,seconds*20,0,(float)-viewYaw,.25f,55).pose());
        if(slim)for(var side:List.of("right","left")){var old=pose.get(side+"_arm");var p=old.position();pose.put(side+"_arm",new Pose(old.rotation(),new V(p.x()+(side.equals("right")?3.125f:-3.125f),p.y(),p.z()),old.scale()));}
        return pose;
    }
    private static final class Track {
        final ModelDocument model;final String group;final boolean slim;
        Map<String,Matrix4f> previous;Vector3f oldSourceRear;Quaternionf oldSourceChest;
        AttackAim.Point oldAim;
        Track(ModelDocument model,String group,boolean slim){this.model=model;this.group=group;this.slim=slim;}
        void solve(Map<String,Pose> source,AttackAim.Point aim,String clip,String phase,int frame) {
            var location=where(group,slim,frame,aim,clip,phase);var raw=model.worldMatrices(source);var rawRear=palm(raw);var rawChest=chest(raw);
            var rearSeed=previous==null?null:PoseLayers.gripSeed(rawRear,oldSourceRear,palm(previous));
            var chestSeed=previous==null?null:PoseLayers.chestSeed(rawChest,oldSourceChest,chest(previous),1/60.0,phase.equals("READY")||phase.equals("RECOVERY")?90:0);
            var posed=SpearAimPose.apply(model,source,aim,0,1.62f,rearSeed,chestSeed,previous,SpearConstraints.Limits.DEFAULT);
            var world=model.worldMatrices(posed);poses++;
            var rear=palm(world);var front=world.get("left_hand").transformPosition(new Vector3f(0,-6.25f,0));var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            require(Math.abs(rear.distance(front)-58)<.02f,"closed grip retains 58cm",location);
            require(new Vector3f(front).sub(rear).cross(shaft).length()<.02f,"both grips stay on pole",location);
            double yaw=Math.toRadians(aim.yaw()),pitch=Math.toRadians(aim.pitch()),range=aim.distance()*100;
            var target=new Vector3f((float)(-Math.sin(yaw)*Math.cos(pitch)*range),(float)(162-Math.sin(pitch)*range),(float)(Math.cos(yaw)*Math.cos(pitch)*range));
            require(new Vector3f(target).sub(rear).normalize().dot(shaft)>.99999f,"closed pole retains actual target",location);
            for(var side:List.of("right","left")) {
                var wrist=posed.get(side+"_hand").fsQuaternion();float twist=(float)Math.abs(Math.IEEEremainder(Math.toDegrees(2*Math.atan2(wrist.y,wrist.w)),360));
                peak(group,side+"WristPronationDegrees",twist,location);require(twist<=150.03f,"wrist pronation exceeds 150deg on "+side+": "+twist,location);
                require(Math.abs(wrist.x)<.0001f&&Math.abs(wrist.z)<.0001f,"wrist remains straight on "+side,location);
                var hinge=posed.get(side+"_forearm").fsQuaternion();float bend=(float)Math.IEEEremainder(Math.toDegrees(2*Math.atan2(hinge.x,hinge.w)),360);
                require(bend>=9.97f&&bend<=135.03f,"positive elbow 10..135deg on "+side,location);
            }
            if(previous!=null) {
                float aimStep=(float)Math.hypot(AttackAim.wrap(aim.yaw()-oldAim.yaw()),aim.pitch()-oldAim.pitch());
                location.addProperty("aimStepDegrees",aimStep);
                for(var bone:BONES) {
                    float step=angle(previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize());
                    peak(group,bone+"StepDegrees",step,location);
                    if(aimStep<=2.01f)require(step<35,"<=2deg aim step produces >=35deg joint jump on "+bone+": "+step,location);
                    else require(step<90,"rendered joint jumps >=90deg on "+bone+": "+step,location);
                }
            }
            previous=world;oldSourceRear=rawRear;oldSourceChest=rawChest;oldAim=aim;
        }
    }
    private static void backwardPitch(ModelDocument model) {
        for(boolean slim:List.of(false,true))for(boolean viewSource:List.of(false,true)) {
            String group=viewSource?"connectedPitchViewSource":"connectedPitchFixedSource";var track=new Track(model,group,slim);
            for(int frame=0;frame<=76;frame++) {
                double pitch=frame<=38?-69+frame*.5:-50-(frame-38)*.5;
                track.solve(source(model,"spear_idle",.405,viewSource?-134:0,slim),new AttackAim.Point(-134,pitch,4.5),"spear_idle","READY",frame);
            }
        }
    }
    /** Continuous 60Hz versions of the actual smoke's cursor/phase schedules. */
    private static void wave(ModelDocument model,int scene) {
        String group=scene==0?"readyPitchCycle":scene==1?"attackPitchCycle":"movingPitchCycle";
        for(boolean slim:List.of(false,true)) {
            var track=new Track(model,group,slim);String oldClip="spear_idle";Map<String,Pose> lastSource=null,bridgeSource=null;
            AttackAim.Point lastAim=null,bridgeAim=null;double bridgeStarted=0,bridgeTicks=0,aimTicks=0;
            for(int frame=0;frame<=630;frame++) {
                double tick=frame/3.0,yaw=scene==0?170*Math.sin(tick*Math.PI/100):scene==1?105*Math.sin(tick*Math.PI/90):135*Math.sin(tick*Math.PI/100);
                double pitch=scene==0?78*Math.sin(tick*Math.PI/70):scene==1?58*Math.sin(tick*Math.PI/80):76*Math.sin(tick*Math.PI/85);
                double cycle=tick%50;int cycleIndex=(int)(tick/50);boolean sweep=cycleIndex%2==1;String clip="spear_idle",phase="READY";int w=0,a=0,r=0;
                AttackAim.Point aim=new AttackAim.Point(scene==1?0:yaw,scene==1?0:pitch,4.5);double seconds=tick/20;
                if(scene>0) {
                    w=sweep?6:5;a=sweep?10:4;r=sweep?12:9;
                    if(cycle<w+a+r) {
                        clip=sweep?"spear_sweep":"spear_thrust";phase=cycle<w?"WINDUP":cycle<w+a?"ACTIVE":"RECOVERY";
                        double start=cycleIndex*50,attackYaw=scene==1?new double[]{0,140,-140,0}[cycleIndex%4]:135*Math.sin(start*Math.PI/100);
                        double attackPitch=scene==1?58*Math.sin(start*Math.PI/80):76*Math.sin(start*Math.PI/85);
                        var path=sweep?new AttackAim(List.of(new AttackAim.Point(attackYaw-35,Math.clamp(attackPitch-12,-80,80),3),new AttackAim.Point(attackYaw,attackPitch,3),new AttackAim.Point(attackYaw+35,Math.clamp(attackPitch+12,-80,80),3))):new AttackAim(List.of(new AttackAim.Point(attackYaw,attackPitch,3)));
                        aim=path.sample(PoseLayers.aimFraction(phase,cycle,w,a));seconds=PoseLayers.phaseTime(model.rig().clips().get(clip),cycle,w,a,r);
                    }
                }
                var source=source(model,clip,seconds,scene==1?0:yaw,slim);
                if(!clip.equals(oldClip)) {
                    bridgeSource=lastSource;bridgeAim=lastAim;bridgeStarted=tick;
                    bridgeTicks=PoseLayers.transitionTicks(model.rig().clips().get(clip),.12,w,!phase.equals("READY"));
                    aimTicks=Math.max(.12,model.rig().clips().get(clip).transition())*20;
                    if(bridgeAim!=null)aimTicks=phase.equals("READY")?PoseLayers.aimTransitionTicks(bridgeAim,aim,aimTicks,240):PoseLayers.windupAimTicks(PoseLayers.aimTransitionTicks(bridgeAim,aim,aimTicks),0,w);
                }
                if(bridgeSource!=null) {
                    float weight=PoseLayers.transitionWeight(tick,bridgeStarted,bridgeTicks);
                    if(weight<1){var blend=new HashMap<String,Pose>();for(var entry:source.entrySet())blend.put(entry.getKey(),bridgeSource.getOrDefault(entry.getKey(),entry.getValue()).mix(entry.getValue(),weight,true));source=blend;}else bridgeSource=null;
                }
                if(bridgeAim!=null) {
                    if(phase.equals("ACTIVE"))bridgeAim=null;
                    else {float weight=phase.equals("WINDUP")?PoseLayers.windupAimWeight(tick,bridgeStarted,aimTicks):PoseLayers.transitionWeight(tick,bridgeStarted,aimTicks);aim=PoseLayers.blendAim(bridgeAim,aim,weight);if(weight>=1)bridgeAim=null;}
                }
                track.solve(source,aim,clip,phase,frame);oldClip=clip;lastSource=source;lastAim=aim;
            }
        }
    }
    public static void main(String[] arguments)throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        backwardPitch(model);for(int scene=0;scene<3;scene++)wave(model,scene);
        var report=new JsonObject();report.addProperty("scope","Separate steep-to-level/backward pitch and coupled actual smoke cursor/attack schedules; leaves original 1930-pose safety comparison unchanged");
        report.addProperty("limitation","Offline coupled schedules include authored phase/entry/aim transitions and view yaw, but use unmodified locomotion pose; actual skin and movement acceptance remains the real client capture");
        report.addProperty("poses",poses);report.addProperty("checks",checks);report.addProperty("failures",failures);report.add("failureExamples",FAILURES);
        var groups=new JsonObject();GROUPS.forEach(groups::add);report.add("groups",groups);
        Path output=Path.of(arguments.length>0?arguments[0]:"verification/spear-solver-20261012/pitch-continuity.json");Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SpearPitchContinuityTest: "+poses+" poses, "+checks+" checks, "+failures+" failures; "+output.toAbsolutePath());
        if(failures>0)throw new AssertionError("Pitch/cursor continuity failures: "+failures+"; see diagnostic evidence");
    }
}

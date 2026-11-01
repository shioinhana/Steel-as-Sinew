package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Fixed-aim locomotion regression: continuity must retain movement amplitude and phase. */
public final class SpearMovementContinuityTest {
    private static final int HZ=60, WARMUP=HZ, FRAMES=6*HZ;
    private static final List<String> SIDES=List.of("right","left");
    private static final AttackAim.Point FRONT=new AttackAim.Point(0,0,3);
    private static final Map<String,JsonObject> GROUPS=new LinkedHashMap<>();
    private static final JsonArray FAILURES=new JsonArray();
    private static final Method HISTORY_SOLVER=historySolver();
    private static final Method MOTION_SOLVER=motionSolver();
    private static int checks, failures, poses;

    private static void require(boolean condition,String message,String group,int frame) {
        checks++;
        if(!condition) {
            failures++;
            var summary=GROUPS.get(group);
            if(summary!=null)summary.addProperty("failedChecks",summary.has("failedChecks")?summary.get("failedChecks").getAsInt()+1:1);
            if(FAILURES.size()<40) {
                var item=new JsonObject();item.addProperty("group",group);item.addProperty("frame",frame);
                item.addProperty("failure",message);FAILURES.add(item);
            }
        }
    }
    private static Method historySolver() {
        try {return SpearAimPose.class.getDeclaredMethod("apply",ModelDocument.class,Map.class,AttackAim.Point.class,
                float.class,float.class,Vector3f.class,Quaternionf.class,Map.class,SpearConstraints.Limits.class);}
        catch(NoSuchMethodException oldSolver){return null;}
    }
    private static Method motionSolver() {
        try {return SpearAimPose.class.getDeclaredMethod("apply",ModelDocument.class,Map.class,AttackAim.Point.class,
                float.class,float.class,Vector3f.class,Quaternionf.class,Map.class,Map.class,float.class,SpearConstraints.Limits.class);}
        catch(NoSuchMethodException oldSolver){return null;}
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Pose> solve(ModelDocument model,Map<String,Pose> source,Vector3f rear,
                                         Quaternionf chest,Map<String,Matrix4f> previous) {
        if(HISTORY_SOLVER==null)return SpearAimPose.apply(model,source,FRONT,0,1.62f,rear,chest);
        try {return (Map<String,Pose>)HISTORY_SOLVER.invoke(null,model,source,FRONT,0,1.62f,rear,chest,previous,SpearConstraints.Limits.DEFAULT);}
        catch(InvocationTargetException failed) {
            if(failed.getCause() instanceof RuntimeException error)throw error;
            if(failed.getCause() instanceof Error error)throw error;
            throw new IllegalStateException(failed.getCause());
        } catch(ReflectiveOperationException failed){throw new IllegalStateException(failed);}
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Pose> solve(ModelDocument model,Map<String,Pose> source,SpearMotion.Seed seed) {
        return solve(model,source,seed,FRONT);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Pose> solve(ModelDocument model,Map<String,Pose> source,SpearMotion.Seed seed,AttackAim.Point aim) {
        try {return (Map<String,Pose>)MOTION_SOLVER.invoke(null,model,source,aim,0,1.62f,seed.rear(),seed.chest(),
                seed.actual(),seed.continuity(),seed.authoredMotion(),SpearConstraints.Limits.DEFAULT);}
        catch(InvocationTargetException failed) {
            if(failed.getCause() instanceof RuntimeException error)throw error;
            if(failed.getCause() instanceof Error error)throw error;
            throw new IllegalStateException(failed.getCause());
        } catch(ReflectiveOperationException failed){throw new IllegalStateException(failed);}
    }
    private static Vector3f palm(Map<String,Matrix4f> world,String side) {
        return world.get(side+"_hand").transformPosition(new Vector3f(0,-6.25f,0));
    }
    private static Quaternionf chest(Map<String,Matrix4f> world) {
        return world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
    }
    private static float angle(Quaternionf a,Quaternionf b) {
        return (float)Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(a.dot(b)),0,1)));
    }
    private static void maximum(JsonObject group,String key,float value) {
        if(!group.has(key)||group.get(key).getAsFloat()<value)group.addProperty(key,value);
    }
    private static Map<String,Pose> slim(Map<String,Pose> source,boolean slim) {
        if(!slim)return source;
        var result=new HashMap<>(source);
        for(var side:SIDES) {
            var old=source.get(side+"_arm");var p=old.position();
            result.put(side+"_arm",new Pose(old.rotation(),new V(p.x()+(side.equals("right")?3.125f:-3.125f),p.y(),p.z()),old.scale()));
        }
        return result;
    }

    /** DC offsets are irrelevant: retain the centred motion, its gain and its same-frame phase. */
    private static final class Signal {
        final List<Vector3f> expected=new ArrayList<>(), actual=new ArrayList<>();
        void add(Vector3f from,Vector3f to){expected.add(new Vector3f(from));actual.add(new Vector3f(to));}
        JsonObject metrics() {
            var result=new JsonObject();var aMean=new Vector3f();var eMean=new Vector3f();
            for(int n=0;n<expected.size();n++){eMean.add(expected.get(n));aMean.add(actual.get(n));}
            eMean.div(expected.size());aMean.div(actual.size());double ee=0,aa=0,ea=0;
            Vector3f eLow=new Vector3f(Float.POSITIVE_INFINITY),eHigh=new Vector3f(Float.NEGATIVE_INFINITY);
            Vector3f aLow=new Vector3f(Float.POSITIVE_INFINITY),aHigh=new Vector3f(Float.NEGATIVE_INFINITY);
            for(int n=0;n<expected.size();n++) {
                var e=new Vector3f(expected.get(n)).sub(eMean);var a=new Vector3f(actual.get(n)).sub(aMean);
                ee+=e.lengthSquared();aa+=a.lengthSquared();ea+=e.dot(a);
                eLow.min(expected.get(n));eHigh.max(expected.get(n));aLow.min(actual.get(n));aHigh.max(actual.get(n));
            }
            result.addProperty("samples",expected.size());result.addProperty("expectedRms",Math.sqrt(ee/expected.size()));
            result.addProperty("actualRms",Math.sqrt(aa/actual.size()));result.addProperty("amplitudeRatio",ee>1e-8?Math.sqrt(aa/ee):0);
            result.addProperty("sameFrameGain",ee>1e-8?ea/ee:0);
            result.addProperty("sameFrameCorrelation",ee>1e-8&&aa>1e-8?ea/Math.sqrt(ee*aa):0);
            result.addProperty("expectedPeakToPeak",eHigh.distance(eLow));result.addProperty("actualPeakToPeak",aHigh.distance(aLow));
            return result;
        }
        void check(String group,String name,double minimumGain,double minimumCorrelation) {
            var metric=metrics();GROUPS.get(group).add(name,metric);
            // A reference with visible motion prevents a stationary fixture from passing this check.
            require(metric.get("expectedRms").getAsDouble()>.15,name+" reference contains visible motion",group,FRAMES);
            require(metric.get("sameFrameGain").getAsDouble()>=minimumGain,
                    name+" retains same-frame motion gain >="+minimumGain+": "+metric.get("sameFrameGain"),group,FRAMES);
            require(metric.get("sameFrameCorrelation").getAsDouble()>=minimumCorrelation,
                    name+" follows authored phase >="+minimumCorrelation+": "+metric.get("sameFrameCorrelation"),group,FRAMES);
        }
    }

    private static final class Track {
        final ModelDocument model;final String group;
        final SpearMotion.State motion=MOTION_SOLVER==null?null:new SpearMotion.State();
        final Signal rawRear=new Signal(), reachableRear=new Signal(), bodyMotion=new Signal(), headMotion=new Signal();
        Map<String,Matrix4f> previous;Vector3f previousSourceRear;Quaternionf previousSourceChest;
        Track(ModelDocument model,String group) {
            this.model=model;this.group=group;GROUPS.put(group,new JsonObject());
            GROUPS.get(group).addProperty("aimYawDegrees",0);GROUPS.get(group).addProperty("aimPitchDegrees",0);
            GROUPS.get(group).addProperty("aimDistanceMetres",3);
        }
        void sample(Map<String,Pose> source,int frame) {
            var raw=model.worldMatrices(source);var rear=palm(raw,"right");var body=chest(raw);
            var rearSeed=previous==null?null:PoseLayers.gripSeed(rear,previousSourceRear,palm(previous,"right"));
            var chestSeed=previous==null?null:PoseLayers.chestSeed(body,previousSourceChest,chest(previous),1.0/HZ,90);
            var posed=motion==null?solve(model,source,rearSeed,chestSeed,previous):solve(model,source,motion.prepare(raw,frame/3.0,90));
            var world=model.worldMatrices(posed);poses++;
            if(motion!=null)motion.commit(world,frame/3.0);
            // Separate stateless feasibility from authored intent. It supplies a phase reference,
            // never substitutes for the history-enabled output being tested.
            var reachable=model.worldMatrices(solve(model,source,null,null,null));
            var actual=palm(world,"right");var front=palm(world,"left");
            var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            require(Math.abs(actual.distance(front)-58)<.02,"58cm rigid grip",group,frame);
            require(new Vector3f(front).sub(actual).cross(shaft).length()<.02,"both palms stay on pole",group,frame);
            require(new Vector3f(0,162,300).sub(actual).normalize().dot(shaft)>.99999f,"actual clicked target remains exact",group,frame);
            var sourceUp=raw.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
            var actualUp=world.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
            require(sourceUp.distance(actualUp)<.001,"torso retains stride and lean",group,frame);
            require(raw.get("body").getTranslation(new Vector3f()).distance(world.get("body").getTranslation(new Vector3f()))<.001,
                    "torso retains breathing, compression and pelvis displacement",group,frame);
            for(String side:SIDES) {
                var hinge=posed.get(side+"_forearm").fsQuaternion();
                float bend=(float)Math.IEEEremainder(Math.toDegrees(2*Math.atan2(hinge.x,hinge.w)),360);
                require(bend>=9.97f&&bend<=135.03f,"positive elbow remains 10..135deg: "+side+"="+bend,group,frame);
                var wrist=posed.get(side+"_hand").fsQuaternion();
                float pronation=(float)Math.abs(Math.IEEEremainder(Math.toDegrees(2*Math.atan2(wrist.y,wrist.w)),360));
                require(pronation<=150.03f,"hard wrist limit remains 150deg: "+side+"="+pronation,group,frame);
                require(Math.abs(wrist.x)<.0001f&&Math.abs(wrist.z)<.0001f,"wrist remains straight: "+side,group,frame);
                var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
                var elbow=world.get(side+"_forearm").getTranslation(new Vector3f());
                var hand=world.get(side+"_hand").getTranslation(new Vector3f());
                require(Math.abs(shoulder.distance(elbow)-37.5)<.01&&Math.abs(elbow.distance(hand)-25)<.01,
                        "physical bone lengths remain unchanged: "+side,group,frame);
            }
            if(previous!=null) {
                maximum(GROUPS.get(group),"rearStepCm",actual.distance(palm(previous,"right")));
                for(var bone:List.of("body","right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand")) {
                    float step=angle(previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),
                            world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize());
                    maximum(GROUPS.get(group),bone+"StepDegrees",step);
                    require(step<35,"fixed aim movement has no >=35deg branch jump: "+bone+"="+step,group,frame);
                }
            }
            if(frame>=WARMUP) {
                rawRear.add(rear,actual);reachableRear.add(palm(reachable,"right"),actual);
                bodyMotion.add(raw.get("body").getTranslation(new Vector3f()),world.get("body").getTranslation(new Vector3f()));
                headMotion.add(raw.get("head").getTranslation(new Vector3f()),world.get("head").getTranslation(new Vector3f()));
            }
            previous=world;previousSourceRear=rear;previousSourceChest=body;
        }
        void finish(boolean strictGrip,boolean movingTorso,boolean movementGate) {
            if(movementGate)rawRear.check(group,"rawAuthoredRearResponse",strictGrip?.65:.35,strictGrip?.9:.55);
            else GROUPS.get(group).add("rawAuthoredRearResponse",rawRear.metrics());
            // A stateless search may select another collision branch; keep it as a diagnostic.
            GROUPS.get(group).add("reachableRearResponse",reachableRear.metrics());
            if(movingTorso)bodyMotion.check(group,"torsoTranslationResponse",.99,.999);
            else GROUPS.get(group).add("torsoTranslationResponse",bodyMotion.metrics());
            GROUPS.get(group).add("headTranslationResponse",headMotion.metrics());
        }
    }

    /** Authored breath/run clips with the runtime stride, inertia, balance and foot-support stack. */
    private static void locomotion(ModelDocument model,boolean slim,String kind) {
        var track=new Track(model,kind+(slim?"Slim":"Stock"));var motion=new PresentationMotion();
        var idle=new PoseLayers.Playback(model.rig(),"spear_idle");var run=new PoseLayers.Playback(model.rig(),"spear_run");
        double speed=kind.equals("idle")?0:kind.equals("sprint")?.28:.13;
        boolean sprint=kind.equals("sprint"),sideways=kind.equals("strafe");
        float walkSpeed=speed==0?0:.65f;
        for(int frame=0;frame<=FRAMES;frame++) {
            double tick=frame/3.0,seconds=frame/(double)HZ;long simulationTick=frame/3;
            double vx=sideways?-speed:0,vz=sideways?0:speed;
            motion.observePosition(simulationTick,vx*simulationTick,vz*simulationTick,vx,vz);
            // A duplicate draw with reset vanilla interpolation fields must not erase movement.
            motion.observePosition(simulationTick,vx*simulationTick,vz*simulationTick,0,0);
            require(Math.abs(motion.velocityX()-vx)<.00001&&Math.abs(motion.velocityZ()-vz)<.00001,
                    "multiple render submissions retain this tick's displacement",track.group,frame);
            var sample=idle.sample(seconds,seconds,tick,walkSpeed,0,.25f,55);
            motion.advance(tick,motion.velocityX(),motion.velocityZ(),0,sprint,true,sample.movingWeight(),1.4f);
            var source=sample.pose();
            if(sprint)source=PresentationMotion.blend(source,run.sample(seconds,seconds,tick,walkSpeed,0,.25f,55).pose(),motion.runWeight());
            float phase=motion.gaitPhase(tick,sample.movingWeight(),sample.lowerBodyWeight(),.72f);
            source=PresentationMotion.stride(source,phase,sample.movingWeight(),1);
            source=motion.applyLean(source,18,1,1);var balance=motion.balance(6,1);
            var hip=PresentationMotion.withAuthoredHipShift(source,model.rig().parts().get("body").rest().position(),balance);
            source=PresentationMotion.shiftTorso(source,balance);var lower=model.worldMatrices(source);
            var supported=Locomotion.supported(lower.get("right_leg"),lower.get("left_leg"),sample.lowerBodyWeight(),phase,
                    walkSpeed,sample.movingWeight(),motion.lateralLean(),motion.forwardLean(),.72f,48,hip,
                    source.get("body").position().y()-model.rig().parts().get("body").rest().position().y(),motion.gaitLane());
            source=PresentationMotion.shiftTorso(source,new PresentationMotion.Balance(0,-supported.pelvisDropCm(),0));
            track.sample(slim(source,slim),frame);
            if(frame==FRAMES) {
                GROUPS.get(track.group).addProperty("runWeight",motion.runWeight());
                GROUPS.get(track.group).addProperty("forwardLean",motion.forwardLean());
                GROUPS.get(track.group).addProperty("lateralLean",motion.lateralLean());
                if(speed>0) {
                    require(Math.hypot(motion.forwardLean(),motion.lateralLean())>.4,"moving actor retains readable inertia",track.group,frame);
                    require(phase>.001,"actual displacement advances gait clock",track.group,frame);
                }
                if(sprint)require(motion.runWeight()>.999,"sprint retains authored run carry",track.group,frame);
            }
        }
        track.finish(false,true,true);
    }

    /** A small legal whole-body translation isolates transport from other gait or lean signals. */
    private static void translationControl(ModelDocument model,boolean slim) {
        String group="translationControl"+(slim?"Slim":"Stock");var track=new Track(model,group);
        var base=slim(new PoseLayers.Playback(model.rig(),"spear_idle").sample(0,0,0,0,0,.25f,55).pose(),slim);
        // Freeze the source at a solved, feasible stance before adding a visible 3cm periodic shift.
        base=solve(model,base,null,null,null);
        for(int frame=0;frame<=FRAMES;frame++) {
            float wave=(float)Math.sin(2*Math.PI*frame/(2*HZ));
            var source=PresentationMotion.shiftTorso(base,new PresentationMotion.Balance(2*wave,3*wave,0));
            track.sample(source,frame);
        }
        track.finish(true,true,true);
    }

    /** Tiny authored shoulder motion changes only the source grip, at a fixed chest and target. */
    private static void sourceGripControl(ModelDocument model,boolean slim) {
        String group="sourceGripControl"+(slim?"Slim":"Stock");var track=new Track(model,group);
        var base=slim(new PoseLayers.Playback(model.rig(),"spear_idle").sample(0,0,0,0,0,.25f,55).pose(),slim);
        base=solve(model,base,null,null,null);var arm=base.get("right_arm");
        for(int frame=0;frame<=FRAMES;frame++) {
            float wave=(float)Math.sin(2*Math.PI*frame/(2*HZ));var source=new HashMap<>(base);
            var q=new Quaternionf().rotationY((float)Math.toRadians(4*wave)).mul(arm.fsQuaternion());
            source.put("right_arm",new Pose(FsvoxAnimation.fromQuaternion(q),arm.position(),arm.scale()));
            track.sample(source,frame);
        }
        track.finish(true,false,false);
    }

    /** Independent fixture construction from a common pole and the rig's physical link lengths. */
    private static V stableFixtureRotation(Quaternionf quaternion) {
        // Compute XYZ with double matrix entries, then choose the equivalent Euler branch
        // away from near-180 components that lose precision in the host float quaternion API.
        double length=Math.sqrt(quaternion.x*(double)quaternion.x+quaternion.y*(double)quaternion.y
                +quaternion.z*(double)quaternion.z+quaternion.w*(double)quaternion.w);
        double x=quaternion.x/length,y=quaternion.y/length,z=quaternion.z/length,w=quaternion.w/length;
        double m00=1-2*(y*y+z*z),m01=2*(x*y-z*w),m02=2*(x*z+y*w);
        double m12=2*(y*z-x*w),m22=1-2*(x*x+y*y),horizontal=Math.hypot(m00,m01);
        double a=horizontal<1e-8?Math.atan2(2*(y*z+x*w),1-2*(x*x+z*z)):Math.atan2(-m12,m22);
        double b=Math.atan2(m02,horizontal),c=horizontal<1e-8?0:Math.atan2(-m01,m00);
        double otherA=Math.IEEEremainder(a+Math.PI,2*Math.PI),otherB=b>=0?Math.PI-b:-Math.PI-b;
        double otherC=Math.IEEEremainder(c+Math.PI,2*Math.PI);
        if(Math.abs(otherA)+Math.abs(otherC)<Math.abs(a)+Math.abs(c)){a=otherA;b=otherB;c=otherC;}
        return new V((float)Math.toDegrees(a),(float)Math.toDegrees(b),(float)Math.toDegrees(c));
    }
    private static Map<String,Pose> commonGrip(ModelDocument model,Map<String,Pose> base,Vector3f rear) {
        var world=model.worldMatrices(base);var result=new HashMap<>(base);
        var shaft=new Vector3f(0,162,300).sub(rear).normalize();var inverseBody=chest(world).invert();
        for(String side:SIDES) {
            var palm=new Vector3f(rear).fma(side.equals("right")?0:58,shaft);
            var delta=palm.sub(world.get(side+"_arm").getTranslation(new Vector3f()));
            var radial=new Vector3f(delta).sub(new Vector3f(shaft).mul(delta.dot(shaft)));float radius=radial.length();
            float cosine=(delta.lengthSquared()+31.25f*31.25f-37.5f*37.5f)/(2*31.25f*radius);
            if(Math.abs(cosine)>1)return null;
            var basis=radial.div(radius);var tangent=new Vector3f(shaft).cross(basis).normalize();
            float sine=(float)Math.sqrt(1-cosine*cosine);
            var first=new Vector3f(basis).mul(cosine).fma(sine,tangent);
            var second=new Vector3f(basis).mul(cosine).fma(-sine,tangent);
            var original=world.get(side+"_forearm").transformDirection(new Vector3f(0,-1,0)).normalize();
            var fore=first.dot(original)>=second.dot(original)?first:second;
            var upper=new Vector3f(delta).fma(-31.25f,fore).div(37.5f).normalize();
            var hinge=new Vector3f(upper).cross(fore).normalize();
            var y=new Vector3f(upper).negate();var z=new Vector3f(hinge).cross(y);
            var upperRotation=new Quaternionf().setFromNormalized(new Matrix3f(hinge,y,z));
            float bend=(float)Math.toDegrees(Math.acos(Math.clamp(upper.dot(fore),-1,1)));
            var lowerZ=new Vector3f(hinge).cross(new Vector3f(fore).negate());
            float twist=(float)Math.toDegrees(Math.atan2(shaft.dot(hinge),shaft.dot(lowerZ)));
            if(bend<9.97f||bend>135.03f||Math.abs(twist)>150.03f)return null;
            var old=result.get(side+"_arm");result.put(side+"_arm",new Pose(stableFixtureRotation(new Quaternionf(inverseBody).mul(upperRotation)),old.position(),old.scale()));
            old=result.get(side+"_forearm");result.put(side+"_forearm",new Pose(new V(bend,0,0),old.position(),old.scale()));
            old=result.get(side+"_hand");result.put(side+"_hand",new Pose(new V(0,twist,0),old.position(),old.scale()));
        }
        return result;
    }
    private static float capsuleClearance(Map<String,Matrix4f> world,Vector3f from,Vector3f to,float radius,float start) {
        float minimum=Float.POSITIVE_INFINITY;
        for(String bone:List.of("body","head")) {
            var inverse=new Matrix4f(world.get(bone)).invert();var a=inverse.transformPosition(new Vector3f(from));
            var b=inverse.transformPosition(new Vector3f(to));boolean head=bone.equals("head");
            for(int n=0;n<=100;n++) {
                var p=new Vector3f(a).lerp(b,start+(1-start)*n/100f);
                float dx=Math.abs(p.x)-25,dy=Math.abs(p.y-(head?25:37.5f))-(head?25:37.5f),dz=Math.abs(p.z)-(head?25:12.5f);
                float outside=(float)Math.sqrt(Math.pow(Math.max(0,dx),2)+Math.pow(Math.max(0,dy),2)+Math.pow(Math.max(0,dz),2));
                minimum=Math.min(minimum,outside+Math.min(0,Math.max(dx,Math.max(dy,dz)))-radius);
            }
        }
        return minimum;
    }
    private static float fixtureClearance(Map<String,Matrix4f> world) {
        var rear=palm(world,"right");var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
        float minimum=capsuleClearance(world,new Vector3f(rear).fma(-60,shaft),new Vector3f(rear).fma(178,shaft),2,0);
        for(String side:SIDES) {
            var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());var elbow=world.get(side+"_forearm").getTranslation(new Vector3f());
            minimum=Math.min(minimum,capsuleClearance(world,shoulder,elbow,10,.32f));
            minimum=Math.min(minimum,capsuleClearance(world,elbow,palm(world,side),8.5f,0));
        }
        return minimum;
    }
    /** Legal paired source grips remove incompatible authored hands as an explanation for freezing. */
    private static void legalCommonGripControl(ModelDocument model,boolean slim) {
        String group="legalCommonGripControl"+(slim?"Slim":"Stock");var track=new Track(model,group);
        var base=slim(new PoseLayers.Playback(model.rig(),"spear_idle").sample(0,0,0,0,0,.25f,55).pose(),slim);
        base=solve(model,base,null,null,null);var baseWorld=model.worldMatrices(base);var baseRear=palm(baseWorld,"right");
        float baseClearance=fixtureClearance(baseWorld),chosen=0,minimumClearance=Float.POSITIVE_INFINITY;
        List<Map<String,Pose>> sources=null;
        for(float radius:new float[]{2,1,.5f,.25f}) {
            var candidate=new ArrayList<Map<String,Pose>>();float minimum=Float.POSITIVE_INFINITY;boolean legal=true;
            for(int frame=0;frame<=FRAMES;frame++) {
                double cycle=2*Math.PI*frame/(2*HZ);var rear=new Vector3f(baseRear).add((float)(radius*Math.sin(cycle)),(float)(-.75+radius*(Math.cos(cycle)-1)),0);
                var source=commonGrip(model,base,rear);
                if(source==null){legal=false;break;}
                var world=model.worldMatrices(source);float clearance=fixtureClearance(world);minimum=Math.min(minimum,clearance);
                if(clearance<baseClearance-.5f){legal=false;break;}
                candidate.add(source);
            }
            if(legal){sources=candidate;chosen=radius;minimumClearance=minimum;break;}
        }
        require(sources!=null,"a visible legal paired-grip loop exists at the initial safe stance",group,0);
        if(sources==null)return;
        GROUPS.get(group).addProperty("sourceCircleRadiusCm",chosen);GROUPS.get(group).addProperty("initialClearanceCm",baseClearance);
        GROUPS.get(group).addProperty("minimumSourceClearanceCm",minimumClearance);
        for(int frame=0;frame<sources.size();frame++) {
            var source=sources.get(frame);var world=model.worldMatrices(source);var rear=palm(world,"right");var front=palm(world,"left");
            var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            float spacingError=Math.abs(rear.distance(front)-58),axisError=new Vector3f(front).sub(rear).cross(shaft).length();
            maximum(GROUPS.get(group),"sourceGripSpacingErrorCm",spacingError);maximum(GROUPS.get(group),"sourceGripAxisErrorCm",axisError);
            require(spacingError<.02&&axisError<.02,
                    "authored pair is already a rigid common grip: spacing="+spacingError+"cm axis="+axisError+"cm",group,frame);
            require(new Vector3f(0,162,300).sub(rear).normalize().dot(shaft)>.99999f,"authored pair already targets the fixed view point",group,frame);
            track.sample(source,frame);
        }
        track.finish(true,false,true);
    }

    private static final List<String> TRANSPORT_BONES=List.of("body","head","right_arm","right_forearm","right_hand",
            "left_arm","left_forearm","left_hand");
    private static Map<String,Matrix4f> copyMatrices(Map<String,Matrix4f> source) {
        var result=new HashMap<String,Matrix4f>();source.forEach((id,value)->result.put(id,new Matrix4f(value)));return result;
    }
    private static Map<String,Matrix4f> expectedTransport(Map<String,Matrix4f> current,Map<String,Matrix4f> before,Map<String,Matrix4f> solved) {
        var result=new HashMap<String,Matrix4f>();
        for(String bone:TRANSPORT_BONES)result.put(bone,new Matrix4f(current.get(bone)).mul(new Matrix4f(before.get(bone)).invert()).mul(solved.get(bone)));
        return result;
    }
    /** Palm intent follows its authored position; only the fixed IK correction follows the torso.
     * Compute the independent rigid-rotation expectation with quaternions rather than hand matrices. */
    private static Vector3f expectedTorsoRear(Map<String,Matrix4f> current,Map<String,Matrix4f> anchorSource,Map<String,Matrix4f> anchorSolved) {
        var offset=palm(anchorSolved,"right").sub(palm(anchorSource,"right"));
        var torsoDelta=chest(current).mul(chest(anchorSource).invert()).normalize();
        return palm(current,"right").add(torsoDelta.transform(offset));
    }
    private static void sameTransport(Map<String,Matrix4f> expected,Map<String,Matrix4f> actual,String group,int frame) {
        for(String bone:TRANSPORT_BONES) {
            float error=0;float[] a=actual.get(bone).get(new float[16]),b=expected.get(bone).get(new float[16]);
            for(int n=0;n<a.length;n++)error=Math.max(error,Math.abs(a[n]-b[n]));
            maximum(GROUPS.get(group),"maximumTransportMatrixError",error);
            require(error<.003,"complete authored-relative transform retained: "+bone+" error="+error,group,frame);
            // The complete fist orientation is checked, including rotations around its shaft.
            require(angle(actual.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),
                    expected.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize())<.1,
                    "complete authored-relative rotation retained: "+bone,group,frame);
        }
    }
    private static Map<String,Pose> transportSource(Map<String,Pose> base,float wave) {
        var source=new HashMap<>(PresentationMotion.shiftTorso(PresentationMotion.lean(base,8*wave,-6*wave),
                new PresentationMotion.Balance(3*wave,2*wave,-4*wave)));
        for(String side:SIDES)for(String suffix:List.of("_arm","_forearm","_hand")) {
            String bone=side+suffix;var old=source.get(bone);
            float sign=side.equals("right")?1:-1;
            var rotation=new Quaternionf().rotationXYZ((float)Math.toRadians(sign*3*wave),
                    (float)Math.toRadians(7*wave),(float)Math.toRadians(sign*5*wave)).mul(old.fsQuaternion());
            source.put(bone,new Pose(FsvoxAnimation.fromQuaternion(rotation),old.position(),old.scale()));
        }
        return source;
    }
    /** Exercise the state independently of collision scoring and its selected IK branch. */
    private static void stateTransport(ModelDocument model) {
        String group="stateTransport";GROUPS.put(group,new JsonObject());var state=new SpearMotion.State();
        var base=new PoseLayers.Playback(model.rig(),"spear_idle").sample(.405,.405,8.1,0,0,.25f,55).pose();
        var raw0=model.worldMatrices(base);var actual0=model.worldMatrices(solve(model,base,null,null,null));
        var savedRaw0=copyMatrices(raw0);var savedActual0=copyMatrices(actual0);
        var first=state.prepare(raw0,10,0);
        require(first.actual()==null&&first.continuity()==null,"first frame has no invented history",group,0);
        require(first.rear().distance(palm(raw0,"right"))<.0001,"first grip uses raw authored source",group,0);
        state.commit(actual0,10);
        // Mutating caller-owned matrices after prepare/commit cannot rewrite history.
        raw0.get("right_hand").translate(500,200,-100);actual0.get("right_arm").rotateX(.7f);
        var raw1=model.worldMatrices(transportSource(base,.4f));var expected1=expectedTransport(raw1,savedRaw0,savedActual0);
        var prepared=state.prepare(raw1,11,0);sameTransport(expected1,prepared.continuity(),group,1);
        require(prepared.rear().distance(expectedTorsoRear(raw1,savedRaw0,savedActual0))<.001,
                "rear preserves authored palm stroke plus torso-relative fixed correction",group,1);
        require(angle(prepared.chest(),chest(expected1))<.1,"chest follows authored torso transform",group,1);
        sameTransport(savedActual0,prepared.actual(),group,1);
        require(prepared.authoredMotion()>1,"motion budget recognizes authored displacement",group,1);
        // Returned matrices are also caller-owned: the next prepare still uses the saved actual.
        prepared.actual().get("right_hand").translate(-500,0,0);
        for(int repetition=0;repetition<8;repetition++) {
            var repeated=state.prepare(raw1,11,0);sameTransport(expected1,repeated.continuity(),group,2+repetition);
            require(repeated.rear().distance(expectedTorsoRear(raw1,savedRaw0,savedActual0))<.001,
                    "same-tick commit leaves torso-relative rear goal idempotent",group,2+repetition);
            sameTransport(savedActual0,repeated.actual(),group,2+repetition);
            state.commit(expected1,11);
        }
        var raw2=model.worldMatrices(transportSource(base,-.3f));var expected2=expectedTransport(raw2,savedRaw0,savedActual0);
        var changedSameTick=state.prepare(raw2,11,0);
        sameTransport(expected2,changedSameTick.continuity(),group,10);
        state.commit(expected2,11);
        var raw3=model.worldMatrices(transportSource(base,.7f));var expected3=expectedTransport(raw3,raw2,expected2);
        sameTransport(expected3,state.prepare(raw3,12,0).continuity(),group,11);
        state.commit(expected3,12);
        // Closed authored cycles must retain the original correction rather than shrinking it.
        for(int frame=1;frame<=240;frame++) {
            float wave=(float)Math.sin(2*Math.PI*frame/120.0);var current=model.worldMatrices(transportSource(base,wave));
            var expected=expectedTransport(current,savedRaw0,savedActual0);
            var seed=state.prepare(current,12+frame/3.0,0);
            require(seed.rear().distance(expectedTorsoRear(current,savedRaw0,savedActual0))<.001,
                    "closed authored cycles retain the initial torso-relative grip offset",group,12+frame);
            sameTransport(expected,seed.continuity(),group,12+frame);state.commit(expected,12+frame/3.0);
        }
        GROUPS.get(group).addProperty("closedCycleFrames",240);

        // A 12deg rotation around the local hand Y leaves its palm offset unchanged. A direction-
        // only or translation-only history would miss this authored pronation entirely.
        var wristState=new SpearMotion.State();wristState.prepare(savedRaw0,0,0);wristState.commit(savedActual0,0);
        var handPose=new HashMap<>(base);var old=base.get("right_hand");
        handPose.put("right_hand",new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf(old.fsQuaternion()).rotateY((float)Math.toRadians(12))),
                old.position(),old.scale()));
        var rawWrist=model.worldMatrices(handPose);var wrist=wristState.prepare(rawWrist,1,0);
        require(palm(rawWrist,"right").distance(palm(savedRaw0,"right"))<.001,"axial pronation control has no palm translation",group,260);
        require(wrist.rear().distance(palm(savedActual0,"right"))<.001,
                "pure wrist pronation never creates an un-authored rear-palm orbit",group,260);
        require(wrist.authoredMotion()>4,"budget recognizes full authored fist rotation",group,260);
        sameTransport(expectedTransport(rawWrist,savedRaw0,savedActual0),wrist.continuity(),group,260);
        GROUPS.get(group).addProperty("purePronationAuthoredMotionCm",wrist.authoredMotion());
        GROUPS.get(group).addProperty("purePronationRearStepCm",wrist.rear().distance(palm(savedActual0,"right")));

        // A rigid whole-body turn must rotate the correction with the torso, retaining
        // the same solved palm in the rotated/translated body's coordinate system.
        var bodyState=new SpearMotion.State();bodyState.prepare(savedRaw0,0,0);bodyState.commit(savedActual0,0);
        var rigidDelta=new Matrix4f().translation(3,2,-4).rotateY((float)Math.toRadians(30)).rotateZ((float)Math.toRadians(12));
        var rigidSource=copyMatrices(savedRaw0);rigidSource.replaceAll((id,matrix)->new Matrix4f(rigidDelta).mul(matrix));
        var rigidSeed=bodyState.prepare(rigidSource,1,0);
        require(rigidSeed.rear().distance(rigidDelta.transformPosition(palm(savedActual0,"right")))<.001,
                "rigid body translation and rotation carry the anchored solved palm exactly",group,261);
        sameTransport(expectedTransport(rigidSource,savedRaw0,savedActual0),rigidSeed.continuity(),group,261);

        // Collision optimization can change this frame's solved residual. Rear intent stays
        // tied to the original anchor, while continuity accurately follows the latest actual.
        var anchorState=new SpearMotion.State();anchorState.prepare(savedRaw0,0,0);anchorState.commit(savedActual0,0);
        var movedActual=copyMatrices(expected1);
        movedActual.replaceAll((id,matrix)->new Matrix4f().translation(3,-1,2).mul(matrix));
        anchorState.prepare(raw1,0,0);anchorState.commit(movedActual,0);
        var anchorNext=anchorState.prepare(raw2,1,0);
        var anchored=expectedTorsoRear(raw2,savedRaw0,savedActual0);
        var latest=expectedTransport(raw2,raw1,movedActual);
        require(anchorNext.rear().distance(anchored)<.001,"same-tick residual replacement never shifts the first committed rear anchor",group,270);
        require(anchorNext.rear().distance(expectedTorsoRear(raw2,raw1,movedActual))>1,"rear anchor remains distinct from cumulative optimized residual",group,270);
        sameTransport(latest,anchorNext.continuity(),group,270);
        var carried=palm(movedActual,"right").add(palm(raw2,"right")).sub(palm(raw1,"right"));
        var relaxed=anchorState.prepare(raw2,1,0,0);
        require(relaxed.rear().distance(carried)<.001,"extreme aim carries the latest safe grip plus authored displacement",group,270);
        var middle=anchorState.prepare(raw2,1,0,.5f);
        require(middle.rear().distance(new Vector3f(anchored).add(carried).mul(.5f))<.001,
                "aim-cone transition continuously joins fixed and carried grip goals",group,270);
        sameTransport(latest,middle.continuity(),group,270);
        require(anchorState.prepare(raw2,1,0,1).rear().distance(anchored)<.001,
                "same-frame safety blending never rewrites the forward correction anchor",group,270);
        anchorState.commit(latest,1);
        var returned=anchorState.prepare(savedRaw0,2,0);
        require(returned.rear().distance(palm(savedActual0,"right"))<.001,
                "closed authored loop returns rear anchor despite changed solved residuals",group,271);
        sameTransport(expectedTransport(savedRaw0,raw2,latest),returned.continuity(),group,271);
        anchorState.commit(returned.continuity(),2);
        var reset=anchorState.prepare(raw3,-1,0);
        require(reset.actual()==null&&reset.continuity()==null,"backward render clock clears all prior history",group,272);
        require(reset.rear().distance(palm(raw3,"right"))<.001,"backward render clock clears the authored rear anchor",group,272);
    }

    private record ActionPacket(String action,String phase,int remaining,int windup,int active,int recovery,
                                long sequence,AttackAim path) {}
    private record ActionSample(Map<String,Pose> source,AttackAim.Point aim,String action,String phase,
                                double elapsed,double clipSeconds,double entryElapsed,double entryTicks) {}

    /** The stationary packet script from the live thrust/sweep review, including return to READY. */
    private static ActionPacket actionPacket(int tick,AttackAim.Point fixed) {
        int cycle=Math.max(0,tick)%50;boolean sweep=Math.max(0,tick)/50%2==1;
        long sequence=101000L+Math.max(0,tick)/50;
        if(tick>=0&&cycle<36) {
            int w=sweep?6:5,a=sweep?10:4,r=sweep?12:9;
            if(cycle<w+a+r) {
                String phase=cycle<w?"WINDUP":cycle<w+a?"ACTIVE":"RECOVERY";
                int remaining=cycle<w?w-cycle:cycle<w+a?w+a-cycle:w+a+r-cycle;
                double yaw=new double[]{0,140,-140,0}[tick/50%4],pitch=58*Math.sin((tick-cycle)*Math.PI/80);
                var path=fixed!=null?new AttackAim(List.of(fixed)):!sweep?new AttackAim(List.of(new AttackAim.Point(yaw,pitch,3))):
                        new AttackAim(List.of(new AttackAim.Point(yaw-35,Math.clamp(pitch-12,-80,80),3),
                                new AttackAim.Point(yaw,pitch,3),new AttackAim.Point(yaw+35,Math.clamp(pitch+12,-80,80),3)));
                return new ActionPacket(sweep?"spear_sweep":"spear_thrust",phase,remaining,w,a,r,sequence,path);
            }
        }
        return new ActionPacket("spear_idle","READY",0,0,0,0,sequence,null);
    }

    /** Replay the source-side binding handoff. transitionFrom is the last pre-locomotion pose,
     * exactly as PlayerAnimations stores it; it never contains an IK correction. */
    private static final class ActionPlayback {
        final ModelDocument model;final boolean slim;final AttackAim.Point fixed;
        final PresentationMotion motion=new PresentationMotion();
        ActionPacket packet;PoseLayers.Playback playback;
        PoseLayers.AttackClock clock;
        Map<String,Pose> lastPose,transitionFrom;
        float lastLower=1,transitionLower=1;
        double received,started,entryTicks,aimStarted,aimTicks;
        AttackAim.Point lastAim,aimFrom;
        boolean aimPending;
        int replacements,entryBlendFrames,settledWindupFrames;
        ActionPlayback(ModelDocument model,boolean slim,AttackAim.Point fixed) {
            this.model=model;this.slim=slim;this.fixed=fixed;
        }
        void accept(ActionPacket next,double tick) {
            double elapsed=PoseLayers.attackElapsed(next.phase(),next.remaining(),next.windup(),next.active(),next.recovery());
            boolean replace=packet==null||!PoseLayers.continuesAction(packet.action(),packet.phase(),packet.sequence(),
                    next.action(),next.phase(),next.sequence());
            if(replace) {
                var oldPlayback=playback;playback=new PoseLayers.Playback(model.rig(),next.action());
                if(oldPlayback!=null)playback.inheritMovement(oldPlayback);
                clock=elapsed>=0?new PoseLayers.AttackClock(elapsed,tick,next.windup(),next.active()):null;
                started=tick;replacements++;
                if(lastPose!=null) {
                    transitionFrom=lastPose;transitionLower=lastLower;
                    entryTicks=PoseLayers.transitionTicks(playback.clip(),.12,next.windup(),elapsed>=0);
                    if(elapsed>=0)entryTicks=PoseLayers.entryWindupTicks(entryTicks,elapsed,next.windup());
                }
                if(lastAim!=null&&fixed==null) {
                    aimFrom=lastAim;aimStarted=tick;aimTicks=Math.max(.12,playback.clip().transition())*20;aimPending=true;
                }
            } else if(elapsed>=0) {
                if(clock==null)clock=new PoseLayers.AttackClock(elapsed,tick,next.windup(),next.active());
                else if(next.remaining()!=packet.remaining()||!next.phase().equals(packet.phase()))clock.accept(elapsed,tick);
            } else clock=null;
            packet=next;received=tick;
        }
        ActionSample sample(double tick) {return sample(tick,0,0);}
        ActionSample sample(double tick,float viewYaw,float viewPitch) {
            double authoritative=PoseLayers.attackElapsed(packet.phase(),packet.remaining(),packet.windup(),packet.active(),packet.recovery());
            if(authoritative>=0)authoritative+=Math.max(0,tick-received);
            double elapsed=authoritative<0?-1:clock.sample(tick);
            double breath=motion.breathingSeconds(tick,1);
            double seconds=PoseLayers.breathingAction(packet.action())?breath:
                    PoseLayers.phaseTime(playback.clip(),Math.max(0,elapsed),packet.windup(),packet.active(),packet.recovery());
            var sample=playback.sample(seconds,tick/20,tick,0,-viewYaw,.25f,55);var pose=sample.pose();float lower=sample.lowerBodyWeight();
            motion.observePosition((long)Math.floor(tick),0,0,0,0);
            motion.advance(tick,0,0,0,false,true,sample.movingWeight(),1.4f);
            if(transitionFrom!=null&&entryTicks>0) {
                float weight=PoseLayers.transitionWeight(tick,started,entryTicks);
                if(weight<1) {
                    var blended=new HashMap<String,Pose>();
                    for(var entry:pose.entrySet())blended.put(entry.getKey(),transitionFrom.getOrDefault(entry.getKey(),entry.getValue()).mix(entry.getValue(),weight,true));
                    pose=blended;lower=transitionLower+(lower-transitionLower)*weight;entryBlendFrames++;
                } else transitionFrom=null;
            }
            lastPose=pose;lastLower=lower;
            if(packet.phase().equals("WINDUP")&&tick-started>=entryTicks)settledWindupFrames++;
            float gait=motion.gaitPhase(tick,sample.movingWeight(),lower,.72f),strength=elapsed>=0?.35f:1;
            pose=PresentationMotion.stride(pose,gait,sample.movingWeight(),elapsed>=0?.25f:1);
            pose=motion.applyLean(pose,18,strength,1);var balance=motion.balance(6,strength);
            var hip=PresentationMotion.withAuthoredHipShift(pose,model.rig().parts().get("body").rest().position(),balance);
            pose=PresentationMotion.shiftTorso(pose,balance);var lowerWorld=model.worldMatrices(pose);
            var supported=Locomotion.supported(lowerWorld.get("right_leg"),lowerWorld.get("left_leg"),lower,gait,0,sample.movingWeight(),
                    motion.lateralLean(),motion.forwardLean(),.72f,48,hip,
                    pose.get("body").position().y()-model.rig().parts().get("body").rest().position().y(),motion.gaitLane());
            pose=slim(PresentationMotion.shiftTorso(pose,new PresentationMotion.Balance(0,-supported.pelvisDropCm(),0)),slim);
            AttackAim.Point aim=fixed!=null?fixed:packet.path()!=null&&authoritative>=0?
                    packet.path().sample(PoseLayers.aimFraction(packet.phase(),authoritative,packet.windup(),packet.active())):new AttackAim.Point(viewYaw,viewPitch);
            if(aimFrom!=null) {
                if(packet.phase().equals("ACTIVE")){aimFrom=null;aimPending=false;}
                else {
                    if(aimPending) {
                        aimTicks=packet.phase().equals("READY")?PoseLayers.aimTransitionTicks(aimFrom,aim,aimTicks,240):PoseLayers.aimTransitionTicks(aimFrom,aim,aimTicks);
                        if(packet.phase().equals("WINDUP"))aimTicks=PoseLayers.windupAimTicks(aimTicks,tick-aimStarted,packet.windup()-authoritative);
                        aimPending=false;
                    }
                    float weight=packet.phase().equals("WINDUP")?PoseLayers.windupAimWeight(tick,aimStarted,aimTicks):PoseLayers.transitionWeight(tick,aimStarted,aimTicks);
                    aim=PoseLayers.blendAim(aimFrom,aim,weight);if(weight>=1)aimFrom=null;
                }
            }
            lastAim=aim;
            return new ActionSample(pose,aim,packet.action(),packet.phase(),elapsed,seconds,tick-started,entryTicks);
        }
    }

    private static Vector3f target(AttackAim.Point aim) {
        double yaw=Math.toRadians(aim.yaw()),pitch=Math.toRadians(aim.pitch()),range=100*aim.distance();
        return new Vector3f((float)(-Math.sin(yaw)*Math.cos(pitch)*range),(float)(162-Math.sin(pitch)*range),(float)(Math.cos(yaw)*Math.cos(pitch)*range));
    }
    private static double aimStep(AttackAim.Point previous,AttackAim.Point current) {
        return Math.hypot(AttackAim.wrap(current.yaw()-previous.yaw()),current.pitch()-previous.pitch());
    }
    private static JsonObject actionContext(ActionSample sample,double tick,double aimStep,float degrees,String bone) {
        var value=new JsonObject();value.addProperty("renderTick",tick);value.addProperty("action",sample.action());value.addProperty("phase",sample.phase());
        value.addProperty("sampledElapsedTicks",sample.elapsed());value.addProperty("sampledClipSeconds",sample.clipSeconds());
        value.addProperty("entryBlendEnded",sample.entryElapsed()>=sample.entryTicks());value.addProperty("aimYawDegrees",sample.aim().yaw());
        value.addProperty("aimPitchDegrees",sample.aim().pitch());value.addProperty("aimDistanceMetres",sample.aim().distance());
        value.addProperty("aimStepDegrees",aimStep);value.addProperty("joint",bone);value.addProperty("degrees",degrees);return value;
    }
    /** These checks use actual consecutive world quaternions, including settled same-WINDUP
     * frames where the live capture snapped by 133/151 degrees after entry blending ended. */
    private static final class ActionTrack {
        final ModelDocument model;final String group;final SpearMotion.State state=new SpearMotion.State();
        final Signal rawRear=new Signal();final Map<String,Integer> phaseFrames=new LinkedHashMap<>();
        Map<String,Matrix4f> previous;AttackAim.Point previousAim;
        int geometryFrames,smallAimPairs,fastAimPairs;
        ActionTrack(ModelDocument model,String group) {
            this.model=model;this.group=group;GROUPS.put(group,new JsonObject());
        }
        void sample(ActionSample sample,double tick,int frame) {
            var raw=model.worldMatrices(sample.source());
            var seed=state.prepare(raw,tick,sample.phase().equals("READY")||sample.phase().equals("RECOVERY")?90:0,
                    MOTION_SOLVER==null||!sample.phase().equals("READY")?1:SpearAimPose.authoredFidelity(sample.aim(),0));
            Map<String,Pose> posed;
            try {posed=solve(model,sample.source(),seed,sample.aim());}
            catch(RuntimeException failed) {
                require(false,"attack source solves without errors: "+sample.action()+" "+sample.phase()+" elapsed="+sample.elapsed()+" "+failed,group,frame);
                return;
            }
            var world=model.worldMatrices(posed);state.commit(world,tick);poses++;geometryFrames++;
            phaseFrames.merge(sample.action()+":"+sample.phase(),1,Integer::sum);
            var rear=palm(world,"right");var front=palm(world,"left");var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            float spacing=Math.abs(rear.distance(front)-58),axis=new Vector3f(front).sub(rear).cross(shaft).length();
            maximum(GROUPS.get(group),"gripSpacingErrorCm",spacing);maximum(GROUPS.get(group),"gripAxisErrorCm",axis);
            require(spacing<.02,"attack handoff retains 58cm rigid grip: "+spacing,group,frame);
            require(axis<.02,"attack handoff retains both palms on shaft: "+axis,group,frame);
            require(target(sample.aim()).sub(rear).normalize().dot(shaft)>.99999f,"attack target remains exact",group,frame);
            require(raw.get("body").getTranslation(new Vector3f()).distance(world.get("body").getTranslation(new Vector3f()))<.001,
                    "attack preserves authored torso/root displacement",group,frame);
            float bank=(float)Math.min(18,Math.max(0,Math.abs(sample.aim().pitch())-60)*.9);
            var rawUp=raw.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
            var actualUp=world.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
            float tilt=(float)Math.toDegrees(Math.acos(Math.clamp(rawUp.dot(actualUp),-1,1)));
            require(Math.abs(tilt-bank)<.1,"attack preserves authored tilt plus bounded vertical bank: "+tilt+" expected="+bank,group,frame);
            for(String bone:TRANSPORT_BONES) {
                boolean finite=true;for(float value:world.get(bone).get(new float[16]))finite&=Float.isFinite(value);
                require(finite,"finite actual attack transform: "+bone,group,frame);
            }
            for(String side:SIDES) {
                var elbowQ=posed.get(side+"_forearm").fsQuaternion();
                float bend=(float)Math.IEEEremainder(Math.toDegrees(2*Math.atan2(elbowQ.x,elbowQ.w)),360);
                require(bend>=9.97f&&bend<=135.03f,"attack positive elbow remains 10..135deg: "+side+"="+bend,group,frame);
                require(Math.abs(elbowQ.y)<.0001&&Math.abs(elbowQ.z)<.0001,"attack elbow remains pure flexion: "+side,group,frame);
                var wrist=posed.get(side+"_hand").fsQuaternion();float twist=(float)Math.abs(Math.IEEEremainder(Math.toDegrees(2*Math.atan2(wrist.y,wrist.w)),360));
                require(twist<=150.03f,"attack hard wrist remains <=150deg: "+side+"="+twist,group,frame);
                require(Math.abs(wrist.x)<.0001&&Math.abs(wrist.z)<.0001,"attack wrist remains straight: "+side,group,frame);
                var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());var elbow=world.get(side+"_forearm").getTranslation(new Vector3f());
                require(Math.abs(shoulder.distance(elbow)-37.5)<.01&&Math.abs(elbow.distance(world.get(side+"_hand").getTranslation(new Vector3f()))-25)<.01,
                        "attack physical bone lengths remain unchanged: "+side,group,frame);
            }
            var localHead=chest(world).invert().mul(world.get("head").getUnnormalizedRotation(new Quaternionf()).normalize());
            var sight=localHead.transform(new Vector3f(0,0,1));float neck=(float)Math.abs(Math.toDegrees(Math.atan2(sight.x,sight.z)));
            require(neck<=85.03f,"attack neck yaw remains <=85deg: "+neck,group,frame);
            if(previous!=null) {
                double aimDelta=aimStep(previousAim,sample.aim());boolean small=aimDelta<=2.1;
                if(small)smallAimPairs++;else fastAimPairs++;
                maximum(GROUPS.get(group),"aimStepDegrees",(float)aimDelta);
                maximum(GROUPS.get(group),"rearStepCm",rear.distance(palm(previous,"right")));
                for(String bone:TRANSPORT_BONES) {
                    float degrees=angle(previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize(),world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize());
                    String key=bone+"StepDegrees";
                    if(!GROUPS.get(group).has(key)||degrees>GROUPS.get(group).get(key).getAsFloat()) {
                        GROUPS.get(group).addProperty(key,degrees);GROUPS.get(group).add(bone+"PeakContext",actionContext(sample,tick,aimDelta,degrees,bone));
                    }
                    if(small)maximum(GROUPS.get(group),bone+"SmallAimStepDegrees",degrees);
                    require(degrees<=(small?35:90),"actual attack joint step <="+(small?35:90)+"deg: "+bone+"="+degrees+
                            " aimStep="+aimDelta+" "+sample.action()+" "+sample.phase()+" elapsed="+sample.elapsed()+" entryEnded="+(sample.entryElapsed()>=sample.entryTicks()),group,frame);
                }
            }
            rawRear.add(palm(raw,"right"),rear);previous=world;previousAim=sample.aim();
        }
        void finish(ActionPlayback playback,int frameCount,boolean bridged) {
            var report=GROUPS.get(group);report.addProperty("samples",geometryFrames);report.addProperty("smallAimPairs",smallAimPairs);
            report.addProperty("fastAimPairs",fastAimPairs);report.addProperty("sourceBindingReplacements",playback.replacements);
            report.addProperty("entryBlendFrames",playback.entryBlendFrames);report.addProperty("settledWindupFrames",playback.settledWindupFrames);
            var phases=new JsonObject();phaseFrames.forEach(phases::addProperty);report.add("phaseFrames",phases);
            // These authored hands can be mutually incompatible. Amplitude acceptance stays
            // with the existing legal common-grip/source-loop controls, whose gates are unchanged.
            if(geometryFrames>0)report.add("rawAuthoredRearResponseDiagnostic",rawRear.metrics());
            require(geometryFrames==frameCount,"every handoff source produces a constrained pose",group,frameCount);
            for(String key:List.of("spear_idle:READY","spear_thrust:WINDUP","spear_thrust:ACTIVE","spear_thrust:RECOVERY",
                    "spear_sweep:WINDUP","spear_sweep:ACTIVE","spear_sweep:RECOVERY"))
                require(phaseFrames.getOrDefault(key,0)>0,"script covers authored "+key,group,frameCount);
            require(playback.entryBlendFrames>0&&playback.settledWindupFrames>0,"script covers entry blending and completed same-WINDUP source",group,frameCount);
            require(smallAimPairs>100,"small-aim branch continuity has meaningful coverage",group,frameCount);
            if(bridged)require(fastAimPairs>10,"script covers high-yaw authoritative bridges and active sweep",group,frameCount);
            else require(fastAimPairs==0,"fixed-aim handoffs remain constant aim throughout",group,frameCount);
        }
    }
    private static void actionHandoffs(ModelDocument model,boolean slim,AttackAim.Point fixed) {
        String group=(fixed==null?"attackSmokeAimBridge":fixed.yaw()==0?"attackFixedFront":"attackFixedRear")+(slim?"Slim":"Stock");
        var playback=new ActionPlayback(model,slim,fixed);var track=new ActionTrack(model,group);
        var times=new java.util.TreeSet<Double>();for(int frame=0;frame<=240*3;frame++)times.add(-30+frame/3.0);
        // Preserve the capture's 2.72 -> 3.06 WINDUP samples (133/151 degree snaps)
        // as well as ordinary 60Hz frames. Both occur after the 2-tick source bridge.
        times.add(102.72);times.add(103.06);
        int acceptedTick=Integer.MIN_VALUE,frame=0;
        for(double sceneTick:times) {
            int packetTick=(int)Math.floor(sceneTick+1e-9);
            if(packetTick!=acceptedTick){playback.accept(actionPacket(packetTick,fixed),379.4+packetTick);acceptedTick=packetTick;}
            track.sample(playback.sample(379.4+sceneTick),379.4+sceneTick,frame++);
        }
        GROUPS.get(group).addProperty("source","live review scene 1 script: 50-tick alternating thrust 5/4/9 and sweep 6/10/12, 30-tick READY prelude");
        GROUPS.get(group).addProperty("aimMode",fixed==null?"runtime aim entry/return bridges and authoritative sweep": "constant world target across all authored action sources");
        track.finish(playback,times.size(),fixed==null);
    }
    /** The real READY scene exercises moving authored view yaw and near-vertical bank through
     * the 11-argument history path, which the original nine-argument pitch sweep cannot cover. */
    private static void readyExtremes(ModelDocument model,boolean slim) {
        String group="readyExtremes"+(slim?"Slim":"Stock");var playback=new ActionPlayback(model,slim,null);var track=new ActionTrack(model,group);
        int frames=210*3;
        for(int frame=0;frame<=frames;frame++) {
            int packetTick=frame/3;double tick=169.4+frame/3.0;
            if(frame%3==0)playback.accept(new ActionPacket("spear_idle","READY",0,0,0,0,100000,null),169.4+packetTick);
            // Entity angles update once per native tick; PlayerAnimations sees those actual
            // values on every render, rather than an invented interpolation of the script.
            float yaw=(float)(170*Math.sin(packetTick*Math.PI/100)),pitch=(float)(78*Math.sin(packetTick*Math.PI/70));
            track.sample(playback.sample(tick,yaw,pitch),tick,frame);
        }
        var report=GROUPS.get(group);report.addProperty("samples",track.geometryFrames);report.addProperty("smallAimPairs",track.smallAimPairs);
        report.addProperty("fastAimPairs",track.fastAimPairs);report.addProperty("sourceBindingReplacements",playback.replacements);
        report.addProperty("source","live READY review scene 0: yaw=170*sin(t*pi/100), pitch=78*sin(t*pi/70), native 20Hz entity angles rendered at 60Hz through State");
        require(track.geometryFrames==frames+1,"every READY extrema source produces a constrained pose",group,frames);
        require(track.smallAimPairs>400&&track.fastAimPairs>10,"READY extrema covers stable and native-tick changing aim",group,frames);
    }

    private static String bytecodeSha256(Class<?> type)throws Exception {
        try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if(input==null)throw new IllegalStateException("Missing loaded bytecode for "+type.getName());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    public static void main(String[] arguments)throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        for(boolean slim:List.of(false,true)) {
            translationControl(model,slim);sourceGripControl(model,slim);legalCommonGripControl(model,slim);
            for(String kind:List.of("idle","walk","strafe","sprint"))locomotion(model,slim,kind);
        }
        if(MOTION_SOLVER!=null)stateTransport(model);
        int fixedAimMovementPoses=poses,fixedAimMovementChecks=checks,fixedAimMovementFailures=failures;
        if(MOTION_SOLVER!=null)for(boolean slim:List.of(false,true)) {
            readyExtremes(model,slim);
            actionHandoffs(model,slim,FRONT);
            actionHandoffs(model,slim,new AttackAim.Point(-140,-41.012193308819754,3));
            actionHandoffs(model,slim,null);
        }
        var report=new JsonObject();report.addProperty("scope","60Hz fixed forward target with stock/slim authored idle/run, stride, lean, position observation, balance and supported pelvis; checks actual motion gain and same-frame phase");
        report.addProperty("attackScope","Stock/slim live READY yaw/pitch extrema through State; READY, thrust WINDUP/ACTIVE/RECOVERY, sweep WINDUP/ACTIVE/RECOVERY and READY return with source entry blending; fixed front/rear aim and live-scene high-yaw aim bridges. Actual world joint gates are 35deg for aim steps <=2.1deg and 90deg overall");
        report.addProperty("fixedAimMovementPoses",fixedAimMovementPoses);report.addProperty("fixedAimMovementChecks",fixedAimMovementChecks);report.addProperty("fixedAimMovementFailures",fixedAimMovementFailures);
        report.addProperty("reference","Centred raw authored motion supplies gait phase; legal paired-grip controls already satisfy pole, target, bone lengths and joint stops. Stateless collision search is diagnostic because it can choose a different branch");
        report.addProperty("limitation","Offline movement stack; real-client acceptance must verify actual vanilla render inputs, skin, armor and foot presentation");
        report.addProperty("solverHasHistory",HISTORY_SOLVER!=null);report.addProperty("solverTransportsAuthoredMotion",MOTION_SOLVER!=null);
        report.addProperty("loadedSolverBytecodeSha256",bytecodeSha256(SpearAimPose.class));
        report.addProperty("loadedConstraintsBytecodeSha256",bytecodeSha256(SpearConstraints.class));
        report.addProperty("loadedConstraintSpaceBytecodeSha256",bytecodeSha256(SpearConstraints.Space.class));
        if(MOTION_SOLVER!=null) {
            report.addProperty("loadedMotionBytecodeSha256",bytecodeSha256(SpearMotion.class));
            report.addProperty("loadedMotionStateBytecodeSha256",bytecodeSha256(SpearMotion.State.class));
        }
        report.addProperty("sampleHz",HZ);report.addProperty("warmupFrames",WARMUP);
        report.addProperty("poses",poses);report.addProperty("checks",checks);report.addProperty("failures",failures);report.add("failureExamples",FAILURES);
        var groups=new JsonObject();GROUPS.forEach(groups::add);report.add("groups",groups);
        var path=Path.of(arguments.length>0?arguments[0]:"verification/spear-blend-20261012/movement-continuity.json");
        Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SpearMovementContinuityTest: "+poses+" poses, "+checks+" checks, "+failures+" failures; "+path.toAbsolutePath());
        if(failures>0&&!Boolean.getBoolean("fsrpg.movementDiagnostic"))throw new AssertionError("Movement amplitude/phase or geometry failures: "+failures+"; see diagnostic evidence");
    }
}

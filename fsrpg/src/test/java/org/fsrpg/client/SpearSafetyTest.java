package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Focused spear diagnostics: conservative centimetre capsules against the actual rigid skin boxes. */
public final class SpearSafetyTest {
    private static final List<String> SIDES=List.of("right","left");
    private static final Vector3f BODY_MIN=new Vector3f(-25,0,-12.5f), BODY_MAX=new Vector3f(25,75,12.5f);
    private static final Vector3f HEAD_MIN=new Vector3f(-25,0,-25), HEAD_MAX=new Vector3f(25,50,25);
    private static final Map<String,Metric> METRICS=new LinkedHashMap<>();
    private static final Map<String,JsonObject> GROUPS=new LinkedHashMap<>();
    private static final Map<String,Map<String,Metric>> GROUP_METRICS=new LinkedHashMap<>();
    private static final Map<String,JsonObject> FIXTURES=new LinkedHashMap<>();
    private static final Method PREVIOUS_SOLVER=previousSolver();
    private static final JsonArray FAILURES=new JsonArray();
    private static int poses,errors,unsafePoses;

    private static final class Metric {
        int count,belowZero; double sum,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        JsonObject minimumWhere,maximumWhere;
        void add(double value,JsonObject where) {
            if(!Double.isFinite(value))throw new AssertionError("Nonfinite diagnostic value");
            count++;sum+=value;if(value<-.01)belowZero++;
            if(value<min){min=value;minimumWhere=where;}
            if(value>max){max=value;maximumWhere=where;}
        }
        JsonObject json() {
            var out=new JsonObject();out.addProperty("samples",count);out.addProperty("minimum",min);
            out.addProperty("maximum",max);out.addProperty("mean",sum/count);out.addProperty("belowMinus0_01",belowZero);
            out.add("minimumWhere",minimumWhere);out.add("maximumWhere",maximumWhere);return out;
        }
    }
    private static void record(String name,double value,JsonObject where) {
        METRICS.computeIfAbsent(name,k->new Metric()).add(value,where);
        GROUP_METRICS.computeIfAbsent(where.get("group").getAsString(),k->new LinkedHashMap<>()).computeIfAbsent(name,k->new Metric()).add(value,where);
    }
    private static Method previousSolver() {
        try {return SpearAimPose.class.getDeclaredMethod("apply",ModelDocument.class,Map.class,AttackAim.Point.class,float.class,float.class,Vector3f.class,Quaternionf.class,Map.class,SpearConstraints.Limits.class);}
        catch(NoSuchMethodException missing){return null;}
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Pose> solve(ModelDocument model,Map<String,Pose> source,AttackAim.Point aim,float bodyYaw,Vector3f rear,Quaternionf chest,Map<String,Matrix4f> previous) {
        if(PREVIOUS_SOLVER==null)return SpearAimPose.apply(model,source,aim,bodyYaw,1.62f,rear,chest);
        try {return (Map<String,Pose>)PREVIOUS_SOLVER.invoke(null,model,source,aim,bodyYaw,1.62f,rear,chest,previous,SpearConstraints.Limits.DEFAULT);}
        catch(InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;if(failed.getCause() instanceof Error error)throw error;throw new IllegalStateException(failed.getCause());}
        catch(ReflectiveOperationException failed){throw new IllegalStateException(failed);}
    }
    private static JsonObject matrices(Map<String,Matrix4f> world) {
        var json=new JsonObject();for(var id:List.of("body","head","right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand")) {
            var elements=new JsonArray();for(float value:world.get(id).get(new float[16]))elements.add(value);json.add(id,elements);
        }return json;
    }
    private static void fixture(String id,JsonObject where,ModelDocument model,Map<String,Pose> source,Map<String,Pose> solved,Map<String,Matrix4f> world) {
        var json=new JsonObject();json.add("where",where);json.add("sourceWorldColumnMajor",matrices(model.worldMatrices(source)));json.add("solvedWorldColumnMajor",matrices(world));
        var local=new JsonObject();for(var entry:solved.entrySet()) {
            if(!List.of("body","head","right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand").contains(entry.getKey()))continue;
            var values=new JsonArray();var rotation=entry.getValue().rotation();for(float value:new float[]{rotation.x(),rotation.y(),rotation.z()})values.add(value);local.add(entry.getKey(),values);
        }json.add("solvedLocalRotationXYZDegrees",local);FIXTURES.put(id,json);
    }
    private static JsonObject where(String group,String clip,boolean slim,double time,double yaw,double pitch,double distance) {
        var out=new JsonObject();out.addProperty("group",group);out.addProperty("clip",clip);out.addProperty("slim",slim);
        out.addProperty("seconds",time);out.addProperty("yaw",yaw);out.addProperty("pitch",pitch);out.addProperty("distanceMetres",distance);return out;
    }
    private static void increment(JsonObject out,String key) {out.addProperty(key,out.has(key)?out.get(key).getAsInt()+1:1);}
    private static Vector3f point(Map<String,Matrix4f> world,String id) {return world.get(id).getTranslation(new Vector3f());}
    private static Vector3f palm(Map<String,Matrix4f> world,String side) {return world.get(side+"_hand").transformPosition(new Vector3f(0,-6.25f,0));}
    private static Vector3f inset(Vector3f from,Vector3f to,float centimetres) {return new Vector3f(from).lerp(to,java.lang.Math.min(.95f,centimetres/from.distance(to)));}
    private static double angle(Quaternionf a,Quaternionf b) {
        return java.lang.Math.toDegrees(2*java.lang.Math.acos(java.lang.Math.clamp(java.lang.Math.abs(a.dot(b)),0,1)));
    }
    private static double signedBoxDistance(Vector3f point,Vector3f min,Vector3f max) {
        double dx=java.lang.Math.max(min.x-point.x,point.x-max.x),dy=java.lang.Math.max(min.y-point.y,point.y-max.y),dz=java.lang.Math.max(min.z-point.z,point.z-max.z);
        double outside=java.lang.Math.sqrt(java.lang.Math.max(0,dx)*java.lang.Math.max(0,dx)+java.lang.Math.max(0,dy)*java.lang.Math.max(0,dy)+java.lang.Math.max(0,dz)*java.lang.Math.max(0,dz));
        return outside+java.lang.Math.min(0,java.lang.Math.max(dx,java.lang.Math.max(dy,dz)));
    }
    /** One centimetre sampling bounds a missed narrow segment contact to at most half a centimetre. */
    private static double clearance(Vector3f start,Vector3f end,Matrix4f inverse,Vector3f min,Vector3f max,double radius) {
        var a=inverse.transformPosition(new Vector3f(start));var b=inverse.transformPosition(new Vector3f(end));
        int steps=java.lang.Math.max(1,(int)java.lang.Math.ceil(a.distance(b)));double lowest=Double.POSITIVE_INFINITY;
        for(int i=0;i<=steps;i++)lowest=java.lang.Math.min(lowest,signedBoxDistance(new Vector3f(a).lerp(b,i/(float)steps),min,max)-radius);
        return lowest;
    }
    /** Exact closest distance between two finite segments; parallel and zero-length safe. */
    private static double segmentDistance(Vector3f p1,Vector3f q1,Vector3f p2,Vector3f q2) {
        var d1=new Vector3f(q1).sub(p1);var d2=new Vector3f(q2).sub(p2);var r=new Vector3f(p1).sub(p2);
        double a=d1.dot(d1),e=d2.dot(d2),f=d2.dot(r),s,t;
        if(a<1e-9&&e<1e-9)return p1.distance(p2);
        if(a<1e-9){s=0;t=java.lang.Math.clamp(f/e,0,1);}
        else {double c=d1.dot(r);if(e<1e-9){t=0;s=java.lang.Math.clamp(-c/a,0,1);}
            else {double b=d1.dot(d2),denom=a*e-b*b;s=denom>1e-9?java.lang.Math.clamp((b*f-c*e)/denom,0,1):0;
                t=(b*s+f)/e;if(t<0){t=0;s=java.lang.Math.clamp(-c/a,0,1);}else if(t>1){t=1;s=java.lang.Math.clamp((b-c)/a,0,1);}}}
        return new Vector3f(p1).add(new Vector3f(d1).mul((float)s)).distance(new Vector3f(p2).add(new Vector3f(d2).mul((float)t)));
    }
    private static Map<String,Pose> source(ModelDocument model,String clip,double seconds,boolean slim,int lean) {
        var pose=new HashMap<>(new PoseLayers.Playback(model.rig(),clip).sample(seconds,0,seconds*20,0,0,.25f,55).pose());
        if(slim)for(var side:SIDES){var old=pose.get(side+"_arm");var p=old.position();pose.put(side+"_arm",new Pose(old.rotation(),new V(p.x()+(side.equals("right")?3.125f:-3.125f),p.y(),p.z()),old.scale()));}
        if(lean!=0){var old=pose.get("body");var q=old.fsQuaternion().rotateX((float)java.lang.Math.toRadians(lean>0?10:-7)).rotateZ((float)java.lang.Math.toRadians(lean>0?12:-12));pose.put("body",new Pose(FsvoxAnimation.fromQuaternion(q),old.position(),old.scale()));}
        return pose;
    }
    private static Map<String,Matrix4f> evaluate(ModelDocument model,Map<String,Pose> source,AttackAim.Point aim,float bodyYaw,JsonObject where,Map<String,Matrix4f> previous,Vector3f rearSeed,Quaternionf chestSeed) {
        poses++;String group=where.get("group").getAsString();var summary=GROUPS.computeIfAbsent(group,k->new JsonObject());increment(summary,"poses");
        try {
            var solved=solve(model,source,aim,bodyYaw,rearSeed,chestSeed,previous);var world=model.worldMatrices(solved);
            if(group.equals("rectangle")&&where.get("clip").getAsString().equals("spear_idle")&&!where.get("slim").getAsBoolean()&&where.get("seconds").getAsDouble()==0&&aim.yaw()==0&&aim.pitch()==0)fixture("idleFront",where,model,source,solved,world);
            var rear=palm(world,"right");var front=palm(world,"left");var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
            var start=new Vector3f(rear).sub(new Vector3f(shaft).mul(60));var end=new Vector3f(front).add(new Vector3f(shaft).mul(120));
            var bodyInverse=new Matrix4f(world.get("body")).invert();var headInverse=new Matrix4f(world.get("head")).invert();
            double worst=0;
            for(var volume:List.of("torso","head")) {
                boolean body=volume.equals("torso");var inv=body?bodyInverse:headInverse;var min=body?BODY_MIN:HEAD_MIN;var max=body?BODY_MAX:HEAD_MAX;
                double shaftClearance=clearance(start,end,inv,min,max,2);record("shaftTo"+volume+"Cm",shaftClearance,where);worst=java.lang.Math.min(worst,shaftClearance);
                if(METRICS.get("shaftTo"+volume+"Cm").min==shaftClearance)fixture("worstShaftTo"+volume,where,model,source,solved,world);
                for(var side:SIDES) {
                    var shoulder=point(world,side+"_arm");var elbow=point(world,side+"_forearm");var hand=palm(world,side);
                    double upper=clearance(inset(shoulder,elbow,12),elbow,inv,min,max,10),lower=clearance(elbow,hand,inv,min,max,8.5);
                    record(side+"UpperTo"+volume+"Cm",upper,where);record(side+"LowerTo"+volume+"Cm",lower,where);
                    if(body&&METRICS.get(side+"LowerTo"+volume+"Cm").min==lower)fixture("worst"+side+"ForearmTotorso",where,model,source,solved,world);
                    worst=java.lang.Math.min(worst,java.lang.Math.min(upper,lower));
                }
            }
            for(var side:SIDES) {
                var shoulder=point(world,side+"_arm");var elbow=point(world,side+"_forearm");var hand=palm(world,side);
                double upperShaft=segmentDistance(start,end,inset(shoulder,elbow,12),elbow)-12;
                double lowerShaft=segmentDistance(start,end,elbow,inset(hand,elbow,13))-10.5;
                record("shaftTo"+side+"UpperCm",upperShaft,where);record("shaftTo"+side+"LowerCm",lowerShaft,where);
                worst=java.lang.Math.min(worst,java.lang.Math.min(upperShaft,lowerShaft));
                var upperAxis=bodyInverse.transformDirection(new Vector3f(elbow).sub(shoulder)).normalize();
                record(side+"ShoulderElevationDegrees",java.lang.Math.toDegrees(java.lang.Math.acos(java.lang.Math.clamp(-upperAxis.y,-1,1))),where);
                record(side+"UpperInwardDirection",upperAxis.x*(side.equals("right")?1:-1),where);
                record(side+"UpperBackwardDirection",-upperAxis.z,where);
                var hinge=solved.get(side+"_forearm").fsQuaternion();double bend=java.lang.Math.IEEEremainder(java.lang.Math.toDegrees(2*java.lang.Math.atan2(hinge.x,hinge.w)),360);
                record(side+"ElbowSignedDegrees",bend,where);record(side+"ElbowAbsoluteDegrees",java.lang.Math.abs(bend),where);
                var wrist=solved.get(side+"_hand").fsQuaternion();record(side+"WristAbsolutePronationDegrees",java.lang.Math.abs(java.lang.Math.IEEEremainder(java.lang.Math.toDegrees(2*java.lang.Math.atan2(wrist.y,wrist.w)),360)),where);
                if(bend>1)increment(summary,side+"PositiveElbowPoses");
                if(previous!=null)for(var suffix:List.of("_arm","_forearm","_hand"))record(side+suffix+"StepDegrees",angle(previous.get(side+suffix).getUnnormalizedRotation(new Quaternionf()).normalize(),world.get(side+suffix).getUnnormalizedRotation(new Quaternionf()).normalize()),where);
            }
            if(worst<-.01){unsafePoses++;increment(summary,"capsuleOverlapPoses");}
            record("worstClearanceCm",worst,where);
            record("gripSpacingErrorCm",java.lang.Math.abs(rear.distance(front)-58),where);
            record("gripShaftErrorCm",new Vector3f(front).sub(rear).cross(shaft).length(),where);
            double yaw=java.lang.Math.toRadians(aim.yaw()-bodyYaw),pitch=java.lang.Math.toRadians(aim.pitch()),range=aim.distance()*100;
            var target=new Vector3f((float)(-java.lang.Math.sin(yaw)*java.lang.Math.cos(pitch)*range),(float)(162-java.lang.Math.sin(pitch)*range),(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch)*range));
            record("targetDirectionErrorDegrees",java.lang.Math.toDegrees(java.lang.Math.acos(java.lang.Math.clamp(new Vector3f(target).sub(rear).normalize().dot(shaft),-1,1))),where);
            if(previous!=null)record("bodyStepDegrees",angle(previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize()),where);
            return world;
        } catch(RuntimeException error) {errors++;increment(summary,"solverErrors");var failure=where.deepCopy();failure.addProperty("error",error.toString());if(FAILURES.size()<20)FAILURES.add(failure);return null;}
    }
    private static void staticGrid(ModelDocument model) {
        for(boolean slim:List.of(false,true))for(String clip:List.of("spear_idle","spear_thrust","spear_sweep")) {
            double duration=model.rig().clips().get(clip).duration();
            for(double seconds:new double[]{0,.24,.405,.47,.72})for(int yaw:new int[]{-55,-33,0,33,55})for(int pitch:new int[]{-40,0,40}) {
                double time=java.lang.Math.min(seconds,duration-.00001);var aim=new AttackAim.Point(yaw,pitch,3);
                evaluate(model,source(model,clip,time,slim,0),aim,0,where("rectangle",clip,slim,time,yaw,pitch,3),null,null,null);
            }
            for(int lean:new int[]{-1,1})for(int yaw:new int[]{-55,0,55})for(int pitch:new int[]{-40,0,40}) {
                var where=where("movementLean",clip,slim,.405,yaw,pitch,3);where.addProperty("leanSign",lean);
                evaluate(model,source(model,clip,.405,slim,lean),new AttackAim.Point(yaw,pitch,3),0,where,null,null,null);
            }
            for(int yaw:new int[]{-150,-100,0,100,150})for(int pitch:new int[]{-80,-60,60,80}) {
                evaluate(model,source(model,clip,.405,slim,0),new AttackAim.Point(yaw,pitch,60),0,where("extremeAim",clip,slim,.405,yaw,pitch,60),null,null,null);
            }
        }
    }
    private static void connectedTracks(ModelDocument model) {
        for(boolean slim:List.of(false,true))for(String clip:List.of("spear_idle","spear_thrust","spear_sweep"))for(int pitch:new int[]{-40,0,40}) {
            Map<String,Matrix4f> previous=null;Vector3f oldSourceRear=null;Quaternionf oldSourceChest=null;
            for(int yaw=-55;yaw<=55;yaw+=2) {
                var source=source(model,clip,.405,slim,0);var raw=model.worldMatrices(source);var sourceRear=palm(raw,"right");var sourceChest=raw.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
                var seed=previous==null?null:PoseLayers.gripSeed(sourceRear,oldSourceRear,palm(previous,"right"));
                var chest=previous==null?null:PoseLayers.chestSeed(sourceChest,oldSourceChest,previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),1/60.0,0);
                previous=evaluate(model,source,new AttackAim.Point(yaw,pitch,3),0,where("connectedYaw",clip,slim,.405,yaw,pitch,3),previous,seed,chest);
                oldSourceRear=sourceRear;oldSourceChest=sourceChest;
            }
        }
        for(boolean slim:List.of(false,true))for(int sign:new int[]{-1,1}) {
            Map<String,Matrix4f> previous=null;Vector3f oldSourceRear=null;Quaternionf oldSourceChest=null;
            double duration=model.rig().clips().get("spear_sweep").duration();
            for(int frame=0;frame<=60;frame++) {
                double seconds=duration*frame/60;var source=source(model,"spear_sweep",seconds,slim,0);var raw=model.worldMatrices(source);var sourceRear=palm(raw,"right");var sourceChest=raw.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
                var seed=previous==null?null:PoseLayers.gripSeed(sourceRear,oldSourceRear,palm(previous,"right"));
                var chest=previous==null?null:PoseLayers.chestSeed(sourceChest,oldSourceChest,previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),duration/60,0);
                previous=evaluate(model,source,new AttackAim.Point(sign*144.765769,-8.434355,60.18877),sign*50,where("rearwardRecovery","spear_sweep",slim,seconds,sign*144.765769,-8.434355,60.18877),previous,seed,chest);
                oldSourceRear=sourceRear;oldSourceChest=sourceChest;
            }
        }
    }
    public static void main(String[] arguments)throws Exception {
        var modelPath=Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel");
        var model=ModelLoader.load(modelPath);
        staticGrid(model);connectedTracks(model);
        var report=new JsonObject();report.addProperty("scope","Only spear solved grips, torso/head/capsule clearance, joint limits and connected aim/recovery");
        report.addProperty("poses",poses);report.addProperty("solverErrors",errors);report.addProperty("capsuleOverlapPoses",unsafePoses);
        report.addProperty("usesPreviousWorldSolver",PREVIOUS_SOLVER!=null);
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        report.addProperty("modelSha256",java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(modelPath))));
        try(var bytecode=SpearAimPose.class.getResourceAsStream("SpearAimPose.class")) {
            report.addProperty("loadedSolverBytecodeSha256",java.util.HexFormat.of().formatHex(digest.digest(bytecode.readAllBytes())));
        }
        report.addProperty("units","centimetres/degrees; conservative capsule contacts are candidates for visual inspection, not exact skin mesh intersections");
        report.addProperty("geometry","torso [-25,0,-12.5]..[25,75,12.5]; head [-25,0,-25]..[25,50,25]; upper radius 10 / lower radius 8.5; upper first 12 cm excluded; shaft radius 2 from rear -60 to front +120; shaft/forearm excludes final 13 cm expected grip contact");
        var metrics=new JsonObject();for(var entry:METRICS.entrySet())metrics.add(entry.getKey(),entry.getValue().json());report.add("metrics",metrics);
        var groups=new JsonObject();GROUPS.forEach((key,summary)->{var groupMetrics=new JsonObject();GROUP_METRICS.get(key).forEach((name,metric)->groupMetrics.add(name,metric.json()));summary.add("metrics",groupMetrics);groups.add(key,summary);});report.add("groups",groups);report.add("solverFailureExamples",FAILURES);
        var fixtures=new JsonObject();FIXTURES.forEach(fixtures::add);report.add("boneFixtures",fixtures);
        Path output=Path.of(arguments.length>0?arguments[0]:"verification/spear-solver-20261012/diagnostics.json");Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SpearSafetyTest: poses="+poses+" errors="+errors+" conservativeOverlapPoses="+unsafePoses+" output="+output.toAbsolutePath());
        for(var name:List.of("shaftTotorsoCm","shaftToheadCm","rightUpperTotorsoCm","leftUpperTotorsoCm","rightLowerTotorsoCm","leftLowerTotorsoCm","rightElbowSignedDegrees","leftElbowSignedDegrees","right_handStepDegrees","left_handStepDegrees")) {
            var metric=METRICS.get(name);System.out.printf(java.util.Locale.ROOT,"  %-27s min=%8.3f max=%8.3f negative=%d/%d%n",name,metric.min,metric.max,metric.belowZero,metric.count);
        }
        if(errors>0)throw new AssertionError("Spear solver raised "+errors+" exceptions; see diagnostic evidence");
    }
}

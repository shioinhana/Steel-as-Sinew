package org.fsrpg.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Aim a pole crossing both fists, retaining the authored weight shift and straight wrists. */
public final class SpearAimPose {
    private static final float UPPER=37.5f, PALM_LINK=31.25f, GRIP_SPACING=58;
    private static final float CONTINUOUS_PEAK=89, PEAK_TIE=.5f;
    private static final List<String> SIDES=List.of("right","left");
    private SpearAimPose() {}
    /** Read-only acceptance hook for the exact final rendered complete pole. */
    public static boolean hasClearShaft(Map<String,Matrix4f> world) {
        var rear=world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
        var axis=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
        return new SpearConstraints.Space(world,SpearConstraints.Limits.DEFAULT).shaftClear(rear,axis);
    }
    public static float authoredFidelity(AttackAim.Point aim,float bodyYaw) {
        double yaw=java.lang.Math.toRadians(AttackAim.wrap(aim.yaw()-bodyYaw)),pitch=java.lang.Math.toRadians(aim.pitch());
        float value=java.lang.Math.clamp(((float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch))-.17364818f)/(.6427876f-.17364818f),0,1);
        return value*value*(3-2*value);
    }
    public static Map<String,Pose> apply(ModelDocument model,Map<String,Pose> pose,AttackAim.Point aim,float bodyYaw,float eyeHeight) {
        return apply(model,pose,aim,bodyYaw,eyeHeight,null);
    }
    /** A prior actual grip plus the current authored displacement preserves a connected aim branch. */
    public static Map<String,Pose> apply(ModelDocument model,Map<String,Pose> pose,AttackAim.Point aim,float bodyYaw,float eyeHeight,Vector3f gripSeed) {
        return apply(model,pose,aim,bodyYaw,eyeHeight,gripSeed,null);
    }
    /** Transported actual chest orientation keeps the same legal side of a rearward target. */
    public static Map<String,Pose> apply(ModelDocument model,Map<String,Pose> pose,AttackAim.Point aim,float bodyYaw,float eyeHeight,Vector3f gripSeed,Quaternionf chestSeed) {
        return apply(model,pose,aim,bodyYaw,eyeHeight,gripSeed,chestSeed,null,SpearConstraints.Limits.DEFAULT);
    }
    static Map<String,Pose> apply(ModelDocument model,Map<String,Pose> pose,AttackAim.Point aim,float bodyYaw,float eyeHeight,
                                 Vector3f gripSeed,Quaternionf chestSeed,Map<String,Matrix4f> previous,SpearConstraints.Limits limits) {
        // Generic IK also supports close/rear targets used by detached tools and
        // historical traces. The live action path supplies its cursor-field stop below.
        return applyCore(model,pose,aim,bodyYaw,eyeHeight,gripSeed,chestSeed,previous,previous,0,limits,true,java.lang.Math.PI);
    }
    /** Authored movement transports the soft branch reference; real history guards actual discontinuities. */
    static Map<String,Pose> apply(ModelDocument model,Map<String,Pose> pose,AttackAim.Point aim,float bodyYaw,float eyeHeight,
                                 Vector3f gripSeed,Quaternionf chestSeed,Map<String,Matrix4f> previous,
                                 Map<String,Matrix4f> continuity,float authoredMotion,SpearConstraints.Limits limits) {
        return applyCore(model,pose,aim,bodyYaw,eyeHeight,gripSeed,chestSeed,previous,continuity,authoredMotion,limits,true,chestHeadingLimit(aim,bodyYaw));
    }
    private static Map<String,Pose> applyCore(ModelDocument model,Map<String,Pose> pose,AttackAim.Point aim,float bodyYaw,float eyeHeight,
                                 Vector3f gripSeed,Quaternionf chestSeed,Map<String,Matrix4f> previous,
                                 Map<String,Matrix4f> continuity,float authoredMotion,SpearConstraints.Limits limits,boolean comfort,double chestHeadingLimit) {
        if(!pose.containsKey("body"))return pose;
        var result=new HashMap<>(pose);
        var world=model.worldMatrices(result);
        if(!world.containsKey("right_hand"))return pose;
        var rear=world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
        var desiredRear=gripSeed==null?new Vector3f(rear):new Vector3f(gripSeed);
        rear.set(desiredRear);
        double yaw=java.lang.Math.toRadians(AttackAim.wrap(aim.yaw()-bodyYaw));
        double pitch=java.lang.Math.toRadians(aim.pitch()),range=aim.distance()*100;
        var target=new Vector3f((float)(-java.lang.Math.sin(yaw)*java.lang.Math.cos(pitch)*range),
                (float)(eyeHeight*100-java.lang.Math.sin(pitch)*range),(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pitch)*range));
        var shaft=new Vector3f();
        var sourceBody=pose.get("body").fsQuaternion().normalize();
        var sourceHead=pose.containsKey("head")?pose.get("head").fsQuaternion().normalize():null;
        // Preserve authored strokes in the character's forward aiming cone.
        // Outside that cone, let the source stroke yield to constrained aiming.
        // Body translation, gait, phase and hard grip/angle limits remain intact.
        // A solved chest may already have turned to make a legal grip. It must not
        // classify that same forward intent as an extreme target on the next frame.
        float confidence=authoredFidelity(aim,bodyYaw);
        // At nearly vertical aim the upright shoulder line has too little projection
        // on the shaft for two transverse wrists. Raise the leading shoulder gradually
        // instead of reversing an elbow or forcing the rear wrist through 150 degrees.
        float bank=(float)java.lang.Math.toRadians(java.lang.Math.copySign(
                java.lang.Math.min(limits.verticalBankDegrees(),java.lang.Math.max(0,java.lang.Math.abs(aim.pitch())-(limits.tucked()?20:60))*.9),limits.tucked()?aim.pitch():-aim.pitch()));
        var sourceUp=new Quaternionf(sourceBody).transform(new Vector3f(0,1,0));
        double previousChestYaw=Double.NaN;
        if(previous!=null) {
            var relative=new Quaternionf(sourceBody).invert().mul(previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize());
            previousChestYaw=wrap(2*java.lang.Math.atan2(relative.y,relative.w));
        }
        double chestYaw=0;
        if(chestSeed!=null) {
            var relative=new Quaternionf(sourceBody).invert().mul(chestSeed).normalize();
            chestYaw=wrap(2*java.lang.Math.atan2(relative.y,relative.w));
        }
        double headingCenter=limits.tucked()?-yaw:0;
        chestYaw=forwardChestYaw(sourceBody,chestYaw,chestHeadingLimit,headingCenter);
        if(chestSeed!=null || chestYaw!=0 || bank!=0) {
            put(result,"body",new Quaternionf(sourceBody).rotateY((float)chestYaw).rotateZ(bank));
            if(sourceHead!=null)head(result,sourceHead,chestYaw,bank,previous,limits);
            world=model.worldMatrices(result);
        }
        // Local Y rotates about the already leaning chest's up axis. Pitch, bank, root
        // motion and breathing survive; the head counter-turn keeps its authored sightline.
        for(int pass=0;pass<128;pass++) {
            shaft.set(target).sub(rear).normalize();
            float adjustment=comfort?(limits.tucked()?underarmChestAdjustment(world,shaft,sourceUp,chestYaw,previousChestYaw):chestAdjustment(world,shaft,sourceUp,chestYaw,previousChestYaw)):chestTurn(world,shaft,sourceUp,30,false);
            adjustment=(float)wrap(forwardChestYaw(sourceBody,chestYaw+adjustment,chestHeadingLimit,headingCenter)-chestYaw);
            if(java.lang.Math.abs(adjustment)>.00001f) {
                chestYaw+=adjustment;
                put(result,"body",new Quaternionf(sourceBody).rotateY((float)chestYaw).rotateZ(bank));
                if(sourceHead!=null)head(result,sourceHead,chestYaw,bank,previous,limits);
                world=model.worldMatrices(result);
            }
            // Retain the branch chosen on the first projection. Re-projecting a fixed
            // unreachable source goal can alternate equally near sides of an inner ring.
            var projected=closedRear(world,shaft,rear,limits);
            float error=projected.distance(rear);
            // A close-to-shoulder inner annulus magnifies finite-target parallax. Damping
            // keeps the coupled shaft/rear fixed point from alternating across that ring.
            rear.lerp(projected,.2f);
            if(error<.00005f && java.lang.Math.abs(adjustment)<.00001f)break;
        }
        boolean rankActual=continuity!=previous && previous!=null;
        if(rankActual)rear.set(finiteRear(world,target,rear,limits));
        shaft.set(target).sub(rear).normalize();
        var bestClosedScore=gripScore(world,shaft,rear,desiredRear,
                new SpearConstraints.Space(world,limits,continuity,previous,desiredRear,shaft,continuity!=previous,authoredMotion,target,confidence),continuity,previous,limits);
        var bestRear=new Vector3f(rear);
        var bestPose=new HashMap<>(result);
        double bestChestYaw=chestYaw;
        // Search the common rigid grip, rather than clamping solved joints independently.
        // Recompute parallax after every accepted correction; both palms stay on the pole.
        for(int safety=0;safety<3;safety++) {
            shaft.set(target).sub(rear).normalize();
            var safe=safeRear(world,shaft,rear,desiredRear,continuity,previous,continuity!=previous,authoredMotion,target,confidence,limits);
            if(safe.distanceSquared(rear)<.0001f)break;
            rear.set(safe);
            for(int pass=0;pass<96;pass++) {
                shaft.set(target).sub(rear).normalize();
                float adjustment=comfort?(limits.tucked()?underarmChestAdjustment(world,shaft,sourceUp,chestYaw,previousChestYaw):chestAdjustment(world,shaft,sourceUp,chestYaw,previousChestYaw)):chestTurn(world,shaft,sourceUp,30,false);
                adjustment=(float)wrap(forwardChestYaw(sourceBody,chestYaw+adjustment,chestHeadingLimit,headingCenter)-chestYaw);
                if(java.lang.Math.abs(adjustment)>.00001f) {
                    chestYaw+=adjustment;
                    put(result,"body",new Quaternionf(sourceBody).rotateY((float)chestYaw).rotateZ(bank));
                    if(sourceHead!=null)head(result,sourceHead,chestYaw,bank,previous,limits);
                    world=model.worldMatrices(result);
                }
                var projected=closedRear(world,shaft,rear,limits);
                float error=projected.distance(rear);
                rear.lerp(projected,.3f);
                if(error<.00005f && java.lang.Math.abs(adjustment)<.00001f)break;
            }
            if(rankActual)rear.set(finiteRear(world,target,rear,limits));
            shaft.set(target).sub(rear).normalize();
            var score=gripScore(world,shaft,rear,desiredRear,
                    new SpearConstraints.Space(world,limits,continuity,previous,desiredRear,shaft,continuity!=previous,authoredMotion,target,confidence),continuity,previous,limits);
            if(betterGrip(score,bestClosedScore,rankActual,0)){bestClosedScore=score;bestRear.set(rear);bestPose=new HashMap<>(result);bestChestYaw=chestYaw;}
        }
        result=bestPose;rear.set(bestRear);chestYaw=bestChestYaw;
        if(sourceHead!=null)head(result,sourceHead,chestYaw,bank,previous,limits);
        world=model.worldMatrices(result);
        // Safety candidates use a fixed shaft during their local search. Finish the
        // finite-target fixed point before writing exact links; a low-cost candidate
        // is not a closed grip until this residual has converged.
        rear.set(finiteRear(world,target,rear,limits));
        shaft.set(target).sub(rear).normalize();
        var parent=world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
        var space=new SpearConstraints.Space(world,limits,continuity,previous,desiredRear,shaft,continuity!=previous,authoredMotion,target,confidence);
        var selectedScore=gripScore(world,shaft,rear,desiredRear,space,continuity,previous,limits);
        if(continuity!=previous && previous!=null) {
            // Project the actual grip with its own finite-target axis. Projecting it
            // on a distant trial grip's axis can lose a connected inner-ring branch.
            // Compare only converged candidates, including the final search result.
            var prior=previous.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
            var warm=finiteRear(world,target,prior,limits);
            var warmShaft=new Vector3f(target).sub(warm).normalize();
            var warmScore=gripScore(world,warmShaft,warm,desiredRear,space,continuity,previous,limits);
            if(betterGrip(warmScore,selectedScore,true,0)) {rear.set(warm);shaft.set(warmShaft);selectedScore=warmScore;}
            if(selectedScore.peak()>space.motionBudget+2) {
                rear.set(connectedRear(world,target,rear,prior,desiredRear,space,continuity,previous,limits));
                shaft.set(target).sub(rear).normalize();
            }
        }
        selectedScore=gripScore(world,shaft,rear,desiredRear,space,continuity,previous,limits);
        if(limits.tucked()&&(!selectedScore.shaftClear()||!selectedScore.rearTucked()
                ||(continuity!=previous&&previous!=null&&selectedScore.peak()>space.motionBudget+1))) {
            var rescued=underarmRescue(model,result,sourceBody,sourceHead,target,desiredRear,chestYaw,bank,
                    rear,selectedScore,continuity,previous,authoredMotion,confidence,chestHeadingLimit,limits);
            result=new HashMap<>(rescued.pose());rear.set(rescued.rear());chestYaw=rescued.yaw();bank=rescued.bank();
            world=model.worldMatrices(result);shaft.set(target).sub(rear).normalize();
            space=new SpearConstraints.Space(world,limits,continuity,previous,desiredRear,shaft,continuity!=previous,authoredMotion,target,confidence);
        }
        parent=world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
        var selectedArms=new Arm[SIDES.size()];
        selectedScore=gripScore(world,shaft,rear,desiredRear,space,continuity,previous,limits,selectedArms);
        for(int side=0;side<SIDES.size();side++)solve(result,SIDES.get(side),selectedArms[side],shaft,parent);
        if(comfort && continuity!=previous && previous!=null) {
            float deep=0;
            for(int sideIndex=0;sideIndex<SIDES.size();sideIndex++) {
                String side=SIDES.get(sideIndex);
                var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
                var palm=new Vector3f(rear).fma(side.equals("right")?0:limits.gripSpacingCm(),shaft);
                var elbow=selectedArms[sideIndex].elbow();
                deep+=space.deepArmContact(shoulder,elbow,palm);
            }
            if(deep>50 || space.shaft(rear,shaft)>50 || selectedScore.peak()>space.motionBudget+2) {
                // Comfort yaw can trap the arms on a penetrating ring. Keep the
                // previous chest side as a second complete pose, turning it only
                // as far as exact reach requires; compare the entire closed grip.
                var alternateChest=new Quaternionf(sourceBody).rotateY((float)previousChestYaw);
                var alternate=applyCore(model,pose,aim,bodyYaw,eyeHeight,gripSeed,alternateChest,previous,continuity,authoredMotion,limits,false,chestHeadingLimit);
                var alternateWorld=model.worldMatrices(alternate);
                var alternateRear=alternateWorld.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
                var alternateShaft=new Vector3f(target).sub(alternateRear).normalize();
                var alternateSpace=new SpearConstraints.Space(alternateWorld,limits,continuity,previous,desiredRear,alternateShaft,true,authoredMotion,target,confidence);
                var alternateScore=gripScore(alternateWorld,alternateShaft,alternateRear,desiredRear,alternateSpace,continuity,previous,limits);
                if(betterGrip(alternateScore,selectedScore,true,0))return alternate;
            }
        }
        return result;
    }
    private record UnderarmRescue(Map<String,Pose> pose,Vector3f rear,double yaw,float bank) {}
    /** Search chest and the complete rigid grip together; a penetrating fixed chest is not a valid branch. */
    private static UnderarmRescue underarmRescue(ModelDocument model,Map<String,Pose> pose,Quaternionf sourceBody,Quaternionf sourceHead,
            Vector3f target,Vector3f authored,double yaw,float bank,Vector3f rear,GripScore initial,
            Map<String,Matrix4f> continuity,Map<String,Matrix4f> previous,float authoredMotion,float confidence,double headingLimit,SpearConstraints.Limits limits) {
        var bestPose=new HashMap<>(pose);var bestRear=new Vector3f(rear);double bestYaw=yaw;float bestBank=bank;var best=initial;
        boolean actualHistory=continuity!=previous&&previous!=null;
        float priorOffset=0;
        if(previous!=null) {
            var relative=new Quaternionf(sourceBody).invert().mul(previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize());
            priorOffset=(float)java.lang.Math.toDegrees(wrap(2*java.lang.Math.atan2(relative.y,relative.w)-yaw));
        }
        search: for(float trialBank:new float[]{bank,bank*.85f,bank*.6f,bank*.3f,0}) {
        for(float offset:new float[]{priorOffset,0,-3,3,-6,6,-12,12,-25,25,-45,45,-55,55}) {
            double trialYaw=forwardChestYaw(sourceBody,yaw+java.lang.Math.toRadians(offset),headingLimit,java.lang.Math.atan2(target.x,target.z));
            var trialPose=new HashMap<>(pose);
            put(trialPose,"body",new Quaternionf(sourceBody).rotateY((float)trialYaw).rotateZ(trialBank));
            if(sourceHead!=null)head(trialPose,sourceHead,trialYaw,trialBank,previous,limits);
            var trialWorld=model.worldMatrices(trialPose);
            var trialSpace=new SpearConstraints.Space(trialWorld,limits,continuity,previous,authored,new Vector3f(target).sub(rear).normalize(),actualHistory,authoredMotion,target,confidence);
            var seeds=new java.util.ArrayList<Vector3f>();
            if(previous!=null) {
                var oldRear=previous.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));seeds.add(oldRear);
                for(float step:new float[]{1,2,4,8})for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
                    var local=new Vector3f(oldRear);local.setComponent(axis,local.get(axis)+sign*step);seeds.add(local);
                }
            }
            seeds.add(new Vector3f(rear));seeds.add(new Vector3f(authored));
            for(float x:new float[]{-60,-50,-70,-40})for(float y:new float[]{60,50,70,40})for(float z:new float[]{-12,0,16,32,48})
                seeds.add(trialWorld.get("body").transformPosition(new Vector3f(x,y,z)));
            for(var seed:seeds) {
                var candidate=trialRear(trialWorld,target,new Vector3f(target).sub(seed).normalize(),seed,true,limits);
                if(candidate==null)continue;
                var axis=new Vector3f(target).sub(candidate).normalize();
                if(!trialSpace.shaftClear(candidate,axis))continue;
                var score=gripScore(trialWorld,axis,candidate,authored,trialSpace,continuity,previous,limits);
                if(betterGrip(score,best,actualHistory,0)) {best=score;bestRear.set(candidate);bestPose=new HashMap<>(trialPose);bestYaw=trialYaw;bestBank=trialBank;}
                // Once a connected, clear rear-elbow branch is available, there
                // is no reason to evaluate the remaining distant seed grid.
                if(actualHistory&&best.shaftClear()&&(best.rearTucked()||java.lang.Math.abs(axis.y)>.34f)&&best.peak()<=trialSpace.motionBudget+1)break search;
            }
            if(best.shaftClear()&&(!actualHistory?best.rearTucked():best.peak()<=trialSpace.motionBudget+2))break search;
        }
        }
        return new UnderarmRescue(bestPose,bestRear,bestYaw,bestBank);
    }
    private static Vector3f finiteRear(Map<String,Matrix4f> world,Vector3f target,Vector3f seed,SpearConstraints.Limits limits) {
        var rear=new Vector3f(seed);
        float damping=.8f,previousError=Float.POSITIVE_INFINITY;
        for(int pass=0;pass<256;pass++) {
            var shaft=new Vector3f(target).sub(rear).normalize();
            var projected=closedRear(world,shaft,rear,limits);
            float error=projected.distanceSquared(rear);
            if(error<.0000000025f)break;
            // Most candidate grips converge directly. Only inner-ring oscillation
            // needs the slow damping; applying it to every candidate stalls frames
            // and can skip most of a short windup between two render samples.
            if(error>previousError*.95f)damping=java.lang.Math.max(.15f,damping*.5f);
            else if(error<previousError*.5f)damping=java.lang.Math.min(.8f,damping*1.25f);
            rear.lerp(projected,damping);previousError=error;
        }
        return rear;
    }
    private static Vector3f trialRear(Map<String,Matrix4f> world,Vector3f target,Vector3f shaft,Vector3f seed,
                                      boolean transported,SpearConstraints.Limits limits) {
        try {
            var rear=transported?finiteRear(world,target,seed,limits):closedRear(world,shaft,seed,limits);
            var axis=transported?new Vector3f(target).sub(rear).normalize():shaft;
            return transported && closedRear(world,axis,rear,limits).distanceSquared(rear)>.00000001f?null:rear;
        } catch(IllegalArgumentException unreachableTrial) {return null;}
    }
    private static float peakStep(Map<String,Matrix4f> world,Vector3f shaft,Vector3f rear,SpearConstraints.Space space,
                                  Map<String,Matrix4f> continuity,SpearConstraints.Limits limits) {
        float peak=0;
        for(String side:SIDES) {
            var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
            var palm=new Vector3f(rear).fma(side.equals("right")?0:limits.gripSpacingCm(),shaft);
            peak=java.lang.Math.max(peak,arm(side,shoulder,palm,shaft,space,continuity,limits).peakStep());
        }
        return peak;
    }
    /** Rescue a connected finite-target ring when a fixed-axis search discarded it. */
    private static Vector3f connectedRear(Map<String,Matrix4f> world,Vector3f target,Vector3f current,Vector3f prior,
                                         Vector3f authored,SpearConstraints.Space space,Map<String,Matrix4f> continuity,
                                         Map<String,Matrix4f> actual,SpearConstraints.Limits limits) {
        var seeds=new java.util.ArrayList<Vector3f>();
        for(var origin:List.of(prior,current))for(float step:new float[]{8,4,2})
            for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
                var seed=new Vector3f(origin);seed.setComponent(axis,seed.get(axis)+sign*step);seeds.add(seed);
            }
        var shaft=new Vector3f(target).sub(prior).normalize();
        var shoulder=world.get("right_arm").getTranslation(new Vector3f());
        var range=longitudinalRange(world,shaft,limits);
        var radial=new Vector3f(prior).sub(shoulder).fma(-new Vector3f(prior).sub(shoulder).dot(shaft),shaft);
        if(radial.lengthSquared()<.0001f)radial.set(radialPole(world.get("body"),shaft,"right"));else radial.normalize();
        var tangent=new Vector3f(shaft).cross(radial).normalize();
        for(int n=0;n<=8;n++)for(float radius:new float[]{20,35,50})for(int turn=0;turn<8;turn++) {
            double angle=turn*java.lang.Math.PI/4;
            float along=range[0]+(range[1]-range[0])*n/8;
            seeds.add(new Vector3f(shoulder).fma(along,shaft).fma((float)(radius*java.lang.Math.cos(angle)),radial)
                    .fma((float)(radius*java.lang.Math.sin(angle)),tangent));
        }
        var best=finiteRear(world,target,current,limits);
        var bestScore=gripScore(world,new Vector3f(target).sub(best).normalize(),best,authored,space,continuity,actual,limits);
        for(var seed:seeds) {
            try {
                var candidate=finiteRear(world,target,seed,limits);
                var candidateShaft=new Vector3f(target).sub(candidate).normalize();
                if(closedRear(world,candidateShaft,candidate,limits).distanceSquared(candidate)>.00000001f)continue;
                var score=gripScore(world,candidateShaft,candidate,authored,space,continuity,actual,limits);
                if(betterGrip(score,bestScore,space.transported && actual!=null,0)){best.set(candidate);bestScore=score;}
            } catch(IllegalArgumentException unreachableTrial) {
                // A trial's parallax can leave the common reach domain; skip that seed.
            }
        }
        return best;
    }
    /** A held world trajectory can cross the cursor boundary or forward-depth plane
     * when the actor turns. Relax the chest stop continuously outside the safe cursor
     * domain; legacy near-grip targets retain their feasible unrestricted chest branch. */
    private static double chestHeadingLimit(AttackAim.Point aim,float bodyYaw) {
        double depth=aim.distance()*java.lang.Math.cos(java.lang.Math.toRadians(AttackAim.wrap(aim.yaw()-bodyYaw)))*java.lang.Math.cos(java.lang.Math.toRadians(aim.pitch()));
        double near=java.lang.Math.max(0,SpearAimField.MIN_FORWARD_DISTANCE-depth)/1.5;
        // Keep the same chest branch while a long authoritative arc enters the box.
        // Only the close/rear domain yields; no angular seam can release the stop.
        double weight=java.lang.Math.clamp(near,0,1);
        weight=weight*weight*(3-2*weight);
        return java.lang.Math.toRadians(89+91*weight);
    }
    /** Bound the actual chest before projecting the common grip. Source pitch/bank
     * and the aim ray survive: only the extra local-Y turn is bounded. */
    private static double forwardChestYaw(Quaternionf source,double yaw,double limit,double center) {
        if(limit>=java.lang.Math.PI)return yaw;
        var forward=new Quaternionf(source).rotateY((float)yaw).transform(new Vector3f(0,0,1));
        double heading=wrap(java.lang.Math.atan2(forward.x,forward.z)-center);
        if(java.lang.Math.abs(heading)<=limit)return yaw;
        double boundary=center+java.lang.Math.copySign(limit,heading),sine=java.lang.Math.sin(boundary),cosine=java.lang.Math.cos(boundary);
        var right=new Quaternionf(source).transform(new Vector3f(1,0,0));
        var ahead=new Quaternionf(source).transform(new Vector3f(0,0,1));
        double a=right.x*cosine-right.z*sine,b=ahead.x*cosine-ahead.z*sine;
        double bounded=java.lang.Math.atan2(-b,a);
        var candidate=new Quaternionf(source).rotateY((float)bounded).transform(new Vector3f(0,0,1));
        if(candidate.x*sine+candidate.z*cosine<0)bounded=wrap(bounded+java.lang.Math.PI);
        return yaw+wrap(bounded-yaw);
    }
    /** Smallest chest rotation reaching a forward elbow branch; zero in the normal stance. */
    private static float underarmChestAdjustment(Map<String,Matrix4f> world,Vector3f shaft,Vector3f up,double yaw,double previousYaw) {
        var forward=world.get("body").transformDirection(new Vector3f(0,0,1));
        forward.fma(-forward.dot(up),up).normalize();
        var direction=new Vector3f(shaft).fma(-shaft.dot(up),up).normalize();
        double relative=java.lang.Math.atan2(up.dot(new Vector3f(forward).cross(direction)),forward.dot(direction));
        // A fully side-on chest traps the rear elbow behind a transverse pole
        // crossing the ribs. Keep a diagonal guard while allowing authored coil.
        double desired=java.lang.Math.clamp(relative,java.lang.Math.toRadians(40),java.lang.Math.toRadians(48));
        double adjustment=relative-desired;
        if(Double.isFinite(previousYaw)) {
            double step=java.lang.Math.toRadians(9);
            adjustment=wrap(previousYaw+java.lang.Math.clamp(wrap(yaw+adjustment-previousYaw),-step,step)-yaw);
        }
        return (float)adjustment;
    }
    private static float chestAdjustment(Map<String,Matrix4f> world,Vector3f shaft,Vector3f up,double yaw,double previousYaw) {
        float adjustment=chestTurn(world,shaft,up,54,true);
        if(!Double.isFinite(previousYaw))return adjustment;
        double relative=wrap(yaw+adjustment-previousYaw),step=java.lang.Math.toRadians(9);
        float bounded=(float)wrap(previousYaw+java.lang.Math.clamp(relative,-step,step)-yaw);
        var shoulders=world.get("left_arm").getTranslation(new Vector3f()).sub(world.get("right_arm").getTranslation(new Vector3f()));
        float along=new Quaternionf().rotationAxis(bounded,up).transform(shoulders).dot(shaft);
        // Comfort yaw is rate limited; exact arm reach remains a hard condition.
        // A sudden authoritative target can require the original 30 cm reach boundary.
        return along>=29.9f?bounded:chestTurn(world,shaft,up,30,false);
    }
    private static float chestTurn(Map<String,Matrix4f> world,Vector3f shaft,Vector3f up,float required,boolean forwardFacing) {
        var shoulders=world.get("left_arm").getTranslation(new Vector3f()).sub(world.get("right_arm").getTranslation(new Vector3f()));
        float vertical=shoulders.dot(up)*shaft.dot(up);
        var horizontal=new Vector3f(shoulders).sub(new Vector3f(up).mul(shoulders.dot(up)));
        double a=horizontal.dot(shaft),b=new Vector3f(up).cross(horizontal).dot(shaft);
        double need=required-vertical,amplitude=java.lang.Math.hypot(a,b);
        var forward=world.get("body").transformDirection(new Vector3f(0,0,1)).normalize();
        if((a>=need && (!forwardFacing || forward.dot(shaft)>=-.02f)) || amplitude<.0001)return 0;
        double phase=java.lang.Math.atan2(b,a);
        // Inside the reachable yaw interval, crossing its central side-on stance
        // needs only the small phase correction. Jumping to the far boundary would
        // rotate the chest by about 40 degrees during an otherwise slow aim return.
        if(a>=need)return (float)phase;
        if(amplitude<need)return (float)phase;
        double offset=java.lang.Math.acos(java.lang.Math.clamp(need/amplitude,-1,1));
        double first=wrap(phase-offset),second=wrap(phase+offset);
        // Emergency reach chooses the nearby legal orientation. Forcing the
        // forward-facing comfort branch here caused 115-degree windup snaps;
        // subsequent comfort steps turn the chest around progressively.
        if(!forwardFacing)return (float)(java.lang.Math.abs(first)<java.lang.Math.abs(second)?first:second);
        float firstFacing=new Quaternionf().rotationAxis((float)first,up).transform(new Vector3f(forward)).dot(shaft);
        float secondFacing=new Quaternionf().rotationAxis((float)second,up).transform(new Vector3f(forward)).dot(shaft);
        if(firstFacing>=0 && secondFacing<0)return (float)first;
        if(secondFacing>=0 && firstFacing<0)return (float)second;
        return (float)(java.lang.Math.abs(first)<java.lang.Math.abs(second)?first:second);
    }
    private static double wrap(double angle) {return java.lang.Math.atan2(java.lang.Math.sin(angle),java.lang.Math.cos(angle));}
    private static void head(Map<String,Pose> result,Quaternionf sourceHead,double chestYaw,float bank,
                             Map<String,Matrix4f> previous,SpearConstraints.Limits limits) {
        var local=new Quaternionf().rotationZ(-bank).rotateY((float)-chestYaw).mul(sourceHead).normalize();
        var sight=local.transform(new Vector3f(0,0,1));
        float yaw=(float)java.lang.Math.atan2(sight.x,sight.z),limit=(float)java.lang.Math.toRadians(limits.neckYawDegrees());
        float prior=Float.NaN;
        if(previous!=null && previous.containsKey("head")) {
            var oldLocal=previous.get("body").getUnnormalizedRotation(new Quaternionf()).normalize().invert()
                    .mul(previous.get("head").getUnnormalizedRotation(new Quaternionf()).normalize());
            var oldSight=oldLocal.transform(new Vector3f(0,0,1));
            prior=(float)java.lang.Math.atan2(oldSight.x,oldSight.z);
            // Unwrap around the previous neck branch before limiting; ±180-degree
            // atan2 wrapping must not switch the head between the two ±85-degree stops.
            yaw=prior+(float)wrap(yaw-prior);
        }
        float clamped=java.lang.Math.clamp(yaw,-limit,limit);
        if(Float.isFinite(prior))clamped=java.lang.Math.clamp(clamped,prior-(float)java.lang.Math.toRadians(10),prior+(float)java.lang.Math.toRadians(10));
        put(result,"head",new Quaternionf().rotationY(clamped-yaw).mul(local));
    }
    private static Vector3f radialPole(Matrix4f body,Vector3f shaft,String side) {
        var pole=body.transformDirection(new Vector3f(side.equals("right")?-.45f:.45f,-1,-.15f));
        pole.sub(new Vector3f(shaft).mul(pole.dot(shaft)));
        if(pole.lengthSquared()<.0001f)pole.set(1,0,0).sub(new Vector3f(shaft).mul(shaft.x));
        return pole.normalize();
    }
    /** Nearest common grip in both arm annuli, with no angle-dependent coordinate-frame sign. */
    private static Vector3f closedRear(Map<String,Matrix4f> world,Vector3f shaft,Vector3f authored,SpearConstraints.Limits limits) {
        var right=world.get("right_arm").getTranslation(new Vector3f());
        var left=world.get("left_arm").getTranslation(new Vector3f());
        var separation=new Vector3f(left).sub(right);
        float shoulderAlong=separation.dot(shaft),difference=limits.gripSpacingCm()-shoulderAlong;
        // Blend the longitudinal grip bound across the whole shoulder projection.
        // A binary 30 cm threshold jumped the rear palm by up to 27 cm when a
        // leaning/slim shoulder crossed it, despite almost unchanged aim.
        var range=longitudinalRange(world,shaft,limits);
        float minimum=range[0],maximum=range[1];
        if(minimum>maximum)throw new IllegalArgumentException("Spear chest cannot reach both transverse grips");
        var delta=new Vector3f(authored).sub(right);
        float a=java.lang.Math.clamp(delta.dot(shaft),minimum,maximum);
        var best=closedRearAt(world,shaft,authored,right,separation,difference,a,limits);
        if(best!=null)return best;
        // Tightened wrist rings may not intersect at the nearest longitudinal
        // coordinate. Search another common coordinate before declaring no reach.
        float error=Float.POSITIVE_INFINITY;
        for(int n=0;n<=32;n++) {
            float trial=minimum+(maximum-minimum)*n/32;
            var candidate=closedRearAt(world,shaft,authored,right,separation,difference,trial,limits);
            if(candidate==null)continue;
            float distance=candidate.distanceSquared(authored);
            if(distance<error){best=candidate;error=distance;}
        }
        if(best==null)throw new IllegalArgumentException("Spear arm annuli cannot satisfy the 150-degree wrist bound");
        return best;
    }
    private static float[] longitudinalRange(Map<String,Matrix4f> world,Vector3f shaft,SpearConstraints.Limits limits) {
        var separation=world.get("left_arm").getTranslation(new Vector3f())
                .sub(world.get("right_arm").getTranslation(new Vector3f()));
        float along=separation.dot(shaft),difference=limits.gripSpacingCm()-along;
        float forward=java.lang.Math.clamp(along/54,0,1);
        forward=forward*forward*(3-2*forward);
        // Negative reach is bounded by the absolute 150-degree wrist limit.
        float low=java.lang.Math.max(limits.tucked()?-32f:-36.5f+40.5f*forward,-UPPER*.8660254f+.01f),high=36.5f;
        if(limits.tucked()) {
            float leftReach=(float)java.lang.Math.sqrt(UPPER*UPPER+PALM_LINK*PALM_LINK+2*UPPER*PALM_LINK*java.lang.Math.cos(java.lang.Math.toRadians(10)));
            return new float[]{java.lang.Math.max(low,-leftReach-difference),java.lang.Math.min(high,leftReach-difference)};
        }
        return new float[]{java.lang.Math.max(low,low-difference),java.lang.Math.min(high,high-difference)};
    }
    private static Vector3f closedRearAt(Map<String,Matrix4f> world,Vector3f shaft,Vector3f authored,
                                         Vector3f right,Vector3f separation,float difference,float a,SpearConstraints.Limits limits) {
        float other=a+difference;
        var delta=new Vector3f(authored).sub(right);
        // Keep both elbow-circle roots away from their tangent: even a bent elbow can
        // otherwise spin rapidly when the upper arm is almost parallel to the shaft.
        float planeCos=.9961947f,planePalm=PALM_LINK*planeCos;
        float planeReserve=PALM_LINK*PALM_LINK*(1-planeCos*planeCos);
        float rightCircle=(float)java.lang.Math.sqrt(java.lang.Math.max(0,UPPER*UPPER-a*a-planeReserve));
        float leftCircle=(float)java.lang.Math.sqrt(java.lang.Math.max(0,UPPER*UPPER-other*other-planeReserve));
        float rightLow=java.lang.Math.abs(planePalm-rightCircle)+.1f,rightHigh=planePalm+rightCircle-.1f;
        float leftLow=java.lang.Math.abs(planePalm-leftCircle)+.1f,leftHigh=planePalm+leftCircle-.1f;
        // A nearly folded 173-degree elbow is neither a useful fighting pose nor a stable
        // arm annulus. Keep elbow flexion within the ordinary 140-degree human range.
        float minDistanceSquared=UPPER*UPPER+PALM_LINK*PALM_LINK+2*UPPER*PALM_LINK*(float)java.lang.Math.cos(java.lang.Math.toRadians(limits.elbowDegrees()));
        rightLow=java.lang.Math.max(rightLow,(float)java.lang.Math.sqrt(java.lang.Math.max(0,minDistanceSquared-a*a)));
        leftLow=java.lang.Math.max(leftLow,(float)java.lang.Math.sqrt(java.lang.Math.max(0,minDistanceSquared-other*other)));
        float maxDistanceSquared=UPPER*UPPER+PALM_LINK*PALM_LINK+2*UPPER*PALM_LINK*(float)java.lang.Math.cos(java.lang.Math.toRadians(10));
        rightHigh=java.lang.Math.min(rightHigh,(float)java.lang.Math.sqrt(java.lang.Math.max(0,maxDistanceSquared-a*a)));
        leftHigh=java.lang.Math.min(leftHigh,(float)java.lang.Math.sqrt(java.lang.Math.max(0,maxDistanceSquared-other*other)));
        if(limits.tucked()) {
            if(other*other>maxDistanceSquared)return null;
            leftLow=(float)java.lang.Math.sqrt(java.lang.Math.max(0,minDistanceSquared-other*other));
            leftHigh=(float)java.lang.Math.sqrt(java.lang.Math.max(0,maxDistanceSquared-other*other));
        }
        // twist=atan2(±b*sqrt(1-c*c),a). For a<0, |twist|<=150
        // requires b*b*(1-c*c)>=a*a/3, yielding these two radial roots.
        // Small inward margins keep float projection tolerances inside the limit.
        if(a<0) {
            double center=UPPER*UPPER+PALM_LINK*PALM_LINK-a*(double)a;
            double span=2*PALM_LINK*java.lang.Math.sqrt(java.lang.Math.max(0,UPPER*UPPER-4*a*(double)a/3));
            rightLow=java.lang.Math.max(rightLow,(float)java.lang.Math.sqrt(java.lang.Math.max(0,center-span))+.003f);
            rightHigh=java.lang.Math.min(rightHigh,(float)java.lang.Math.sqrt(java.lang.Math.max(0,center+span))-.003f);
        }
        if(other<0&&!limits.tucked()) {
            double center=UPPER*UPPER+PALM_LINK*PALM_LINK-other*(double)other;
            double span=2*PALM_LINK*java.lang.Math.sqrt(java.lang.Math.max(0,UPPER*UPPER-4*other*(double)other/3));
            leftLow=java.lang.Math.max(leftLow,(float)java.lang.Math.sqrt(java.lang.Math.max(0,center-span))+.003f);
            leftHigh=java.lang.Math.min(leftHigh,(float)java.lang.Math.sqrt(java.lang.Math.max(0,center+span))-.003f);
        }
        if(rightLow>rightHigh || leftLow>leftHigh)return null;
        float shoulderAlong=separation.dot(shaft);
        var center=new Vector3f(separation).sub(new Vector3f(shaft).mul(shoulderAlong));
        var desired=new Vector3f(delta).sub(new Vector3f(shaft).mul(delta.dot(shaft)));
        var candidates=new java.util.ArrayList<Vector3f>();candidates.add(new Vector3f(desired));
        var down=radialPole(world.get("body"),shaft,"right");
        for(float radius:new float[]{rightLow,rightHigh})candidates.add(circleProjection(desired,new Vector3f(),radius,down));
        for(float radius:new float[]{leftLow,leftHigh})candidates.add(circleProjection(desired,center,radius,down));
        float width=center.length();
        if(width>.0001f) {
            Vector3f across=new Vector3f(center).div(width),normal=new Vector3f(shaft).cross(across).normalize();
            for(float rightRadius:new float[]{rightLow,rightHigh})for(float leftRadius:new float[]{leftLow,leftHigh}) {
                double x=(width*(double)width+rightRadius*(double)rightRadius-leftRadius*(double)leftRadius)/(2*width);
                double square=rightRadius*(double)rightRadius-x*x;
                if(square<-.001)continue;
                float y=(float)java.lang.Math.sqrt(java.lang.Math.max(0,square));
                candidates.add(new Vector3f(across).mul((float)x).add(new Vector3f(normal).mul(y)));
                candidates.add(new Vector3f(across).mul((float)x).sub(new Vector3f(normal).mul(y)));
            }
        }
        Vector3f best=null;float error=Float.POSITIVE_INFINITY;
        for(var candidate:candidates) {
            float first=candidate.length(),second=candidate.distance(center);
            if(first<rightLow-.001f || first>rightHigh+.001f || second<leftLow-.001f || second>leftHigh+.001f)continue;
            float distance=candidate.distanceSquared(desired);
            if(distance<error){best=candidate;error=distance;}
        }
        if(best==null)return null;
        return new Vector3f(right).add(new Vector3f(shaft).mul(a)).add(best);
    }
    private static Vector3f circleProjection(Vector3f desired,Vector3f center,float radius,Vector3f down) {
        var direction=new Vector3f(desired).sub(center);
        if(direction.lengthSquared()<.0001f)direction.set(down);else direction.normalize();
        return direction.mul(radius).add(center);
    }
    private record Arm(Vector3f upper,Vector3f fore,Vector3f hinge,Vector3f elbow,float twist,float cost,float continuity,float peakStep) {}
    private static Arm arm(String side,Vector3f shoulder,Vector3f palm,Vector3f shaft,SpearConstraints.Space space,
                           Map<String,Matrix4f> previous,SpearConstraints.Limits limits) {
        if(space.tucked&&side.equals("left"))return supportArm(shoulder,palm,shaft,space,previous,limits);
        var delta=new Vector3f(palm).sub(shoulder);
        var radial=new Vector3f(delta).sub(new Vector3f(shaft).mul(delta.dot(shaft)));
        float distance=radial.length();
        if(distance<.0001f)throw new IllegalArgumentException("Spear closed grip has a degenerate elbow plane");
        Vector3f basis=radial.div(distance),tangent=new Vector3f(shaft).cross(basis).normalize();
        float cosine=(delta.lengthSquared()+PALM_LINK*PALM_LINK-UPPER*UPPER)/(2*PALM_LINK*distance);
        if(java.lang.Math.abs(cosine)>1.00005f)throw new IllegalArgumentException("Spear closed grip is unreachable: "+side+" cosine="+cosine);
        cosine=java.lang.Math.clamp(cosine,-1,1);
        float sine=(float)java.lang.Math.sqrt(java.lang.Math.max(0,1-cosine*cosine));
        var first=new Vector3f(basis).mul(cosine).add(new Vector3f(tangent).mul(sine));
        var second=new Vector3f(basis).mul(cosine).sub(new Vector3f(tangent).mul(sine));
        var preferred=side.equals("right")?first:second;
        var alternative=side.equals("right")?second:first;
        var a=armCandidate(side,shoulder,palm,shaft,preferred,space,previous,limits);
        var b=armCandidate(side,shoulder,palm,shaft,alternative,space,previous,limits);
        if(space.tucked&&side.equals("right")) {
            var history=space.actualHistory.get(side);
            if(history!=null) {
                var wrist=new Quaternionf(history.worldFore()).invert().mul(history.worldHand()).normalize();
                float sign=wrist.y*wrist.w;
                if(java.lang.Math.abs(sign)>.00001f) {
                    boolean sameA=a.twist()*sign>=0,sameB=b.twist()*sign>=0;
                    // Carry the rear palm on the same anatomical side of the pole
                    // through thrust/recovery; changing pronation roots leaves the
                    // following READY pose permanently hanging below its guard.
                    if(sameA!=sameB)return sameA?a:b;
                }
            }
        }
        // A different branch needs a meaningful clearance improvement. With a prior
        // elbow its distance term keeps the choice connected as a cursor moves.
        if(previous!=null && a.elbow().distance(b.elbow())>3
                && (!space.transported || a.peakStep()>java.lang.Math.max(35,space.motionBudget+1)
                || b.peakStep()>java.lang.Math.max(35,space.motionBudget+1)))
            return a.continuity()<=b.continuity()?a:b;
        return b.cost()+12<a.cost()?b:a;
    }
    /** The front hand guides the pole obliquely; only the rear fist sets its axis. */
    private static Arm supportArm(Vector3f shoulder,Vector3f palm,Vector3f shaft,SpearConstraints.Space space,
                                  Map<String,Matrix4f> previous,SpearConstraints.Limits limits) {
        var delta=new Vector3f(palm).sub(shoulder);float distance=delta.length();
        var axis=delta.div(distance);
        float along=(UPPER*UPPER-PALM_LINK*PALM_LINK+distance*distance)/(2*distance);
        float radius=(float)java.lang.Math.sqrt(java.lang.Math.max(0,UPPER*UPPER-along*along));
        var center=new Vector3f(shoulder).fma(along,axis);
        var parent=new Quaternionf(space.inverseParent).invert();
        var radial=parent.transform(new Vector3f(.5f,-.4f,.8f));radial.fma(-radial.dot(axis),axis).normalize();
        var tangent=new Vector3f(axis).cross(radial).normalize();
        var hints=new java.util.ArrayList<Vector3f>();
        var actual=space.actualHistory.get("left");
        if(actual!=null)hints.add(actual.worldArm().transform(new Vector3f(0,-UPPER,0)).add(shoulder).sub(center));
        if(previous!=null)hints.add(previous.get("left_forearm").getTranslation(new Vector3f()).sub(center));
        hints.add(new Vector3f(radial));
        for(int i=1;i<16;i++)hints.add(new Vector3f(radial).mul((float)java.lang.Math.cos(i*java.lang.Math.PI/8)).fma((float)java.lang.Math.sin(i*java.lang.Math.PI/8),tangent));
        Arm best=null;int evaluated=0;
        for(var hint:hints) {
            hint.fma(-hint.dot(axis),axis);if(hint.lengthSquared()<.000001f)continue;hint.normalize();
            var elbow=new Vector3f(center).fma(radius,hint);
            var fore=new Vector3f(palm).sub(elbow).normalize();
            var candidate=armCandidate("left",shoulder,palm,shaft,fore,space,previous,limits);
            if(java.lang.Math.abs(candidate.twist())>150.01f)continue;
            if(previous!=null&&candidate.peakStep()<=space.motionBudget+1&&space.deepArmContact(shoulder,candidate.elbow(),palm)<.1f)return candidate;
            if(best==null||candidate.cost()<best.cost())best=candidate;
            if(++evaluated>=3&&best.peakStep()<=35&&space.deepArmContact(shoulder,best.elbow(),palm)<.1f)return best;
        }
        if(best==null)throw new IllegalArgumentException("Front support wrist has no legal orientation");
        return best;
    }
    private static Arm armCandidate(String side,Vector3f shoulder,Vector3f palm,Vector3f shaft,Vector3f fore,
                                    SpearConstraints.Space space,Map<String,Matrix4f> previous,SpearConstraints.Limits limits) {
        var delta=new Vector3f(palm).sub(shoulder);
        var upperAxis=new Vector3f(delta).sub(new Vector3f(fore).mul(PALM_LINK)).div(UPPER).normalize();
        var hinge=new Vector3f(upperAxis).cross(fore).normalize();
        var elbow=new Vector3f(shoulder).fma(UPPER,upperAxis);
        var lowerZ=new Vector3f(hinge).cross(new Vector3f(fore).negate());
        float twist=(float)java.lang.Math.toDegrees(java.lang.Math.atan2(shaft.dot(hinge),shaft.dot(lowerZ)));
        boolean frontGuide=space.tucked&&side.equals("left");
        if(frontGuide)twist=0;
        float cost=180*space.arm(shoulder,elbow,palm,side);
        if(space.deepArmWeight>0)cost+=space.deepArmWeight*space.deepArmContact(shoulder,elbow,palm);
        cost+=4*SpearConstraints.square(java.lang.Math.max(0,java.lang.Math.abs(twist)-limits.wristDegrees()));
        cost+=.01f*SpearConstraints.square(java.lang.Math.max(0,java.lang.Math.abs(twist)-80));
        float continuity=0,peak=0;
        var worldUpper=frame(upperAxis,hinge);
        var worldFore=frame(fore,hinge);
        var local=new Quaternionf(space.inverseParent).mul(worldUpper);
        var localFore=new Quaternionf(space.inverseParent).mul(worldFore);
        var handY=new Vector3f(fore).negate();
        var handZ=frontGuide?new Vector3f(lowerZ).normalize():new Vector3f(shaft).fma(-shaft.dot(handY),handY).normalize();
        var handX=new Vector3f(handY).cross(handZ).normalize();
        var worldHand=new Quaternionf().setFromNormalized(new Matrix3f(handX,handY,handZ));
        var hand=new Quaternionf(space.inverseParent).mul(worldHand);
        var history=space.history.get(side);
        if(history!=null) {
            var transported=space.body.transformDirection(new Vector3f(elbow).sub(shoulder));
            float angle=rotationStep(local,history.arm()),foreAngle=rotationStep(localFore,history.fore()),handAngle=rotationStep(hand,history.hand());
            cost+=.08f*(transported.distanceSquared(history.upper())+angle*angle+foreAngle*foreAngle+handAngle*handAngle);
        }
        var actual=space.actualHistory.get(side);
        if(actual!=null) {
            var actualUpper=space.body.transformDirection(new Vector3f(elbow).sub(shoulder));
            float angle=space.transported?rotationStep(worldUpper,actual.worldArm()):rotationStep(local,actual.arm());
            float foreAngle=space.transported?rotationStep(worldFore,actual.worldFore()):rotationStep(localFore,actual.fore());
            float handAngle=space.transported?rotationStep(worldHand,actual.worldHand()):rotationStep(hand,actual.hand());
            continuity=actualUpper.distanceSquared(actual.upper())+angle*angle+foreAngle*foreAngle+handAngle*handAngle;
            peak=java.lang.Math.max(angle,java.lang.Math.max(foreAngle,handAngle));
            cost+=space.temporalWeight*SpearConstraints.square(java.lang.Math.max(0,peak-space.motionBudget));
        }
        return new Arm(upperAxis,fore,hinge,elbow,twist,cost,continuity,peak);
    }
    private static float rotationStep(Quaternionf a,Quaternionf b) {
        return (float)java.lang.Math.toDegrees(2*java.lang.Math.acos(java.lang.Math.clamp(java.lang.Math.abs(a.dot(b)),0,1)));
    }
    private static float gripCost(Map<String,Matrix4f> world,Vector3f shaft,Vector3f rear,Vector3f authored,
                                  SpearConstraints.Space space,Map<String,Matrix4f> previous,SpearConstraints.Limits limits) {
        return gripScore(world,shaft,rear,authored,space,previous,null,limits).cost();
    }
    private record GripScore(float peak,float cost,boolean shaftClear,boolean rearTucked) {}
    /** Rank closed poses directly; a large scalar penalty can hide meaningful cost differences. */
    private static boolean betterGrip(GripScore candidate,GripScore best,boolean actualHistory,float costEpsilon) {
        if(candidate.shaftClear()!=best.shaftClear())return candidate.shaftClear();
        if(candidate.rearTucked()!=best.rearTucked()) {
            if(actualHistory && (candidate.peak()<=35)!=(best.peak()<=35))return candidate.peak()<=35;
            if(actualHistory && candidate.peak()>35&&best.peak()>35)return candidate.peak()<best.peak();
            return candidate.rearTucked();
        }
        if(actualHistory) {
            boolean candidateContinuous=candidate.peak()<=CONTINUOUS_PEAK,bestContinuous=best.peak()<=CONTINUOUS_PEAK;
            if(candidateContinuous!=bestContinuous)return candidateContinuous;
            if(!candidateContinuous && java.lang.Math.abs(candidate.peak()-best.peak())>PEAK_TIE)
                return candidate.peak()<best.peak();
        }
        return candidate.cost()<best.cost()-costEpsilon;
    }
    private static GripScore gripScore(Map<String,Matrix4f> world,Vector3f shaft,Vector3f rear,Vector3f authored,
                                      SpearConstraints.Space space,Map<String,Matrix4f> continuity,
                                      Map<String,Matrix4f> actual,SpearConstraints.Limits limits) {
        return gripScore(world,shaft,rear,authored,space,continuity,actual,limits,null);
    }
    /** Share the chosen six arm rotations between soft scoring, actual peak ranking and final IK writes. */
    private static GripScore gripScore(Map<String,Matrix4f> world,Vector3f shaft,Vector3f rear,Vector3f authored,
                                      SpearConstraints.Space space,Map<String,Matrix4f> continuity,
                                      Map<String,Matrix4f> actual,SpearConstraints.Limits limits,Arm[] selectedArms) {
        boolean clear=!space.tucked||space.shaftClear(rear,shaft);
        float shaftCost=space.tucked&&clear?0:space.shaft(rear,shaft);
        float cost=space.poseWeight*rear.distanceSquared(authored)+space.shaftWeight*shaftCost;
        if(space.carry) {
            var local=space.body.transformPosition(new Vector3f(rear));
            cost+=4000*(SpearConstraints.square(java.lang.Math.max(0,local.x+47))
                    +SpearConstraints.square(java.lang.Math.max(0,-70-local.x))
                    +SpearConstraints.square(java.lang.Math.max(0,local.z-3))
                    +SpearConstraints.square(java.lang.Math.max(0,-24-local.z))
                    +SpearConstraints.square(java.lang.Math.max(0,56-local.y))
                    +SpearConstraints.square(java.lang.Math.max(0,local.y-66)));
        }
        float peak=0;boolean rearTucked=true;
        if(space.transported && space.actualBody!=null) {
            float chestStep=rotationStep(world.get("body").getUnnormalizedRotation(new Quaternionf()).normalize(),space.actualBody);
            cost+=space.temporalWeight*SpearConstraints.square(java.lang.Math.max(0,chestStep-space.motionBudget));
            peak=chestStep;
            if(actual!=null && actual.containsKey("head") && world.containsKey("head"))
                peak=java.lang.Math.max(peak,rotationStep(world.get("head").getUnnormalizedRotation(new Quaternionf()).normalize(),
                        actual.get("head").getUnnormalizedRotation(new Quaternionf()).normalize()));
        }
        for(int sideIndex=0;sideIndex<SIDES.size();sideIndex++) {
            String side=SIDES.get(sideIndex);
            var palm=new Vector3f(rear).fma(side.equals("right")?0:GRIP_SPACING,shaft);
            var candidate=arm(side,world.get(side+"_arm").getTranslation(new Vector3f()),palm,shaft,space,continuity,limits);
            if(space.carry&&side.equals("right")) {
                var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
                var upper=space.body.transformDirection(new Vector3f(candidate.elbow()).sub(shoulder));
                var hand=space.body.transformDirection(new Vector3f(palm).sub(shoulder));
                rearTucked=-upper.z>=java.lang.Math.max(20,-upper.y*1.2f)&&hand.y<=-10;
            }
            cost+=candidate.cost();peak=java.lang.Math.max(peak,candidate.peakStep());
            if(selectedArms!=null)selectedArms[sideIndex]=candidate;
        }
        return new GripScore(peak,cost,clear&&(!space.tucked||space.shaftInFront(shaft)),rearTucked);
    }
    private static Vector3f safeRear(Map<String,Matrix4f> world,Vector3f shaft,Vector3f rear,Vector3f authored,
                                     Map<String,Matrix4f> previous,Map<String,Matrix4f> actual,boolean transported,float authoredMotion,Vector3f target,float confidence,SpearConstraints.Limits limits) {
        var space=new SpearConstraints.Space(world,limits,previous,actual,authored,shaft,transported,authoredMotion,target,confidence);
        var best=transported?finiteRear(world,target,rear,limits):new Vector3f(rear);
        var bestAxis=transported?new Vector3f(target).sub(best).normalize():shaft;
        var initialArms=new Arm[SIDES.size()];
        var bestScore=gripScore(world,bestAxis,best,authored,space,previous,actual,limits,initialArms);
        boolean rankActual=transported && actual!=null;
        float contacts=space.tucked&&bestScore.shaftClear()?0:space.shaft(best,bestAxis),twist=0,peak=bestScore.peak(),deepContacts=0;boolean rearPosture=true;
        for(int sideIndex=0;sideIndex<SIDES.size();sideIndex++) {
            String side=SIDES.get(sideIndex);
            var shoulder=world.get(side+"_arm").getTranslation(new Vector3f());
            var palm=new Vector3f(best).fma(side.equals("right")?0:limits.gripSpacingCm(),bestAxis);
            var candidate=initialArms[sideIndex];
            if(limits.carry()&&side.equals("right")) {
                var upper=space.body.transformDirection(new Vector3f(candidate.elbow()).sub(shoulder));
                rearPosture=-upper.z>=java.lang.Math.max(20,-upper.y*1.2f);
            }
            contacts+=space.contact(shoulder,candidate.elbow(),palm);
            if(space.tucked)deepContacts+=space.deepArmContact(shoulder,candidate.elbow(),palm);
            twist=java.lang.Math.max(twist,java.lang.Math.abs(candidate.twist()));
        }
        // A safe, moving prediction already satisfies the hard grip/angle limits.
        // Do not replace its small breathing/stride signal with a static global optimum.
        if(transported && rearPosture && bestScore.shaftClear() && contacts<.0001f && twist<=limits.wristDegrees()+1 && peak<=space.motionBudget+1)return best;
        if(transported&&space.tucked&&rearPosture&&bestScore.shaftClear()&&deepContacts<.1f&&twist<=150.01f&&peak<=space.motionBudget+1)return best;
        if(transported && actual!=null) {
            // A nearest-point projection can jump between disconnected inner rings.
            // Keep the real prior grip in the candidate set while the authored target
            // still supplies the motion goal and the target supplies the shaft axis.
            var prior=actual.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
            var candidate=finiteRear(world,target,prior,limits);
            var candidateShaft=new Vector3f(target).sub(candidate).normalize();
            var score=gripScore(world,candidateShaft,candidate,authored,space,previous,actual,limits);
            if(betterGrip(score,bestScore,rankActual,.001f)){best.set(candidate);bestScore=score;}
        }
        var body=world.get("body");
        var directions=new Vector3f[]{new Vector3f(1,0,0),new Vector3f(0,1,0),new Vector3f(0,0,1),
                new Vector3f(0,-1,1).normalize(),new Vector3f(1,0,1).normalize(),new Vector3f(-1,0,1).normalize()};
        for(var direction:directions)body.transformDirection(direction).normalize();
        // The padded sleeve shell is a soft comfort score. Only actual limb
        // penetration requires the broad seed grid during a connected frame.
        if(!transported || !bestScore.shaftClear() || (space.tucked?deepContacts>1:contacts>1) || peak>space.motionBudget+10) {
            // Disconnected near/far rings need a few genuine alternative starting grips,
            // not only a local descent from an already penetrating authored hand.
            for(float y:limits.tucked()?new float[]{52,60,68}:new float[]{30,45,60})for(float z:limits.tucked()?new float[]{-24,-8,8,24}:new float[]{22,38,54})for(float x:limits.tucked()?new float[]{-65,-55,-45}:new float[]{-40,-25,-10}) {
                var seed=body.transformPosition(new Vector3f(x,y,z));
                var candidate=trialRear(world,target,shaft,seed,transported,limits);
                if(candidate==null)continue;
                var axis=transported?new Vector3f(target).sub(candidate).normalize():shaft;
                var score=gripScore(world,axis,candidate,authored,space,previous,actual,limits);
                if(betterGrip(score,bestScore,rankActual,.001f)){best.set(candidate);bestScore=score;}
            }
        }
        float[] steps=transported?(space.tucked?new float[]{8,2,.5f}:new float[]{8,4,2,1,.5f,.25f}):new float[]{16,8,4,2,1};
        for(float step:steps) {
            var origin=new Vector3f(best);
            for(var direction:directions)for(int sign:new int[]{1,-1}) {
                var candidate=trialRear(world,target,shaft,new Vector3f(origin).fma(sign*step,direction),transported,limits);
                if(candidate==null)continue;
                var axis=transported?new Vector3f(target).sub(candidate).normalize():shaft;
                var score=gripScore(world,axis,candidate,authored,space,previous,actual,limits);
                if(betterGrip(score,bestScore,rankActual,.001f)){best.set(candidate);bestScore=score;}
            }
        }
        return best;
    }
    /** Positive elbow flexion; pronation never causes a 180-degree upper-arm roll. */
    private static void solve(Map<String,Pose> result,String side,Arm arm,Vector3f shaft,Quaternionf parent) {
        var upperAxis=arm.upper();var fore=arm.fore();var hinge=arm.hinge();
        Quaternionf upper=frame(upperAxis,hinge);
        put(result,side+"_arm",new Quaternionf(parent).invert().mul(upper));
        float bend=(float)java.lang.Math.toDegrees(java.lang.Math.acos(java.lang.Math.clamp(upperAxis.dot(fore),-1,1)));
        if(new Vector3f(upperAxis).cross(fore).dot(hinge)<0)bend=-bend;
        putAxis(result,side+"_forearm",new FsvoxAnimation.V(bend,0,0));
        putAxis(result,side+"_hand",new FsvoxAnimation.V(0,arm.twist(),0));
    }
    private static void putAxis(Map<String,Pose> poses,String id,FsvoxAnimation.V rotation) {
        var old=poses.get(id);poses.put(id,new Pose(rotation,old.position(),old.scale()));
    }
    private static void put(Map<String,Pose> poses,String id,Quaternionf rotation) {
        // Double XYZ decomposition avoids float asin's lost precision near a 90-degree
        // orientation. Pure Y pronation and pure X hinges stay pure in the saved Pose.
        double length=java.lang.Math.sqrt(rotation.x*(double)rotation.x+rotation.y*(double)rotation.y+rotation.z*(double)rotation.z+rotation.w*(double)rotation.w);
        double x=rotation.x/length,y=rotation.y/length,z=rotation.z/length,w=rotation.w/length;
        double m00=1-2*(y*y+z*z),m01=2*(x*y-z*w),m02=2*(x*z+y*w);
        double m12=2*(y*z-x*w),m22=1-2*(x*x+y*y);
        double cosine=java.lang.Math.hypot(m00,m01),a,b,c;
        if(cosine<1e-6) {a=java.lang.Math.atan2(2*(y*z+x*w),1-2*(x*x+z*z));b=java.lang.Math.atan2(m02,cosine);c=0;}
        else {a=java.lang.Math.atan2(-m12,m22);b=java.lang.Math.atan2(m02,cosine);c=java.lang.Math.atan2(-m01,m00);}
        double alternateX=wrap(a+java.lang.Math.PI),alternateY=b>=0?java.lang.Math.PI-b:-java.lang.Math.PI-b,alternateZ=wrap(c+java.lang.Math.PI);
        var angles=new FsvoxAnimation.V((float)java.lang.Math.toDegrees(a),(float)java.lang.Math.toDegrees(b),(float)java.lang.Math.toDegrees(c));
        var alternateAngles=new FsvoxAnimation.V((float)java.lang.Math.toDegrees(alternateX),(float)java.lang.Math.toDegrees(alternateY),(float)java.lang.Math.toDegrees(alternateZ));
        var old=poses.get(id);
        var first=new Pose(angles,old.position(),old.scale());
        var second=new Pose(alternateAngles,old.position(),old.scale());
        // The host reconstructs Euler angles with float cos-from-sin. Either XYZ
        // branch can lose small deviations near 180 degrees (including Y). Compare
        // their actual round trips, rather than assuming small X/Z is more accurate.
        double firstError=rotationError(first,x,y,z,w),secondError=rotationError(second,x,y,z,w);
        boolean simpler=java.lang.Math.abs(alternateX)+java.lang.Math.abs(alternateZ)<java.lang.Math.abs(a)+java.lang.Math.abs(c);
        poses.put(id,secondError<firstError-1e-12 || (java.lang.Math.abs(secondError-firstError)<=1e-12 && simpler)?second:first);
    }
    private static double rotationError(Pose pose,double x,double y,double z,double w) {
        var actual=pose.fsQuaternion().normalize();
        double sign=actual.x*x+actual.y*y+actual.z*z+actual.w*w<0?-1:1;
        double dx=actual.x-sign*x,dy=actual.y-sign*y,dz=actual.z-sign*z,dw=actual.w-sign*w;
        return dx*dx+dy*dy+dz*dz+dw*dw;
    }
    private static Quaternionf frame(Vector3f axis,Vector3f hinge) {
        Vector3f y=new Vector3f(axis).negate(),x=new Vector3f(hinge),z=new Vector3f(x).cross(y);
        return new Quaternionf().setFromNormalized(new Matrix3f(x,y,z));
    }
}

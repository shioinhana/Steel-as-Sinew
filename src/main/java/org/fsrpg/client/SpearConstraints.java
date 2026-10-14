package org.fsrpg.client;

import java.util.Map;
import java.util.HashMap;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Small, bounded geometric search for a rigid two-hand grip; all distances are centimeters. */
final class SpearConstraints {
    private SpearConstraints() {}
    record Limits(float elbowDegrees, float wristDegrees, float clearanceCm, float neckYawDegrees,float verticalBankDegrees, boolean tucked,boolean carry,float gripSpacingCm) {
        Limits(float elbowDegrees,float wristDegrees,float clearanceCm,float neckYawDegrees,float verticalBankDegrees,boolean tucked,boolean carry) {
            this(elbowDegrees,wristDegrees,clearanceCm,neckYawDegrees,verticalBankDegrees,tucked,carry,SpearGrip.RESTING_CM);
        }
        Limits(float elbowDegrees,float wristDegrees,float clearanceCm,float neckYawDegrees,float verticalBankDegrees,boolean tucked) {
            this(elbowDegrees,wristDegrees,clearanceCm,neckYawDegrees,verticalBankDegrees,tucked,tucked);
        }
        Limits withCarry(boolean value) {return new Limits(elbowDegrees,wristDegrees,clearanceCm,neckYawDegrees,verticalBankDegrees,tucked,value,gripSpacingCm);}
        Limits withGrip(float spacingCm) {return new Limits(elbowDegrees,wristDegrees,clearanceCm,neckYawDegrees,verticalBankDegrees,tucked,carry,spacingCm);}
        Limits(float elbowDegrees,float wristDegrees,float clearanceCm,float neckYawDegrees,float verticalBankDegrees) {
            this(elbowDegrees,wristDegrees,clearanceCm,neckYawDegrees,verticalBankDegrees,false);
        }
        static final Limits DEFAULT = new Limits(135, 120, 1.5f, 85,18);
        Limits(float elbowDegrees,float wristDegrees,float clearanceCm,float neckYawDegrees) {
            this(elbowDegrees,wristDegrees,clearanceCm,neckYawDegrees,18);
        }
        Limits {
            if (!Float.isFinite(elbowDegrees) || elbowDegrees < 100 || elbowDegrees > 145
                    || !Float.isFinite(wristDegrees) || wristDegrees < 90 || wristDegrees > 150
                    || !Float.isFinite(clearanceCm) || clearanceCm < 0 || clearanceCm > 6
                    || !Float.isFinite(neckYawDegrees) || neckYawDegrees < 50 || neckYawDegrees > 100
                    || !Float.isFinite(verticalBankDegrees) || verticalBankDegrees < 0 || verticalBankDegrees > 25
                    || !Float.isFinite(gripSpacingCm) || gripSpacingCm < SpearGrip.MINIMUM_CM || gripSpacingCm > SpearGrip.MAXIMUM_CM)
                throw new IllegalArgumentException("Invalid spear presentation limits");
        }
    }
    /** Immutable per-solve collision frames: avoid inverting matrices for every trial. */
    static final class Space {
        final Matrix4f body, head;
        final Vector3f facing;
        final Vector3f waist,lumbar;
        final float waistRadius;
        final Quaternionf inverseParent;
        final Quaternionf actualBody;
        final Map<String, History> history = new HashMap<>();
        final Map<String, History> actualHistory = new HashMap<>();
        final float margin, motionBudget,poseWeight,shaftWeight,deepArmWeight,temporalWeight;
        final boolean transported, tucked,carry;
        Space(Map<String, Matrix4f> world, Limits limits) {
            this(world,limits,null,null,null);
        }
        Space(Map<String, Matrix4f> world, Limits limits,Map<String,Matrix4f> previous,Vector3f authored,Vector3f shaft) {
            this(world,limits,previous,previous,authored,shaft,false,0);
        }
        Space(Map<String, Matrix4f> world, Limits limits,Map<String,Matrix4f> previous,Map<String,Matrix4f> actual,
              Vector3f authored,Vector3f shaft,
              boolean transported,float authoredMotion) {
            this(world,limits,previous,actual,authored,shaft,transported,authoredMotion,null);
        }
        Space(Map<String, Matrix4f> world, Limits limits,Map<String,Matrix4f> previous,Map<String,Matrix4f> actual,
              Vector3f authored,Vector3f shaft,boolean transported,float authoredMotion,Vector3f target) {
            this(world,limits,previous,actual,authored,shaft,transported,authoredMotion,target,1);
        }
        Space(Map<String, Matrix4f> world, Limits limits,Map<String,Matrix4f> previous,Map<String,Matrix4f> actual,
              Vector3f authored,Vector3f shaft,boolean transported,float authoredMotion,Vector3f target,float confidence) {
            this.transported=transported;
            tucked=limits.tucked();
            carry=limits.carry();
            body = new Matrix4f(world.get("body")).invert();
            facing=world.get("body").transformDirection(new Vector3f(0,0,1)).normalize();
            waist=world.get("body").getTranslation(new Vector3f());
            lumbar=world.get("body").transformPosition(new Vector3f(0,37.5f,0));
            var up=world.get("body").transformDirection(new Vector3f(0,1,0)).normalize();
            // Every twisted 8x4 torso section fits this envelope. The extra term
            // bounds its center shift from tilted chest to horizontal pelvis.
            waistRadius=27.95085f+11*up.sub(0,1,0).length()+.25f;
            head = new Matrix4f(world.get("head")).invert();
            inverseParent=body.getUnnormalizedRotation(new Quaternionf()).normalize();
            actualBody=actual==null?null:actual.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            margin = limits.clearanceCm();
            // Authored motion is a goal in its own right. A tiny distance weight lets
            // comfort/collision descent erase breathing and stride even at a legal grip.
            // Exact closure and joint stops are projected before this soft comparison.
            poseWeight=transported?.12f+(256-.12f)*confidence:.12f;
            // Safety and actual-frame continuity remain goals at extreme angles.
            // Only the source stroke yields when that source posture is unsuitable.
            shaftWeight=transported?36000:360;
            deepArmWeight=transported?18000:0;
            temporalWeight=transported?100000:10000;
            remember(history,previous);
            remember(actualHistory,actual);
            float budget=10;
            if(actual!=null) {
                if(shaft!=null && authored!=null) {
                    var oldShaft=actual.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                    var oldRear=actual.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
                    // Measure the new target from the unchanged actual rear palm.
                    // A trial grip's parallax must not create its own motion budget.
                    var aimAxis=transported && target!=null?new Vector3f(target).sub(oldRear).normalize():shaft;
                    float aimStep=(float)java.lang.Math.toDegrees(java.lang.Math.acos(java.lang.Math.clamp(oldShaft.dot(aimAxis),-1,1)));
                    // A persistent target's unresolved offset is not new motion.
                    // Grant movement budget from the source's actual frame delta.
                    float movement=transported?authoredMotion:authored.distance(oldRear);
                    // A fast target already requires a fast rigid shaft. Searching
                    // hundreds of rescue grips against an impossible 34deg ceiling
                    // stalls the frame, skipping still more of a short windup.
                    budget+=transported?java.lang.Math.min(75,1.1f*aimStep+.6f*movement)
                            :java.lang.Math.min(24,1.5f*aimStep+.6f*movement);
                }
            }
            motionBudget=budget;
        }
        private static void remember(Map<String,History> target,Map<String,Matrix4f> previous) {
            if(previous==null)return;
            var oldBody=new Matrix4f(previous.get("body")).invert();
            var oldParent=oldBody.getUnnormalizedRotation(new Quaternionf()).normalize();
            for(String side:new String[]{"right","left"}) {
                var delta=previous.get(side+"_forearm").getTranslation(new Vector3f())
                        .sub(previous.get(side+"_arm").getTranslation(new Vector3f()));
                target.put(side,new History(oldBody.transformDirection(delta),
                        new Quaternionf(oldParent).mul(previous.get(side+"_arm").getUnnormalizedRotation(new Quaternionf()).normalize()),
                        new Quaternionf(oldParent).mul(previous.get(side+"_forearm").getUnnormalizedRotation(new Quaternionf()).normalize()),
                        new Quaternionf(oldParent).mul(previous.get(side+"_hand").getUnnormalizedRotation(new Quaternionf()).normalize()),
                        previous.get(side+"_arm").getUnnormalizedRotation(new Quaternionf()).normalize(),
                        previous.get(side+"_forearm").getUnnormalizedRotation(new Quaternionf()).normalize(),
                        previous.get(side+"_hand").getUnnormalizedRotation(new Quaternionf()).normalize()));
            }
        }
        float arm(Vector3f shoulder, Vector3f elbow, Vector3f palm, String side) {
            // The proximal sleeve intentionally joins the chest. The remaining arm and
            // the complete forearm must stay outside the torso and the real head frame.
            float cost = contact(shoulder,elbow,palm);
            var upper = body.transformDirection(new Vector3f(elbow).sub(shoulder).normalize());
            float inward = (side.equals("right") ? -1 : 1) * upper.x;
            cost += 120 * square(java.lang.Math.max(0, -inward - .12f));
            if(carry && side.equals("right")) {
                // The rear arm hangs back beside the ribs; its forearm brings the
                // palm forwards to the shaft without raising the upper arm.
                cost += 80000 * square(java.lang.Math.max(0, upper.z + .65f));
                cost += 100 * square(java.lang.Math.max(0, -upper.z - .80f));
                cost += 160 * square(java.lang.Math.max(0, upper.y + .35f));
                cost += 40000 * square(java.lang.Math.max(0, -upper.y - .50f));
            } else if(!tucked)cost += 160 * square(java.lang.Math.max(0, -upper.z - .25f));
            cost += 100 * square(java.lang.Math.max(0, upper.y - .5f));
            cost += 25 * square(java.lang.Math.max(0, upper.y));
            return cost;
        }
        float contact(Vector3f shoulder,Vector3f elbow,Vector3f palm) {
            return segment(shoulder,elbow,10+margin,.32f,false)+segment(elbow,palm,8.5f+margin,0,false);
        }
        float deepArmContact(Vector3f shoulder,Vector3f elbow,Vector3f palm) {
            // Preserve small sleeve motion at the conservative outer shell, while
            // strongly rejecting a limb centerline entering the chest or head.
            return segment(shoulder,elbow,3,.32f,false)+segment(elbow,palm,3,0,false);
        }
        float shaft(Vector3f rear, Vector3f shaft) {
            return segment(new Vector3f(rear).fma(-60, shaft), new Vector3f(rear).fma(178, shaft), 2 + margin, 0, true);
        }
        /** The complete pole must stay outside both rendered boxes, including between scoring samples. */
        boolean shaftClear(Vector3f rear,Vector3f shaft) {
            var from=new Vector3f(rear).fma(-60,shaft);var to=new Vector3f(rear).fma(178,shaft);
            // Leave a small allowance for the final quaternion/Euler round trip.
            float radius=2+margin+(transported?.05f:0);
            return !crossesBox(body,from,to,25,75,12.5f,radius)&&!crossesBox(head,from,to,25,50,25,radius)
                    &&segmentDistanceSquared(from,to,waist,lumbar)>square(waistRadius+radius);
        }
        private static float segmentDistanceSquared(Vector3f p,Vector3f q,Vector3f a,Vector3f b) {
            var u=new Vector3f(q).sub(p);var v=new Vector3f(b).sub(a);var w=new Vector3f(p).sub(a);
            float uu=u.lengthSquared(),vv=v.lengthSquared(),uv=u.dot(v),uw=u.dot(w),vw=v.dot(w);
            float denominator=uu*vv-uv*uv;
            float s=denominator>1e-5f?java.lang.Math.clamp((uv*vw-vv*uw)/denominator,0,1):0;
            float t=(uv*s+vw)/vv;
            if(t<0){t=0;s=java.lang.Math.clamp(-uw/uu,0,1);}
            else if(t>1){t=1;s=java.lang.Math.clamp((uv-uw)/uu,0,1);}
            return w.fma(s,u).fma(-t,v).lengthSquared();
        }
        boolean shaftInFront(Vector3f shaft) { return facing.x*shaft.x+facing.z*shaft.z>.01f; }
        private static boolean crossesBox(Matrix4f inverse,Vector3f from,Vector3f to,float halfX,float highY,float halfZ,float radius) {
            return crossesBox(inverse.transformPosition(new Vector3f(from)),inverse.transformPosition(new Vector3f(to)),halfX,highY,halfZ,radius);
        }
        private static boolean crossesBox(Vector3f a,Vector3f to,float halfX,float highY,float halfZ,float radius) {
            var d=new Vector3f(to).sub(a);
            float low=0,high=1;
            for(int axis=0;axis<3;axis++) {
                float minimum=axis==1?-radius:-(axis==0?halfX:halfZ)-radius;
                float maximum=(axis==0?halfX:axis==1?highY:halfZ)+radius;
                float origin=a.get(axis),delta=d.get(axis);
                if(java.lang.Math.abs(delta)<.000001f) {if(origin<minimum||origin>maximum)return false;}
                else {
                    float first=(minimum-origin)/delta,last=(maximum-origin)/delta;
                    if(first>last){float swap=first;first=last;last=swap;}
                    low=java.lang.Math.max(low,first);high=java.lang.Math.min(high,last);
                    if(low>high)return false;
                }
            }
            return true;
        }
        private float segment(Vector3f from, Vector3f to, float radius, float start, boolean weapon) {
            float cost = 0, worst = 0;
            int count = weapon ? 48 : 10;
            Vector3f chestFrom=body.transformPosition(new Vector3f(from)),chestTo=body.transformPosition(new Vector3f(to));
            Vector3f headFrom=head.transformPosition(new Vector3f(from)),headTo=head.transformPosition(new Vector3f(to));
            // The expanded box encloses the rounded distance field. A segment
            // outside it has exactly zero sampled penetration, so skip its scan.
            boolean chestNear=crossesBox(new Vector3f(chestFrom).lerp(chestTo,start),chestTo,25,75,12.5f,radius);
            boolean headNear=crossesBox(new Vector3f(headFrom).lerp(headTo,start),headTo,25,50,25,radius);
            if(!chestNear&&!headNear)return 0;
            for (int n = 0; n <= count; n++) {
                float t = start + (1 - start) * n / count;
                float chest = chestNear?penetrationSquared(chestFrom.x+(chestTo.x-chestFrom.x)*t,chestFrom.y+(chestTo.y-chestFrom.y)*t,
                        chestFrom.z+(chestTo.z-chestFrom.z)*t,25,75,12.5f,radius):0;
                float skull = headNear?penetrationSquared(headFrom.x+(headTo.x-headFrom.x)*t,headFrom.y+(headTo.y-headFrom.y)*t,
                        headFrom.z+(headTo.z-headFrom.z)*t,25,50,25,radius):0;
                float contact=chest+skull;
                cost += contact;worst=java.lang.Math.max(worst,contact);
            }
            return 2*worst+cost / (count + 1);
        }
        private static float penetrationSquared(float x,float y,float z,float halfX,float highY,float halfZ,float radius) {
            float dx=java.lang.Math.abs(x)-halfX,dy=java.lang.Math.abs(y-highY/2)-highY/2,dz=java.lang.Math.abs(z)-halfZ;
            if(dx>=radius||dy>=radius||dz>=radius)return 0;
            float distanceSquared=square(java.lang.Math.max(0,dx))+square(java.lang.Math.max(0,dy))+square(java.lang.Math.max(0,dz));
            if(distanceSquared>=radius*radius)return 0;
            float depth=radius-((float)java.lang.Math.sqrt(distanceSquared)+java.lang.Math.min(0,java.lang.Math.max(dx,java.lang.Math.max(dy,dz))));
            return square(java.lang.Math.max(0,depth));
        }
    }
    record History(Vector3f upper,Quaternionf arm,Quaternionf fore,Quaternionf hand,
                   Quaternionf worldArm,Quaternionf worldFore,Quaternionf worldHand) {}
    /** Signed Euclidean distance to the rendered stock torso/head box, negative inside. */
    static float clearance(Vector3f point, float halfX, float lowY, float highY, float halfZ) {
        return clearance(point.x,point.y,point.z,halfX,lowY,highY,halfZ);
    }
    private static float clearance(float x,float y,float z,float halfX,float lowY,float highY,float halfZ) {
        float dx = java.lang.Math.abs(x) - halfX;
        float dy = java.lang.Math.abs(y - (lowY + highY) / 2) - (highY - lowY) / 2;
        float dz = java.lang.Math.abs(z) - halfZ;
        return (float)java.lang.Math.sqrt(square(java.lang.Math.max(0, dx)) + square(java.lang.Math.max(0, dy))
                + square(java.lang.Math.max(0, dz))) + java.lang.Math.min(0, java.lang.Math.max(dx, java.lang.Math.max(dy, dz)));
    }
    static float square(float value) { return value * value; }
}

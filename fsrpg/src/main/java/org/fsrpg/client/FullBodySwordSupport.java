package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Bounded visual support correction after authoritative sword travel is clipped.
 * All distances are model centimetres. The entity never moves in this helper.
 * The receipt's actual/expected advance must refer to the same attack sequence
 * and its fixed forward frame. Reset after a discontinuous world/heading change.
 */
public final class FullBodySwordSupport {
    private FullBodySwordSupport() {}
    public static final class State {
        private long sequence=Long.MIN_VALUE;
        private double previous=Double.NaN;
        private float scale=1;
        private final boolean[] planted=new boolean[2];
        private final float[] anchor=new float[2];
        private Map<String,Pose> last;
        private boolean applied;

        public void reset() {sequence=Long.MIN_VALUE;previous=Double.NaN;last=null;planted[0]=planted[1]=false;scale=1;applied=false;}
        public float supportScale() {return scale;}

        /** Observe normal, unmodified frames before the first collision. This
         * keeps the already planted feet's action-start-frame anchors available
         * when clipping first occurs; no pose, IK, or resource state is changed.
         * If apply already ran at this timestamp, observe is a harmless no-op.
         */
        public void observe(ModelDocument model,Map<String,Pose> source,double renderTick,long actionSequence,
                            double expectedAdvanceCm,double actualAdvanceCm) {
            if(!Double.isFinite(renderTick)||!Double.isFinite(expectedAdvanceCm)||!Double.isFinite(actualAdvanceCm))return;
            boolean changed=sequence!=actionSequence||!Double.isFinite(previous)||renderTick<previous||renderTick-previous>5;
            if(changed){sequence=actionSequence;previous=Double.NaN;scale=1;planted[0]=planted[1]=false;last=null;applied=false;}
            if(renderTick==previous&&last!=null)return;
            var world=model.worldMatrices(source);
            if(!world.containsKey("right_foot")||!world.containsKey("left_foot"))return;
            for(int i=0;i<2;i++){
                var ankle=world.get((i==0?"right":"left")+"_foot").getTranslation(new Vector3f());
                boolean ground=ankle.y-12.5f<.15f;
                if(ground&&!planted[i])anchor[i]=ankle.z+(float)actualAdvanceCm;
                planted[i]=ground;
            }
            previous=renderTick;last=source;applied=false;
        }

        /** Invoke once per actual presentation timestamp, before skin retargeting.
         * Pass fresh receipts only: no receipt / READY means reset and skip this
         * method. A repeated timestamp returns the exact same cached pose.
         */
        public Map<String,Pose> apply(ModelDocument model,Map<String,Pose> source,double renderTick,long actionSequence,
                                      double expectedAdvanceCm,double actualAdvanceCm,double travelFraction) {
            if(!Double.isFinite(renderTick)||!Double.isFinite(expectedAdvanceCm)||!Double.isFinite(actualAdvanceCm)
                    ||!Double.isFinite(travelFraction))return source;
            boolean changed=sequence!=actionSequence||!Double.isFinite(previous)||renderTick<previous||renderTick-previous>5;
            if(changed){sequence=actionSequence;previous=renderTick;scale=1;planted[0]=planted[1]=false;last=null;applied=false;}
            else if(renderTick==previous&&last!=null&&applied)return last;
            float target=(float)Math.clamp(travelFraction,0,1);
            if(expectedAdvanceCm>1e-4)target=Math.min(target,(float)Math.clamp(actualAdvanceCm/expectedAdvanceCm,0,1));
            double dt=changed?0:Math.max(0,renderTick-previous);previous=renderTick;
            float weight=(float)(1-Math.exp(-dt/(target<scale?.7:2.0)));
            scale+=(target-scale)*weight;
            var world=model.worldMatrices(source);
            if(!world.keySet().containsAll(java.util.Set.of("body","hip","root","right_leg","right_foot","left_leg","left_foot")))return source;
            var waist=world.get("body").transformPosition(new Vector3f(0,-31.25f,0));
            var desiredWaist=new Vector3f(waist.x*scale,68+(waist.y-68)*scale,waist.z*scale);
            var goals=new Vector3f[2];var hips=new Vector3f[2];
            for(int i=0;i<2;i++){
                String side=i==0?"right":"left";float sign=i==0?-1:1;
                var foot=world.get(side+"_foot").getTranslation(new Vector3f());
                float lift=Math.max(0,foot.y-12.5f)*(float)Math.sqrt(Math.max(0,scale));
                var goal=new Vector3f(sign*16+(foot.x-sign*16)*scale,12.5f+lift,-sign*14+(foot.z+sign*14)*scale);
                boolean ground=lift<.15f;
                if(ground){
                    if(!planted[i])anchor[i]=goal.z+(float)actualAdvanceCm;
                    goal.z=anchor[i]-(float)actualAdvanceCm;goal.y=12.5f;
                }
                planted[i]=ground;
                goals[i]=goal;
                hips[i]=world.get(side+"_leg").getTranslation(new Vector3f()).sub(waist).add(desiredWaist);
            }
            // Keep the shared waist reachable with the unchanged 37.5/25 rig.
            float drop=0;
            for(int i=0;i<2;i++){
                float dx=goals[i].x-hips[i].x,dz=goals[i].z-hips[i].z,horizontal=dx*dx+dz*dz;
                if(horizontal>60*60){
                    float factor=60/(float)Math.sqrt(horizontal);
                    goals[i].x=hips[i].x+dx*factor;goals[i].z=hips[i].z+dz*factor;
                    planted[i]=false;horizontal=3600;
                }
                drop=Math.max(drop,hips[i].y-goals[i].y-(float)Math.sqrt(Math.max(1,61.8f*61.8f-horizontal)));
            }
            drop=Math.max(0,drop);desiredWaist.y-=drop;
            var delta=new Vector3f(desiredWaist).sub(waist);
            var result=new HashMap<>(source);
            Pose hip=source.get("hip");
            var rootInverse=new Matrix4f(world.get("root")).invert();
            var localDelta=rootInverse.transformDirection(new Vector3f(delta));
            result.put("hip",new Pose(hip.rotation(),vector(hip.position().vector().add(localDelta)),hip.scale()));
            for(int i=0;i<2;i++){
                String side=i==0?"right":"left";hips[i].y-=drop;
                var matrices=solve(hips[i],goals[i],world.get(side+"_foot"));
                Matrix4f localThigh=new Matrix4f(rootInverse).mul(matrices[0]);
                put(result,model,side+"_leg",localThigh);
                put(result,model,side+"_foreleg",new Matrix4f(matrices[0]).invert().mul(matrices[1]));
                put(result,model,side+"_foot",new Matrix4f(matrices[1]).invert().mul(matrices[2]));
            }
            last=Map.copyOf(result);applied=true;return last;
        }
    }
    private static Matrix4f[] solve(Vector3f hip,Vector3f ankle,Matrix4f sourceFoot) {
        var d=new Vector3f(ankle).sub(hip);float distance=d.length();
        if(distance<13||distance>62.5f)throw new IllegalArgumentException("Unreachable sword support: "+distance);
        var axis=new Vector3f(d).div(distance);
        var pole=new Vector3f(0,0,1).sub(new Vector3f(axis).mul(axis.z));
        if(pole.lengthSquared()<1e-6f)pole.set(1,0,0).sub(new Vector3f(axis).mul(axis.x));pole.normalize();
        float along=(37.5f*37.5f-25*25+distance*distance)/(2*distance);
        var knee=new Vector3f(hip).fma(along,axis).fma((float)Math.sqrt(Math.max(0,37.5f*37.5f-along*along)),pole);
        var a=new Vector3f(knee).sub(hip).normalize();var b=new Vector3f(ankle).sub(knee).normalize();
        var hinge=new Vector3f(a).cross(b).normalize();
        Matrix4f thigh=frame(hinge,new Vector3f(a).negate(),hip),shin=frame(hinge,new Vector3f(b).negate(),knee);
        var footRotation=sourceFoot.getUnnormalizedRotation(new Quaternionf()).normalize();
        Matrix4f foot=new Matrix4f().translationRotate(ankle,footRotation);
        return new Matrix4f[]{thigh,shin,foot};
    }
    private static Matrix4f frame(Vector3f x,Vector3f y,Vector3f at) {
        var z=new Vector3f(x).cross(y).normalize();
        var rotation=new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0,x).setColumn(1,y).setColumn(2,z));
        return new Matrix4f().translationRotate(at,rotation);
    }
    private static V vector(Vector3f v){return new V(v.x,v.y,v.z);}
    private static void put(Map<String,Pose> poses,ModelDocument model,String bone,Matrix4f local) {
        var q=local.getUnnormalizedRotation(new Quaternionf()).normalize();
        // sampleLocal already resolved delta keys against rest transforms.
        // Runtime Pose values are complete local transforms, not file deltas.
        var p=local.getTranslation(new Vector3f());
        poses.put(bone,new Pose(FsvoxAnimation.fromQuaternion(q),vector(p),new V(1,1,1)));
    }
}

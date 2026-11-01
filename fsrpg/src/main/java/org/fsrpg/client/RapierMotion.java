package org.fsrpg.client;

import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.joml.Matrix4f;

/** Continuous fencing clock and one bounded waist response for reaction plus actual travel. */
public final class RapierMotion {
    public static final float REACTION_DEGREES=3,MAXIMUM_COMBINED_DEGREES=5;
    public record Lean(float lateral,float forward) {}
    private RapierMotion(){}
    public static boolean cut(Clip clip){return clip!=null&&(clip.tags().contains("rapier_rework:084")||fencing(clip));}
    public static boolean flow(Clip clip){return clip!=null&&clip.tags().contains("rapier_fencing:0100");}
    public static boolean fencing(Clip clip){return clip!=null&&(clip.tags().contains("rapier_fencing:085")||flow(clip));}
    public static String phrase(Clip clip){return clip.tags().stream().filter(t->t.startsWith("rapier_phrase:")).map(t->t.substring(14)).findFirst().orElse("");}
    /** A branch starts on the last presented joints and takes their shortest rotation arc.
     * The target keeps its combat clock; no new-clip sample is mistaken for outgoing velocity. */
    public static java.util.Map<String,org.fsvoxel.animation.FsvoxAnimation.Pose> bridge(
            java.util.Map<String,org.fsvoxel.animation.FsvoxAnimation.Pose> from,
            java.util.Map<String,org.fsvoxel.animation.FsvoxAnimation.Pose> authored,float weight){
        if(from==null||weight>=1)return authored;
        if(weight<=0)return from;
        return PresentationMotion.blend(from,authored,weight);
    }
    /** Direct queued handoffs meet at ACTIVE end; late clicks bridge from their actual resting pose. */
    public static boolean directHandoff(Clip previous,Clip next,double elapsed,double windup,double active){
        if(!fencing(previous)||!fencing(next))return false;
        if(flow(previous)!=flow(next))return false;
        var phrases=flow(previous)?java.util.List.of("direct-thrust","rising-cut","diagonal-cut","return-cut","lunge-thrust")
                :java.util.List.of("direct-thrust","disengage-thrust","opposition-thrust","short-return-cut","lunge-thrust");
        int index=phrases.indexOf(phrase(previous));
        return index>=0&&phrases.get((index+1)%phrases.size()).equals(phrase(next))
                &&elapsed>=windup+active-.6&&elapsed<=windup+active+.6;
    }
    /** Piecewise monotone Hermite preserves velocity through the authoritative phase boundaries. */
    public static double clipTime(Clip clip,double elapsed,double windup,double active,double recovery){
        double[] ticks={0,Math.max(0,windup),Math.max(0,windup)+Math.max(0,active),Math.max(0,windup)+Math.max(0,active)+Math.max(0,recovery)};
        double[] phase={0,.26,.86,1};double total=ticks[3];if(total<=0)return clip.duration();double t=Math.clamp(elapsed,0,total);
        int i=t<ticks[1]?0:t<ticks[2]?1:2;double span=ticks[i+1]-ticks[i];if(span<=0)return phase[i+1]*clip.duration();
        double first=i==0?(phase[1]-phase[0])/Math.max(1e-6,ticks[1]):slope(ticks,phase,i);
        double last=i==2?0:slope(ticks,phase,i+1);double u=(t-ticks[i])/span;
        double value=(2*u*u*u-3*u*u+1)*phase[i]+(u*u*u-2*u*u+u)*span*first+(-2*u*u*u+3*u*u)*phase[i+1]+(u*u*u-u*u)*span*last;
        return Math.clamp(value,phase[i],phase[i+1])*clip.duration();
    }
    private static double slope(double[] ticks,double[] phase,int index){
        double a=(phase[index]-phase[index-1])/Math.max(1e-6,ticks[index]-ticks[index-1]),b=(phase[index+1]-phase[index])/Math.max(1e-6,ticks[index+1]-ticks[index]);
        return Math.min(3*Math.min(a,b),2*a*b/Math.max(1e-9,a+b));
    }
    public static float reaction(Clip clip,double seconds){
        if(!cut(clip))return 0;int direction=clip.tags().contains("rapier_recoil:1")?1:-1;
        double t=Math.clamp(seconds/clip.duration(),0,1),u=Math.clamp((t-.12)/.88,0,1);
        return (float)(direction*REACTION_DEGREES*Math.pow(Math.sin(Math.PI*u),2));
    }
    /** Small reaction accompanies the authored shoulder/hip drive without replacing it. */
    public static Lean recoil(Clip clip,double seconds){
        if(!fencing(clip))return new Lean(reaction(clip,seconds),0);
        float envelope=Math.abs(reaction(clip,seconds))/REACTION_DEGREES;
        return switch(phrase(clip)){
            case "direct-thrust"->new Lean(-.35f*envelope,-.8f*envelope);
            case "disengage-thrust"->new Lean(.7f*envelope,-.7f*envelope);
            case "opposition-thrust"->new Lean(-.7f*envelope,-.7f*envelope);
            case "short-return-cut"->new Lean(1.8f*envelope,-.15f*envelope);
            case "rising-cut"->new Lean(-1.15f*envelope,-.35f*envelope);
            case "diagonal-cut"->new Lean(1.3f*envelope,.3f*envelope);
            case "return-cut"->new Lean(-1.35f*envelope,-.15f*envelope);
            case "charged-thrust","riposte","reprise"->new Lean(-.3f*envelope,-.9f*envelope);
            case "lunge-thrust"->new Lean(-.3f*envelope,-.9f*envelope);
            default->new Lean(0,0);
        };
    }
    /** The same distance-driven phase as the feet: no idle rocking or clock reset at a new attack.
     *  This is fed through the persistent waist response together with actual travel and recoil. */
    public static Lean gait(float phase,float moving,float speed,float strength){
        float weight=Math.clamp(moving,0,1)*Math.clamp(speed/.16f,0,1)*Math.clamp(strength,0,1);
        double cycle=phase*.6662;
        return new Lean((float)(Math.sin(cycle)*.65*weight),(float)(Math.sin(cycle*2)*.18*weight));
    }
    public static Lean add(Lean first,Lean second){
        return new Lean(first.lateral()+second.lateral(),first.forward()+second.forward());
    }
    public static Lean combine(float lateral,float forward,float movementDegrees,float movementWeight,float reaction){
        return combine(lateral,forward,movementDegrees,movementWeight,new Lean(reaction,0));
    }
    public static Lean combine(float lateral,float forward,float movementDegrees,float movementWeight,Lean reaction){
        double x=lateral*Math.clamp(movementDegrees,0,SwordMotion.MAXIMUM_MOVEMENT_LEAN)*movementWeight+Math.clamp(reaction.lateral(),-REACTION_DEGREES,REACTION_DEGREES),z=forward*Math.clamp(movementDegrees,0,SwordMotion.MAXIMUM_MOVEMENT_LEAN)*movementWeight+Math.clamp(reaction.forward(),-REACTION_DEGREES,REACTION_DEGREES);
        double length=Math.hypot(x,z),scale=length>MAXIMUM_COMBINED_DEGREES?MAXIMUM_COMBINED_DEGREES/length:1;
        return new Lean((float)(x*scale),(float)(z*scale));
    }
    /** Authored sole targets feed the existing two-bone support solve; hips are never moved apart. */
    public static Matrix4f fencingFoot(Matrix4f source,boolean left,Clip clip,double seconds,float moving){
        if(source==null||clip==null||!(fencing(clip)||clip.tags().contains("rapier_guard:085")||clip.tags().contains("rapier_guard:0100")))return source;
        double t=Math.clamp(seconds/clip.duration(),0,1),reach=0;
        if(fencing(clip)){
            double enter=Math.clamp((t-.26)/.3,0,1),leave=1-Math.clamp((t-.56)/.3,0,1);
            double envelope=enter*enter*(3-2*enter)*leave*leave*(3-2*leave);
            reach=(phrase(clip).equals("lunge-thrust")?22:3)*envelope;
        }
        float still=1-Math.clamp(moving,0,1),z=(float)((left?-8-reach*.18:8+reach)*still*still);
        return new Matrix4f().translation(0,0,z).mul(source);
    }
    /** This state survives combo bindings and never integrates twice for one presentation timestamp. */
    public static final class State {
        private double previous=Double.NaN;private Lean lean=new Lean(0,0);
        public Lean advance(double tick,Lean target,double responseTicks){
            if(Double.isFinite(previous)&&tick<=previous)return lean;
            double dt=Double.isFinite(previous)?Math.min(5,tick-previous):0;previous=tick;
            double alpha=1-Math.exp(-dt/Math.max(.1,responseTicks));
            lean=new Lean((float)(lean.lateral()+(target.lateral()-lean.lateral())*alpha),(float)(lean.forward()+(target.forward()-lean.forward())*alpha));return lean;
        }
        public Lean lean(){return lean;}
    }
    public static PresentationMotion.Balance balance(Lean lean,float maximumCm){
        float amount=Math.min(2,Math.max(0,maximumCm)),x=lean.lateral()/MAXIMUM_COMBINED_DEGREES*amount,z=lean.forward()/MAXIMUM_COMBINED_DEGREES*amount;
        return new PresentationMotion.Balance(x,(float)Math.sqrt(Math.max(0,75*75-x*x-z*z))-75,z);
    }
}

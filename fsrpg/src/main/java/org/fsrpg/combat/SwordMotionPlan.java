package org.fsrpg.combat;

/** Pure, bounded action displacement. The adapter must collide each returned delta, never teleport through obstacles. */
public final class SwordMotionPlan {
    private final long sequence;
    private final double x,z,distance,steering;
    private double progress,actualAdvance;
    public SwordMotionPlan(long sequence,double yawDegrees,double distance,double steering){
        SkillDefinition.checkNumber(yawDegrees,"motion yaw",-Double.MAX_VALUE,Double.MAX_VALUE);
        SkillDefinition.checkNumber(distance,"motion distance",0,3);
        SkillDefinition.checkNumber(steering,"motion steering",0,.35);
        this.sequence=sequence;this.distance=distance;this.steering=steering;
        double a=Math.toRadians(yawDegrees);x=-Math.sin(a);z=Math.cos(a);
    }
    public long sequence(){return sequence;}
    public double expectedAdvance(){return progress*distance;}
    public double actualAdvance(){return actualAdvance;}
    /** Accumulate only this adapter move, projected onto the accepted action heading. */
    public void recordActual(double deltaX,double deltaZ){if(!Double.isFinite(deltaX)||!Double.isFinite(deltaZ))throw new IllegalArgumentException("nonfinite actual sword motion");actualAdvance=Math.clamp(actualAdvance+deltaX*x+deltaZ*z,0,distance);}
    public record Step(double x,double z){public static Step zero(){return new Step(0,0);}}
    public Step advance(double nextProgress,double intentX,double intentZ){
        if(!Double.isFinite(nextProgress)||!Double.isFinite(intentX)||!Double.isFinite(intentZ))throw new IllegalArgumentException("nonfinite sword movement");
        double next=Math.clamp(nextProgress,progress,1),delta=(next-progress)*distance;progress=next;
        double length=Math.hypot(intentX,intentZ);if(length>1){intentX/=length;intentZ/=length;}
        // Steering changes the direction within a small cone; it never increases the travel budget.
        double dx=x+intentX*steering,dz=z+intentZ*steering,n=Math.hypot(dx,dz);
        return n<1e-9?Step.zero():new Step(dx/n*delta,dz/n*delta);
    }
    public static double progress(int elapsed,SkillDefinition skill){
        int w=skill.windupTicks(),a=skill.activeTicks(),r=skill.recoveryTicks();
        if(elapsed<=w&&w>0)return .2*smooth(elapsed/(double)w);
        if(elapsed<=w+a)return .2+.65*smooth((elapsed-w)/(double)a);
        return r==0?1:.85+.15*smooth((elapsed-w-a)/(double)r);
    }
    private static double smooth(double x){x=Math.clamp(x,0,1);return x*x*(3-2*x);}
}

package org.fsrpg.client;

/** Render-time camera travel; entering and leaving finish in the same fixed, reversible half-second envelope. */
public final class ShoulderCameraMotion {
    public record Offset(double x,double y,double z) {
        public Offset {
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("non-finite camera offset");
        }
        Offset lerp(Offset target,double weight){return new Offset(x+(target.x-x)*weight,y+(target.y-y)*weight,z+(target.z-z)*weight);}
        Offset scale(double factor){return new Offset(x*factor,y*factor,z*factor);}
    }
    private boolean initialized,target;
    private double start,last,from,blend;
    private Offset full;

    public Offset sample(boolean enabled,double now,Offset desired,double transitionSeconds,double responseSeconds) {
        if(!Double.isFinite(now)||!Double.isFinite(transitionSeconds)||transitionSeconds<=0
                ||!Double.isFinite(responseSeconds)||responseSeconds<=0||desired==null)throw new IllegalArgumentException("invalid camera clock/settings");
        if(!initialized||now<last){reset();initialized=true;start=last=now;full=desired;}
        blend=value(now,transitionSeconds);
        if(enabled!=target){from=blend;target=enabled;start=now;}
        double dt=Math.clamp(now-last,0,1);last=now;
        full=full.lerp(desired,1-Math.exp(-dt/responseSeconds));
        blend=value(now,transitionSeconds);
        return full.scale(blend);
    }
    private double value(double now,double duration){
        double t=Math.clamp((now-start)/duration,0,1),ease=t*t*(3-2*t);
        return from+((target?1:0)-from)*ease;
    }
    public double blend(){return blend;}
    public boolean active(){return target||blend>1e-7;}
    public boolean settledOff(){return !target&&blend<=1e-7;}
    public void reset(){initialized=target=false;start=last=from=blend=0;full=null;}
}

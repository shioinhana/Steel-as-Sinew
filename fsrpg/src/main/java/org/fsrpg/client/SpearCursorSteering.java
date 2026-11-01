package org.fsrpg.client;

/** A floating spear cursor spends its deflection on smooth camera rotation, then returns to center. */
public final class SpearCursorSteering {
    public record Settings(double yawRange,double pitchRange,double centerSpeed,double edgeSpeed,double edgeStart,double responseSeconds) {
        public Settings {
            if(!Double.isFinite(yawRange)||!Double.isFinite(pitchRange)||!Double.isFinite(centerSpeed)||!Double.isFinite(edgeSpeed)
                    ||!Double.isFinite(edgeStart)||!Double.isFinite(responseSeconds)||yawRange<=0||pitchRange<=0
                    ||centerSpeed<=0||edgeSpeed<0||edgeStart<=0||edgeStart>=1||responseSeconds<=0)
                throw new IllegalArgumentException("Invalid spear cursor steering settings");
        }
    }
    public record Step(double yaw,double pitch) {}
    private double x,y,vx,vy,last=Double.NaN;
    public double x(){return x;}
    public double y(){return y;}
    public void move(double dx,double dy,double width,double height){
        if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0)
            throw new IllegalArgumentException("Expected finite mouse deltas and positive viewport");
        x=Math.clamp(x+2*dx/width,-1,1);y=Math.clamp(y+2*dy/height,-1,1);
        if(x*vx<=0)vx=0;if(y*vy<=0)vy=0;
    }
    /** Monotonic wall time, with bounded catch-up and small integration steps independent of render frequency. */
    public Step advance(double now,double cameraPitch,double pitchLimit,Settings settings){
        if(!Double.isFinite(now)||!Double.isFinite(cameraPitch)||!Double.isFinite(pitchLimit)||pitchLimit<=0)
            throw new IllegalArgumentException("Invalid steering clock or camera pitch");
        if(Double.isNaN(last)||now<last){last=now;vx=vy=0;return new Step(0,0);}
        double elapsed=Math.min(.1,now-last);last=now;
        if(elapsed<=0)return new Step(0,0);
        int steps=Math.max(1,(int)Math.ceil(elapsed*240));double dt=elapsed/steps,turnX=0,turnY=0;
        for(int i=0;i<steps;i++){
            double blend=1-Math.exp(-dt/settings.responseSeconds());
            vx+=(speed(x,settings)-vx)*blend;vy+=(speed(y,settings)-vy)*blend;
            if(x*vx<=0)vx=0;if(y*vy<=0)vy=0;
            double yaw=consume(x,vx*dt,settings.yawRange());
            double proposed=consume(y,vy*dt,settings.pitchRange());
            double pitch=Math.clamp(cameraPitch+turnY+proposed,-pitchLimit,pitchLimit)-(cameraPitch+turnY);
            if(Math.abs(pitch-proposed)>1e-9)vy=0;
            x-=yaw/settings.yawRange();y-=pitch/settings.pitchRange();
            turnX+=yaw;turnY+=pitch;
            if(Math.abs(x)<.0001){x=0;vx=0;}if(Math.abs(y)<.0001){y=0;vy=0;}
        }
        return new Step(turnX,turnY);
    }
    public static double speed(double offset,Settings settings){
        double amount=Math.clamp(Math.abs(offset),0,1),edge=Math.clamp((amount-settings.edgeStart())/(1-settings.edgeStart()),0,1);
        double ease=edge*edge*(3-2*edge);
        return Math.copySign(settings.centerSpeed()*amount+settings.edgeSpeed()*ease*ease,offset);
    }
    private static double consume(double offset,double distance,double range){return Math.copySign(Math.min(Math.abs(distance),Math.abs(offset)*range),offset);}
    /** GUI, pause, focus loss and server corrections may never retain deferred turning input. */
    public void reset(){x=y=vx=vy=0;last=Double.NaN;}
}

package org.fsrpg.client;

/** Match one authoritative guard marker to its damage notification, rather than muting all guarding hits. */
public final class GuardHurtMotion {
    private int cause,direct;
    private long markerTick=Long.MIN_VALUE;
    private boolean pending,suppressed;
    public void guarded(int cause,int direct,long tick){
        this.cause=cause;this.direct=direct;markerTick=tick;pending=true;
    }
    public boolean damage(int cause,int direct,long tick){
        suppressed=pending&&tick>=markerTick&&tick-markerTick<=2&&this.cause==cause&&this.direct==direct;
        pending=false;return suppressed;
    }
    public boolean suppressed(){return suppressed;}
    public void cancelCurrentHurt(){suppressed=true;pending=false;}
    public void reset(){pending=suppressed=false;markerTick=Long.MIN_VALUE;}
}

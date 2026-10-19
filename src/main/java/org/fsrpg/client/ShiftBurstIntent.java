package org.fsrpg.client;

import java.util.Locale;

/** Physical Shift owns a single burst edge; renewal retains the original world direction until release. */
public final class ShiftBurstIntent {
    private boolean initialized,held,owned;
    private long lastSent;
    private double x,z;
    public String sample(boolean down,boolean eligible,boolean guarding,long tick,double directionX,double directionZ){
        boolean fresh=initialized&&!held&&down,released=initialized&&held&&!down;
        initialized=true;held=down;
        if(released||!eligible||guarding){if(owned){owned=false;return "up";}return null;}
        if(!down)return null;
        if(fresh){
            double length=Math.hypot(directionX,directionZ);
            if(!Double.isFinite(length)||length<1e-7)return null;
            x=directionX/length;z=directionZ/length;owned=true;lastSent=tick;
            return down();
        }
        if(owned&&tick-lastSent>=4){lastSent=tick;return down();}
        return null;
    }
    private String down(){return String.format(Locale.ROOT,"down %.9f %.9f",x,z);}
    public boolean ownsInput(){return owned;}
    public void reset(){initialized=held=owned=false;lastSent=0;x=z=0;}
}

package org.fsrpg.client;

import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;

/** First-person branches inherit the actual wrist pose; canonical queued cuts meet directly. */
public final class RapierViewMotion {
    private Object model;
    private Clip previousClip;
    private long sequence;
    private double previousTick=Double.NaN,elapsed,started,duration;
    private int windup,active;
    private Map<String,Pose> last;
    private Map<String,Pose> from;

    public Map<String,Pose> sample(Object token,Clip clip,long revision,String nextPhase,
                                  int nextWindup,int nextActive,double nextElapsed,double tick,Map<String,Pose> authored){
        if(model!=token||!Double.isFinite(previousTick)||tick<previousTick||tick-previousTick>3){
            model=token;previousClip=null;last=null;from=null;
        } else if(tick==previousTick&&last!=null)return last;
        boolean changed=previousClip!=null&&(!previousClip.id().equals(clip.id())
                ||nextPhase.equals("WINDUP")&&revision!=sequence);
        if(changed&&last!=null){
            boolean joined=RapierMotion.directHandoff(previousClip,clip,elapsed,windup,active);
            duration=nextElapsed>=0?Math.min(1.25,Math.max(0,nextWindup-nextElapsed)*.4):2;
            from=joined||duration<=0?null:last;started=tick;
        }
        var result=RapierMotion.bridge(from,authored,PoseLayers.transitionWeight(tick,started,duration));
        if(from!=null&&tick-started>=duration)from=null;
        previousClip=clip;sequence=revision;windup=nextWindup;active=nextActive;
        elapsed=nextElapsed;previousTick=tick;last=Map.copyOf(result);return last;
    }
}

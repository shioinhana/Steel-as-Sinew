package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;

/** Authored whole-body sword poses. Entity travel is exclusively server-owned. */
public final class FullBodySword {
    public static final String MODEL = "rapier_fullbody.fsvoxel";
    public static final String TAG = "rapier_fullbody:0120";
    public static final String LAYER = "sword_fullbody";
    private FullBodySword() {}
    public static boolean action(Clip clip) { return clip != null && clip.tags().contains(TAG); }
    public static double seconds(Clip clip, String phase, double elapsed, int windup, int active, int recovery, double running) {
        // A held charge is still WINDUP on the server, but is not the thrust's
        // attack timeline. Its static hold must never cycle through that cut.
        if(clip.id().equals("rapier_charge"))return Math.clamp(running,0,clip.duration());
        if(clip.id().equals("rapier_full_charge"))return 0;
        if (elapsed >= 0 && action(clip)) {
            // Foot plants are authored against the server's three movement phases.
            // Hermite-retiming this clock would move the feet away from their collision endpoint.
            double fraction;
            if(elapsed<windup && windup>0)fraction=.26*elapsed/windup;
            else if(elapsed<windup+active && active>0)fraction=.26+.60*(elapsed-windup)/active;
            else fraction=.86+.14*(elapsed-windup-active)/Math.max(1,recovery);
            return Math.clamp(fraction,0,1)*clip.duration();
        }
        return LegacyPlayback.seconds(clip,running);
    }
    /** Head tracking is a bounded additive; the authored pelvis and support legs stay together. */
    public static Map<String,Pose> aimHead(Map<String,Pose> source,float pitch,float yaw) {
        Pose head=source.get("head"); if(head==null)return source;
        var result=new HashMap<>(source);
        var q=head.fsQuaternion().rotateY((float)Math.toRadians(Math.clamp(yaw,-35,35)))
                .rotateX((float)Math.toRadians(-Math.clamp(pitch,-40,40)));
        result.put("head",new Pose(FsvoxAnimation.fromQuaternion(q),head.position(),head.scale()));
        return Map.copyOf(result);
    }
    public static boolean frame(PlayerAnimations.Frame frame) { return frame != null && LAYER.equals(frame.layer()); }
}

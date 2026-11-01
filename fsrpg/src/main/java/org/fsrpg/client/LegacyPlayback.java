package org.fsrpg.client;

import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;

/** Runtime sampling for imported clips; the engine's direct-seek API holds loop endpoints. */
public final class LegacyPlayback {
    private LegacyPlayback() {}

    public static double seconds(Clip clip,double elapsed) {
        if(!Double.isFinite(elapsed))throw new IllegalArgumentException("Non-finite imported playback time");
        double value=Math.max(0,elapsed);
        return clip.loop()?value%clip.duration():Math.min(value,clip.duration());
    }

    public static Map<String,Pose> sample(ModelDocument model,Clip clip,double elapsed) {
        return model.rig().sampleLocal(List.of(clip),seconds(clip,elapsed),Map.of());
    }
}

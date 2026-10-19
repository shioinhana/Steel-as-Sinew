package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Keep an advancing authored upper action attached to its retained airborne pelvis. */
public final class AirPoseJoin {
    private AirPoseJoin() {}

    public static Vector3f waist(ModelDocument model, Map<String,Matrix4f> world) {
        boolean oldCenter=model.rig().id().startsWith("fsrpg.legacy_")&&!SwordMotion.applies(model);
        return world.get("body").transformPosition(new Vector3f(0,oldCenter?-31.25f:0,0));
    }

    /** One shared translation preserves every source upper rotation, chain and grip. */
    public static Map<String,Pose> align(ModelDocument model,Map<String,Pose> current,
                                         Map<String,Matrix4f> currentWorld,Map<String,Matrix4f> takeoffWorld) {
        Vector3f delta=waist(model,takeoffWorld).sub(waist(model,currentWorld));
        var result=new HashMap<>(current);var upper=PoseLayers.masks(model.rig()).get("upper");
        for(String id:upper){
            var part=model.rig().parts().get(id);if(upper.contains(part.parent()))continue;
            Pose pose=current.get(id);if(pose==null)continue;
            Matrix4f parent=currentWorld.get(part.parent());
            Vector3f local=parent==null?new Vector3f(delta):new Matrix4f(parent).invert().transformDirection(new Vector3f(delta));
            V at=pose.position();result.put(id,new Pose(pose.rotation(),new V(at.x()+local.x,at.y()+local.y,at.z()+local.z),pose.scale()));
        }
        return Map.copyOf(result);
    }
}

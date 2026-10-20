package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Attach the unchanged twelve-pixel native torso to the imported dual/bow hips. */
public final class LegacyWaistFit {
    private static final float EPSILON_CM = .0001f;
    private LegacyWaistFit() {}

    public static Map<String, Pose> apply(ModelDocument model, Map<String, Pose> source) {
        String id = model.rig().id();
        if (!id.equals("fsrpg.legacy_dual_blade") && !id.equals("fsrpg.legacy_bow")) return source;
        if (!source.containsKey("body") || !source.containsKey("right_leg") || !source.containsKey("left_leg")) return source;
        Map<String, Matrix4f> world = model.worldMatrices(source);
        Vector3f target = world.get("right_leg").getTranslation(new Vector3f())
                .add(world.get("left_leg").getTranslation(new Vector3f())).mul(.5f);
        Vector3f delta = target.sub(nativeWaist(world.get("body")));
        if (!delta.isFinite() || delta.lengthSquared() <= EPSILON_CM * EPSILON_CM) return source;
        var upper = PoseLayers.masks(model.rig()).get("upper");
        Map<String, Pose> result = new HashMap<>(source);
        for (String part : upper) {
            String parentId = model.rig().parts().get(part).parent();
            if (upper.contains(parentId)) continue;
            Pose old = source.get(part);
            if (old == null) continue;
            Matrix4f parent = world.get(parentId);
            Vector3f local = parent == null ? new Vector3f(delta)
                    : new Matrix4f(parent).invert().transformDirection(new Vector3f(delta));
            V at = old.position();
            result.put(part, new Pose(old.rotation(),
                    new V(at.x() + local.x, at.y() + local.y, at.z() + local.z), old.scale()));
        }
        return Map.copyOf(result);
    }

    /**
     * PlayerAnimations.place transports the source top pivot with scale, but the
     * original skin torso remains twelve native pixels long and uses rotation only.
     * A transformed local -31.25 point is therefore insufficient for scaled rigs.
     */
    static Vector3f nativeWaist(Matrix4f body) {
        Quaternionf rotation = body.getUnnormalizedRotation(new Quaternionf()).normalize();
        return body.transformPosition(new Vector3f(0, 43.75f, 0))
                .add(rotation.transform(new Vector3f(0, -75, 0)));
    }
}

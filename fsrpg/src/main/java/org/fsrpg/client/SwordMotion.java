package org.fsrpg.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The original sword torso and arms, carried by the current supported-foot pipeline. */
public final class SwordMotion {
    public static final float MAXIMUM_MOVEMENT_LEAN = 4;
    /** A fencing step is shorter than a running spear stride; cadence still follows real travel. */
    public static final float MAXIMUM_STRIDE_SCALE = .55f;
    /** Share of the torso's stance yaw the pelvis and legs carry. */
    public static final float STANCE_FOLLOW = .5f;
    private static final V WAIST_REST = new V(0, -75, 0);
    private SwordMotion() {}

    public static boolean applies(ModelDocument model) { return model.rig().id().equals("fsrpg.legacy_rapier"); }
    public static V waistRest() { return WAIST_REST; }
    public static String source(Clip clip) {
        return clip.tags().stream().filter(tag -> tag.startsWith("source:")).map(tag -> tag.substring(7)).findFirst().orElse(clip.id());
    }
    public static boolean fullAction(Clip clip) {
        String name = source(clip);
        return name.equals("draw") || name.equals("put_away") || name.startsWith("rapier_dodge_");
    }

    /** The 0.1.1 static sword pose supplies arms while walking; imported *_upper attacks
     * keep their original timing and runtime hip-yaw cancellation. No old walk/run torso
     * pitch is mixed in. Sampling external clips preserves the user's authored keys. */
    public static Map<String, Pose> sampleUpper(ModelDocument model, Clip action, double seconds,
                                               double clockSeconds, float pitch, float headYaw) {
        var rig = model.rig();
        Clip idle = rig.clips().get("legacy_rapier_static_idle");
        if (idle == null) idle = rig.clips().get("rapier_idle");
        if (idle == null) throw new IllegalArgumentException("Original sword idle is missing");
        Map<String, Pose> pose = new HashMap<>(LegacyPlayback.sample(model, idle, clockSeconds));
        String name = source(action);
        if (!name.equals("static_idle") && !name.equals("idle_upper")) {
            Map<String, Pose> stroke = LegacyPlayback.sample(model, action, seconds);
            Set<String> upper = PoseLayers.masks(rig).get("upper");
            for (String id : action.tracks().keySet())
                if (upper.contains(id) || id.equals("root") || id.equals("hip")) pose.put(id, stroke.get(id));
        }
        if (!fullAction(action)) {
            Pose hip = pose.get("hip");
            float hipYaw = hip == null ? 0 : hip.fsQuaternion().getEulerAnglesZYX(new Vector3f()).y;
            rotate(pose, "body", 0, -hipYaw + Math.toRadians(65), 0);
        }
        rotate(pose, "head", -Math.toRadians(pitch), Math.toRadians(headYaw), 0);
        return Map.copyOf(pose);
    }

    /** Yaw the authored pose gives the hips, in radians; the sword stance used to discard it. */
    public static float authoredHipYaw(Map<String, Pose> source) {
        Pose hip = source.get("hip");
        return hip == null ? 0 : hip.fsQuaternion().getEulerAnglesZYX(new Vector3f()).y;
    }

    /** Rotation the final torso carries around the actor's own forward, in radians. */
    public static float bodyYaw(ModelDocument model, Map<String, Pose> pose) {
        var world = model.worldMatrices(pose);
        Matrix4f body = world.get("body");
        if (body == null) return 0;
        Vector3f forward = body.transformDirection(new Vector3f(0, 0, 1));
        return (float) Math.atan2(forward.x, forward.z);
    }

    /** Move the old torso-center pivot to the waist without changing a single upper
     * world transform. Child offsets compensate the pivot change. Ancestor motion is
     * baked once, so adding a world-space lean does not rotate around the old yawed hip. */
    public static Map<String, Pose> waist(ModelDocument model, Map<String, Pose> source) {
        Map<String, Matrix4f> world = model.worldMatrices(source);
        Matrix4f body = world.get("body");
        if (body == null || !model.rig().parts().containsKey("hip")) return source;
        float pivotShift = 75 - 43.75f;
        Matrix4f waist = new Matrix4f(body).translate(0, -pivotShift, 0);
        Map<String, Pose> pose = new HashMap<>(source);
        Pose rootRest = model.rig().parts().get("root").rest();
        Pose hipRest = model.rig().parts().get("hip").rest();
        pose.put("root", rootRest); pose.put("hip", hipRest);
        Matrix4f parent = model.worldMatrices(pose).get("hip");
        Matrix4f local = new Matrix4f(parent).invert().mul(waist);
        pose.put("body", matrixPose(local));
        for (var part : model.rig().parts().values()) if (part.parent().equals("body")) {
            Pose child = pose.get(part.id());
            if (child != null) pose.put(part.id(), new Pose(child.rotation(),
                    new V(child.position().x(), child.position().y() + pivotShift, child.position().z()), child.scale()));
        }
        return Map.copyOf(pose);
    }

    /** Rotation a yaw about the world up axis puts on a pose, preserving its world height. */
    private static Pose yawed(Pose pose, float yaw, Vector3f pivot) {
        Quaternionf rotation = new Quaternionf().rotationY(yaw);
        Vector3f position = rotation.transform(pose.position().vector().sub(pivot, new Vector3f())).add(pivot);
        return new Pose(FsvoxAnimation.fromQuaternion(rotation.mul(pose.fsQuaternion())),
                new V(position.x, position.y, position.z), pose.scale());
    }

    /** Carry the pelvis and both legs toward the torso's stance by part of its yaw, so the lower
     * body follows the fighting posture instead of staying square to the actor's forward. */
    public static Map<String, Pose> stance(ModelDocument sword, Map<String, Pose> upper,
                                           Map<String, Matrix4f> spearWorld, float yaw) {
        Map<String, Pose> pose = new HashMap<>(upper);
        float offset = yaw * STANCE_FOLLOW;
        var pivot = new Vector3f();
        int feet = 0;
        for (String side : List.of("right", "left")) {
            Matrix4f leg = spearWorld.get(side + "_leg");
            if (leg == null) continue;
            pivot.add(leg.getTranslation(new Vector3f())); feet++;
        }
        if (feet == 0) return Map.copyOf(pose);
        pivot.div(feet);
        for (String side : List.of("right", "left")) {
            Matrix4f leg = spearWorld.get(side + "_leg");
            if (leg != null) pose.put(side + "_leg", yawed(matrixPose(leg), offset, pivot));
            for (String suffix : List.of("_foreleg", "_foot")) {
                var part = sword.rig().parts().get(side + suffix);
                if (part != null) pose.put(part.id(), part.rest());
            }
        }
        return Map.copyOf(pose);
    }

    /** Stance feet are the authored base; movement replaces them through the same support
     * solver, cadence, lanes and upper/full action weights as the spear. */
    public static Map<String, Pose> lower(ModelDocument sword, Map<String, Pose> upper,
                                         Map<String, Matrix4f> spearWorld) {
        return stance(sword, upper, spearWorld, 0);
    }

    private static Pose matrixPose(Matrix4f matrix) {
        Vector3f at = matrix.getTranslation(new Vector3f()), scale = matrix.getScale(new Vector3f());
        Quaternionf rotation = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
        return new Pose(FsvoxAnimation.fromQuaternion(rotation), new V(at.x, at.y, at.z), new V(scale.x, scale.y, scale.z));
    }
    private static void rotate(Map<String, Pose> pose, String id, double x, double y, double z) {
        Pose original = pose.get(id); if (original == null) return;
        Vector3f e = original.fsQuaternion().getEulerAnglesZYX(new Vector3f()).add((float) x, (float) y, (float) z);
        pose.put(id, new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationZYX(e.z, e.y, e.x)),
                original.position(), original.scale()));
    }
}

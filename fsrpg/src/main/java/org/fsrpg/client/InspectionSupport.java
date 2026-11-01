package org.fsrpg.client;

import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Project only an inspection's extra leg yaw into the actual supported skin legs. */
public final class InspectionSupport {
    // The catalogue permits 45 degrees of chest turn; its .28 pelvic share is
    // 12.6 degrees. Leave that complete range while bounding custom pose input.
    public static final float MAXIMUM_TURN_RADIANS = (float)Math.toRadians(15);
    private InspectionSupport() {}

    public static Locomotion.LowerBody apply(Locomotion.LowerBody base, Map<String, Pose> capturedBase,
                                             Map<String, Pose> inspected) {
        if (base == null || capturedBase == null || inspected == null) return base;
        float delta = additionalYaw(capturedBase, inspected);
        if (Math.abs(delta) < .000001f) return base;
        // Hip heading is absolute, whereas each foot receives only this gesture's
        // added turn. Passing base pelvis heading as a foot delta doubles stance.
        return Locomotion.turn(base, new SpearTurn.Feet(base.pelvisYaw() + delta, delta, delta, 0, 0,
                base.right().support(), base.left().support()), 0);
    }

    /** Native/model-part yaw: FS +Y turns become native -Y turns. */
    public static float additionalYaw(Map<String, Pose> capturedBase, Map<String, Pose> inspected) {
        double sine = 0, cosine = 0; int count = 0;
        for (String side : new String[]{"right_leg", "left_leg"}) {
            Pose before = capturedBase.get(side), after = inspected.get(side);
            if (before == null || after == null) continue;
            Quaternionf change = after.fsQuaternion().normalize().mul(before.fsQuaternion().normalize().invert());
            Vector3f forward = change.transform(new Vector3f(0, 0, 1));
            if (!Float.isFinite(forward.x) || !Float.isFinite(forward.z) || Math.hypot(forward.x, forward.z) < .000001) continue;
            double yaw = -Math.atan2(forward.x, forward.z);
            sine += Math.sin(yaw); cosine += Math.cos(yaw); count++;
        }
        if (count == 0) return 0;
        return Math.clamp((float)Math.atan2(sine, cosine), -MAXIMUM_TURN_RADIANS, MAXIMUM_TURN_RADIANS);
    }
}

package org.fsrpg.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.Map;

/**
 * Where the two hands sit on the pole.
 *
 * The imported spear rig was authored with a fixed 58 cm two-hand separation, which makes the
 * whole weapon move as one rigid block: a thrust cannot slide the rear hand forward and a sweep
 * cannot close the hands for leverage. Real pole work does both, so the separation is derived
 * per frame from the authored pose and the aim instead of being a constant.
 *
 * Everything stays inside a band around the authored separation, so the arms never leave their
 * feasible reach and the solver's longitudinal search keeps a non-empty interval.
 */
public final class SpearGrip {
    /** The separation the imported rig and the existing configuration are built around. */
    public static final float RESTING_CM = 58;
    public static final float MINIMUM_CM = 44;
    public static final float MAXIMUM_CM = 70;

    private SpearGrip() {}

    /**
     * Authored separation of the two palms for this frame, or the resting value when the rig
     * does not expose both hands.
     */
    public static float authored(Map<String, Matrix4f> source) {
        var world = source;
        var right = palm(world.get("right_hand"));
        var left = palm(world.get("left_hand"));
        if (right == null || left == null) return RESTING_CM;
        float separation = right.distance(left);
        return Float.isFinite(separation) && separation > 1 ? clamp(separation) : RESTING_CM;
    }

    /**
     * Separation to solve with. The authored value is the base; a thrust slides the grip open
     * toward the target and a sweep closes it for leverage, both scaled by how far the hands
     * must travel, so a stationary carry is unaffected.
     */
    public static float solve(float authoredCm, float aimTravelCm, float authoredMotionCm) {
        float travel = Math.max(0, aimTravelCm) + Math.max(0, authoredMotionCm);
        float drive = (float) Math.tanh(travel / 45f);
        return clamp(authoredCm + (MAXIMUM_CM - authoredCm) * drive * .65f);
    }

    public static float clamp(float spacingCm) {
        if (!Float.isFinite(spacingCm)) return RESTING_CM;
        return Math.clamp(spacingCm, MINIMUM_CM, MAXIMUM_CM);
    }

    private static Vector3f palm(Matrix4f hand) {
        return hand == null ? null : hand.transformPosition(new Vector3f(0, -6.25f, 0));
    }
}

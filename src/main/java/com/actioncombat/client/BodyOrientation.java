package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Combat facing follows the aim; independent gait clips carry side/back steps.
 * Turning the whole model toward travel would turn a strafe into a forward walk.
 */
public final class BodyOrientation {
    /** Smoothed body yaw in degrees, or null until the first update seeds it. */
    private Float bodyYaw;

    /**
     * @param player      the mirrored player
     * @param cameraYaw   the player's yRot, i.e. where the camera looks
     * @param faceCamera  true while attacking/guarding: turn to the aim direction
     * @param deltaSeconds real time since the previous update
     * @return the yaw the body should render at
     */
    public float update(Player player, float cameraYaw, boolean faceCamera, float deltaSeconds) {
        if (faceCamera) {
            // Attacks must snap onto the look direction the same frame, otherwise the
            // swing plays while the body is still turning from a strafe heading.
            bodyYaw = cameraYaw;
            return cameraYaw;
        }

        if (bodyYaw == null) {
            bodyYaw = cameraYaw;
            return cameraYaw;
        }

        float delta = Mth.degreesDifference(bodyYaw, cameraYaw);
        float maxStep = CombatConstants.BODY_TURN_DEGREES_PER_SECOND * deltaSeconds;
        bodyYaw = Mth.wrapDegrees(bodyYaw + Mth.clamp(delta, -maxStep, maxStep));
        return bodyYaw;
    }

    /** Drop the smoothing state so the next update snaps (used when re-entering combat). */
    public void reset(float yaw) {
        bodyYaw = yaw;
    }

    public float bodyYaw(float fallback) {
        return bodyYaw != null ? bodyYaw : fallback;
    }
}

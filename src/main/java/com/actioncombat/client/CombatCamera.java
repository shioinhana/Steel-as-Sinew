package com.actioncombat.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Drawn-combat camera, always Genshin-style third-person (centered behind the
 * player, never over-the-shoulder).
 * <p>
 * The mouse always orbits the camera — including mid-swing. Earlier the swing
 * "lock" handed the mouse back to vanilla {@code player.turn}, which dragged the
 * camera with the body; that forced camera turn is removed.
 */
public final class CombatCamera {
    private static float yaw;
    private static float pitch;
    private static boolean replacePosition;

    private CombatCamera() {
    }

    public static void seedFrom(Player player) {
        yaw = player.getYRot();
        pitch = player.getXRot();
        replacePosition = false;
    }

    public static void reset() {
        replacePosition = false;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static boolean shouldReplacePosition() {
        return replacePosition;
    }

    /**
     * @return true if vanilla {@code player.turn} should be skipped
     * (the mouse always belongs to the orbit camera while drawn).
     */
    public static boolean handleMouseTurn(double yawDelta, double pitchDelta) {
        if (!ClientCombat.isLocalDrawn()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return false;
        if (mc.options.getCameraType().isFirstPerson()) return false;
        yaw += (float) yawDelta * 0.15F;
        pitch = Mth.clamp(pitch + (float) pitchDelta * 0.15F, -90.0F, 90.0F);
        yaw = Mth.wrapDegrees(yaw);
        return true;
    }

    /**
     * Called from {@code CameraMixin} at the end of {@code Camera.setup}.
     * First person tracks the player rotation; third person keeps the orbit.
     */
    public static void prepare(Camera camera) {
        replacePosition = false;
        if (!ClientCombat.isLocalDrawn()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (camera.entity() != player) return;

        if (mc.options.getCameraType().isFirstPerson()) {
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
    }
}

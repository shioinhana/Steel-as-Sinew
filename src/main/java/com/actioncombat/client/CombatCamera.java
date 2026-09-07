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
 * Unlocked: mouse orbits the camera; the body yaw is copied from this camera so
 * WASD, jump and dodge share the same facing. Locked (during a swing): mouse
 * turns body and camera together.
 */
public final class CombatCamera {
    private static float yaw;
    private static float pitch;
    private static boolean wasLocked = true;
    private static boolean replacePosition;

    private CombatCamera() {
    }

    public static void seedFrom(Player player) {
        yaw = player.getYRot();
        pitch = player.getXRot();
        wasLocked = true;
        replacePosition = false;
    }

    public static void reset() {
        wasLocked = true;
        replacePosition = false;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static boolean isLocked() {
        return ClientCombat.isAimLocked();
    }

    public static boolean shouldReplacePosition() {
        return replacePosition;
    }

    /**
     * @return true if vanilla {@code player.turn} should be skipped
     * (unlocked: mouse belongs to the camera).
     */
    public static boolean handleMouseTurn(double yawDelta, double pitchDelta) {
        if (!ClientCombat.isLocalDrawn()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return false;
        if (mc.options.getCameraType().isFirstPerson()) return false;
        if (isLocked()) return false;
        yaw += (float) yawDelta * 0.15F;
        pitch = Mth.clamp(pitch + (float) pitchDelta * 0.15F, -90.0F, 90.0F);
        yaw = Mth.wrapDegrees(yaw);
        return true;
    }

    /**
     * Called from {@code CameraMixin} at the end of {@code Camera.setup}.
     * Updates lock state; the mixin then writes rotation/position.
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
            wasLocked = isLocked();
            return;
        }

        boolean locked = isLocked();
        if (locked) {
            yaw = player.getYRot();
            pitch = player.getXRot();
        }

        if (locked && !wasLocked) {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yaw;
            player.xRotO = pitch;
            player.yHeadRot = yaw;
            player.yHeadRotO = yaw;
        }
        wasLocked = locked;
        replacePosition = false;
    }
}

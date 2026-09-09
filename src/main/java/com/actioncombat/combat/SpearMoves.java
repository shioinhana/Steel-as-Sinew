package com.actioncombat.combat;

import net.minecraft.world.phys.Vec3;

/** Longitudinal closing speed, in blocks/second; transverse motion adds no thrust energy. */
public final class SpearMoves {
    public static final double POKE_RANGE = 3.0, LONG_RANGE = 4.5, BASH_RANGE = 2.5;
    public static final int STEP_TICKS = 4;
    private SpearMoves() {}
    public static float speedMultiplier(Vec3 attackerPerTick, Vec3 targetPerTick, Vec3 towardTarget) {
        double closing = attackerPerTick.subtract(targetPerTick).dot(towardTarget.normalize()) * 20.0;
        return (float) (1.0 + 0.15 * Math.max(0.0, closing));
    }
    public static Vec3 backstep(float yaw) {
        return Vec3.directionFromRotation(0.0F, yaw).scale(-1.0);
    }
}

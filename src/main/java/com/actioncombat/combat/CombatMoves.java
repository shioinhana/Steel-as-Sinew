package com.actioncombat.combat;

import com.actioncombat.CombatConstants;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Shared movement helpers for combat-mode dodge. Direction is camera-relative
 * (the same space vanilla WASD already uses).
 */
public final class CombatMoves {
    public static final int DIR_FORWARD = 1;
    public static final int DIR_BACK = 2;
    public static final int DIR_LEFT = 4;
    public static final int DIR_RIGHT = 8;

    private CombatMoves() {
    }

    public static int packDirs(Input keys) {
        int dirs = 0;
        if (keys.forward()) dirs |= DIR_FORWARD;
        if (keys.backward()) dirs |= DIR_BACK;
        if (keys.left()) dirs |= DIR_LEFT;
        if (keys.right()) dirs |= DIR_RIGHT;
        return dirs;
    }

    /** Horizontal displacement of {@link CombatConstants#DODGE_DISTANCE} metres. */
    public static Vec3 dodgeOffset(float yawDegrees, int dirs) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yawDegrees);
        Vec3 left = Vec3.directionFromRotation(0.0F, yawDegrees - 90.0F);
        if ((dirs & (DIR_FORWARD | DIR_BACK | DIR_LEFT | DIR_RIGHT)) == 0) {
            dirs = DIR_BACK;
        }
        double x = 0.0;
        double z = 0.0;
        if ((dirs & DIR_FORWARD) != 0) {
            x += forward.x;
            z += forward.z;
        }
        if ((dirs & DIR_BACK) != 0) {
            x -= forward.x;
            z -= forward.z;
        }
        if ((dirs & DIR_LEFT) != 0) {
            x += left.x;
            z += left.z;
        }
        if ((dirs & DIR_RIGHT) != 0) {
            x -= left.x;
            z -= left.z;
        }
        Vec3 vec = new Vec3(x, 0.0, z);
        if (vec.lengthSqr() < 1.0E-8) vec = forward.scale(-1.0);
        return vec.normalize().scale(CombatConstants.DODGE_DISTANCE);
    }

    public static Vec3 attackHandOrigin(Vec3 feet, float yawDegrees, boolean leftHand) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yawDegrees);
        Vec3 left = Vec3.directionFromRotation(0.0F, yawDegrees - 90.0F);
        double side = leftHand ? CombatConstants.ATTACK_HAND_SIDE : -CombatConstants.ATTACK_HAND_SIDE;
        return feet.add(0.0, CombatConstants.ATTACK_HAND_HEIGHT, 0.0)
                .add(forward.scale(CombatConstants.ATTACK_HAND_FORWARD))
                .add(left.scale(side));
    }

    /** True if {@code box} intersects the 1.5m / 80° / 3-high slash fan. */
    public static boolean inAttackFan(Vec3 origin, float yawDegrees, net.minecraft.world.phys.AABB box) {
        float halfHeight = CombatConstants.ATTACK_FAN_HEIGHT * 0.5F;
        if (box.maxY < origin.y - halfHeight || box.minY > origin.y + halfHeight) return false;
        double x = origin.x < box.minX ? box.minX : Math.min(origin.x, box.maxX);
        double z = origin.z < box.minZ ? box.minZ : Math.min(origin.z, box.maxZ);
        double dx = x - origin.x;
        double dz = z - origin.z;
        double range = CombatConstants.ATTACK_RANGE;
        if (dx * dx + dz * dz > range * range && !box.contains(origin)) return false;
        if (dx * dx + dz * dz < 1.0E-8) return true;
        Vec3 forward = Vec3.directionFromRotation(0.0F, yawDegrees);
        double len = Math.sqrt(dx * dx + dz * dz);
        double cos = (dx * forward.x + dz * forward.z) / len;
        return cos >= CombatConstants.ATTACK_CONE_COS;
    }
}

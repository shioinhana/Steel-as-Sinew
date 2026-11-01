package org.fsrpg.movement;

import java.util.function.ToDoubleFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.fsrpg.RpgServer;

/** Shared travel hook and eligibility rules; the client accessor is supplied only from the client entrypoint. */
public final class FootworkPhysics {
    /** Client-only air-drag factor source (1 = vanilla). Replaced by the client entrypoint at startup. */
    public static ToDoubleFunction<Player> clientDrag = p -> 1;
    private FootworkPhysics() {}
    /**
     * Applies Light Body's halved air drag after vanilla travel. Server side is authoritative from
     * learning state; the client only mirrors the same correction for the local player.
     */
    public static void afterTravel(LivingEntity entity) {
        if (!(entity instanceof Player p) || p.onGround() || !eligible(p)) return;
        double factor = 1;
        if (p.level().isClientSide()) {
            factor = clientDrag.applyAsDouble(p);
        } else if (p instanceof ServerPlayer sp && RpgServer.instance() != null) {
            RpgServer server = RpgServer.instance();
            var view = server.progression().footworkView(sp.getUUID());
            if (view.enabled() && view.lightTier() >= 1)
                factor = server.config().current().skillTrees().rules().footwork().airDragFactor();
        }
        if (!Double.isFinite(factor) || factor == 1) return;
        var velocity = p.getDeltaMovement();
        double scale = FootworkMath.airDragScale(factor);
        p.setDeltaMovement(velocity.x * scale, velocity.y, velocity.z * scale);
    }
    /** Ordinary footwork requires a living, non-riding, non-liquid, non-climbing, non-flying player. */
    public static boolean eligible(Player p) {
        return p.isAlive() && !p.isSpectator() && !p.isPassenger() && !p.isFallFlying()
                && !p.isSwimming() && !p.isInWater() && !p.isInLava() && !p.onClimbable()
                && !p.getAbilities().flying && !p.isSleeping();
    }
    /** Server-confirmed movement-key direction in world space, or zero when no key is pressed. */
    public static Vec3 intentDirection(ServerPlayer p) {
        Vec3 input = p.getLastClientMoveIntent();
        Vec3 horizontal = new Vec3(input.x, 0, input.z);
        if (!Double.isFinite(horizontal.x) || !Double.isFinite(horizontal.z)) return Vec3.ZERO;
        return horizontal.lengthSqr() < 1.0E-8 ? Vec3.ZERO : horizontal.normalize();
    }
    /**
     * Old actions that accept "any direction" fall back from the movement keys to the current
     * horizontal velocity and finally to body yaw; the legacy implementation did the same.
     */
    public static Vec3 directionOr(ServerPlayer p, Vec3 preferred) {
        Vec3 horizontal = new Vec3(preferred.x, 0, preferred.z);
        if (Double.isFinite(horizontal.x) && Double.isFinite(horizontal.z) && horizontal.lengthSqr() > 1.0E-8)
            return horizontal.normalize();
        Vec3 velocity = p.getDeltaMovement();
        Vec3 moving = new Vec3(velocity.x, 0, velocity.z);
        if (Double.isFinite(moving.x) && Double.isFinite(moving.z) && moving.lengthSqr() > 1.0E-8)
            return moving.normalize();
        Vec3 look = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z);
        return look.lengthSqr() < 1.0E-8 ? Vec3.ZERO : look.normalize();
    }
}

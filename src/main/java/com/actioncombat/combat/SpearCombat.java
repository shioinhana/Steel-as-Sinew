package com.actioncombat.combat;

import com.actioncombat.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;

public final class SpearCombat {
    private record Push(LivingEntity target, Vec3 step, int ticks) {}
    private static final ArrayList<Push> PUSHES = new ArrayList<>();
    private SpearCombat() {}
    public static void cancelPush(LivingEntity entity) {
        PUSHES.removeIf(p -> p.target == entity);
    }
    public static void push(LivingEntity entity, Vec3 offset) {
        PUSHES.removeIf(p -> p.target == entity);
        PUSHES.add(new Push(entity, offset.scale(1.0 / SpearMoves.STEP_TICKS), SpearMoves.STEP_TICKS));
    }
    public static void tick(MinecraftServer server) {
        for (int i = PUSHES.size() - 1; i >= 0; i--) {
            Push p = PUSHES.remove(i);
            if (!p.target.isAlive() || p.target.isRemoved() || p.target.level().getServer() != server) continue;
            p.target.move(MoverType.SELF, p.step);
            p.target.hurtMarked = true;
            if (p.ticks > 1) PUSHES.add(new Push(p.target, p.step, p.ticks - 1));
        }
    }
    public static LivingEntity target(ServerPlayer player, CombatData.ActiveAttack attack) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        boolean bash = attack.side == SwingSide.RIGHT;
        double range = bash ? SpearMoves.BASH_RANGE : attack.depth == 1 ? SpearMoves.LONG_RANGE : SpearMoves.POKE_RANGE;
        Vec3 direction = Vec3.directionFromRotation(data.hasAim ? data.aimPitch : player.getXRot(),
                data.hasAim ? data.aimYaw : player.getYRot());
        // Begin at the player's eyes: neither a rear camera nor a wall can extend the spear.
        Vec3 start = player.getEyePosition(), end = start.add(direction.scale(range));
        var wall = player.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (wall.getType() != HitResult.Type.MISS) end = wall.getLocation();
        LivingEntity nearest = null;
        double best = start.distanceToSqr(end);
        for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range), e -> e != player && e.isAlive() && !e.isSpectator())) {
            var box = target.getBoundingBox().inflate(bash ? 0.5 : 0.12);
            var contact = box.clip(start, end);
            if (contact.isEmpty() && !box.contains(start)) continue;
            double distance = box.contains(start) ? 0 : start.distanceToSqr(contact.get());
            if (distance <= best && player.hasLineOfSight(target)) { best = distance; nearest = target; }
        }
        return nearest;
    }
    public static void finishPoke(ServerPlayer player) {
        if (player.isPassenger()) return;
        if (player.isFallFlying()) {
            player.setDeltaMovement(player.getKnownMovement().scale(0.5));
            player.hurtMarked = true;
        }
        var packet = new com.actioncombat.ModPayloads.ClientboundSpearRecovery();
        if (((net.neoforged.neoforge.common.extensions.ICommonPacketListener) player.connection).hasChannel(packet))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        player.setSprinting(false);
        player.getData(ModAttachments.COMBAT).spearSprintStopUntil = player.level().getGameTime() + 4;
    }
}

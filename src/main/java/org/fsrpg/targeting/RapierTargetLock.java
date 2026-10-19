package org.fsrpg.targeting;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.CombatActor;
import org.fsrpg.combat.CombatPhase;
import org.fsrpg.combat.RapierTargeting;
import org.fsrpg.progression.MinecraftProgression;

/** Server-owned explicit crosshair selection, retained target validation and character facing. */
public final class RapierTargetLock {
    public record Snapshot(boolean enabled, int targetId, String targetUuid, double yaw, double pitch, double range) {
        public boolean locked() { return enabled && targetId >= 0 && !targetUuid.isEmpty(); }
        public AttackAim aim() { return AttackAim.at(yaw, pitch); }
    }
    private final Map<UUID, Snapshot> states = new HashMap<>();

    public Snapshot update(ServerPlayer player, CombatActor actor, boolean weaponEquipped) {
        RapierTargeting.Rules rules = actor.rules().rapierTargetLock();
        boolean enabled = enabled(player,actor,weaponEquipped);
        Snapshot previous = states.get(player.getUUID());
        RapierTargeting.Candidate selected = null;
        if(enabled&&previous!=null&&previous.locked()&&player.level().getEntity(previous.targetId()) instanceof Mob mob
                &&mob.getUUID().toString().equals(previous.targetUuid())) {
            selected=RapierTargeting.selected(mob.getUUID(),List.of(candidate(player,mob)),rules);
        }
        double yaw = AttackAim.wrap(player.getYRot()), pitch = Math.clamp(player.getXRot(), -80, 80);
        if (selected != null && player.level().getEntity(selected.entityId()) instanceof LivingEntity target) {
            var displacement = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
            var direction = RapierTargeting.direction(displacement.x, displacement.y, displacement.z, yaw);
            yaw = direction.yaw(); pitch = direction.pitch();
            player.setYRot((float) yaw);
            player.setXRot((float) pitch);
            player.setYHeadRot((float) yaw);
            player.setYBodyRot((float) yaw);
            actor.trackLockedAim(AttackAim.at(yaw, pitch));
        }
        Snapshot snapshot = new Snapshot(enabled, selected == null ? -1 : selected.entityId(),
                selected == null ? "" : selected.uuid().toString(), yaw, pitch, rules.range());
        states.put(player.getUUID(), snapshot);
        return snapshot;
    }
    private static boolean enabled(ServerPlayer player,CombatActor actor,boolean weaponEquipped) {
        return actor.rules().rapierTargetLock().enabled() && player.isAlive() && !player.isSpectator() && weaponEquipped
                && actor.drawn() && actor.phase() != CombatPhase.SHEATHING && actor.style() != null
                && !actor.style().controls().spear() && !actor.style().weaponGroup().equals("unarmed");
    }
    private static RapierTargeting.Candidate candidate(ServerPlayer player,Mob mob) {
        boolean friendly=player.isAlliedTo(mob)||mob.isAlliedTo(player)||mob instanceof TamableAnimal animal&&animal.isTame();
        boolean hostile=MinecraftProgression.hostile(mob)||mob.getTarget()!=null&&mob.getTarget().getUUID().equals(player.getUUID());
        return new RapierTargeting.Candidate(mob.getUUID(),mob.getId(),player.distanceToSqr(mob),hostile,
                mob.isAlive()&&!mob.isRemoved(),mob.isAttackable()&&!mob.isInvulnerable(),friendly,player.hasLineOfSight(mob));
    }
    /** Call only after a real accepted attack; the adapter independently validates the submitted eye ray. */
    public boolean acquire(ServerPlayer player,CombatActor actor,boolean weaponEquipped,LivingEntity target) {
        if(!enabled(player,actor,weaponEquipped)||!(target instanceof Mob mob)||!candidate(player,mob).eligible(actor.rules().rapierTargetLock()))return false;
        states.put(player.getUUID(),new Snapshot(true,mob.getId(),mob.getUUID().toString(),player.getYRot(),player.getXRot(),actor.rules().rapierTargetLock().range()));
        update(player,actor,weaponEquipped);return true;
    }
    public void unlock(ServerPlayer player,CombatActor actor) {
        states.put(player.getUUID(),new Snapshot(actor.rules().rapierTargetLock().enabled(),-1,"",player.getYRot(),player.getXRot(),actor.rules().rapierTargetLock().range()));
    }

    public Snapshot snapshot(ServerPlayer player, CombatActor actor) {
        return states.getOrDefault(player.getUUID(), new Snapshot(false, -1, "", AttackAim.wrap(player.getYRot()),
                Math.clamp(player.getXRot(), -80, 80), actor.rules().rapierTargetLock().range()));
    }
    public LivingEntity target(ServerPlayer player) {
        Snapshot state = states.get(player.getUUID());
        if (state == null || !state.locked()) return null;
        return player.level().getEntity(state.targetId()) instanceof LivingEntity entity
                && entity.getUUID().toString().equals(state.targetUuid()) && entity.isAlive() ? entity : null;
    }
    public void remove(UUID player) { states.remove(player); }
}

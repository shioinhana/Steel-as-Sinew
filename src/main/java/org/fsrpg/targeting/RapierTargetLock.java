package org.fsrpg.targeting;

import java.util.ArrayList;
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

/** Minecraft adapter for server-owned selection, validation and character facing. */
public final class RapierTargetLock {
    public record Snapshot(boolean enabled, int targetId, String targetUuid, double yaw, double pitch, double range) {
        public boolean locked() { return enabled && targetId >= 0 && !targetUuid.isEmpty(); }
        public AttackAim aim() { return AttackAim.at(yaw, pitch); }
    }
    private final Map<UUID, Snapshot> states = new HashMap<>();

    public Snapshot update(ServerPlayer player, CombatActor actor, boolean weaponEquipped) {
        RapierTargeting.Rules rules = actor.rules().rapierTargetLock();
        boolean enabled = rules.enabled() && player.isAlive() && !player.isSpectator() && weaponEquipped
                && actor.drawn() && actor.phase() != CombatPhase.SHEATHING && actor.style() != null
                && actor.style().presentationStyleId().equals("rapier");
        Snapshot previous = states.get(player.getUUID());
        RapierTargeting.Candidate selected = null;
        if (enabled) {
            var candidates = new ArrayList<RapierTargeting.Candidate>();
            for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(rules.range()))) {
                boolean friendly = player.isAlliedTo(mob) || mob.isAlliedTo(player)
                        || mob instanceof TamableAnimal animal && animal.isTame();
                boolean hostile = MinecraftProgression.hostile(mob)
                        || mob.getTarget() != null && mob.getTarget().getUUID().equals(player.getUUID());
                candidates.add(new RapierTargeting.Candidate(mob.getUUID(), mob.getId(), player.distanceToSqr(mob),
                        hostile, mob.isAlive() && !mob.isRemoved(), mob.isAttackable() && !mob.isInvulnerable(),
                        friendly, player.hasLineOfSight(mob)));
            }
            UUID held = previous != null && previous.locked() ? UUID.fromString(previous.targetUuid()) : null;
            selected = RapierTargeting.select(held, candidates, rules);
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
            actor.trackRapierAim(AttackAim.at(yaw, pitch));
        }
        Snapshot snapshot = new Snapshot(enabled, selected == null ? -1 : selected.entityId(),
                selected == null ? "" : selected.uuid().toString(), yaw, pitch, rules.range());
        states.put(player.getUUID(), snapshot);
        return snapshot;
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

package org.fsrpg.creature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fsrpg.RpgServer;
import org.fsrpg.network.RpgCreatureAttackPayload;
import org.fsrpg.mixin.EnderManStareInvoker;

/** Delays a vanilla melee invocation, rather than synthesizing replacement damage. */
public final class CreatureMeleeServer {
    public record Snapshot(UUID entityId, UUID targetId, long sequence, String clip, String phase,
                           long startedGameTick, long contactGameTick, long endGameTick, boolean contactClaimed) {}
    private static final class Attack {
        final Mob mob;
        final LivingEntity target;
        final ServerLevel level;
        final CreatureCombatCatalog.Definition definition;
        final CreatureAttackTimeline clock;
        final ItemStack weapon;
        final boolean trackedTarget;
        final long sequence;
        Attack(Mob mob, LivingEntity target, ServerLevel level, CreatureCombatCatalog.Definition definition, long sequence) {
            this.mob=mob; this.target=target; this.level=level; this.definition=definition; this.sequence=sequence;
            clock=new CreatureAttackTimeline(level.getGameTime(),definition.windupTicks(),definition.recoveryTicks());
            weapon=mob.getMainHandItem().copy(); trackedTarget=mob.getTarget()==target;
        }
    }
    private record Invocation(Mob attacker, Entity target) {}
    private final Map<UUID,Attack> active = new HashMap<>();
    private final ToIntFunction<Entity> stunRemaining;
    private CreatureCombatCatalog catalog;
    private Invocation invocation;
    private long sequence, acceptedAttempts, contactAttempts, canceledAttacks, completedAttacks;

    public CreatureMeleeServer(CreatureCombatCatalog catalog, ToIntFunction<Entity> stunRemaining) {
        this.catalog=catalog; this.stunRemaining=stunRemaining;
    }
    /** True means this attempt was consumed; false lets the untouched vanilla method run. */
    public static boolean intercept(Mob attacker, ServerLevel level, Entity target) {
        RpgServer server=RpgServer.instance();
        return server!=null && server.creatureMelee()!=null && server.creatureMelee().request(attacker,level,target);
    }
    private boolean request(Mob mob, ServerLevel level, Entity entity) {
        if (invocation!=null && invocation.attacker()==mob && invocation.target()==entity) return false;
        if (!(entity instanceof LivingEntity target) || mob.level()!=level) return false;
        String entityType=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        var chosen=catalog.find(entityType,variant(mob));
        if (chosen.isEmpty()) return false;
        if (active.containsKey(mob.getUUID()) || stunRemaining.applyAsInt(mob)>0) return true;
        if (!canContinue(mob) || !mob.isAlive() || mob.isRemoved() || !target.isAlive() || target.isRemoved()
                || target.level()!=level || !mob.isWithinMeleeAttackRange(target) || !mob.hasLineOfSight(target)) return true;
        Attack attack=new Attack(mob,target,level,chosen.get(),++sequence);
        active.put(mob.getUUID(),attack); acceptedAttempts++;
        send(attack,"BEGIN",null); return true;
    }
    public static String variant(Mob mob) {
        String item=BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem()).getPath();
        return item.contains("crossbow")?"crossbow":item.equals("bow")?"bow":item.contains("spear")?"spear":item.contains("axe")?"axe":"sword";
    }
    /** Runs once after the world's vanilla AI. Pending attempts never invoke any attack side effects. */
    public void tick() {
        for (Attack attack:List.copyOf(active.values())) {
            if (active.get(attack.mob.getUUID())!=attack) continue;
            if (!valid(attack) || stunRemaining.applyAsInt(attack.mob)>0) { finish(attack,true); continue; }
            long now=attack.level.getGameTime();
            if (attack.clock.claimContact(now)) {
                contactAttempts++; send(attack,"CONTACT",null);
                // The original virtual dispatch preserves damage, item/enchantment hooks and
                // mob-specific effects. Incoming damage continues through the real guard resolver.
                if (attack.mob.isWithinMeleeAttackRange(attack.target) && attack.mob.hasLineOfSight(attack.target)) {
                    Invocation previous=invocation; invocation=new Invocation(attack.mob,attack.target);
                    try { attack.mob.doHurtTarget(attack.level,attack.target); }
                    finally { invocation=previous; }
                    if (stunRemaining.applyAsInt(attack.mob)>0) {
                        attack.mob.getNavigation().stop(); attack.mob.setDeltaMovement(0,0,0); attack.mob.hurtMarked=true;
                        finish(attack,true);
                    }
                }
            }
            if (active.get(attack.mob.getUUID())==attack && attack.clock.phase(now)==CreatureAttackTimeline.Phase.FINISHED)
                finish(attack,false);
        }
    }
    private boolean valid(Attack attack) {
        return canContinue(attack.mob) && attack.mob.isAlive() && !attack.mob.isRemoved() && attack.target.isAlive() && !attack.target.isRemoved()
                && attack.mob.level()==attack.level && attack.target.level()==attack.level
                && (!attack.trackedTarget || attack.mob.getTarget()==attack.target)
                && (attack.clock.contactClaimed() ? attack.weapon.getItem()==attack.mob.getMainHandItem().getItem()
                    : ItemStack.isSameItemSameComponents(attack.weapon,attack.mob.getMainHandItem()));
    }
    private static boolean canContinue(Mob mob) {
        if (mob instanceof Creaking creaking && !creaking.canMove()) return false;
        if (mob instanceof EnderMan ender && ender.getTarget() instanceof Player player
                && ender.distanceToSqr(player)<=256 && ((EnderManStareInvoker)ender).fsrpg$isBeingStaredBy(player)) return false;
        if (mob instanceof Warden warden)
            return !warden.hasPose(Pose.DIGGING) && !warden.hasPose(Pose.EMERGING)
                    && !warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_DELAY)
                    && !warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN);
        return true;
    }
    public void cancel(Entity entity) {
        Attack attack=active.get(entity.getUUID());
        if (attack!=null && attack.mob==entity) finish(attack,true);
    }
    private void finish(Attack attack, boolean canceled) {
        if (!active.remove(attack.mob.getUUID(),attack)) return;
        if (canceled) {attack.clock.cancel(); canceledAttacks++;} else completedAttacks++;
        send(attack,canceled?"CANCEL":"END",null);
    }
    public void reconfigure(CreatureCombatCatalog replacement) {
        for (Attack attack:List.copyOf(active.values())) finish(attack,true);
        catalog=replacement;
    }
    public void startTracking(ServerPlayer viewer, Entity entity) {
        Attack attack=active.get(entity.getUUID());
        if (attack!=null && attack.mob==entity) send(attack,attack.clock.contactClaimed()?"CONTACT":"BEGIN",viewer);
    }
    private static void send(Attack attack, String phase, ServerPlayer viewer) {
        var definition=attack.definition;
        var payload=new RpgCreatureAttackPayload(attack.mob.getUUID(),attack.sequence,definition.clip(),phase,
                attack.clock.startedGameTick(),attack.level.getGameTime(),definition.windupTicks(),definition.recoveryTicks(),
                definition.contactSeconds(),definition.durationSeconds());
        if (viewer==null) PacketDistributor.sendToPlayersTrackingEntity(attack.mob,payload);
        else PacketDistributor.sendToPlayer(viewer,payload);
    }
    public Snapshot snapshot(Entity entity) {
        Attack attack=active.get(entity.getUUID());
        if (attack==null || attack.mob!=entity) return null;
        return new Snapshot(entity.getUUID(),attack.target.getUUID(),attack.sequence,attack.definition.clip(),
                attack.clock.phase(attack.level.getGameTime()).name(),attack.clock.startedGameTick(),
                attack.clock.contactGameTick(),attack.clock.endGameTick(),attack.clock.contactClaimed());
    }
    public long acceptedAttempts(){return acceptedAttempts;}
    public long contactAttempts(){return contactAttempts;}
    public long canceledAttacks(){return canceledAttacks;}
    public long completedAttacks(){return completedAttacks;}
    public int activeAttacks(){return active.size();}
    public CreatureCombatCatalog catalog(){return catalog;}
}

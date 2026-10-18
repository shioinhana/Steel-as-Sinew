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
import org.fsrpg.network.RpgGuardCuePayload;
import org.fsrpg.mixin.EnderManStareInvoker;

/** Delays a vanilla melee invocation, rather than synthesizing replacement damage. */
public final class CreatureMeleeServer {
    public record Snapshot(UUID entityId, UUID targetId, long sequence, String clip, String phase,
                           long startedGameTick, long contactGameTick, long endGameTick, boolean contactClaimed) {}
    public record GuardCueSnapshot(UUID entityId, UUID targetId, long sequence, long startedGameTick,
                                   long flashGameTick, long openGameTick, long closeGameTick, long contactGameTick) {}
    private static final class Attack {
        final Mob mob;
        final LivingEntity target;
        final ServerLevel level;
        final CreatureCombatCatalog.Definition definition;
        final CreatureAttackTimeline clock;
        final GuardCueWindow cue;
        final int flashDurationMillis;
        final ItemStack weapon;
        final boolean trackedTarget;
        final long sequence;
        Attack(Mob mob, LivingEntity target, ServerLevel level, CreatureCombatCatalog.Definition definition, long sequence, GuardCueSettings settings) {
            this.mob=mob; this.target=target; this.level=level; this.definition=definition; this.sequence=sequence;
            int windup=target instanceof ServerPlayer?settings.windupTicks(definition.windupTicks()):definition.windupTicks();
            clock=new CreatureAttackTimeline(level.getGameTime(),windup,definition.recoveryTicks());
            cue=settings.enabled()&&target instanceof ServerPlayer?settings.window(level.getGameTime(),windup):null;
            flashDurationMillis=settings.flashDurationMillis();
            weapon=mob.getMainHandItem().copy(); trackedTarget=mob.getTarget()==target;
        }
    }
    private record Invocation(Attack attack) {}
    private final Map<UUID,Attack> active = new HashMap<>();
    private final ToIntFunction<Entity> stunRemaining;
    private CreatureCombatCatalog catalog;
    private GuardCueSettings cueSettings;
    private Invocation invocation;
    private long sequence, acceptedAttempts, contactAttempts, canceledAttacks, completedAttacks;

    public CreatureMeleeServer(CreatureCombatCatalog catalog, ToIntFunction<Entity> stunRemaining) {
        this(catalog,stunRemaining,GuardCueSettings.defaults());
    }
    public CreatureMeleeServer(CreatureCombatCatalog catalog, ToIntFunction<Entity> stunRemaining, GuardCueSettings settings) {
        this.catalog=catalog; this.stunRemaining=stunRemaining;this.cueSettings=settings;
    }
    /** True means this attempt was consumed; false lets the untouched vanilla method run. */
    public static boolean intercept(Mob attacker, ServerLevel level, Entity target) {
        RpgServer server=RpgServer.instance();
        return server!=null && server.creatureMelee()!=null && server.creatureMelee().request(attacker,level,target);
    }
    private boolean request(Mob mob, ServerLevel level, Entity entity) {
        if (invocation!=null && invocation.attack().mob==mob && invocation.attack().target==entity) return false;
        if (!(entity instanceof LivingEntity target) || mob.level()!=level) return false;
        String entityType=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        var chosen=catalog.find(entityType,variant(mob));
        if (chosen.isEmpty()) return false;
        if (active.containsKey(mob.getUUID()) || stunRemaining.applyAsInt(mob)>0) return true;
        if (!canContinue(mob) || !mob.isAlive() || mob.isRemoved() || !target.isAlive() || target.isRemoved()
                || target.level()!=level || !mob.isWithinMeleeAttackRange(target) || !mob.hasLineOfSight(target)) return true;
        Attack attack=new Attack(mob,target,level,chosen.get(),++sequence,cueSettings);
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
                    Invocation previous=invocation; invocation=new Invocation(attack);
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
        if(attack.cue!=null)attack.cue.cancel();
        if (canceled) {attack.clock.cancel(); canceledAttacks++;} else completedAttacks++;
        send(attack,canceled?"CANCEL":"END",null);
    }
    public void reconfigure(CreatureCombatCatalog replacement) {
        reconfigure(replacement,cueSettings);
    }
    public void reconfigure(CreatureCombatCatalog replacement,GuardCueSettings settings) {
        for (Attack attack:List.copyOf(active.values())) finish(attack,true);
        catalog=replacement;cueSettings=settings;
    }
    public void startTracking(ServerPlayer viewer, Entity entity) {
        Attack attack=active.get(entity.getUUID());
        if (attack!=null && attack.mob==entity) send(attack,attack.clock.contactClaimed()?"CONTACT":"BEGIN",viewer);
    }
    private static void send(Attack attack, String phase, ServerPlayer viewer) {
        var definition=attack.definition;
        var payload=new RpgCreatureAttackPayload(attack.mob.getUUID(),attack.sequence,definition.clip(),phase,
                attack.clock.startedGameTick(),attack.level.getGameTime(),(int)(attack.clock.contactGameTick()-attack.clock.startedGameTick()),definition.recoveryTicks(),
                definition.contactSeconds(),definition.durationSeconds());
        if (viewer==null) PacketDistributor.sendToPlayersTrackingEntity(attack.mob,payload);
        else PacketDistributor.sendToPlayer(viewer,payload);
        if(attack.cue!=null&&attack.target instanceof ServerPlayer player&&(viewer==null||viewer==player)){
            var cue=attack.cue;
            PacketDistributor.sendToPlayer(player,new RpgGuardCuePayload(attack.mob.getUUID(),player.getUUID(),attack.sequence,phase,
                    cue.startedGameTick(),attack.level.getGameTime(),cue.flashGameTick(),cue.openGameTick(),cue.closeGameTick(),
                    cue.contactGameTick(),attack.flashDurationMillis));
        }
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
    public GuardCueSettings guardCueSettings(){return cueSettings;}
    public GuardCueSnapshot guardCueSnapshot(Entity entity){
        Attack attack=active.get(entity.getUUID());if(attack==null||attack.mob!=entity||attack.cue==null)return null;
        var cue=attack.cue;return new GuardCueSnapshot(entity.getUUID(),attack.target.getUUID(),attack.sequence,cue.startedGameTick(),
                cue.flashGameTick(),cue.openGameTick(),cue.closeGameTick(),cue.contactGameTick());
    }
    public boolean guardOpportunity(ServerPlayer player){
        for(Attack attack:active.values())if(attack.target==player&&attack.cue!=null&&attack.cue.accepts(attack.level.getGameTime())&&valid(attack))return true;
        return false;
    }
    public int authorizeGuardPress(ServerPlayer player,long serial){
        int accepted=0;
        for(Attack attack:active.values())if(attack.target==player&&attack.cue!=null&&valid(attack)
                &&attack.cue.press(attack.level.getGameTime(),serial))accepted++;
        return accepted;
    }
    /** Null denotes a vanilla attack outside this cue system. False explicitly forbids generic timed parry. */
    public Boolean claimGuardAuthorization(Entity source,Entity direct,ServerPlayer defender,long serial,boolean held){
        if(invocation==null)return null;
        Attack attack=invocation.attack();
        if(source!=attack.mob||direct!=attack.mob||attack.target!=defender||attack.cue==null)return null;
        return attack.cue.consume(attack.level.getGameTime(),serial,held);
    }
}

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
        final double damageMultiplier;
        CreatureContactDelay contact;
        Attack(Mob mob, LivingEntity target, ServerLevel level, CreatureCombatCatalog.Definition definition, long sequence, GuardCueSettings settings, double damageMultiplier) {
            this.mob=mob; this.target=target; this.level=level; this.definition=definition; this.sequence=sequence;
            this.damageMultiplier=damageMultiplier;
            int windup=target instanceof ServerPlayer?settings.windupTicks(definition.windupTicks()):definition.windupTicks();
            clock=new CreatureAttackTimeline(level.getGameTime(),windup,definition.recoveryTicks());
            cue=settings.enabled()&&target instanceof ServerPlayer?settings.window(level.getGameTime(),windup):null;
            flashDurationMillis=settings.flashDurationMillis();
            weapon=mob.getMainHandItem().copy(); trackedTarget=mob.getTarget()==target;
        }
    }
    /** An unprofiled vanilla attempt already reached its original contact invocation. */
    private static final class NativeAttack {
        final Mob mob;
        final LivingEntity target;
        final ServerLevel level;
        final ItemStack weapon;
        final boolean trackedTarget;
        final long sequence, contactTick;
        final double damageMultiplier;
        final int windowTicks;
        final CreatureContactDelay contact;
        NativeAttack(Mob mob,LivingEntity target,ServerLevel level,long sequence,int windowTicks,double damageMultiplier) {
            this.mob=mob;this.target=target;this.level=level;this.sequence=sequence;this.windowTicks=windowTicks;
            this.damageMultiplier=damageMultiplier;
            contactTick=level.getGameTime();weapon=mob.getMainHandItem().copy();trackedTarget=mob.getTarget()==target;
            contact=new CreatureContactDelay(contactTick,windowTicks,true);
        }
    }
    private record Invocation(Attack attack, NativeAttack nativeAttack, long contactTick, boolean deferred, boolean windowClash) {
        Mob mob(){return attack!=null?attack.mob:nativeAttack.mob;}
        LivingEntity target(){return attack!=null?attack.target:nativeAttack.target;}
        double damageMultiplier(){return attack!=null?attack.damageMultiplier:nativeAttack.damageMultiplier;}
    }
    private final Map<UUID,Attack> active = new HashMap<>();
    private final Map<UUID,NativeAttack> nativeActive = new HashMap<>();
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
        if (invocation!=null && invocation.mob()==mob && invocation.target()==entity) return false;
        if (!(entity instanceof LivingEntity target) || mob.level()!=level) return false;
        if (active.containsKey(mob.getUUID()) || nativeActive.containsKey(mob.getUUID()) || stunRemaining.applyAsInt(mob)>0) return true;
        String entityType=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        var chosen=catalog.find(entityType,variant(mob));
        if (chosen.isEmpty()) {
            RpgServer server=RpgServer.instance();
            if (server==null || !server.deferCreatureContact(mob,target)) return false;
            if (!canContinue(mob) || !mob.isAlive() || mob.isRemoved() || !target.isAlive() || target.isRemoved()
                    || target.level()!=level || !mob.isWithinMeleeAttackRange(target) || !mob.hasLineOfSight(target)) return true;
            double damageMultiplier=server.consumeMeleeCounter(mob);
            NativeAttack attack=new NativeAttack(mob,target,level,++sequence,server.config().current().combat().weaponClash().contactWindowTicks(),damageMultiplier);
            nativeActive.put(mob.getUUID(),attack);acceptedAttempts++;contactAttempts++;
            // No authored clip or cue exists. Keep the complete original virtual invocation only.
            return true;
        }
        if (!canContinue(mob) || !mob.isAlive() || mob.isRemoved() || !target.isAlive() || target.isRemoved()
                || target.level()!=level || !mob.isWithinMeleeAttackRange(target) || !mob.hasLineOfSight(target)) return true;
        RpgServer server=RpgServer.instance();
        double damageMultiplier=server==null?1:server.consumeMeleeCounter(mob);
        Attack attack=new Attack(mob,target,level,chosen.get(),++sequence,cueSettings,damageMultiplier);
        active.put(mob.getUUID(),attack); acceptedAttempts++;
        send(attack,"BEGIN",null); return true;
    }
    public static String variant(Mob mob) {
        String item=BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem()).getPath();
        return item.contains("crossbow")?"crossbow":item.equals("bow")?"bow":item.contains("spear")?"spear":item.contains("axe")?"axe":"sword";
    }
    /** Runs once after the world's vanilla AI. Pending attempts never invoke any attack side effects. */
    public void tick() {
        for (NativeAttack attack:List.copyOf(nativeActive.values()))
            if (!valid(attack) || stunRemaining.applyAsInt(attack.mob)>0) finish(attack,true);
        for (Attack attack:List.copyOf(active.values())) {
            if (active.get(attack.mob.getUUID())!=attack) continue;
            if (!valid(attack) || stunRemaining.applyAsInt(attack.mob)>0) { finish(attack,true); continue; }
            long now=attack.level.getGameTime();
            if (attack.clock.claimContact(now)) {
                contactAttempts++; send(attack,"CONTACT",null);
                if (attack.mob.isWithinMeleeAttackRange(attack.target) && attack.mob.hasLineOfSight(attack.target)) {
                    RpgServer server=RpgServer.instance();
                    boolean wait=server!=null&&server.deferCreatureContact(attack.mob,attack.target);
                    // Authored attacks allow a first ACTIVE on this inclusive contact tick, then
                    // commit any unanswered native damage in the same tick after the player scan.
                    int window=0;
                    attack.contact=new CreatureContactDelay(now,window,wait);
                    // Expiry is deliberately checked by flushContacts after this tick's player
                    // contacts, so the inclusive final tick cannot lose to world iteration order.
                    executeContact(attack,now,false);
                }
            }
            finishCompleted(attack,now);
        }
    }
    /** Run after collecting player contacts, before expiring unmatched player contacts. */
    public void flushContacts() {
        for (Attack attack:List.copyOf(active.values())) {
            if (active.get(attack.mob.getUUID())!=attack || attack.contact==null || !attack.contact.pending()) continue;
            if (!valid(attack) || stunRemaining.applyAsInt(attack.mob)>0) { finish(attack,true); continue; }
            long now=attack.level.getGameTime();
            executeContact(attack,now,true);
            finishCompleted(attack,now);
        }
        for (NativeAttack attack:List.copyOf(nativeActive.values())) {
            if (nativeActive.get(attack.mob.getUUID())!=attack || !attack.contact.pending()) continue;
            if (!valid(attack) || stunRemaining.applyAsInt(attack.mob)>0) { finish(attack,true); continue; }
            RpgServer server=RpgServer.instance();long now=attack.level.getGameTime();
            boolean reverse=server!=null&&server.hasPendingMelee(attack.target,attack.mob,attack.contactTick);
            if (!attack.contact.claimExecution(now,reverse)) continue;
            Invocation previous=invocation;
            // The native contact occurred at interception, even when dispatch follows in the same tick.
            invocation=new Invocation(null,attack,attack.contactTick,true,false);
            try { attack.mob.doHurtTarget(attack.level,attack.target); }
            finally { invocation=previous;finish(attack,false); }
        }
    }
    private void executeContact(Attack attack,long now,boolean allowExpiration) {
        var contact=attack.contact;
        if (contact==null || !contact.pending()) return;
        RpgServer server=RpgServer.instance();
        boolean reverse=server!=null&&server.hasPendingMelee(attack.target,attack.mob,contact.contactTick());
        if (!allowExpiration && contact.waitForReverse() && !reverse) return;
        if (!contact.claimExecution(now,reverse)) return;
        // Invoke the complete original virtual method exactly once. Delaying only a replacement
        // hurtServer call would discard poison, enchantment hooks and other mob-specific effects.
        Invocation previous=invocation;
        invocation=new Invocation(attack,null,contact.contactTick(),contact.deferred(),false);
        try { attack.mob.doHurtTarget(attack.level,attack.target); }
        finally { invocation=previous; }
        if (stunRemaining.applyAsInt(attack.mob)>0) {
            attack.mob.getNavigation().stop(); attack.mob.setDeltaMovement(0,0,0); attack.mob.hurtMarked=true;
            finish(attack,true);
        }
    }
    private void finishCompleted(Attack attack,long now) {
        if (active.get(attack.mob.getUUID())==attack && (attack.contact==null || !attack.contact.pending())
                && attack.clock.phase(now)==CreatureAttackTimeline.Phase.FINISHED) finish(attack,false);
    }
    /** The synchronous incoming-damage adapter can recover the original animation contact tick. */
    public Long invocationContactTick(Entity source,Entity direct,LivingEntity target) {
        if (invocation==null) return null;
        return source==invocation.mob() && direct==invocation.mob() && target==invocation.target() ? invocation.contactTick() : null;
    }
    public boolean invocationDeferred() { return invocation!=null && invocation.deferred(); }
    public boolean invocationWindowClash() { return invocation!=null && invocation.windowClash(); }
    /** Counter power belongs to this accepted native invocation, even after a long authored windup. */
    public double invocationDamageMultiplier() { return invocation==null ? 1 : invocation.damageMultiplier(); }
    /** An authored attack can meet only a new ACTIVE inside its own current cue window. */
    public Snapshot windowClashCandidate(Entity entity,LivingEntity target,long now) {
        Attack attack=active.get(entity.getUUID());
        if(attack==null||attack.mob!=entity||attack.target!=target||attack.cue==null
                ||!attack.cue.acceptsClash(now)||now>attack.clock.contactGameTick()
                ||attack.contact!=null&&!attack.contact.pending()||!valid(attack)
                ||stunRemaining.applyAsInt(entity)>0||!attack.mob.isWithinMeleeAttackRange(target)
                ||!attack.mob.hasLineOfSight(target))return null;
        return snapshot(entity);
    }
    /** Invoke the original virtual native attack once to obtain its full real incoming damage. */
    public boolean executeWindowClash(Mob mob,LivingEntity target,long expectedSequence) {
        long now=mob.level().getGameTime();Snapshot candidate=windowClashCandidate(mob,target,now);
        if(candidate==null||candidate.sequence()!=expectedSequence)return false;
        Attack attack=active.get(mob.getUUID());
        if(attack.contact==null){
            contactAttempts++;send(attack,"CONTACT",null);
            attack.contact=new CreatureContactDelay(now,0,false);
        }
        if(!attack.contact.claimExecution(now,true))return false;
        Invocation previous=invocation;
        invocation=new Invocation(attack,null,attack.clock.contactGameTick(),false,true);
        try {mob.doHurtTarget(attack.level,target);}
        finally {
            invocation=previous;
            // The one original call already happened; its future animation contact is canceled
            // even if another native hook canceled damage before the RPG incoming adapter.
            finish(attack,true);
        }
        return true;
    }
    private boolean valid(Attack attack) {
        return canContinue(attack.mob) && attack.mob.isAlive() && !attack.mob.isRemoved() && attack.target.isAlive() && !attack.target.isRemoved()
                && attack.mob.level()==attack.level && attack.target.level()==attack.level
                && (!attack.trackedTarget || attack.mob.getTarget()==attack.target)
                && (attack.clock.contactClaimed() && (attack.contact==null || !attack.contact.pending()) ? attack.weapon.getItem()==attack.mob.getMainHandItem().getItem()
                    : ItemStack.isSameItemSameComponents(attack.weapon,attack.mob.getMainHandItem()));
    }
    private boolean valid(NativeAttack attack) {
        return canContinue(attack.mob) && attack.mob.isAlive() && !attack.mob.isRemoved() && attack.target.isAlive() && !attack.target.isRemoved()
                && attack.mob.level()==attack.level && attack.target.level()==attack.level
                && (!attack.trackedTarget || attack.mob.getTarget()==attack.target)
                && ItemStack.isSameItemSameComponents(attack.weapon,attack.mob.getMainHandItem());
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
        for (Attack attack:List.copyOf(active.values()))
            if (attack.mob==entity || attack.target==entity) finish(attack,true);
        for (NativeAttack attack:List.copyOf(nativeActive.values()))
            if (attack.mob==entity || attack.target==entity) finish(attack,true);
    }
    /** A parry stops this attacker's weapon, without erasing unrelated attacks aimed at it. */
    public void cancelAttacker(Entity entity) {
        for (Attack attack:List.copyOf(active.values()))if(attack.mob==entity)finish(attack,true);
        for (NativeAttack attack:List.copyOf(nativeActive.values()))if(attack.mob==entity)finish(attack,true);
    }
    private void finish(Attack attack, boolean canceled) {
        if (!active.remove(attack.mob.getUUID(),attack)) return;
        if(attack.contact!=null)attack.contact.cancel();
        if(attack.cue!=null)attack.cue.cancel();
        if (canceled) {attack.clock.cancel(); canceledAttacks++;} else completedAttacks++;
        send(attack,canceled?"CANCEL":"END",null);
    }
    private void finish(NativeAttack attack,boolean canceled) {
        if (!nativeActive.remove(attack.mob.getUUID(),attack)) return;
        attack.contact.cancel();
        if (canceled) canceledAttacks++; else completedAttacks++;
    }
    public void reconfigure(CreatureCombatCatalog replacement) {
        reconfigure(replacement,cueSettings);
    }
    public void reconfigure(CreatureCombatCatalog replacement,GuardCueSettings settings) {
        for (Attack attack:List.copyOf(active.values())) finish(attack,true);
        for (NativeAttack attack:List.copyOf(nativeActive.values())) finish(attack,true);
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
        if (attack==null || attack.mob!=entity) {
            NativeAttack nativeAttack=nativeActive.get(entity.getUUID());
            if (nativeAttack==null || nativeAttack.mob!=entity) return null;
            return new Snapshot(entity.getUUID(),nativeAttack.target.getUUID(),nativeAttack.sequence,"","NATIVE_CONTACT",
                    nativeAttack.contactTick,nativeAttack.contactTick,nativeAttack.contactTick+nativeAttack.windowTicks,true);
        }
        return new Snapshot(entity.getUUID(),attack.target.getUUID(),attack.sequence,attack.definition.clip(),
                attack.clock.phase(attack.level.getGameTime()).name(),attack.clock.startedGameTick(),
                attack.clock.contactGameTick(),attack.clock.endGameTick(),attack.clock.contactClaimed());
    }
    /** Only an attack with a future or still-waiting original contact can answer a player's strike. */
    public long clashSequence(Entity entity) {
        Attack attack=active.get(entity.getUUID());
        if (attack!=null && attack.mob==entity && valid(attack) && stunRemaining.applyAsInt(attack.mob)<=0
                && (!attack.clock.contactClaimed() || attack.contact!=null && attack.contact.pending())) return attack.sequence;
        NativeAttack nativeAttack=nativeActive.get(entity.getUUID());
        return nativeAttack!=null && nativeAttack.mob==entity && valid(nativeAttack) && stunRemaining.applyAsInt(nativeAttack.mob)<=0
                && nativeAttack.contact.pending() ? nativeAttack.sequence : -1;
    }
    public long acceptedAttempts(){return acceptedAttempts;}
    public long contactAttempts(){return contactAttempts;}
    public long canceledAttacks(){return canceledAttacks;}
    public long completedAttacks(){return completedAttacks;}
    public int activeAttacks(){return active.size()+nativeActive.size();}
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
        if(attack==null)return null;
        if(source!=attack.mob||direct!=attack.mob||attack.target!=defender||attack.cue==null)return null;
        return attack.cue.consume(attack.level.getGameTime(),serial,held);
    }
}

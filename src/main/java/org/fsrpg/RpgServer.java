package org.fsrpg;

import com.google.gson.*;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fsrpg.combat.*;
import org.fsrpg.combat.HitResult;
import org.fsrpg.config.*;
import org.fsrpg.network.*;
import org.fsrpg.quest.*;
import org.fsrpg.progression.*;
import org.fsrpg.archery.*;
import org.fsrpg.movement.FootworkDamage;
import org.fsrpg.movement.FootworkServer;
import org.fsrpg.targeting.RapierTargetLock;
import org.fsrpg.creature.CreatureCombatCatalog;
import org.fsrpg.creature.CreatureMeleeServer;
import org.fsrpg.creature.GuardCueSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minecraft adapters only. Definitions and deterministic state machines live in separate packages. */
public final class RpgServer {
    private static final Logger LOG = LoggerFactory.getLogger("FSRPG");
    private static RpgServer instance;
    private final MinecraftServer server;
    private final RpgConfig config;
    private QuestService quests;
    private final Map<UUID,SwordMotionPlan> swordMotions=new HashMap<>();
    private static final Identifier SWORD_MOVE_ID=Identifier.fromNamespaceAndPath("fsrpg","sword_action_movement");
    private CollectionService collections;
    private final CollectionStore collectionStore;
    private long collectionSavedRevision = -1;
    private final QuestStore store;
    private final Path resourceFile;
    private final ProgressionService progression;
    private final ProgressionStore progressionStore;
    private final MinecraftProgression training;
    private final ArcheryServer archery;
    private final FootworkServer footwork;
    private final Path archeryFile;
    private long progressionSavedRevision=-1;
    private final Map<UUID,Long> rapierHasteUntil=new HashMap<>();
    private final Map<UUID,String> bowEquipment=new HashMap<>();
    private final Map<UUID, CombatActor> actors = new HashMap<>();
    private record EnemyStun(LivingEntity entity, long untilGameTick) {}
    private final Map<UUID, EnemyStun> enemyStuns = new HashMap<>();
    private CreatureCombatCatalog validatedCreatureCombat;
    private GuardCueSettings validatedGuardCues;
    private final CreatureMeleeServer creatureMelee;
    private static final class GuardPress {boolean held;long serial;}
    private final Map<UUID,GuardPress> guardPresses=new HashMap<>();
    private record GuardedDamage(net.minecraft.world.damagesource.DamageSource source,long gameTick){}
    private final Map<UUID,GuardedDamage> guardedDamage=new HashMap<>();
    private final Map<UUID,SpearHoldState> spearHolds=new HashMap<>();
    private final Map<UUID,Long> spearInputs=new HashMap<>();
    private final Map<UUID,Vec3> spearOrigins=new HashMap<>();
    private record SpearRay(Vec3 origin,Vec3 tip,double padding,SpearSwipe.Vector swipe) {}
    private final Map<UUID,List<SpearRay>> spearRays=new HashMap<>();
    private final Map<UUID,AABB> spearBounds=new HashMap<>();
    private final Map<UUID, WeaponDrawController> drawControllers = new HashMap<>();
    private final Map<UUID, Resources> resources = new HashMap<>();
    private final Map<UUID, Vec3> dodgeDirections = new HashMap<>();
    private final Map<UUID,Long> burstRequests=new HashMap<>();
    private final Map<UUID, String> swingItems = new HashMap<>();
    private final Map<UUID, Long> requestTicks = new HashMap<>();
    private final Map<UUID, Integer> requestCounts = new HashMap<>();
    private final Map<UUID,String> swingOffhands=new HashMap<>(), damageItems=new HashMap<>();
    private final Map<UUID, String> animationStates = new HashMap<>();
    private final Map<UUID, Long> animationSequences = new HashMap<>();
    private final Map<UUID, WeaponStyleDefinition> pendingStyles = new HashMap<>();
    private final RapierTargetLock rapierTargets = new RapierTargetLock();
    private final Map<UUID, RapierTargetLock.Snapshot> sentTargetLocks = new HashMap<>();
    private boolean resolvingDamage;
    private UUID suppressedSpearKnockback;
    private long ticks;
    private long savedRevision = -1;
    private record Resources(double stamina, double spirit, String weaponStyle) {}
    private record PendingHit(ServerPlayer attacker, LivingEntity victim, org.fsrpg.combat.HitResult result, double damageMultiplier,SpearSwipe.Vector swipe) {}
    private record MeleeHit(ServerPlayer attacker, LivingEntity victim, HitResult result, double damage,
            SpearSwipe.Vector swipe, long sequence, long opponentSequence, String mainItem, String offItem,
            boolean dual, boolean thrust, boolean execution, double knockX, double knockZ,
            net.minecraft.server.level.ServerLevel level) {}
    private final WeaponClash<MeleeHit> meleeContacts = new WeaponClash<>((a,b)->
            a.opponentSequence()==b.sequence() && b.opponentSequence()==a.sequence());
    private final List<MeleeHit> ordinaryMelee = new ArrayList<>();
    private final Map<CombatActor,SwingActivationGate> creatureActivations=new WeakHashMap<>();
    private final Map<CombatActor,Long> clashedBashReservations=new WeakHashMap<>();
    private static final class WindowClashAttempt {
        final MeleeHit hit;final long creatureSequence;boolean settled;
        WindowClashAttempt(MeleeHit hit,long creatureSequence){this.hit=hit;this.creatureSequence=creatureSequence;}
    }
    private WindowClashAttempt windowClashAttempt;
    private record EnemyCounter(long untilTick, double multiplier) {}
    private final Map<UUID,EnemyCounter> enemyCounters=new HashMap<>();
    private long meleeClashes, balancedClashes, advantageClashes;
    public long meleeClashes(){return meleeClashes;}
    public long balancedClashes(){return balancedClashes;}
    public long advantageClashes(){return advantageClashes;}
    public int perfectGuardCounterRemaining(Entity entity) {
        if(entity instanceof ServerPlayer p)return actor(p).counterRemainingTicks();
        EnemyCounter c=enemyCounters.get(entity.getUUID());
        return c==null?0:(int)Math.max(0,c.untilTick()-entity.level().getGameTime());
    }
    /** Consume at attack acceptance; the complete windup then retains this multiplier. */
    public double consumeMeleeCounter(LivingEntity entity) {
        EnemyCounter c=enemyCounters.remove(entity.getUUID());
        return c!=null&&entity.level().getGameTime()<c.untilTick()?c.multiplier():1;
    }

    public static RpgServer instance() { return instance; }
    public ProgressionService progression(){return progression;}
    public RpgConfig config(){return config;}
    public ArcheryServer archery(){return archery;}
    public FootworkServer footwork(){return footwork;}
    public CreatureMeleeServer creatureMelee(){return creatureMelee;}
    public RapierTargetLock.Snapshot targetLock(ServerPlayer player){return rapierTargets.snapshot(player,actor(player));}
    private RpgServer(MinecraftServer server) throws IOException {
        this.server = server;
        config = new RpgConfig(FMLPaths.CONFIGDIR.get().resolve("fsrpg"));
        config.setValidator(this::validateRegistryIds);
        var definitions = config.reload();
        creatureMelee = new CreatureMeleeServer(validatedCreatureCombat, this::perfectGuardStunRemaining,validatedGuardCues);
        quests = new QuestService(definitions.quests());
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("data/fsrpg");
        store = new QuestStore(data.resolve("quests.json"), RpgConfig.JSON);
        quests.restore(store.load());
        collections = new CollectionService(definitions.collections());
        collectionStore = new CollectionStore(data.resolve("collections.json"), RpgConfig.JSON);
        collections.restore(collectionStore.load());
        resourceFile = data.resolve("resources.json");
        if (Files.exists(resourceFile)) {
            JsonObject dataJson = JsonParser.parseString(Files.readString(resourceFile)).getAsJsonObject();
            for (var entry : dataJson.entrySet()) {
                Resources value = RpgConfig.JSON.fromJson(entry.getValue(), Resources.class);
                if (value == null || !Double.isFinite(value.stamina()) || !Double.isFinite(value.spirit()) || value.stamina() < 0 || value.spirit() < 0)
                    throw new IOException("Invalid resources for " + entry.getKey());
                resources.put(UUID.fromString(entry.getKey()), value);
            }
        }
        progression=new ProgressionService(definitions.skillTrees());
        progressionStore=new ProgressionStore(data.resolve("skill_trees.json"),RpgConfig.JSON);
        progression.restore(progressionStore.load());
        training=new MinecraftProgression(progression,()->config.current().skillTrees(),resources.keySet());
        footwork=new FootworkServer(progression,()->config.current().skillTrees().rules().footwork(),training::forced);
        archeryFile=data.resolve("archery.json");
        archery=new ArcheryServer(definitions.archery(),new ArcheryServer.Sink(){
            @Override public void changed(ServerPlayer player){send(player,"",null);broadcastAnimation(player,true);}
            @Override public void defenseContact(ServerPlayer player){actor(player).markCombatActivity();}
            @Override public void skillUsed(UUID player,String skill){quests.useSkill(player,skill);}
            @Override public void kill(UUID player){progression.recordArcheryKill(player,MinecraftProgression.epochDay());}
            @Override public double damageBonus(UUID player){return progression.effects(player).damageBonus();}
            @Override public boolean meleeContact(ServerPlayer player,LivingEntity target,double damage,double knockback,long reservation){
                captureBash(player,target,damage,knockback,reservation);return true;
            }
        });
        archery.restore(archeryFile);
        LOG.info("FSRPG loaded {} skills, {} tree nodes and {} quests from {}", definitions.skills().size(), definitions.skillTrees().nodes().size(), definitions.quests().definitions().size(), config.directory());
    }
    public static void register(IEventBus bus) {
        bus.addListener((ServerStartingEvent event) -> {
            try { instance = new RpgServer(event.getServer()); }
            catch (Exception failure) { throw new IllegalStateException("FSRPG configuration/progress could not load. Existing files were preserved.", failure); }
        });
        bus.addListener((ServerStoppingEvent event) -> { if (instance != null) {
            instance.flushAllMelee();
            instance.archery.settleAllProjectiles();
            instance.actors.values().forEach(CombatActor::forceSheathed);
            instance.save();
        } });
        bus.addListener((net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) -> {
            if(instance!=null&&!event.getLevel().isClientSide()){
                instance.flushMelee(event.getEntity());instance.creatureMelee.cancel(event.getEntity());
                instance.enemyCounters.remove(event.getEntity().getUUID());instance.archery.onEntityLeave(event.getEntity());
            }
        });
        bus.addListener((LivingDamageEvent.Post event) -> {if(instance!=null)instance.archery.onDamagePost(event);});
        bus.addListener((LivingDamageEvent.Post event) -> {if(instance!=null)instance.guardedDamageCommitted(event);});
        bus.addListener((ServerStoppedEvent event) -> instance = null);
        bus.addListener((ServerTickEvent.Post event) -> { if (instance != null) instance.tick(); });
        bus.addListener((EntityTickEvent.Pre event) -> { if (instance != null) instance.enemyTick(event); });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> { if (instance != null && event.getEntity() instanceof ServerPlayer p) { instance.actor(p); instance.quests.ensurePlayer(p.getUUID()); instance.recoverCollection(p); instance.reconcileWeapon(p); instance.send(p, "", null); instance.broadcastAnimation(p, true); } });
        bus.addListener((PlayerEvent.StartTracking event) -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target)
                PacketDistributor.sendToPlayer(viewer, instance.animationPayload(target));
            if (instance != null && event.getEntity() instanceof ServerPlayer viewer)
                instance.creatureMelee.startTracking(viewer,event.getTarget());
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> { if (instance != null && event.getEntity() instanceof ServerPlayer p) instance.logout(p); });
        bus.addListener((PlayerEvent.PlayerRespawnEvent event) -> { if (instance != null && event.getEntity() instanceof ServerPlayer p) instance.resetActor(p); });
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event)->{if(instance!=null&&event.getEntity() instanceof ServerPlayer p){
            instance.flushMelee(p);instance.creatureMelee.cancel(p);
            instance.burstRequests.remove(p.getUUID());instance.actor(p).cancelBurstDashInput();
            instance.footwork.remove(p.getUUID());
        }});
        bus.addListener((LivingEvent.LivingJumpEvent event) -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer p) instance.footwork.onJump(p);
        });
        bus.addListener((LivingFallEvent event) -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer p && instance.footwork.fallImmune(p))
                event.setCanceled(true);
        });
        bus.addListener((LivingDeathEvent event) -> {
            if (instance != null) {
                instance.enemyStuns.remove(event.getEntity().getUUID());
                instance.enemyCounters.remove(event.getEntity().getUUID());
                instance.creatureMelee.cancel(event.getEntity());
                if (event.getEntity() instanceof ServerPlayer dead) { instance.actor(dead).forceSheathed(); instance.footwork.remove(dead.getUUID()); }
            }
            if(instance!=null)instance.archery.onDeath(event);
            if (instance != null && event.getSource().getEntity() instanceof ServerPlayer p) {
                String entity = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString();
                instance.quests.kill(p.getUUID(), entity, 1);
                if(MinecraftProgression.hostile(event.getEntity())&&event.getSource().is(DamageTypeTags.IS_PLAYER_ATTACK)
                        &&instance.actor(p).drawn()&&instance.actor(p).style().presentationStyleId().equals("rapier"))
                    instance.progression.recordRapierKill(p.getUUID(),MinecraftProgression.epochDay());
                if(entity.equals("minecraft:ender_dragon")){instance.progression.dragonKilled(p.getUUID());instance.progression.bossDefeated(p.getUUID());}
                else if(entity.equals("minecraft:wither"))instance.progression.bossDefeated(p.getUUID());
                if (event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy) {
                    instance.recoverCollection(p);
                    if (instance.collections.pending(p.getUUID()).isEmpty() && instance.collections.firstKill(p.getUUID(), entity)) {
                        instance.save(); instance.send(p, "首次猎杀完成，可以提交生物掉落物了", null);
                    }
                }
            }
        });
        bus.addListener((ItemEntityPickupEvent.Post event) -> {
            if (instance != null && event.getPlayer() instanceof ServerPlayer p) {
                ItemStack original = event.getOriginalStack();
                int acquired = original.getCount() - event.getCurrentStack().getCount();
                if (acquired > 0) instance.quests.collect(p.getUUID(), BuiltInRegistries.ITEM.getKey(original.getItem()).toString(), acquired);
            }
        });
        bus.addListener((AttackEntityEvent event) -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer p) {
                instance.reconcileAndSync(p);
                CombatActor a = instance.actor(p);
                if (a.drawn() || a.phase() == CombatPhase.DRAWING || a.phase() == CombatPhase.SHEATHING) event.setCanceled(true);
            }
        });
        bus.addListener((PlayerInteractEvent.RightClickItem event) -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer p) {
                instance.reconcileAndSync(p);
                if (instance.actor(p).drawn()) event.setCanceled(true);
            }
        });
        bus.addListener((LivingIncomingDamageEvent event) -> { if (instance != null) instance.incoming(event); });
        bus.addListener((LivingDamageEvent.Pre event)->{if(instance!=null)instance.archery.onDamagePre(event);});
        bus.addListener((LivingShieldBlockEvent event)->{if(instance!=null){
            instance.archery.onShieldBlock(event);
            if(event.getBlocked()&&event.getBlockedDamage()>0&&event.getEntity() instanceof ServerPlayer player)
                instance.actor(player).markCombatActivity();
        }});
        bus.addListener((LivingDamageEvent.Post event)->{if(instance!=null&&event.getEntity() instanceof ServerPlayer player)
            instance.training.damage(player,event.getSource().getEntity(),event.getNewDamage());});
        bus.addListener((net.neoforged.neoforge.event.level.SleepFinishedTimeEvent event)->{
            if(instance!=null)for(var player:event.getLevel().players())if(player instanceof ServerPlayer p&&p.isSleeping())instance.training.sleep(p);
        });
        bus.addListener((LivingEntityUseItemEvent.Start event)->{if(instance!=null&&event.getEntity() instanceof ServerPlayer p)instance.training.foodStart(p,event.getItem());});
        bus.addListener((LivingEntityUseItemEvent.Stop event)->{if(instance!=null&&event.getEntity() instanceof ServerPlayer p)instance.training.foodStop(p);});
        bus.addListener((LivingEntityUseItemEvent.Finish event)->{if(instance!=null&&event.getEntity() instanceof ServerPlayer p)instance.training.foodEnd(p,event.getItem());});
        bus.addListener((net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event)->{if(instance!=null&&!event.isCanceled()&&event.getPlayer() instanceof ServerPlayer p)instance.progression.onMined(p.getUUID(),1);});
        bus.addListener((PlayerEvent.BreakSpeed event)->{if(instance!=null&&event.getEntity() instanceof ServerPlayer p){
            instance.training.ensure(p);double factor=instance.progression.effects(p.getUUID()).miningTimeFactor();
            event.setNewSpeed((float)(event.getNewSpeed()/factor));
        }});
        bus.addListener((LivingKnockBackEvent event)->{
            if(instance!=null&&event.getEntity().getUUID().equals(instance.suppressedSpearKnockback)){event.setCanceled(true);return;}
            if(instance!=null&&event.getEntity() instanceof ServerPlayer armored&&instance.actor(armored).swordSuperArmor()){event.setCanceled(true);return;}
            if(instance!=null&&event.getEntity() instanceof ServerPlayer p&&event.getStrength()>0)instance.training.forced(p,10);
        });
        bus.addListener((net.neoforged.neoforge.event.entity.EntityTeleportEvent event)->{if(instance!=null){
            instance.flushMelee(event.getEntity());instance.creatureMelee.cancel(event.getEntity());
            if(event.getEntity() instanceof ServerPlayer p)instance.training.forced(p,10);
        }});
        bus.addListener((ProjectileImpactEvent event) -> { if (instance != null) instance.projectile(event); });
        bus.addListener(RpgServer::commands);
    }
    private CombatActor actor(ServerPlayer p) {
        training.ensure(p);
        CombatActor current = actors.computeIfAbsent(p.getUUID(), id -> {
            CombatActor actor = new CombatActor(id, config.current().combat());
            actor.setPlayerLevel(p.experienceLevel);
            Resources value = resources.get(id);
            if (value != null) actor.restoreSavedResources(value.stamina(), value.spirit());
            else actor.setResources(actor.maxStamina(),actor.spirit());
            WeaponStyleDefinition selected = value == null ? null : config.current().styles().get(value.weaponStyle());
            if (selected == null || !selected.enabled()) selected = defaultStyle(config.current());
            accepted(actor.setStyle(selected));
            return actor;
        });
        current.setPlayerLevel(p.experienceLevel);
        return current;
    }
    private void resetActor(ServerPlayer p) {
        flushMelee(p);creatureMelee.cancel(p);
        burstRequests.remove(p.getUUID());
        archery.remove(p.getUUID());cancelSpear(p);
        guardPresses.remove(p.getUUID());
        guardedDamage.remove(p.getUUID());
        CombatActor previous = actors.remove(p.getUUID());
        if (previous != null) { previous.forceSheathed(); resources.put(p.getUUID(), persisted(previous)); }
        dodgeDirections.remove(p.getUUID()); swingItems.remove(p.getUUID());
        pendingStyles.remove(p.getUUID());
        drawControllers.remove(p.getUUID());
        rapierTargets.remove(p.getUUID()); sentTargetLocks.remove(p.getUUID());
        archery.remove(p.getUUID());training.remove(p.getUUID());footwork.remove(p.getUUID());
        actor(p); reconcileWeapon(p); send(p, "", null); broadcastAnimation(p, true);
    }
    private void logout(ServerPlayer p) {
        flushMelee(p);creatureMelee.cancel(p);
        burstRequests.remove(p.getUUID());
        archery.remove(p.getUUID());cancelSpear(p);
        guardPresses.remove(p.getUUID());
        guardedDamage.remove(p.getUUID());
        CombatActor a = actors.remove(p.getUUID());
        if (a != null) { a.forceSheathed(); resources.put(p.getUUID(), persisted(a)); }
        dodgeDirections.remove(p.getUUID()); swingItems.remove(p.getUUID());
        pendingStyles.remove(p.getUUID());
        drawControllers.remove(p.getUUID());
        rapierTargets.remove(p.getUUID()); sentTargetLocks.remove(p.getUUID());
        requestCounts.remove(p.getUUID()); requestTicks.remove(p.getUUID()); save();
        swingOffhands.remove(p.getUUID());damageItems.remove(p.getUUID());
        animationStates.remove(p.getUUID()); animationSequences.remove(p.getUUID());
        archery.remove(p.getUUID());training.remove(p.getUUID());rapierHasteUntil.remove(p.getUUID());
        footwork.remove(p.getUUID());
    }
    public boolean canEdit(ServerPlayer p) { return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER); }
    public void action(ServerPlayer p, String action, String argument) {
        if (!p.isAlive()) return;
        if (requestTicks.getOrDefault(p.getUUID(), -1L) != ticks) { requestTicks.put(p.getUUID(), ticks); requestCounts.put(p.getUUID(), 0); }
        int count = requestCounts.merge(p.getUUID(), 1, Integer::sum);
        if (count > 20) return;
        String message = "";
        JsonObject editResult = null;
        try {
            CombatActor a = actor(p);
            applyPendingStyle(p);
            reconcileWeapon(p);
            refreshTargetLock(p, false);
            switch (action) {
                case "status" -> { }
                case "draw" -> {
                    if(rapierTargets.snapshot(p,a).locked()) {
                        rapierTargets.unlock(p,a);refreshTargetLock(p,true);break;
                    }
                    var result = drawController(p).cycle(a, usableStyles(p), config.current().weapons(), mainItem(p), offhandItem(p), p.isAlive());
                    if (result == ActionResult.NOT_DRAWN) throw new IllegalStateException("请先手持配置中的战斗武器");
                    if (result == ActionResult.BUSY) throw new IllegalStateException("请等当前动作结束再切换拔刀状态");
                    accepted(result);
                    pendingStyles.remove(p.getUUID());
                    archery.cancelCharge(p);archery.reconcile(p,a,archeryProgress(p));
                }
                case "learnSkill" -> {
                    boolean creative = p.isCreative();
                    var result = creative ? progression.learnFree(p.getUUID(),argument) : progression.learn(p.getUUID(),argument);
                    if(!result.success())throw new IllegalStateException(result.reason());
                    save();reconcileWeapon(p);message = creative ? result.reason() : "技能已学会";
                }
                case "unlearnSkill" -> {
                    requireCreative(p);
                    var result=progression.unlearn(p.getUUID(),argument);
                    if(!result.success())throw new IllegalStateException(result.reason());
                    save();reconcileWeapon(p);message=result.reason();
                }
                case "progressionFill" -> {
                    requireCreative(p);
                    progression.creativeFill(p.getUUID());
                    save();reconcileWeapon(p);message="已补满修行进度";
                }
                case "progressionReset" -> {
                    requireCreative(p);
                    progression.creativeReset(p.getUUID());
                    save();reconcileWeapon(p);message="已清空修行与已学技能，可重新选择";
                }
                case "archeryCharge" -> accepted(archery.charge(p,a,archeryProgress(p),booleanArgument(argument)));
                case "archeryCancel" -> archery.cancelCharge(p);
                case "archeryAuto" -> accepted(archery.auto(p,a,archeryProgress(p),booleanArgument(argument)));
                case "archeryBash" -> accepted(archery.bash(p,a,archeryProgress(p)));
                case "rapierCharge" -> {
                    if(booleanArgument(argument))beginRapierCharge(p);else accepted(a.releaseChargedAttack());
                    moveCharge(p);startedSkills(p);
                }
                case "rapierCancel" -> a.cancelChargedPreparation();
                case "parry" -> {if(!argument.isBlank())throw new IllegalArgumentException("parry takes no argument");if(!hasWeapon(p))throw new IllegalStateException("请先手持剑");accepted(a.requestSwordParry());message="振刀：只迎击重击，普攻可打断";}
                case "spearPress", "spearMove", "spearRelease" -> {spearIntent(p,a,action,argument);startedSkills(p);}
                case "targetAttack" -> targetAttack(p,a,argument);
                case "aimAttack" -> {
                    if(!hasWeapon(p)) throw new IllegalStateException("当前物品不匹配所选武器档位");
                    JsonObject request=JsonParser.parseString(argument).getAsJsonObject();
                    var aim=RpgConfig.JSON.fromJson(request.get("aim"),org.fsrpg.combat.AttackAim.class);
                    if(aim==null) throw new IllegalArgumentException("missing aim");
                    String button=request.get("button").getAsString();
                    var controls=a.style().controls();
                    if(!controls.dual()&&!controls.spear()) throw new IllegalArgumentException("style does not accept cursor attacks");
                    for(var point:aim.points()) if(controls.spear() && !org.fsrpg.combat.SpearAimField.contains(point,p.getYRot()))
                        throw new IllegalArgumentException("aim outside forward field");
                    if(controls.dual()) {
                        if(!java.util.Set.of("left","right").contains(button)||aim.points().size()!=1) throw new IllegalArgumentException("invalid dual input");
                        // Player yaw comes through the normal movement channel; orbit never changes it.
                        var result=a.aimedInput(button.equals("left")?SkillInput.LEFT:SkillInput.RIGHT,skillsFor(p),org.fsrpg.combat.AttackAim.at(aim.sample(0).yaw(),0));
                        if(!result.accepted()) throw new IllegalStateException(comboRejection(result));
                    } else {
                        if(!java.util.Set.of("left","sweep").contains(button)) throw new IllegalArgumentException("invalid spear gesture");
                        if(button.equals("left")&&aim.points().size()!=1) throw new IllegalArgumentException("thrust requires one point");
                        var skill=config.current().skills().get(button.equals("sweep")?controls.sweepSkill():a.style().leftCombo().getFirst().skillId());
                        accepted(button.equals("left") ? a.aimedLeftSkill(skill,aim) : a.aimedSkill(skill,aim));
                    }
                    startedSkills(p);
                }
                case "attack" -> {
                    if(ArcheryServer.bowStyle(a)) {
                        if(argument.equals("left")||argument.equals("bow_bash"))accepted(archery.bash(p,a,archeryProgress(p)));
                        else if(argument.equals("right")||argument.equals("skill")||argument.endsWith("_charge"))accepted(archery.charge(p,a,archeryProgress(p),true));
                        else throw new IllegalStateException("弓箭需要实际拉弓、弹药和服务端放箭判定");
                        break;
                    }
                    if(a.style()!=null&&a.style().presentationStyleId().equals("rapier")&&(argument.equals("right")||argument.equals("skill")||argument.equals("sword_thrust"))) {
                        beginRapierCharge(p);startedSkills(p);break;
                    }
                    if (argument.equals("left") || argument.equals("right")) {
                        if (!hasWeapon(p)) throw new IllegalStateException("当前物品不匹配所选武器档位");
                        var result = a.aimedInput(argument.equals("left") ? SkillInput.LEFT : SkillInput.RIGHT, skillsFor(p),attackAim(p));
                        if (!result.accepted()) throw new IllegalStateException(comboRejection(result));
                        if (!result.started()) message = result.status() == ComboInputStatus.BUFFERED ? "输入已缓冲" : "下一段已排队";
                    } else {
                        SkillDefinition skill = selectSkill(p, argument);
                        accepted(a.aimedSkill(skill,attackAim(p)));
                    }
                    startedSkills(p);
                }
                case "weaponStyle" -> {
                    WeaponStyleDefinition style = config.current().styles().get(argument);
                    if (style == null) throw new IllegalArgumentException("未知武器档位：" + argument);
                    if(!progression.weaponUnlocked(p.getUUID(),style.presentationStyleId()))throw new IllegalStateException("请先在 P 技能树学习该武器");
                    if (!style.enabled()) throw new IllegalStateException(style.label() + "尚未配置动作与技能");
                    if (!matchingStyles(p).contains(style)) throw new IllegalStateException("这个拔刀状态不适用于当前手持武器");
                    if (a.phase() != CombatPhase.READY && a.phase() != CombatPhase.SHEATHED) throw new IllegalStateException("请等当前动作结束再切换拔刀状态");
                    accepted(a.setStyle(style));
                    accepted(a.requestDrawn());
                    pendingStyles.remove(p.getUUID());
                    resources.put(p.getUUID(), persisted(a)); message = "武器档位：" + style.label();
                }
                case "guard" -> {
                    if (!List.of("true", "false", "light", "heavy", "held", "held_heavy", "suspend").contains(argument)) throw new IllegalArgumentException("Invalid guard intent");
                    GuardPress press=guardPresses.computeIfAbsent(p.getUUID(),id->new GuardPress());
                    boolean physicalHeld=!argument.equals("false"),held=physicalHeld&&!argument.equals("suspend");
                    boolean fresh=held&&!press.held&&(argument.equals("true")||argument.equals("light")||argument.equals("heavy"));
                    press.held=physicalHeld;if(fresh)press.serial++;
                    if (held && !hasWeapon(p)) throw new IllegalStateException("请先手持战斗武器");
                    boolean heavy=argument.equals("heavy")||argument.equals("held_heavy");
                    boolean opportunity=!a.swordCombatEnabled()&&fresh&&creatureMelee.guardOpportunity(p);
                    // GUI/equipment suspension is not a physical release and cannot reset
                    // the three-perfect chain or invent a fresh window when input resumes.
                    if(held&&!fresh&&!a.guardHeld())a.suspendGuarding();
                    ActionResult result=argument.equals("suspend")?a.suspendGuarding()
                            :opportunity&&validatedGuardCues.guardCancelsActive()?a.requestPrecisionGuard(heavy):a.setGuarding(held,heavy);
                    accepted(result);
                    if(opportunity)creatureMelee.authorizeGuardPress(p,press.serial);
                }
                case "dodge" -> {
                    Vec3 intended = rapierDodgeDirection(a, argument);
                    archery.cancelCharge(p);training.forced(p,20);
                    accepted(a.requestDodge(p.onGround()));
                    Vec3 direction = intended != null ? intended : p.getLastClientMoveIntent().multiply(1, 0, 1);
                    if (direction.lengthSqr() < 0.001) direction = p.getLookAngle().multiply(1, 0, 1);
                    dodgeDirections.put(p.getUUID(), direction.normalize());
                }
                case "burstDash" -> burstDashIntent(p,a,argument);
                case "footwork" -> footwork.action(p, argument);
                case "acceptQuest" -> { quests.accept(p.getUUID(), argument); saveQuests(); message = "已接受任务"; }
                case "claimQuest" -> { claim(p, argument); message = "已领取奖励"; }
                case "collectionSubmitMob", "collectionSubmitCrop", "collectionClaimSeeds" -> {
                    recoverCollection(p);
                    CollectionService.Plan plan;
                    if (action.equals("collectionSubmitMob")) plan = collections.planMob(p.getUUID(), argument, CollectionInventory.owned(p));
                    else if (action.equals("collectionSubmitCrop")) plan = collections.planCrop(p.getUUID(), argument, CollectionInventory.owned(p));
                    else {
                        String[] choice = argument.split("\\|", -1);
                        if (choice.length != 2) throw new IllegalArgumentException("请选择要领取的其他作物种子");
                        plan = collections.planSeedChoice(p.getUUID(), choice[0], choice[1]);
                    }
                    exchangeCollection(p, plan);
                    message = action.equals("collectionSubmitMob") ? "生物提交完成，已获得掉落物与达成的里程碑奖励"
                            : action.equals("collectionSubmitCrop") ? "作物提交完成，请选择其他作物的种子奖励" : "已领取 5 个种子与 3 个骨头";
                }
                case "reload" -> {
                    requireEditor(p);
                    applyConfig(config.reload()); message = "FSRPG 配置重载完成";
                }
                case "editSkill" -> {
                    requireEditor(p);
                    JsonObject edit = JsonParser.parseString(argument).getAsJsonObject();
                    String id = edit.get("id").getAsString();
                    applyConfig(config.editSkill(id, edit.getAsJsonObject("changes")));
                    editResult = editResult(id, true, "已保存并应用到服务端");
                }
                default -> throw new IllegalArgumentException("Unknown action " + action);
            }
        } catch (Exception error) {
            Throwable detail = error;
            while (detail.getCause() != null && detail.getCause() != detail) detail = detail.getCause();
            message = collectionMessage(detail.getMessage() == null ? "请求未通过校验" : detail.getMessage());
            if (action.equals("editSkill")) {
                String id = "";
                try { id = JsonParser.parseString(argument).getAsJsonObject().get("id").getAsString(); } catch (Exception ignored) {}
                editResult = editResult(id, false, message);
            }
            p.sendSystemMessage(Component.literal("[FSRPG] " + message));
        }
        refreshTargetLock(p, false);
        broadcastAnimation(p, false);
        if(!action.equals("spearMove")&&!action.equals("burstDash")||!message.isEmpty())send(p, message, editResult);
    }
    private void burstDashIntent(ServerPlayer p,CombatActor actor,String argument) {
        String[] parts=argument.trim().split("\\s+");
        if(parts.length==1&&parts[0].equals("up")){accepted(actor.requestBurstDash(false,0,0,p.onGround()));return;}
        if(parts.length!=7||!parts[0].equals("down"))throw new IllegalArgumentException("invalid grounded Shift intent");
        long request=Long.parseLong(parts[3]),clientTick=Long.parseLong(parts[4]);
        int entityId=Integer.parseInt(parts[5]);long now=p.level().getGameTime();
        if(request<=0||entityId!=p.getId()||!parts[6].equals(p.level().dimension().identifier().toString())
                ||clientTick<0||now-clientTick>20||clientTick-now>5)throw new IllegalArgumentException("expired Shift input context");
        long previousRequest=burstRequests.getOrDefault(p.getUUID(),0L);
        if(request<previousRequest||request==previousRequest&&!actor.burstDashSnapshot().held())return;
        var direction=RapierTargeting.dodgeDirection(Double.parseDouble(parts[1]),Double.parseDouble(parts[2]));
        burstRequests.put(p.getUUID(),request);
        long before=actor.burstDashSnapshot().sequence();
        boolean grounded=p.onGround()&&!p.isPassenger()&&!p.isFallFlying()&&!p.isInWater()&&!p.isInLava();
        accepted(actor.requestBurstDash(true,direction.x(),direction.z(),grounded));
        if(before==actor.burstDashSnapshot().sequence())return;
        var impulse=actor.consumeBurstDashImpulse();
        if(impulse.moving()) {
            Vec3 velocity=p.getDeltaMovement();p.setDeltaMovement(impulse.x(),velocity.y,impulse.z());
            PacketDistributor.sendToPlayer(p,new RpgBurstDashPayload(p.getUUID(),p.getId(),p.level().dimension().identifier().toString(),
                    request,impulse.sequence(),now,impulse.x(),impulse.z()));
        }
        training.forced(p,10);send(p,"",null);
    }
    private void targetAttack(ServerPlayer p,CombatActor actor,String argument) {
        if(!hasWeapon(p)||actor.style()==null||actor.style().controls().spear())throw new IllegalStateException("请先持武器进入拔刀模式");
        JsonObject request=JsonParser.parseString(argument).getAsJsonObject();
        String button=request.get("button").getAsString();
        if(!Set.of("left","right","skill","dual_combined").contains(button))throw new IllegalArgumentException("invalid target attack button");
        AttackAim aim=RpgConfig.JSON.fromJson(request.get("aim"),AttackAim.class);
        if(aim==null||aim.points().size()!=1)throw new IllegalArgumentException("target attack requires one eye direction");
        var previous=rapierTargets.target(p);
        LivingEntity selected=previous;
        if(selected==null&&request.has("targetId")) {
            int id=request.get("targetId").getAsBigDecimal().intValueExact();
            String uuid=request.has("targetUuid")?request.get("targetUuid").getAsString():"";
            selected=crosshairTarget(p,actor,id,uuid,aim.points().getFirst());
        }
        if(selected!=null) {
            var delta=selected.getBoundingBox().getCenter().subtract(p.getEyePosition());
            aim=new AttackAim(List.of(RapierTargeting.direction(delta.x,delta.y,delta.z,p.getYRot())));
        }
        var facing=aim.points().getFirst();p.setYRot((float)facing.yaw());p.setXRot((float)facing.pitch());p.setYHeadRot((float)facing.yaw());
        if(ArcheryServer.bowStyle(actor)) {
            if(button.equals("left"))accepted(archery.bash(p,actor,archeryProgress(p)));
            else accepted(archery.charge(p,actor,archeryProgress(p),true));
        } else if(actor.style().presentationStyleId().equals("rapier")&&!button.equals("left"))beginRapierCharge(p);
        else if(button.equals("left")||button.equals("right")) {
            var result=actor.aimedInput(button.equals("left")?SkillInput.LEFT:SkillInput.RIGHT,skillsFor(p),aim);
            if(!result.accepted())throw new IllegalStateException(comboRejection(result));
        } else accepted(actor.aimedSkill(selectSkill(p,button),aim));
        if(previous==null&&selected!=null)rapierTargets.acquire(p,actor,true,selected);
        refreshTargetLock(p,true);startedSkills(p);
    }
    private LivingEntity crosshairTarget(ServerPlayer p,CombatActor actor,int entityId,String uuid,AttackAim.Point aim) {
        if(entityId<0||!actor.rules().rapierTargetLock().enabled()||!(p.level().getEntity(entityId) instanceof Mob target)
                ||!target.getUUID().toString().equals(uuid)||!target.isAlive()||target.isRemoved()||!target.isAttackable()||target.isInvulnerable()
                ||p.isAlliedTo(target)||target.isAlliedTo(p)||target instanceof TamableAnimal animal&&animal.isTame())return null;
        var rules=actor.rules().rapierTargetLock();
        if(p.distanceToSqr(target)>rules.range()*rules.range()||!p.hasLineOfSight(target)
                ||!rules.allowPassive()&&!MinecraftProgression.hostile(target))return null;
        Vec3 eye=p.getEyePosition(),end=eye.add(Vec3.directionFromRotation((float)aim.pitch(),(float)aim.yaw()).scale(rules.range()+4));
        var box=target.getBoundingBox().inflate(.15);var hit=box.contains(eye)?Optional.of(eye):box.clip(eye,end);
        if(hit.isEmpty())return null;
        double distance=hit.get().distanceToSqr(eye);
        var wall=p.level().clip(new net.minecraft.world.level.ClipContext(eye,hit.get(),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,p));
        if(wall.getType()!=net.minecraft.world.phys.HitResult.Type.MISS&&wall.getLocation().distanceToSqr(eye)+.001<distance)return null;
        for(var other:p.level().getEntitiesOfClass(LivingEntity.class,new AABB(eye,hit.get()).inflate(.15),e->e!=p&&e!=target&&e.isAlive()&&e.isPickable())) {
            var otherBox=other.getBoundingBox().inflate(.1);var obstruction=otherBox.contains(eye)?Optional.of(eye):otherBox.clip(eye,end);
            if(obstruction.isPresent()&&obstruction.get().distanceToSqr(eye)+.01<distance)return null;
        }
        return target;
    }
    private void spearIntent(ServerPlayer p,CombatActor actor,String action,String argument) {
        JsonObject request=JsonParser.parseString(argument).getAsJsonObject();
        long input=request.get("input").getAsBigDecimal().longValueExact();
        if(input<1)throw new IllegalArgumentException("invalid spear input sequence");
        SpearHoldState state=spearHolds.get(p.getUUID());
        if(action.equals("spearRelease")) {
            if(state==null||!Objects.equals(spearInputs.get(p.getUUID()),input))return;
            if(request.has("aim")) {
                AttackAim finalAim=RpgConfig.JSON.fromJson(request.get("aim"),AttackAim.class);
                if(finalAim!=null)state.update(p.level().getGameTime(),state.sequence(),finalAim,p.getYRot());
            }
            state.release(p.level().getGameTime(),state.sequence());
            actor.setSpearAttackHeld(false,state.point()==null?null:new AttackAim(List.of(state.point())));
            broadcastAnimation(p,true);return;
        }
        if(!hasWeapon(p)||actor.style()==null||!actor.style().controls().spear())throw new IllegalStateException("请先持长枪");
        AttackAim aim=RpgConfig.JSON.fromJson(request.get("aim"),AttackAim.class);
        if(aim==null)throw new IllegalArgumentException("missing spear aim");
        for(var point:aim.points())if(!SpearAimField.contains(point,p.getYRot()))throw new IllegalArgumentException("aim outside forward field");
        if(action.equals("spearPress")) {
            if(state!=null&&state.held(p.level().getGameTime()))return;
            if(aim.points().size()!=1)throw new IllegalArgumentException("spear press needs one direction");
            // This lease must belong to a newly paid swing. General sword buffering
            // would otherwise attach a second press to the old ACTIVE swing and hold it forever.
            if(actor.currentSkill()!=null)return;
            SkillDefinition thrust=config.current().skills().get(actor.style().leftCombo().getFirst().skillId());
            accepted(actor.aimedLeftSkill(thrust,aim));
            state=new SpearHoldState();
            if(!state.begin(p.level().getGameTime(),actor.snapshot().swingSequence(),aim.points().getFirst(),p.getYRot()))
                throw new IllegalStateException("spear press rejected");
            spearHolds.put(p.getUUID(),state);spearInputs.put(p.getUUID(),input);spearOrigins.put(p.getUUID(),p.getEyePosition());
            // Spear presses only own a paid swing and its submitted aim. Old target fields
            // are ignored, so a legacy client cannot re-enable spear facing/target locks.
            refreshTargetLock(p,true);
        } else {
            if(state==null||!Objects.equals(spearInputs.get(p.getUUID()),input)||!actor.spearAttackHeld())return;
            if(!state.update(p.level().getGameTime(),actor.snapshot().swingSequence(),aim,p.getYRot()))return;
        }
        actor.setSpearAttackHeld(true,new AttackAim(List.of(state.point())));
        broadcastAnimation(p,true);
    }
    private void cancelSpear(ServerPlayer p) {
        var state=spearHolds.remove(p.getUUID());if(state!=null)state.cancel();
        var actor=actors.get(p.getUUID());if(actor!=null)actor.setSpearAttackHeld(false,null);
        spearInputs.remove(p.getUUID());spearOrigins.remove(p.getUUID());spearRays.remove(p.getUUID());spearBounds.remove(p.getUUID());
    }
    /** Build geometry once per actor and collision tick; clashes and every target share the same cover. */
    private void prepareSpearRays(ServerPlayer p,CombatActor actor) {
        spearRays.remove(p.getUUID());
        spearBounds.remove(p.getUUID());
        var state=spearHolds.get(p.getUUID());
        if(state!=null&&(actor.currentSkill()==null||actor.snapshot().swingSequence()!=state.sequence()
                ||actor.style()==null||!actor.style().controls().spear()||!p.isAlive())){cancelSpear(p);state=null;}
        if(state!=null) {
            if(state.expired(p.level().getGameTime())||state.point()==null) {
                cancelSpear(p);state=null;
            } else if(!SpearAimField.contains(state.point(),p.getYRot())) {
                // Vanilla root rotation can arrive one tick before the next legal cursor packet.
                // Preserve the lease, pause contacts and keep the visual pole in front while waiting.
                var safeAim=new AttackAim(List.of(SpearAimField.constrain(state.point(),p.getYRot())));
                boolean changed=!safeAim.equals(actor.attackAim());
                actor.setSpearAttackHeld(state.held(p.level().getGameTime()),safeAim);
                state.drainSegments(p.getYRot());spearOrigins.put(p.getUUID(),p.getEyePosition());
                if(changed)broadcastAnimation(p,true);
                return;
            } else actor.setSpearAttackHeld(state.held(p.level().getGameTime()),new AttackAim(List.of(state.point())));
        }
        var skill=actor.activeSkill();var aim=actor.attackAim();
        if(skill==null||aim==null||actor.style()==null||!actor.style().controls().spear())return;
        Vec3 origin=p.getEyePosition(),previous=spearOrigins.put(p.getUUID(),origin);
        if(previous==null||previous.distanceToSqr(origin)>4)previous=origin;
        List<AttackAim> paths;
        if(state!=null)paths=state.drainSegments(p.getYRot());
        else {
            double end=(actor.snapshot().skillElapsedTicks()-skill.windupTicks()+1.0)/skill.activeTicks();
            double begin=Math.max(0,end-1.0/skill.activeTicks());
            var points=new ArrayList<AttackAim.Point>();points.add(aim.sample(begin));
            for(int i=1;i<aim.points().size()-1;i++){double t=i/(double)(aim.points().size()-1);if(t>begin&&t<end)points.add(aim.points().get(i));}
            points.add(aim.sample(end));
            paths=List.of(new AttackAim(points));
        }
        paths=paths.stream().filter(path->path.points().stream().allMatch(point->SpearAimField.contains(point,p.getYRot()))).toList();
        if(paths.isEmpty())return;
        double radius=actor.style().controls().spearRadius();
        var motion=SweptSpearContact.coverMotion(paths,skill.reach(),radius,previous.distanceTo(origin),4096);
        var cover=motion.angular();int origins=motion.originIntervals();double padding=cover.padding()+motion.translationMargin();
        var rays=new ArrayList<SpearRay>(motion.rayCount());
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        var directions=new ArrayList<Vec3>(cover.points().size());
        for(var point:cover.points())directions.add(Vec3.directionFromRotation((float)point.pitch(),(float)point.yaw()).scale(skill.reach()));
        for(int step=0;step<=origins;step++) {
            Vec3 start=origins==0?origin:previous.lerp(origin,step/(double)origins);
            for(var direction:directions) {
                Vec3 tip=start.add(direction);rays.add(new SpearRay(start,tip,padding,cover.swipes().get(rays.size()%directions.size())));
                minX=Math.min(minX,Math.min(start.x,tip.x)-padding);minY=Math.min(minY,Math.min(start.y,tip.y)-padding);minZ=Math.min(minZ,Math.min(start.z,tip.z)-padding);
                maxX=Math.max(maxX,Math.max(start.x,tip.x)+padding);maxY=Math.max(maxY,Math.max(start.y,tip.y)+padding);maxZ=Math.max(maxZ,Math.max(start.z,tip.z)+padding);
            }
        }
        // If a moving shaft and its stationary endpoint both touch, the drag owns the impulse.
        rays.sort(Comparator.comparing((SpearRay ray)->!ray.swipe().swept()));
        spearRays.put(p.getUUID(),List.copyOf(rays));
        spearBounds.put(p.getUUID(),new AABB(minX,minY,minZ,maxX,maxY,maxZ));
    }
    private static void accepted(ActionResult result) { if (result != ActionResult.ACCEPTED) throw new IllegalStateException(result.toString()); }
    private static Vec3 rapierDodgeDirection(CombatActor actor, String argument) {
        if (argument.isBlank() || actor.style() == null || !actor.style().presentationStyleId().equals("rapier")) return null;
        JsonElement parsed = JsonParser.parseString(argument);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("dodge intent must be an object containing x and z");
        JsonObject intent = parsed.getAsJsonObject();
        if (!intent.keySet().equals(Set.of("x", "z"))) throw new IllegalArgumentException("dodge intent requires only x and z");
        for (String component : List.of("x", "z")) {
            JsonElement value = intent.get(component);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("dodge intent coordinates must be numbers");
        }
        var direction = RapierTargeting.dodgeDirection(intent.get("x").getAsDouble(), intent.get("z").getAsDouble());
        return new Vec3(direction.x(), 0, direction.z());
    }
    private void requireEditor(ServerPlayer p) { if (!canEdit(p)) throw new IllegalStateException("修改服务端配置需要管理员权限"); }
    private static void requireCreative(ServerPlayer p) { if (!p.isCreative()) throw new IllegalStateException("仅创造模式可用"); }
    private static JsonObject editResult(String id, boolean ok, String message) { var result = new JsonObject(); result.addProperty("id", id); result.addProperty("ok", ok); result.addProperty("message", message); return result; }
    private SkillDefinition selectSkill(ServerPlayer p, String arg) {
        if (arg.equals("skill")) {
            WeaponStyleDefinition style = actor(p).style();
            if (style != null && !style.rightSkill().isEmpty()) arg = style.rightSkill();
        }
        if (!arg.equals("left") && !arg.equals("right") && !arg.equals("skill")) {
            SkillDefinition skill = config.current().skills().get(arg);
            if (skill == null) throw new IllegalArgumentException("Unknown skill " + arg);
            if(!progression.skillUnlocked(p.getUUID(),arg))throw new IllegalStateException("该技能尚未达到旧版修行条件，请查看 P 技能树");
            if (!weaponMatches(p, skill)) throw new IllegalStateException("当前武器不满足技能要求");
            if(actor(p).style()==null || !skill.weapon().equals("any")&&!skill.weapon().equals(actor(p).style().weaponGroup()))
                throw new IllegalStateException("技能不属于当前武器档位");
            boolean conditional = config.current().styles().values().stream().anyMatch(s -> s.techniques().conditional(skill.id()));
            if (conditional && !actor(p).techniqueSnapshot().skillId().equals(skill.id()))
                throw new IllegalStateException("此招式需要拨挡或闪避后的出手机会");
            return skillsFor(p).getOrDefault(skill.id(),skill);
        }
        SkillInput input = arg.equals("left") ? SkillInput.LEFT : arg.equals("right") ? SkillInput.RIGHT : SkillInput.SPECIAL;
        return config.current().skills().values().stream().filter(s -> s.input() == input && weaponMatches(p, s)).findFirst()
            .orElseThrow(() -> new IllegalStateException("当前武器没有配置对应技能"));
    }
    private boolean weaponMatches(ServerPlayer p, SkillDefinition skill) { return config.current().weapons().matches(skill.weapon(), mainItem(p), BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString()); }
    private boolean hasWeapon(ServerPlayer p) {
        return WeaponDrawController.matchesSelection(actor(p), usableStyles(p), config.current().weapons(), mainItem(p), offhandItem(p));
    }
    private static String mainItem(ServerPlayer p) { return BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(); }
    private static String offhandItem(ServerPlayer p) { return BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString(); }
    private WeaponDrawController drawController(ServerPlayer p) { return drawControllers.computeIfAbsent(p.getUUID(), ignored -> new WeaponDrawController()); }
    private Collection<WeaponStyleDefinition> usableStyles(ServerPlayer p) {
        return config.current().styles().values().stream().filter(s->progression.weaponUnlocked(p.getUUID(),s.presentationStyleId())).toList();
    }
    private List<WeaponStyleDefinition> matchingStyles(ServerPlayer p) { return WeaponDrawController.matchingStyles(usableStyles(p), config.current().weapons(), mainItem(p), offhandItem(p)); }
    private boolean reconcileWeapon(ServerPlayer p) {
        CombatActor a=actor(p);boolean changed=drawController(p).equip(a,usableStyles(p),config.current().weapons(),mainItem(p),offhandItem(p),p.isAlive());
        if(p.isAlive()&&ArcheryServer.bowStyle(a)&&hasWeapon(p)) {
            String key=p.getInventory().getSelectedSlot()+":"+System.identityHashCode(p.getMainHandItem());
            if(!key.equals(bowEquipment.put(p.getUUID(),key))) {
                var rapid=config.current().styles().get("bow_rapid");
                if(rapid!=null&&rapid.enabled()){a.forceSheathed();a.setStyle(rapid);changed=true;}
            }
        } else bowEquipment.remove(p.getUUID());
        // Only spears retain automatic drawing. Bow stances remain available through V.
        changed|=archery.reconcile(p,a,archeryProgress(p));return changed;
    }
    private static boolean booleanArgument(String text) {
        if(!text.equals("true")&&!text.equals("false"))throw new IllegalArgumentException("Expected true or false");return Boolean.parseBoolean(text);
    }
    private ArcheryProgress archeryProgress(ServerPlayer p) {
        var progress=progression.archeryView(p.getUUID());var effects=progression.effects(p.getUUID());
        var branch150=ArcheryProgress.Branch150.valueOf(progress.branch150());
        var branch250=ArcheryProgress.Branch250.valueOf(progress.branch250());
        return new ArcheryProgress(progress.unlocked(),progress.score(),branch150,branch250,effects.damageBonus(),effects.bowDrawFactor());
    }
    private Map<String,SkillDefinition> skillsFor(ServerPlayer player) {
        CombatActor actor=actor(player);if(actor.style()==null||actor.style().controls().spear())return config.current().skills();
        var effects=progression.effects(player.getUUID());Map<String,SkillDefinition> result=new LinkedHashMap<>();
        actor.setLegacyDualIntervalAdjustment(effects::attackIntervalTicks);
        for(var skill:config.current().skills().values()) {
            if(actor.style().controls().legacyDual()&&skill.weapon().equals("dual_blade")){result.put(skill.id(),skill);continue;}
            int gate=skill.windupTicks()+skill.activeTicks();
            if(skill.weapon().equals("sword")&&actor.style().leftCombo().stream().anyMatch(stage->stage.skillId().equals(skill.id()))
                    &&ticks<rapierHasteUntil.getOrDefault(player.getUUID(),0L))gate=6;
            if(skill.weapon().equals("sword")||skill.weapon().equals("dual_blade"))gate=effects.attackIntervalTicks(gate);
            int active=Math.max(1,gate-skill.windupTicks());
            if(active==skill.activeTicks()||skill.id().equals("sword_thrust"))result.put(skill.id(),skill);
            else result.put(skill.id(),new SkillDefinition(skill.id(),skill.label(),skill.weapon(),skill.input(),skill.windupTicks(),active,skill.recoveryTicks(),skill.cooldownTicks(),skill.staminaCost(),skill.spiritCost(),skill.damage(),skill.reach(),skill.arcDegrees(),skill.knockback(),skill.blockable(),skill.parryable(),skill.clashable(),skill.model(),skill.action()));
        }
        return result;
    }
    private void beginRapierCharge(ServerPlayer player) {
        var actor=actor(player);var effects=progression.effects(player.getUUID());var rules=config.current().skillTrees().rules().rapier();
        if(actor.style()==null||!actor.style().presentationStyleId().equals("rapier")||!hasWeapon(player))throw new IllegalStateException("此技能需要西洋剑");
        String opening=actor.techniqueSnapshot().skillId();
        if(!opening.isEmpty()&&progression.skillUnlocked(player.getUUID(),opening)) {
            var result=actor.aimedInput(SkillInput.RIGHT,skillsFor(player),attackAim(player));
            if(!result.accepted())throw new IllegalStateException(comboRejection(result));
            return;
        }
        if(!actor.swordCombatEnabled()&&!effects.rapierThrust())throw new IllegalStateException("突刺需要80次西洋剑击杀及对应修行条件，请查看 P 技能树");
        SkillDefinition skill=config.current().skills().get("sword_thrust");
        skill=new SkillDefinition(skill.id(),skill.label(),skill.weapon(),skill.input(),rules.windupTicks(),rules.activeTicks(),rules.recoveryTicks(),rules.cooldownTicks(),skill.staminaCost(),skill.spiritCost(),rules.thrustDamage(),rules.range(),skill.arcDegrees(),skill.knockback(),skill.blockable(),skill.parryable(),skill.clashable(),skill.model(),skill.action());
        accepted(actor.requestRapierThrust(skill,attackAim(player),actor.swordCombatEnabled()||effects.rapierParry(),!actor.swordCombatEnabled()&&effects.rapierParry(),rules.fullChargeTicks(),rules.guardBreakCooldownTicks()));
    }
    private void moveCharge(ServerPlayer player) {
        if(!actor(player).consumeChargeLunge())return;
        if(actor(player).swordCombatEnabled())return;
        double speed=config.current().skillTrees().rules().rapier().lungeSpeedMps()/20;
        var aim=actor(player).attackAim();
        Vec3 direction=aim==null?player.getLookAngle().normalize():Vec3.directionFromRotation((float)aim.sample(0).pitch(),(float)aim.sample(0).yaw());
        player.setDeltaMovement(player.getDeltaMovement().add(direction.scale(speed)));
        player.hurtMarked=true;training.forced(player,10);
    }
    /** A thin sole probe distinguishes genuine support from a wall beside an airborne player. */
    private static boolean swordSupported(ServerPlayer player,AABB box) {
        double inset=1e-5;
        AABB sole=new AABB(box.minX+inset,box.minY-.002,box.minZ+inset,box.maxX-inset,box.minY,box.maxZ-inset);
        return player.level().findSupportingBlock(player,sole).isPresent();
    }
    private void moveSword(ServerPlayer player,CombatActor actor) {
        // Vanilla acknowledges each position correction with onGround=false. That packet is not
        // evidence of a jump: keep a plan while the real feet remain supported and are not rising.
        boolean moving=actor.swordCombatEnabled()&&actor.currentSkill()!=null&&!player.isPassenger()&&!player.isInWater()&&!player.isInLava()&&!player.isFallFlying()
                &&player.getDeltaMovement().y<=1e-7&&swordSupported(player,player.getBoundingBox());
        boolean limited=actor.swordCombatEnabled()&&(actor.currentSkill()!=null||actor.phase()==CombatPhase.PARRYING);
        var attribute=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if(attribute!=null){double amount=limited?actor.rules().swordCombat().moveSpeedMultiplier()-1:0;var previous=attribute.getModifier(SWORD_MOVE_ID);if(previous==null||Math.abs(previous.amount()-amount)>1e-9){if(previous!=null)attribute.removeModifier(SWORD_MOVE_ID);if(Math.abs(amount)>1e-9)attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SWORD_MOVE_ID,amount,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));}}
        SwordMotionPlan plan=swordMotions.get(player.getUUID());
        if(plan==null)return;
        if(!moving||plan.sequence()!=actor.snapshot().swingSequence()) {
            swordMotions.remove(player.getUUID());
            PacketDistributor.sendToPlayer(player,new RpgSwordMotionPayload(player.getUUID(),player.getId(),player.level().dimension().identifier().toString(),plan.sequence(),ticks,player.getX(),player.getY(),player.getZ(),false,1,plan.expectedAdvance(),plan.actualAdvance()));return;
        }
        if(actor.chargeSnapshot().holding())return;
        double progress=SwordMotionPlan.progress(actor.snapshot().skillElapsedTicks()+1,actor.currentSkill());
        var input=player.getLastClientMoveIntent();var step=plan.advance(progress,input.x,input.z);
        Vec3 delta=new Vec3(step.x(),0,step.z());double beforeX=player.getX(),beforeY=player.getY(),beforeZ=player.getZ();
        if(delta.lengthSqr()>1e-12){
            Vec3 end=player.position().add(delta);
            boolean safe=player.level().hasChunkAt(net.minecraft.core.BlockPos.containing(end))&&player.level().getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(end));
            // No root-motion fall off cliffs. Ordinary jump/fall remains vanilla and cancels this plan.
            safe&=swordSupported(player,player.getBoundingBox().move(delta));
            if(safe){
                player.setOnGround(true); // Restore only the support just verified above, including vanilla step-up eligibility.
                player.move(MoverType.SELF,delta);training.forced(player,2);
                player.setOnGround(swordSupported(player,player.getBoundingBox()));
            }
        }
        double endpointX=player.getX(),endpointY=player.getY(),endpointZ=player.getZ();
        double appliedX=endpointX-beforeX,appliedY=endpointY-beforeY,appliedZ=endpointZ-beforeZ;
        plan.recordActual(appliedX,appliedZ);
        // The receipt atomically installs endpoint + visual debt before vanilla resets old/current
        // to this same endpoint. Reversing these packets can show the endpoint for one frame first.
        PacketDistributor.sendToPlayer(player,new RpgSwordMotionPayload(player.getUUID(),player.getId(),player.level().dimension().identifier().toString(),plan.sequence(),ticks,endpointX,endpointY,endpointZ,true,delta.lengthSqr()<1e-12?1:Math.clamp(Math.hypot(appliedX,appliedZ)/delta.length(),0,1),plan.expectedAdvance(),plan.actualAdvance(),appliedX,appliedY,appliedZ));
        if(Math.hypot(appliedX,appliedZ)>1e-9){
            player.connection.teleport(endpointX,endpointY,endpointZ,player.getYRot(),player.getXRot());
            player.setOnGround(swordSupported(player,player.getBoundingBox()));
        }
    }
    private AttackAim attackAim(ServerPlayer player) {
        refreshTargetLock(player, false);
        var lock = rapierTargets.snapshot(player, actor(player));
        return lock.locked() ? lock.aim() : AttackAim.at(player.getYRot(), player.getXRot());
    }
    private void refreshTargetLock(ServerPlayer player, boolean force) {
        var lock = rapierTargets.update(player, actor(player), hasWeapon(player));
        var previous = sentTargetLocks.get(player.getUUID());
        boolean changed = previous == null || previous.enabled() != lock.enabled()
                || previous.targetId() != lock.targetId() || !previous.targetUuid().equals(lock.targetUuid())
                || previous.range() != lock.range();
        if (force || changed || lock.enabled() && ticks % 5 == 0 && !lock.equals(previous)) {
            sentTargetLocks.put(player.getUUID(), lock);
            PacketDistributor.sendToPlayer(player, new RpgTargetLockPayload(RpgConfig.JSON.toJson(lock)));
        }
    }
    private void reconcileAndSync(ServerPlayer p) { if (reconcileWeapon(p)) { send(p, "", null); broadcastAnimation(p, true); } }
    private void applyPendingStyle(ServerPlayer p) {
        var a = actor(p);
        if (pendingStyles.containsKey(p.getUUID()) && (a.phase() == CombatPhase.READY || a.phase() == CombatPhase.SHEATHED)) {
            var pending = pendingStyles.remove(p.getUUID());
            if (matchingStyles(p).contains(pending)) accepted(a.setStyle(pending));
            reconcileWeapon(p);
        }
    }
    private void applyConfig(RpgConfig.Definitions definitions) {
        flushAllMelee();
        enemyCounters.clear();
        creatureMelee.reconfigure(validatedCreatureCombat,validatedGuardCues);
        QuestService replacement = new QuestService(definitions.quests());
        replacement.restore(quests.snapshot());
        quests = replacement;
        CollectionService replacementCollections = new CollectionService(definitions.collections());
        replacementCollections.restore(collections.snapshot()); collections = replacementCollections; collectionSavedRevision = -1;
        progression.reconfigure(definitions.skillTrees());archery.setRules(definitions.archery());
        savedRevision = -1;
        for (CombatActor a : actors.values()) {
            String selectedId = a.style() == null ? "rapier" : a.style().id();
            a.setRules(definitions.combat());
            WeaponStyleDefinition updated = definitions.styles().get(selectedId);
            if (updated == null || !updated.enabled()) updated = defaultStyle(definitions);
            // Config changes safely interrupt a pending action rather than letting an old combo use a new catalog.
            if (a.currentSkill() != null || (a.phase() != CombatPhase.READY && a.phase() != CombatPhase.SHEATHED)) a.stun(1);
            pendingStyles.put(a.id(), updated);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) { send(p, "配置已更新", null); broadcastAnimation(p, true); }
    }
    private void tick() {
        ticks++;
        enemyCounters.entrySet().removeIf(e->e.getValue().untilTick()<=server.overworld().getGameTime());
        enemyStuns.entrySet().removeIf(entry -> !entry.getValue().entity().isAlive() || entry.getValue().entity().isRemoved()
                || entry.getValue().entity().level().getGameTime() >= entry.getValue().untilGameTick());
        creatureMelee.tick();
        var players = List.copyOf(server.getPlayerList().getPlayers());
        // Validate every actor before taking a shared collision-phase snapshot.
        for (ServerPlayer p : players) {
            CombatActor a = actor(p);
            if (reconcileWeapon(p)) { send(p, "", null); broadcastAnimation(p, true); }
            refreshTargetLock(p, false);
            if (!p.isAlive()) { cancelSpear(p); broadcastAnimation(p, false); continue; }
            training.tick(p,a);archery.tick(p,a,archeryProgress(p));footwork.tick(p);
            if (a.currentSkill() != null && !validSwing(p)) a.stun(1);
            if (a.phase() == CombatPhase.GUARDING && !hasWeapon(p)) a.setGuarding(false);
            moveSword(p,a);
            prepareSpearRays(p,a);
        }
        List<PendingHit> pendingHits = new ArrayList<>();
        for (ServerPlayer p : players) {
            CombatActor a = actor(p);
            if (!p.isAlive()) continue;
            SkillDefinition skill = a.activeSkill();
            if (skill != null) {
                if(creatureActivations.computeIfAbsent(a,ignored->new SwingActivationGate()).claim(a.snapshot().swingSequence())
                        && windowClashOnActivation(p,a,skill))continue;
                boolean spear=a.style()!=null&&a.style().controls().spear();
                var bounds=spear?spearBounds.getOrDefault(p.getUUID(),p.getBoundingBox().inflate(skill.reach())):p.getBoundingBox().inflate(skill.reach());
                var targets=p.level().getEntitiesOfClass(LivingEntity.class,bounds,t->t!=p&&t.isAlive());
                if(a.style()!=null&&a.style().presentationStyleId().equals("rapier"))targets.sort(Comparator.comparingDouble(t->attackDistance(p,t)));
                for (LivingEntity target : targets) {
                    if(a.hasHit(target.getUUID()))continue;
                    if (target instanceof ServerPlayer q && !p.canHarmPlayer(q)) continue;
                    var contact=spear?spearImpact(p,target,a):null;
                    if (spear?contact==null:!p.hasLineOfSight(target)) continue;
                    CombatActor defender = target instanceof ServerPlayer q ? actor(q) : new CombatActor(target.getUUID(), config.current().combat());
                    double attackMultiplier = a.attackDamageMultiplier();
                    double distance=attackDistance(p,target);
                    if(spear)distance=Math.min(distance,skill.reach()); // The swept contact already proved a legal past/current origin within reach.
                    var result = CombatResolver.captureMelee(a, defender, distance, attackFacing(p, target), facing(target, p));
                    if (result.contacted()) {
                        var swipe=contact==null?SpearSwipe.Vector.ZERO:contact.swipe();
                        if(result.outcome()==HitOutcome.HIT) captureMelee(p,target,result,attackMultiplier,swipe);
                        else pendingHits.add(new PendingHit(p,target,result,attackMultiplier,swipe));
                        if(a.style()!=null&&a.style().presentationStyleId().equals("rapier"))break;
                    }
                }
            }
        }
        // Collect both outgoing intents before applying health changes, including lethal trades.
        creatureMelee.flushContacts();
        resolveMeleeContacts();
        for (PendingHit hit : pendingHits) applyHit(hit.attacker(), hit.victim(), hit.result(), hit.damageMultiplier(),hit.swipe());
        // Advance everyone together after all hits: the last dodge/attack frame has the same
        // meaning for every player regardless of player-list order.
        for (ServerPlayer p : players) {
            if (!p.isAlive()) continue;
            CombatActor a = actor(p);
            if (a.currentSkill() != null && !validSwing(p)) a.stun(1);
            var advance = a.tick();
            // Shift has no movement loop: the accepted action assigned one XZ impulse; vanilla owns its decay.
            moveCharge(p);
            applyPendingStyle(p);
            boolean skillStarted = startedSkills(p);
            if (advance.dodgeDistanceThisTick() > 0) {
                Vec3 dir = dodgeDirections.getOrDefault(p.getUUID(), Vec3.ZERO);
                p.move(MoverType.SELF, dir.scale(advance.dodgeDistanceThisTick()));
                p.connection.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
            }
            if (a.phase() != CombatPhase.DODGING) dodgeDirections.remove(p.getUUID());
            if (ticks % 10 == 0) {
                quests.visit(p.getUUID(), p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ());
            }
            if (skillStarted || advance.before() != advance.after() || ticks % 10 == 0) send(p, "", null);
            broadcastAnimation(p, ticks % 10 == 0);
        }
        if (ticks % 100 == 0) save();
    }
    private boolean validSwing(ServerPlayer p) {
        SkillDefinition skill = actor(p).currentSkill();
        return mainItem(p).equals(swingItems.get(p.getUUID())) && (actor(p).style()==null || !actor(p).style().controls().dual() || BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString().equals(swingOffhands.get(p.getUUID()))) && (skill == null || weaponMatches(p, skill));
    }
    private long opponentSequence(LivingEntity opponent,ServerPlayer attacker,long sequence) {
        if(actor(attacker).swordHeavyAttack()||actor(attacker).swordSuperArmor() || opponent instanceof ServerPlayer q && (actor(q).swordHeavyAttack()||actor(q).swordSuperArmor()) || actor(attacker).swordCombatEnabled()&&swordHeavyMob(opponent))return -1;
        for(var c:meleeContacts.contacts()) if(c.attacker().equals(opponent.getUUID()) && c.victim().equals(attacker.getUUID())
                && c.value().opponentSequence()==sequence) return c.sequence();
        if(opponent instanceof ServerPlayer p) {
            CombatActor a=actor(p);
            return validSwing(p) && (a.phase()==CombatPhase.WINDUP || a.phase()==CombatPhase.ACTIVE) ? a.snapshot().swingSequence() : -1;
        }
        var pending=creatureMelee.snapshot(opponent);
        // Authored monsters use their animation's ACTIVE-entry opportunity, never a later
        // actual-contact match. Native unprofiled melee retains its real-contact fallback.
        return pending!=null && pending.clip().isEmpty() && pending.targetId().equals(attacker.getUUID()) ? creatureMelee.clashSequence(opponent) : -1;
    }
    private void captureBash(ServerPlayer attacker,LivingEntity target,double damage,double knockback,long reservation) {
        CombatActor attacking=actor(attacker);
        if(Objects.equals(clashedBashReservations.get(attacking),reservation))return;
        CombatActor defender=target instanceof ServerPlayer p?actor(p):new CombatActor(target.getUUID(),config.current().combat());
        // A bow bash is instantaneous melee; arrows never enter this path.
        if(defender.invulnerable())return;
        archery.markBashContact(attacker.getUUID());
        if(target instanceof ServerPlayer && defender.receiveChargedParry()==1)return;
        if(defender.phase()==CombatPhase.GUARDING&&CombatResolver.withinArc(facing(target,attacker),defender.guardRules().arcDegrees())) {
            var defense=CombatResolver.receiveIncoming(actor(attacker),defender,facing(target,attacker),damage,knockback,true,true,false);
            effects(attacker,target,defense);
            if(defense.outcome()!=HitOutcome.HIT) {
                if(defense.dealsDamage()) {
                    resolvingDamage=true;
                    try{
                        var source=attacker.damageSources().playerAttack(attacker);
                        if(defense.outcome()==HitOutcome.BLOCKED)markGuardedDamage(target,source);
                        target.hurtServer(attacker.level(),source,(float)defense.damage());
                    }
                    finally{resolvingDamage=false;}
                }
                return;
            }
        }
        actor(attacker).markCombatActivity();defender.markCombatActivity();
        long sequence=Long.MAX_VALUE/2+reservation,opponent=opponentSequence(target,attacker,sequence);
        var hit=new MeleeHit(attacker,target,new HitResult(HitOutcome.HIT,damage,knockback,false,config.current().combat().hitStunTicks(),"bow_bash"),
                damage,SpearSwipe.Vector.ZERO,sequence,opponent,mainItem(attacker),BuiltInRegistries.ITEM.getKey(attacker.getOffhandItem().getItem()).toString(),
                false,false,false,attacker.getX()-target.getX(),attacker.getZ()-target.getZ(),attacker.level());
        if(target instanceof Mob mob&&windowClashFacing(attacker,mob,null)) {
            var candidate=creatureMelee.windowClashCandidate(mob,attacker,attacker.level().getGameTime());
            if(candidate!=null&&executeWindowClash(hit,candidate.sequence())){clashedBashReservations.put(attacking,reservation);return;}
        }
        if(opponent<0)ordinaryMelee.add(hit);
        else meleeContacts.offer(new WeaponClash.Contact<>(attacker.getUUID(),target.getUUID(),sequence,attacker.level().getGameTime(),damage,hit));
    }
    private void captureMelee(ServerPlayer attacker,LivingEntity target,HitResult result,double attackMultiplier,SpearSwipe.Vector swipe) {
        if(target instanceof ServerPlayer q && actor(q).receiveChargedParry()==1) return;
        MeleeHit frozen=freezeMelee(attacker,target,result,attackMultiplier,swipe,opponentSequence(target,attacker,actor(attacker).snapshot().swingSequence()));
        if(frozen.opponentSequence()<0)ordinaryMelee.add(frozen);
        else meleeContacts.offer(new WeaponClash.Contact<>(attacker.getUUID(),target.getUUID(),frozen.sequence(),attacker.level().getGameTime(),frozen.damage(),frozen));
    }
    /** Shared by physical contact and an authored monster's first-ACTIVE exchange. */
    private MeleeHit freezeMelee(ServerPlayer attacker,LivingEntity target,HitResult result,double attackMultiplier,SpearSwipe.Vector swipe,long opponent) {
        CombatActor a=actor(attacker);
        var bonus=progression.effects(attacker.getUUID());
        boolean thrust=result.skillId().equals("sword_thrust");
        double damage=result.damage();
        if(thrust&&a.chargedParried()) {
            var rules=config.current().skillTrees().rules().rapier();
            damage*=rules.thrustMultiplier()>0?rules.parriedMultiplier()/rules.thrustMultiplier():1;
        }
        damage=(damage+bonus.damageBonus()*attackMultiplier)*config.current().weapons().damageMultiplier(damageItems.getOrDefault(attacker.getUUID(),mainItem(attacker)));
        long sequence=a.snapshot().swingSequence();
        return new MeleeHit(attacker,target,result,damage,swipe,sequence,opponent,mainItem(attacker),
                BuiltInRegistries.ITEM.getKey(attacker.getOffhandItem().getItem()).toString(),a.style()!=null&&a.style().controls().dual(),
                thrust,bonus.rapierExecution(),attacker.getX()-target.getX(),attacker.getZ()-target.getZ(),attacker.level());
    }
    private boolean windowClashFacing(ServerPlayer player,Mob enemy,SkillDefinition skill) {
        CombatActor a=actor(player);
        double facing=RpgServer.facing(player,enemy);
        if(a.attackAim()!=null){var aim=a.attackAim().sample(0);facing=angle(Vec3.directionFromRotation((float)aim.pitch(),(float)aim.yaw()),enemy.position().subtract(player.position()));}
        double arc=skill==null?config.current().combat().clashFacingArcDegrees():skill.arcDegrees();
        return player.hasLineOfSight(enemy)&&CombatResolver.withinArc(facing,arc)
                &&CombatResolver.withinArc(RpgServer.facing(enemy,player),config.current().combat().clashFacingArcDegrees());
    }
    private boolean windowClashOnActivation(ServerPlayer player,CombatActor a,SkillDefinition skill) {
        if(a.swordHeavyAttack()||a.swordSuperArmor())return false;
        if(!a.drawn()||!hasWeapon(player)||!validSwing(player)||a.invulnerable())return false;
        var enemies=player.level().getEntitiesOfClass(Mob.class,player.getBoundingBox().inflate(skill.reach()),Mob::isAlive);
        enemies.sort(Comparator.comparingDouble((Mob enemy)->attackDistance(player,enemy)).thenComparing(enemy->enemy.getUUID().toString()));
        for(Mob enemy:enemies){
            if(a.hasHit(enemy.getUUID())||attackDistance(player,enemy)>skill.reach()||!windowClashFacing(player,enemy,skill)||a.swordCombatEnabled()&&swordHeavyMob(enemy))continue;
            var candidate=creatureMelee.windowClashCandidate(enemy,player,player.level().getGameTime());
            if(candidate==null)continue;
            double multiplier=a.attackDamageMultiplier();
            var result=CombatResolver.captureMelee(a,new CombatActor(enemy.getUUID(),config.current().combat()),attackDistance(player,enemy),0,0);
            if(result.outcome()!=HitOutcome.HIT)continue;
            var hit=freezeMelee(player,enemy,result,multiplier,SpearSwipe.Vector.ZERO,candidate.sequence());
            if(executeWindowClash(hit,candidate.sequence()))return true;
            // An independent native hook may have canceled this original invocation. Preserve
            // the player's ordinary attack once rather than manufacturing an RPG exchange.
            ordinaryMelee.add(hit);return false;
        }
        return false;
    }
    private boolean executeWindowClash(MeleeHit hit,long creatureSequence) {
        if(!(hit.victim() instanceof Mob mob))return false;
        WindowClashAttempt previous=windowClashAttempt,attempt=new WindowClashAttempt(hit,creatureSequence);
        windowClashAttempt=attempt;
        try{creatureMelee.executeWindowClash(mob,hit.attacker(),creatureSequence);}
        finally{windowClashAttempt=previous;}
        return attempt.settled;
    }
    /** Creature contacts use world time, exactly like their animation timeline. */
    public boolean hasPendingMelee(Entity attacker,Entity victim,long contactTick) {
        return meleeContacts.contacts().stream().anyMatch(c->c.attacker().equals(attacker.getUUID())&&c.victim().equals(victim.getUUID())
                && Math.abs(c.tick()-contactTick)<=config.current().combat().weaponClash().contactWindowTicks() && eligibleClash(c.value()));
    }
    public boolean deferCreatureContact(Mob mob,LivingEntity target) {
        if(!(target instanceof ServerPlayer p)||!hasWeapon(p))return false;
        CombatActor a=actor(p);
        boolean authored=creatureMelee.guardCueSnapshot(mob)!=null;
        boolean defer=a.drawn()&&!a.invulnerable()&&a.phase()!=CombatPhase.GUARDING
                && (authored?a.phase()==CombatPhase.ACTIVE:a.phase()==CombatPhase.WINDUP||a.phase()==CombatPhase.ACTIVE||hasPendingMelee(p,mob,mob.level().getGameTime()));
        if(defer)a.markCombatActivity();
        return defer;
    }
    private boolean eligibleClash(MeleeHit h) {
        return h.attacker().isAlive()&&!h.attacker().isRemoved()&&h.victim().isAlive()&&!h.victim().isRemoved()
                && h.attacker().level()==h.level()&&h.victim().level()==h.level()
                && mainItem(h.attacker()).equals(h.mainItem())
                && (!h.dual()||BuiltInRegistries.ITEM.getKey(h.attacker().getOffhandItem().getItem()).toString().equals(h.offItem()));
    }
    private void resolveMeleeContacts() {
        for(var c:meleeContacts.contacts()) if(!eligibleClash(c.value())) {
            ordinaryMelee.add(c.value());meleeContacts.remove(c);
        }
        long now=server.overworld().getGameTime();
        var batch=meleeContacts.resolve(now,config.current().combat().weaponClash());
        for(var pair:batch.pairs()) {
            MeleeHit a=pair.first().value(),b=pair.second().value();
            ordinaryMelee.removeIf(h->sameSwing(h,a)||sameSwing(h,b));
            settleClash(a.attacker(),a.sequence(),b.attacker(),b.sequence(),pair.exchange());
            if(pair.exchange().firstDamage()>0) deliverMelee(a,pair.exchange().firstDamage(),true);
            if(pair.exchange().secondDamage()>0) deliverMelee(b,pair.exchange().secondDamage(),true);
        }
        for(var c:batch.expired()) ordinaryMelee.add(c.value());
        var committed=List.copyOf(ordinaryMelee);ordinaryMelee.clear();
        for(MeleeHit h:committed) deliverMelee(h,h.damage(),false);
    }
    private static boolean sameSwing(MeleeHit a,MeleeHit b){return a.attacker()==b.attacker()&&a.sequence()==b.sequence();}
    private void finishClashActor(LivingEntity entity,long sequence) {
        if(entity instanceof ServerPlayer p) {actor(p).finishClash(sequence);actor(p).recordGuardContact();}
        else {creatureMelee.cancelAttacker(entity);enemyCounters.remove(entity.getUUID());enemyStuns.remove(entity.getUUID());}
    }
    private void settleClash(LivingEntity first,long firstSequence,LivingEntity second,long secondSequence,WeaponClash.Exchange exchange) {
        meleeClashes++;finishClashActor(first,firstSequence);finishClashActor(second,secondSequence);
        for(var entity:List.of(first,second))if(entity instanceof ServerPlayer p)
            PacketDistributor.sendToPlayer(p,new RpgGuardImpactPayload(p.getUUID(),-1,-1,false));
        LivingEntity winner=exchange.winner()>0?first:exchange.winner()<0?second:null;
        if(winner==null) {
            balancedClashes++;effects(first,second,new HitResult(HitOutcome.CLASH,0,0,false,0,""));
            for(var entity:List.of(first,second))if(entity instanceof ServerPlayer p)
                p.displayClientMessage(Component.literal("§e相杀 · 双方伤害免除"),true);
        } else {
            advantageClashes++;LivingEntity loser=winner==first?second:first;
            if(winner instanceof ServerPlayer p)actor(p).rewardClashAdvantage(winner==first?firstSequence:secondSequence);
            else enemyCounters.put(winner.getUUID(),new EnemyCounter(winner.level().getGameTime()+config.current().combat().perfectGuardCounter().windowTicks(),config.current().combat().perfectGuardCounter().damageMultiplier()));
            effects(loser,winner,new HitResult(HitOutcome.PARRIED,0,0,false,0,""),false);
        }
        for(LivingEntity e:List.of(first,second))if(e instanceof ServerPlayer p){send(p,"",null);broadcastAnimation(p,true);}
    }
    /** Frozen total already contains weapon/counter/progression modifiers; armor is applied once below. */
    private void deliverMelee(MeleeHit h,double damage,boolean clashRemainder) {
        LivingEntity target=h.victim();ServerPlayer attacker=h.attacker();
        if(target.isRemoved()||!target.isAlive()||target.level()!=h.level()||attacker.level()!=h.level())return;
        if(!clashRemainder && target instanceof ServerPlayer p)actor(p).queueHitStun(h.result().victimStunTicks());
        if(damage<=0)return;
        resolvingDamage=true;
        try {
            suppressedSpearKnockback=h.swipe().swept()?target.getUUID():null;
            if(h.thrust()&&target.invulnerableTime>10)target.invulnerableTime=0;
            var source=attacker.damageSources().playerAttack(attacker);
            if(clashRemainder)markGuardedDamage(target,source);
            boolean hurt=target.hurtServer(h.level(),source,(float)Math.min(100_000_000,damage));
            if(hurt&&h.dual())progression.recordDualHit(attacker.getUUID(),h.level().getGameTime(),MinecraftProgression.epochDay());
            if(hurt&&h.thrust()) {
                rapierHasteUntil.put(attacker.getUUID(),ticks+60);
                flashThrust(attacker,target,damage);
                if(!clashRemainder&&h.execution()&&target.isAlive()) {
                    double missing=target.getMaxHealth()-target.getHealth();target.invulnerableTime=0;
                    target.hurtServer(h.level(),attacker.damageSources().playerAttack(attacker),(float)(missing*config.current().skillTrees().rules().rapier().executionMissingHealthShare()));
                }
            }
            suppressedSpearKnockback=null;
            if(hurt&&!clashRemainder&&h.result().knockback()>0) {
                if(h.swipe().swept())applySpearSwipe(target,h.swipe(),h.result().knockback(),actor(attacker).rules().spearSwipeKnockbackMultiplier());
                else target.knockback(h.result().knockback(),h.knockX(),h.knockZ());
            }
        } finally {resolvingDamage=false;suppressedSpearKnockback=null;}
    }
    private void flushAllMelee() {
        for(var c:meleeContacts.contacts())ordinaryMelee.add(c.value());meleeContacts.clear();
        var committed=List.copyOf(ordinaryMelee);ordinaryMelee.clear();for(var h:committed)deliverMelee(h,h.damage(),false);
    }
    private void flushMelee(Entity entity) {
        List<MeleeHit> committed=new ArrayList<>();
        for(var c:meleeContacts.contacts())if(c.attacker().equals(entity.getUUID())||c.victim().equals(entity.getUUID()))committed.add(c.value());
        meleeContacts.remove(entity.getUUID());
        ordinaryMelee.removeIf(h->{if(h.attacker()==entity||h.victim()==entity){committed.add(h);return true;}return false;});
        for(var h:committed)deliverMelee(h,h.damage(),false);
    }
    private void applyHit(ServerPlayer attacker, LivingEntity target, org.fsrpg.combat.HitResult result, double attackMultiplier,SpearSwipe.Vector swipe) {
        effects(attacker, target, result);
        if (!result.dealsDamage()) return;
        double multiplier = config.current().weapons().damageMultiplier(damageItems.getOrDefault(attacker.getUUID(), mainItem(attacker)));
        CombatActor actor=actor(attacker);var bonus=progression.effects(attacker.getUUID());
        if(target instanceof ServerPlayer defender&&actor(defender).receiveChargedParry()==1)return;
        double damage=result.damage();
        if(actor.currentSkill()!=null&&actor.currentSkill().id().equals("sword_thrust")&&actor.chargedParried()) {
            var rules=config.current().skillTrees().rules().rapier();damage*=rules.thrustMultiplier()>0?rules.parriedMultiplier()/rules.thrustMultiplier():1;
        }
        damage+=bonus.damageBonus() * attackMultiplier;
        resolvingDamage = true;
        try {
            // Damage's vanilla radial impulse would pull a side cut back toward forward.
            suppressedSpearKnockback=swipe.swept()?target.getUUID():null;
            if(actor.currentSkill()!=null&&actor.currentSkill().id().equals("sword_thrust")&&target.invulnerableTime>10)target.invulnerableTime=0;
            var source=attacker.damageSources().playerAttack(attacker);
            if(result.outcome()==HitOutcome.BLOCKED)markGuardedDamage(target,source);
            boolean hurt = target.hurtServer(attacker.level(), source, (float) (damage * multiplier));
            if(hurt&&actor.style()!=null&&actor.style().controls().dual())progression.recordDualHit(attacker.getUUID(),attacker.level().getGameTime(),MinecraftProgression.epochDay());
            if(hurt&&actor.currentSkill()!=null&&actor.currentSkill().id().equals("sword_thrust")) {
                rapierHasteUntil.put(attacker.getUUID(),ticks+60);
                flashThrust(attacker,target,damage*multiplier);
                if(bonus.rapierExecution()&&target.isAlive()) {
                    double missing=target.getMaxHealth()-target.getHealth();target.invulnerableTime=0;
                    target.hurtServer(attacker.level(),attacker.damageSources().playerAttack(attacker),(float)(missing*config.current().skillTrees().rules().rapier().executionMissingHealthShare()));
                }
            }
            suppressedSpearKnockback=null;
            if (hurt && result.knockback() > 0) {
                if(swipe.swept())applySpearSwipe(target,swipe,result.knockback(),actor.rules().spearSwipeKnockbackMultiplier());
                else target.knockback(result.knockback(), attacker.getX() - target.getX(), attacker.getZ() - target.getZ());
            }
        } finally { resolvingDamage = false;suppressedSpearKnockback=null; }
    }
    /**
     * 闪刺: only a real rapier-thrust contact inside a Rabbit invulnerability or Flying Swan
     * window adds the old 50% pre-armor, armor-bypassing damage, once per 100-tick combination
     * cooldown. Vanilla i-frames are cleared first so the extra hit lands exactly here.
     */
    private void flashThrust(ServerPlayer attacker,LivingEntity target,double preArmorDamage) {
        if(preArmorDamage<=0||!target.isAlive()||target.level()!=attacker.level())return;
        if(!footwork.takeFlashThrust(attacker))return;
        target.invulnerableTime=0;
        try {
            target.hurtServer(attacker.level(),FootworkDamage.flashThrust(attacker),
                    (float)(preArmorDamage*config.current().skillTrees().rules().footwork().flashThrustDamageShare()));
        } catch(RuntimeException invalidDamageType) {
            LOG.warn("Flash thrust damage type is unavailable; the combination hit was skipped",invalidDamageType);
        }
    }
    private static void applySpearSwipe(LivingEntity target,SpearSwipe.Vector swipe,double amount,double multiplier) {
        var event=net.neoforged.neoforge.common.CommonHooks.onLivingKnockBack(target,(float)(amount*multiplier),-swipe.x(),-swipe.z());
        if(event.isCanceled())return;
        double horizontal=Math.hypot(swipe.x(),swipe.z()),eventLength=Math.hypot(event.getRatioX(),event.getRatioZ());
        var direction=eventLength<1e-9?new SpearSwipe.Vector(0,swipe.y(),0):new SpearSwipe.Vector(-event.getRatioX()/eventLength*horizontal,swipe.y(),-event.getRatioZ()/eventLength*horizontal);
        Vec3 old=target.getDeltaMovement();
        double resistance=Math.clamp(target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE),0,1);
        var impulse=SpearSwipe.apply(direction,Math.clamp(event.getStrength(),0,128),1,resistance,target.onGround(),new SpearSwipe.Vector(old.x,old.y,old.z));
        if(!impulse.applied())return;
        var velocity=impulse.velocity();target.setDeltaMovement(velocity.x(),velocity.y(),velocity.z());target.needsSync=true;target.hurtMarked=true;
        if(target instanceof ServerPlayer player)player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));
    }
    private void incoming(LivingIncomingDamageEvent event) {
        if(event.getEntity().level().isClientSide())return;
        Entity sourceEntity = event.getSource().getEntity();
        // Vanilla melee must obey the same interruption as RPG swings. Already released arrows
        // retain their flight; pausing their owner must not erase unrelated projectile impacts.
        if (!resolvingDamage && sourceEntity != null && sourceEntity == event.getSource().getDirectEntity()
                && perfectGuardStunRemaining(sourceEntity) > 0) { event.setCanceled(true); return; }
        archery.onOutgoing(event);
        // Rabbit's 8-tick window cancels every incoming damage source, exactly like the old event.
        // The old handler still recorded the hostile attack that the dash negated, so training sees
        // the real event but the health loss never happens.
        if (event.getEntity() instanceof ServerPlayer target && footwork.invulnerable(target)) {
            if (!resolvingDamage) training.incoming(target, event.getSource().getEntity());
            event.setCanceled(true); return;
        }
        if (resolvingDamage || !(event.getEntity() instanceof ServerPlayer p) || event.getSource().getDirectEntity() == null) return;
        reconcileAndSync(p);
        training.incoming(p,event.getSource().getEntity());
        boolean directMelee=sourceEntity!=null&&sourceEntity==event.getSource().getDirectEntity();
        boolean swordHeavy=directMelee&&(sourceEntity instanceof ServerPlayer q?actor(q).swordHeavyAttack():swordHeavyMob(sourceEntity)&&creatureMelee.invocationContactTick(sourceEntity,event.getSource().getDirectEntity(),p)!=null);
        if((!actor(p).swordCombatEnabled()||!actor(p).swordSuperArmor()&&!swordHeavy)&&creatureClash(event,p))return;
        if(archery.onIncoming(event,archeryProgress(p)))return;
        if(actor(p).receiveChargedParry()==1){
            actor(p).markCombatActivity();
            recordArcheryContact(event.getSource().getEntity(),event.getSource().getDirectEntity());
            event.setCanceled(true);send(p,"拨挡成功，松开右键还击",null);broadcastAnimation(p,true);return;
        }
        Entity source = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        CombatActor attacker = source instanceof ServerPlayer q ? actor(q) : null;
        double angle = direct instanceof AbstractArrow arrow ? angle(p.getLookAngle(), arrow.getDeltaMovement().scale(-1)) : facing(p, direct);
        GuardPress press=guardPresses.get(p.getUUID());
        Boolean precise=creatureMelee.claimGuardAuthorization(source,direct,p,press==null?0:press.serial,press!=null&&press.held);
        var result = actor(p).swordCombatEnabled()?CombatResolver.receiveSwordIncoming(attacker,actor(p),angle,Math.min(1_000_000,event.getAmount()),0,true,direct instanceof AbstractArrow,swordHeavy,"")
                :precise==null?CombatResolver.receiveIncoming(attacker, actor(p), angle, Math.min(1_000_000, event.getAmount()), 0, true, true, direct instanceof AbstractArrow)
                :CombatResolver.receiveTelegraphed(attacker,actor(p),angle,Math.min(1_000_000,event.getAmount()),0,true,true,false,precise);
        if(result.outcome()!=HitOutcome.DODGED)recordArcheryContact(source,direct);
        if(result.outcome()==HitOutcome.BLOCKED&&result.dealsDamage())markGuardedDamage(p,event.getSource());
        if (result.dealsDamage()) event.setAmount((float) result.damage()); else event.setCanceled(true);
        if (result.outcome() != HitOutcome.HIT) effects(source, p, result);
    }
    /** Only an authored, live melee sequence is classified as heavy; arbitrary damage cannot borrow its type. */
    private boolean swordHeavyMob(Entity entity){
        if(!(entity instanceof LivingEntity living)||!config.current().combat().swordCombat().heavyMobTypes().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))return false;
        var attack=creatureMelee.snapshot(living);return attack!=null&&!attack.clip().isEmpty();
    }
    private boolean creatureClash(LivingIncomingDamageEvent event,ServerPlayer defender) {
        Entity source=event.getSource().getEntity(),direct=event.getSource().getDirectEntity();
        if(!(source instanceof LivingEntity enemy)||source==defender||source!=direct||source instanceof ServerPlayer)return false;
        Long contactTick=creatureMelee.invocationContactTick(source,direct,defender);
        double multiplier=contactTick==null?consumeMeleeCounter(enemy):creatureMelee.invocationDamageMultiplier();
        event.setAmount((float)Math.min(100_000_000,event.getAmount()*multiplier));
        if(contactTick==null)return false;
        var attack=creatureMelee.snapshot(enemy);
        long sequence=attack==null?-1:attack.sequence();
        WindowClashAttempt window=windowClashAttempt;
        if(creatureMelee.invocationWindowClash()&&window!=null&&window.hit.attacker()==defender
                &&window.hit.victim()==enemy&&window.creatureSequence==sequence&&eligibleClash(window.hit)) {
            window.settled=true;
            settleCreatureExchange(event,defender,enemy,sequence,window.hit);
            return true;
        }
        // An authored monster can be answered only by that swing's first ACTIVE in its
        // own cue window. Actual contact proximity alone must not re-authorize it.
        if(attack!=null&&!attack.clip().isEmpty())return false;
        var reverse=meleeContacts.contacts().stream().filter(c->c.attacker().equals(defender.getUUID())&&c.victim().equals(enemy.getUUID())
                && c.value().opponentSequence()==sequence&&eligibleClash(c.value())
                &&Math.abs(c.tick()-contactTick)<=config.current().combat().weaponClash().contactWindowTicks())
                .min(Comparator.comparingLong(c->Math.abs(c.tick()-contactTick))).orElse(null);
        if(reverse!=null) {
            MeleeHit hit=reverse.value();
            settleCreatureExchange(event,defender,enemy,sequence,hit);
            return true;
        }
        if(creatureMelee.invocationDeferred()) {
            // This contact already happened while the player was attacking. Later C/charge/dodge
            // input cannot turn an unanswered contact into a retroactive defense.
            actor(defender).markCombatActivity();actor(defender).queueHitStun(config.current().combat().hitStunTicks());
            return true;
        }
        return false;
    }
    private void settleCreatureExchange(LivingIncomingDamageEvent event,ServerPlayer defender,LivingEntity enemy,long sequence,MeleeHit hit) {
        var exchange=WeaponClash.compare(hit.damage(),Math.max(0,event.getAmount()),config.current().combat().weaponClash().damageTolerance());
        meleeContacts.removeSwing(hit.attacker().getUUID(),hit.sequence());ordinaryMelee.removeIf(h->sameSwing(h,hit));
        settleClash(defender,hit.sequence(),enemy,sequence,exchange);
        if(exchange.firstDamage()>0)deliverMelee(hit,exchange.firstDamage(),true);
        if(exchange.secondDamage()>0){markGuardedDamage(defender,event.getSource());event.setAmount((float)exchange.secondDamage());}else event.setCanceled(true);
    }
    private void projectile(ProjectileImpactEvent event) {
        if(archery.onProjectileImpact(event)||event.isCanceled())return;
        if (event.getProjectile().level().isClientSide() || !(event.getProjectile() instanceof AbstractArrow arrow) || !(event.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof ServerPlayer p)) return;
        reconcileAndSync(p);
        CombatActor defender = actor(p);
        if (defender.invulnerable()) { event.setCanceled(true); return; }
        if (!defender.guardPerfectAgainst(null)
                || defender.stamina() < defender.guardRules().perfectGuardStaminaCost()) return;
        Vec3 incomingVelocity = arrow.getDeltaMovement();
        if (incomingVelocity.lengthSqr() < 1e-9) return;
        double incomingAngle = angle(p.getLookAngle(), incomingVelocity.scale(-1));
        // This is only an interception prepass. Let ordinary damage handle nonfrontal arrows once.
        if (!CombatResolver.withinArc(incomingAngle, defender.guardRules().arcDegrees())) return;
        Entity shooter = arrow.getOwner();
        if (shooter != null && (shooter.isRemoved() || shooter.level() != arrow.level())) shooter = null;
        var result = CombatResolver.receiveIncoming(shooter instanceof ServerPlayer q ? actor(q) : null, defender, incomingAngle, 1, 0, true, true, true);
        if (!result.reflectProjectile()) return;
        archery.markProjectileContact(arrow);
        event.setCanceled(true);
        double speed = Math.max(1, incomingVelocity.length());
        Vec3 direction = shooter != null ? shooter.getBoundingBox().getCenter().subtract(hit.getLocation()).normalize() : incomingVelocity.scale(-1).normalize();
        if (direction.lengthSqr() < 1e-9) direction = incomingVelocity.scale(-1).normalize();
        arrow.setOwner(p);
        arrow.shoot(direction.x, direction.y, direction.z, (float) speed, 0);
        arrow.setPos(hit.getLocation().add(direction.scale(arrow.getBbWidth() + 0.1)));
        arrow.hurtMarked = true;
        effects(shooter, p, result);
    }
    private void recordArcheryContact(Entity source,Entity direct) {
        if(direct instanceof Projectile projectile)archery.markProjectileContact(projectile);
        else if(source instanceof ServerPlayer player)archery.markBashContact(player.getUUID());
    }
    private void markGuardedDamage(LivingEntity victim,net.minecraft.world.damagesource.DamageSource source){
        if(victim instanceof ServerPlayer)guardedDamage.put(victim.getUUID(),new GuardedDamage(source,victim.level().getGameTime()));
    }
    private void guardedDamageCommitted(LivingDamageEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        GuardedDamage marked=guardedDamage.remove(player.getUUID());
        if(marked==null||marked.source()!=event.getSource()||marked.gameTick()!=player.level().getGameTime())return;
        Entity cause=event.getSource().getEntity(),direct=event.getSource().getDirectEntity();
        PacketDistributor.sendToPlayer(player,new RpgGuardImpactPayload(player.getUUID(),cause==null?-1:cause.getId(),direct==null?-1:direct.getId(),true));
    }
    private void effects(Entity attacker, LivingEntity victim, org.fsrpg.combat.HitResult result) {effects(attacker,victim,result,true);}
    private void effects(Entity attacker, LivingEntity victim, org.fsrpg.combat.HitResult result,boolean stunAttacker) {
        if (!(victim.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (stunAttacker && result.outcome() == HitOutcome.PARRIED && attacker instanceof LivingEntity living
                && !(living instanceof ServerPlayer)) {
            int duration = victim instanceof ServerPlayer defender ? actor(defender).swordCombatEnabled()?actor(defender).rules().swordCombat().parryStunTicks():actor(defender).guardRules().parryStunTicks() : config.current().combat().parryStunTicks();
            if (duration > 0 && living.isAlive()) {
                enemyStuns.put(living.getUUID(), new EnemyStun(living, living.level().getGameTime() + duration));
                creatureMelee.cancelAttacker(living);
                stopStunnedEnemy(living);
            }
        }
        boolean metal = result.outcome() == HitOutcome.CLASH || result.outcome() == HitOutcome.PARRIED || result.outcome() == HitOutcome.BLOCKED;
        if (metal) {
            if(!result.dealsDamage()&&victim instanceof ServerPlayer p)
                PacketDistributor.sendToPlayer(p,new RpgGuardImpactPayload(p.getUUID(),-1,-1,false));
            level.playSound(null, victim.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 0.8f, result.outcome() == HitOutcome.PARRIED ? 1.5f : 1.0f);
            level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 1, victim.getZ(), 8, 0.25, 0.25, 0.25, 0.1);
            if (result.outcome() == HitOutcome.PARRIED && victim instanceof ServerPlayer p) {
                p.displayClientMessage(Component.literal(actor(p).swordCombatEnabled()?"§b振刀成功 · 左键专用还击":"§b完美格挡 · 0.2秒内左键反击 ×1.5"), true);
                send(p, "", null);
            }
        }
    }
    /** Read-only server time, also used by the copied-world verification harness. */
    public int perfectGuardStunRemaining(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            CombatActor actor = actors.get(player.getUUID());
            return actor != null && actor.phase() == CombatPhase.STUNNED ? actor.snapshot().phaseTicksRemaining() : 0;
        }
        EnemyStun stun = enemyStuns.get(entity.getUUID());
        if (stun == null) return 0;
        if (stun.entity() != entity || !entity.isAlive() || entity.isRemoved()
                || entity.level().getGameTime() >= stun.untilGameTick()) {
            enemyStuns.remove(entity.getUUID()); return 0;
        }
        return (int)Math.min(Integer.MAX_VALUE, stun.untilGameTick() - entity.level().getGameTime());
    }
    private static void stopStunnedEnemy(LivingEntity enemy) {
        if (enemy instanceof Mob mob) { mob.getNavigation().stop(); mob.setXxa(0); mob.setZza(0); }
        enemy.setDeltaMovement(0, 0, 0); enemy.hurtMarked = true;
    }
    private void enemyTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide() || entity instanceof ServerPlayer || !(entity instanceof LivingEntity enemy)) return;
        if (perfectGuardStunRemaining(entity) > 0) {
            stopStunnedEnemy(enemy);
            // Prevent goals, Brain, movement and attack timers, without ever persisting a noAI flag.
            event.setCanceled(true);
        }
    }
    private void claim(ServerPlayer p, String id) throws IOException {
        var before = quests.snapshot();
        var reward = quests.claim(p.getUUID(), id);
        try { saveQuests(); } catch (IOException error) { quests.restore(before); throw error; }
        for (var item : reward.items()) {
            var value = BuiltInRegistries.ITEM.getValue(Identifier.parse(item.id()));
            int remaining = item.count();
            while (remaining > 0) {
                ItemStack stack = new ItemStack(value, Math.min(remaining, value.getDefaultInstance().getMaxStackSize()));
                remaining -= stack.getCount();
                if (!p.addItem(stack)) p.drop(stack, false);
            }
        }
        p.giveExperiencePoints(reward.xp());
    }
    private void saveQuests() throws IOException { store.save(quests.snapshot()); savedRevision = quests.revision(); }
    private void saveCollections() throws IOException { collectionStore.save(collections.snapshot()); collectionSavedRevision = collections.revision(); }
    private void exchangeCollection(ServerPlayer p, CollectionService.Plan plan) throws IOException {
        CollectionInventory.preflight(p, plan);
        var before = collections.snapshot();
        var exchange = collections.begin(plan);
        try { saveCollections(); } catch (IOException error) { collections.restore(before); throw error; }
        try { CollectionInventory.applyOnce(server, p, exchange); }
        catch (IOException | RuntimeException error) {
            if (!CollectionInventory.applied(p, exchange.transactionId())) {
                collections.cancel(p.getUUID(), exchange.transactionId()); saveCollections();
            }
            throw error;
        }
        var pending = collections.snapshot();
        collections.finish(p.getUUID(), exchange.transactionId());
        try { saveCollections(); }
        catch (IOException error) { collections.restore(pending); throw new IOException("奖励已存入背包，进度保存待恢复，请稍后重试", error); }
    }
    private void recoverCollection(ServerPlayer p) {
        collections.pending(p.getUUID()).ifPresent(exchange -> {
            var before = collections.snapshot();
            try {
                if (CollectionInventory.applied(p, exchange.transactionId())) {
                    CollectionInventory.applyOnce(server, p, exchange);
                    collections.finish(p.getUUID(), exchange.transactionId());
                } else collections.cancel(p.getUUID(), exchange.transactionId());
                saveCollections();
            }
            catch (IOException error) { collections.restore(before); LOG.error("FSRPG collection recovery could not save for {}", p.getUUID(), error); }
        });
    }
    private static String collectionMessage(String message) {
        if (message.startsWith("hunt your first monster")) return "请先猎杀第一个怪物，再提交生物掉落物";
        if (message.startsWith("creature already submitted")) return "这种生物已经提交过了";
        if (message.startsWith("crop already submitted")) return "这种作物已经提交过了";
        if (message.startsWith("not enough collection ingredients")) return "材料不足，请先收齐任务要求的物品";
        if (message.startsWith("choose a different crop")) return "请选择与本次提交不同的作物种子";
        if (message.startsWith("no unclaimed seed choice")) return "这个作物的种子奖励已领取，或尚未提交";
        if (message.startsWith("a collection inventory exchange")) return "上次提交正在恢复，请稍后重试";
        if (message.startsWith("unknown creature collection") || message.startsWith("unknown crop collection")) return "任务种类不存在，请重新打开任务页";
        return message;
    }
    private JsonArray collectionItems(List<CollectionCatalog.Item> items, Map<String, Integer> owned) {
        JsonArray result = new JsonArray();
        for (var item : items) {
            JsonObject value = RpgConfig.JSON.toJsonTree(item).getAsJsonObject();
            if (owned != null) value.addProperty("owned", owned.getOrDefault(item.key(), 0)); result.add(value);
        }
        return result;
    }
    private JsonObject collectionSnapshot(ServerPlayer p) {
        var state = collections.progress(p.getUUID()); var catalog = collections.catalog();
        var owned = CollectionInventory.owned(p); boolean idle = collections.pending(p.getUUID()).isEmpty();
        JsonObject result = new JsonObject(), hunt = new JsonObject();
        hunt.addProperty("complete", state.firstHuntComplete()); hunt.addProperty("entityId", state.firstHuntEntity()); result.add("firstHunt", hunt);
        result.addProperty("mobCount", state.mobCount()); result.addProperty("cropCount", state.cropCount());
        JsonArray milestones = new JsonArray();
        for (var milestone : catalog.mobMilestones()) {
            JsonObject value = new JsonObject(); value.addProperty("target", milestone.target());
            value.addProperty("claimed", state.claimedMobMilestones().contains(milestone.target()));
            value.add("rewards", collectionItems(milestone.rewards(), null)); milestones.add(value);
        }
        result.add("mobMilestones", milestones);
        JsonObject cropMilestone = new JsonObject(); cropMilestone.addProperty("target", catalog.cropMilestone().target());
        cropMilestone.addProperty("claimed", state.cropMilestoneClaimed()); cropMilestone.add("rewards", collectionItems(catalog.cropMilestoneRewards(), null)); result.add("cropMilestone", cropMilestone);
        JsonArray mobs = new JsonArray();
        for (var mob : catalog.mobs()) {
            JsonObject value = new JsonObject(); boolean submitted = state.submittedMobs().contains(mob.id());
            value.addProperty("id", mob.id()); value.addProperty("entity", mob.entity()); value.addProperty("label", mob.label()); value.addProperty("submitted", submitted);
            value.add("requirements", collectionItems(mob.drops(), owned)); value.add("rewards", collectionItems(catalog.mobRewards(mob.id()), null));
            value.addProperty("canSubmit", idle && !submitted && (!catalog.firstHuntRequired() || state.firstHuntComplete()) && CollectionService.hasRequirements(mob.drops(), owned)); mobs.add(value);
        }
        result.add("mobs", mobs);
        JsonArray crops = new JsonArray(), choices = new JsonArray();
        for (var crop : catalog.crops()) {
            JsonObject value = new JsonObject(); boolean submitted = state.submittedCrops().contains(crop.id());
            value.addProperty("id", crop.id()); value.addProperty("label", crop.label()); value.addProperty("seed", crop.seed()); value.addProperty("mature", crop.mature());
            value.addProperty("submitted", submitted); value.addProperty("pendingChoice", state.pendingSeedChoices().contains(crop.id())); value.addProperty("rewardClaimed", state.claimedSeedChoices().contains(crop.id()));
            value.add("requirements", collectionItems(catalog.cropRequirements(crop.id()), owned));
            value.addProperty("canSubmit", idle && !submitted && CollectionService.hasRequirements(catalog.cropRequirements(crop.id()), owned)); crops.add(value);
            JsonObject choice = new JsonObject(); choice.addProperty("id", crop.id()); choice.addProperty("label", crop.label()); choice.addProperty("seed", crop.seed()); choices.add(choice);
        }
        result.add("crops", crops); result.add("seedChoices", choices); return result;
    }
    private void save() {
        try {
            if (savedRevision != quests.revision()) saveQuests();
            if (collectionSavedRevision != collections.revision()) saveCollections();
            if(progressionSavedRevision!=progression.revision()){progressionStore.save(progression.snapshot());progressionSavedRevision=progression.revision();}
            archery.save(archeryFile);
            for (var entry : actors.entrySet()) resources.put(entry.getKey(), persisted(entry.getValue()));
            RpgConfig.atomicWrite(resourceFile, RpgConfig.JSON.toJson(resources));
        } catch (IOException failure) { LOG.error("FSRPG progress save failed; existing files preserved", failure); }
    }
    private void validateRegistryIds(RpgConfig.Definitions definitions) {
        try {
            CreatureCombatCatalog candidate = CreatureCombatCatalog.read(config.directory().resolve("creature_combat.json"));
            for (var attack : candidate.definitions())
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(attack.entityType())))
                    throw new IllegalArgumentException("Unknown creature combat entity " + attack.entityType());
            validatedCreatureCombat = candidate;
            validatedGuardCues = GuardCueSettings.read(config.directory().resolve("guard_cues.json"));
        } catch (IOException error) { throw new IllegalArgumentException("creature_combat.json: " + error.getMessage(),error); }
        for(String id:definitions.combat().swordCombat().heavyMobTypes())if(!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(id)))throw new IllegalArgumentException("Unknown sword heavy entity "+id);
        definitions.collections().validateReferences(id -> BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)),
                id -> BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(id)), id -> BuiltInRegistries.POTION.containsKey(Identifier.parse(id)));
        for (var group : definitions.weapons().groups().values()) {
            for (String id : group.mainItems()) weaponItemExists(id);
            for (String id : group.offhandItems()) weaponItemExists(id);
        }
        for (String id : definitions.weapons().itemDamageMultipliers().keySet()) weaponItemExists(id);
        for (var quest : definitions.quests().definitions()) {
            for (var reward : quest.rewards().items()) itemExists(reward.id());
            for (var objective : quest.objectives()) {
                if (objective.type() == QuestDefinition.ObjectiveType.COLLECT) itemExists(objective.target());
                if (objective.type() == QuestDefinition.ObjectiveType.KILL && !BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(objective.target()))) throw new IllegalArgumentException("Unknown entity " + objective.target());
            }
        }
    }
    private static void itemExists(String id) { if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)) || id.equals("minecraft:air")) throw new IllegalArgumentException("Unknown/reward-invalid item " + id); }
    private static void weaponItemExists(String id) { if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(id))) throw new IllegalArgumentException("Unknown weapon item " + id); }
    private static WeaponStyleDefinition defaultStyle(RpgConfig.Definitions definitions) {
        WeaponStyleDefinition rapier = definitions.styles().get("rapier");
        return rapier != null && rapier.enabled() ? rapier : definitions.styles().values().stream().filter(WeaponStyleDefinition::enabled).findFirst().orElseThrow();
    }
    private static Resources persisted(CombatActor actor) {
        return new Resources(actor.stamina(), actor.spirit(), actor.style() == null ? null : actor.style().id());
    }
    private static String comboRejection(ComboInputResult result) {
        return switch (result.status()) {
            case TOO_EARLY -> "输入过早，尚未到连击缓冲区";
            case TOO_LATE -> "输入过晚，连击窗口已关闭";
            case ALREADY_QUEUED -> "下一段已经排队，无需重复输入";
            case COMBO_FINISHED -> "最后一段结束后才能开始新连击";
            default -> "连击请求未通过：" + (result.rejection() == null ? result.status() : result.rejection());
        };
    }
    private boolean startedSkills(ServerPlayer player) {
        CombatActor actor = actor(player);
        List<SkillStart> starts = actor.drainStartedSkills();
        for (SkillStart started : starts) {
            swingItems.put(player.getUUID(), mainItem(player));
            String off=BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).toString();
            swingOffhands.put(player.getUUID(),off);
            damageItems.put(player.getUUID(),actor.style()!=null&&actor.style().controls().leftHand(started.skillId())?off:mainItem(player));
            quests.useSkill(player.getUUID(), started.skillId());
            if(actor.swordCombatEnabled()){
                double distance=actor.rules().swordCombat().motionDistances().getOrDefault(started.skillId(),0.0);
                var aim=actor.attackAim();double yaw=aim==null?player.getYRot():aim.sample(0).yaw();
                swordMotions.put(player.getUUID(),new SwordMotionPlan(started.swingSequence(),yaw,distance,actor.rules().swordCombat().steeringFraction()));
            }
        }
        return !starts.isEmpty();
    }
    private RpgAnimationPayload animationPayload(ServerPlayer player) {
        CombatActor actor = actor(player);
        CombatSnapshot snapshot = actor.snapshot();
        WeaponStyleDefinition style = actor.style();
        String styleId = style == null ? "" : style.presentationStyleId();
        SkillDefinition skill = actor.currentSkill();
        boolean visible = (actor.drawn() || actor.phase()==CombatPhase.DRAWING&&style!=null&&!style.controls().spear()) && player.isAlive();
        String model = "", action = "";
        String phase=snapshot.phase().name();int remaining=snapshot.phaseTicksRemaining();
        int windup=skill==null?0:skill.windupTicks(),active=skill==null?0:skill.activeTicks(),recovery=skill==null?0:skill.recoveryTicks();
        if (visible && skill != null) { model = skill.model(); action = skill.action(); }
        else if (visible && style != null && !style.leftCombo().isEmpty()) {
            SkillDefinition base = config.current().skills().get(style.leftCombo().getFirst().skillId());
            if (base != null) model = base.model();
            if(style.controls().dual()) action=actor.phase()==CombatPhase.GUARDING?"dual_guard":"dual_idle_00";
            else if(style.controls().spear()) action=actor.phase()==CombatPhase.GUARDING?"spear_guard":"spear_idle";
            else if (styleId.equals("rapier")) action = actor.phase() == CombatPhase.GUARDING
                    ? actor.heavyGuard() ? "rapier_guard_heavy" : "rapier_guard" : "rapier_idle";
        }
        if(visible&&style!=null&&style.controls().legacyDual()&&model.startsWith("legacy_")) {
            model="legacy_dual_blade.fsvoxel";
            if(skill==null)action=actor.phase()==CombatPhase.DRAWING?"dual_draw":actor.phase()==CombatPhase.GUARDING?"dual_guard":"dual_idle_"+Math.max(0,actor.leftSide()+1)+Math.max(0,actor.rightSide()+1);
        }
        if(visible&&styleId.equals("rapier")&&(model.startsWith("legacy_")||model.equals("rapier_fullbody.fsvoxel"))) {
            if(actor.phase()==CombatPhase.DRAWING)action="rapier_draw";
            if(skill!=null&&skill.id().equals("sword_thrust")&&actor.phase()==CombatPhase.WINDUP&&actor.chargeSnapshot().holding())
                action=actor.chargeSnapshot().fullyCharged()?"rapier_full_charge":"rapier_charge";
        }
        if(visible&&actor.swordCombatEnabled()&&actor.phase()==CombatPhase.PARRYING){var r=actor.rules().swordCombat();action="rapier_parry";windup=r.parryWindupTicks();active=r.parryActiveTicks();recovery=r.parryRecoveryTicks();}
        if(visible&&styleId.equals("bow")) {
            var bow=archery.snapshot(player,archeryProgress(player));model="legacy_bow.fsvoxel";action=bow.animationAction();
            // The actor can become visible before the independent bow controller is active.
            if(action.isBlank())action=style.id().equals("bow_snipe")?"bow_snipe_idle":"bow_rapid_idle";
            windup=active=recovery=0;
            if(actor.phase()==CombatPhase.DRAWING){action="bow_draw";phase="DRAWING";remaining=snapshot.phaseTicksRemaining();}
            else if(bow.charging()&&!action.endsWith("_release")) {
                phase="WINDUP";windup=Math.max(1,(int)Math.ceil(bow.requiredDrawTicks()));
                remaining=Math.max(1,(int)Math.ceil(bow.requiredDrawTicks()*(1-bow.chargeProgress())));
            } else if(action.endsWith("_release")||action.equals("bow_bash")) {
                phase="ACTIVE";active=action.equals("bow_bash")?config.current().archery().bashAnimationTicks():config.current().archery().shotAnimationTicks();
                remaining=Math.max(0,bow.animationRemainingTicks());
            } else {phase=snapshot.phase().name();remaining=snapshot.phaseTicksRemaining();}
        }
        return new RpgAnimationPayload(player.getUUID(), styleId, model, action, phase, remaining,
                windup,active,recovery,
                animationSequences.getOrDefault(player.getUUID(), 0L), visible,actor.leftSide(),actor.rightSide(),actor.attackAim(),actor.spearAttackHeld(),snapshot.swingSequence());
    }
    private void broadcastAnimation(ServerPlayer player, boolean heartbeat) {
        CombatActor actor = actor(player);
        RpgAnimationPayload payload = animationPayload(player);
        String state = payload.style() + "|" + payload.model() + "|" + payload.action() + "|" + payload.phase() + "|" + payload.drawn() + "|" + payload.spearHeld() + "|" + actor.snapshot().swingSequence()
                +(ArcheryServer.bowStyle(actor)?"|"+archery.snapshot(player,archeryProgress(player)).sequence():"");
        boolean changed = !state.equals(animationStates.get(player.getUUID()));
        if (changed) {
            animationStates.put(player.getUUID(), state);
            animationSequences.merge(player.getUUID(), 1L, Long::sum);
            payload = animationPayload(player);
        }
        if (changed || heartbeat) PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload);
    }
    private double attackFacing(ServerPlayer p,Entity target) {
        var a=actor(p); if(a.style()!=null&&a.style().controls().spear())return 0;
        if(a.style()!=null&&(a.style().controls().dual() || a.style().presentationStyleId().equals("rapier"))&&a.attackAim()!=null) {
            var point=a.attackAim().sample(0);return angle(Vec3.directionFromRotation((float)point.pitch(),(float)point.yaw()),target.position().subtract(p.position()));
        }
        return facing(p,target);
    }
    private boolean spearContact(ServerPlayer p,LivingEntity target,CombatActor actor) {
        return spearImpact(p,target,actor)!=null;
    }
    private SpearRay spearImpact(ServerPlayer p,LivingEntity target,CombatActor actor) {
        if(actor.activeSkill()==null)return null;
        for(var ray:spearRays.getOrDefault(p.getUUID(),List.of())) {
            var origin=ray.origin();var box=target.getBoundingBox().inflate(ray.padding());
            var contact=box.contains(origin)?Optional.of(origin):box.clip(origin,ray.tip());
            if(contact.isPresent()) {
                var wall=p.level().clip(new net.minecraft.world.level.ClipContext(origin,contact.get(),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,p));
                if(wall.getType()==net.minecraft.world.phys.HitResult.Type.MISS || wall.getLocation().distanceToSqr(origin)+.001>=contact.get().distanceToSqr(origin))return ray;
            }
        }
        return null;
    }
    private static double attackDistance(Entity attacker, Entity target) {
        Vec3 origin = attacker.getEyePosition();
        AABB box = target.getBoundingBox();
        Vec3 contact = new Vec3(Math.max(box.minX, Math.min(box.maxX, origin.x)),
                Math.max(box.minY, Math.min(box.maxY, origin.y)), Math.max(box.minZ, Math.min(box.maxZ, origin.z)));
        return origin.distanceTo(contact);
    }
    private static double facing(Entity observer, Entity target) { return angle(observer.getLookAngle(), target.position().subtract(observer.position())); }
    private static double angle(Vec3 forward, Vec3 direction) {
        Vec3 a = forward.multiply(1, 0, 1).normalize(), b = direction.multiply(1, 0, 1).normalize();
        if (a.lengthSqr() == 0 || b.lengthSqr() == 0) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b)))));
    }
    private void send(ServerPlayer p, String message, JsonObject editResult) {
        JsonObject snapshot = RpgConfig.JSON.toJsonTree(actor(p).snapshot()).getAsJsonObject();
        snapshot.addProperty("playerLevel",actor(p).playerLevel());
        snapshot.addProperty("staminaRegenPerSecond",actor(p).staminaRegenPerSecond());
        snapshot.addProperty("currentStaminaRegenPerSecond",actor(p).currentStaminaRegenPerSecond());
        snapshot.addProperty("staminaCombatState",actor(p).staminaCombatState());
        snapshot.add("burstDash",RpgConfig.JSON.toJsonTree(actor(p).burstDashSnapshot()));
        snapshot.add("burstDashRules",RpgConfig.JSON.toJsonTree(actor(p).rules().burstDash()));
        snapshot.addProperty("skill", actor(p).snapshot().skillId());
        snapshot.addProperty("canEdit", canEdit(p)); snapshot.addProperty("creative", p.isCreative()); snapshot.addProperty("message", message);
        snapshot.add("skills", RpgConfig.JSON.toJsonTree(config.current().skills().values()));
        snapshot.add("styles", RpgConfig.JSON.toJsonTree(config.current().styles().values()));
        snapshot.addProperty("weaponStyle", actor(p).style() == null ? "" : actor(p).style().id());
        snapshot.addProperty("weaponMode", actor(p).style() == null ? "" : actor(p).style().presentationStyleId());
        snapshot.addProperty("weaponEquipped", hasWeapon(p));
        snapshot.addProperty("cameraAvailable",!p.getMainHandItem().isEmpty());
        List<WeaponStyleDefinition> drawStates = matchingStyles(p);
        snapshot.addProperty("drawStateCount", drawStates.size());
        snapshot.addProperty("drawStateIndex", drawStates.indexOf(actor(p).style()));
        snapshot.addProperty("leftSide",actor(p).leftSide());snapshot.addProperty("rightSide",actor(p).rightSide());
        snapshot.add("combo", RpgConfig.JSON.toJsonTree(actor(p).comboSnapshot()));
        snapshot.addProperty("guardMode", actor(p).heavyGuard() ? "HEAVY" : "LIGHT");
        snapshot.add("technique", RpgConfig.JSON.toJsonTree(actor(p).techniqueSnapshot()));
        snapshot.addProperty("counterRemainingTicks", actor(p).counterRemainingTicks());
        snapshot.addProperty("attackDamageMultiplier", actor(p).attackDamageMultiplier());
        snapshot.addProperty("legacySkills",true);
        snapshot.add("charge",RpgConfig.JSON.toJsonTree(actor(p).chargeSnapshot()));
        snapshot.addProperty("swordCombatEnabled",actor(p).swordCombatEnabled());
        snapshot.addProperty("swordHeavyAttack",actor(p).swordHeavyAttack());
        snapshot.addProperty("swordSuperArmor",actor(p).swordSuperArmor());
        snapshot.addProperty("swordParryWindow",actor(p).swordParryWindow());
        snapshot.addProperty("swordReturnRemainingTicks",actor(p).swordReturnRemainingTicks());
        snapshot.add("swordCombatRules",RpgConfig.JSON.toJsonTree(actor(p).rules().swordCombat()));
        snapshot.add("targetLock",RpgConfig.JSON.toJsonTree(rapierTargets.snapshot(p, actor(p))));
        var bowProgress=archeryProgress(p);var bowJson=archery.snapshotJson(p,bowProgress);var bowRules=archery.rules();
        bowJson.addProperty("fullSnipeArrowSpeed",ArcheryScores.arrowSpeed(bowRules,bowProgress,true,1));
        bowJson.addProperty("fullRapidArrowSpeed",ArcheryScores.arrowSpeed(bowRules,bowProgress,false,1));
        bowJson.addProperty("scopeFullMagnification",ArcheryScores.scopeMagnification(bowRules,bowProgress,1));
        bowJson.addProperty("scopeUnlocked",bowProgress.score()>=bowRules.scopeScore());
        snapshot.add("archery",bowJson);
        JsonObject skillTree=new JsonObject();skillTree.add("view",RpgConfig.JSON.toJsonTree(progression.view(p.getUUID())));
        skillTree.add("nodes",RpgConfig.JSON.toJsonTree(progression.nodes(p.getUUID())));skillTree.add("effects",RpgConfig.JSON.toJsonTree(progression.effects(p.getUUID())));
        skillTree.add("footwork",RpgConfig.JSON.toJsonTree(progression.footworkView(p.getUUID())));
        skillTree.addProperty("trainingHint","睡眠开启心法；食物、在线时间增长心法；实际承受敌对伤害开启煅体；采矿、走路、承受攻击增长煅体与步法");
        snapshot.add("progression",skillTree);
        // Transient footwork clocks are presentation data; the client never decides a distance or window.
        snapshot.add("footworkMotion",footwork.snapshot(p));
        JsonArray journal = new JsonArray();
        for (var progress : quests.progress(p.getUUID())) {
            var definition = quests.catalog().get(progress.questId());
            JsonObject quest = new JsonObject();
            quest.addProperty("id", definition.id()); quest.addProperty("label", definition.label());
            quest.addProperty("description", definition.description()); quest.addProperty("status", progress.status().name());
            quest.addProperty("repeatable", definition.repeatable());
            quest.addProperty("rewardText", RpgConfig.JSON.toJson(definition.rewards()));
            JsonArray objectives = new JsonArray();
            for (var objective : definition.objectives()) {
                JsonObject obj = RpgConfig.JSON.toJsonTree(objective).getAsJsonObject();
                obj.addProperty("label", objective.type() + " " + (objective.target() == null ? "" : objective.target()));
                obj.addProperty("current", progress.objectiveCounts().getOrDefault(objective.id(), 0));
                objectives.add(obj);
            }
            quest.add("objectives", objectives); journal.add(quest);
        }
        snapshot.add("quests", journal);
        snapshot.add("collection", collectionSnapshot(p));
        if (editResult != null) snapshot.add("editResult", editResult);
        PacketDistributor.sendToPlayer(p, new RpgSnapshotPayload(RpgConfig.JSON.toJson(snapshot)));
    }
    private static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("fsrpg");
        for (String action : List.of("status", "draw", "dodge", "left", "right", "quests")) root.then(Commands.literal(action).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (instance != null) {
                instance.action(p, action.equals("left") || action.equals("right") ? "attack" : action.equals("quests") ? "status" : action, action);
                if (action.equals("status") || action.equals("quests")) ctx.getSource().sendSuccess(() -> Component.literal("FSRPG: " + RpgConfig.JSON.toJson(instance.actor(p).snapshot()) + "; quests=" + instance.quests.progress(p.getUUID())), false);
            }
            return 1;
        }));
        root.then(Commands.literal("guard").then(Commands.argument("held", BoolArgumentType.bool()).executes(ctx -> { instance.action(ctx.getSource().getPlayerOrException(), "guard", String.valueOf(BoolArgumentType.getBool(ctx, "held"))); return 1; })));
        root.then(Commands.literal("guardMode").then(Commands.argument("mode", StringArgumentType.word()).executes(ctx -> {
            instance.action(ctx.getSource().getPlayerOrException(), "guard", StringArgumentType.getString(ctx, "mode")); return 1;
        })));
        root.then(Commands.literal("style").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
            instance.action(ctx.getSource().getPlayerOrException(), "weaponStyle", StringArgumentType.getString(ctx, "id")); return 1;
        })));
        for (String verb : List.of("accept", "claim", "skill")) root.then(Commands.literal(verb).then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
            instance.action(ctx.getSource().getPlayerOrException(), verb.equals("accept") ? "acceptQuest" : verb.equals("claim") ? "claimQuest" : "attack", StringArgumentType.getString(ctx, "id")); return 1;
        })));
        root.then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer p) instance.action(p, "reload", "");
            else { try { instance.applyConfig(instance.config.reload()); ctx.getSource().sendSuccess(() -> Component.literal("FSRPG config reloaded"), true); } catch (Exception error) { ctx.getSource().sendFailure(Component.literal(error.getMessage())); } }
            return 1;
        }));
        event.getDispatcher().register(root);
    }
}

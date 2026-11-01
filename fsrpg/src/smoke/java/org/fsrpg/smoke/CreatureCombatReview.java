package org.fsrpg.smoke;

import com.google.gson.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CreatureArtRenderer;
import org.fsrpg.client.HudReadout;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.creature.CreatureMeleeServer;
import org.fsrpg.mixin.EnderManStareInvoker;
import org.fsrpg.progression.ProgressionService;
import org.lwjgl.glfw.GLFW;

/** Copied-world acceptance of complete vanilla attacks delayed to server animation contact. */
final class CreatureCombatReview {
    private enum Mode { AUTO, FROZEN, SONIC, STARE, ENCHANTED, RANGE, WALL, GUARD, PERFECT, DEAD, WEAPON, TARGET, REMOVED, GOLEM, POISON, GENERIC }
    private record Scenario(String id, EntityType<?> type, Mode mode) {}
    private record Shot(String name, int levelIndex) {}
    private final Scenario[] cases={
            new Scenario("zombie-ai-auto",EntityType.ZOMBIE,Mode.AUTO),
            new Scenario("zombie-contact",EntityType.ZOMBIE,Mode.ENCHANTED),
            new Scenario("move-out-of-range",EntityType.ZOMBIE,Mode.RANGE),
            new Scenario("occluded-before-contact",EntityType.ZOMBIE,Mode.WALL),
            new Scenario("ordinary-c-guard",EntityType.ZOMBIE,Mode.GUARD),
            new Scenario("perfect-c-guard-counter",EntityType.ZOMBIE,Mode.PERFECT),
            new Scenario("dead-attacker-cancels",EntityType.ZOMBIE,Mode.DEAD),
            new Scenario("changed-weapon-cancels",EntityType.ZOMBIE,Mode.WEAPON),
            new Scenario("changed-target-cancels",EntityType.ZOMBIE,Mode.TARGET),
            new Scenario("removed-target-cancels",EntityType.ZOMBIE,Mode.REMOVED),
            new Scenario("warden-sonic-cancels",EntityType.WARDEN,Mode.SONIC),
            new Scenario("enderman-stare-cancels",EntityType.ENDERMAN,Mode.STARE),
            new Scenario("creaking-frozen-cancels",EntityType.CREAKING,Mode.FROZEN),
            new Scenario("iron-golem-independent-override",EntityType.IRON_GOLEM,Mode.GOLEM),
            new Scenario("cave-spider-poison-contact",EntityType.CAVE_SPIDER,Mode.POISON),
            new Scenario("unprofiled-hoglin-native",EntityType.HOGLIN,Mode.GENERIC)};
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray levels=new JsonArray(),scenarios=new JsonArray(),tickRows=new JsonArray(),stuns=new JsonArray();
    private final List<JsonObject> packets=new CopyOnWriteArrayList<>(),rendered=new CopyOnWriteArrayList<>();
    private final Queue<Shot> screenshots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingScreenshots=new AtomicInteger(),capturedScreenshots=new AtomicInteger(),levelScreenshotAck=new AtomicInteger(-1);
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final AutoCloseable snapshotObserver,renderObserver;
    private volatile Throwable failure;
    private volatile boolean ready,scriptDone,videoRequested,videoReady,videoStopRequested,videoStopped;
    private volatile JsonObject lastPacket=new JsonObject(),lastRendered=new JsonObject();
    private volatile UUID watchedMob;
    private volatile long observedServerTick,videoContactTick;
    private volatile int cameraEntityId=-1;
    private UUID playerId;
    private boolean initialized,done,autoAwaiting,levelPrepared,levelRegenChecked,levelScreenshotRequested,prepared,attackStarted,mutated,contactChecked,counterStarted,counterChecked,clampChecked;
    private int warmup,stage,levelIndex,caseIndex,previousFps,previousDistance,previousScale,enemyWorkTicks,enemyWorkAtContact;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private final int[] experienceLevels={0,1,10,100};
    private long started,serverTicks,nativeFrames,levelStarted,caseStarted,acceptedBefore,contactsBefore,canceledBefore,completedBefore;
    private double levelStaminaBefore,counterExpectedDamage,counterMultiplier;
    private Mob mob;
    private LivingEntity victim;
    private CreatureMeleeServer.Snapshot attack;
    private float healthBefore,contactHealth,mobHealthBeforeCounter;
    private int damageEvents,preContactDamageEvents,preContactSideEffects;
    private JsonObject levelRow,caseRow;

    CreatureCombatReview() {
        snapshotObserver=ClientState.observeSnapshots(snapshot->{if(!done&&initialized){lastPacket=snapshot.deepCopy();packets.add(snapshot.deepCopy());}});
        renderObserver=CreatureArtRenderer.observeRenderedAttack(sample->{
            if(done||watchedMob==null)return;
            String entity=ClientState.text(sample,"entityId",ClientState.text(sample,"entityUUID",""));
            if(!watchedMob.toString().equals(entity))return;
            lastRendered=sample.deepCopy();if(videoReady&&!videoStopped)rendered.add(sample.deepCopy());
        });
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,this::incoming);
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post event)->{if(event.getEntity()==mob)enemyWorkTicks++;});
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean ok,String description){if(!ok)throw new AssertionError(description);checks.add(description);System.out.println("CREATURE COMBAT PASS "+description);}
    private void near(double expected,double actual,String description){check(Double.isFinite(actual)&&Math.abs(expected-actual)<.002,description+" expected="+expected+" actual="+actual);}
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer player){return RpgSmoke.actor(player);}
    private void action(ServerPlayer player,String name,String argument){rpg().action(player,name,argument);}

    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(root.resolve("screenshots"));JsonObject environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        check("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()),"Review window belongs to DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name=System.getProperty("fsrpg.creatureCombatWorld","");
        check(name.equals("FSRPG Creature Combat Review 20261016 01")&&name.equals(world.getFileName().toString())
                &&Files.isRegularFile(world.resolve(".fsrpg-creature-combat-test-world")),"Only the named marked copied world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousCamera=game.options.getCameraType();previousActive=game.isWindowActive();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=environment.getAsJsonObject("monitorBounds");long window=game.getWindow().handle();
        GLFW.glfwSetWindowSize(window,1000,680);GLFW.glfwSetWindowPos(window,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);
        GLFW.glfwSetWindowTitle(window,"FSRPG 生物接触、格挡与等级体力验收 · DISPLAY5");game.resizeDisplay();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try {
                ServerPlayer player=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var entity:player.level().getEntities(player,player.getBoundingBox().inflate(48),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))entity.discard();
                player.setGameMode(GameType.SURVIVAL);player.setInvulnerable(false);player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);player.setHealth(200);player.getFoodData().setFoodLevel(18);nightVision(player);
                player.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);player.level().setDayTime(18000);player.level().setWeatherParameters(6000,0,false,false);
                for(int x=-8;x<=8;x++)for(int z=-6;z<=12;z++){
                    player.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                    for(int y=80;y<85;y++)player.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
                }
                player.connection.teleport(.5,80,.5,0,0);player.setDeltaMovement(Vec3.ZERO);player.getInventory().clearContent();player.getInventory().setSelectedSlot(0);fixtureProgress(player);
                player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));player.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))player.setItemSlot(slot,ItemStack.EMPTY);
                actor(player).forceSheathed();check(actor(player).setStyle(rpg().config().current().styles().get("rapier"))==ActionResult.ACCEPTED,"Copied player selects learned rapier style");action(player,"status","");
                ArmorStand camera=new ArmorStand(player.level(),4,80,-3);camera.setYRot(42);camera.setXRot(10);camera.setNoGravity(true);camera.setInvisible(true);camera.setInvulnerable(true);nightVision(camera);player.level().addFreshEntity(camera);cameraEntityId=camera.getId();
                ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    private void fixtureProgress(ServerPlayer player) {
        var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);
        JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}
        for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);
        one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();learned.add("rapier");one.add("learnedNodes",learned);
        service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));check(service.weaponUnlocked(playerId,"rapier"),"Copied fixture has the real server-learned rapier root");
    }
    private void heldGuard(boolean held){Minecraft.getInstance().execute(()->{for(var key:Minecraft.getInstance().options.keyMappings)if(key.getName().equals("key.fsrpg.guard"))key.setDown(held);});}
    private void guard(ServerPlayer player,boolean held){action(player,"guard",Boolean.toString(held));heldGuard(held);}

    private void serverTick(ServerTickEvent.Post event) {
        if(!initialized||!ready||done||scriptDone||failure!=null)return;
        try {
            ServerPlayer player=event.getServer().getPlayerList().getPlayer(playerId);if(player==null)return;serverTicks++;observedServerTick=player.level().getGameTime();
            if(stage==0){levelStep(player);return;}
            if(caseIndex>=cases.length){scriptDone=true;return;}
            caseStep(player,cases[caseIndex]);
        }catch(Throwable error){failure=error;}
    }
    private void levelStep(ServerPlayer player) {
        if(levelIndex>=experienceLevels.length){
            CombatActor a=actor(player);a.setResources(a.maxStamina(),a.spirit());player.setExperienceLevels(0);action(player,"status","");near(50,a.stamina(),"Actual experience downgrade clips stamina to the level-zero cap");
            player.setExperienceLevels(100);action(player,"status","");near(50,a.stamina(),"Actual experience upgrade does not refill stamina");
            player.setExperienceLevels(0);action(player,"status","");clampChecked=true;stage=1;return;
        }
        int level=experienceLevels[levelIndex];CombatActor a=actor(player);
        if(!levelPrepared){
            a.setResources(10,a.spirit());double before=a.stamina();player.setExperienceLevels(level);action(player,"status","");a=actor(player);
            double expectedMax=level==0?50:50+Math.pow(level,.8)+3*Math.log(level),expectedRegen=level==0?5:Math.floor(5+Math.sqrt(level)+Math.log(level));
            near(before,a.stamina(),"Level "+level+" changes limits without refilling current stamina");check(a.playerLevel()==level&&player.experienceLevel==level,"Real player experience level reaches actor at "+level);
            near(expectedMax,a.maxStamina(),"Level "+level+" maximum follows requested expression");near(expectedRegen,a.staminaRegenPerSecond(),"Level "+level+" regeneration follows requested expression");
            levelRow=new JsonObject();levelRow.addProperty("playerExperienceLevel",level);levelRow.addProperty("actorPlayerLevel",a.playerLevel());levelRow.addProperty("maxStamina",a.maxStamina());levelRow.addProperty("staminaRegenPerSecond",a.staminaRegenPerSecond());
            levelStarted=player.level().getGameTime();levelStaminaBefore=a.stamina();levelPrepared=true;return;
        }
        if(!levelRegenChecked&&player.level().getGameTime()>levelStarted){double delta=a.stamina()-levelStaminaBefore;near(a.staminaRegenPerSecond()/20,delta,"Real server next-tick regeneration at level "+level);levelRow.addProperty("observedTickRegen",delta);levelRegenChecked=true;action(player,"status","");}
        JsonObject packet=lastPacket;
        if(!levelRegenChecked||ClientState.number(packet,"playerLevel",-1)!=level||Math.abs(ClientState.number(packet,"maxStamina",-1)-a.maxStamina())>.002)return;
        if(!levelScreenshotRequested){
            near(a.staminaRegenPerSecond(),ClientState.number(packet,"staminaRegenPerSecond",-1),"Real received regeneration snapshot at level "+level);
            levelRow.addProperty("clientPlayerLevel",ClientState.number(packet,"playerLevel",-1));levelRow.addProperty("clientMaxStamina",ClientState.number(packet,"maxStamina",-1));levelRow.addProperty("clientStaminaRegenPerSecond",ClientState.number(packet,"staminaRegenPerSecond",-1));
            levelRow.addProperty("clientStamina",ClientState.number(packet,"stamina",-1));levelRow.addProperty("hudReadoutLabel",HudReadout.of(ClientState.number(packet,"stamina",Double.NaN),ClientState.number(packet,"maxStamina",Double.NaN)).label());
            screenshots.add(new Shot("level-"+level+"-actual-hud",levelIndex));levelScreenshotRequested=true;return;
        }
        if(levelScreenshotAck.get()<levelIndex)return;
        levels.add(levelRow);levelIndex++;levelPrepared=levelRegenChecked=levelScreenshotRequested=false;
    }
    private void prepare(ServerPlayer player,Scenario scenario) {
        if(mob!=null&&!mob.isRemoved())mob.discard();if(victim!=null&&victim!=player&&!victim.isRemoved())victim.discard();
        guard(player,false);actor(player).forceSheathed();player.setItemSlot(EquipmentSlot.MAINHAND,scenario.mode()==Mode.STARE?ItemStack.EMPTY:new ItemStack(Items.DIAMOND_SWORD));
        if(scenario.mode()==Mode.STARE)action(player,"status","");else action(player,"weaponStyle","rapier");actor(player).setResources(actor(player).maxStamina(),100);
        player.connection.teleport(.5,80,.5,scenario.mode()==Mode.STARE?180:0,0);player.setDeltaMovement(Vec3.ZERO);player.setHealth(player.getMaxHealth());player.clearFire();player.removeAllEffects();nightVision(player);player.invulnerableTime=0;player.hurtTime=0;clearWall(player);
        mob=(Mob)scenario.type().create(player.level(),EntitySpawnReason.COMMAND);check(mob!=null,"Spawn actual vanilla "+scenario.id());
        mob.setPos(.5,80,1.7);mob.setYRot(180);mob.yBodyRot=mob.yBodyRotO=180;mob.setNoGravity(true);mob.setNoAi(true);mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);mob.setHealth(200);if(mob.getAttribute(Attributes.ATTACK_DAMAGE)!=null)mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4);
        if(mob.getAttribute(Attributes.ARMOR)!=null)mob.getAttribute(Attributes.ARMOR).setBaseValue(0);if(mob.getAttribute(Attributes.ARMOR_TOUGHNESS)!=null)mob.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(0);
        if(mob.getAttribute(Attributes.MOVEMENT_SPEED)!=null)mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);if(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)!=null)mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        mob.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
        if(scenario.mode()==Mode.SONIC){mob.setPose(Pose.STANDING);mob.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_SOUND_DELAY);mob.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN);}
        if(scenario.mode()==Mode.ENCHANTED){ItemStack sword=new ItemStack(Items.IRON_SWORD);sword.enchant(player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FIRE_ASPECT),1);mob.setItemSlot(EquipmentSlot.MAINHAND,sword);}
        player.level().addFreshEntity(mob);victim=player;
        if(scenario.mode()==Mode.REMOVED){victim=(LivingEntity)EntityType.COW.create(player.level(),EntitySpawnReason.COMMAND);if(victim==null)throw new AssertionError("Cannot create removable target");victim.setPos(.5,80,.5);((Mob)victim).setNoAi(true);player.level().addFreshEntity(victim);}
        mob.setTarget(victim);check(mob.isWithinMeleeAttackRange(victim)&&mob.hasLineOfSight(victim),"Native melee range and visibility accept "+scenario.id());
        prepared=true;autoAwaiting=false;attackStarted=mutated=contactChecked=counterStarted=counterChecked=false;damageEvents=preContactDamageEvents=preContactSideEffects=0;enemyWorkTicks=0;healthBefore=victim.getHealth();
        caseRow=new JsonObject();caseRow.addProperty("id",scenario.id());caseRow.addProperty("entityType",BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        if(scenario.mode()==Mode.ENCHANTED){watchedMob=mob.getUUID();videoRequested=true;}
    }
    private void caseStep(ServerPlayer player,Scenario scenario) {
        if(!prepared){prepare(player,scenario);return;}
        if(!attackStarted){
            if(actor(player).phase()!=CombatPhase.READY&&!(scenario.mode()==Mode.STARE&&actor(player).phase()==CombatPhase.SHEATHED))return;if(scenario.mode()==Mode.ENCHANTED&&!videoReady)return;
            if(scenario.mode()==Mode.STARE)check(!((EnderManStareInvoker)mob).fsrpg$isBeingStaredBy(player),"Enderman melee begins while the actual target player is facing away");
            if(!autoAwaiting){
                acceptedBefore=rpg().creatureMelee().acceptedAttempts();contactsBefore=rpg().creatureMelee().contactAttempts();canceledBefore=rpg().creatureMelee().canceledAttacks();completedBefore=rpg().creatureMelee().completedAttacks();
                healthBefore=victim.getHealth();caseStarted=player.level().getGameTime();
            }
            if(scenario.mode()==Mode.AUTO&&!autoAwaiting){mob.setNoAi(false);autoAwaiting=true;return;}
            boolean immediate=false;
            if(scenario.mode()==Mode.AUTO){
                attack=rpg().creatureMelee().snapshot(mob);
                if(attack==null){check(player.level().getGameTime()-caseStarted<30,"Live vanilla zombie AI begins its own attack within thirty ticks");return;}
                check(!mob.isNoAi(),"First creature attack was scheduled by genuinely enabled vanilla AI");caseRow.addProperty("startedByVanillaAi",true);mob.setNoAi(true);
            }else {
                if(scenario.mode()==Mode.GUARD)guard(player,true);
                if(scenario.mode()==Mode.PERFECT)mob.setNoAi(false);
                immediate=mob.doHurtTarget(player.level(),victim);attack=rpg().creatureMelee().snapshot(mob);
            }
            attackStarted=true;
            caseRow.addProperty("immediateHealthLoss",healthBefore-victim.getHealth());
            if(scenario.mode()==Mode.GENERIC){check(rpg().creatureMelee().catalog().find("minecraft:hoglin","sword").isEmpty(),"Generic fixture has no supplied creature melee profile");check(attack==null&&immediate&&victim.getHealth()<healthBefore,"Unprofiled hoglin retains immediate original native attack");check(rpg().creatureMelee().acceptedAttempts()==acceptedBefore,"Unprofiled attack does not enter the animation controller");finishCase(player,scenario);return;}
            check(!immediate&&attack!=null,"Native attack attempt enters server windup: "+scenario.id());near(healthBefore,victim.getHealth(),"No immediate health loss: "+scenario.id());
            check(!victim.isOnFire()&&!victim.hasEffect(MobEffects.POISON),"No immediate enchantment or poison side effect: "+scenario.id());
            check(rpg().creatureMelee().acceptedAttempts()==acceptedBefore+1,"Exactly one accepted attack attempt: "+scenario.id());
            caseRow.addProperty("sequence",attack.sequence());caseRow.addProperty("startGameTick",attack.startedGameTick());caseRow.addProperty("contactGameTick",attack.contactGameTick());caseRow.addProperty("endGameTick",attack.endGameTick());caseRow.addProperty("clip",attack.clip());
            if(scenario.mode()==Mode.ENCHANTED)videoContactTick=attack.contactGameTick();
            for(int i=0;i<4;i++)mob.doHurtTarget(player.level(),victim);
            check(rpg().creatureMelee().acceptedAttempts()==acceptedBefore+1&&rpg().creatureMelee().snapshot(mob).sequence()==attack.sequence(),"Repeated actual doHurtTarget calls cannot restart windup: "+scenario.id());return;
        }
        long now=player.level().getGameTime();boolean cancellation=switch(scenario.mode()){case DEAD,WEAPON,TARGET,REMOVED,FROZEN,SONIC,STARE->true;default->false;};
        JsonObject tick=new JsonObject();tick.addProperty("scenario",scenario.id());tick.addProperty("gameTick",now);tick.addProperty("health",victim.getHealth());tick.addProperty("onFire",victim.isOnFire());tick.addProperty("poison",victim.hasEffect(MobEffects.POISON));tick.addProperty("mobPosition",mob.position().toString());tick.addProperty("victimPosition",victim.position().toString());tick.add("clock",RpgConfig.JSON.toJsonTree(rpg().creatureMelee().snapshot(mob)));tickRows.add(tick);
        if(now<attack.contactGameTick()){
            near(healthBefore,victim.getHealth(),"Windup tick "+(now-attack.startedGameTick())+" has no health loss: "+scenario.id());
            if(victim.isOnFire()||victim.hasEffect(MobEffects.POISON))preContactSideEffects++;
            check(preContactDamageEvents==0&&preContactSideEffects==0,"Windup has no damage event or status side effect: "+scenario.id());
            if(!mutated&&now>=attack.startedGameTick()+1){mutate(player,scenario);mutated=true;}
            if(scenario.mode()==Mode.FROZEN&&mutated&&now>attack.startedGameTick()+1){
                check(!((Creaking)mob).canMove(),"Native Creaking aiStep freezes from the actual player's gaze");
                long accepted=rpg().creatureMelee().acceptedAttempts();mob.doHurtTarget(player.level(),victim);
                check(rpg().creatureMelee().acceptedAttempts()==accepted&&rpg().creatureMelee().snapshot(mob)==null,"Frozen Creaking cannot begin another melee windup");
            }
            if((scenario.mode()==Mode.SONIC||scenario.mode()==Mode.STARE)&&mutated&&now>attack.startedGameTick()+1){
                if(scenario.mode()==Mode.SONIC)check(mob.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_DELAY),"Warden remains in the real native sonic-charge memory state");
                else check(mob.getTarget()==player&&((EnderManStareInvoker)mob).fsrpg$isBeingStaredBy(player),"Original Enderman stare predicate remains true with the same player target");
                long accepted=rpg().creatureMelee().acceptedAttempts();mob.doHurtTarget(player.level(),victim);
                check(rpg().creatureMelee().acceptedAttempts()==accepted&&rpg().creatureMelee().snapshot(mob)==null,"Ineligible native AI state cannot begin another melee windup: "+scenario.id());
            }
            if(scenario.mode()==Mode.PERFECT&&now==attack.contactGameTick()-1){guard(player,true);check(actor(player).inPerfectGuardWindow(),"C guard begins within two ticks of actual creature contact");}
            if(!cancellation&&!mutatedAway(scenario.mode())){mob.doHurtTarget(player.level(),victim);check(rpg().creatureMelee().acceptedAttempts()==acceptedBefore+1,"Pending attack stays one sequence under next-tick reentry: "+scenario.id());}
            return;
        }
        if(!contactChecked){
            contactChecked=true;contactHealth=victim.getHealth();caseRow.addProperty("observedContactGameTick",now);caseRow.addProperty("contactHealthLoss",healthBefore-contactHealth);
            long claims=rpg().creatureMelee().contactAttempts()-contactsBefore;
            if(cancellation){check(claims==0&&rpg().creatureMelee().snapshot(mob)==null,"Invalid attack cancels before claiming contact: "+scenario.id());near(healthBefore,victim.getHealth(),"Canceled attack causes no late health loss: "+scenario.id());}
            else {
                check(now==attack.contactGameTick()&&claims==1,"Contact is claimed exactly on its server animation tick: "+scenario.id());
                if(scenario.mode()==Mode.RANGE||scenario.mode()==Mode.WALL){near(healthBefore,victim.getHealth(),"Range or occlusion makes contact miss: "+scenario.id());check(damageEvents==0,"Missed contact never enters native incoming damage: "+scenario.id());}
                else {
                    check(damageEvents==1,"One original native incoming damage invocation at contact: "+scenario.id());
                    if(scenario.mode()==Mode.PERFECT){near(healthBefore,victim.getHealth(),"Perfect C guard cancels actual creature contact damage");check(rpg().perfectGuardStunRemaining(mob)==4,"Actual creature receives four world ticks of perfect-guard stun");check(actor(player).counterRemainingTicks()>0,"Actual creature parry grants a real left-counter opportunity");enemyWorkAtContact=enemyWorkTicks;mob.setTarget(null);screenshots.add(new Shot("perfect-creature-guard",-1));}
                    else if(scenario.mode()==Mode.GUARD){check(!actor(player).inPerfectGuardWindow(),"Ordinary held C is outside the perfect window");near(4*actor(player).guardRules().damageMultiplier(),healthBefore-victim.getHealth(),"Ordinary C applies the actual configured guard damage multiplier");}
                    else {check(victim.getHealth()<healthBefore,"Unblocked original contact removes health: "+scenario.id());if(scenario.mode()==Mode.ENCHANTED)check(victim.isOnFire(),"Original fire-aspect post-attack enchantment executes only at contact");if(scenario.mode()==Mode.POISON)check(victim.hasEffect(MobEffects.POISON),"Original CaveSpider poison executes only at contact");}
                }
            }
            victim.clearFire();victim.removeAllEffects();if(victim==player)nightVision(player);
        }
        if(scenario.mode()==Mode.PERFECT)perfectStep(player,now);
        if(now<attack.endGameTick()+2)return;
        if(scenario.mode()==Mode.PERFECT&&(!counterChecked||actor(player).currentSkill()!=null))return;
        if(scenario.mode()!=Mode.PERFECT)near(contactHealth,victim.getHealth(),"Recovery creates no second hit: "+scenario.id());
        check(rpg().creatureMelee().acceptedAttempts()==acceptedBefore+1,"Complete attack retains exactly one sequence: "+scenario.id());
        if(!cancellation&&scenario.mode()!=Mode.PERFECT)check(rpg().creatureMelee().completedAttacks()==completedBefore+1,"Native attack completes recovery: "+scenario.id());
        check(rpg().creatureMelee().snapshot(mob)==null,"No active attack remains after recovery/cancellation: "+scenario.id());finishCase(player,scenario);
    }
    private static boolean mutatedAway(Mode mode){return mode==Mode.RANGE||mode==Mode.WALL;}
    private void mutate(ServerPlayer player,Scenario scenario){
        switch(scenario.mode()){
            case RANGE->{mob.setPos(.5,80,20.5);check(!mob.isWithinMeleeAttackRange(victim),"Target is genuinely outside native reach before contact");}
            case WALL->{for(int y=80;y<=82;y++)player.level().setBlock(new BlockPos(0,y,1),Blocks.STONE.defaultBlockState(),2);check(!mob.hasLineOfSight(victim),"A real block ray occludes the pending contact");}
            case SONIC->{
                Warden warden=(Warden)mob;warden.getBrain().setMemory(MemoryModuleType.SONIC_BOOM_SOUND_DELAY,Unit.INSTANCE);
                check(warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_DELAY),"Real Warden Brain enters the native sonic-charge memory state");
                caseRow.addProperty("aiStateFixture","Native SONIC_BOOM_SOUND_DELAY memory seeded with Unit.INSTANCE; no ranged sonic damage is synthesized");
            }
            case STARE->{
                Vec3 delta=mob.getEyePosition().subtract(player.getEyePosition());float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));float pitch=(float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z)));
                player.connection.teleport(player.getX(),player.getY(),player.getZ(),yaw,pitch);
                check(mob.getTarget()==player&&((EnderManStareInvoker)mob).fsrpg$isBeingStaredBy(player),"Actual server player eye rotation activates the original Enderman stare predicate");
                caseRow.addProperty("aiStateFixture","Real target Player eye rotation; original isBeingStaredBy predicate including disguise and NeoForge anger hooks");
            }
            case FROZEN->{
                Creaking creaking=(Creaking)mob;
                creaking.getBrain().setMemory(MemoryModuleType.NEAREST_PLAYERS,List.of(player));creaking.setIsActive(true);
                check(!creaking.checkCanMove(),"Native gaze predicate sees the real server player looking at Creaking");
                caseRow.addProperty("freezeFixture","Nearest-player perception memory seeded; actual player look and native Creaking.aiStep determine canMove, not a direct synched-state override");
            }
            case DEAD->mob.setHealth(0);
            case WEAPON->mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_AXE));
            case TARGET->mob.setTarget(null);
            case REMOVED->victim.discard();
            default->{ }
        }
    }
    private void perfectStep(ServerPlayer player,long now){
        long elapsed=now-attack.contactGameTick();int remaining=rpg().perfectGuardStunRemaining(mob);
        if(elapsed<=4){JsonObject row=new JsonObject();row.addProperty("elapsed",elapsed);row.addProperty("remaining",remaining);row.addProperty("completedNativeTicks",enemyWorkTicks);stuns.add(row);check(remaining==Math.max(0,4-elapsed),"Creature stun exact remaining at world tick "+elapsed);if(elapsed>0&&elapsed<4)check(enemyWorkTicks==enemyWorkAtContact,"Creature native AI/entity tick stays paused at stun tick "+elapsed);if(elapsed==4){check(enemyWorkTicks>enemyWorkAtContact,"Creature native entity tick resumes at exact fourth stun tick");mob.setNoAi(true);}}
        if(elapsed==1&&!counterStarted){
            mob.invulnerableTime=0;mobHealthBeforeCounter=mob.getHealth();actor(player).setResources(actor(player).maxStamina(),100);action(player,"attack","left");
            check(actor(player).currentSkill()!=null,"Real left-click dispatch accepts the creature-parry counter");counterMultiplier=actor(player).attackDamageMultiplier();near(1.5,counterMultiplier,"Creature-parry counter uses the requested 1.5 multiplier");
            counterExpectedDamage=(actor(player).currentSkill().damage()+rpg().progression().effects(playerId).damageBonus())*1.5*rpg().config().current().weapons().damageMultiplier("minecraft:diamond_sword");counterStarted=true;
            float before=player.getHealth();mob.doHurtTarget(player.level(),player);near(before,player.getHealth(),"A real repeated creature attempt during stun deals no immediate damage");
        }
        if(counterStarted&&!counterChecked&&actor(player).currentSkill()==null){near(counterExpectedDamage,mobHealthBeforeCounter-mob.getHealth(),"Actual creature loses exactly the enhanced counter damage");counterChecked=true;}
    }
    private void finishCase(ServerPlayer player,Scenario scenario){
        caseRow.addProperty("preContactDamageEvents",preContactDamageEvents);caseRow.addProperty("preContactSideEffects",preContactSideEffects);caseRow.addProperty("contactClaims",rpg().creatureMelee().contactAttempts()-contactsBefore);caseRow.addProperty("damageEvents",damageEvents);caseRow.addProperty("totalHealthLoss",healthBefore-victim.getHealth());
        caseRow.addProperty("canceledAttacks",rpg().creatureMelee().canceledAttacks()-canceledBefore);caseRow.addProperty("completedAttacks",rpg().creatureMelee().completedAttacks()-completedBefore);caseRow.addProperty("outcome",scenario.mode().name());scenarios.add(caseRow);
        if(scenario.mode()==Mode.ENCHANTED)videoStopRequested=true;guard(player,false);clearWall(player);if(mob!=null&&!mob.isRemoved())mob.discard();if(victim!=player&&!victim.isRemoved())victim.discard();
        caseIndex++;prepared=false;
    }
    private static void nightVision(LivingEntity entity){entity.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));}
    private static void clearWall(ServerPlayer player){for(int y=80;y<=82;y++)player.level().setBlock(new BlockPos(0,y,1),Blocks.AIR.defaultBlockState(),2);}
    private void incoming(LivingIncomingDamageEvent event){
        if(done||!attackStarted||mob==null||event.getEntity()!=victim||event.getSource().getDirectEntity()!=mob)return;
        damageEvents++;long now=event.getEntity().level().getGameTime();if(attack!=null&&now<attack.contactGameTick())preContactDamageEvents++;
        JsonObject row=new JsonObject();row.addProperty("scenario",caseIndex<cases.length?cases[caseIndex].id():"finished");row.addProperty("gameTick",now);row.addProperty("amount",event.getAmount());row.addProperty("canceled",event.isCanceled());row.addProperty("kind","native-incoming-damage");tickRows.add(row);
    }
    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try {
            if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Server creature review failed",failure);
            game.getFramerateLimitTracker().onInputReceived();if(System.nanoTime()-started>120_000_000_000L)throw new AssertionError("Creature combat review exceeded two minutes");
            if(videoRequested&&!videoReady){Entity camera=game.level.getEntity(cameraEntityId);if(camera==null)return;game.setCameraEntity(camera);game.options.setCameraType(CameraType.FIRST_PERSON);movies.start("zombie-native-contact","Actual server-controlled creature clip, original virtual Mob.doHurtTarget, one capture per rendered frame",this::observe);videoReady=true;}
            if(videoStopRequested&&!videoStopped){movies.stop();videoStopped=true;}
            if(scriptDone&&screenshots.isEmpty()&&pendingScreenshots.get()==0&&videoStopped&&movies.finish()){
                check(clampChecked&&levels.size()==4,"All four actual experience levels, no-refill upgrade and downgrade clamp passed");check(scenarios.size()==cases.length,"All required native creature scenarios completed");
                for(int level:experienceLevels)check(Files.isRegularFile(root.resolve("screenshots/level-"+level+"-actual-hud.png")),"Actual HUD screenshot file exists for level "+level);
                check(packets.stream().anyMatch(p->ClientState.number(p,"counterRemainingTicks",0)>0),"Real creature-parry counter-window packet reached client presentation state");validateRender();finish(null);
            }
        }catch(Throwable error){finish(error);}
    }
    private JsonObject observe(){JsonObject sample=lastRendered.deepCopy();sample.addProperty("observedServerGameTick",observedServerTick);sample.addProperty("actualServerContactGameTick",videoContactTick);if(sample.has("poseHash"))sample.add("rig",sample.get("poseHash"));return sample;}
    private void render(RenderFrameEvent.Post event){
        if(done||!initialized)return;nativeFrames++;
        try {
            movies.frame(event);Shot shot;while((shot=screenshots.poll())!=null){Shot one=shot;pendingScreenshots.incrementAndGet();Screenshot.grab(root.toFile(),one.name()+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedScreenshots.incrementAndGet();pendingScreenshots.decrementAndGet();if(one.levelIndex()>=0)levelScreenshotAck.accumulateAndGet(one.levelIndex(),Math::max);});}
        }catch(Throwable error){failure=error;}
    }
    private JsonObject renderEvidence(){
        JsonObject report=new JsonObject();int windup=0,contact=0,recovery=0,invalid=0;Set<String> hashes=new HashSet<>();
        for(JsonObject row:rendered){if(!ClientState.text(row,"source","").equals("SERVER"))continue;String phase=ClientState.text(row,"phase","");double seconds=ClientState.number(row,"sampleSeconds",Double.NaN);
            if(!Double.isFinite(seconds)||ClientState.number(row,"sequence",0)<=0)invalid++;if(phase.equals("WINDUP"))windup++;if(phase.equals("RECOVERY"))recovery++;if(phase.equals("CONTACT"))contact++;
            if(row.has("poseHash"))hashes.add(row.get("poseHash").getAsString());}
        JsonObject capture=movies.summary();JsonArray clips=capture.getAsJsonArray("clips");JsonObject clip=clips.isEmpty()?new JsonObject():clips.get(0).getAsJsonObject();
        report.addProperty("recording","render-capture.json");report.addProperty("source","SERVER");report.addProperty("windupFrames",windup);report.addProperty("contactFrames",contact);report.addProperty("recoveryFrames",recovery);report.addProperty("invalidRenderedSamples",invalid);report.addProperty("distinctPoseHashes",hashes.size());
        report.addProperty("nativeSourceFps",ClientState.number(clip,"sourceFps",0));report.addProperty("actualRenderFrames",ClientState.number(clip,"capturedFrames",0));report.addProperty("distinctSamplesWithinGameTick",ClientState.number(clip,"distinctSamplesWithinGameTick",0));report.addProperty("changedRigPairs",ClientState.number(clip,"changedRigPairs",0));report.addProperty("serverContactGameTick",videoContactTick);return report;
    }
    private void validateRender(){JsonObject r=renderEvidence();check(r.get("nativeSourceFps").getAsDouble()>=30,"Creature recording is genuine native >=30 Hz");check(r.get("windupFrames").getAsInt()>0&&r.get("contactFrames").getAsInt()>0&&r.get("recoveryFrames").getAsInt()>0,"Actual renderer includes windup, contact and recovery on the server payload clock");check(r.get("invalidRenderedSamples").getAsInt()==0&&r.get("distinctPoseHashes").getAsInt()>10&&r.get("changedRigPairs").getAsInt()>10,"Actual submitted creature FK changes across native render frames");check(r.get("distinctSamplesWithinGameTick").getAsInt()>0,"Recording includes distinct sub-tick source frames");}
    private void finish(Throwable error){
        if(done)return;done=true;movies.stop();try{snapshotObserver.close();renderObserver.close();}catch(Exception closeError){if(error==null)error=closeError;}
        try {
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.creatureCombatWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("serverTicks",serverTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedScreenshots.get());
            report.addProperty("levelNoRefillAndClampPassed",clampChecked);report.addProperty("counterMultiplier",counterMultiplier);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("levelScaling",levels);report.add("scenarios",scenarios);report.add("serverTickTrace",tickRows);report.add("stunTicks",stuns);report.add("renderEvidence",renderEvidence());
            report.addProperty("scope","Vanilla melee with supplied profiles. Projectile/spell/dragon collision and Creeper explosions retain original behavior; no synthetic hurtServer replacement is used.");RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("creature-combat-review.json"),RpgConfig.JSON.toJson(report));System.out.println("CREATURE COMBAT REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reportError){reportError.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);
        if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
}

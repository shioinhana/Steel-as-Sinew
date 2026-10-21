package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.progression.ProgressionService;
import org.lwjgl.glfw.GLFW;

/** Protocol-11 copied-world acceptance. Damage, defense, packets and camera bobs remain native. */
final class DefenseFlowReview {
    private enum Kind { EXCHANGE, CHAIN, RESET, BLOCK_CAMERA }
    private record Scenario(String id,Kind kind,int gap,double playerDamage,double enemyDamage) {}
    private static final List<Scenario> CASES=List.of(
            new Scenario("fraction-open-mutual-block-before-original-contact",Kind.EXCHANGE,0,9,6),
            new Scenario("fraction-open-player-advantage-before-original-contact",Kind.EXCHANGE,0,10,6),
            new Scenario("fraction-open-npc-advantage-before-original-contact",Kind.EXCHANGE,0,6,10),
            new Scenario("fraction-original-contact-inclusive",Kind.EXCHANGE,999,9,6),
            new Scenario("fraction-before-open-new-active-rejected",Kind.EXCHANGE,-1,10,6),
            new Scenario("fraction-old-active-not-retried-after-window-opens",Kind.EXCHANGE,-1,10,6),
            new Scenario("fraction-middle-player-advantage",Kind.EXCHANGE,2,10,6),
            new Scenario("one-held-c-three-perfect-fourth-pressure",Kind.CHAIN,0,0,6),
            new Scenario("release-new-c-reopens-seven-tick-window",Kind.RESET,0,0,6),
            new Scenario("ordinary-block-native-hurt-only-camera-suppressed",Kind.BLOCK_CAMERA,0,0,6));
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray(),trace=new JsonArray(),damageEvents=new JsonArray(),fixtures=new JsonArray();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private final List<Mob> mobs=new ArrayList<>();
    private volatile Throwable failure;
    private volatile boolean ready,videoReady,scriptDone,done,prewarmAck,desiredGuard;
    private volatile String visibleCase="setup";
    private volatile JsonObject serverObservation=new JsonObject(),clientObservation=new JsonObject();
    private volatile int lookAcknowledged=-1,prewarmStage;
    private volatile UUID warmZombie;
    private volatile long zombieRenderedTick=-1;
    private UUID playerId;
    private Mob enemy;
    private Path skillsFile;
    private byte[] originalSkills;
    private AutoCloseable warmObserver;
    private KeyMapping guardKey;
    private boolean initialized,prepared,begun,playerStarted,matched,restored,videoStopped,sawRecovery;
    private boolean sawPlayerCounter,sawNpcCounter,sawBlockedHurt,sawUnblockedHurt,blockKnockback;
    private int warmup,warmTicks,index,chainStep,nativeCalls,previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private long started,nativeFrames,serverTicks,lastWarmTick=-1,preparedAt,startedAt,finishAt=-1;
    private long nativeContact,playerContact,plannedPlayerStart,windowOpen,clashesBefore,balancedBefore,advantageBefore;
    private long firstPlayerDamage=-1,firstEnemyDamage=-1,blockedPacketsBefore,blockedSuppressedBefore,blockedBobsBefore,unblockedPacketsBefore,unblockedBobsBefore;
    private double nativeRaw,blockedPost,unblockedPost,blockedRaw,unblockedRaw;
    private float playerHealth,enemyHealth,healthBeforeCall;
    private JsonObject row;

    DefenseFlowReview(){
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,true,this::incoming);NeoForge.EVENT_BUS.addListener(this::damaged);NeoForge.EVENT_BUS.addListener(this::knockback);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private void action(ServerPlayer p,String name,String argument){rpg().action(p,name,argument);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.add(message);System.out.println("DEFENSE FLOW PASS "+message);}
    private void near(double expected,double actual,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<.004,message+" expected="+expected+" actual="+actual);}
    private void guard(ServerPlayer p,boolean held){desiredGuard=held;action(p,"guard",Boolean.toString(held));}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Native defense review belongs to DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();String name=System.getProperty("fsrpg.defenseFlowWorld","");
        check(name.equals("FSRPG Defense Flow Review 20261021 01")&&name.equals(world.getFileName().toString())&&Files.isRegularFile(world.resolve(".fsrpg-defense-flow-test-world")),"Only the exact named and marked copied defense world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.mouseHandler.releaseMouse();game.getTutorial().setStep(TutorialSteps.NONE);
        for(var key:game.options.keyMappings)if(key.getName().equals("key.fsrpg.guard"))guardKey=key;check(guardKey!=null,"Real client C mapping is available");
        var b=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,b.get("x").getAsInt()+35,b.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 防御窗口与格挡链验收 · DISPLAY5");game.resizeDisplay();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{try{
            ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(48),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
            p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(false);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.setExperienceLevels(0);p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);p.setHealth(200);p.getFoodData().setFoodLevel(18);p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            p.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);p.level().setDayTime(18000);p.level().setWeatherParameters(6000,0,false,false);p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));
            for(int x=-8;x<=8;x++)for(int z=-6;z<=12;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            fixtureProgress(p);skillsFile=rpg().config().directory().resolve("skills.json");originalSkills=Files.readAllBytes(skillsFile);Files.write(root.resolve("fixture-original-skills.json"),originalSkills);
            check(rpg().config().current().combat().weaponClash().contactWindowTicks()==3,"Installed clash window includes exactly three ticks");near(3,rpg().config().current().combat().weaponClash().damageTolerance(),"Installed damage tolerance remains three");check(rpg().config().current().combat().perfectGuardTicks()==7,"Installed global perfect guard window is seven ticks");
            var cue=rpg().creatureMelee().guardCueSettings();check(cue.enabled()&&cue.timingMode().equals("windup_fraction"),"Installed creature guard cue uses the requested animation fractions");near(.2,cue.flashAtFraction(),"Native cue flashes at one fifth of authored windup");near(.6,cue.windowOpenFraction(),"Native opportunity opens at three fifths of authored windup");ready=true;
        }catch(Throwable error){failure=error;}});
    }
    private void fixtureProgress(ServerPlayer p){
        var service=rpg().progression();UUID id=p.getUUID();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(id,0,0,day);service.onSleep(id);
        JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(id.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}
        for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);
        one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();learned.add("rapier");one.add("learnedNodes",learned);service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
    }
    private int windup(Scenario s){return 0;}
    private void fixtureSkill(Scenario s)throws Exception{
        double multiplier=rpg().config().current().weapons().damageMultiplier("minecraft:iron_sword");check(multiplier>0,"Real fixture sword has a positive configured multiplier");JsonObject edit=new JsonObject();edit.addProperty("damage",s.playerDamage()/multiplier);edit.addProperty("windupTicks",windup(s));edit.addProperty("activeTicks",s.id().contains("old-active")?10:1);edit.addProperty("recoveryTicks",12);edit.addProperty("cooldownTicks",0);edit.addProperty("knockback",0);rpg().config().editSkill("sword_left",edit);JsonObject value=new JsonObject();value.addProperty("scenario",s.id());value.add("skillChanges",edit);value.addProperty("itemMultiplier",multiplier);fixtures.add(value);
    }
    private void equip(ServerPlayer p){
        actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);action(p,"status","");
        check(actor(p).style()!=null&&actor(p).style().id().equals("rapier"),"Actual equipment selects the rapier stance");check(actor(p).guardRules().perfectGuardTicks()==7,"Installed rapier perfect guard window is seven ticks");check(actor(p).guardRules().parryStunTicks()==4,"Ordinary C perfect guard retains four attacker-stun ticks");if(actor(p).phase()==CombatPhase.SHEATHED)action(p,"draw","");actor(p).setResources(actor(p).maxStamina(),100);
        p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.invulnerableTime=0;p.hurtTime=0;p.setHealth(200);p.setInvulnerable(false);p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
    }
    private Mob spawn(ServerPlayer p,boolean hog,double damage,double x,double z){
        Mob mob=(Mob)(hog?EntityType.HOGLIN:EntityType.DROWNED).create(p.level(),EntitySpawnReason.COMMAND);if(mob==null)throw new AssertionError("Cannot spawn actual native fixture mob");mob.setPos(x,80,z);mob.setYRot(180);mob.yBodyRot=mob.yBodyRotO=180;mob.setNoGravity(true);mob.setNoAi(true);mob.setPersistenceRequired();
        for(var slot:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))mob.setItemSlot(slot,ItemStack.EMPTY);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);mob.setHealth(200);mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);mob.getAttribute(Attributes.ARMOR).setBaseValue(0);mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);p.level().addFreshEntity(mob);mob.setTarget(p);mobs.add(mob);return mob;
    }
    private void cleanMobs(){for(Mob mob:mobs){rpg().creatureMelee().cancel(mob);mob.discard();}mobs.clear();enemy=null;}
    private void prepare(ServerPlayer p,Scenario s)throws Exception{
        guard(p,false);cleanMobs();fixtureSkill(s);equip(p);enemy=spawn(p,s.kind()!=Kind.EXCHANGE,s.enemyDamage(),.5,1.7);
        if(s.kind()==Kind.BLOCK_CAMERA)p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0);
        visibleCase=s.id();row=new JsonObject();row.addProperty("id",s.id());row.addProperty("requestedActiveOffsetFromWindowOpenTicks",s.gap());row.addProperty("playerFullDamage",s.playerDamage());row.addProperty("enemyAttributeDamage",s.enemyDamage());row.add("actualAttackIntents",new JsonArray());row.add("guardContacts",new JsonArray());
        clashesBefore=rpg().meleeClashes();balancedBefore=rpg().balancedClashes();advantageBefore=rpg().advantageClashes();prepared=true;begun=playerStarted=matched=sawRecovery=sawPlayerCounter=sawNpcCounter=sawBlockedHurt=sawUnblockedHurt=blockKnockback=false;chainStep=0;nativeCalls=0;nativeRaw=blockedPost=unblockedPost=blockedRaw=unblockedRaw=0;nativeContact=playerContact=plannedPlayerStart=-1;firstPlayerDamage=firstEnemyDamage=-1;finishAt=-1;preparedAt=p.level().getGameTime();playerHealth=p.getHealth();enemyHealth=enemy.getHealth();synchronizeLook();
    }
    private void synchronizeLook(){int scene=index;lookAcknowledged=-1;Minecraft.getInstance().execute(()->{try{Minecraft g=Minecraft.getInstance();CombatCamera.reset();g.setCameraEntity(g.player);g.options.setCameraType(CameraType.THIRD_PERSON_BACK);g.player.setYRot(0);g.player.setXRot(0);g.player.setYHeadRot(0);g.player.yBodyRot=g.player.yBodyRotO=0;g.player.yRotO=g.player.xRotO=0;CombatCamera.prepareRapierAttack();if(g.getConnection()!=null)g.getConnection().send(new ServerboundMovePlayerPacket.Rot(0,0,g.player.onGround(),g.player.horizontalCollision));lookAcknowledged=scene;}catch(Throwable error){failure=error;}});}
    private boolean stable(ServerPlayer p,long now){JsonObject client=clientObservation;boolean aligned=lookAcknowledged==index&&ClientState.number(client,"scenarioIndex",-1)==index&&Math.abs(AttackAim.wrap(p.getYRot()))<.1&&Math.abs(p.getXRot())<.1&&Math.abs(AttackAim.wrap(ClientState.number(client,"yaw",360)))<.1&&Math.abs(ClientState.number(client,"pitch",90))<.1;if(now-preparedAt>=16&&aligned)return true;if(now-preparedAt>100)throw new AssertionError("Fixture look did not stabilize: "+geometry(p)+" client="+client);return false;}
    private void left(ServerPlayer p){
        Vec3 delta=enemy.position().subtract(p.position());AttackAim aim=AttackAim.at(RapierTargeting.direction(delta.x,0,delta.z,p.getYRot()).yaw(),0);JsonObject input=new JsonObject();input.addProperty("button","left");input.add("aim",RpgConfig.JSON.toJsonTree(aim));long before=actor(p).snapshot().swingSequence();action(p,"targetAttack",input.toString());check(actor(p).currentSkill()!=null&&actor(p).currentSkill().id().equals("sword_left")&&actor(p).snapshot().swingSequence()==before+1,"Real left intent starts one physical sword attack: "+visibleCase);JsonObject value=geometry(p);value.addProperty("worldGameTick",p.level().getGameTime());value.add("requestedAim",RpgConfig.JSON.toJsonTree(aim));value.add("acceptedActor",RpgConfig.JSON.toJsonTree(actor(p).snapshot()));row.getAsJsonArray("actualAttackIntents").add(value);playerStarted=true;
    }
    private void begin(ServerPlayer p,Scenario s){
        begun=true;startedAt=p.level().getGameTime();row.add("stableStartGeometry",geometry(p));row.add("stableClientLook",clientObservation.deepCopy());row.addProperty("settledTicksBeforeBegin",startedAt-preparedAt);
        if(s.kind()!=Kind.EXCHANGE){guard(p,true);check(actor(p).phase()==CombatPhase.GUARDING,"One real C press begins guarding: "+s.id());if(s.kind()==Kind.BLOCK_CAMERA){blockedPacketsBefore=(long)ClientState.number(clientObservation,"damagePackets",0);blockedSuppressedBefore=(long)ClientState.number(clientObservation,"suppressedPackets",0);blockedBobsBefore=(long)ClientState.number(clientObservation,"skippedBobs",0);}return;}
        check(!enemy.doHurtTarget(p.level(),p),"Actual drowned enters its original authored animation windup: "+s.id());var attack=rpg().creatureMelee().snapshot(enemy);check(attack!=null,"Native drowned has its own contact timeline");var cue=rpg().creatureMelee().guardCueSnapshot(enemy);check(cue!=null,"Original native attack owns its authoritative cue clock");nativeContact=attack.contactGameTick();windowOpen=cue.openGameTick();long duration=nativeContact-attack.startedGameTick();check(duration==16,"Actual drowned keeps its original sixteen-tick authored contact");check(cue.flashGameTick()==attack.startedGameTick()+(long)Math.floor(duration*.2)&&windowOpen==attack.startedGameTick()+(long)Math.ceil(duration*.6)&&cue.closeGameTick()==attack.startedGameTick()+(long)Math.floor(duration*.8),"Real native flash/open/close ticks follow one-fifth, three-fifths and four-fifths; clash keeps inclusive contact");playerContact=s.gap()==999?nativeContact:windowOpen+s.gap();plannedPlayerStart=playerContact-windup(s)-1;row.add("nativeAttack",RpgConfig.JSON.toJsonTree(attack));row.add("nativeGuardCue",RpgConfig.JSON.toJsonTree(cue));row.addProperty("expectedPlayerFirstActiveGameTick",playerContact);row.addProperty("originalNativeContactGameTick",nativeContact);row.addProperty("activeBeforeOriginalContactTicks",nativeContact-playerContact);check(plannedPlayerStart>=startedAt,"Physical player attack is scheduled before its intended first ACTIVE");if(plannedPlayerStart==startedAt)left(p);
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||!videoReady||done||scriptDone||failure!=null)return;
        try{ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;serverTicks++;long now=p.level().getGameTime();if(index>=CASES.size()){guard(p,false);cleanMobs();restoreSkills();scriptDone=true;return;}Scenario s=CASES.get(index);if(!prepared){prepare(p,s);return;}if(!begun){if(!stable(p,now)||actor(p).phase()!=CombatPhase.READY){observe(p);return;}begin(p,s);observe(p);return;}
            if(s.kind()==Kind.EXCHANGE)exchangeStep(p,s,now);else if(s.kind()==Kind.BLOCK_CAMERA)blockStep(p,now);else chainStep(p,s,now);
            if(prepared)observe(p);check(now-preparedAt<180,"Actual defense scene stays within nine seconds: "+s.id());
        }catch(Throwable error){failure=error;}
    }
    private void exchangeStep(ServerPlayer p,Scenario s,long now){
        if(!playerStarted&&now==plannedPlayerStart)left(p);
        if(s.id().contains("old-active")&&now==windowOpen){check(actor(p).phase()==CombatPhase.ACTIVE,"Previously active swing really remains ACTIVE when the opportunity later opens");check(rpg().meleeClashes()==clashesBefore,"An old ACTIVE does not gain a second activation check at window open");sawRecovery=true;}
        sawPlayerCounter|=rpg().perfectGuardCounterRemaining(p)>0;sawNpcCounter|=rpg().perfectGuardCounterRemaining(enemy)>0;
        if(!matched&&rpg().meleeClashes()>clashesBefore){matched=true;check(now==playerContact,"NPC window clash settles when the player first enters ACTIVE: "+s.id());if(s.gap()==0)check(nativeContact-now>3,"Window-open clash occurs more than three ticks before the original native contact");check(actor(p).phase()==CombatPhase.READY&&actor(p).currentSkill()==null,"Real clash clears the player's attack recovery immediately");check(rpg().creatureMelee().snapshot(enemy)==null,"Real clash removes the NPC's remaining attack recovery");check(rpg().perfectGuardStunRemaining(p)==0&&rpg().perfectGuardStunRemaining(enemy)==0,"Clash adds no winner or loser hitstun");row.addProperty("clashGameTick",now);row.add("actorAtClash",RpgConfig.JSON.toJsonTree(actor(p).snapshot()));row.addProperty("playerCounterAtClash",rpg().perfectGuardCounterRemaining(p));row.addProperty("npcCounterAtClash",rpg().perfectGuardCounterRemaining(enemy));shots.add(s.id()+"-contact");}
        if(now>=Math.max(nativeContact,playerContact)+5){if(finishAt<0){verifyExchange(p,s);finishAt=now+2;shots.add(s.id()+"-result");}else if(now>=finishAt)complete();}
    }
    private void verifyExchange(ServerPlayer p,Scenario s){
        boolean clash=s.gap()>=0;double loss=playerHealth-p.getHealth(),enemyLoss=enemyHealth-enemy.getHealth();near(s.enemyDamage(),nativeRaw,"Comparison observes the actual native incoming attack: "+s.id());check(nativeCalls==1,"Original NPC damage dispatch occurs exactly once, including after its canceled future contact: "+s.id());check(rpg().meleeClashes()-clashesBefore==(clash?1:0),"Only a first ACTIVE within this native animation window grants clash: "+s.id());
        if(clash){double difference=s.playerDamage()-s.enemyDamage();near(Math.max(0,-difference-3),loss,"Only native advantage beyond tolerance reaches player health: "+s.id());near(Math.max(0,difference-3),enemyLoss,"Only player advantage beyond tolerance reaches NPC health: "+s.id());if(Math.abs(difference)<=3)check(rpg().balancedClashes()==balancedBefore+1&&!sawPlayerCounter&&!sawNpcCounter,"Exactly three difference produces mutual ordinary blocks");else check(rpg().advantageClashes()==advantageBefore+1&&(difference>0?sawPlayerCounter:sawNpcCounter),"Advantage preserves the winner's real perfect-counter opportunity");}
        else{near(s.enemyDamage(),loss,"ACTIVE before the window keeps the original incoming contact damage");near(s.playerDamage(),enemyLoss,"Earlier unmatched outgoing attack is committed exactly once");check(firstPlayerDamage==nativeContact,"Unanswered authored attack still applies damage on its original contact tick without delay");if(s.id().contains("old-active"))check(sawRecovery,"Long-lived old ACTIVE was observed crossing into the window without a new authorization");}
        row.addProperty("actualPlayerHealthLoss",loss);row.addProperty("actualEnemyHealthLoss",enemyLoss);row.addProperty("firstPlayerDamageGameTick",firstPlayerDamage);row.addProperty("firstEnemyDamageGameTick",firstEnemyDamage);row.addProperty("nativeIncomingRawDamage",nativeRaw);row.addProperty("nativeDamageInvocations",nativeCalls);row.addProperty("meleeClashes",rpg().meleeClashes()-clashesBefore);
    }
    private void nativeGuardContact(ServerPlayer p,int mobIndex,boolean perfect){
        if(mobIndex>0){rpg().creatureMelee().cancel(enemy);enemy.discard();enemy=spawn(p,true,CASES.get(index).enemyDamage(),.5,1.7);}
        healthBeforeCall=p.getHealth();double stamina=actor(p).stamina();enemy.doHurtTarget(p.level(),p);JsonObject contact=new JsonObject();contact.addProperty("worldGameTick",p.level().getGameTime());contact.addProperty("elapsedSincePhysicalPress",p.level().getGameTime()-startedAt);contact.addProperty("mobUUID",enemy.getUUID().toString());contact.addProperty("originalIncomingRawDamage",nativeRaw);contact.addProperty("healthLoss",healthBeforeCall-p.getHealth());contact.addProperty("staminaCost",stamina-actor(p).stamina());contact.addProperty("heldPerfectGuards",actor(p).heldPerfectGuards());contact.addProperty("attackerStunRemaining",rpg().perfectGuardStunRemaining(enemy));contact.add("actualActor",RpgConfig.JSON.toJsonTree(actor(p).snapshot()));row.getAsJsonArray("guardContacts").add(contact);
        check(nativeRaw>0,"Original unprofiled Hoglin produces real positive damage before defense");if(perfect){near(healthBeforeCall,p.getHealth(),"Held C perfect guard cancels the actual health damage");check(rpg().perfectGuardStunRemaining(enemy)==4,"Pure C success retains exactly four ticks of native attacker stun");check(rpg().perfectGuardCounterRemaining(p)==4,"Pure C success grants the actual four-tick counter opportunity");}else{check(p.getHealth()<healthBeforeCall,"Fourth held C contact becomes ordinary block or pressure damage");check(rpg().perfectGuardStunRemaining(enemy)==0,"Exhausted fourth contact adds no perfect-guard attacker stun");}
    }
    private void chainStep(ServerPlayer p,Scenario s,long now){
        long elapsed=now-startedAt;long[] contacts={1,7,13,14};if(chainStep<4&&elapsed==contacts[chainStep]){nativeGuardContact(p,chainStep,chainStep<3);chainStep++;if(chainStep<=3)check(actor(p).heldPerfectGuards()==chainStep,"One held physical C records exactly "+chainStep+" perfect guards");shots.add(s.id()+"-contact-"+chainStep);}
        if(s.kind()==Kind.CHAIN&&elapsed>=20){check(chainStep==4&&nativeCalls==4,"Three automatic windows and the fourth pressure use four real native invocations");row.addProperty("physicalFreshPresses",1);row.addProperty("actualPerfectGuards",3);complete();return;}
        if(s.kind()==Kind.RESET){if(elapsed==16){guard(p,false);check(actor(p).heldPerfectGuards()==0,"Physical C release resets the exhausted three-success chain");}
            if(elapsed==34){check(actor(p).phase()==CombatPhase.READY,"Ordinary guard-break recovery finishes before a genuinely new press");guard(p,true);check(actor(p).heldPerfectGuards()==0,"A genuinely new C press starts a fresh success allowance");}
            if(elapsed==40){check(actor(p).phase()==CombatPhase.GUARDING,"New C remains held six ticks after its press");nativeGuardContact(p,4,true);check(actor(p).heldPerfectGuards()==1,"Seventh timing tick after the new press is still precise");row.addProperty("newPressGameTick",startedAt+34);row.addProperty("freshContactGameTick",now);shots.add(s.id()+"-new-window-contact");}
            if(elapsed>=46){check(nativeCalls==5,"Release and new press are tested through a fifth actual NPC damage invocation");row.addProperty("physicalFreshPresses",2);row.addProperty("actualPerfectGuards",4);complete();}}
    }
    private void blockStep(ServerPlayer p,long now){
        long elapsed=now-startedAt;JsonObject client=clientObservation;long packets=(long)ClientState.number(client,"damagePackets",0),suppressed=(long)ClientState.number(client,"suppressedPackets",0),bobs=(long)ClientState.number(client,"skippedBobs",0);
        if(elapsed==8){check(!actor(p).inPerfectGuardWindow(),"Ordinary block arrives after the full seven-tick precision window");double health=p.getHealth();enemy.doHurtTarget(p.level(),p);blockedRaw=nativeRaw;blockedPost=health-p.getHealth();near(blockedRaw*actor(p).guardRules().damageMultiplier(),blockedPost,"Ordinary block keeps its configured real health damage");check(blockedPost>0,"Ordinary block really applies nonzero health damage");check(p.hurtTime>0,"Ordinary block keeps the native server hurt timer");blockKnockback|=p.getDeltaMovement().lengthSqr()>1e-7;row.add("blockedServerVelocity",RpgConfig.JSON.toJsonTree(new double[]{p.getDeltaMovement().x,p.getDeltaMovement().y,p.getDeltaMovement().z}));shots.add("ordinary-block-native-contact");}
        if(elapsed>8&&elapsed<24&&packets>blockedPacketsBefore&&suppressed>blockedSuppressedBefore&&bobs>blockedBobsBefore&&ClientState.bool(client,"suppressed",false)&&ClientState.number(client,"hurtTime",0)>0)sawBlockedHurt=true;
        if(elapsed==24){check(sawBlockedHurt,"A real guarded damage packet preserves client hurtTime while actual bobHurt calls are skipped");check(blockKnockback,"Ordinary block preserves actual native knockback");guard(p,false);check(actor(p).phase()==CombatPhase.READY,"C release ends ordinary guard before the same-source comparison");p.invulnerableTime=0;unblockedPacketsBefore=packets;unblockedBobsBefore=bobs;double health=p.getHealth();enemy.doHurtTarget(p.level(),p);unblockedRaw=nativeRaw;unblockedPost=health-p.getHealth();near(unblockedRaw,unblockedPost,"Same native source's unguarded hit keeps its full original damage");check(p.hurtTime>0,"Same-source unguarded hit retains native hurtTime");shots.add("ordinary-source-unguarded-contact");}
        if(elapsed>24&&packets>unblockedPacketsBefore&&!ClientState.bool(client,"suppressed",true)&&ClientState.number(client,"hurtTime",0)>0)sawUnblockedHurt=true;
        if(elapsed>=35){check(nativeCalls==2,"Guarded and unguarded comparison dispatch exactly two original attacks");check(sawUnblockedHurt,"The next same-source unguarded damage packet resets camera suppression during native hurtTime");check(bobs==unblockedBobsBefore,"Actual camera bob suppression does not run for the later ordinary hit");check(suppressed==blockedSuppressedBefore+1,"Only the guarded native damage packet was tagged for suppression");row.addProperty("blockedRawDamage",blockedRaw);row.addProperty("blockedHealthLoss",blockedPost);row.addProperty("unblockedRawDamage",unblockedRaw);row.addProperty("unblockedHealthLoss",unblockedPost);row.addProperty("sawNativeKnockback",blockKnockback);row.addProperty("guardedHurtSuppressed",sawBlockedHurt);row.addProperty("followingSameSourceHurtUnsuppressed",sawUnblockedHurt);row.add("finalNativePresentation",client.deepCopy());shots.add("ordinary-block-camera-restored-result");complete();}
    }
    private void complete(){row.addProperty("passed",true);scenarios.add(row);shots.add(visibleCase+"-result");index++;prepared=false;}
    private JsonObject geometry(ServerPlayer p){JsonObject value=new JsonObject();value.add("playerPosition",RpgConfig.JSON.toJsonTree(new double[]{p.getX(),p.getY(),p.getZ()}));value.addProperty("yaw",p.getYRot());value.addProperty("pitch",p.getXRot());if(actor(p).attackAim()!=null)value.add("frozenAttackAim",RpgConfig.JSON.toJsonTree(actor(p).attackAim()));if(enemy!=null){value.addProperty("targetUUID",enemy.getUUID().toString());value.add("targetPosition",RpgConfig.JSON.toJsonTree(new double[]{enemy.getX(),enemy.getY(),enemy.getZ()}));value.addProperty("lineOfSight",p.hasLineOfSight(enemy));value.addProperty("eyeToTargetCenterDistance",p.getEyePosition().distanceTo(enemy.getBoundingBox().getCenter()));if(actor(p).currentSkill()!=null){value.addProperty("skillReach",actor(p).currentSkill().reach());value.addProperty("skillArcDegrees",actor(p).currentSkill().arcDegrees());}}return value;}
    private void observe(ServerPlayer p){JsonObject value=RpgConfig.JSON.toJsonTree(actor(p).snapshot()).getAsJsonObject();value.addProperty("scenario",visibleCase);value.addProperty("worldGameTick",p.level().getGameTime());value.addProperty("playerHealth",p.getHealth());value.addProperty("enemyHealth",enemy==null?0:enemy.getHealth());value.addProperty("heldPerfectGuards",actor(p).heldPerfectGuards());value.addProperty("playerStunRemaining",rpg().perfectGuardStunRemaining(p));value.addProperty("npcStunRemaining",enemy==null?0:rpg().perfectGuardStunRemaining(enemy));value.addProperty("playerCounterRemaining",rpg().perfectGuardCounterRemaining(p));value.addProperty("npcCounterRemaining",enemy==null?0:rpg().perfectGuardCounterRemaining(enemy));value.add("geometry",geometry(p));value.add("client",clientObservation.deepCopy());serverObservation=value;trace.add(value.deepCopy());}
    private void incoming(LivingIncomingDamageEvent event){if(!initialized||done||row==null||!event.getEntity().getUUID().equals(playerId)||!mobs.contains(event.getSource().getEntity()))return;nativeCalls++;nativeRaw=event.getAmount();JsonObject entry=new JsonObject();entry.addProperty("scenario",visibleCase);entry.addProperty("kind","original-incoming-before-defense");entry.addProperty("worldGameTick",event.getEntity().level().getGameTime());entry.addProperty("amount",event.getAmount());entry.addProperty("sourceUUID",event.getSource().getEntity().getUUID().toString());Long original=rpg().creatureMelee().invocationContactTick(event.getSource().getEntity(),event.getSource().getDirectEntity(),event.getEntity());if(original!=null)entry.addProperty("actualOriginalContactGameTick",original);entry.addProperty("invocationDeferred",rpg().creatureMelee().invocationDeferred());entry.addProperty("invocationWindowClash",rpg().creatureMelee().invocationWindowClash());damageEvents.add(entry);}
    private void damaged(LivingDamageEvent.Post event){if(!initialized||done||row==null||event.getNewDamage()<=0)return;boolean player=event.getEntity().getUUID().equals(playerId);if(!player&&!mobs.contains(event.getEntity()))return;long now=event.getEntity().level().getGameTime();if(player&&firstPlayerDamage<0)firstPlayerDamage=now;if(!player&&firstEnemyDamage<0)firstEnemyDamage=now;JsonObject entry=new JsonObject();entry.addProperty("scenario",visibleCase);entry.addProperty("kind","actual-native-post-damage");entry.addProperty("worldGameTick",now);entry.addProperty("victimUUID",event.getEntity().getUUID().toString());entry.addProperty("newDamage",event.getNewDamage());damageEvents.add(entry);}
    private void knockback(LivingKnockBackEvent event){if(initialized&&!done&&row!=null&&index<CASES.size()&&CASES.get(index).kind()==Kind.BLOCK_CAMERA&&event.getEntity().getUUID().equals(playerId)&&event.getStrength()>0){blockKnockback=true;JsonObject value=new JsonObject();value.addProperty("scenario",visibleCase);value.addProperty("kind","actual-native-knockback");value.addProperty("worldGameTick",event.getEntity().level().getGameTime());value.addProperty("strength",event.getStrength());damageEvents.add(value);}}
    private void restoreSkills()throws Exception{if(!restored&&originalSkills!=null){Files.write(skillsFile,originalSkills);restored=true;}}
    private boolean prewarm(Minecraft game)throws Exception{
        if(prewarmStage==0){prewarmStage=1;prewarmAck=false;visibleCase="prewarm-real-rapier-drowned-hoglin";warmObserver=CreatureArtRenderer.observeRenderedAttack(sample->{if(warmZombie!=null&&game.level!=null&&warmZombie.toString().equals(ClientState.text(sample,"entityUUID","")))zombieRenderedTick=game.level.getGameTime();});game.getSingleplayerServer().execute(()->{try{ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);fixtureSkill(CASES.getFirst());equip(p);enemy=spawn(p,false,6,.5,1.7);warmZombie=enemy.getUUID();spawn(p,true,6,-1.4,2.2);prewarmAck=true;}catch(Throwable error){failure=error;}});return false;}
        if(prewarmStage==1&&prewarmAck&&warmTicks>=55){check(warmTicks>=55,"Native rapier and exact drowned rig render for at least fifty-five distinct ticks before recording");if(warmObserver!=null){warmObserver.close();warmObserver=null;}prewarmStage=2;return true;}return prewarmStage==2;
    }
    private void prewarmFrame(){Minecraft game=Minecraft.getInstance();if(!prewarmAck||prewarmStage!=1||game.level==null||game.player==null)return;long tick=game.level.getGameTime();if(tick==lastWarmTick||zombieRenderedTick!=tick||!ClientState.drawn()||!game.player.getMainHandItem().is(Items.IRON_SWORD)||!"rapier".equals(ClientState.text(ClientState.snapshot(),"weaponStyle","")))return;try{JsonObject avatar=RenderObservations.submittedSampler(game.player).get();if(!avatar.has("rig")||avatar.getAsJsonObject("rig").isEmpty())return;}catch(IllegalStateException pendingSubmission){return;}lastWarmTick=tick;warmTicks++;}
    private JsonObject nativeClient(){Minecraft g=Minecraft.getInstance();JsonObject value=new JsonObject();value.addProperty("scenarioIndex",lookAcknowledged);value.addProperty("yaw",g.player.getYRot());value.addProperty("pitch",g.player.getXRot());value.addProperty("hurtTime",g.player.hurtTime);value.addProperty("health",g.player.getHealth());value.addProperty("damagePackets",GuardImpactPresentation.receivedDamagePackets());value.addProperty("suppressedPackets",GuardImpactPresentation.suppressedDamagePackets());value.addProperty("skippedBobs",GuardImpactPresentation.skippedCameraBobs());value.addProperty("suppressed",GuardImpactPresentation.suppressed());value.add("position",RpgConfig.JSON.toJsonTree(new double[]{g.player.getX(),g.player.getY(),g.player.getZ()}));return value;}
    private void tick(ClientTickEvent.Post event){Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;try{if(!initialized){if(warmup++>=55)initialize(game);return;}movies.assertHealthy();if(failure!=null)throw new IllegalStateException("Native defense-flow review failed",failure);if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();guardKey.setDown(desiredGuard);clientObservation=nativeClient();if(System.nanoTime()-started>180_000_000_000L)throw new AssertionError("Defense flow review exceeded three minutes");if(ready&&!videoReady&&prewarm(game)){movies.start("defense-flow","Untouched native RenderFrameEvent.Post PNGs after actual rapier/Drowned/Hoglin prewarm; first-ACTIVE animation fractions, inclusive contact, held C chain, native damage packets and camera hurt suppression",this::observation);videoReady=true;}if(scriptDone&&!videoStopped){movies.stop();videoStopped=true;}if(scriptDone&&videoStopped&&shots.isEmpty()&&pendingShots.get()==0&&movies.finish()){validate();finish(null);}}catch(Throwable error){finish(error);}}
    private JsonObject observation(){Minecraft g=Minecraft.getInstance();JsonObject value=serverObservation.deepCopy();value.addProperty("scenario",visibleCase);value.add("nativeClient",nativeClient());value.addProperty("actualCameraType",g.options.getCameraType().name());JsonObject avatar=RenderObservations.submittedSampler(g.player).get();value.add("rig",avatar.remove("rig"));value.add("submittedAvatar",avatar);var cue=GuardCuePresentation.lastFrame();if(cue!=null)value.add("visibleGuardCue",RpgConfig.JSON.toJsonTree(cue));return value;}
    private void preRender(RenderFrameEvent.Pre event){Minecraft g=Minecraft.getInstance();if(initialized&&!done&&g.player!=null){g.setCameraEntity(g.player);g.options.setCameraType(CameraType.THIRD_PERSON_BACK);}RenderObservations.beginSubmittedFrame(event);}
    private void render(RenderFrameEvent.Post event){if(done||!initialized)return;nativeFrames++;try{if(!videoReady)prewarmFrame();movies.frame(event);clientObservation=nativeClient();String shot;while((shot=shots.poll())!=null){String name=shot;pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}}
    private void validate(){check(scenarios.size()==CASES.size(),"All ten native defense-flow scenarios completed");check(capturedShots.get()>=CASES.size(),"Each native scenario has an original framebuffer screenshot");var clip=movies.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Native Post-render recording exceeds thirty actual frames per second");check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native recording includes genuine sub-tick submitted frames");check(ClientState.number(clip,"changedRigPairs",0)>0,"Original submitted avatar moves across captured frames");check(restored,"Temporary copied-world skill config is restored byte-for-byte");}
    private void finish(Throwable error){if(done)return;done=true;desiredGuard=false;movies.stop();try{movies.assertHealthy();if(warmObserver!=null){warmObserver.close();warmObserver=null;}restoreSkills();}catch(Throwable cleanup){if(error==null)error=cleanup;}try{JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("cause",error==null||error.getCause()==null?null:error.getCause().toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.defenseFlowWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("serverTicks",serverTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());report.addProperty("rapierDrownedSubmittedPrewarmTicks",warmTicks);report.addProperty("fixtureSkillConfigRestored",restored);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("fixtureOverrides",fixtures);report.add("serverTickTrace",trace);report.add("nativeDamageEvents",damageEvents);report.add("renderCapture",safeSummary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("defense-flow-review.json"),RpgConfig.JSON.toJson(report));System.out.println("DEFENSE FLOW REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable reporting){reporting.printStackTrace();}Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.mouseHandler.releaseMouse();if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();}
    private JsonObject safeSummary(){try{return movies.summary();}catch(Throwable error){JsonObject result=new JsonObject();result.addProperty("error",error.toString());return result;}}
}

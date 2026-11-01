package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.CreatureArtRenderer;
import org.fsrpg.client.GuardCuePresentation;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.creature.CreatureMeleeServer;
import org.fsrpg.progression.ProgressionService;
import org.lwjgl.glfw.GLFW;

/** Copied-world packaged-JAR acceptance; every health change uses the actual server damage path. */
final class WeaponClashReview {
    private enum Kind { EXCHANGE, NPC_COUNTER, NPC_ONLY, PLAYER_ONLY, PERFECT, HOG_EXCHANGE, HOG_LATE_GUARD, DUAL, DUAL_CLASH, PVP }
    private record Scenario(String id,Kind kind,int gap,double playerDamage,double enemyDamage) {}
    private static final List<Scenario> CASES=List.of(
            new Scenario("npc-first-gap-0",Kind.EXCHANGE,0,10,6),
            new Scenario("npc-first-gap-1",Kind.EXCHANGE,1,10,6),
            new Scenario("npc-first-gap-2",Kind.EXCHANGE,2,10,6),
            new Scenario("npc-first-gap-3-rejected",Kind.EXCHANGE,3,10,6),
            new Scenario("player-first-gap-1",Kind.EXCHANGE,-1,10,6),
            new Scenario("player-first-gap-2",Kind.EXCHANGE,-2,10,6),
            new Scenario("player-first-gap-3-rejected",Kind.EXCHANGE,-3,10,6),
            new Scenario("exact-difference-three-both-block",Kind.EXCHANGE,0,9,6),
            new Scenario("npc-advantage-residual-and-counter",Kind.EXCHANGE,0,6,10),
            new Scenario("npc-counter-request-in-window-contact-after-window",Kind.NPC_COUNTER,0,6,10),
            new Scenario("ordinary-npc-contact-not-delayed",Kind.NPC_ONLY,0,0,6),
            new Scenario("ordinary-player-contact-not-delayed",Kind.PLAYER_ONLY,0,10,0),
            new Scenario("original-c-precision-guard",Kind.PERFECT,0,0,6),
            new Scenario("native-hoglin-pending-single-dispatch",Kind.HOG_EXCHANGE,1,20,6),
            new Scenario("native-hoglin-later-c-cannot-retroactively-block",Kind.HOG_LATE_GUARD,5,10,6),
            new Scenario("dual-two-left-return-before-third",Kind.DUAL,0,0,0),
            new Scenario("dual-clash-neutral-counter",Kind.DUAL_CLASH,0,0,1),
            new Scenario("pvp-same-tick",Kind.PVP,0,10,10),
            new Scenario("pvp-two-ticks-reversed-list-order",Kind.PVP,2,10,10));
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray(),trace=new JsonArray(),damageEvents=new JsonArray(),fixtures=new JsonArray();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private volatile Throwable failure;
    private volatile boolean ready,scriptDone,videoReady,videoStopped,done;
    private volatile String visibleCase="setup";
    private volatile long observedTick;
    private volatile JsonObject serverObservation=new JsonObject();
    private volatile JsonObject clientLookObservation=new JsonObject();
    private volatile int lookAcknowledgedScenario=-1;
    private volatile int prewarmStage;
    private volatile boolean prewarmAck;
    private volatile UUID prewarmZombieId;
    private long lastPrewarmGameTick=-1,lastZombieSubmittedTick=-1;
    private int dualPrewarmTicks,rapierZombiePrewarmTicks;
    private AutoCloseable prewarmCreatureObserver;
    private UUID playerId;
    private Mob enemy;
    private ReviewPlayer peer;
    private EmbeddedChannel peerChannel;
    private CreatureMeleeServer.Snapshot nativeAttack;
    private Path skillsFile;
    private byte[] originalSkills;
    private boolean initialized,prepared,begun,playerStarted,peerStarted,guardPressed,matchObserved,restored,counterAttackStarted;
    private boolean sawPlayerCounter,sawEnemyCounter,sawEnemyStun,sawPlayerStun;
    private int warmup,index,previousFps,previousDistance,previousScale,dualStage;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private long started,nativeFrames,serverTicks,preparedAt,startedAt,finishAt=-1;
    private long nativeContact,playerContact,peerContact,plannedPlayerStart,firstSequence,secondSequence;
    private long counterContact,counterRequested,counterClashTick;
    private long clashesBefore,balancedBefore,advantageBefore,nativeContactsBefore,firstPlayerDamage=-1,firstEnemyDamage=-1;
    private int nativeDamageCalls;
    private double nativeRawDamage;
    private double counterPostDamage;
    private double dualClashFullDamage,dualCounterFullDamage;
    private float playerHealth,enemyHealth;
    private float counterHealthBefore;
    private JsonObject row;

    /** NeoForge's stock fake player cannot harm players; this local-only fixture changes that policy. */
    private static final class ReviewPlayer extends FakePlayer {
        ReviewPlayer(ServerLevel level) {
            super(level,new GameProfile(UUID.fromString("dc479d45-7ab4-44cf-a440-168b29669fb3"),"[ClashReview]"));
        }
        @Override public boolean canHarmPlayer(Player player) { return true; }
    }

    WeaponClashReview() {
        NeoForge.EVENT_BUS.addListener(this::preRender);
        NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,true,this::incoming);
        NeoForge.EVENT_BUS.addListener(this::damaged);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer player){return RpgSmoke.actor(player);}
    private void action(ServerPlayer player,String name,String argument){rpg().action(player,name,argument);}
    private LivingEntity opponent(ServerPlayer player){return player==peer?Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayer(playerId):peer!=null?peer:enemy;}
    private AttackAim fixtureAim(ServerPlayer player) {
        LivingEntity target=opponent(player);double yaw=0;
        if(target!=null){Vec3 delta=target.position().subtract(player.position());yaw=RapierTargeting.direction(delta.x,0,delta.z,player.getYRot()).yaw();}
        return AttackAim.at(yaw,0);
    }
    /** The same explicit eye-direction entry point used by the normal orbit-camera left click. */
    private void left(ServerPlayer player) {
        AttackAim aim=fixtureAim(player);JsonObject request=new JsonObject();request.addProperty("button","left");request.add("aim",RpgConfig.JSON.toJsonTree(aim));
        action(player,"targetAttack",request.toString());
        if(row!=null){if(!row.has("actualAttackIntents"))row.add("actualAttackIntents",new JsonArray());JsonObject intent=geometry(player);intent.addProperty("worldGameTick",player.level().getGameTime());intent.addProperty("playerUUID",player.getUUID().toString());intent.add("requestedAim",RpgConfig.JSON.toJsonTree(aim));intent.add("acceptedActor",RpgConfig.JSON.toJsonTree(actor(player).snapshot()));row.getAsJsonArray("actualAttackIntents").add(intent);}
    }
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("WEAPON CLASH PASS "+message);}
    private void near(double expected,double actual,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<.004,message+" expected="+expected+" actual="+actual);}
    private void guard(ServerPlayer player,boolean held) {
        action(player,"guard",Boolean.toString(held));
        Minecraft.getInstance().execute(()->{for(var key:Minecraft.getInstance().options.keyMappings)if(key.getName().equals("key.fsrpg.guard"))key.setDown(held);});
    }
    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Native review window belongs to DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name=System.getProperty("fsrpg.weaponClashWorld","");
        check(name.equals("FSRPG Weapon Clash Review 20261021 01")&&name.equals(world.getFileName().toString())
                &&Files.isRegularFile(world.resolve(".fsrpg-weapon-clash-test-world")),"Only the exact named and marked copied world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.mouseHandler.releaseMouse();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);
        GLFW.glfwSetWindowTitle(handle,"FSRPG 相杀与双刀回正验收 · DISPLAY5");game.resizeDisplay();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try {
                ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var entity:p.level().getEntities(p,p.getBoundingBox().inflate(48),e->e instanceof LivingEntity&&!(e instanceof Player)))entity.discard();
                p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(false);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.setExperienceLevels(0);
                p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);p.setHealth(200);p.getFoodData().setFoodLevel(18);
                p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                p.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);p.level().setDayTime(18000);p.level().setWeatherParameters(6000,0,false,false);
                p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));
                for(int x=-8;x<=8;x++)for(int z=-6;z<=12;z++){
                    p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                    for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
                }
                fixtureProgress(p);skillsFile=rpg().config().directory().resolve("skills.json");originalSkills=Files.readAllBytes(skillsFile);
                Files.write(root.resolve("fixture-original-skills.json"),originalSkills);
                check(rpg().config().current().combat().weaponClash().contactWindowTicks()==2,"Installed rules use the requested inclusive two-tick window");
                near(3,rpg().config().current().combat().weaponClash().damageTolerance(),"Installed rules use three damage points of tolerance");ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    private void fixtureProgress(ServerPlayer player) {
        var service=rpg().progression();UUID id=player.getUUID();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(id,0,0,day);service.onSleep(id);
        JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(id.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}
        for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);
        one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();learned.add("rapier");learned.add("dual_sword");one.add("learnedNodes",learned);
        service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
    }
    private int windup(Scenario scenario){return scenario.kind()==Kind.HOG_EXCHANGE||scenario.gap()<0?0:4;}
    private void fixtureSkill(Scenario scenario)throws Exception {
        if(scenario.kind()==Kind.DUAL||scenario.kind()==Kind.DUAL_CLASH)return;
        double multiplier=rpg().config().current().weapons().damageMultiplier("minecraft:iron_sword");
        check(multiplier>0,"Fixture iron sword has a positive installed item multiplier");
        JsonObject changes=new JsonObject();changes.addProperty("damage",scenario.playerDamage()/multiplier);changes.addProperty("windupTicks",windup(scenario));
        changes.addProperty("activeTicks",1);changes.addProperty("recoveryTicks",0);changes.addProperty("cooldownTicks",0);changes.addProperty("knockback",0);
        rpg().config().editSkill("sword_left",changes);JsonObject fixture=new JsonObject();fixture.addProperty("scenario",scenario.id());fixture.add("skillChanges",changes);fixture.addProperty("itemMultiplier",multiplier);fixtures.add(fixture);
    }
    private void equip(ServerPlayer p,boolean dual) {
        actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,dual?new ItemStack(Items.IRON_SWORD):ItemStack.EMPTY);
        action(p,"status","");check(actor(p).style()!=null&&actor(p).style().id().equals(dual?"dual_blade":"rapier"),"Real equipment selects "+(dual?"dual blades":"rapier"));
        if(actor(p).phase()==CombatPhase.SHEATHED)action(p,"draw","");actor(p).setResources(actor(p).maxStamina(),100);
        if(p instanceof ReviewPlayer){p.setPos(.5,80,1.7);p.setYRot(180);p.yBodyRot=p.yBodyRotO=180;}else p.connection.teleport(.5,80,.5,0,0);
        p.setDeltaMovement(Vec3.ZERO);p.invulnerableTime=0;p.hurtTime=0;p.setHealth(200);p.setInvulnerable(false);
    }
    private Mob spawn(ServerPlayer p,Scenario scenario) {
        boolean hoglin=scenario.kind()==Kind.HOG_EXCHANGE||scenario.kind()==Kind.HOG_LATE_GUARD||scenario.kind()==Kind.DUAL_CLASH;
        Mob mob=(Mob)(hoglin?EntityType.HOGLIN:EntityType.ZOMBIE).create(p.level(),EntitySpawnReason.COMMAND);if(mob==null)throw new AssertionError("Cannot create real native mob");
        mob.setPos(.5,80,1.7);mob.setYRot(180);mob.yBodyRot=mob.yBodyRotO=180;mob.setNoGravity(true);mob.setNoAi(true);mob.setPersistenceRequired();
        for(var slot:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))mob.setItemSlot(slot,ItemStack.EMPTY);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);mob.setHealth(200);mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(scenario.enemyDamage());
        mob.getAttribute(Attributes.ARMOR).setBaseValue(0);mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        p.level().addFreshEntity(mob);mob.setTarget(p);return mob;
    }
    @SuppressWarnings("unchecked")
    private void addPeer(ServerPlayer p,boolean first)throws Exception {
        peer=new ReviewPlayer(p.level());
        // FakePlayer's shared dummy connection has no channel until a fixture gives it one.
        // ConfigSync and hasChannel query Netty attributes even though its packet sends are no-ops.
        var connection=peer.connection.getConnection();
        if(connection.channel()==null){peerChannel=new EmbeddedChannel(connection);ChannelAttributes.setConnectionType(connection,ConnectionType.OTHER);}
        check(connection.channel() instanceof EmbeddedChannel&&connection.channel().isOpen(),"PvP dummy connection uses an open in-memory channel without any socket");
        fixtureProgress(peer);peer.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);peer.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);peer.setNoGravity(true);
        var list=p.level().getServer().getPlayerList();List<ServerPlayer> players=(List<ServerPlayer>)field(list,"players").get(list);
        Map<UUID,ServerPlayer> byId=(Map<UUID,ServerPlayer>)field(list,"playersByUUID").get(list);
        check(!byId.containsKey(peer.getUUID()),"Local dummy connection uses an unused review UUID");
        if(first)players.addFirst(peer);else players.add(peer);byId.put(peer.getUUID(),peer);
        p.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(peer)));p.level().addNewPlayer(peer);equip(peer,false);
        check(list.getPlayers().contains(peer)&&p.level().getEntity(peer.getUUID())==peer,"Local review opponent participates in actual PlayerList and world entity collision");
        check(p.canHarmPlayer(peer)&&peer.canHarmPlayer(p),"Both actual server players permit PvP damage without an external login");
    }
    private static Field field(Object object,String name)throws ReflectiveOperationException {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    @SuppressWarnings("unchecked")
    private void removePeer(ServerPlayer p)throws Exception {
        if(peer==null)return;actor(peer).forceSheathed();var list=p.level().getServer().getPlayerList();
        ((List<ServerPlayer>)field(list,"players").get(list)).remove(peer);((Map<UUID,ServerPlayer>)field(list,"playersByUUID").get(list)).remove(peer.getUUID());
        peer.discard();p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(peer.getUUID())));peer=null;
    }
    private void closePeerChannel(){if(peerChannel!=null){peerChannel.finishAndReleaseAll();peerChannel=null;}}
    private void prepare(ServerPlayer p,Scenario scenario)throws Exception {
        guard(p,false);if(enemy!=null){rpg().creatureMelee().cancel(enemy);enemy.discard();enemy=null;}removePeer(p);
        fixtureSkill(scenario);equip(p,scenario.kind()==Kind.DUAL||scenario.kind()==Kind.DUAL_CLASH);
        if(scenario.kind()==Kind.PVP)addPeer(p,scenario.gap()==2);
        else if(scenario.kind()!=Kind.DUAL&&scenario.kind()!=Kind.DUAL_CLASH)enemy=spawn(p,scenario);
        visibleCase=scenario.id();row=new JsonObject();row.addProperty("id",scenario.id());row.addProperty("requestedPlayerMinusNpcContactTicks",scenario.gap());
        row.addProperty("playerFullAttackDamage",scenario.playerDamage());row.addProperty("enemyFullAttackDamage",scenario.enemyDamage());row.add("actualStarts",new JsonArray());
        clashesBefore=rpg().meleeClashes();balancedBefore=rpg().balancedClashes();advantageBefore=rpg().advantageClashes();nativeContactsBefore=rpg().creatureMelee().contactAttempts();
        prepared=true;begun=playerStarted=peerStarted=guardPressed=matchObserved=false;sawPlayerCounter=sawEnemyCounter=sawEnemyStun=sawPlayerStun=false;
        counterAttackStarted=false;counterContact=counterRequested=counterClashTick=-1;counterPostDamage=0;
        nativeContact=playerContact=peerContact=-1;firstPlayerDamage=firstEnemyDamage=-1;finishAt=-1;nativeDamageCalls=0;nativeRawDamage=0;dualStage=0;preparedAt=p.level().getGameTime();
        playerHealth=p.getHealth();enemyHealth=peer!=null?peer.getHealth():enemy!=null?enemy.getHealth():0;
        synchronizeLookOnce();
    }
    private void synchronizeLookOnce() {
        int scenarioIndex=index;lookAcknowledgedScenario=-1;
        Minecraft.getInstance().execute(()->{
            try{
                Minecraft game=Minecraft.getInstance();CombatCamera.reset();game.setCameraEntity(game.player);game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                game.player.setYRot(0);game.player.setXRot(0);game.player.setYHeadRot(0);game.player.yBodyRot=game.player.yBodyRotO=0;game.player.yRotO=game.player.xRotO=0;
                CombatCamera.prepareRapierAttack();
                if(game.getConnection()!=null)game.getConnection().send(new ServerboundMovePlayerPacket.Rot(0,0,game.player.onGround(),game.player.horizontalCollision));
                lookAcknowledgedScenario=scenarioIndex;
            }catch(Throwable error){failure=error;}
        });
    }
    private boolean stableFixture(ServerPlayer p,long now) {
        JsonObject client=clientLookObservation;boolean aligned=lookAcknowledgedScenario==index&&ClientState.number(client,"scenarioIndex",-1)==index
                &&Math.abs(AttackAim.wrap(p.getYRot()))<.1&&Math.abs(p.getXRot())<.1
                &&Math.abs(AttackAim.wrap(ClientState.number(client,"yaw",360)))<.1&&Math.abs(ClientState.number(client,"pitch",90))<.1;
        if(now-preparedAt>=16&&aligned)return true;
        if(now-preparedAt>100)throw new AssertionError("Fixture look failed to stabilize: "+geometry(p)+" client="+client);
        return false;
    }
    private void serverTick(ServerTickEvent.Post event) {
        if(!initialized||!ready||!videoReady||done||scriptDone||failure!=null)return;
        try {
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;serverTicks++;observedTick=p.level().getGameTime();
            if(index>=CASES.size()){removePeer(p);closePeerChannel();restoreSkills();scriptDone=true;return;}
            Scenario scenario=CASES.get(index);if(!prepared){prepare(p,scenario);return;}
            if(!begun&&!stableFixture(p,observedTick)){observe(p);return;}
            if(scenario.kind()==Kind.DUAL){dualStep(p);observe(p);return;}
            if(scenario.kind()==Kind.DUAL_CLASH){dualClashStep(p,scenario);observe(p);return;}
            if(!begun){if(actor(p).phase()!=CombatPhase.READY||peer!=null&&actor(peer).phase()!=CombatPhase.READY)return;begin(p,scenario);observe(p);return;}
            long now=p.level().getGameTime();
            if(!playerStarted&&(scenario.kind()==Kind.EXCHANGE||scenario.kind()==Kind.NPC_COUNTER||scenario.kind()==Kind.PVP)&&now==plannedPlayerStart)startPlayer(p,scenario);
            if(scenario.kind()==Kind.PVP&&!peerStarted&&now==startedAt+scenario.gap()){start(peer);peerStarted=true;}
            if(scenario.kind()==Kind.PERFECT&&!guardPressed&&now==nativeContact-1){guard(p,true);guardPressed=true;check(actor(p).phase()==CombatPhase.GUARDING,"C guard actually starts inside the announced native contact window");}
            if(scenario.kind()==Kind.HOG_LATE_GUARD&&!guardPressed&&now==nativeContact+1){check(nativeDamageCalls==0,"Native Hoglin remains pending before its inclusive deadline");guard(p,true);guardPressed=true;check(actor(p).phase()==CombatPhase.GUARDING,"Later C really interrupts the pending player windup");}
            if(playerStarted&&now==playerContact-1&&Math.abs(scenario.gap())<=2)
                check(actor(p).phase()==CombatPhase.ACTIVE,"Player is actually ACTIVE before the next collision phase: "+scenario.id());
            sawPlayerCounter|=rpg().perfectGuardCounterRemaining(p)>0;sawEnemyCounter|=enemy!=null&&rpg().perfectGuardCounterRemaining(enemy)>0;
            sawEnemyStun|=enemy!=null&&rpg().perfectGuardStunRemaining(enemy)>0;sawPlayerStun|=actor(p).phase()==CombatPhase.STUNNED;
            if(!matchObserved&&rpg().meleeClashes()>clashesBefore){matchObserved=true;row.addProperty("observedClashGameTick",now);row.addProperty("playerCounterAtClash",rpg().perfectGuardCounterRemaining(p));row.addProperty("enemyCounterAtClash",enemy==null?0:rpg().perfectGuardCounterRemaining(enemy));
                check(now==Math.max(playerContact,scenario.kind()==Kind.PVP?peerContact:nativeContact),"Clash resolves exactly when the second real attack reaches contact: "+scenario.id());shots.add(scenario.id()+"-actual-clash");}
            if(scenario.kind()==Kind.NPC_COUNTER&&matchObserved&&!counterAttackStarted){
                counterClashTick=now;counterRequested=now;counterHealthBefore=p.getHealth();near(1,playerHealth-counterHealthBefore,"NPC's first winning clash deals exactly one residual health point");
                check(rpg().perfectGuardCounterRemaining(enemy)>0,"NPC starts its next real attack while the four-tick opportunity is still open");
                check(!enemy.doHurtTarget(p.level(),p),"NPC counter requests the complete original animated attack rather than direct fixture damage");
                var next=rpg().creatureMelee().snapshot(enemy);check(next!=null&&next.sequence()!=nativeAttack.sequence(),"A distinct real server attack timeline owns the NPC counter");counterContact=next.contactGameTick();counterAttackStarted=true;
                check(counterContact>counterClashTick+4,"Counter's actual native contact deliberately occurs after the four-tick input opportunity");
                check(rpg().perfectGuardCounterRemaining(enemy)==0,"Accepted native counter request consumes the opportunity exactly once");row.add("actualCounterAttack",RpgConfig.JSON.toJsonTree(next));row.addProperty("counterRequestedGameTick",counterRequested);row.addProperty("counterContactGameTick",counterContact);
            }
            if(counterAttackStarted&&now==counterContact-1){p.invulnerableTime=0;row.addProperty("counterInvulnerabilityResetGameTick",now);}
            if(counterAttackStarted&&now==counterContact)shots.add(scenario.id()+"-native-amplified-contact");
            observe(p);long last=Math.max(counterContact,Math.max(nativeContact,Math.max(playerContact,peerContact)));
            if(now>=last+4){if(finishAt<0){verify(p,scenario);finishAt=now+2;shots.add(scenario.id()+"-result");}else if(now>=finishAt){row.addProperty("passed",true);scenarios.add(row);index++;prepared=false;}}
            check(now-preparedAt<100,"Native scenario completes within five seconds: "+scenario.id());
        }catch(Throwable error){failure=error;}
    }
    private void begin(ServerPlayer p,Scenario scenario) {
        long now=p.level().getGameTime();begun=true;startedAt=now;row.add("stableStartGeometry",geometry(p));row.add("stableStartClientLook",clientLookObservation.deepCopy());row.addProperty("settledTicksBeforeBegin",now-preparedAt);
        if(scenario.kind()==Kind.PLAYER_ONLY){playerContact=now+windup(scenario)+1;startPlayer(p,scenario);return;}
        if(scenario.kind()==Kind.PVP){playerContact=now+windup(scenario)+1;peerContact=playerContact+scenario.gap();plannedPlayerStart=now;startPlayer(p,scenario);if(scenario.gap()==0){start(peer);peerStarted=true;}return;}
        if(scenario.kind()==Kind.HOG_EXCHANGE||scenario.kind()==Kind.HOG_LATE_GUARD){
            check(rpg().creatureMelee().catalog().find("minecraft:hoglin","sword").isEmpty(),"Native Hoglin fixture has no authored combat profile");
            nativeContact=now;playerContact=now+windup(scenario)+1;startPlayer(p,scenario);
            check(!enemy.doHurtTarget(p.level(),p),"Already-started player attack defers the complete original native Hoglin contact");
            long sequence=rpg().creatureMelee().clashSequence(enemy);var pending=rpg().creatureMelee().snapshot(enemy);
            check(sequence>0&&pending!=null&&pending.sequence()==sequence&&pending.clip().isEmpty()&&pending.phase().equals("NATIVE_CONTACT"),"Unprofiled Hoglin owns a native pending sequence without an invented animation clip");
            for(int attempt=0;attempt<3;attempt++)enemy.doHurtTarget(p.level(),p);
            check(rpg().creatureMelee().clashSequence(enemy)==sequence&&nativeDamageCalls==0,"Repeated native Hoglin requests preserve one pending invocation without side effects");
            near(playerHealth,p.getHealth(),"Pending native Hoglin contact has not dealt health damage early");row.addProperty("nativeFallbackSequence",sequence);return;
        }
        check(!enemy.doHurtTarget(p.level(),p),"Native zombie enters its real animation windup without immediate damage: "+scenario.id());nativeAttack=rpg().creatureMelee().snapshot(enemy);
        check(nativeAttack!=null,"Actual zombie owns a server creature attack timeline: "+scenario.id());nativeContact=nativeAttack.contactGameTick();row.add("nativeAttack",RpgConfig.JSON.toJsonTree(nativeAttack));
        if(scenario.kind()==Kind.EXCHANGE||scenario.kind()==Kind.NPC_COUNTER){playerContact=nativeContact+scenario.gap();plannedPlayerStart=playerContact-windup(scenario)-1;
            check(plannedPlayerStart>=now,"Fixture schedules its physical attack before the intended native collision: "+scenario.id());if(plannedPlayerStart==now)startPlayer(p,scenario);}
    }
    private void start(ServerPlayer player) {
        long before=actor(player).snapshot().swingSequence();left(player);CombatActor a=actor(player);
        check(a.currentSkill()!=null&&a.currentSkill().id().equals("sword_left")&&a.snapshot().swingSequence()==before+1,"Actual server action commits exactly one physical left attack: "+visibleCase);
        JsonObject one=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();one.addProperty("worldGameTick",player.level().getGameTime());one.addProperty("playerUUID",player.getUUID().toString());one.add("geometry",geometry(player));row.getAsJsonArray("actualStarts").add(one);
    }
    private void startPlayer(ServerPlayer p,Scenario scenario){start(p);playerStarted=true;row.addProperty("expectedPlayerContactGameTick",playerContact);row.addProperty("fixtureWindupTicks",windup(scenario));}
    private void verify(ServerPlayer p,Scenario scenario) {
        double loss=playerHealth-p.getHealth(),enemyLoss=enemyHealth-(peer!=null?peer.getHealth():enemy.getHealth());
        row.addProperty("actualPlayerHealthLoss",loss);row.addProperty("actualEnemyHealthLoss",enemyLoss);row.addProperty("firstPlayerDamageGameTick",firstPlayerDamage);row.addProperty("firstEnemyDamageGameTick",firstEnemyDamage);
        row.addProperty("meleeClashes",rpg().meleeClashes()-clashesBefore);row.addProperty("balancedClashes",rpg().balancedClashes()-balancedBefore);row.addProperty("advantageClashes",rpg().advantageClashes()-advantageBefore);
        row.addProperty("nativeDamageInvocations",nativeDamageCalls);row.addProperty("nativeIncomingDamageBeforeClash",nativeRawDamage);row.addProperty("sawPlayerCounter",sawPlayerCounter);row.addProperty("sawNpcCounter",sawEnemyCounter);
        if(scenario.kind()==Kind.EXCHANGE){
            boolean clash=Math.abs(scenario.gap())<=2;check(rpg().meleeClashes()-clashesBefore==(clash?1:0),"Inclusive contact window accepts only differences up to two ticks: "+scenario.id());
            near(scenario.enemyDamage(),nativeRawDamage,"Damage comparison receives the actual native incoming attack amount: "+scenario.id());
            check(nativeDamageCalls==1,"Native virtual attack dispatches incoming damage exactly once: "+scenario.id());
            if(clash){double difference=scenario.playerDamage()-scenario.enemyDamage();near(Math.max(0,-difference-3),loss,"Only NPC advantage beyond three reaches player health: "+scenario.id());near(Math.max(0,difference-3),enemyLoss,"Only player advantage beyond three reaches NPC health: "+scenario.id());
                if(difference>3)check(sawPlayerCounter&&sawEnemyStun,"Player advantage grants a real counter opportunity and NPC stun: "+scenario.id());
                else if(difference< -3)check(sawEnemyCounter&&sawPlayerStun,"NPC advantage grants a real NPC counter opportunity and player stun");
                else check(rpg().balancedClashes()==balancedBefore+1&&!sawPlayerCounter&&!sawEnemyCounter,"Damage difference exactly three grants mutual ordinary blocks without perfect counters");
            } else {near(scenario.enemyDamage(),loss,"A three-tick gap retains the native ordinary attack: "+scenario.id());if(scenario.gap()<0)near(scenario.playerDamage(),enemyLoss,"Earlier unmatched player contact still lands through actual damage");}
        } else if(scenario.kind()==Kind.NPC_COUNTER){
            check(nativeDamageCalls==2,"NPC clash and subsequent counter each dispatch the original incoming path exactly once");near(10,nativeRawDamage,"Second native incoming starts with the NPC's real unmodified ten-point attack");
            near(15,counterPostDamage,"Native damage Post observes ten damage multiplied by the frozen 1.5 counter bonus");near(15,counterHealthBefore-p.getHealth(),"Actual player health loses the full frozen counter damage after the input window expires");
            near(16,loss,"First one-point clash remainder plus the later fifteen-point counter are the only player damage");near(0,enemyLoss,"NPC advantage blocks the player's original six-point attack");
            check(counterRequested-counterClashTick<4&&counterContact-counterClashTick>4,"Actual NPC request and contact bracket the four-tick opportunity");check(rpg().meleeClashes()==clashesBefore+1,"The later unanswered NPC counter is not counted as another clash");row.addProperty("counterActualPostDamage",counterPostDamage);
        } else if(scenario.kind()==Kind.HOG_EXCHANGE){
            check(nativeDamageCalls==1,"Buffered native Hoglin invokes its complete real damage path exactly once");check(nativeRawDamage>0,"Hoglin comparison observes its actual randomized native attack damage");
            check(rpg().meleeClashes()==clashesBefore+1,"Unprofiled native Hoglin contact pairs with the real player's next-tick contact");near(0,loss,"Player advantage blocks actual native Hoglin damage");
            near(Math.max(0,scenario.playerDamage()-nativeRawDamage-3),enemyLoss,"Native Hoglin receives only the actual compared damage difference beyond three");check(sawPlayerCounter&&sawEnemyStun,"Native fallback advantage grants the existing perfect-guard counter and real Hoglin stun");
        } else if(scenario.kind()==Kind.HOG_LATE_GUARD){
            check(nativeDamageCalls==1&&nativeRawDamage>0,"Unanswered native Hoglin dispatches real randomized damage exactly once");near(nativeRawDamage,loss,"C pressed after the original native contact cannot reduce its delayed damage");
            near(0,enemyLoss,"Later C cancels the player's still-uncommitted outgoing attack");check(firstPlayerDamage==nativeContact+2,"Unmatched native contact dispatches at the inclusive two-tick deadline");
            check(rpg().meleeClashes()==clashesBefore&&!sawPlayerCounter&&!sawEnemyCounter,"Later C grants neither a retroactive clash nor a perfect counter");
        } else if(scenario.kind()==Kind.NPC_ONLY){near(6,loss,"An unanswered native attack keeps full original damage");check(firstPlayerDamage==nativeContact,"An unanswered native attack is applied at its actual contact tick without waiting");}
        else if(scenario.kind()==Kind.PLAYER_ONLY){near(10,enemyLoss,"An unanswered player attack keeps full server-configured damage");check(firstEnemyDamage==playerContact,"An unanswered player attack is applied at its actual contact tick without waiting");}
        else if(scenario.kind()==Kind.PERFECT){near(0,loss,"Original late C precision guard still cancels the complete native attack");check(sawPlayerCounter&&sawEnemyStun,"Original C precision guard retains counter and actual enemy stun");check(rpg().meleeClashes()==clashesBefore,"Manual C precision guard does not manufacture a weapon clash");}
        else if(scenario.kind()==Kind.PVP){near(0,loss,"Actual PvP clash blocks damage to the local player");near(0,enemyLoss,"Actual PvP clash blocks damage to the local dummy opponent");check(rpg().balancedClashes()==balancedBefore+1,"Actual PlayerList PvP collision produces exactly one mutual block: "+scenario.id());}
        if(scenario.kind()!=Kind.PVP&&scenario.kind()!=Kind.PLAYER_ONLY)check(rpg().creatureMelee().contactAttempts()==nativeContactsBefore+(scenario.kind()==Kind.NPC_COUNTER?2:1),"Expected original animation CONTACT count was recorded: "+scenario.id());
    }
    private void dualStep(ServerPlayer p) {
        CombatActor a=actor(p);long now=p.level().getGameTime();
        if(!begun){if(a.phase()!=CombatPhase.READY)return;begun=true;startedAt=now;left(p);firstSequence=a.snapshot().swingSequence();check(a.currentSkill()!=null&&a.currentSkill().id().equals("dual_ll"),"First actual left click starts the original dual left-hand cut");left(p);check(a.comboSnapshot().queued(),"Second actual left click buffers the right-hand continuation");return;}
        if(dualStage==0&&a.snapshot().swingSequence()==firstSequence+1){secondSequence=a.snapshot().swingSequence();check(a.currentSkill()!=null&&a.currentSkill().id().equals("dual_rl"),"Second actual left cut uses the other original hand");double stamina=a.stamina();left(p);
            check(a.snapshot().swingSequence()==secondSequence&&!a.comboSnapshot().queued()&&a.comboSnapshot().lastRejection().equals("DIRECTION_EXHAUSTED"),"Third consecutive actual left request is rejected before the hands return");near(stamina,a.stamina(),"Rejected third left request consumes no stamina");dualStage=1;shots.add("dual-two-left-third-rejected");}
        if(dualStage==1&&a.phase()==CombatPhase.READY&&a.currentSkill()==null){left(p);check(a.snapshot().swingSequence()==secondSequence+1&&a.currentSkill()!=null&&a.currentSkill().id().equals("dual_ll"),"Natural authored recovery returns the hands and permits a new original left cut");dualStage=2;shots.add("dual-returned-left-accepted");}
        if(dualStage==2&&a.phase()==CombatPhase.READY&&a.currentSkill()==null){row.addProperty("actualAcceptedBladeCount",3);row.addProperty("thirdContinuousInputRejected",true);row.addProperty("newLeftAcceptedAfterNaturalReturn",true);row.addProperty("passed",true);scenarios.add(row);index++;prepared=false;}
        check(now-startedAt<180,"Dual sequence finishes through authored recovery without forced reset");
    }
    private void dualClashStep(ServerPlayer p,Scenario scenario) {
        CombatActor a=actor(p);long now=p.level().getGameTime();
        if(!begun){
            if(a.phase()!=CombatPhase.READY)return;begun=true;startedAt=now;left(p);firstSequence=a.snapshot().swingSequence();
            check(a.currentSkill()!=null&&a.currentSkill().id().equals("dual_ll"),"Real dual clash fixture begins with the first original left cut");
            left(p);check(a.comboSnapshot().queued(),"Real dual clash fixture queues the second original left cut");return;
        }
        if(dualStage==0&&a.snapshot().swingSequence()==firstSequence+1){
            secondSequence=a.snapshot().swingSequence();check(a.currentSkill()!=null&&a.currentSkill().id().equals("dual_rl"),"The other original hand performs the second left cut before any opponent is spawned");dualStage=1;
        }
        if(dualStage==1&&(a.phase()==CombatPhase.ACTIVE||a.phase()==CombatPhase.WINDUP&&a.snapshot().phaseTicksRemaining()<=1)){
            enemy=spawn(p,scenario);enemyHealth=enemy.getHealth();nativeContact=now;playerContact=now+(a.phase()==CombatPhase.ACTIVE?1:a.snapshot().phaseTicksRemaining()+1);
            dualClashFullDamage=a.currentSkill().damage()*a.attackDamageMultiplier()*rpg().config().current().weapons().damageMultiplier("minecraft:iron_sword");
            check(dualClashFullDamage>3.5,"Original second dual cut has enough actual item-scaled damage to beat the real half-point Hoglin attack");
            check(!enemy.doHurtTarget(p.level(),p),"Newly spawned native Hoglin reaches its original contact while the second dual cut is already committed");
            var pending=rpg().creatureMelee().snapshot(enemy);check(pending!=null&&pending.phase().equals("NATIVE_CONTACT"),"Dual clash uses a real unprofiled native contact pending for the second blade");
            row.addProperty("secondDualFullDamage",dualClashFullDamage);row.addProperty("originalNpcContactGameTick",nativeContact);row.addProperty("expectedSecondDualContactGameTick",playerContact);row.addProperty("secondDualSequence",secondSequence);row.add("nativePending",RpgConfig.JSON.toJsonTree(pending));dualStage=2;
        }
        if(dualStage==2&&rpg().meleeClashes()==clashesBefore+1){
            counterClashTick=now;check(now==playerContact&&now-nativeContact<=2,"Second original dual blade and native Hoglin contact really pair inside two ticks");
            check(nativeDamageCalls==1,"Native opponent invokes damage exactly once for the dual clash");near(.5,nativeRawDamage,"Adult Hoglin's real attribute-one attack contributes exactly half a damage point");
            near(dualClashFullDamage-nativeRawDamage-3,enemyHealth-enemy.getHealth(),"Dual advantage applies only the compared remainder beyond three");near(0,playerHealth-p.getHealth(),"Winning dual clash blocks the real native attack");
            check(a.phase()==CombatPhase.READY&&a.currentSkill()==null,"Real dual clash ends the current blade in the displayed default READY stance");
            check(rpg().perfectGuardCounterRemaining(p)>0,"Real dual clash grants the four-tick left counter opportunity");row.add("readyAfterClash",RpgConfig.JSON.toJsonTree(a.snapshot()));
            left(p);check(a.snapshot().swingSequence()==secondSequence+1&&a.currentSkill()!=null&&a.currentSkill().id().equals("dual_ll"),"Immediate physical left after clash starts original dual_ll from returned default hands");
            near(1.5,a.attackDamageMultiplier(),"Immediate real dual left consumes the clash reward with a 1.5 damage multiplier");check(rpg().perfectGuardCounterRemaining(p)==0,"Accepted immediate dual counter consumes the opportunity exactly once");
            dualCounterFullDamage=a.currentSkill().damage()*a.attackDamageMultiplier()*rpg().config().current().weapons().damageMultiplier("minecraft:iron_sword");
            counterContact=now+a.currentSkill().windupTicks()+1;row.add("actualCounterStart",RpgConfig.JSON.toJsonTree(a.snapshot()));row.addProperty("counterRequestedGameTick",now);row.addProperty("counterExpectedFullDamage",dualCounterFullDamage);row.addProperty("counterExpectedContactGameTick",counterContact);dualStage=3;shots.add(scenario.id()+"-immediate-left-accepted");
        }
        if(dualStage==3&&now==counterContact-1){enemy.invulnerableTime=0;row.addProperty("counterTargetInvulnerabilityResetGameTick",now);}
        if(dualStage==3&&a.phase()==CombatPhase.READY&&a.currentSkill()==null){
            near(dualCounterFullDamage,counterPostDamage,"Actual damage Post confirms the accepted dual counter delivers its frozen 1.5 multiplier");
            near(dualClashFullDamage-nativeRawDamage-3+dualCounterFullDamage,enemyHealth-enemy.getHealth(),"Actual NPC health contains only the clash remainder and one enhanced original dual cut");
            check(rpg().meleeClashes()==clashesBefore+1&&rpg().advantageClashes()==advantageBefore+1,"Dual return and counter produce one real advantage clash without duplicate contacts");
            row.addProperty("actualCounterPostDamage",counterPostDamage);row.addProperty("actualNpcHealthLoss",enemyHealth-enemy.getHealth());row.addProperty("actualPlayerHealthLoss",playerHealth-p.getHealth());row.addProperty("nativeDamageInvocations",nativeDamageCalls);row.addProperty("passed",true);scenarios.add(row);shots.add(scenario.id()+"-result");index++;prepared=false;
        }
        check(now-startedAt<180,"Real dual clash and immediate counter finish without a forty-tick default-pose fallback");
    }
    private void observe(ServerPlayer p) {
        JsonObject sample=RpgConfig.JSON.toJsonTree(actor(p).snapshot()).getAsJsonObject();sample.addProperty("scenario",visibleCase);sample.addProperty("worldGameTick",observedTick);sample.addProperty("playerHealth",p.getHealth());
        sample.add("geometry",geometry(p));sample.add("clientLook",clientLookObservation.deepCopy());
        sample.addProperty("enemyHealth",enemy==null?peer==null?0:peer.getHealth():enemy.getHealth());sample.addProperty("meleeClashes",rpg().meleeClashes());sample.addProperty("balancedClashes",rpg().balancedClashes());sample.addProperty("advantageClashes",rpg().advantageClashes());
        if(enemy!=null)sample.add("nativeAttack",RpgConfig.JSON.toJsonTree(rpg().creatureMelee().snapshot(enemy)));serverObservation=sample;trace.add(sample.deepCopy());
    }
    private JsonObject geometry(ServerPlayer p) {
        JsonObject value=new JsonObject();CombatActor a=actor(p);LivingEntity target=opponent(p);
        value.add("playerPosition",RpgConfig.JSON.toJsonTree(new double[]{p.getX(),p.getY(),p.getZ()}));value.addProperty("yaw",p.getYRot());value.addProperty("pitch",p.getXRot());value.add("look",RpgConfig.JSON.toJsonTree(new double[]{p.getLookAngle().x,p.getLookAngle().y,p.getLookAngle().z}));
        value.addProperty("mainItem",BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());value.addProperty("offItem",BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString());
        if(a.attackAim()!=null)value.add("frozenAttackAim",RpgConfig.JSON.toJsonTree(a.attackAim()));
        if(a.currentSkill()!=null){value.addProperty("skillReach",a.currentSkill().reach());value.addProperty("skillArcDegrees",a.currentSkill().arcDegrees());}
        if(target!=null){
            var box=target.getBoundingBox();Vec3 origin=p.getEyePosition(),nearest=new Vec3(Math.clamp(origin.x,box.minX,box.maxX),Math.clamp(origin.y,box.minY,box.maxY),Math.clamp(origin.z,box.minZ,box.maxZ));
            Vec3 forward=a.attackAim()==null?p.getLookAngle():Vec3.directionFromRotation((float)a.attackAim().sample(0).pitch(),(float)a.attackAim().sample(0).yaw());
            Vec3 flatForward=forward.multiply(1,0,1).normalize(),flatTarget=target.position().subtract(p.position()).multiply(1,0,1).normalize();
            value.addProperty("targetUUID",target.getUUID().toString());value.add("targetPosition",RpgConfig.JSON.toJsonTree(new double[]{target.getX(),target.getY(),target.getZ()}));value.addProperty("lineOfSight",p.hasLineOfSight(target));value.addProperty("eyeDistanceToTargetBounds",origin.distanceTo(nearest));value.addProperty("frozenFacingAngleDegrees",Math.toDegrees(Math.acos(Math.clamp(flatForward.dot(flatTarget),-1,1))));
        }
        return value;
    }
    private void incoming(LivingIncomingDamageEvent event) {
        if(!initialized||done||row==null||enemy==null||event.getEntity().getUUID().equals(playerId)==false||event.getSource().getEntity()!=enemy||event.getSource().getDirectEntity()!=enemy)return;
        nativeDamageCalls++;nativeRawDamage=event.getAmount();JsonObject entry=new JsonObject();entry.addProperty("scenario",visibleCase);entry.addProperty("kind","native-before-defense");entry.addProperty("worldGameTick",enemy.level().getGameTime());entry.addProperty("amount",event.getAmount());
        Long contact=rpg().creatureMelee().invocationContactTick(enemy,enemy,event.getEntity());if(contact!=null)entry.addProperty("originalContactGameTick",contact);entry.addProperty("invocationDeferred",rpg().creatureMelee().invocationDeferred());damageEvents.add(entry);
    }
    private void damaged(LivingDamageEvent.Post event) {
        if(!initialized||done||row==null||event.getNewDamage()<=0)return;
        boolean player=event.getEntity().getUUID().equals(playerId),other=event.getEntity()==enemy||event.getEntity()==peer;if(!player&&!other)return;
        long now=event.getEntity().level().getGameTime();if(player&&firstPlayerDamage<0)firstPlayerDamage=now;if(other&&firstEnemyDamage<0)firstEnemyDamage=now;
        if(player&&counterAttackStarted&&now==counterContact&&event.getSource().getEntity()==enemy)counterPostDamage=event.getNewDamage();
        if(other&&index<CASES.size()&&CASES.get(index).kind()==Kind.DUAL_CLASH&&dualStage==3&&now==counterContact
                &&event.getSource().getEntity() instanceof ServerPlayer source&&source.getUUID().equals(playerId))counterPostDamage=event.getNewDamage();
        JsonObject entry=new JsonObject();entry.addProperty("scenario",visibleCase);entry.addProperty("kind","actual-post-damage");entry.addProperty("worldGameTick",now);entry.addProperty("victimUUID",event.getEntity().getUUID().toString());entry.addProperty("newDamage",event.getNewDamage());damageEvents.add(entry);
    }
    private void restoreSkills()throws Exception {if(!restored&&originalSkills!=null){Files.write(skillsFile,originalSkills);restored=true;}}
    /** Cold configuration and both real weapon rigs are loaded before native frame timing begins. */
    private boolean prewarm(Minecraft game)throws Exception {
        if(prewarmStage==0){
            prewarmStage=1;prewarmAck=false;visibleCase="prewarm-dual";
            game.getSingleplayerServer().execute(()->{
                try{ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);fixtureSkill(CASES.getFirst());equip(p,true);prewarmAck=true;}
                catch(Throwable error){failure=error;}
            });
            return false;
        }
        if(prewarmStage==1&&prewarmAck&&dualPrewarmTicks>=55){
            check(dualPrewarmTicks>=55,"Actual client submitted the equipped dual rig in at least fifty-five distinct pre-recording ticks");
            prewarmStage=2;prewarmAck=false;lastPrewarmGameTick=-1;visibleCase="prewarm-rapier-zombie";
            prewarmCreatureObserver=CreatureArtRenderer.observeRenderedAttack(sample->{
                Minecraft current=Minecraft.getInstance();UUID id=prewarmZombieId;
                if(id!=null&&current.level!=null&&id.toString().equals(ClientState.text(sample,"entityUUID","")))lastZombieSubmittedTick=current.level.getGameTime();
            });
            game.getSingleplayerServer().execute(()->{
                try{ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);equip(p,false);enemy=spawn(p,CASES.getFirst());prewarmZombieId=enemy.getUUID();prewarmAck=true;}
                catch(Throwable error){failure=error;}
            });
            return false;
        }
        if(prewarmStage==2&&prewarmAck&&rapierZombiePrewarmTicks>=55){
            check(rapierZombiePrewarmTicks>=55,"Actual client submitted the equipped rapier and exact prewarm zombie in at least fifty-five distinct pre-recording ticks");
            closePrewarmObserver();prewarmStage=3;visibleCase="prewarm-complete";return true;
        }
        return prewarmStage==3;
    }
    private void prewarmRenderedFrame() {
        Minecraft game=Minecraft.getInstance();if(!prewarmAck||game.level==null||game.player==null||prewarmStage<1||prewarmStage>2)return;
        long gameTick=game.level.getGameTime();if(gameTick==lastPrewarmGameTick)return;
        String expected=prewarmStage==1?"dual_blade":"rapier";
        if(!ClientState.drawn()||!expected.equals(ClientState.text(ClientState.snapshot(),"weaponStyle",""))
                ||!game.player.getMainHandItem().is(Items.IRON_SWORD)
                ||prewarmStage==1&&!game.player.getOffhandItem().is(Items.IRON_SWORD)
                ||prewarmStage==2&&(!game.player.getOffhandItem().isEmpty()||lastZombieSubmittedTick!=gameTick))return;
        JsonObject avatar;
        try{avatar=RenderObservations.submittedSampler(game.player).get();}
        catch(IllegalStateException notSubmittedYet){return;}
        if(!avatar.has("rig")||avatar.getAsJsonObject("rig").isEmpty())return;
        lastPrewarmGameTick=gameTick;if(prewarmStage==1)dualPrewarmTicks++;else rapierZombiePrewarmTicks++;
    }
    private void closePrewarmObserver()throws Exception {if(prewarmCreatureObserver!=null){prewarmCreatureObserver.close();prewarmCreatureObserver=null;}}
    private void tick(ClientTickEvent.Post event) {
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try {
            if(!initialized){if(warmup++>=55)initialize(game);return;}movies.assertHealthy();if(failure!=null)throw new IllegalStateException("Native weapon-clash review failed",failure);
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();
            JsonObject look=new JsonObject();look.addProperty("scenarioIndex",lookAcknowledgedScenario);look.addProperty("yaw",game.player.getYRot());look.addProperty("pitch",game.player.getXRot());look.add("position",RpgConfig.JSON.toJsonTree(new double[]{game.player.getX(),game.player.getY(),game.player.getZ()}));clientLookObservation=look;
            if(System.nanoTime()-started>180_000_000_000L)throw new AssertionError("Weapon clash review exceeded three minutes");
            if(ready&&!videoReady&&prewarm(game)){movies.start("weapon-clashes","One untouched PNG per actual RenderFrameEvent.Post throughout all scenarios, after real dual/rapier/zombie rendering prewarm; submitted third-person avatar, original zombie contact animation, real native damage and dual hand return",this::observation);videoReady=true;}
            if(scriptDone&&!videoStopped){movies.stop();videoStopped=true;}
            if(scriptDone&&videoStopped&&shots.isEmpty()&&pendingShots.get()==0&&movies.finish()){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private JsonObject observation() {
        Minecraft game=Minecraft.getInstance();JsonObject sample=serverObservation.deepCopy();sample.addProperty("scenario",visibleCase);sample.addProperty("observedServerGameTick",observedTick);
        sample.addProperty("actualCameraType",game.options.getCameraType().name());sample.addProperty("actualCameraEntityId",game.getCameraEntity().getUUID().toString());
        JsonObject avatar=RenderObservations.submittedSampler(game.player).get();sample.add("rig",avatar.remove("rig"));sample.add("submittedAvatar",avatar);
        var cue=GuardCuePresentation.lastFrame();if(cue!=null)sample.add("visibleGuardCue",RpgConfig.JSON.toJsonTree(cue));return sample;
    }
    private void preRender(RenderFrameEvent.Pre event) {
        Minecraft game=Minecraft.getInstance();if(initialized&&!done&&game.level!=null&&game.player!=null){game.setCameraEntity(game.player);game.options.setCameraType(CameraType.THIRD_PERSON_BACK);}RenderObservations.beginSubmittedFrame(event);
    }
    private void render(RenderFrameEvent.Post event) {
        if(done||!initialized)return;nativeFrames++;
        try {if(!videoReady)prewarmRenderedFrame();movies.frame(event);String shot;while((shot=shots.poll())!=null){String name=shot;pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}
        catch(Throwable error){failure=error;}
    }
    private void validate() {
        check(scenarios.size()==CASES.size(),"All nineteen actual native contact, PvP, guard and dual scenarios completed");check(capturedShots.get()>=CASES.size(),"Actual framebuffer screenshots cover every scenario result");
        var clip=movies.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Native recording samples at least thirty actual rendered frames per second");
        check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"PNG capture includes genuine sub-tick native frames");check(ClientState.number(clip,"changedRigPairs",0)>0,"Native submitted avatar changes pose across captured frames");
        check(restored,"Temporary copied-config skill fixture has been restored byte-for-byte");
    }
    private void finish(Throwable error) {
        if(done)return;done=true;movies.stop();try{movies.assertHealthy();closePrewarmObserver();restoreSkills();}catch(Throwable cleanup){if(error==null)error=cleanup;}
        try {
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.weaponClashWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);
            report.addProperty("serverTicks",serverTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());report.addProperty("fixtureSkillConfigRestored",restored);
            report.addProperty("dualSubmittedPrewarmTicks",dualPrewarmTicks);report.addProperty("rapierAndZombieSubmittedPrewarmTicks",rapierZombiePrewarmTicks);report.addProperty("prewarmStage",prewarmStage);
            report.addProperty("pvpFixture","Smoke-only FakePlayer subclass with NeoForge dummy connection, registered in the real PlayerList and ServerLevel; no external login. Stock FakePlayerFactory forbids PvP.");
            report.addProperty("npcCounterValidation","A distinct original NPC attack is requested inside the four-tick window, then reaches native contact after the window; highest-priority incoming preserves the original 10 and actual damage Post/health verify the frozen 15.");
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("fixtureOverrides",fixtures);report.add("serverTickTrace",trace);report.add("nativeDamageEvents",damageEvents);report.add("renderCapture",safeCaptureSummary());RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("weapon-clash-review.json"),RpgConfig.JSON.toJson(report));System.out.println("WEAPON CLASH REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);
        if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}
        if((peer!=null||peerChannel!=null)&&game.getSingleplayerServer()!=null)game.getSingleplayerServer().execute(()->{try{ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);if(p!=null)removePeer(p);closePeerChannel();}catch(Throwable cleanup){cleanup.printStackTrace();}finally{game.execute(game::stop);}});else game.stop();
    }
    private JsonObject safeCaptureSummary(){try{return movies.summary();}catch(Throwable error){JsonObject result=new JsonObject();result.addProperty("passed",false);result.addProperty("error",error.toString());return result;}}
}

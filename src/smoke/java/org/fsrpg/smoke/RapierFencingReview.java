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
import java.util.function.Consumer;
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
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.progression.ProgressionService;
import org.lwjgl.glfw.GLFW;

/** Five real fencing phrases, normal queued attacks and actual vanilla movement in a marked copied world. */
final class RapierFencingReview {
    private record Scenario(String id,String key){}
    private static final List<Scenario> CASES=List.of(
            new Scenario("direct-thrust-first-motion",""),new Scenario("rising-cut",""),
            new Scenario("diagonal-cut",""),new Scenario("return-cut",""),
            new Scenario("lunge-thrust-finisher",""),
            new Scenario("fencing-forward-travel","forward"),new Scenario("fencing-backward-travel","back"),
            new Scenario("fencing-left-travel","left"),new Scenario("fencing-right-travel","right"));
    private static final List<String> PHRASES=List.of("direct-thrust","rising-cut","diagonal-cut","return-cut","lunge-thrust");
    private static final List<String> VIEWS=List.of("rear","side","front");
    private static final List<String> FLOWS=List.of("stationary-turn-follow","moving-turn-start-stop","left-right-thrust-branch","guard-left-cancel-return","guard-right-riposte","dodge-right-reprise","standalone-charge-release","first-person-five-phrase-chain","first-person-left-right-branch");
    private enum Stage { WARM,CHAIN,VIEW,LOCK,MOVE,FLOW,FINISH }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray(),serverTrace=new JsonArray(),damageTrace=new JsonArray(),modelProof=new JsonArray();
    private final JsonArray flowScenarios=new JsonArray();
    private final Set<String> seenPhrases=new HashSet<>(),viewActions=new HashSet<>();
    private final JsonObject[] rows=new JsonObject[9];
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private final Set<String> screenshotCases=new HashSet<>();
    private final Set<String> preparationShots=new HashSet<>();
    private final int[] cutFrames=new int[5];
    private final double[] windupTravel=new double[5],peakSpeed=new double[5],peakElapsed=new double[5],moveExtent=new double[4],moveLean=new double[4];
    private volatile Stage stage=Stage.WARM;
    private volatile Throwable failure;
    private volatile boolean ready,videoReady,chainFinished,scriptDone,done;
    private volatile int index,acknowledged;
    private volatile String visibleCase="prewarm";
    private volatile JsonObject serverSample=new JsonObject();
    private UUID playerId;
    private Mob target;
    private boolean initialized,acted,videoStopped,clientConfigurationRestored,lockedReticleHidden;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private int warmup,warmTicks,clientTicks,stageStarted,commandSerial,awaited,lockStep,previousFps,previousDistance,previousScale;
    private long started,nativeFrames,lastWarmTick=-1,chainSequence,queuedSequence,resetInvulnerabilitySequence=-1,chainStarted,officialBefore,cacheBefore;
    private int lastCut=-1,actualDamagePosts;
    private int viewIndex,flowIndex,flowStep,flowFrames,lowerChecks,armChecks;
    private volatile long flowStarted;
    private volatile boolean flowReady,flowComplete;
    private JsonObject flowRow;
    private double maximumWaistGap,maximumWristFlexion,maximumHipStep,maximumLowerWeightStep;
    private JsonObject previousNative;
    private boolean previousThirdPerson;
    private double previousCameraDistance;
    private net.neoforged.neoforge.common.ModConfigSpec.DoubleValue cameraDistanceOption;
    private net.neoforged.neoforge.common.ModConfigSpec.BooleanValue thirdPersonOption;
    private long firstPersonBefore,firstPersonItemsBefore,flowSequence,flowQueued;
    private final Set<String> firstPersonActions=new HashSet<>();
    private double lastTick,lastElapsed,orbitYaw;
    private float targetBefore;
    private Vec3 lastHand;
    private Path skillsFile;
    private byte[] skillsOriginal;

    RapierFencingReview(){for(int n=0;n<rows.length;n++){rows[n]=new JsonObject();rows[n].addProperty("id",CASES.get(n).id());rows[n].add("actualServerStarts",new JsonArray());}
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);NeoForge.EVENT_BUS.addListener(this::render);NeoForge.EVENT_BUS.addListener(this::damaged);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.add(message);System.out.println("RAPIER MOTION PASS "+message);}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private int dispatch(Consumer<ServerPlayer> task){int serial=++commandSerial;Minecraft.getInstance().getSingleplayerServer().execute(()->{try{ServerPlayer p=Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayer(playerId);task.accept(p);acknowledged=serial;}catch(Throwable error){failure=error;}});return serial;}
    private void enter(Stage next){stage=next;stageStarted=clientTicks;acted=false;}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Native rapier review remains on DISPLAY5");
        String name=System.getProperty("fsrpg.rapierFencingWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();check(name.startsWith("FSRPG Rapier Fencing Review ")&&world.getFileName().toString().equals(name)&&Files.isRegularFile(world.resolve(".fsrpg-rapier-fencing-test-world")),"Only the explicitly named marked copied rapier world is modified");
        for(String model:List.of("legacy_rapier.fsvoxel","legacy_rapier_fp.fsvoxel")){Path runtime=rpg().config().directory().resolve("models").resolve(model);try(var source=RpgServer.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/"+model)){require(source!=null,"Missing packaged rapier model");byte[] packaged=source.readAllBytes(),configured=Files.readAllBytes(runtime);check(Arrays.equals(packaged,configured),"Actual copied configuration uses the exact packaged reauthored model: "+model);JsonObject proof=new JsonObject();proof.addProperty("model",model);proof.addProperty("runtimePath",runtime.toString());proof.addProperty("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(configured)));modelProof.add(proof);}}
        var thirdPersonField=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField("THIRD_PERSON");thirdPersonField.setAccessible(true);thirdPersonOption=(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue)thirdPersonField.get(null);
        var distanceField=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField("CAMERA_DISTANCE");distanceField.setAccessible(true);cameraDistanceOption=(net.neoforged.neoforge.common.ModConfigSpec.DoubleValue)distanceField.get(null);previousCameraDistance=cameraDistanceOption.get();cameraDistanceOption.set(4.5);
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);game.mouseHandler.releaseMouse();KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        CombatCamera.reset();game.player.setYRot(0);game.player.setXRot(0);game.player.yRotO=game.player.xRotO=0;var b=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1280,800);GLFW.glfwSetWindowPos(handle,b.get("x").getAsInt()+35,b.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 瑗挎磱鍓戞寫鍔堝埡鐮嶄笌鍒嗘敮楠屾敹 路 DISPLAY5");game.resizeDisplay();initialized=true;started=System.nanoTime();
        dispatch(p->{try{
            for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.setExperienceLevels(0);p.getFoodData().setFoodLevel(18);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));
            for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            fixtureProgress();actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);rpg().action(p,"status","");rpg().action(p,"draw","");actor(p).setResources(actor(p).maxStamina(),100);p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);
            target=(Mob)EntityType.HUSK.create(p.level(),EntitySpawnReason.COMMAND);require(target!=null,"Cannot spawn original daylight-safe husk target");target.setPos(.5,80,2.3);target.setNoAi(true);target.setNoGravity(true);target.setPersistenceRequired();target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);target.setHealth(200);target.getAttribute(Attributes.ARMOR).setBaseValue(0);target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);p.level().addFreshEntity(target);targetBefore=target.getHealth();skillsFile=rpg().config().directory().resolve("skills.json");skillsOriginal=Files.readAllBytes(skillsFile);ready=true;
        }catch(Exception error){throw new IllegalStateException(error);}});
    }
    private void fixtureProgress(){var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();learned.add("rapier");one.add("learnedNodes",learned);one.add("creativeNodes",new JsonArray());service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));}
    private void left(ServerPlayer p,boolean explicitTarget){JsonObject request=new JsonObject();request.addProperty("button","left");request.add("aim",RpgConfig.JSON.toJsonTree(AttackAim.at(p.getYRot(),0)));request.addProperty("targetId",explicitTarget?target.getId():-1);request.addProperty("targetUuid",explicitTarget?target.getUUID().toString():"");rpg().action(p,"targetAttack",request.toString());}
    private void serverTick(ServerTickEvent.Post event){if(!initialized||!ready||done)return;try{ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;CombatActor a=actor(p);long now=p.level().getGameTime();
        if(stage==Stage.CHAIN){if(chainSequence==0){if(a.phase()!=CombatPhase.READY)return;left(p,true);chainSequence=a.snapshot().swingSequence();chainStarted=now;left(p,false);queuedSequence=chainSequence;check(a.comboSnapshot().queued(),"Second left is accepted during the first cut's preparation");}
            long sequence=a.snapshot().swingSequence();int cut=(int)(sequence-chainSequence);require(cut>=0&&cut<=4,"Real relaxed combo uses exactly the five authored fencing stages");index=cut;visibleCase=VIEWS.get(viewIndex)+"-"+CASES.get(index).id();if(a.currentSkill()!=null&&viewActions.add(viewIndex+":"+cut)){rows[index].addProperty("serverSequence",sequence);rows[index].getAsJsonArray("actualServerStarts").add(RpgConfig.JSON.toJsonTree(a.snapshot()));check(a.currentSkill().id().equals(List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust","rapier_lunge_finish").get(cut)),"Real queued combo reaches stage "+(cut+1)+" from "+VIEWS.get(viewIndex));}
            if(cut<4&&queuedSequence!=sequence&&a.currentSkill()!=null){left(p,false);queuedSequence=sequence;check(a.comboSnapshot().queued(),"Continuation queues directly without returning to a neutral hand pose");}
            if(a.phase()==CombatPhase.ACTIVE&&resetInvulnerabilitySequence!=sequence){target.invulnerableTime=0;resetInvulnerabilitySequence=sequence;rows[index].addProperty("singleNativeCooldownResetBeforeContactTick",now);}
            if(cut==4&&a.phase()==CombatPhase.READY&&a.currentSkill()==null){check(actualDamagePosts==5,"All five real fencing phrases commit native damage from the actual player source");double expected=0;for(String id:List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust","rapier_lunge_finish"))expected+=rpg().config().current().skills().get(id).damage()*rpg().config().current().weapons().damageMultiplier("minecraft:iron_sword");check(Math.abs(targetBefore-target.getHealth()-expected)<.01,"Real target health matches configured skill damage and item multiplier exactly once");require(now-chainStarted<80,"Actual five-phrase combo finishes naturally");chainFinished=true;}
        }
        if(stage==Stage.FLOW&&flowReady&&!flowComplete)flowServer(p,now);
        JsonObject sample=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();sample.addProperty("scenario",visibleCase);sample.addProperty("worldGameTick",now);sample.addProperty("playerX",p.getX());sample.addProperty("playerZ",p.getZ());sample.addProperty("velocityX",p.getDeltaMovement().x);sample.addProperty("velocityZ",p.getDeltaMovement().z);sample.addProperty("targetHealth",target==null?0:target.getHealth());sample.addProperty("serverTargetLocked",rpg().targetLock(p).locked());serverSample=sample;if(videoReady)serverTrace.add(sample.deepCopy());
    }catch(Throwable error){failure=error;}}
    private void damaged(LivingDamageEvent.Post event){if(done||event.getEntity()!=target||event.getNewDamage()<=0)return;Entity source=event.getSource().getEntity();require(source instanceof ServerPlayer&&source.getUUID().equals(playerId)&&event.getSource().getDirectEntity()==source,"Native target damage must come from the real player melee source");CombatActor a=actor((ServerPlayer)source);check(a.phase()==CombatPhase.ACTIVE,"Actual server damage happens inside the configured ACTIVE frame");actualDamagePosts++;JsonObject value=new JsonObject();value.addProperty("worldGameTick",event.getEntity().level().getGameTime());value.addProperty("sourceUUID",source.getUUID().toString());value.addProperty("damage",event.getNewDamage());value.add("actualActorAtDamage",RpgConfig.JSON.toJsonTree(a.snapshot()));damageTrace.add(value);}
    private void movement(Minecraft game,String key){game.options.keyUp.setDown(key.equals("forward"));game.options.keyDown.setDown(key.equals("back"));game.options.keyLeft.setDown(key.equals("left"));game.options.keyRight.setDown(key.equals("right"));}
    private void right(ServerPlayer p){rpg().action(p,"attack","right");}
    private void learnBranches(){
        // Automatic nodes check training totals, not the learned-node set. Only
        // this marked fixture receives the minimum required totals; training
        // bonuses remain disabled so authored combat timing stays unchanged.
        var service=rpg().progression();JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject();
        var one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
        var requirements=rpg().config().current().skillTrees().node("rapier_parry_thrust").requirements();
        for(String name:List.of("mind","body","footwork")){
            int total=switch(name){case "mind"->requirements.mind();case "body"->requirements.body();default->requirements.footwork();};
            JsonObject points=new JsonObject();points.addProperty("total",total);points.addProperty("available",total);one.add(name,points);
        }
        one.addProperty("rapierKills",requirements.score());service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
        check(service.effects(playerId).rapierThrust()&&service.effects(playerId).rapierParry(),"Copied-world fixture meets the real automatic branch requirements");
    }
    private void prepareFlow(){
        flowReady=flowComplete=false;flowStarted=0;flowStep=flowFrames=0;visibleCase=FLOWS.get(flowIndex);
        flowSequence=flowQueued=0;
        if(flowIndex==7){previousThirdPerson=thirdPersonOption.get();thirdPersonOption.set(false);CombatCamera.reset();Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);firstPersonBefore=LegacyFirstPersonAnimations.samples();firstPersonItemsBefore=LegacyFirstPersonAnimations.itemSubmissions();}
        flowRow=new JsonObject();flowRow.addProperty("id",visibleCase);flowRow.add("serverStates",new JsonArray());
        dispatch(p->{if(flowIndex==0)learnBranches();rpg().action(p,"guard","false");rpg().action(p,"rapierCancel","");
            actor(p).forceSheathed();actor(p).setResources(actor(p).maxStamina(),100);p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);
            rpg().action(p,"draw","");flowReady=true;});
    }
    private boolean flowSaw(String id){for(var state:flowRow.getAsJsonArray("serverStates"))if(id.equals(ClientState.text(state.getAsJsonObject(),"skillId","")))return true;return false;}
    private void flowServer(ServerPlayer p,long now){
        CombatActor a=actor(p);if(flowStarted==0){if(a.phase()!=CombatPhase.READY)return;flowStarted=now;}
        int age=(int)(now-flowStarted);String flow=FLOWS.get(flowIndex);
        if(age==0){switch(flow){
            case "left-right-thrust-branch","first-person-left-right-branch","first-person-five-phrase-chain"->left(p,false);
            case "guard-left-cancel-return","guard-right-riposte"->rpg().action(p,"guard","true");
            case "dodge-right-reprise"->rpg().action(p,"dodge","{\"x\":0,\"z\":-1}");
            case "standalone-charge-release"->right(p);
            default->{}
        }}
        if(flow.equals("left-right-thrust-branch")||flow.equals("first-person-left-right-branch")){
            if(age==1){right(p);check(a.comboSnapshot().queued(),"Actual right input queues one thrust during the left attack");}
            if(age==2)rpg().action(p,"rapierCharge","false");
        }
        if(flow.equals("first-person-five-phrase-chain")){
            long sequence=a.snapshot().swingSequence();if(flowSequence==0)flowSequence=sequence;
            int cut=(int)(sequence-flowSequence);require(cut>=0&&cut<5,"First-person real chain contains exactly five phrases");
            if(cut<4&&a.currentSkill()!=null&&flowQueued!=sequence){left(p,false);flowQueued=sequence;}
        }
        if(flow.equals("guard-left-cancel-return")){
            if(age==5){check(a.phase()==CombatPhase.GUARDING,"C holds the native guard before immediate left transition");shots.add(flow+"-guard");}
            if(age==8){left(p,false);check(a.currentSkill()!=null,"Actual left starts directly from the native guard");}
            if(age==9)rpg().action(p,"guard","false");
        }
        if(flow.equals("guard-right-riposte")){
            if(age==1){
                // This unprofiled native attacker has no authored delayed contact; the
                // existing defense suite uses the same original Hoglin virtual method.
                target=(Mob)EntityType.HOGLIN.create(p.level(),EntitySpawnReason.COMMAND);require(target!=null,"Native riposte attacker exists");
                target.setPos(.5,80,1.7);target.setNoAi(true);target.setNoGravity(true);target.setPersistenceRequired();
                target.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4);target.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);p.level().addFreshEntity(target);
                p.setInvulnerable(false);p.invulnerableTime=0;
            }
            if(age==2){target.doHurtTarget(p.level(),p);check(a.techniqueSnapshot().skillId().equals("rapier_riposte"),"A real native melee hit is perfectly guarded and opens the right riposte");}
            if(age==3){right(p);check(a.currentSkill()!=null&&a.currentSkill().id().equals("rapier_riposte"),"Actual right input consumes the guard riposte without charging");}
            if(age==4){rpg().action(p,"guard","false");rpg().action(p,"rapierCharge","false");p.setInvulnerable(true);target.discard();target=null;}
        }
        if(flow.equals("dodge-right-reprise")&&age>0&&flowStep==0&&a.phase()==CombatPhase.READY){
            check(a.techniqueSnapshot().skillId().equals("rapier_reprise"),"A completed real directional dodge opens reprise");right(p);
            check(a.currentSkill()!=null&&a.currentSkill().id().equals("rapier_reprise"),"Actual right input consumes the dodge reprise");flowStep=1;
        }
        if(flow.equals("standalone-charge-release")){
            if(age==10)check(a.phase()==CombatPhase.WINDUP,"Standalone held right retains the actual charge beyond the ordinary windup");
            if(age==12){rpg().action(p,"rapierCharge","false");shots.add(flow+"-release");}
        }
        if(flow.equals("moving-turn-start-stop")&&age==20)left(p,false);
        JsonObject state=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();state.addProperty("age",age);flowRow.getAsJsonArray("serverStates").add(state);
        int duration=flowIndex<2?84:flow.equals("standalone-charge-release")||flow.equals("first-person-five-phrase-chain")?70:58;
        if(age>=duration){
            check(a.phase()==CombatPhase.READY,"Native flow finishes in READY with natural recovery: "+flow);
            if(flow.equals("left-right-thrust-branch")||flow.equals("first-person-left-right-branch"))check(flowSaw("sword_left")&&flowSaw("sword_thrust"),"Left-right branch submits both actual server actions: "+flow);
            if(flow.equals("first-person-five-phrase-chain"))for(String id:List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust","rapier_lunge_finish"))check(flowSaw(id),"First-person actual server chain reaches "+id);
            if(flow.equals("guard-right-riposte"))check(flowSaw("rapier_riposte"),"Native riposte exists in the server trace");
            if(flow.equals("dodge-right-reprise"))check(flowSaw("rapier_reprise"),"Native reprise exists in the server trace");
            if(flow.equals("standalone-charge-release"))check(flowSaw("sword_thrust"),"Standalone charge keeps its stable thrust skill");
            flowComplete=true;
        }
    }
    private void tick(ClientTickEvent.Post event){Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;try{if(!initialized){if(warmup++>=55)initialize(game);return;}movie.assertHealthy();if(failure!=null)throw new IllegalStateException("Actual rapier review failed",failure);require(System.nanoTime()-started<180_000_000_000L,"Rapier review exceeded three minutes");clientTicks++;game.setWindowActive(true);game.getFramerateLimitTracker().onInputReceived();int age=clientTicks-stageStarted;
        if(stage==Stage.WARM){if(!ready||warmTicks<55)return;if(!acted){CombatCamera.reset();game.player.setYRot(0);game.player.setXRot(0);game.player.yRotO=game.player.xRotO=0;CombatCamera.prepareRapierAttack();double inspectionGain=Math.pow(game.options.sensitivity().get()*.6+.2,3)*8;require(CombatCamera.mouse(0,12/(inspectionGain*.15)*(game.options.invertMouseY().get()?-1:1)),"Native review camera frames the full supported body");game.getConnection().send(new ServerboundMovePlayerPacket.Rot(0,0,game.player.onGround(),game.player.horizontalCollision));stageStarted=clientTicks;acted=true;return;}if(age<16)return;officialBefore=OfficialHeldItems.submissions();cacheBefore=PlayerAnimations.frameCacheHits();movie.start("rapier-fencing","Untouched native Post-render PNGs after actual rapier model prewarm; five compact fencing phrases, native damage timing and four directions of real movement with composed reaction",this::observation);videoReady=true;enter(Stage.CHAIN);return;}
        if(stage==Stage.CHAIN&&chainFinished){
            check(lockedReticleHidden,"Real rapier target lock hides the actual center reticle throughout attacks");check(CombatCamera.lockedTarget()!=null&&!CombatCamera.reticleVisible(),"Native client receives the actual accepted target lock");
            if(viewIndex<VIEWS.size()-1){viewIndex++;enter(Stage.VIEW);return;}
            orbitYaw=CombatCamera.yaw();check(CombatCamera.mouse(80,0),"Locked rapier accepts normal orbit-camera mouse input");enter(Stage.LOCK);return;
        }
        if(stage==Stage.VIEW){
            if(!acted){awaited=dispatch(p->{target.setInvisible(true);target.setHealth(target.getMaxHealth());target.invulnerableTime=0;targetBefore=target.getHealth();actualDamagePosts=0;actor(p).setResources(actor(p).maxStamina(),100);});acted=true;}
            double delta=AttackAim.wrap(viewIndex*90-CombatCamera.yaw());
            if(Math.abs(delta)>.01){double gain=Math.pow(game.options.sensitivity().get()*.6+.2,3)*8;double step=Math.clamp(delta,-6,6);
                require(CombatCamera.mouse(step/(gain*.15)*(game.options.invertMouseX().get()?-1:1),0),"Native orbit accepts smooth inspection rotation");}
            if(age>=30&&Math.abs(delta)<.01&&acknowledged>=awaited){check(true,"Native orbit reaches the "+VIEWS.get(viewIndex)+" view");chainSequence=queuedSequence=0;resetInvulnerabilitySequence=-1;chainFinished=false;lastCut=-1;enter(Stage.CHAIN);}return;
        }
        if(stage==Stage.LOCK){if(lockStep==0&&age>=4){check(Math.abs(AttackAim.wrap(CombatCamera.yaw()-orbitYaw))>5,"Rapier camera still freely orbits its locked fighter");CombatCamera.mouse(-80,0);awaited=dispatch(p->{rpg().action(p,"draw","");if(target!=null)target.discard();});lockStep=1;return;}if(lockStep==1&&acknowledged>=awaited&&age>=10&&CombatCamera.lockedTarget()==null){check(CombatCamera.reticleVisible(),"Real R intent unlocks and restores the rapier center reticle");for(int n=0;n<5;n++){rows[n].addProperty("passed",true);rows[n].addProperty("nativeSubmittedFrames",cutFrames[n]);if(n<5){rows[n].addProperty("nativeWindupHandTravelCm",windupTravel[n]);rows[n].addProperty("nativePeakHandSpeedCmPerTick",peakSpeed[n]);rows[n].addProperty("nativePeakElapsedTicks",peakElapsed[n]);}scenarios.add(rows[n]);}index=5;visibleCase=CASES.get(index).id();enter(Stage.MOVE);return;}}
        if(stage==Stage.MOVE){movement(game,age<30?CASES.get(index).key():"");if(age==12)awaited=dispatch(p->{left(p,false);rows[index].getAsJsonArray("actualServerStarts").add(RpgConfig.JSON.toJsonTree(actor(p).snapshot()));});if(age>=42&&acknowledged>=awaited){int n=index-5;check(moveExtent[n]>.04,"Real movement packets produce visible displacement: "+visibleCase);check(moveLean[n]>.1,"Actual submitted waist follows real travel with bounded composed attack reaction: "+visibleCase);rows[index].addProperty("maximumNativeTravelPerTick",moveExtent[n]);rows[index].addProperty("maximumNativeLeanBalanceCm",moveLean[n]);rows[index].addProperty("passed",true);scenarios.add(rows[index]);index++;if(index==9){enter(Stage.FLOW);prepareFlow();return;}visibleCase=CASES.get(index).id();enter(Stage.MOVE);}}
        if(stage==Stage.FLOW){
            int elapsed=flowStarted==0?-1:(int)(game.level.getGameTime()-flowStarted);
            if(flowIndex<2&&elapsed>=0){
                movement(game,flowIndex==1&&elapsed>=8&&elapsed<45?"forward":"");
                if(flowIndex==0&&elapsed>=8&&elapsed<32)CombatCamera.mouse(20,0);
                if(flowIndex==0&&elapsed>=40&&elapsed<64)CombatCamera.mouse(-20,0);
                if(flowIndex==1&&elapsed>=16&&elapsed<36)CombatCamera.mouse(12,0);
                if(elapsed==8||elapsed==32||elapsed==44||elapsed==65)shots.add(visibleCase+"-tick-"+elapsed);
            }
            if(flowComplete){movement(game,"");check(flowFrames>20,"Actual renderer covers the full transition: "+visibleCase);flowRow.addProperty("nativeSubmittedFrames",flowFrames);flowRow.addProperty("passed",true);flowScenarios.add(flowRow);flowIndex++;if(flowIndex==FLOWS.size())enter(Stage.FINISH);else prepareFlow();}
        }
        if(stage==Stage.FINISH&&age>=5)scriptDone=true;if(scriptDone&&!videoStopped){movie.stop();videoStopped=true;}if(scriptDone&&videoStopped&&shots.isEmpty()&&pendingShots.get()==0&&movie.finish()){validate();finish(null);}
    }catch(Throwable error){finish(error);}}
    private void preRender(RenderFrameEvent.Pre event){RenderObservations.beginSubmittedFrame(event);}
    private JsonObject observation(){
        Minecraft game=Minecraft.getInstance();JsonObject value=serverSample.deepCopy();value.addProperty("scenario",visibleCase);
        value.addProperty("nativeReticleVisible",CombatCamera.reticleVisible());value.addProperty("nativeTargetLocked",CombatCamera.lockedTarget()!=null);
        value.addProperty("cameraType",game.options.getCameraType().name());value.addProperty("cameraYaw",CombatCamera.yaw());
        if(flowIndex>=7&&stage==Stage.FLOW||stage==Stage.FINISH){
            value.addProperty("firstPersonDrawn",ClientState.drawn());value.addProperty("firstPersonNativeSamples",LegacyFirstPersonAnimations.samples());value.addProperty("firstPersonOfficialItemSubmissions",LegacyFirstPersonAnimations.itemSubmissions());
            value.addProperty("firstPersonAction",LegacyFirstPersonAnimations.lastAction());value.addProperty("firstPersonClipSeconds",LegacyFirstPersonAnimations.lastSeconds());value.add("rig",new JsonObject());
        }else{JsonObject avatar=RenderObservations.submittedSampler(game.player).get();value.add("rig",avatar.remove("rig"));value.add("submittedAvatar",avatar);}
        return value;
    }
    private static double distance(JsonArray a,JsonArray b){double sum=0;for(int i=0;i<3;i++){double d=a.get(i).getAsDouble()-b.get(i).getAsDouble();sum+=d*d;}return Math.sqrt(sum);}
    private void inspectNative(){
        Minecraft game=Minecraft.getInstance();
        if(flowIndex>=7&&stage==Stage.FLOW||stage==Stage.FINISH){
            if(stage==Stage.FLOW&&flowReady){flowFrames++;String action=LegacyFirstPersonAnimations.lastAction();if(firstPersonActions.add(visibleCase+":"+action))shots.add(visibleCase+"-"+action);}
            return;
        }
        JsonObject avatar=RenderObservations.submittedSampler(game.player).get();
        if(!avatar.has("rig")||avatar.getAsJsonObject("rig").isEmpty())return;
        if(stage==Stage.WARM&&ready){long tick=game.level.getGameTime();if(tick!=lastWarmTick&&ClientState.drawn()){warmTicks++;lastWarmTick=tick;}return;}
        if(!videoReady)return;
        JsonObject animation=ClientState.object(avatar,"renderAnimation");String action=ClientState.text(animation,"action","");
        String tags=animation.has("clipTags")?animation.getAsJsonArray("clipTags").toString():"";
        boolean authored=tags.contains("rapier_fencing:0100")||tags.contains("rapier_guard:0100")||tags.contains("rapier_charge:0100");
        boolean settled=ClientState.number(animation,"transitionElapsedTicks",0)>=ClientState.number(animation,"transitionTicks",0)-.001;
        require(RenderObservations.stableLowerSupport(avatar),"Native feet retain supported soles and attached hips: "+visibleCase);lowerChecks++;
        JsonObject lower=ClientState.object(avatar,"finalLowerBody");
        if(!lower.isEmpty()){
            double gap=distance(lower.getAsJsonArray("waist"),lower.getAsJsonArray("pelvis"));maximumWaistGap=Math.max(maximumWaistGap,gap);
            require(gap<.035,"Actual rendered waist and common pelvis remain connected: "+visibleCase+" gap="+gap);
            JsonArray sections=lower.getAsJsonArray("renderedTorsoSections");
            require(sections!=null&&sections.size()==5,"Native sword submits its segmented lumbar skin");
            JsonArray seam=sections.get(4).getAsJsonArray(),pelvis=lower.getAsJsonArray("pelvis");
            for(var vertex:seam)require(Math.abs(vertex.getAsJsonArray().get(1).getAsDouble()-pelvis.get(1).getAsDouble())<.035,"Waist skin ring remains on the common hip plane");
        }
        for(String side:List.of("right","left")){
            double wrist=ClientState.number(avatar,side+"WristFlexionDegrees",999);maximumWristFlexion=Math.max(maximumWristFlexion,wrist);
            require(Double.isFinite(wrist),"Actual "+side+" wrist remains finite: "+visibleCase);
            if(authored&&settled)require(wrist<=34.05,"Actual "+side+" wrist stays within the authored 34 degree limit: "+visibleCase+" = "+wrist);
            if(!authored&&wrist>60&&preparationShots.add("legacy-wrist-"+side+"-"+action))shots.add("legacy-wrist-"+side+"-"+action);
            JsonArray shoulder=avatar.getAsJsonArray(side+"ShoulderCm"),elbow=avatar.getAsJsonArray(side+"ElbowCm");
            double upper=distance(shoulder,elbow);require(upper>15&&upper<45,"Actual "+side+" upper arm retains human-scale rigid length");armChecks++;
        }
        require(ClientState.number(avatar,"rightOfficialLocatorHandErrorCm",999)<.1,"Actual official sword locator remains attached to the hand");
        lockedReticleHidden|=CombatCamera.lockedTarget()!=null&&!CombatCamera.reticleVisible();
        if(previousNative!=null&&ClientState.number(avatar,"sampledTick",0)>ClientState.number(previousNative,"sampledTick",0)){
            JsonObject oldAnimation=ClientState.object(previousNative,"renderAnimation");
            double gap=ClientState.number(avatar,"sampledTick",0)-ClientState.number(previousNative,"sampledTick",0);
            if(gap<1.2&&ClientState.text(oldAnimation,"style","").equals("rapier")){
                maximumLowerWeightStep=Math.max(maximumLowerWeightStep,Math.abs(ClientState.number(avatar,"lowerBodyWeight",0)-ClientState.number(previousNative,"lowerBodyWeight",0)));
                JsonObject oldLower=ClientState.object(previousNative,"finalLowerBody");
                if(!lower.isEmpty()&&!oldLower.isEmpty())maximumHipStep=Math.max(maximumHipStep,distance(lower.getAsJsonArray("pelvis"),oldLower.getAsJsonArray("pelvis")));
                if(stage==Stage.CHAIN&&ClientState.number(animation,"assetIdentity",-1)==ClientState.number(oldAnimation,"assetIdentity",-2))
                    for(String field:List.of("motionIdentity","swordLeanIdentity","swordTurnIdentity"))require(ClientState.number(animation,field,-1)==ClientState.number(oldAnimation,field,-2),"Queued fencing preserves persistent "+field);
            }
        }
        previousNative=avatar.deepCopy();
        int cut=action.matches("legacy_rapier_attack[1-5]_upper")?action.charAt("legacy_rapier_attack".length())-'1':-1;
        double elapsed=ClientState.number(animation,"sampledElapsedTicks",-1),windup=ClientState.number(animation,"windupTicks",0),active=ClientState.number(animation,"activeTicks",0);
        boolean contact=elapsed>=windup+.05&&elapsed<=windup+active;
        if(stage==Stage.CHAIN&&cut>=0){
            cutFrames[cut]++;String phrase=ClientState.text(animation,"rapierPhrase","");
            require(PHRASES.get(cut).equals(phrase),"Real submitted action uses the reauthored phrase "+PHRASES.get(cut));
            require(animation.getAsJsonArray("clipTags").toString().contains("rapier_fencing:0100"),"Real client loads the current 0.10.0 fencing source");seenPhrases.add(phrase);
            String name=VIEWS.get(viewIndex)+"-"+CASES.get(cut).id();
            if(elapsed>=0&&elapsed<windup&&preparationShots.add(name))shots.add(name+"-preparation");
            if(contact&&screenshotCases.add(name))shots.add(name);
            JsonArray hand=avatar.getAsJsonObject("rig").getAsJsonArray("right_hand");Vec3 point=new Vec3(hand.get(12).getAsDouble(),hand.get(13).getAsDouble(),hand.get(14).getAsDouble());
            double tick=ClientState.number(avatar,"sampledTick",0);
            if(lastCut==cut&&tick>lastTick){double distance=point.distanceTo(lastHand),speed=distance/(tick-lastTick);if(elapsed<=windup&&lastElapsed>=0)windupTravel[cut]+=distance;if(speed>peakSpeed[cut]){peakSpeed[cut]=speed;peakElapsed[cut]=elapsed;}}
            lastCut=cut;lastHand=point;lastTick=tick;lastElapsed=elapsed;
        }
        if(stage==Stage.MOVE&&index>=5&&index<9){
            if(contact&&screenshotCases.add(visibleCase))shots.add(visibleCase);int n=index-5;var velocity=avatar.getAsJsonArray("movementXZ");
            moveExtent[n]=Math.max(moveExtent[n],Math.hypot(velocity.get(0).getAsDouble(),velocity.get(1).getAsDouble()));
            JsonObject motion=ClientState.object(animation,"motion");boolean forward=n<2;double direction=forward?ClientState.number(motion,"forward",0):ClientState.number(motion,"x",0);
            double balance=ClientState.number(avatar,forward?"balanceZcm":"balanceXcm",0);if(direction*balance>0)moveLean[n]=Math.max(moveLean[n],Math.abs(balance));
        }
        if(stage==Stage.FLOW&&flowReady){flowFrames++;if(contact&&screenshotCases.add(visibleCase))shots.add(visibleCase+"-active");}
    }
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;nativeFrames++;try{inspectNative();movie.frame(event);String shot;while((shot=shots.poll())!=null){String name=shot;pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}}
    private void validate()throws Exception{
        check(scenarios.size()==9,"Five native phrases and four real movement directions completed");
        check(flowScenarios.size()==FLOWS.size(),"All nine turn, branch, guard, dodge and first-person flows completed");
        check(viewActions.size()==15,"All five real server phrases were repeated in rear, side and front views");
        check(seenPhrases.containsAll(PHRASES),"All five reauthored 0.10.0 phrases reached the native renderer");
        check(capturedShots.get()>=28&&screenshotCases.size()>=19,"Original framebuffer PNGs cover every phrase view and movement direction");
        for(int n=0;n<5;n++){
            check(cutFrames[n]>=9,"Native renderer submitted the actual combo action "+(n+1)+" across three views");
            check(windupTravel[n]>.25,"Actual preparation moves before ACTIVE for phrase "+(n+1));
            check(peakSpeed[n]>1,"Actual contact stroke has visible native hand velocity for phrase "+(n+1));
        }
        for(int n=1;n<=5;n++)check(firstPersonActions.contains("first-person-five-phrase-chain:legacy_rapier_attack"+n+"_upper"),"Native first-person hand renderer advances through combo phrase "+n);
        check(firstPersonActions.contains("first-person-left-right-branch:legacy_rapier_rapier_thrust_hit_upper"),"Native first-person hand renderer reaches the actual right thrust branch");
        check(LegacyFirstPersonAnimations.samples()>firstPersonBefore+30&&LegacyFirstPersonAnimations.itemSubmissions()>firstPersonItemsBefore+30,"First-person replay submits actual native skin and official items");
        check(lowerChecks>100&&armChecks>200,"Actual segmented skin, waist, common hips, wrists and grounded feet are checked per rendered frame");
        check(OfficialHeldItems.submissions()>officialBefore,"Native sword uses official held ItemStack submissions");
        check(PlayerAnimations.frameCacheHits()>cacheBefore,"Skin and held items reuse the same actual per-frame pose");
        check(Arrays.equals(skillsOriginal,Files.readAllBytes(skillsFile)),"Native acceptance keeps all server skill timing, damage and costs byte-for-byte");
        var clip=movie.summary().getAsJsonArray("clips").get(0).getAsJsonObject();
        check(ClientState.number(clip,"sourceFps",0)>=30,"Original native PNG sampling exceeds thirty actual frames per second");
        check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native film contains actual sub-tick motion samples");
    }
    private void finish(Throwable error){if(done)return;done=true;movie.stop();Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.mouseHandler.releaseMouse();if(initialized){cameraDistanceOption.set(previousCameraDistance);if(flowIndex>=7)thirdPersonOption.set(previousThirdPerson);game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);clientConfigurationRestored=true;}
        try{JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("cause",error==null||error.getCause()==null?null:error.getCause().toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.rapierFencingWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("submittedRapierPrewarmTicks",warmTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());report.addProperty("clientConfigurationRestored",clientConfigurationRestored);report.addProperty("nativeLowerChecks",lowerChecks);report.addProperty("nativeArmChecks",armChecks);report.addProperty("maximumWaistGapNativePixels",maximumWaistGap);report.addProperty("maximumWristFlexionDegrees",maximumWristFlexion);report.addProperty("maximumHipStepNativePixels",maximumHipStep);report.addProperty("maximumLowerWeightStep",maximumLowerWeightStep);report.add("flowScenarios",flowScenarios);report.add("firstPersonActions",RpgConfig.JSON.toJsonTree(firstPersonActions));report.add("modelProof",modelProof);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("serverTrace",serverTrace);report.add("actualDamageEvents",damageTrace);report.add("renderCapture",movie.summary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("rapier-fencing-review.json"),RpgConfig.JSON.toJson(report));System.out.println("RAPIER FENCING REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable reporting){reporting.printStackTrace();}game.stop();}
}

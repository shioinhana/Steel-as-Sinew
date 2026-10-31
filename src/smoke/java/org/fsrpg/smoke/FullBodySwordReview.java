package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
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

/** Same-JAR native-client acceptance. Only a named, marked copied world is changed.
 * Combat intents call the authoritative public server adapter; ordinary LocalPlayer
 * ticks and movement packets remain enabled throughout. No synthetic position packets,
 * per-tick fixture teleports, actor ticking or replacement damage are used. */
final class FullBodySwordReview {
    private enum Scene { STANDING_CHAIN, WALKING_CHAIN, STOP_AND_GUARD, DODGE_CANCEL,
        WALL_CONTACT, C_GUARD, X_MISS, X_CONTACT_RETURN, X_PUNISHED, HEAVY_PARTIAL, HEAVY_FULL, MELEE_CLASH,
        IMMUNE_TARGET, FIRST_PERSON_CHAIN, SPEAR_GUARD, SPEAR_THRUST }
    private static final List<String> CHAIN=List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust","rapier_lunge_finish");
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenes=new JsonArray(),serverTrace=new JsonArray(),clientTrace=new JsonArray(),damageTrace=new JsonArray(),shots=new JsonArray();
    private final AtomicInteger pendingShots=new AtomicInteger();
    private volatile Throwable failure;
    private volatile boolean setupReady,sceneReady,active,sceneDone,done;
    private volatile JsonObject serverSample=new JsonObject(),clientSample=new JsonObject();
    private volatile Scene scene=Scene.STANDING_CHAIN;
    private volatile String capture;
    private UUID playerId;
    private Mob target;
    private boolean initialized,recording,finishing,changedOptions,oldPause,oldVsync,oldHidden,oldActive;
    private CameraType oldCamera;
    private int warmTicks,localTicks,settleTicks,sceneIndex,oldFps,oldDistance,oldScale,nativeFrames,sceneFrames,lowerFrames;
    private long started,sceneStarted,firstSequence,lastQueued,clashesBefore,expectedContact,parryAt,fpBefore,itemsBefore;
    private boolean began,canceled,parried,returnStarted,seenActive,chargeReleased,shotCaptured;
    private Vec3 origin,cancelPosition;
    private double maxForward,maxBackward,serverClientMaxGap,lowerRange;
    private float targetHealth,playerHealth;
    private final Set<String> skillsSeen=new LinkedHashSet<>();
    private final Set<Long> damageSequences=new HashSet<>();
    private JsonObject row;
    private double previousServerZ;
    private double minimumFootZ,maximumFootZ;
    private final Map<String,Double> initialFootZ=new HashMap<>();
    private double footPoseChange;
    private double partialChargeDamage;
    private int authoredFrames;
    private long authoredSubmissionsBefore;
    private int movingTicks,backwardTicks;
    private KeyMapping parryKey,guardKey;
    private final Map<net.neoforged.neoforge.common.ModConfigSpec.DoubleValue,Double> oldCameraComposition=new LinkedHashMap<>();
    private final JsonArray motionSegments=new JsonArray();
    private SkillDefinition motionSkill;
    private long motionSequence;
    private int lastMotionElapsed;
    private double expectedChainDistance,configuredChainDistance;
    private double oldFovEffectScale,maximumSubmittedRootBackward;
    private int nativeMouseReleases,grabbedPointerRenderFrames;
    private Vec3 previousNativeRoot,previousSubmittedRoot,previousCameraRoot;
    private long previousRootWorldTick;
    private int actualOffsetFrames,sameEndpointRootPairs;
    private double sameEndpointRootTravel,maximumCameraRootDeltaError;

    FullBodySwordReview(){
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event)->isolateNativePointer());
        NeoForge.EVENT_BUS.addListener(this::clientTick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(RenderObservations::beginSubmittedFrame);
        NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::render);NeoForge.EVENT_BUS.addListener(this::damage);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e)->{if(!done)e.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key e)->{if(!done)KeyMapping.releaseAll();});
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(ViewportEvent.ComputeCameraAngles e)->{if(!done&&initialized&&Minecraft.getInstance().options.getCameraType()==CameraType.THIRD_PERSON_BACK){e.setPitch(16);e.setRoll(0);}});
    }
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("FULL BODY SWORD PASS "+message);}
    private void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void action(ServerPlayer p,String name,String value){rpg().action(p,name,value);}
    private void server(java.util.function.Consumer<ServerPlayer> task){var game=Minecraft.getInstance();game.getSingleplayerServer().execute(()->{try{task.accept(game.getSingleplayerServer().getPlayerList().getPlayer(playerId));}catch(Throwable error){failure=error;}});}
    private void monitor(){JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);require("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual game window left DISPLAY5");}
    private boolean spear(){return scene==Scene.SPEAR_GUARD||scene==Scene.SPEAR_THRUST;}
    private boolean chain(){return scene==Scene.STANDING_CHAIN||scene==Scene.WALKING_CHAIN||scene==Scene.FIRST_PERSON_CHAIN;}
    private boolean charge(){return scene==Scene.HEAVY_PARTIAL||scene==Scene.HEAVY_FULL;}
    private boolean incomingContact(){return scene==Scene.C_GUARD||scene==Scene.X_CONTACT_RETURN||scene==Scene.X_PUNISHED||scene==Scene.MELEE_CLASH;}
    private void isolateNativePointer(){if(done||!initialized)return;var mouse=Minecraft.getInstance().mouseHandler;if(mouse.isMouseGrabbed()){mouse.releaseMouse();nativeMouseReleases++;}mouse.setIgnoreFirstMove();}
    private JsonObject clientActor(){var source=ClientState.snapshot();var value=new JsonObject();for(String key:List.of("drawn","phase","phaseTicksRemaining","skillId","skill","skillElapsedTicks","guardElapsedTicks","invulnerable","stamina","maxStamina","spirit","maxSpirit","serverTick","swingSequence","weaponStyle","weaponMode","weaponEquipped","combo","guardMode","technique","charge","swordCombatEnabled","swordHeavyAttack","swordSuperArmor","swordParryWindow","swordReturnRemainingTicks","targetLock"))if(source.has(key))value.add(key,source.get(key).deepCopy());return value;}
    private long authoredSubmissions(){try{return ((Number)SkinnedArms.class.getMethod("authoredSwordLegSubmissions").invoke(null)).longValue();}catch(ReflectiveOperationException error){throw new IllegalStateException("Actual authored skin leg submission counter is required",error);}}
    private void temporaryCameraOption(String name,double value)throws ReflectiveOperationException{var field=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField(name);field.setAccessible(true);var option=(net.neoforged.neoforge.common.ModConfigSpec.DoubleValue)field.get(null);oldCameraComposition.put(option,option.get());option.set(value);}
    private void initialize(Minecraft game)throws Exception{
        monitor();String name=System.getProperty("fsrpg.fullBodySwordWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.startsWith("FSRPG Full Body Sword Review ")&&world.getFileName().toString().equals(name)&&Files.isRegularFile(world.resolve(".fsrpg-full-body-sword-test-world")),"Only the explicitly named and marked copied world can be changed");
        Files.createDirectories(root.resolve("screenshots"));playerId=game.player.getUUID();started=System.nanoTime();
        oldFps=game.options.framerateLimit().get();oldDistance=game.options.renderDistance().get();oldScale=game.options.guiScale().get();oldFovEffectScale=game.options.fovEffectScale().get();oldPause=game.options.pauseOnLostFocus;oldVsync=game.options.enableVsync().get();oldHidden=game.options.hideGui;oldActive=game.isWindowActive();oldCamera=game.options.getCameraType();changedOptions=true;
        game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.fovEffectScale().set(0.0);game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);game.mouseHandler.releaseMouse();game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();
        temporaryCameraOption("CAMERA_DISTANCE",5.5);temporaryCameraOption("CAMERA_HEIGHT",.05);temporaryCameraOption("SHOULDER",.65);
        for(var key:game.options.keyMappings){if(key.getName().equals("key.fsrpg.guard"))guardKey=key;if(key.getName().toLowerCase(Locale.ROOT).contains("parry")&&key.getName().startsWith("key.fsrpg."))parryKey=key;}
        check(guardKey!=null,"Existing C defense key mapping remains registered");check(parryKey!=null,"Dedicated X parry key mapping is registered");
        check(parryKey.getDefaultKey().getValue()==GLFW.GLFW_KEY_X,"Dedicated parry defaults to X without replacing C");
        JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);var b=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1280,800);GLFW.glfwSetWindowPos(handle,b.get("x").getAsInt()+35,b.get("y").getAsInt()+50);GLFW.glfwSetWindowTitle(handle,"FSRPG 0.12.0 full body sword acceptance - DISPLAY5");game.resizeDisplay();
        for(String model:List.of("rapier_fullbody.fsvoxel","rapier_fullbody_fp.fsvoxel")){Path configured=rpg().config().directory().resolve("models").resolve(model);try(var source=RpgServer.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/"+model)){require(source!=null,"Missing packaged full-body model "+model);check(Files.isRegularFile(configured)&&Arrays.equals(source.readAllBytes(),Files.readAllBytes(configured)),"Runtime uses exact same-JAR full-body model "+model);}}
        initialized=true;server(p->{
            p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(false);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.setExperienceLevels(0);p.getFoodData().setFoodLevel(18);p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);p.setHealth(200);p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);p.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
            for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(48),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
            for(int x=-12;x<=12;x++)for(int z=-8;z<=20;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            fixtureProgress();setupReady=true;
        });
    }
    private void fixtureProgress(){var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());for(String name:List.of("mind","body","footwork")){var points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);var learned=new JsonArray();learned.add("rapier");one.add("learnedNodes",learned);one.add("creativeNodes",new JsonArray());service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));}
    private void prepare(Minecraft game){
        KeyMapping.releaseAll();CombatCamera.reset();game.options.setCameraType(scene==Scene.FIRST_PERSON_CHAIN?CameraType.FIRST_PERSON:CameraType.THIRD_PERSON_BACK);game.player.setYRot(0);game.player.setXRot(0);game.player.yRotO=game.player.xRotO=0;
        sceneReady=active=sceneDone=false;shotCaptured=false;authoredFrames=0;authoredSubmissionsBefore=authoredSubmissions();settleTicks=sceneFrames=lowerFrames=0;lowerRange=footPoseChange=0;initialFootZ.clear();minimumFootZ=Double.POSITIVE_INFINITY;maximumFootZ=Double.NEGATIVE_INFINITY;fpBefore=LegacyFirstPersonAnimations.samples();itemsBefore=OfficialHeldItems.submissions();
        previousNativeRoot=previousSubmittedRoot=previousCameraRoot=null;previousRootWorldTick=Long.MIN_VALUE;actualOffsetFrames=sameEndpointRootPairs=0;sameEndpointRootTravel=maximumCameraRootDeltaError=maximumSubmittedRootBackward=0;
        server(p->{
            if(target!=null){rpg().creatureMelee().cancel(target);target.discard();target=null;}
            action(p,"guard","false");actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,spear()?new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:iron_spear"))):new ItemStack(Items.IRON_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);action(p,"status","");if(actor(p).phase()==CombatPhase.SHEATHED)action(p,"draw","");actor(p).setResources(actor(p).maxStamina(),100);p.setHealth(200);p.invulnerableTime=0;p.hurtTime=0;p.setInvulnerable(false);
            for(int x=-2;x<=2;x++)for(int y=80;y<=82;y++)p.level().setBlock(new BlockPos(x,y,2),scene==Scene.WALL_CONTACT?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            p.connection.teleport(.5,80,scene==Scene.WALL_CONTACT?1.65:.5,0,0);p.setDeltaMovement(Vec3.ZERO);
            if(scene==Scene.C_GUARD||scene==Scene.X_CONTACT_RETURN||scene==Scene.X_PUNISHED||charge()||scene==Scene.MELEE_CLASH||scene==Scene.IMMUNE_TARGET||scene==Scene.WALL_CONTACT){
                target=(Mob)(scene==Scene.X_CONTACT_RETURN?EntityType.VINDICATOR:charge()||scene==Scene.IMMUNE_TARGET?EntityType.HUSK:EntityType.DROWNED).create(p.level(),EntitySpawnReason.COMMAND);require(target!=null,"Native fixture entity creation failed");target.setPos(.5,80,scene==Scene.WALL_CONTACT?3.5:incomingContact()?1.7:2.1);target.setNoAi(true);target.setNoGravity(true);target.setPersistenceRequired();target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);target.setHealth(200);target.getAttribute(Attributes.ARMOR).setBaseValue(0);target.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(6);target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);target.setYRot(180);target.yBodyRot=180;target.setTarget(p);target.setInvulnerable(scene==Scene.IMMUNE_TARGET);if(target.getType()==EntityType.DROWNED)target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));require(p.level().noCollision(target,target.getBoundingBox()),"Native target fixture must not overlap solid blocks or another entity");p.level().addFreshEntity(target);if(incomingContact())check(target.isWithinMeleeAttackRange(p)&&target.hasLineOfSight(p),"Native incoming fixture is genuinely inside vanilla melee reach and line of sight: "+scene);
            }
            row=new JsonObject();row.addProperty("id",scene.name().toLowerCase(Locale.ROOT));row.addProperty("combatIntentSource","RpgServer.action; normal LocalPlayer movement packets remain enabled");skillsSeen.clear();damageSequences.clear();firstSequence=lastQueued=0;began=canceled=parried=returnStarted=seenActive=chargeReleased=false;maxForward=maxBackward=serverClientMaxGap=0;movingTicks=backwardTicks=0;cancelPosition=null;clashesBefore=rpg().meleeClashes();sceneReady=true;
            motionSegments.asList().clear();motionSkill=null;motionSequence=0;lastMotionElapsed=0;expectedChainDistance=configuredChainDistance=0;
        });
    }
    private void clientTick(ClientTickEvent.Post event){if(done)return;try{
        if(failure!=null)throw new AssertionError("Server/render acceptance failed",failure);var game=Minecraft.getInstance();if(game.player==null||game.level==null||game.getSingleplayerServer()==null||rpg()==null)return;
        if(!initialized){if(game.screen!=null||game.getOverlay()!=null)return;initialize(game);return;}isolateNativePointer();require(System.nanoTime()-started<240_000_000_000L,"Full-body acceptance exceeded four minutes");
        localTicks++;var client=new JsonObject();client.addProperty("clientTick",game.player.tickCount);client.addProperty("scene",scene.name());client.addProperty("x",game.player.getX());client.addProperty("y",game.player.getY());client.addProperty("z",game.player.getZ());client.addProperty("yaw",game.player.getYRot());client.addProperty("onGround",game.player.onGround());client.addProperty("verticalVelocity",game.player.getDeltaMovement().y);client.addProperty("mouseGrabbed",game.mouseHandler.isMouseGrabbed());client.addProperty("phase",ClientState.text(ClientState.snapshot(),"phase",""));client.add("actor",clientActor());clientSample=client;if(recording)clientTrace.add(client.deepCopy());
        if(!setupReady)return;if(warmTicks++<60)return;
        if(finishing){if(movie.finish()&&pendingShots.get()==0){validate();finish(null);}return;}
        if(!sceneReady){if(settleTicks==0){settleTicks=-1;prepare(game);settleTicks=-1;}return;}
        if(!active){++settleTicks;if(settleTicks==12){game.player.setYRot(0);game.player.setXRot(0);game.player.yRotO=game.player.xRotO=0;CombatCamera.reset();}if(settleTicks<28)return;require(Math.abs(game.player.getYRot())<.1,"Native camera/player yaw must settle before testing travel: playerYaw="+game.player.getYRot()+", cameraYaw="+CombatCamera.yaw()+", pitch="+game.player.getXRot());movie.start(scene.name().toLowerCase(Locale.ROOT).replace('_','-'),"Real server combat plus unmodified local player tick/movement packets",this::observation);recording=true;active=true;return;}
        game.options.keyUp.setDown(scene==Scene.WALKING_CHAIN&&!sceneDone); // Normal controls, never a synthetic movement packet.
        if(sceneDone){game.options.keyUp.setDown(false);movie.stop();recording=false;require(sceneFrames>=8,"Every scenario must include actual submitted render frames");if(scene==Scene.FIRST_PERSON_CHAIN){check(LegacyFirstPersonAnimations.samples()>fpBefore,"Real first-person chain uses the production first-person sampler");}else{check(lowerFrames>5,"Actual submitted full-body lower pose is available: "+scene);check(OfficialHeldItems.submissions()>itemsBefore,"Actual ItemStack remains submitted by the official renderer: "+scene);}require(shotCaptured,"Scenario must capture its real required phase, never substitute READY: "+scene);if(!spear()&&scene!=Scene.FIRST_PERSON_CHAIN)check(authoredFrames>0&&authoredSubmissions()>authoredSubmissionsBefore,"Actual skin leg submission follows the authored thigh/shin/foot path: "+scene);row.addProperty("authoredSkinFrames",authoredFrames);row.addProperty("authoredSkinSubmissions",authoredSubmissions()-authoredSubmissionsBefore);if(scene==Scene.STANDING_CHAIN){check(footPoseChange>.05,"Stationary five-stage chain changes actual submitted foot placement");validateActualRoot();}row.addProperty("nativeFrames",sceneFrames);row.addProperty("lowerBodyFrames",lowerFrames);row.addProperty("footZRangePixels",lowerFrames==0?0:maximumFootZ-minimumFootZ);row.addProperty("footPoseChangePixels",footPoseChange);row.addProperty("maximumAbsoluteFootZPixels",lowerRange);scenes.add(row.deepCopy());if(++sceneIndex==Scene.values().length){finishing=true;return;}scene=Scene.values()[sceneIndex];sceneReady=active=false;settleTicks=0;}
    }catch(Throwable error){finish(error);}}
    private void begin(ServerPlayer p,long now){
        began=true;sceneStarted=now;origin=p.position();previousServerZ=p.getZ();playerHealth=p.getHealth();targetHealth=target==null?0:target.getHealth();row.addProperty("startZ",p.getZ());row.addProperty("startTick",now);
        if(chain()){for(int i=0;i<CHAIN.size();i++){var skill=rpg().config().current().skills().get(CHAIN.get(i));int handoff=i<CHAIN.size()-1&&actor(p).style().cancels().recoveryAttack()?skill.windupTicks()+skill.activeTicks():skill.durationTicks();configuredChainDistance+=actor(p).rules().swordCombat().motionDistances().getOrDefault(skill.id(),0.0)*SwordMotionPlan.progress(handoff,skill);}row.addProperty("configuredChainTravelMeters",configuredChainDistance);}
        if(scene==Scene.C_GUARD||scene==Scene.SPEAR_GUARD){action(p,"guard","true");check(actor(p).phase()==CombatPhase.GUARDING,"C enters ordinary GUARDING: "+scene);if(scene==Scene.C_GUARD){check(!target.doHurtTarget(p.level(),p),"Real native attack begins against ordinary C defense");require(rpg().creatureMelee().snapshot(target)!=null,"C defense requires a real accepted native attack clock");}}
        else if(scene==Scene.X_MISS){action(p,"parry","");check(actor(p).phase().name().equals("PARRYING"),"X uses its own PARRYING state");}
        else if(scene==Scene.X_CONTACT_RETURN||scene==Scene.X_PUNISHED||scene==Scene.MELEE_CLASH){check(!target.doHurtTarget(p.level(),p),"Native attacker starts authored windup before contact: "+scene);var attack=rpg().creatureMelee().snapshot(target);require(attack!=null,"Native attack must expose its actual contact clock");expectedContact=attack.contactGameTick();parryAt=expectedContact-actor(p).rules().swordCombat().parryWindupTicks()-1;}
        else if(charge()){action(p,"rapierCharge","true");check(actor(p).chargeSnapshot().holding(),"Actual right-button charge remains held before release: "+scene);firstSequence=actor(p).snapshot().swingSequence();}else startAttack(p);
    }
    private void startAttack(ServerPlayer p){long before=actor(p).snapshot().swingSequence();action(p,"attack","left");check(actor(p).snapshot().swingSequence()>before&&actor(p).currentSkill()!=null,"Real attack intent starts from legal stance: "+scene);if(firstSequence==0)firstSequence=actor(p).snapshot().swingSequence();}
    private void observeChainMotion(CombatActor actor){
        var current=actor.currentSkill();long sequence=actor.snapshot().swingSequence();
        if(motionSkill!=null&&(current==null||sequence!=motionSequence)){
            int terminalElapsed=lastMotionElapsed+1;double progress=SwordMotionPlan.progress(terminalElapsed,motionSkill),distance=actor.rules().swordCombat().motionDistances().getOrDefault(motionSkill.id(),0.0),expected=distance*progress;
            var segment=new JsonObject();segment.addProperty("skill",motionSkill.id());segment.addProperty("sequence",motionSequence);segment.addProperty("lastObservedElapsedTicks",lastMotionElapsed);segment.addProperty("actualHandoffElapsedTicks",terminalElapsed);segment.addProperty("configuredDistanceMeters",distance);segment.addProperty("expectedProgressAtHandoff",progress);segment.addProperty("expectedTravelMeters",expected);motionSegments.add(segment);expectedChainDistance+=expected;motionSkill=null;
        }
        if(current!=null){if(motionSkill==null){motionSkill=current;motionSequence=sequence;lastMotionElapsed=0;}lastMotionElapsed=Math.max(lastMotionElapsed,actor.snapshot().skillElapsedTicks());}
    }
    private void serverTick(ServerTickEvent.Post event){if(!active||sceneDone||done)return;try{
        ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;var a=actor(p);long now=p.level().getGameTime();if(!began){if(a.phase()!=CombatPhase.READY)return;begin(p,now);}long t=now-sceneStarted;
        require(t<180,"Scenario did not finish: "+scene);double travel=p.getZ()-origin.z;maxForward=Math.max(maxForward,travel);maxBackward=Math.min(maxBackward,travel);double step=p.getZ()-previousServerZ;if(step>.002)movingTicks++;if(step<-.02)backwardTicks++;previousServerZ=p.getZ();
        if(a.currentSkill()!=null)skillsSeen.add(a.currentSkill().id());if(a.phase()==CombatPhase.ACTIVE)seenActive=true;
        if(chain())observeChainMotion(a);
        if(chain()&&a.currentSkill()!=null&&a.snapshot().swingSequence()-firstSequence<4&&lastQueued!=a.snapshot().swingSequence()){action(p,"attack","left");require(a.comboSnapshot().queued(),"Natural five-stage chain must accept relaxed next input");lastQueued=a.snapshot().swingSequence();}
        if((scene==Scene.STOP_AND_GUARD||scene==Scene.DODGE_CANCEL)&&!canceled&&t>=1){action(p,scene==Scene.STOP_AND_GUARD?"guard":"dodge",scene==Scene.STOP_AND_GUARD?"true":"");check(a.phase()==(scene==Scene.STOP_AND_GUARD?CombatPhase.GUARDING:CombatPhase.DODGING),"Accepted cancellation replaces the attack state: "+scene);canceled=true;}
        if(scene==Scene.STOP_AND_GUARD&&canceled){action(p,"guard","held");if(t==8)cancelPosition=p.position();if(t>8)require(p.position().distanceTo(cancelPosition)<.025,"Guard cancellation must not leave delayed sword displacement");}
        if(scene==Scene.C_GUARD||scene==Scene.SPEAR_GUARD){if(t<20){action(p,"guard","held");require(a.phase()==CombatPhase.GUARDING,"Held C must not become X parry");}if(t==20)action(p,"guard","false");}
        if((scene==Scene.X_CONTACT_RETURN||scene==Scene.X_PUNISHED)&&!parried&&now>=parryAt){action(p,"parry","");check(a.phase().name().equals("PARRYING"),"X accepted immediately before native contact");parried=true;}
        if(scene==Scene.X_CONTACT_RETURN&&parried&&!returnStarted&&now>expectedContact){require(p.getHealth()==playerHealth,"Successful X must prevent the incoming native contact damage");action(p,"attack","left");check(a.currentSkill()!=null&&a.currentSkill().id().equals("sword_parry_return"),"Successful X exposes the real sword_parry_return branch");returnStarted=true;}
        if(scene==Scene.X_PUNISHED&&parried&&now>expectedContact){require(p.getHealth()<playerHealth,"An ordinary native attack must damage the player through X");require(a.swordReturnRemainingTicks()==0,"Ordinary attack cannot award the heavy-parry return branch");}
        if(charge()&&!chargeReleased){
            require(p.position().distanceTo(origin)<.025,"Held charge cannot consume its release displacement early");require(target.getHealth()==targetHealth,"Held charge has no active damage before release");
            int releaseAt=scene==Scene.HEAVY_PARTIAL?3:rpg().config().current().skillTrees().rules().rapier().fullChargeTicks()+3;
            if(t>=releaseAt){boolean full=a.chargeSnapshot().fullyCharged();check(full==(scene==Scene.HEAVY_FULL),"Actual held-charge clock distinguishes full and partial charge: "+scene);action(p,"rapierCharge","false");chargeReleased=true;check(a.swordHeavyAttack()==full,"Only a fully charged release is classified as heavy: "+scene);row.addProperty("fullChargeAtRelease",full);row.addProperty("releaseTick",now);}
        }
        if(scene==Scene.MELEE_CLASH&&firstSequence==0){var cue=rpg().creatureMelee().guardCueSnapshot(target);require(cue!=null,"Native exchange retains a cue");int windup=rpg().config().current().skills().get("sword_left").windupTicks();if(now>=Math.max(sceneStarted,cue.openGameTick()-windup))startAttack(p);}
        if(scene==Scene.WALL_CONTACT){require(p.getZ()<=1.701,"Actual player AABB must not pass the front wall");require(target.getHealth()==targetHealth,"Attack must not damage a native entity behind the wall");}
        if(scene==Scene.IMMUNE_TARGET)require(target.getHealth()==targetHealth,"Native invulnerable target remains immune");
        var sample=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();sample.addProperty("scenario",scene.name());sample.addProperty("worldGameTick",now);sample.addProperty("x",p.getX());sample.addProperty("y",p.getY());sample.addProperty("z",p.getZ());sample.addProperty("onGround",p.onGround());sample.addProperty("verticalVelocity",p.getDeltaMovement().y);sample.addProperty("deltaZ",step);sample.addProperty("actualMovementZ",p.getDeltaMovement().z);sample.addProperty("playerHealth",p.getHealth());sample.addProperty("targetHealth",target==null?0:target.getHealth());if(target!=null){sample.addProperty("nativeTargetWithinMeleeReach",target.isWithinMeleeAttackRange(p));sample.addProperty("nativeTargetHasLineOfSight",target.hasLineOfSight(p));sample.add("nativeAttackerClock",RpgConfig.JSON.toJsonTree(rpg().creatureMelee().snapshot(target)));}sample.add("charge",RpgConfig.JSON.toJsonTree(a.chargeSnapshot()));sample.addProperty("swordHeavyAttack",a.swordHeavyAttack());sample.addProperty("swordParryWindow",a.swordParryWindow());sample.addProperty("normalClientMovementPacketsEnabled",true);sample.addProperty("meleeClashes",rpg().meleeClashes());sample.add("latestClient",clientSample.deepCopy());serverSample=sample;serverTrace.add(sample.deepCopy());
        serverClientMaxGap=Math.max(serverClientMaxGap,Math.abs(p.getZ()-ClientState.number(clientSample,"z",p.getZ())));
        boolean ended=t>=60&&(a.phase()==CombatPhase.READY||scene==Scene.STOP_AND_GUARD&&a.phase()==CombatPhase.GUARDING);
        if(ended){
            if(chain()){check(skillsSeen.containsAll(CHAIN),"All five authored stages ran through real server state: "+scene);check(seenActive,"Chain reached actual ACTIVE frames: "+scene);require(motionSegments.size()==CHAIN.size()&&Math.abs(expectedChainDistance-configuredChainDistance)<1e-6,"Observed five-stage handoffs must match the configured recovery-cancel timing");row.add("motionSegments",motionSegments.deepCopy());row.addProperty("expectedChainTravelMeters",expectedChainDistance);if(scene==Scene.STANDING_CHAIN){double actual=p.getZ()-origin.z,tolerance=Math.max(.03,expectedChainDistance*.02);check(Math.abs(actual-expectedChainDistance)<=tolerance&&movingTicks>=CHAIN.size()*2,"Standing chain retains its configured per-stage displacement after ordinary client packets: actual="+actual+", expected="+expectedChainDistance+", tolerance="+tolerance);row.addProperty("retainedTravelFraction",actual/expectedChainDistance);}}
            if(scene==Scene.C_GUARD)check(p.getHealth()<playerHealth&&p.getHealth()>playerHealth-6,"Ordinary C mitigates native damage without granting X immunity");if(scene==Scene.MELEE_CLASH)check(rpg().meleeClashes()>clashesBefore,"Real opposing attacks produced a native melee exchange");
            if(scene==Scene.X_CONTACT_RETURN)check(returnStarted,"Actual native contact led to the dedicated return attack");
            if(scene==Scene.X_PUNISHED)check(p.getHealth()<playerHealth&&!skillsSeen.contains("sword_parry_return"),"Ordinary native strike punishes X and grants no special return");
            if(charge()){double damage=targetHealth-target.getHealth();check(chargeReleased&&damage>0,"Released charged thrust makes real native contact: "+scene);row.addProperty("nativeDamage",damage);if(scene==Scene.HEAVY_PARTIAL)partialChargeDamage=damage;else check(partialChargeDamage>0&&Math.abs(damage-partialChargeDamage*a.rules().swordCombat().chargedDamageMultiplier())<.01,"Fully charged native damage equals the measured partial strike times the configured heavy multiplier");}
            if(scene==Scene.X_MISS)check(!skillsSeen.contains("sword_parry_return"),"Unsuccessful X grants no return attack");
            if(scene==Scene.WALL_CONTACT)check(maxForward<.06,"Wall collision limits actual authored displacement");
            if(scene==Scene.IMMUNE_TARGET)check(target.getHealth()==targetHealth&&a.phase()==CombatPhase.READY,"Immune hit completes naturally without damage or stuck motion");
            if(scene==Scene.SPEAR_THRUST)check(skillsSeen.contains("spear_thrust"),"Spear keeps its original authoritative thrust");
            row.addProperty("maxForwardMeters",maxForward);row.addProperty("minForwardMeters",maxBackward);row.addProperty("endZ",p.getZ());row.addProperty("movingTicks",movingTicks);row.addProperty("backwardTicks",backwardTicks);row.addProperty("maxClientServerPositionGap",serverClientMaxGap);row.add("skills",RpgConfig.JSON.toJsonTree(skillsSeen));row.addProperty("passed",true);action(p,"guard","false");sceneDone=true;
        }
    }catch(Throwable error){failure=error;}}
    private JsonObject observation(){JsonObject value=new JsonObject();value.addProperty("scenario",scene.name());value.add("server",serverSample.deepCopy());value.add("client",clientSample.deepCopy());if(scene!=Scene.FIRST_PERSON_CHAIN){var pose=RenderObservations.submittedSampler(Minecraft.getInstance().player).get();value.add("pose",pose);if(pose.has("rig"))value.add("rig",pose.get("rig").deepCopy());}else{value.addProperty("firstPersonSamples",LegacyFirstPersonAnimations.samples());value.addProperty("firstPersonOfficialItemSubmissions",LegacyFirstPersonAnimations.itemSubmissions());}return value;}
    private static Vec3 point(JsonObject value,String field){var xyz=value.getAsJsonArray(field);return new Vec3(xyz.get(0).getAsDouble(),xyz.get(1).getAsDouble(),xyz.get(2).getAsDouble());}
    private void observeActualRoot(JsonObject pose){
        Vec3 nativeRoot=point(pose,"nativeActorRenderPositionMeters"),submittedRoot=point(pose,"submittedRenderStatePositionMeters"),cameraRoot=point(pose,"submittedCameraPositionMeters");long tick=Minecraft.getInstance().level.getGameTime();
        if(previousSubmittedRoot!=null){double backward=previousSubmittedRoot.z-submittedRoot.z;maximumSubmittedRootBackward=Math.max(maximumSubmittedRootBackward,backward);require(backward<=.002,"Standing submitted root must never backtrack after packet delivery: previous="+previousSubmittedRoot.z+", current="+submittedRoot.z+", native="+nativeRoot.z+", backward="+backward);}
        if(submittedRoot.distanceTo(nativeRoot)>1e-5)actualOffsetFrames++;
        if(previousNativeRoot!=null&&nativeRoot.distanceTo(previousNativeRoot)<1e-7){
            Vec3 rootDelta=submittedRoot.subtract(previousSubmittedRoot),cameraDelta=cameraRoot.subtract(previousCameraRoot);
            if(rootDelta.length()>1e-5){sameEndpointRootPairs++;sameEndpointRootTravel+=rootDelta.length();double error=rootDelta.subtract(cameraDelta).length();maximumCameraRootDeltaError=Math.max(maximumCameraRootDeltaError,error);require(error<.003,"Submitted avatar and actual camera must share the same sub-tick root displacement: delta error="+error);}
        }
        previousNativeRoot=nativeRoot;previousSubmittedRoot=submittedRoot;previousCameraRoot=cameraRoot;previousRootWorldTick=tick;
    }
    private void validateActualRoot(){check(actualOffsetFrames>=8&&sameEndpointRootPairs>=8&&sameEndpointRootTravel>.1,"Real submitted avatar moves continuously between adjacent rendered frames with the same native packet endpoint");row.addProperty("actualRootOffsetFrames",actualOffsetFrames);row.addProperty("sameNativeEndpointRootMovementPairs",sameEndpointRootPairs);row.addProperty("sameNativeEndpointRootTravelMeters",sameEndpointRootTravel);row.addProperty("maximumCameraAvatarDeltaErrorMeters",maximumCameraRootDeltaError);row.addProperty("maximumSubmittedRootBackwardMeters",maximumSubmittedRootBackward);}
    private boolean shotReady(JsonObject pose) {
        JsonObject binding=pose==null?null:pose.getAsJsonObject("renderAnimation");
        String phase=binding==null?ClientState.text(ClientState.snapshot(),"phase",""):ClientState.text(binding,"phase","");
        return switch(scene){
            case STOP_AND_GUARD,C_GUARD,SPEAR_GUARD -> phase.equals("GUARDING");
            case DODGE_CANCEL -> phase.equals("DODGING");
            case X_MISS,X_CONTACT_RETURN -> phase.equals("PARRYING");
            case X_PUNISHED -> phase.equals("STUNNED")&&ClientState.number(serverSample,"playerHealth",playerHealth)<playerHealth;
            case HEAVY_FULL -> phase.equals("WINDUP")&&binding!=null&&ClientState.text(binding,"action","").equals("rapier_full_charge")&&serverSample.has("charge")&&ClientState.bool(serverSample.getAsJsonObject("charge"),"fullyCharged",false);
            default -> phase.equals("ACTIVE");
        };
    }
    private void render(RenderFrameEvent.Post event){
        if(done||!initialized)return;
        try{
            nativeFrames++;if(!recording)return;monitor();if(Minecraft.getInstance().mouseHandler.isMouseGrabbed())grabbedPointerRenderFrames++;require(grabbedPointerRenderFrames==0,"Native test window must never capture the OS pointer during recorded gameplay");sceneFrames++;movie.frame(event);movie.assertHealthy();JsonObject pose=null;
            if(scene!=Scene.FIRST_PERSON_CHAIN){
                pose=RenderObservations.submittedSampler(Minecraft.getInstance().player).get();
                if(scene==Scene.STANDING_CHAIN)observeActualRoot(pose);
                if(ClientState.bool(pose,"authoredSwordSkinLegs",false))authoredFrames++;
                var lower=pose.getAsJsonObject("finalLowerBody");
                if(lower!=null){lowerFrames++;for(String side:List.of("left","right")){
                    var leg=lower.getAsJsonObject(side);var sole=leg.getAsJsonArray("soleCenter");
                    if(ClientState.bool(pose,"authoredSwordSkinLegs",false))require(ClientState.number(leg,"maximumWaistSeamGapPixels",Double.POSITIVE_INFINITY)<.005,"Actual submitted skin leg cap must stay joined to the authored body waist");
                    for(var n:sole)require(Double.isFinite(n.getAsDouble()),"Actual rendered foot contains non-finite coordinates");
                    double z=sole.get(2).getAsDouble();initialFootZ.putIfAbsent(side,z);footPoseChange=Math.max(footPoseChange,Math.abs(z-initialFootZ.get(side)));minimumFootZ=Math.min(minimumFootZ,z);maximumFootZ=Math.max(maximumFootZ,z);lowerRange=Math.max(lowerRange,Math.abs(z));
                }}
            }
            if(!shotCaptured&&shotReady(pose)){
                shotCaptured=true;String name=String.format("%02d-%s",sceneIndex,scene.name().toLowerCase(Locale.ROOT));pendingShots.incrementAndGet();
                Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->pendingShots.decrementAndGet());
                var shot=new JsonObject();shot.addProperty("scenario",scene.name());shot.addProperty("file","screenshots/"+name+".png");shot.addProperty("nativeFrame",nativeFrames);shot.addProperty("requiredStateActuallyObserved",true);shot.add("server",serverSample.deepCopy());shot.add("client",clientSample.deepCopy());if(pose!=null)shot.add("pose",pose.deepCopy());shots.add(shot);
            }
        }catch(Throwable error){failure=error;}
    }
    private void damage(LivingDamageEvent.Post event){if(done||!active||event.getNewDamage()<=0)return;if(event.getEntity()!=target&&(!(event.getEntity() instanceof ServerPlayer p)||!p.getUUID().equals(playerId)))return;try{var row=new JsonObject();row.addProperty("scene",scene.name());row.addProperty("victim",event.getEntity().getUUID().toString());row.addProperty("amount",event.getNewDamage());row.addProperty("tick",event.getEntity().level().getGameTime());var source=event.getSource();row.addProperty("damageSource",source.getMsgId());row.addProperty("sourceUuid",source.getEntity()==null?null:source.getEntity().getUUID().toString());row.addProperty("directSourceUuid",source.getDirectEntity()==null?null:source.getDirectEntity().getUUID().toString());row.addProperty("sourceEntityType",source.getEntity()==null?null:BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).toString());row.addProperty("victimOverlapsSolid",!event.getEntity().level().noCollision(event.getEntity(),event.getEntity().getBoundingBox()));damageTrace.add(row);if(source.getEntity() instanceof ServerPlayer p&&p.getUUID().equals(playerId)){var a=actor(p);row.add("actor",RpgConfig.JSON.toJsonTree(a.snapshot()));require(a.phase()==CombatPhase.ACTIVE,"Native outgoing damage must occur only during actual ACTIVE");require(damageSequences.add(a.snapshot().swingSequence()),"One authored sword swing must not damage the same fixture twice");}}catch(Throwable error){failure=error;}}
    private void validate()throws Exception{check(scenes.size()==Scene.values().length,"Every required full-body, defense, cancel, wall, clash, immunity and spear scene passed");check(shots.size()==Scene.values().length,"Every scenario has a native screenshot");for(var shot:shots)check(Files.size(root.resolve(shot.getAsJsonObject().get("file").getAsString()))>1000,"Native scenario screenshot exists: "+shot.getAsJsonObject().get("scenario"));for(var item:movie.summary().getAsJsonArray("clips")){var clip=item.getAsJsonObject();check(clip.get("sourceFps").getAsDouble()>=30&&clip.get("capturedFrames").getAsInt()==clip.get("writtenFrames").getAsInt(),"Native clip is fully written at >=30 Hz: "+clip.get("id"));}check(serverTrace.size()>100&&clientTrace.size()>100,"Independent real server and normal client movement traces were captured");}
    private void finish(Throwable error){if(done)return;done=true;if(recording)movie.stop();try{var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("version","0.12.0");report.addProperty("world",System.getProperty("fsrpg.fullBodySwordWorld"));report.addProperty("checks",checks.size());report.add("checksPassed",RpgConfig.JSON.toJsonTree(checks));report.add("scenes",scenes);report.add("screenshots",shots);report.add("serverTrace",serverTrace);report.add("clientTrace",clientTrace);report.add("damageTrace",damageTrace);report.addProperty("nativeFrames",nativeFrames);report.addProperty("osPointerCaptured",grabbedPointerRenderFrames>0);report.addProperty("grabbedPointerRenderFrames",grabbedPointerRenderFrames);report.addProperty("nativeMouseReleaseCount",nativeMouseReleases);report.addProperty("pointerIsolation","Wait for native loading screen/overlay to close, then MouseHandler.releaseMouse at client Pre/Post; native player ticks and movement packets untouched");try{report.add("renderCapture",movie.summary());}catch(Throwable captureError){report.addProperty("captureError",captureError.toString());report.addProperty("passed",false);if(error==null)error=captureError;else error.addSuppressed(captureError);}RpgSmoke.windowEnvironment(report);var jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");Files.createDirectories(root);RpgConfig.atomicWrite(root.resolve("full-body-sword-review.json"),RpgConfig.JSON.toJson(report));System.out.println("FULL BODY SWORD REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable report){report.printStackTrace();}
        var game=Minecraft.getInstance();KeyMapping.releaseAll();if(changedOptions){for(var option:oldCameraComposition.entrySet())option.getKey().set(option.getValue());CombatCamera.reset();game.options.setCameraType(oldCamera);game.options.framerateLimit().set(oldFps);game.options.renderDistance().set(oldDistance);game.options.guiScale().set(oldScale);game.options.fovEffectScale().set(oldFovEffectScale);game.options.pauseOnLostFocus=oldPause;game.options.enableVsync().set(oldVsync);game.options.hideGui=oldHidden;game.setWindowActive(oldActive);}game.stop();}
}

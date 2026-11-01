package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.fsrpg.progression.ProgressionService;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Native player submission and real server selection/air/shoulder integration, with prewarmed assets. */
final class ShoulderCombatReview {
    private enum Scenario { EQUIP_SHEATHED, SELECT_HUSK, KEEP_PASSIVE, R_UNLOCK_SHEATH, SOURCE_COMBO,
        AIR_RAPIER, AIR_DUAL, AIR_SPEAR, AIR_BOW, AIR_ATTACK, DUAL_ARMOR, CAMERA_ONLY }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray(),trace=new JsonArray(),cameraTransitions=new JsonArray();
    private final Object reportLock=new Object();
    private final List<Mob> targets=new ArrayList<>();
    private final Map<String,Field> fields=new HashMap<>();
    private final Map<Scenario,Visual> visuals=new EnumMap<>(Scenario.class);
    private volatile Scenario scenario=Scenario.EQUIP_SHEATHED;
    private volatile Throwable failure;
    private volatile boolean ready,scriptDone,recording,jumpInput;
    private volatile JsonObject serverObservation=new JsonObject();
    private volatile long elapsed;
    private volatile boolean cameraOnly;
    private volatile double cameraBlend;
    private volatile int drawRequests;
    private volatile int clientSelectionTargetId=-1;
    private volatile String clientSelectionTargetUuid="";
    private volatile boolean clientSelectionRequested,clientSelectionClicked,clientSelectionVerified,unlockedMouseChecked,unlockedServerFacingObserved;
    private volatile double unlockedMouseYaw;
    private volatile JsonObject clientSelectionProof=new JsonObject();
    private JsonObject selectionProof;
    private int clientAimTicks,lockedOrbitNativeFrames;
    private double cameraTransitionSeconds,lastCameraBlend=Double.NaN;
    private long lastCameraNano,transitionFirstNano;
    private int transitionDirection,transitionIntermediateFrames,transitionMiddleFrames;
    private double transitionMinEye,transitionMaxEye;
    private int appliedDraw,step,prewarmedModels;
    private boolean rQueued,sawAir,attackQueued;
    private long lastAppliedModels;
    private final Set<String> nativeSkinModels=new TreeSet<>();
    private long entered,readyAt;
    private Mob selected;
    private final List<String> startedSkills=new ArrayList<>();
    private Method legacySample;
    private double breathSpeed;
    private volatile boolean initialized,done,begun;
    private boolean prepared,stopped;
    private int warmup,prewarmTicks,index,previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private UUID playerId;
    private long started,caseStarted,sequence,lastSequence,officialMainBefore,officialOffBefore;
    private JsonObject row;
    private static final class Visual {
        long frames,sourceFrames,airHeldFrames,airAttackFrames,shoulderFrames,armorFrames,bridgeFrames;
        double sourceError,shoulderError,airError,lowerAirError,nativeAirError,nativeLowerAirError,waistJoinError;
        transient Map<String,Matrix4f> heldRig,heldLower;
        transient float[] heldNative,heldNativeLower;
    }
    ShoulderCombatReview(){
        NeoForge.EVENT_BUS.addListener(this::clientTick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(this::submitted);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.add(message);System.out.println("SHOULDER COMBAT PASS "+message);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private RpgServer rpg(){return RpgServer.instance();}
    private void action(ServerPlayer p,String name,String argument){rpg().action(p,name,argument);}
    private Object field(Class<?> owner,Object instance,String name)throws ReflectiveOperationException{String key=owner.getName()+"#"+name;Field f=fields.get(key);if(f==null){f=owner.getDeclaredField(name);f.setAccessible(true);fields.put(key,f);}return f.get(instance);}
    private void preRender(RenderFrameEvent.Pre event){RenderObservations.beginSubmittedFrame(event);}
    private static Map<String,Matrix4f> copyRig(Map<String,Matrix4f> source){var copy=new HashMap<String,Matrix4f>();source.forEach((id,matrix)->copy.put(id,new Matrix4f(matrix)));return copy;}
    private static double matrixError(Matrix4f a,Matrix4f b){float[] x=a.get(new float[16]),y=b.get(new float[16]);double error=0;for(int n=0;n<16;n++)error=Math.max(error,Math.abs(x[n]-y[n]));return error;}
    private static double rigError(Map<String,Matrix4f> a,Map<String,Matrix4f> b,Collection<String> parts){double result=0;for(String id:parts)if(a.containsKey(id)&&b.containsKey(id))result=Math.max(result,matrixError(a.get(id),b.get(id)));return result;}
    private static float[] nativePose(ModelPart... parts){float[] values=new float[parts.length*6];int i=0;for(ModelPart part:parts){values[i++]=part.x;values[i++]=part.y;values[i++]=part.z;values[i++]=part.xRot;values[i++]=part.yRot;values[i++]=part.zRot;}return values;}
    private static double poseError(float[] a,float[] b){double error=0;for(int n=0;n<a.length;n++)error=Math.max(error,Math.abs(a[n]-b[n]));return error;}
    private void submitted(RenderLivingEvent.Post<?,?,?> event){
        RenderObservations.captureSubmittedAvatar(event);Minecraft game=Minecraft.getInstance();if(!initialized||!recording||done||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{PlayerAnimations.Frame frame=PlayerAnimations.frame(state);if(frame==null)return;nativeSkinModels.add(state.skin.model().name());Visual v=visuals.computeIfAbsent(scenario,key->new Visual());v.frames++;
            @SuppressWarnings("unchecked") Map<UUID,Object> bindings=(Map<UUID,Object>)field(PlayerAnimations.class,null,"bindings");Object binding=bindings.get(game.player.getUUID());if(binding==null)return;RpgAnimationPayload payload=(RpgAnimationPayload)field(binding.getClass(),binding,"state");
            double clipSeconds=((Number)field(binding.getClass(),binding,"sampledClipSeconds")).doubleValue();
            boolean normal=!game.player.isInWater()&&!game.player.isPassenger()&&!state.isFallFlying&&state.swimAmount<=.5f;
            if(scenario==Scenario.SOURCE_COMBO&&SwordMotion.applies(frame.model())&&AirPose.attack(payload.phase())&&game.player.onGround()&&!game.player.isCrouching()&&normal){
                if(field(binding.getClass(),binding,"transitionFrom")!=null&&((Number)field(binding.getClass(),binding,"transitionTicks")).doubleValue()>0)v.bridgeFrames++;
                require(v.bridgeFrames==0,"Source sword handoff resurrected a default/previous-pose offset bridge");
                Object motion=field(binding.getClass(),binding,"motion");double breathStarted=((Number)field(PresentationMotion.class,motion,"breathStarted")).doubleValue();require(Double.isFinite(breathStarted),"Actual sword breathing origin was not initialized");double breathSeconds=Math.max(0,frame.sampledTick()-breathStarted)*breathSpeed/20;
                var clip=frame.model().rig().clips().get(payload.action());var expected=SwordMotion.sampleUpper(frame.model(),clip,clipSeconds,breathSeconds,game.player.getXRot(),state.yRot);expected=SwordMotion.waist(frame.model(),expected);expected=PlayerShoulderFit.apply(frame.model(),expected,state.skin.model()==PlayerModelType.SLIM);var reference=frame.model().worldMatrices(expected);
                Matrix4f expectedBody=new Matrix4f(reference.get("body")).invert(),actualBody=new Matrix4f(frame.world().get("body")).invert();
                for(String side:List.of("right","left"))for(String suffix:List.of("_arm","_forearm","_hand","_item")){String id=side+suffix;double error=matrixError(new Matrix4f(expectedBody).mul(reference.get(id)),new Matrix4f(actualBody).mul(frame.world().get(id)));v.sourceError=Math.max(v.sourceError,error);require(error<.003,"Actual sword source chain differs from the current authored clip: "+payload.action()+" / "+id+" error="+error);}
                v.sourceFrames++;
            }
            if(scenario.name().startsWith("AIR")&&begun&&!game.player.onGround()&&normal&&event.getRenderer().getModel() instanceof PlayerModel nativeModel){
                float[] nativeBody=nativePose(nativeModel.body,nativeModel.rightArm,nativeModel.leftArm,nativeModel.rightLeg,nativeModel.leftLeg),nativeLower=nativePose(nativeModel.rightLeg,nativeModel.leftLeg);
                if(PlayerAnimations.airbornePoseHeld(game.player.getUUID())){
                    if(v.heldRig==null){v.heldRig=copyRig(frame.world());v.heldNative=nativeBody;}double error=rigError(v.heldRig,frame.world(),List.of("body","right_arm","left_arm","right_leg","left_leg"));v.airError=Math.max(v.airError,error);require(error<.001,"Native airborne READY did not retain the last actually submitted pose: "+scenario+" error="+error);double nativeError=poseError(v.heldNative,nativeBody);v.nativeAirError=Math.max(v.nativeAirError,nativeError);require(nativeError<.001,"Actually submitted native airborne body/limbs changed during retained READY: "+scenario+" error="+nativeError);v.airHeldFrames++;
                }else{v.heldRig=null;v.heldNative=null;}
                var lower=PoseLayers.masks(frame.model().rig()).get("lower");if(v.heldLower==null){v.heldLower=copyRig(frame.world());v.heldNativeLower=nativeLower;}
                if(AirPose.attack(payload.phase())){double error=rigError(v.heldLower,frame.world(),lower);v.lowerAirError=Math.max(v.lowerAirError,error);require(error<.001,"Actual airborne attack replaces its takeoff leg stance: "+error);double nativeError=poseError(v.heldNativeLower,nativeLower);v.nativeLowerAirError=Math.max(v.nativeLowerAirError,nativeError);require(nativeError<.001,"Actual native attack legs lost their retained takeoff stance: "+nativeError);
                    Vector3f waist=new Quaternionf().rotationZYX(nativeModel.body.zRot,nativeModel.body.yRot,nativeModel.body.xRot).transform(new Vector3f(0,12,0)).add(nativeModel.body.x,nativeModel.body.y,nativeModel.body.z);Vector3f hips=new Vector3f((nativeModel.rightLeg.x+nativeModel.leftLeg.x)*.5f,(nativeModel.rightLeg.y+nativeModel.leftLeg.y)*.5f,(nativeModel.rightLeg.z+nativeModel.leftLeg.z)*.5f);double join=waist.distance(hips);v.waistJoinError=Math.max(v.waistJoinError,join);require(join<.003,"Actual native upper/lower waist detached during airborne attack: "+join+" pixels");v.airAttackFrames++;}
            }else if(scenario.name().startsWith("AIR")&&game.player.onGround()){v.heldRig=null;v.heldLower=null;v.heldNative=null;v.heldNativeLower=null;}
            if(scenario==Scenario.DUAL_ARMOR&&frame.model().rig().id().equals("fsrpg.legacy_dual_blade")&&game.player.onGround()&&!game.player.isCrouching()&&normal&&payload.phase().equals("READY")){
                @SuppressWarnings("unchecked") Map<String,Pose> raw=(Map<String,Pose>)legacySample.invoke(null,frame.model(),payload,clipSeconds,frame.sampledTick()/20,state,game.player);var baseline=new HashMap<>(raw);
                if(state.skin.model()==PlayerModelType.SLIM)for(String side:List.of("right","left")){String id=side+"_arm";Pose old=baseline.get(id);var at=old.position();baseline.put(id,new Pose(old.rotation(),new org.fsvoxel.animation.FsvoxAnimation.V(at.x()+(side.equals("right")?3.125f:-3.125f),at.y(),at.z()),old.scale()));}
                var before=frame.model().worldMatrices(baseline);
                for(String side:List.of("right","left")){Vector3f expected=before.get("body").transformDirection(new Vector3f((side.equals("right")?-1:1)*PlayerShoulderFit.DUAL_OUTSET_CM,0,0));for(String suffix:List.of("_arm","_forearm","_hand","_item")){String id=side+suffix;Vector3f actual=frame.world().get(id).getTranslation(new Vector3f()).sub(before.get(id).getTranslation(new Vector3f()));double error=expected.distance(actual);v.shoulderError=Math.max(v.shoulderError,error);require(error<.003,"Native dual shoulder/hand/item chain does not share the small outward correction: "+id+" error="+error);}}
                v.shoulderFrames++;if(!game.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()&&PlayerAnimations.appliedModels()-lastAppliedModels>1)v.armorFrames++;
            }
            lastAppliedModels=PlayerAnimations.appliedModels();
        }catch(Throwable error){failure=error;}
    }
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root);JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual shoulder combat window is on DISPLAY5");
        String name=System.getProperty("fsrpg.shoulderCombatWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.equals("FSRPG Shoulder Combat Review 20261019 01")&&world.getFileName().toString().equals(name)&&Files.isRegularFile(world.resolve(".fsrpg-shoulder-combat-test-world")),"Only the named and marked copied shoulder-combat world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 手动锁定、原稿连段与空中姿势 · DISPLAY5");game.resizeDisplay();
        Class<?> assets=Class.forName("org.fsrpg.client.AnimationAssets");Method load=assets.getDeclaredMethod("load",String.class);load.setAccessible(true);
        for(String nameToWarm:List.of("dual_blade.fsvoxel","editor_rig.fsvoxel","legacy_bow_fp.fsvoxel","legacy_bow.fsvoxel","legacy_dual_blade_fp.fsvoxel","legacy_dual_blade.fsvoxel","legacy_rapier_fp.fsvoxel","legacy_rapier.fsvoxel","rapier.fsvoxel","spear.fsvoxel")){load.invoke(null,nameToWarm);prewarmedModels++;}
        Class<?> legacy=Class.forName("org.fsrpg.client.LegacyMotion");legacySample=legacy.getDeclaredMethod("sample",ModelDocument.class,RpgAnimationPayload.class,double.class,double.class,AvatarRenderState.class,LivingEntity.class);legacySample.setAccessible(true);
        Object rate=field(Class.forName("org.fsrpg.client.ClientOptions"),null,"BREATH_SPEED");Method readRate=rate.getClass().getMethod("get");readRate.setAccessible(true);breathSpeed=((Number)readRate.invoke(rate)).doubleValue();
        Object transition=field(Class.forName("org.fsrpg.client.ClientOptions"),null,"CAMERA_TRANSITION");cameraTransitionSeconds=((Number)readRate.invoke(transition)).doubleValue();check(Math.abs(cameraTransitionSeconds-.5)<.000001,"Native camera review uses the configured half-second shoulder transition");
        initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{try{ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
            p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
            for(int x=-16;x<=16;x++)for(int z=-16;z<=24;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);fixtureProgress();equip(p,"dual_blade",true);armor(p,true);actor(p).requestDrawn();spawn(p,EntityType.HUSK,0,3.5);spawn(p,EntityType.COW,25,4);ready=true;
        }catch(Throwable error){failure=error;}});
    }
    private void fixtureProgress(){var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();for(String id:List.of("rapier","dual_sword","archery"))learned.add(id);one.add("learnedNodes",learned);service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));}
    private void armor(ServerPlayer p,boolean equipped){p.setItemSlot(EquipmentSlot.HEAD,equipped?new ItemStack(Items.IRON_HELMET):ItemStack.EMPTY);p.setItemSlot(EquipmentSlot.CHEST,equipped?new ItemStack(Items.IRON_CHESTPLATE):ItemStack.EMPTY);p.setItemSlot(EquipmentSlot.LEGS,equipped?new ItemStack(Items.IRON_LEGGINGS):ItemStack.EMPTY);p.setItemSlot(EquipmentSlot.FEET,equipped?new ItemStack(Items.IRON_BOOTS):ItemStack.EMPTY);}
    private void equip(ServerPlayer p,String style,boolean draw){actor(p).forceSheathed();armor(p,false);p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(style.equals("spear")?Items.DIAMOND_SPEAR:style.startsWith("bow")?Items.BOW:Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,style.equals("dual_blade")?new ItemStack(Items.DIAMOND_SWORD):ItemStack.EMPTY);actor(p).setStyle(rpg().config().current().styles().get(style));action(p,"status","");if(draw)actor(p).requestDrawn();actor(p).setResources(actor(p).maxStamina(),100);p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);}
    private Mob spawn(ServerPlayer p,EntityType<?> type,double yaw,double range){Mob mob=(Mob)type.create(p.level(),EntitySpawnReason.COMMAND);require(mob!=null,"Could not create live selection fixture");double radians=Math.toRadians(yaw);mob.setPos(p.getX()-Math.sin(radians)*range,80,p.getZ()+Math.cos(radians)*range);mob.setNoAi(true);mob.setPersistenceRequired();mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);mob.setHealth(200);if(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)!=null)mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);p.level().addFreshEntity(mob);targets.add(mob);return mob;}
    private void clearTargets(){for(Mob target:targets)target.discard();targets.clear();selected=null;}
    private void selectAttack(ServerPlayer p,Mob target){var delta=target.getBoundingBox().getCenter().subtract(p.getEyePosition());var aim=RapierTargeting.direction(delta.x,delta.y,delta.z,p.getYRot());JsonObject request=new JsonObject();request.addProperty("button","left");request.addProperty("targetId",target.getId());request.addProperty("targetUuid",target.getUUID().toString());request.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(List.of(aim))));action(p,"targetAttack",request.toString());}
    private String style(){return switch(scenario){case AIR_DUAL,DUAL_ARMOR->"dual_blade";case AIR_SPEAR,AIR_ATTACK->"spear";case AIR_BOW->"bow_rapid";default->"rapier";};}
    private void prepare(ServerPlayer p,long now){step=0;rQueued=false;sawAir=false;attackQueued=false;readyAt=-1;jumpInput=false;entered=now;row=new JsonObject();row.addProperty("id",scenario.name().toLowerCase(Locale.ROOT).replace('_','-'));startedSkills.clear();
        switch(scenario){
            case EQUIP_SHEATHED -> {clearTargets();equip(p,"rapier",false);selected=spawn(p,EntityType.HUSK,0,3);spawn(p,EntityType.HUSK,40,3);}
            case SELECT_HUSK -> {clientSelectionRequested=clientSelectionClicked=clientSelectionVerified=unlockedMouseChecked=unlockedServerFacingObserved=false;clientSelectionTargetId=selected.getId();clientSelectionTargetUuid=selected.getUUID().toString();}
            case R_UNLOCK_SHEATH -> { }
            case KEEP_PASSIVE -> {action(p,"draw","");clearTargets();selected=spawn(p,EntityType.COW,0,3);spawn(p,EntityType.HUSK,35,2.5);}
            case CAMERA_ONLY -> {clearTargets();actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.APPLE));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);armor(p,false);action(p,"status","");}
            default -> {clearTargets();equip(p,style(),false);if(scenario==Scenario.DUAL_ARMOR)armor(p,true);}
        }
        prepared=true;begun=false;
    }
    private boolean drawnReady(ServerPlayer p){CombatActor a=actor(p);if(a.phase()==CombatPhase.SHEATHED&&!a.drawn()&&!rQueued){drawRequests++;rQueued=true;return false;}return a.drawn()&&a.phase()==CombatPhase.READY;}
    private void finishCase(ServerPlayer p){row.addProperty("passed",true);row.addProperty("elapsedTicks",elapsed);row.addProperty("drawn",actor(p).drawn());row.add("targetLock",RpgConfig.JSON.toJsonTree(rpg().targetLock(p)));synchronized(reportLock){scenarios.add(row.deepCopy());}index++;prepared=false;begun=false;jumpInput=false;}
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||done||!recording||scriptDone||failure!=null)return;
        try{ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;long now=p.level().getGameTime();if(index>=Scenario.values().length){scriptDone=true;return;}scenario=Scenario.values()[index];CombatActor a=actor(p);
            if(!prepared){prepare(p,now);return;}if(now-entered<3)return;
            if(!begun){
                if(scenario==Scenario.EQUIP_SHEATHED){if(now-entered<10)return;check(!a.drawn()&&a.phase()==CombatPhase.SHEATHED,"Equipping a non-spear weapon does not draw it automatically");check(!rpg().targetLock(p).locked(),"Nearby hostiles cannot acquire an automatic target");drawRequests++;rQueued=true;}
                else if(scenario!=Scenario.CAMERA_ONLY&&!drawnReady(p))return;
                if(scenario.name().startsWith("AIR")&&!p.onGround())return;
                begun=true;caseStarted=now;elapsed=0;sequence=lastSequence=a.snapshot().swingSequence();
                if(scenario==Scenario.SELECT_HUSK){check(selected!=null,"Selection fixture still exists");clientSelectionRequested=true;}
                if(scenario==Scenario.KEEP_PASSIVE){check(selected!=null,"Selection fixture still exists");selectAttack(p,selected);check(rpg().targetLock(p).targetUuid().equals(selected.getUUID().toString()),"Accepted real eye-ray attack locks exactly its submitted target: "+scenario);}
                if(scenario==Scenario.SOURCE_COMBO){action(p,"attack","left");require(a.currentSkill()!=null,"Source combo first attack did not start");sequence=lastSequence=a.snapshot().swingSequence();startedSkills.add(a.currentSkill().id());action(p,"attack","left");}
                if(scenario.name().startsWith("AIR"))jumpInput=true;
                if(scenario==Scenario.CAMERA_ONLY)drawRequests++;
                return;
            }
            elapsed=now-caseStarted;JsonObject observation=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();observation.addProperty("scenario",scenario.name());observation.addProperty("elapsedTicks",elapsed);observation.addProperty("grounded",p.onGround());observation.add("targetLock",RpgConfig.JSON.toJsonTree(rpg().targetLock(p)));synchronized(reportLock){trace.add(observation.deepCopy());}serverObservation=observation;
            switch(scenario){
                case EQUIP_SHEATHED -> {if(a.drawn()&&a.phase()==CombatPhase.READY&&elapsed>=18){check(!rpg().targetLock(p).locked(),"R draws without automatically selecting a nearby enemy");row.addProperty("autoDrawOnEquip",false);finishCase(p);}}
                case SELECT_HUSK -> {
                    if(unlockedMouseChecked&&!clientSelectionClicked&&!unlockedServerFacingObserved&&Math.abs(AttackAim.wrap(p.getYRot()-unlockedMouseYaw))<.05){check(!rpg().targetLock(p).locked(),"Actual unlocked raw-mouse facing reaches the server without acquiring an enemy");row.addProperty("unlockedServerYawError",Math.abs(AttackAim.wrap(p.getYRot()-unlockedMouseYaw)));unlockedServerFacingObserved=true;}
                    if(rpg().targetLock(p).locked()){
                        check(rpg().targetLock(p).targetUuid().equals(selected.getUUID().toString()),"The actual client camera-ray click locks its selected husk and another nearby hostile cannot steal it");
                        if(clientSelectionVerified&&elapsed>=32&&a.phase()==CombatPhase.READY){var delta=selected.getBoundingBox().getCenter().subtract(p.getEyePosition());double expected=RapierTargeting.direction(delta.x,delta.y,delta.z,p.getYRot()).yaw();double error=Math.abs(AttackAim.wrap(p.getYRot()-expected));check(error<.05,"Server actor remains directed at the locked target after the client's sixty-degree camera orbit");row.addProperty("serverLockedYawError",error);row.add("clientCameraSelection",clientSelectionProof.deepCopy());row.addProperty("targetUuid",selected.getUUID().toString());finishCase(p);}
                    }else require(elapsed<90,"Real client camera-ray targetAttack did not establish the intended lock");
                }
                case KEEP_PASSIVE -> {check(rpg().targetLock(p).targetUuid().equals(selected.getUUID().toString()),"Unselected nearby hostile cannot steal the explicit lock: "+scenario);if(elapsed>=32&&a.phase()==CombatPhase.READY){row.addProperty("targetUuid",selected.getUUID().toString());finishCase(p);}}
                case R_UNLOCK_SHEATH -> {
                    if(step==0){drawRequests++;step=1;readyAt=now;}
                    if(step==1&&now-readyAt>=5){check(!rpg().targetLock(p).locked()&&a.drawn(),"First R releases the existing target lock while leaving the weapon drawn");drawRequests++;step=2;readyAt=now;}
                    if(step==2&&now-readyAt>=16){check(!a.drawn()&&a.phase()==CombatPhase.SHEATHED,"Second R sheathes after the explicit lock was removed");finishCase(p);}
                }
                case SOURCE_COMBO -> {
                    if(a.snapshot().swingSequence()>lastSequence){check(a.snapshot().swingSequence()==lastSequence+1,"One buffered source continuation starts exactly one next blade");startedSkills.add(a.currentSkill().id());lastSequence=a.snapshot().swingSequence();if(startedSkills.size()<3)action(p,"attack","left");}
                    if(startedSkills.size()==3&&a.phase()==CombatPhase.READY&&a.currentSkill()==null){check(startedSkills.equals(List.of("sword_left","rapier_downcut","rapier_crosscut")),"Three real relaxed sword inputs preserve their corresponding authored stages");row.add("skills",RpgConfig.JSON.toJsonTree(startedSkills));finishCase(p);}
                }
                case AIR_RAPIER,AIR_DUAL,AIR_SPEAR,AIR_BOW,AIR_ATTACK -> {
                    if(elapsed>=3)jumpInput=false;if(!p.onGround())sawAir=true;check(a.phase()!=CombatPhase.GUARDING,"Jumping or falling never fabricates a server guard state: "+scenario);
                    if(scenario==Scenario.AIR_ATTACK&&sawAir&&!attackQueued){JsonObject request=new JsonObject();request.addProperty("button","left");request.add("aim",RpgConfig.JSON.toJsonTree(AttackAim.at(p.getYRot(),0)));action(p,"aimAttack",request.toString());check(a.currentSkill()!=null,"An actual point thrust starts while the player is airborne");attackQueued=true;}
                    if(elapsed>=34&&p.onGround()){check(sawAir,"Real jump input leaves the physical ground: "+scenario);if(scenario==Scenario.AIR_ATTACK)check(attackQueued,"Air attack was actually emitted");finishCase(p);}
                }
                case DUAL_ARMOR -> {if(elapsed>=34){check(!p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()&&!p.getOffhandItem().isEmpty(),"Real armored dual fixture retains chest armor and both official swords");finishCase(p);}}
                case CAMERA_ONLY -> {
                    if(step==0&&elapsed>=16){check(cameraOnly&&cameraBlend>.99&&!a.drawn(),"R with an ordinary item enables only the local shoulder camera");drawRequests++;step=1;}
                    if(step==1&&elapsed>=34){check(!cameraOnly&&cameraBlend<.01&&!a.drawn(),"Ordinary-item R exits shoulder camera without a combat draw state");finishCase(p);}
                }
            }
            require(elapsed<160,"Native shoulder-combat scenario timed out: "+scenario);
        }catch(Throwable error){failure=error;}
    }
    private void render(RenderFrameEvent.Post event){if(initialized&&!done)try{movie.frame(event);}catch(Throwable error){failure=error;}}
    /** Feed the same raw-delta entry point as MouseHandler; never write camera or actor angles in the fixture. */
    private void rawMouseDegrees(double yaw,double pitch){Minecraft game=Minecraft.getInstance();double sensitivity=game.options.sensitivity().get()*.6+.2,gain=sensitivity*sensitivity*sensitivity*8*.15;
        require(CombatCamera.mouse(yaw/(gain*(game.options.invertMouseX().get()?-1:1)),pitch/(gain*(game.options.invertMouseY().get()?-1:1))),"The real shoulder camera consumed the raw mouse input");}
    private void clientSelection(Minecraft game){
        if(scenario!=Scenario.SELECT_HUSK||!begun||!clientSelectionRequested||clientSelectionVerified)return;
        if(!(game.level.getEntity(clientSelectionTargetId) instanceof LivingEntity target)||!target.getUUID().toString().equals(clientSelectionTargetUuid))return;
        if(!CombatCamera.active()||CombatCamera.transitionBlend()<.999)return;
        if(!unlockedMouseChecked){
            require(CombatCamera.lockedTarget()==null,"Camera-ray fixture starts without a fabricated client lock");selectionProof=new JsonObject();float before=CombatCamera.yaw();rawMouseDegrees(18,-4);
            double moved=AttackAim.wrap(CombatCamera.yaw()-before),error=Math.abs(AttackAim.wrap(game.player.getYRot()-CombatCamera.yaw()));
            check(Math.abs(moved-18)<.001&&error<.001&&Math.abs(game.player.getXRot()-CombatCamera.pitch())<.001,"Unlocked raw mouse first turns the camera and the actual local actor follows its final yaw and pitch");
            selectionProof.addProperty("inputProvenance","CombatCamera.mouse raw deltas, actual shoulder-camera ray and CombatCamera.targetAttack(left); no constructed target request");selectionProof.addProperty("unlockedRawYawDegrees",moved);selectionProof.addProperty("unlockedActorCameraYawError",error);unlockedMouseYaw=game.player.getYRot();unlockedMouseChecked=true;return;
        }
        if(!unlockedServerFacingObserved)return;
        if(!clientSelectionClicked){
            Vec3 camera=game.gameRenderer.getMainCamera().position(),delta=target.getBoundingBox().getCenter().subtract(camera);var desired=RapierTargeting.direction(delta.x,delta.y,delta.z,CombatCamera.yaw());
            double yawError=AttackAim.wrap(desired.yaw()-CombatCamera.yaw()),pitchError=desired.pitch()-CombatCamera.pitch();rawMouseDegrees(yawError,pitchError);clientAimTicks++;
            // Let the translated real camera settle around its new orbit before using its rendered reticle.
            Vec3 ray=Vec3.directionFromRotation(CombatCamera.pitch(),CombatCamera.yaw()),end=camera.add(ray.scale(16));
            if(clientAimTicks<3||Math.hypot(yawError,pitchError)>.15||target.getBoundingBox().clip(camera,end).isEmpty())return;
            check(game.player.distanceToSqr(target)<=100,"The actual shoulder reticle intersects the selected live husk inside ten blocks");
            selectionProof.addProperty("targetId",target.getId());selectionProof.addProperty("targetUuid",target.getUUID().toString());selectionProof.addProperty("cameraAimTicks",clientAimTicks);selectionProof.addProperty("cameraToTargetYaw",CombatCamera.yaw());selectionProof.addProperty("cameraToTargetPitch",CombatCamera.pitch());selectionProof.addProperty("cameraEyeDistanceAtClick",camera.distanceTo(game.player.getEyePosition()));selectionProof.addProperty("reticleIntersectsTarget",true);selectionProof.addProperty("clientTargetAttackCalls",1);selectionProof.addProperty("directServerAttackCalls",0);
            CombatCamera.targetAttack("left");clientSelectionClicked=true;return;
        }
        LivingEntity locked=CombatCamera.lockedTarget();if(locked==null)return;require(locked.getUUID().equals(target.getUUID()),"The actual camera-ray request must return the same authoritative client lock");
        float before=CombatCamera.yaw();rawMouseDegrees(60,0);double moved=AttackAim.wrap(CombatCamera.yaw()-before),error=Math.abs(AttackAim.wrap(game.player.getYRot()-CombatCamera.lockYaw())),separation=Math.abs(AttackAim.wrap(CombatCamera.yaw()-game.player.getYRot()));
        check(Math.abs(moved-60)<.001&&error<.001&&separation>45,"Locked raw mouse turns the actual camera sixty degrees while the local actor continues facing its enemy");
        selectionProof.addProperty("lockedRawYawDegrees",moved);selectionProof.addProperty("lockedCameraYawAfter",CombatCamera.yaw());selectionProof.addProperty("lockedActorYawAfter",game.player.getYRot());selectionProof.addProperty("lockedActorTargetYawError",error);selectionProof.addProperty("lockedCameraActorYawSeparation",separation);clientSelectionProof=selectionProof.deepCopy();clientSelectionVerified=true;
    }
    /** Measure completed native 0->1 / 1->0 travels; a reversed transition is retained in frames but never counted as complete. */
    private void sampleCameraTransition(long now,double blend,double eyeDistance){
        if(Double.isFinite(lastCameraBlend)){
            int direction=blend>lastCameraBlend+1e-8?1:blend<lastCameraBlend-1e-8?-1:0;
            if(transitionDirection==0&&direction!=0&&(direction>0&&lastCameraBlend<1e-7||direction<0&&lastCameraBlend>1-1e-7)){
                transitionDirection=direction;transitionFirstNano=lastCameraNano;transitionIntermediateFrames=transitionMiddleFrames=0;transitionMinEye=Double.POSITIVE_INFINITY;transitionMaxEye=0;
            }else if(transitionDirection!=0&&direction!=0&&direction!=transitionDirection)transitionDirection=0;
            if(transitionDirection!=0){
                if(blend>1e-7&&blend<1-1e-7){transitionIntermediateFrames++;if(blend>.25&&blend<.75)transitionMiddleFrames++;transitionMinEye=Math.min(transitionMinEye,eyeDistance);transitionMaxEye=Math.max(transitionMaxEye,eyeDistance);}
                if(transitionDirection>0&&blend>=1-1e-7||transitionDirection<0&&blend<=1e-7){JsonObject transition=new JsonObject();transition.addProperty("direction",transitionDirection>0?"enter":"exit");transition.addProperty("durationSeconds",(now-transitionFirstNano)/1e9);transition.addProperty("configuredSeconds",cameraTransitionSeconds);transition.addProperty("intermediateFrames",transitionIntermediateFrames);transition.addProperty("middleFrames",transitionMiddleFrames);transition.addProperty("minimumIntermediateEyeDistance",transitionMinEye);transition.addProperty("maximumIntermediateEyeDistance",transitionMaxEye);transition.addProperty("completedInScenario",scenario.name());cameraTransitions.add(transition);transitionDirection=0;}
            }
        }
        lastCameraNano=now;lastCameraBlend=blend;
    }
    private void clientTick(ClientTickEvent.Post event){Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;try{
        if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Shoulder-combat native review failed",failure);movie.assertHealthy();require(System.nanoTime()-started<180_000_000_000L,"Shoulder-combat native review exceeded three minutes");
        if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();game.options.keyJump.setDown(jumpInput);
        while(appliedDraw<drawRequests){appliedDraw++;CombatCamera.drawKey();}cameraOnly=CombatCamera.cameraOnly();cameraBlend=CombatCamera.transitionBlend();clientSelection(game);
        if(ready&&!recording&&++prewarmTicks>=50){officialMainBefore=OfficialHeldItems.submissions();officialOffBefore=OfficialHeldItems.offhandSubmissions();movie.start("shoulder-combat","Prewarmed production assets; actual player avatar submissions, real source combos, physical jumps, explicit selection and shoulder camera transitions",this::observation);recording=true;}
        if(scriptDone&&!stopped){movie.stop();stopped=true;KeyMapping.releaseAll();}
        if(scriptDone&&movie.finish()){validate();finish(null);}
    }catch(Throwable error){finish(error);}}
    private JsonObject observation(){Minecraft game=Minecraft.getInstance();JsonObject value=serverObservation.deepCopy();value.addProperty("scenario",scenario.name());value.addProperty("actualCameraType",game.options.getCameraType().name());value.addProperty("actualCameraEntityId",game.getCameraEntity().getUUID().toString());value.addProperty("cameraOnly",CombatCamera.cameraOnly());double blend=CombatCamera.transitionBlend(),eyeDistance=game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition(game.getDeltaTracker().getGameTimeDeltaPartialTick(false)));value.addProperty("cameraBlend",blend);value.addProperty("cameraEyeDistanceMeters",eyeDistance);value.addProperty("cameraYaw",CombatCamera.yaw());value.addProperty("cameraPitch",CombatCamera.pitch());value.addProperty("clientActorYaw",game.player.getYRot());value.addProperty("clientActorPitch",game.player.getXRot());value.addProperty("clientReticleAttackSent",clientSelectionClicked);value.addProperty("clientReticleLockVerified",clientSelectionVerified);sampleCameraTransition(System.nanoTime(),blend,eyeDistance);
        if(scenario==Scenario.SELECT_HUSK&&clientSelectionVerified&&CombatCamera.lockedTarget()!=null){require(Math.abs(AttackAim.wrap(game.player.getYRot()-CombatCamera.lockYaw()))<.05,"Rendered local actor lost its locked facing during the actual camera orbit");require(Math.abs(AttackAim.wrap(CombatCamera.yaw()-game.player.getYRot()))>45,"Rendered camera recoupled to the actor after raw-mouse orbit");lockedOrbitNativeFrames++;value.addProperty("lockedOrbitNativeFrame",lockedOrbitNativeFrames);}
        try{JsonObject avatar=RenderObservations.submittedSampler(game.player).get();value.add("rig",avatar.remove("rig"));value.add("submittedAvatar",avatar);}catch(IllegalStateException absent){value.add("rig",new JsonObject());value.addProperty("actualAvatarSubmissionAbsent",true);}return value;}
    private Visual visual(Scenario key){Visual value=visuals.get(key);require(value!=null,"No actual submitted avatar frames were observed for "+key);return value;}
    private JsonArray copiedRows(JsonArray source){synchronized(reportLock){return source.deepCopy();}}
    private void validate(){
        check(copiedRows(scenarios).size()==Scenario.values().length,"All twelve native shoulder-combat scenarios completed");
        check(prewarmedModels==10&&prewarmTicks>=50,"All ten production player assets and the real armored avatar are prewarmed before native recording");
        JsonObject clip=movie.summary().getAsJsonArray("clips").get(0).getAsJsonObject();
        check(ClientState.number(clip,"sourceFps",0)>=30,"Prewarmed native capture samples at least thirty real rendered frames per second");
        check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native source capture contains real partial-tick frames");
        check(ClientState.number(clip,"changedRigPairs",0)>50,"Native capture contains changing actual submitted full-body rigs");
        check(clientSelectionVerified&&unlockedServerFacingObserved&&lockedOrbitNativeFrames>10,"Actual client raw mouse, shoulder-reticle attack and decoupled locked orbit are observed by the client, server and multiple native frames");
        boolean entered=false,exited=false;for(var element:cameraTransitions){JsonObject transition=element.getAsJsonObject();double duration=transition.get("durationSeconds").getAsDouble();if(Math.abs(duration-cameraTransitionSeconds)>.085||transition.get("intermediateFrames").getAsInt()<8||transition.get("middleFrames").getAsInt()<3||transition.get("maximumIntermediateEyeDistance").getAsDouble()-transition.get("minimumIntermediateEyeDistance").getAsDouble()<.5)continue;if(transition.get("direction").getAsString().equals("enter"))entered=true;else exited=true;}
        check(entered&&exited,"Real rendered eye-to-shoulder entry and return each take about half a second with several actual intermediate blend and camera-position frames");
        Visual sword=visual(Scenario.SOURCE_COMBO);
        check(sword.sourceFrames>20&&sword.bridgeFrames==0&&sword.sourceError<.003,"Actual sword combo upper chains follow their current authored clips without a default-pose bridge");
        for(Scenario key:List.of(Scenario.AIR_RAPIER,Scenario.AIR_DUAL,Scenario.AIR_SPEAR,Scenario.AIR_BOW)){
            Visual air=visual(key);check(air.airHeldFrames>5&&air.airError<.001&&air.nativeAirError<.001,"Actual airborne READY retains both the rig and submitted native takeoff pose across multiple frames: "+key);
        }
        Visual attack=visual(Scenario.AIR_ATTACK);
        check(attack.airAttackFrames>2&&attack.lowerAirError<.001&&attack.nativeLowerAirError<.001&&attack.waistJoinError<.003,"An actual airborne thrust continues above retained native legs with the upper/lower waist attached");
        check(attack.airHeldFrames>3&&attack.airError<.001,"The completed airborne thrust retains its actually presented endpoint until landing");
        Visual dual=visual(Scenario.DUAL_ARMOR);
        check(dual.shoulderFrames>20&&dual.shoulderError<.003,"Actual dual shoulder, forearm, hand and official item chains share the small outward fit");
        check(dual.armorFrames>5,"Actual equipped armor models are fitted during the same dual-player submissions");
        check(!nativeSkinModels.isEmpty()&&OfficialHeldItems.submissions()>officialMainBefore&&OfficialHeldItems.offhandSubmissions()>officialOffBefore,"The actual player skin and both official item stacks were submitted by the native renderer");
    }
    private void finish(Throwable error){if(done)return;done=true;movie.stop();try{JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.shoulderCombatWorld"));report.addProperty("prewarmedProductionModels",prewarmedModels);report.addProperty("prewarmClientTicks",prewarmTicks);report.addProperty("officialMainHandSubmissions",OfficialHeldItems.submissions()-officialMainBefore);report.addProperty("officialOffHandSubmissions",OfficialHeldItems.offhandSubmissions()-officialOffBefore);report.add("nativeSkinModels",RpgConfig.JSON.toJsonTree(nativeSkinModels));report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",copiedRows(scenarios));report.add("serverTrace",copiedRows(trace));report.add("nativeVisuals",RpgConfig.JSON.toJsonTree(visuals));report.add("clientCameraSelection",clientSelectionProof.deepCopy());report.addProperty("lockedOrbitNativeFrames",lockedOrbitNativeFrames);report.add("cameraTransitions",cameraTransitions.deepCopy());report.add("renderCapture",movie.summary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("shoulder-combat-review.json"),RpgConfig.JSON.toJson(report));System.out.println("SHOULDER COMBAT REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();}
}

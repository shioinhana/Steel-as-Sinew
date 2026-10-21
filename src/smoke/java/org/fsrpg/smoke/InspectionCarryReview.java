package org.fsrpg.smoke;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.progression.ProgressionService;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Packaged production animations on the actual avatar and ordinary player physics. */
final class InspectionCarryReview {
    private static final List<String> STYLES=List.of("rapier","dual_blade","spear","bow_rapid");
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final Object reportLock=new Object();
    private final JsonArray rows=new JsonArray(),serverTrace=new JsonArray();
    private final Map<String,Visual> visuals=new LinkedHashMap<>();
    private static final class Visual {long frames,activeFrames,carryFrames,twoHandReturnFrames,attackReturnFrames;double maximumWaistJoinPixels,maximumPoseChange,maximumGripLineErrorCm,minimumForwardShaftZ=100000,minimumLegAngle=100000,maximumLegAngle=-100000;transient Map<String,Matrix4f> previous;}
    private volatile boolean ready,recording,scriptDone,clientComplete;
    private volatile Throwable failure;
    private volatile int index,caseEpoch;
    private volatile long elapsed;
    private volatile String requested="",caseId="";
    private volatile JsonObject serverObservation=new JsonObject();
    private UUID playerId;
    private volatile boolean done,begun;
    private boolean initialized,prepared,stopped;
    private int warmup,prewarm,clientEpoch=-1,clientClicks,clientStep;
    private long started,entered,caseStarted,officialBefore;
    private double requestedAt;
    private boolean sawInspection,attackSent,stopAimChanged;
    private JsonObject row;
    private int previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousActive,previousCombatShoulder;
    private net.neoforged.neoforge.common.ModConfigSpec.BooleanValue shoulder;
    private CameraType previousCamera;
    InspectionCarryReview(){
        NeoForge.EVENT_BUS.addListener(this::clientTick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(this::submitted);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.Key e)->{if(!done)KeyMapping.releaseAll();});
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e)->{if(!done)e.setCanceled(true);});
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void check(boolean value,String message){require(value,message);checks.add(message);System.out.println("INSPECTION CARRY PASS "+message);}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private RpgServer rpg(){return RpgServer.instance();}
    private void action(ServerPlayer p,String verb,String argument){rpg().action(p,verb,argument);}
    private JsonArray copied(JsonArray a){synchronized(reportLock){return a.deepCopy();}}
    private void preRender(RenderFrameEvent.Pre event){RenderObservations.beginSubmittedFrame(event);}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root);JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual native review window is on DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(world.getFileName().toString().equals("FSRPG Inspection Carry Review 20261020 01")&&Files.isRegularFile(world.resolve(".fsrpg-inspection-carry-test-world")),"Only the named marked copied inspection world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();var shoulderField=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField("THIRD_PERSON");shoulderField.setAccessible(true);shoulder=(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue)shoulderField.get(null);previousCombatShoulder=shoulder.get();shoulder.set(false);
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);game.setWindowActive(true);game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 武器检视与长枪双握跑动 · DISPLAY5");game.resizeDisplay();
        Class<?> assets=Class.forName("org.fsrpg.client.AnimationAssets");var load=assets.getDeclaredMethod("load",String.class);load.setAccessible(true);
        for(String name:List.of("legacy_rapier.fsvoxel","legacy_dual_blade.fsvoxel","legacy_bow.fsvoxel","spear.fsvoxel","legacy_rapier_fp.fsvoxel","legacy_dual_blade_fp.fsvoxel","legacy_bow_fp.fsvoxel"))load.invoke(null,name);
        started=System.nanoTime();initialized=true;
        game.getSingleplayerServer().execute(()->{try{var p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
            for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(128),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
            for(int x=-16;x<=16;x++)for(int z=-16;z<=72;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<86;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
            for(String name:List.of("mind","body","footwork")){var points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);var learned=new JsonArray();for(String id:List.of("rapier","dual_sword","archery"))learned.add(id);one.add("learnedNodes",learned);service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
            equip(p,"dual_blade");ready=true;
        }catch(Throwable e){failure=e;}});
    }
    private void equip(ServerPlayer p,String style){
        actor(p).forceSheathed();p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(style.equals("spear")?Items.DIAMOND_SPEAR:style.startsWith("bow")?Items.BOW:Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,style.equals("dual_blade")?new ItemStack(Items.DIAMOND_SWORD):ItemStack.EMPTY);
        actor(p).setStyle(rpg().config().current().styles().get(style));action(p,"status","");actor(p).requestDrawn();actor(p).setResources(50,100);p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(0,0,0);p.setSprinting(false);
    }
    private void prepare(ServerPlayer p,long now){
        String style=index<12?STYLES.get(index/3):index==17?"rapier":"spear";if(index>=12||index%3==0)equip(p,style);
        requested=index<12?WeaponInspections.actions(style).get(index%3):"";
        caseId=index<12?requested.replace('_','-'):switch(index){case 12->"spear-fast-run";case 13->"spear-stop-return";case 14->"spear-run-attack";case 15->"spear-walk-two-hand";case 16->"inspection-move-cancel";default->"inspection-attack-cancel";};
        row=new JsonObject();row.addProperty("id",caseId);row.addProperty("style",style);row.addProperty("inspection",requested);entered=now;elapsed=0;prepared=true;begun=false;clientComplete=false;caseEpoch++;
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||!recording||done||scriptDone||failure!=null)return;
        try{ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(index>=18){scriptDone=true;return;}long now=p.level().getGameTime();if(!prepared){prepare(p,now);return;}
            var a=actor(p);if(!begun){if(now-entered<14||a.phase()!=CombatPhase.READY||!p.onGround())return;begun=true;caseStarted=now;return;}elapsed=now-caseStarted;
            JsonObject observation=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();observation.addProperty("caseId",caseId);observation.addProperty("elapsedTicks",elapsed);observation.addProperty("onGround",p.onGround());observation.addProperty("sprinting",p.isSprinting());observation.addProperty("x",p.getX());observation.addProperty("y",p.getY());observation.addProperty("z",p.getZ());synchronized(reportLock){serverTrace.add(observation.deepCopy());}serverObservation=observation;
            if(index<12){require(a.phase()==CombatPhase.READY&&Math.abs(a.stamina()-50)<.001,"Cosmetic inspections may not emit combat or consume stamina");if(clientComplete){row.addProperty("noCombatOrResourceMutation",true);finishCase();}}
            else if(index<=15){if(elapsed>=55)finishCase();}
            else if(clientComplete&&a.phase()==CombatPhase.READY)finishCase();
            require(elapsed<220,"Native inspection case exceeded eleven seconds: "+caseId);
        }catch(Throwable e){failure=e;}
    }
    private void finishCase(){row.addProperty("passed",true);row.addProperty("elapsedTicks",elapsed);synchronized(reportLock){rows.add(row.deepCopy());}index++;prepared=begun=false;}
    private void clickInspection()throws Exception{var field=InspectionInput.class.getDeclaredField("KEY");field.setAccessible(true);var key=(KeyMapping)field.get(null);KeyMapping.click(key.getKey());clientClicks++;}
    private void controls(Minecraft game)throws Exception{
        if(index<12)game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        if(clientEpoch!=caseEpoch){if(index>=12)shoulder.set(true);KeyMapping.releaseAll();clientEpoch=caseEpoch;clientStep=0;sawInspection=false;attackSent=false;stopAimChanged=false;requestedAt=Double.NaN;}
        if(!begun)return;
        if(index<12){
            if(clientStep==0){clickInspection();requestedAt=PlayerAnimations.presentationTick();clientStep=1;}
            String active=PlayerAnimations.inspectionAction(playerId);if(active.equals(requested))sawInspection=true;
            if(sawInspection&&active.isEmpty()&&PlayerAnimations.presentationTick()-requestedAt>=WeaponInspections.duration(requested)*20){clientComplete=true;}
        }else if(index<=15){
            boolean running=index!=15&&(index!=13||elapsed<26)&&(index!=14||elapsed<28);
            game.options.keyUp.setDown(running||index==15);game.options.keySprint.setDown(running);
            if(!running&&index!=15){game.options.keyUp.setDown(false);game.player.setSprinting(false);}
            if(index==13&&elapsed>=16&&!stopAimChanged){require(CombatCamera.mouse(0,-500),"Actual spear camera must accept raw look input during sprint");stopAimChanged=true;}
            if(index==14&&elapsed>=28&&!attackSent){ClientHooks.send("attack","left");attackSent=true;}
        }else{
            if(clientStep==0){clickInspection();requestedAt=PlayerAnimations.presentationTick();clientStep=1;}
            if(!PlayerAnimations.inspectionAction(playerId).isEmpty())sawInspection=true;
            if(sawInspection&&clientStep==1&&PlayerAnimations.presentationTick()-requestedAt>=18){if(index==16)game.options.keyUp.setDown(true);else ClientHooks.send("attack","left");clientStep=2;}
            if(clientStep==2&&PlayerAnimations.inspectionAction(playerId).isEmpty()){game.options.keyUp.setDown(false);clientComplete=true;}
        }
    }
    private void clientTick(ClientTickEvent.Post event){var game=Minecraft.getInstance();if(done||game.player==null||game.level==null||game.getSingleplayerServer()==null)return;try{
        if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Native inspection/carry failure",failure);movie.assertHealthy();require(System.nanoTime()-started<300_000_000_000L,"Review exceeded five minutes");game.setWindowActive(true);game.getFramerateLimitTracker().onInputReceived();if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);
        if(ready&&!recording&&++prewarm>=50){officialBefore=OfficialHeldItems.submissions();movie.start("inspection-carry","Twelve Astra Ultra inspections via actual H mapping, official ItemStacks and real sprint/stop/attack physics",this::observation);recording=true;}
        if(recording&&!scriptDone)controls(game);
        if(scriptDone&&!stopped){movie.stop();stopped=true;KeyMapping.releaseAll();}if(scriptDone&&movie.finish()){validate();finish(null);}
    }catch(Throwable e){finish(e);}}
    private JsonObject observation(){var value=serverObservation.deepCopy();value.addProperty("caseId",caseId);value.addProperty("inspectionAction",PlayerAnimations.inspectionAction(playerId));value.addProperty("carryWeight",0);value.addProperty("view",Minecraft.getInstance().options.getCameraType().name());try{JsonObject actual=RenderObservations.submittedSampler(Minecraft.getInstance().player).get();value.add("rig",actual.remove("rig"));value.add("submittedAvatar",actual);}catch(IllegalStateException absent){value.add("rig",new JsonObject());}return value;}
    private void submitted(RenderLivingEvent.Post<?,?,?> event){RenderObservations.captureSubmittedAvatar(event);var game=Minecraft.getInstance();if(!recording||done||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId()||!begun)return;try{
        var frame=PlayerAnimations.frame(state);if(frame==null)return;String id=caseId;Visual v=visuals.computeIfAbsent(id,k->new Visual());v.frames++;
        String inspection=PlayerAnimations.inspectionAction(playerId);if(index<12&&inspection.equals(requested)){v.activeFrames++;if(event.getRenderer().getModel() instanceof PlayerModel nativeModel){var part=nativeModel.body;Vector3f waist=new Vector3f(0,12,0).rotate(new Quaternionf().rotationZYX(part.zRot,part.yRot,part.xRot)).add(part.x,part.y,part.z);Vector3f hips=new Vector3f((nativeModel.rightLeg.x+nativeModel.leftLeg.x)/2,(nativeModel.rightLeg.y+nativeModel.leftLeg.y)/2,(nativeModel.rightLeg.z+nativeModel.leftLeg.z)/2);if(frame.supportedLower()!=null&&(frame.model().rig().id().equals("fsrpg.spear")||SwordMotion.applies(frame.model())))waist=new TorsoDeformation(frame.world().get("body"),frame.supportedLower()).point(0,12,0).mul(16);v.maximumWaistJoinPixels=Math.max(v.maximumWaistJoinPixels,waist.distance(hips));require(v.maximumWaistJoinPixels<.02,"Actual inspection waist separated from native hips: "+id+" "+v.maximumWaistJoinPixels);}}
        if(index>=12&&index<=15){
            var grip=SpearGrip.measure(frame.world());
            v.maximumGripLineErrorCm=Math.max(v.maximumGripLineErrorCm,grip.freeHandDistanceCm());v.minimumForwardShaftZ=Math.min(v.minimumForwardShaftZ,grip.chestAxis().z);
            require(grip.clearShaft()&&grip.chestAxis().z>.01&&grip.freeHandDistanceCm()<2,"Native running, stopping, attacks and walking retain the normal forward two-hand grip and complete-pole clearance");
            if(index<=14&&game.player.onGround()&&game.player.isSprinting()&&elapsed>=6&&ClientState.text(ClientState.snapshot(),"phase","READY").equals("READY")){
                v.carryFrames++;
                if(event.getRenderer().getModel() instanceof PlayerModel m){v.minimumLegAngle=Math.min(v.minimumLegAngle,m.rightLeg.xRot);v.maximumLegAngle=Math.max(v.maximumLegAngle,m.rightLeg.xRot);}
            }
        }
        if(index==13&&elapsed>=42){require(stopAimChanged,"The stopped grip must also be tested after changing the aim during the run");require(SpearGrip.leftShaftDistance(frame.world())<2,"Stopping retains the actual two-hand grip");v.twoHandReturnFrames++;}
        if(index==14&&elapsed>=28&&!ClientState.text(ClientState.snapshot(),"phase","READY").equals("READY")){require(SpearGrip.leftShaftDistance(frame.world())<2,"Actual attacks retain the two-hand weapon grip");v.attackReturnFrames++;}
        if(v.previous!=null){for(String part:List.of("right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand")){var a=v.previous.get(part);var b=frame.world().get(part);if(a!=null&&b!=null)v.maximumPoseChange=Math.max(v.maximumPoseChange,a.getTranslation(new Vector3f()).distance(b.getTranslation(new Vector3f())));}}
        var copy=new HashMap<String,Matrix4f>();frame.world().forEach((k,m)->copy.put(k,new Matrix4f(m)));v.previous=copy;
    }catch(Throwable e){failure=e;}}
    private void render(RenderFrameEvent.Post event){if(recording&&!done)try{movie.frame(event);}catch(Throwable e){failure=e;}}
    private void validate(){check(rows.size()==18,"All twelve inspection and six two-hand locomotion/interrupt native cases completed");check(clientClicks==14,"The twelve variants and two interruption checks used the actual H key mapping");for(String style:STYLES)for(String id:WeaponInspections.actions(style)){var v=visuals.get(id.replace('_','-'));check(v!=null&&v.activeFrames>30,"Authored inspection is actually submitted over thirty changing native frames: "+id);check(v.maximumPoseChange>.05,"Actual inspection rig visibly moves: "+id);}for(String id:List.of("spear-fast-run","spear-stop-return","spear-run-attack")){var v=visuals.get(id);check(v.carryFrames>10,"Actual sprint retains the normal two-hand grip: "+id);check(v.maximumLegAngle-v.minimumLegAngle>.05,"Actual legs keep running with the normal two-hand grip: "+id);}check(visuals.get("spear-stop-return").twoHandReturnFrames>10,"Native stopped grip is actually observed");check(visuals.get("spear-run-attack").attackReturnFrames>1,"Native two-handed attack is actually observed");var clip=movie.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Native framebuffer captures at least thirty actual frames per second");check(OfficialHeldItems.submissions()>officialBefore,"Inspections retain actual official ItemStack submissions");}
    private void finish(Throwable error){if(done)return;done=true;movie.stop();try{var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.inspectionCarryWorld"));report.addProperty("manualHClicks",clientClicks);report.addProperty("prewarmTicks",prewarm);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",copied(rows));report.add("serverTrace",copied(serverTrace));report.add("nativeVisuals",RpgConfig.JSON.toJsonTree(visuals));report.add("renderCapture",movie.summary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("inspection-carry-review.json"),RpgConfig.JSON.toJson(report));System.out.println("INSPECTION CARRY COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable reporting){reporting.printStackTrace();}var game=Minecraft.getInstance();KeyMapping.releaseAll();if(initialized){shoulder.set(previousCombatShoulder);game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();}
}

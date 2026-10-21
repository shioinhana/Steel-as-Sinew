package org.fsrpg.smoke;

import com.google.gson.*;
import java.nio.file.*;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Actual packaged avatar, official held items and vanilla jumps; no per-tick player relocation. */
final class RunAirReview {
    private record Case(String id,String style,String kind,boolean front) {}
    private static final List<Case> CASES=List.of(
        new Case("spear-run-rear","spear","run",false),new Case("spear-run-front","spear","run",true),
        new Case("spear-stop-reaim","spear","stop",false),new Case("spear-run-turn","spear","turn",false),
        new Case("spear-run-attack","spear","attack",false),new Case("spear-walk","spear","walk",true),
        new Case("spear-jump","spear","jump",true),new Case("spear-run-jump","spear","runJump",false),
        new Case("rapier-jump","rapier","jump",true),new Case("dual-jump","dual_blade","jump",true),
        new Case("bow-jump","bow_rapid","jump",true),new Case("spear-air-attack","spear","airAttack",true),
        new Case("rapier-air-attack","rapier","airAttack",true),new Case("spear-air-guard","spear","airGuard",true),
        new Case("spear-air-disabled","spear","disabled",true));
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final Object lock=new Object();
    private final JsonArray rows=new JsonArray();
    private final Map<String,Visual> visuals=new LinkedHashMap<>();
    private static final class Visual {
        long frames,carryFrames,airFrames,upFrames,downFrames,landedFrames,activeInertiaFrames,attackFrames,guardFrames,returnFrames;
        double maxWaistPixels,maxBodyPitchDegrees,maxLegPitchDegrees,minAirPitch=100000,maxAirPitch=-100000,maximumAirEpisodeRotationRadians;
        transient boolean priorAir;transient Quaternionf episodeRotation;
        double minLeftArm=100000,maxLeftArm=-100000,minLeg=100000,maxLeg=-100000,maxShaftUp=-100000,maxShaftForward=-100000,maxPalmForward=-100000,maxPalmUp=-100000,maxWorldShaftUp=-100000,minYaw=100000,maxYaw=-100000,attackGripTravel;
        transient Vector3f firstAttackPalm;
    }
    private volatile Throwable failure;
    private volatile boolean initialized,ready,recording,done,begun,scriptDone,clientCaseReady;
    private volatile int index,epoch;
    private volatile long elapsed;
    private volatile String caseId="";
    private UUID playerId;
    private boolean prepared,stopped,attackSent,guardSent,guardReleased,lookSent,secondLookSent;
    private int warmup,prewarm,clientEpoch=-1,previousFps,previousDistance,previousScale;
    private long started,entered,caseStarted,officialBefore;
    private boolean previousPause,previousVsync,previousActive,previousShoulder;
    private double previousInertia;
    private CameraType previousCamera;
    private ModConfigSpec.BooleanValue shoulder;
    private ModConfigSpec.DoubleValue inertia;
    private java.lang.reflect.Method cameraPosition;
    RunAirReview(){
        NeoForge.EVENT_BUS.addListener(this::clientTick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e)->RenderObservations.beginSubmittedFrame(e));
        NeoForge.EVENT_BUS.addListener(this::submitted);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,this::profileCamera);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e)->{if(recording&&!done)try{movie.frame(e);}catch(Throwable t){failure=t;}});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key e)->{if(!done)KeyMapping.releaseAll();});
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e)->{if(!done)e.setCanceled(true);});
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void check(boolean condition,String message){require(condition,message);checks.add(message);System.out.println("RUN AIR PASS "+message);}
    private CombatActor actor(ServerPlayer player){return RpgSmoke.actor(player);}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root);JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Native window is on DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(world.getFileName().toString().equals("FSRPG Run Air Review 20261021 01")&&Files.isRegularFile(world.resolve(".fsrpg-run-air-test-world")),"Only the marked copied test world is used");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        var cls=Class.forName("org.fsrpg.client.ClientOptions");var f=cls.getDeclaredField("THIRD_PERSON");f.setAccessible(true);shoulder=(ModConfigSpec.BooleanValue)f.get(null);previousShoulder=shoulder.get();
        f=cls.getDeclaredField("AIR_INERTIA_STRENGTH");f.setAccessible(true);inertia=(ModConfigSpec.DoubleValue)f.get(null);previousInertia=inertia.get();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.setWindowActive(true);game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG Astra 后置拖枪与空中惯性 · DISPLAY5");game.resizeDisplay();
        var load=Class.forName("org.fsrpg.client.AnimationAssets").getDeclaredMethod("load",String.class);load.setAccessible(true);
        for(String name:List.of("legacy_rapier.fsvoxel","legacy_dual_blade.fsvoxel","legacy_bow.fsvoxel","spear.fsvoxel","legacy_rapier_fp.fsvoxel","legacy_dual_blade_fp.fsvoxel","legacy_bow_fp.fsvoxel"))load.invoke(null,name);
        cameraPosition=net.minecraft.client.Camera.class.getDeclaredMethod("setPosition",Vec3.class);cameraPosition.setAccessible(true);
        started=System.nanoTime();initialized=true;
        game.getSingleplayerServer().execute(()->{try{var p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
            for(var entity:p.level().getEntities(p,p.getBoundingBox().inflate(128),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))entity.discard();
            for(int x=-24;x<=24;x++)for(int z=-24;z<=100;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<86;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            equip(p,"spear");ready=true;
        }catch(Throwable t){failure=t;}});
    }
    private void equip(ServerPlayer p,String style){
        actor(p).forceSheathed();p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);
        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(style.equals("spear")?Items.DIAMOND_SPEAR:style.startsWith("bow")?Items.BOW:Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,style.equals("dual_blade")?new ItemStack(Items.DIAMOND_SWORD):ItemStack.EMPTY);
        actor(p).setStyle(RpgServer.instance().config().current().styles().get(style));RpgServer.instance().action(p,"status","");actor(p).requestDrawn();actor(p).setResources(50,100);
        p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(0,0,0);p.setSprinting(false);
    }
    private static int duration(Case c){return switch(c.kind()){case "stop"->100;case "walk"->60;case "run","turn","attack"->90;default->85;};}
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||!recording||done||scriptDone||failure!=null)return;
        try{if(index>=CASES.size()){scriptDone=true;return;}var p=event.getServer().getPlayerList().getPlayer(playerId);long now=p.level().getGameTime();
            if(!prepared){equip(p,CASES.get(index).style());caseId=CASES.get(index).id();entered=now;elapsed=0;prepared=true;begun=false;clientCaseReady=false;epoch++;return;}
            if(!begun){if(now-entered<16)return;require(actor(p).style().id().equals(CASES.get(index).style()),"Server must retain requested weapon style");require(now-entered<100,"Client weapon/style did not synchronize");if(actor(p).phase()!=CombatPhase.READY||!p.onGround()||!clientCaseReady)return;begun=true;caseStarted=now;return;}
            elapsed=now-caseStarted;
            if(elapsed>=duration(CASES.get(index))){var row=new JsonObject();row.addProperty("id",caseId);row.addProperty("passed",true);row.addProperty("elapsedTicks",elapsed);synchronized(lock){rows.add(row);}index++;prepared=begun=false;}
        }catch(Throwable t){failure=t;}
    }
    private void controls(Minecraft game){
        if(index>=CASES.size())return;var c=CASES.get(index);
        if(clientEpoch!=epoch){KeyMapping.releaseAll();clientEpoch=epoch;attackSent=guardSent=guardReleased=lookSent=secondLookSent=false;shoulder.set(!c.front());inertia.set(c.kind().equals("disabled")?0d:1d);}
        if(c.front())game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        if(!begun)return;
        boolean run=switch(c.kind()){case "run","turn","runJump"->true;case "stop"->elapsed<60;case "attack"->elapsed<45;default->false;};
        game.options.keyUp.setDown(run||c.kind().equals("walk"));game.options.keySprint.setDown(run);if(!run)game.player.setSprinting(false);
        boolean jump=c.kind().contains("Jump")||Set.of("jump","airAttack","airGuard","disabled").contains(c.kind());
        game.options.keyJump.setDown(jump&&(elapsed>=20&&elapsed<22||elapsed>=55&&elapsed<57));
        if(c.kind().equals("stop")&&elapsed>=35&&!lookSent){require(CombatCamera.mouse(0,-350),"Real spear look input accepted before stopping");lookSent=true;}
        if(c.kind().equals("turn")&&elapsed>=30&&!lookSent){require(CombatCamera.mouse(900,0),"Real look input accepted during running");lookSent=true;}
        if(c.kind().equals("turn")&&elapsed>=55&&!secondLookSent){require(CombatCamera.mouse(-1800,0),"Real reverse look input accepted during running");secondLookSent=true;}
        if(c.kind().equals("attack")&&elapsed>=46&&!attackSent||c.kind().equals("airAttack")&&elapsed>=23&&!game.player.onGround()&&!attackSent){ClientHooks.send("attack","left");attackSent=true;}
        if(c.kind().equals("airGuard")&&elapsed>=23&&!game.player.onGround()&&!guardSent){ClientHooks.send("guard","light");guardSent=true;}
        if(c.kind().equals("airGuard")&&elapsed>=34&&guardSent&&!guardReleased){ClientHooks.send("guard","false");guardReleased=true;}
    }
    private void clientTick(ClientTickEvent.Post event){var game=Minecraft.getInstance();if(done||game.player==null||game.level==null||game.getSingleplayerServer()==null)return;try{
        if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Native run/air failure",failure);
        movie.assertHealthy();require(System.nanoTime()-started<300_000_000_000L,"Review exceeded five minutes");game.setWindowActive(true);game.getFramerateLimitTracker().onInputReceived();if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);
        if(ready&&!recording&&++prewarm>=55){officialBefore=OfficialHeldItems.submissions();movie.start("run-air","Astra Ultra rear low spear carry and motion-driven airborne poses: actual vanilla movement, jumps and official held items",this::observation);recording=true;}
        if(recording&&!scriptDone)controls(game);
        if(scriptDone&&!stopped){movie.stop();stopped=true;KeyMapping.releaseAll();}if(scriptDone&&movie.finish()){validate();finish(null);}
    }catch(Throwable t){finish(t);}}
    /** Filming-only quarter of the front run: keep the real player as camera entity and leave input/physics untouched. */
    private void profileCamera(ViewportEvent.ComputeCameraAngles event){
        if(!recording||done||!begun||!caseId.equals("spear-run-front")||elapsed<45)return;
        try{var game=Minecraft.getInstance();float yaw=game.player.getYRot()-90,pitch=8;
            Vec3 target=game.player.position().add(0,1.05,0);
            Vec3 direction=Vec3.directionFromRotation(pitch,yaw);
            cameraPosition.invoke(event.getCamera(),target.subtract(direction.scale(4.6)));
            event.setYaw(yaw);event.setPitch(pitch);event.setRoll(0);
        }catch(Throwable t){failure=t;}
    }
    private JsonObject observation(){var game=Minecraft.getInstance();var o=new JsonObject();o.addProperty("caseId",caseId);o.addProperty("elapsedTicks",elapsed);o.addProperty("onGround",game.player.onGround());o.addProperty("actualVerticalDisplacement",game.player.getY()-game.player.yo);o.addProperty("phase",ClientState.text(ClientState.snapshot(),"phase",""));o.addProperty("view",caseId.equals("spear-run-front")&&elapsed>=45?"FILMING_RIGHT_PROFILE":game.options.getCameraType().name());o.addProperty("carryWeight",PlayerAnimations.spearRunCarryWeight(playerId));o.add("inertia",RpgConfig.JSON.toJsonTree(PlayerAnimations.airInertiaOffset(playerId)));
        try{var actual=RenderObservations.submittedSampler(game.player).get();o.add("rig",actual.remove("rig"));o.add("submittedAvatar",actual);}catch(IllegalStateException absent){o.add("rig",new JsonObject());}return o;}
    private void submitted(RenderLivingEvent.Post<?,?,?> event){RenderObservations.captureSubmittedAvatar(event);var game=Minecraft.getInstance();if(!recording||done||index>=CASES.size()||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{var frame=PlayerAnimations.frame(state);if(frame==null)return;var c=CASES.get(index);String expectedRig=c.style().equals("spear")?"fsrpg.spear":c.style().equals("rapier")?"fsrpg.legacy_rapier":c.style().equals("dual_blade")?"fsrpg.legacy_dual_blade":"fsrpg.legacy_bow";clientCaseReady=frame.model().rig().id().equals(expectedRig)&&ClientState.text(ClientState.snapshot(),"weaponStyle","").equals(c.style());if(!begun)return;var v=visuals.computeIfAbsent(c.id(),id->new Visual());v.frames++;v.minYaw=Math.min(v.minYaw,AttackAim.wrap(game.player.getYRot()));v.maxYaw=Math.max(v.maxYaw,AttackAim.wrap(game.player.getYRot()));
            String phase=ClientState.text(ClientState.snapshot(),"phase","READY");float carry=PlayerAnimations.spearRunCarryWeight(playerId);
            if(carry>.99&&game.player.onGround()){
                var m=SpearRunCarry.measure(frame.world());v.carryFrames++;v.maxShaftUp=Math.max(v.maxShaftUp,m.chestAxis().y);v.maxShaftForward=Math.max(v.maxShaftForward,m.chestAxis().z);v.maxPalmForward=Math.max(v.maxPalmForward,m.chestRear().z);v.maxPalmUp=Math.max(v.maxPalmUp,m.chestRear().y);v.maxWorldShaftUp=Math.max(v.maxWorldShaftUp,m.axis().y);
                require(m.clearShaft()&&m.chestRear().z< -15&&m.chestRear().y<50&&m.chestAxis().z<-.8&&m.chestAxis().y<.05&&m.axis().y<.02&&m.freeHandDistanceCm()>20,"The real pole points behind and down; the right palm stays low behind the torso and the left hand is free");
                if(event.getRenderer().getModel() instanceof PlayerModel model){v.minLeftArm=Math.min(v.minLeftArm,model.leftArm.xRot);v.maxLeftArm=Math.max(v.maxLeftArm,model.leftArm.xRot);v.minLeg=Math.min(v.minLeg,model.rightLeg.xRot);v.maxLeg=Math.max(v.maxLeg,model.rightLeg.xRot);}
            }
            if(c.kind().equals("stop")&&elapsed>=85){require(carry<.001&&SpearRunCarry.leftShaftDistance(frame.world())<2,"Stopping after looking around restores real two-hand grip");v.returnFrames++;}
            if((c.kind().equals("attack")||c.kind().equals("airAttack")&&!game.player.onGround())&&AirPose.attack(phase)){require(carry<.001,"Real attacks immediately override the running carry");v.attackFrames++;var palm=new org.joml.Matrix4f(frame.world().get("body")).invert().transformPosition(frame.world().get("right_hand").getTranslation(new Vector3f()));if(v.firstAttackPalm==null)v.firstAttackPalm=new Vector3f(palm);v.attackGripTravel=Math.max(v.attackGripTravel,palm.distance(v.firstAttackPalm));}
            if(c.kind().equals("walk"))require(carry<.001&&SpearRunCarry.leftShaftDistance(frame.world())<2,"Ordinary walking remains two-handed");
            var offset=PlayerAnimations.airInertiaOffset(playerId);v.maxBodyPitchDegrees=Math.max(v.maxBodyPitchDegrees,Math.abs(offset.pitchDegrees()));v.maxLegPitchDegrees=Math.max(v.maxLegPitchDegrees,Math.abs(offset.legPitchDegrees()));
            require(Math.abs(offset.pitchDegrees())<=3.001&&Math.abs(offset.legPitchDegrees())<=4.001,"Airborne motion stays subtle and bounded");
            if(!game.player.onGround()){
                if(!v.priorAir){v.episodeRotation=null;}v.priorAir=true;
                v.airFrames++;double dy=game.player.getY()-game.player.yo;if(dy>.02)v.upFrames++;if(dy<-.02)v.downFrames++;if(offset.active())v.activeInertiaFrames++;
                if(c.kind().equals("airGuard")&&phase.equals("GUARDING")){require(!offset.active(),"Explicit guard has priority over cosmetic air response");v.guardFrames++;}
                if(Set.of("jump","runJump","disabled").contains(c.kind())){require(phase.equals("READY"),"Jumping alone never substitutes a guard or attack");if(event.getRenderer().getModel() instanceof PlayerModel m){v.minAirPitch=Math.min(v.minAirPitch,m.body.xRot);v.maxAirPitch=Math.max(v.maxAirPitch,m.body.xRot);var rotation=new Quaternionf().rotationZYX(m.body.zRot,m.body.yRot,m.body.xRot);if(v.episodeRotation==null)v.episodeRotation=new Quaternionf(rotation);var difference=new Quaternionf(rotation).mul(new Quaternionf(v.episodeRotation).conjugate()).normalize();double angle=2*Math.atan2(Math.sqrt(difference.x*difference.x+difference.y*difference.y+difference.z*difference.z),Math.abs(difference.w));v.maximumAirEpisodeRotationRadians=Math.max(v.maximumAirEpisodeRotationRadians,angle);require(angle<Math.toRadians(8),"Actual native airborne body movement stays below eight degrees within each jump");}}
            }else {v.priorAir=false;if(elapsed>35){require(!offset.active(),"Landing clears the airborne offset");v.landedFrames++;}}
            if(event.getRenderer().getModel() instanceof PlayerModel m){Vector3f waist=new Vector3f(0,12,0).rotate(new Quaternionf().rotationZYX(m.body.zRot,m.body.yRot,m.body.xRot)).add(m.body.x,m.body.y,m.body.z);var hips=new Vector3f((m.rightLeg.x+m.leftLeg.x)/2,(m.rightLeg.y+m.leftLeg.y)/2,(m.rightLeg.z+m.leftLeg.z)/2);if(frame.supportedLower()!=null&&(frame.model().rig().id().equals("fsrpg.spear")||SwordMotion.applies(frame.model())))waist=new TorsoDeformation(frame.world().get("body"),frame.supportedLower()).point(0,12,0).mul(16);v.maxWaistPixels=Math.max(v.maxWaistPixels,waist.distance(hips));require(v.maxWaistPixels<.025,"Actual native waist remains attached to the pelvis");}
        }catch(Throwable t){failure=t;}}
    private void validate(){
        check(rows.size()==CASES.size(),"All fifteen run, return, jump, action-priority and disabled cases completed");
        for(var c:CASES){var v=visuals.get(c.id());check(v!=null&&v.frames>30,"Actual submitted avatar observed: "+c.id());
            if(c.kind().equals("run")){check(v.carryFrames>70,"Sustained rear low carry across multiple real steps: "+c.id());check(v.maxLeftArm-v.minLeftArm>.15&&v.maxLeg-v.minLeg>.2,"Actual free arm and legs keep running: "+c.id());}
            if(Set.of("jump","runJump","disabled","airAttack","airGuard").contains(c.kind())){check(v.upFrames>3&&v.downFrames>3&&v.landedFrames>10,"Real ascent, descent and landing observed: "+c.id());}
            if(Set.of("jump","runJump").contains(c.kind()))check(v.activeInertiaFrames>10&&v.maximumAirEpisodeRotationRadians>.005,"Actual airborne body responds to changing inertia: "+c.id());
            if(c.kind().equals("disabled"))check(v.activeInertiaFrames==0&&v.maximumAirEpisodeRotationRadians<.0001,"Zero strength keeps the exact retained airborne body");
            if(c.kind().equals("airAttack")||c.kind().equals("attack"))check(v.attackFrames>1&&v.attackGripTravel>.5,"Actual attack still advances: "+c.id());
            if(c.kind().equals("airGuard"))check(v.guardFrames>1,"Real guard overrides the cosmetic air layer");
            if(c.kind().equals("turn"))check(v.maxYaw-v.minYaw>25&&v.carryFrames>30,"Real running turn and sustained trailing carry observed");
            if(c.kind().equals("stop"))check(v.returnFrames>10,"The changed aim is restored after stopping");
        }
        var clip=movie.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Native render capture reaches thirty actual frames per second");check(OfficialHeldItems.submissions()>officialBefore,"Actual official ItemStack submissions retained");
    }
    private void finish(Throwable error){if(done)return;done=true;movie.stop();try{var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.runAirWorld"));report.addProperty("playerTeleportsPerCase",1);report.addProperty("perTickPlayerTeleport",false);report.addProperty("perTickVelocityOverwrite",false);report.add("checks",RpgConfig.JSON.toJsonTree(checks));synchronized(lock){report.add("scenarios",rows.deepCopy());}report.add("nativeVisuals",RpgConfig.JSON.toJsonTree(visuals));report.add("renderCapture",movie.summary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJarSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));RpgConfig.atomicWrite(root.resolve("run-air-review.json"),RpgConfig.JSON.toJson(report));System.out.println("RUN AIR COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable t){t.printStackTrace();}
        var game=Minecraft.getInstance();KeyMapping.releaseAll();if(initialized){shoulder.set(previousShoulder);inertia.set(previousInertia);game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
}

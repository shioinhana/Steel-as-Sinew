package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Actual client key/gesture/network integration. No direct server attack or gesture method calls. */
final class SpearGestureReview {
    private enum Stage { WAIT_READY, FIRST_PRESS, HOLD_AND_DRAG, RELEASE, SECOND_PRESS, SECOND_HOLD,
        FOCUS_CANCEL, RESTORED_WHILE_HELD, FINISH }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray serverTrace=new JsonArray(),nativeTrace=new JsonArray();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private final Map<String,Field> fields=new HashMap<>();
    private volatile Stage stage=Stage.WAIT_READY;
    private volatile Throwable failure;
    private volatile boolean ready,clientReady,desiredHeld,desiredFocus=true,clientGestureHeld,scriptDone;
    private volatile long stageElapsed;
    private boolean initialized,done;
    private int warmup,previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private double previousSensitivity;
    private KeyMapping left;
    private UUID playerId;
    private long started,stageStarted,baselineActorTick,baselineSequence,firstSequence,secondSequence;
    private long frames,heldFrames,firstHeldNano,lastHeldNano,mouseEvents,cameraFrames,officialBefore,overflowServerTicks;
    private volatile boolean overflowApplied;
    private volatile double overflowRootTurnDegrees;
    private double firstStamina,maxAxisError,maxHeldClockError,minServerYaw=Double.POSITIVE_INFINITY,maxServerYaw=Double.NEGATIVE_INFINITY;
    private double minimumCursorYaw=Double.POSITIVE_INFINITY,maximumCursorYaw=Double.NEGATIVE_INFINITY;
    private JsonObject lastNative=new JsonObject();

    SpearGestureReview(){
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(this::submitted);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.add(message);System.out.println("SPEAR GESTURE PASS "+message);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private RpgServer rpg(){return RpgServer.instance();}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Real client gesture window stays on DISPLAY5");
        String name=System.getProperty("fsrpg.spearGestureWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        boolean independent=name.equals("FSRPG Spear Gesture Review 20261018 01")&&Files.isRegularFile(world.resolve(".fsrpg-spear-gesture-test-world"));
        boolean reused=name.equals("FSRPG Combat Revision Review 20261018 01")&&Files.isRegularFile(world.resolve(".fsrpg-combat-revision-test-world"));
        check((independent||reused)&&world.getFileName().toString().equals(name),"Only a named and marked copied gesture/revision test world is modified");
        playerId=game.player.getUUID();left=Arrays.stream(game.options.keyMappings).filter(key->key.getName().equals("key.fsrpg.left")).findFirst().orElseThrow();
        previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousSensitivity=game.options.sensitivity().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.sensitivity().set(.5);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);
        GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 长枪真实输入链路验收 · DISPLAY5");game.resizeDisplay();
        officialBefore=OfficialHeldItems.submissions();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try{
                ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
                p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
                for(int x=-12;x<=12;x++)for(int z=-12;z<=20;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
                p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
                check(actor(p).setStyle(rpg().config().current().styles().get("spear"))==ActionResult.ACCEPTED,"Real copied-world player equips the spear style");
                rpg().action(p,"status","");if(!actor(p).drawn()&&actor(p).phase()==CombatPhase.SHEATHED)rpg().action(p,"draw","");
                ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    private void enter(Stage next,long now){stage=next;stageStarted=now;stageElapsed=0;}
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||done||scriptDone||failure!=null)return;
        try{
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;CombatActor a=actor(p);long now=p.level().getGameTime();stageElapsed=now-stageStarted;
            JsonObject trace=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();trace.addProperty("worldGameTick",now);trace.addProperty("stage",stage.name());trace.addProperty("held",a.spearAttackHeld());trace.addProperty("desiredLeftDown",desiredHeld);trace.addProperty("desiredClientActive",desiredFocus);trace.addProperty("observedClientGestureHeld",clientGestureHeld);if(a.attackAim()!=null)trace.add("aim",RpgConfig.JSON.toJsonTree(a.attackAim()));serverTrace.add(trace);
            switch(stage){
                case WAIT_READY -> {
                    if(a.phase()!=CombatPhase.READY||!clientReady)return;
                    a.setResources(40,100);firstStamina=a.stamina();baselineActorTick=a.snapshot().serverTick();baselineSequence=a.snapshot().swingSequence();desiredHeld=true;enter(Stage.FIRST_PRESS,now);
                }
                case FIRST_PRESS -> {
                    if(a.currentSkill()!=null&&a.spearAttackHeld()){
                        firstSequence=a.snapshot().swingSequence();check(firstSequence==baselineSequence+1,"Real left key press starts exactly one server swing through client gesture and network");
                        double reserved=firstStamina+(a.snapshot().serverTick()-baselineActorTick)*a.staminaRegenPerSecond()/20-a.stamina();
                        check(Math.abs(reserved-10)<.02,"Client-generated press reserves the real ten-stamina spear cost: "+reserved);
                        check(clientGestureHeld,"Client gesture remains held after its real press dispatch");shots.add("01-real-client-press");enter(Stage.HOLD_AND_DRAG,now);
                    }else require(stageElapsed<30,"Client key press never reached the actual server spear adapter");
                }
                case HOLD_AND_DRAG -> {
                    require(a.spearAttackHeld()&&a.currentSkill()!=null&&a.snapshot().swingSequence()==firstSequence,"Continuous physical-key state lost or restarted the paid spear swing");
                    if(overflowApplied)overflowServerTicks++;
                    var point=a.attackAim().sample(1);double relative=AttackAim.wrap(point.yaw()-p.getYRot());minServerYaw=Math.min(minServerYaw,relative);maxServerYaw=Math.max(maxServerYaw,relative);
                    if(stageElapsed>=40){check(a.phase()==CombatPhase.ACTIVE,"Real client keepalive retains ACTIVE longer than one second");check(maxServerYaw-minServerYaw>1,"Actual client mouse movement changes the server's accepted held aim: "+(maxServerYaw-minServerYaw));check(overflowApplied&&overflowRootTurnDegrees>30&&overflowServerTicks>=3,"Real 1200-pixel overflow turns the actor while the original server hold survives the rotation/move packet gap");desiredHeld=false;shots.add("02-real-client-held-drag");enter(Stage.RELEASE,now);}
                }
                case RELEASE -> {
                    if(a.phase()==CombatPhase.READY&&a.currentSkill()==null&&!a.spearAttackHeld()&&stageElapsed>=6){check(a.snapshot().swingSequence()==firstSequence&&!clientGestureHeld,"Real key release retracts without a second automatic thrust");desiredHeld=true;enter(Stage.SECOND_PRESS,now);}
                    else require(stageElapsed<20,"Real key release did not return spear to READY");
                }
                case SECOND_PRESS -> {
                    if(a.spearAttackHeld()&&a.currentSkill()!=null){secondSequence=a.snapshot().swingSequence();check(secondSequence==firstSequence+1,"A fresh real press after release starts one new paid swing");enter(Stage.SECOND_HOLD,now);}
                    else require(stageElapsed<20,"Fresh client press failed after the previous release");
                }
                case SECOND_HOLD -> {
                    require(a.spearAttackHeld()&&a.snapshot().swingSequence()==secondSequence,"Second client hold restarted its swing");
                    if(stageElapsed>=10){desiredFocus=false;shots.add("03-before-focus-cancel");enter(Stage.FOCUS_CANCEL,now);}
                }
                case FOCUS_CANCEL -> {
                    if(a.phase()==CombatPhase.READY&&a.currentSkill()==null&&!a.spearAttackHeld()){
                        check(stageElapsed<12,"Client loss-of-focus release cancels before the server's twelve-tick lease expires");check(!clientGestureHeld,"Actual client gesture clears on loss of focus");
                        desiredFocus=true;enter(Stage.RESTORED_WHILE_HELD,now);
                    }else require(stageElapsed<20,"Loss of focus never canceled the actual server spear hold");
                }
                case RESTORED_WHILE_HELD -> {
                    require(a.snapshot().swingSequence()==secondSequence&&!a.spearAttackHeld()&&a.currentSkill()==null,"Restoring focus while the original key stays down auto-fired another spear");
                    if(stageElapsed>=8){check(!clientGestureHeld,"Restored focus stays blocked until an actual key release");desiredHeld=false;shots.add("04-released-after-focus-cancel");enter(Stage.FINISH,now);}
                }
                case FINISH -> {if(stageElapsed>=4)scriptDone=true;}
            }
        }catch(Throwable error){failure=error;}
    }
    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Actual client spear gesture review failed",failure);
            require(System.nanoTime()-started<90_000_000_000L,"Actual client gesture review exceeded ninety seconds");
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();
            game.setWindowActive(desiredFocus);left.setDown(desiredHeld);game.options.keyUse.setDown(false);
            clientReady=ClientState.drawn()&&CombatCamera.style().equals("spear")&&CombatCamera.active()&&game.screen==null;
            if(stage==Stage.HOLD_AND_DRAG&&stageElapsed>=10&&stageElapsed<=32){
                double dx=stageElapsed<=23?4:-4;checkMouse(CombatCamera.mouse(dx,0));
                var cursor=CombatCamera.aimPoint();double relative=AttackAim.wrap(cursor.yaw()-game.player.getYRot());minimumCursorYaw=Math.min(minimumCursorYaw,relative);maximumCursorYaw=Math.max(maximumCursorYaw,relative);
            }
            if(stage==Stage.HOLD_AND_DRAG&&stageElapsed>=33&&!overflowApplied){
                float before=game.player.getYRot();checkMouse(CombatCamera.mouse(1200,0));
                overflowRootTurnDegrees=Math.abs(AttackAim.wrap(game.player.getYRot()-before));overflowApplied=true;
                require(overflowRootTurnDegrees>30,"Large cursor motion did not create the intended real actor overflow turn");
            }
            clientGestureHeld=CombatCamera.gestureHeld();
            if(scriptDone&&shots.isEmpty()&&pendingShots.get()==0){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void checkMouse(boolean accepted){require(accepted,"Real spear CombatCamera rejected scripted cursor motion");mouseEvents++;}
    private Object field(Class<?> type,Object instance,String name)throws ReflectiveOperationException{
        String key=type.getName()+"#"+name;Field f=fields.get(key);if(f==null){f=type.getDeclaredField(name);f.setAccessible(true);fields.put(key,f);}return f.get(instance);
    }
    private void submitted(RenderLivingEvent.Post<?,?,?> event){
        RenderObservations.captureSubmittedAvatar(event);Minecraft game=Minecraft.getInstance();
        if(!initialized||done||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{
            PlayerAnimations.Frame frame=PlayerAnimations.frame(state);if(frame==null||!frame.model().rig().id().equals("fsrpg.spear"))return;
            require(game.getCameraEntity()==game.player&&game.options.getCameraType()==CameraType.THIRD_PERSON_BACK,"Actual submitted gesture rig uses a detached camera");cameraFrames++;
            @SuppressWarnings("unchecked") Map<UUID,Object> bindings=(Map<UUID,Object>)field(PlayerAnimations.class,null,"bindings");Object binding=bindings.get(game.player.getUUID());if(binding==null)return;
            RpgAnimationPayload payload=(RpgAnimationPayload)field(binding.getClass(),binding,"state");
            if(stage!=Stage.HOLD_AND_DRAG||stageElapsed<5||!payload.spearHeld()||!payload.phase().equals("ACTIVE")||payload.aim()==null)return;
            long nativeNow=System.nanoTime();if(firstHeldNano==0)firstHeldNano=nativeNow;lastHeldNano=nativeNow;heldFrames++;
            double clock=((Number)field(binding.getClass(),binding,"sampledElapsedTicks")).doubleValue();double clockError=Math.abs(clock-payload.windupTicks()-payload.activeTicks());maxHeldClockError=Math.max(maxHeldClockError,clockError);require(clockError<.0001,"Native client gesture does not retain the extended source endpoint");
            var aim=payload.aim().sample(1);float partial=game.getDeltaTracker().getGameTimeDeltaPartialTick(false);float bodyYaw=Mth.rotLerp(partial,game.player.yBodyRotO,game.player.yBodyRot);
            double yaw=Math.toRadians(AttackAim.wrap(aim.yaw()-bodyYaw)),pitch=Math.toRadians(aim.pitch()),range=aim.distance()*100;
            Vector3f target=new Vector3f((float)(-Math.sin(yaw)*Math.cos(pitch)*range),(float)(game.player.getEyeHeight()*100-Math.sin(pitch)*range),(float)(Math.cos(yaw)*Math.cos(pitch)*range));
            var hand=frame.world().get("right_hand");Vector3f palm=hand.transformPosition(new Vector3f(0,-6.25f,0)),axis=hand.transformDirection(new Vector3f(0,0,1)).normalize();
            Vector3f expected=target.sub(palm,new Vector3f()).normalize();double angle=Math.toDegrees(Math.acos(Math.clamp(axis.dot(expected),-1,1)));maxAxisError=Math.max(maxAxisError,angle);require(Double.isFinite(angle),"Actual rendered spear shaft has non-finite aim geometry");
            JsonObject sample=RenderObservations.submittedSampler(game.player).get();sample.addProperty("stage",stage.name());sample.addProperty("nativeNanoTime",nativeNow);sample.addProperty("sourceEndpointClockError",clockError);sample.addProperty("shaftToAcceptedTargetDegrees",angle);sample.add("acceptedAim",RpgConfig.JSON.toJsonTree(aim));nativeTrace.add(sample);lastNative=sample;
        }catch(Throwable error){failure=error;}
    }
    private void preRender(RenderFrameEvent.Pre event){Minecraft game=Minecraft.getInstance();if(initialized&&!done&&game.player!=null){game.setCameraEntity(game.player);game.options.setCameraType(CameraType.THIRD_PERSON_BACK);}RenderObservations.beginSubmittedFrame(event);}
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;frames++;try{String name;while((name=shots.poll())!=null){pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}}
    private double heldHz(){return heldFrames>1&&lastHeldNano>firstHeldNano?(heldFrames-1)*1e9/(lastHeldNano-firstHeldNano):0;}
    private static double span(double minimum,double maximum){return Double.isFinite(minimum)&&Double.isFinite(maximum)?maximum-minimum:0;}
    private void validate(){
        check(mouseEvents>10&&maxServerYaw-minServerYaw>1,"Actual cursor-to-network pipeline changes held server aim");
        check(heldFrames>30&&lastHeldNano-firstHeldNano>1_000_000_000L,"Actual submitted held gesture spans more than one second of native player frames");
        check(heldHz()>=30,"Native submitted held player rig really samples at least thirty Hz: "+heldHz());
        check(Double.isFinite(maxAxisError)&&maxHeldClockError<.0001,"Actual source pose remains extended and records finite shaft-to-target geometry");
        check(OfficialHeldItems.submissions()>officialBefore&&cameraFrames>30,"Real local player and official ItemStack render during gesture input");
        check(capturedShots.get()==4,"Four actual framebuffer gesture screenshots were saved");
    }
    private void finish(Throwable error){
        if(done)return;done=true;
        try{
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.spearGestureWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("actualNativeFrames",frames);report.addProperty("heldSourceFrames",heldFrames);report.addProperty("heldSourceHz",heldHz());report.addProperty("axisMaxErrorDegrees",maxAxisError);report.addProperty("sourceEndpointMaxError",maxHeldClockError);report.addProperty("serverAimYawSpanDegrees",span(minServerYaw,maxServerYaw));report.addProperty("clientCursorYawSpanDegrees",span(minimumCursorYaw,maximumCursorYaw));report.addProperty("clientMouseEvents",mouseEvents);report.addProperty("screenshots",capturedShots.get());
            report.addProperty("inputPipeline","KeyMapping key.fsrpg.left.setDown -> existing ClientHooks.tick -> CombatCamera.gesture/sendSpear -> real client payload -> server spearIntent; CombatCamera.mouse for actual cursor movement");report.addProperty("directServerAttackCalls",0);report.addProperty("nativePoseProvenance","RenderLivingEvent.Post actual local-player avatar submission; no detached preview");report.addProperty("recording","Four real framebuffer screenshots and native submitted-frame geometry/timestamps; no additional video claimed");
            report.addProperty("overflowTested",overflowApplied);report.addProperty("overflowRootTurnDegrees",overflowRootTurnDegrees);report.addProperty("heldServerTicksAfterOverflow",overflowServerTicks);
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("serverTickTrace",serverTrace);report.add("nativeSubmittedTrace",nativeTrace);report.add("lastNativeSubmission",lastNative);RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("spear-gesture-review.json"),RpgConfig.JSON.toJson(report));System.out.println("SPEAR GESTURE REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.sensitivity().set(previousSensitivity);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
}

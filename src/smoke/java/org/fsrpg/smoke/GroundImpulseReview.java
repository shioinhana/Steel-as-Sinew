package org.fsrpg.smoke;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
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
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgBurstDashPayload;
import org.lwjgl.glfw.GLFW;

/** Packaged-JAR one-shot impulse through real networking, local-player physics, floor friction and collision. */
final class GroundImpulseReview {
    private enum Scenario { GROUND_COAST, ICE_COAST, WALL_COLLISION, GROUND_JUMP, HELD_VANILLA_TURN, AIR_REJECT_THEN_LAND }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray rows=new JsonArray(),serverTrace=new JsonArray(),clientTrace=new JsonArray(),nativeTrace=new JsonArray();
    private final Object evidenceLock=new Object();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private volatile Throwable failure;
    private volatile Scenario scenario=Scenario.GROUND_COAST;
    private volatile boolean ready,scriptDone,begun,requestQueued,freshAfterLanding;
    private volatile boolean clientReady,clientJumpRequested,clientJumpIssued,clientTurnIssued,clientAppliedVerified;
    private volatile long elapsed,requestId=81000,clientApplications,clientLastAppliedTick;
    private volatile double clientX,clientY,clientZ,clientHorizontal,clientVertical,jumpPeak;
    private volatile boolean clientGrounded,clientAscending,clientDescending,clientLanded,clientSprinting;
    private boolean initialized,done,prepared,airSent,airVerified,turnObserved;
    private int warmup,index,jumpReleaseTicks,previousFps,previousDistance,previousScale,generation,seenGeneration=-1;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private UUID playerId;
    private long started,preparedAt,caseStarted,serverSequence,nativeFrames,firstNativeNano,lastNativeNano,officialBefore;
    private double startX,startY,startZ,staminaBefore,integratedRegen,normalDistance,previousCoastSpeed,decaySum;
    private int decaySamples;
    private JsonObject row;

    GroundImpulseReview(){
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(this::submitted);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("GROUND IMPULSE PASS "+message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void near(double expected,double actual,double tolerance,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,message+" expected="+expected+" actual="+actual);}
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private static JsonObject vector(Vec3 value){var json=new JsonObject();json.addProperty("x",value.x);json.addProperty("y",value.y);json.addProperty("z",value.z);return json;}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root);JsonObject environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(environment,"monitorAdapter","")),"Actual ground-impulse window is on DISPLAY5");
        String name=System.getProperty("fsrpg.groundImpulseWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.equals("FSRPG Ground Impulse Review 20261020 01")&&world.getFileName().toString().equals(name)
                &&Files.isRegularFile(world.resolve(".fsrpg-ground-impulse-test-world")),"Only the named marked copied ground-impulse world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=environment.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);
        GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 0.7.9 单次速度冲量与原版物理 · DISPLAY5");game.resizeDisplay();
        officialBefore=OfficialHeldItems.submissions();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{try{
            ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            for(var entity:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))entity.discard();
            p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(false);p.removeAllEffects();p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
            p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
            ready=true;
        }catch(Throwable error){failure=error;}});
    }
    private void prepare(ServerPlayer p,long now){
        actor(p).cancelBurstDashInput();actor(p).forceSheathed();
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        check(actor(p).setStyle(rpg().config().current().styles().get("spear"))==ActionResult.ACCEPTED,"Real spear style selected for "+scenario);
        for(int x=-16;x<=16;x++)for(int z=-12;z<=48;z++){
            p.level().setBlock(new BlockPos(x,79,z),(scenario==Scenario.ICE_COAST?Blocks.PACKED_ICE:Blocks.SMOOTH_STONE).defaultBlockState(),2);
            for(int y=80;y<86;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
        if(scenario==Scenario.WALL_COLLISION)for(int x=-3;x<=3;x++)for(int y=80;y<=83;y++)p.level().setBlock(new BlockPos(x,y,2),Blocks.STONE.defaultBlockState(),2);
        rpg().action(p,"status","");p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.setSprinting(false);actor(p).setResources(40,100);
        preparedAt=now;prepared=true;begun=false;clientReady=false;requestQueued=freshAfterLanding=false;airSent=airVerified=turnObserved=false;elapsed=0;
        clientJumpRequested=clientJumpIssued=clientTurnIssued=clientAppliedVerified=false;jumpPeak=0;clientAscending=clientDescending=clientLanded=clientSprinting=false;jumpReleaseTicks=0;
        previousCoastSpeed=decaySum=0;decaySamples=0;requestId++;generation++;
        row=new JsonObject();row.addProperty("id",scenario.name().toLowerCase(Locale.ROOT).replace('_','-'));row.addProperty("requestId",requestId);
    }
    private void begin(ServerPlayer p,long now){
        begun=true;caseStarted=now;startX=p.getX();startY=p.getY();startZ=p.getZ();staminaBefore=actor(p).stamina();integratedRegen=0;serverSequence=actor(p).burstDashSnapshot().sequence();
        if(scenario==Scenario.AIR_REJECT_THEN_LAND)clientJumpRequested=true;else requestQueued=true;
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||done||scriptDone||failure!=null)return;
        try{
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;long now=p.level().getGameTime();
            if(index>=Scenario.values().length){scriptDone=true;return;}scenario=Scenario.values()[index];
            if(!prepared){prepare(p,now);return;}var a=actor(p);
            if(!begun){if(now-preparedAt>=16&&clientReady&&a.phase()==CombatPhase.READY&&p.onGround()&&Math.abs(clientZ-p.getZ())<.3)begin(p,now);return;}
            elapsed=now-caseStarted;integratedRegen+=a.currentStaminaRegenPerSecond()/20;
            var trace=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();trace.addProperty("scenario",scenario.name());trace.addProperty("elapsed",elapsed);trace.addProperty("worldGameTick",now);
            trace.add("position",vector(p.position()));trace.add("velocity",vector(p.getDeltaMovement()));trace.add("burst",RpgConfig.JSON.toJsonTree(a.burstDashSnapshot()));synchronized(evidenceLock){serverTrace.add(trace);}
            if(elapsed==2)check(!a.invulnerable()&&!p.isInvulnerable(),"One-shot Shift supplies no invulnerability in "+scenario);
            if(scenario==Scenario.AIR_REJECT_THEN_LAND){
                if(!airSent&&p.getY()>startY+.1&&!p.onGround()){requestQueued=true;airSent=true;}
                if(airSent&&!airVerified&&elapsed>=30&&p.onGround()){
                    check(a.burstDashSnapshot().sequence()==serverSequence&&clientApplications==0,"Airborne request and held landing produce no velocity authorization");
                    near(0,staminaBefore+integratedRegen-a.stamina(),.02,"Airborne request costs zero and leaves normal regeneration intact");
                    check(jumpPeak>=1&&clientAscending&&clientDescending&&clientLanded,"Rejected air input preserves the full natural jump and landing");
                    row.addProperty("airRejectCost",staminaBefore+integratedRegen-a.stamina());freshAfterLanding=true;airVerified=true;
                }
                if(airVerified&&elapsed>=48){check(a.burstDashSnapshot().sequence()==serverSequence+1&&clientApplications==1,"Release then fresh ground press produces exactly one subsequent authorization");finishCase(p);}
            } else if(elapsed>=34){
                check(a.burstDashSnapshot().sequence()==serverSequence+1,"Exactly one server sequence was created for "+scenario);
                check(clientApplications==1,"Exactly one real client XZ assignment occurred for "+scenario);
                double distance=p.getZ()-startZ;row.addProperty("serverForwardDistance",distance);row.addProperty("clientForwardDistance",clientZ-startZ);
                if(scenario==Scenario.GROUND_COAST){normalDistance=distance;near(2.97,distance,.35,"Normal floor friction naturally settles the no-input impulse near three blocks");check(decaySamples>=3,"Actual local travel records multiple natural ground decay samples");near(.546,decaySum/decaySamples,.04,"Observed ground velocity follows vanilla block friction times 0.91");}
                else if(scenario==Scenario.ICE_COAST){check(distance>normalDistance*2.5,"Same one-shot speed coasts farther on real packed ice");near(.8918,decaySum/Math.max(1,decaySamples),.04,"Observed ice velocity follows its distinct vanilla friction");}
                else if(scenario==Scenario.WALL_COLLISION){check(p.getZ()<=1.701&&p.getZ()>1.3,"Real wall collision stops the ordinary local-player motion at its bounding box");near(0,clientHorizontal,.01,"No repeated custom velocity pushes the player against the wall");}
                else if(scenario==Scenario.GROUND_JUMP){check(jumpPeak>=1&&clientAscending&&clientDescending&&clientLanded,"Ground impulse allows a later real vanilla jump with natural ascent, gravity and landing");}
                else if(scenario==Scenario.HELD_VANILLA_TURN){check(clientSprinting,"Held scenario uses actual vanilla sprint input and state");check(Math.abs(p.getX()-startX)>1,"Real WASD changes direction after the initial velocity without a frozen world vector");row.addProperty("sidewaysDistanceAfterTurn",p.getX()-startX);}
                finishCase(p);
            }
            require(elapsed<100,"Ground-impulse case exceeded five seconds: "+scenario);
        }catch(Throwable error){failure=error;}
    }
    private void finishCase(ServerPlayer p){
        var a=actor(p);near(5,staminaBefore+integratedRegen-a.stamina(),.025,"One accepted impulse costs exactly five after integrating real recovery: "+scenario);
        row.addProperty("passed",true);row.addProperty("actualElapsedTicks",elapsed);row.addProperty("finalNetStaminaCost",staminaBefore+integratedRegen-a.stamina());
        row.addProperty("clientVelocityApplications",clientApplications);row.addProperty("decaySamples",decaySamples);row.addProperty("observedMeanDecay",decaySamples>0?decaySum/decaySamples:0);
        row.addProperty("jumpPeak",jumpPeak);row.add("finalServerPosition",vector(p.position()));synchronized(evidenceLock){rows.add(row);}
        shots.add(row.get("id").getAsString());index++;prepared=begun=false;requestQueued=freshAfterLanding=clientJumpRequested=false;
    }
    private void sendImpulse(Minecraft game,boolean release){
        ClientHooks.send("burstDash","down 0 1 "+requestId);if(release)ClientHooks.send("burstDash","up");
    }
    private void tick(ClientTickEvent.Post event){
        var game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Ground-impulse review failed",failure);
            require(System.nanoTime()-started<180_000_000_000L,"Ground-impulse review exceeded three minutes");game.setWindowActive(true);game.getFramerateLimitTracker().onInputReceived();
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);
            if(seenGeneration!=generation){KeyMapping.releaseAll();CombatCamera.reset();BurstDashMotion.reset();seenGeneration=generation;clientApplications=0;clientLastAppliedTick=-1;clientReady=true;}
            game.options.keyUp.setDown(false);game.options.keyDown.setDown(false);game.options.keyLeft.setDown(false);game.options.keyRight.setDown(false);game.options.keySprint.setDown(false);
            if(requestQueued){
                boolean correctGround=scenario==Scenario.AIR_REJECT_THEN_LAND?!game.player.onGround():game.player.onGround();
                if(correctGround){requestQueued=false;sendImpulse(game,scenario!=Scenario.HELD_VANILLA_TURN&&scenario!=Scenario.AIR_REJECT_THEN_LAND);}
            }
            if(freshAfterLanding&&game.player.onGround()){freshAfterLanding=false;ClientHooks.send("burstDash","up");requestId++;sendImpulse(game,true);}
            clientApplications=BurstDashMotion.applicationCount();clientLastAppliedTick=BurstDashMotion.lastAppliedTick();
            if(begun&&scenario==Scenario.HELD_VANILLA_TURN&&clientApplications==1&&elapsed<29){
                game.options.keySprint.setDown(true);
                if(elapsed<14)game.options.keyUp.setDown(true);else game.options.keyRight.setDown(true);
                if(elapsed%4==0)ClientHooks.send("burstDash","down -1 0 "+requestId);
            } else if(begun&&scenario==Scenario.HELD_VANILLA_TURN&&elapsed>=29)ClientHooks.send("burstDash","up");
            if(begun&&scenario==Scenario.AIR_REJECT_THEN_LAND&&airSent&&!airVerified&&elapsed%4==0)ClientHooks.send("burstDash","down 0 1 "+requestId);
            if(scenario==Scenario.GROUND_JUMP&&begun&&clientApplications==1&&!clientJumpIssued)clientJumpRequested=true;
            if(clientJumpRequested&&!clientJumpIssued){clientJumpIssued=true;clientJumpRequested=false;jumpReleaseTicks=1;game.options.keyJump.setDown(true);}
            else if(jumpReleaseTicks>0){jumpReleaseTicks--;game.options.keyJump.setDown(false);}else game.options.keyJump.setDown(false);
            clientX=game.player.getX();clientY=game.player.getY();clientZ=game.player.getZ();clientGrounded=game.player.onGround();clientHorizontal=game.player.getDeltaMovement().horizontalDistance();clientVertical=game.player.getDeltaMovement().y;
            if(begun){
                jumpPeak=Math.max(jumpPeak,clientY-startY);clientAscending|=clientVertical>.02;clientDescending|=clientY>startY+.1&&clientVertical<-.02;if(jumpPeak>1&&clientGrounded)clientLanded=true;clientSprinting|=game.player.isSprinting();
                if((scenario==Scenario.GROUND_COAST||scenario==Scenario.ICE_COAST)&&clientApplications==1&&clientHorizontal>.01&&previousCoastSpeed>.01&&clientHorizontal<previousCoastSpeed){decaySum+=clientHorizontal/previousCoastSpeed;decaySamples++;}previousCoastSpeed=clientHorizontal;
                if(clientApplications==1){if(!clientAppliedVerified){near(0,BurstDashMotion.lastAppliedX(),.00001,"Applied velocity keeps requested X component for "+scenario);near(1.35,BurstDashMotion.lastAppliedZ(),.00001,"Applied Z speed is 27 blocks per second once for "+scenario);clientAppliedVerified=true;}
                    if(elapsed==10){long before=BurstDashMotion.applicationCount();var duplicate=new RpgBurstDashPayload(game.player.getUUID(),game.player.getId(),game.level.dimension().identifier().toString(),requestId,BurstDashMotion.sequence(),game.level.getGameTime(),0,1.35);
                        BurstDashMotion.accept(duplicate);BurstDashMotion.accept(duplicate);check(BurstDashMotion.applicationCount()==before,"Repeated production receipt cannot reapply an existing impulse in "+scenario);}}
                var sample=new JsonObject();sample.addProperty("scenario",scenario.name());sample.addProperty("clientGameTick",game.level.getGameTime());sample.addProperty("nativeNanoTime",System.nanoTime());sample.addProperty("elapsed",elapsed);sample.add("position",vector(game.player.position()));sample.add("velocity",vector(game.player.getDeltaMovement()));sample.addProperty("onGround",clientGrounded);sample.addProperty("sprinting",game.player.isSprinting());sample.addProperty("velocityApplications",clientApplications);clientTrace.add(sample);
            }
            if(scriptDone&&shots.isEmpty()&&pendingShots.get()==0){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void preRender(RenderFrameEvent.Pre event){RenderObservations.beginSubmittedFrame(event);}
    private void submitted(RenderLivingEvent.Post<?,?,?> event){
        RenderObservations.captureSubmittedAvatar(event);var game=Minecraft.getInstance();
        if(!initialized||done||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{require(game.getCameraEntity()==game.player&&game.options.getCameraType()==CameraType.THIRD_PERSON_BACK,"Native impulse frames render the real shoulder-camera avatar");
            long now=System.nanoTime();if(firstNativeNano==0)firstNativeNano=now;lastNativeNano=now;nativeFrames++;
            var frame=RenderObservations.submittedSampler(game.player).get();frame.addProperty("scenario",scenario.name());frame.addProperty("nativeNanoTime",now);nativeTrace.add(frame);
        }catch(Throwable error){failure=error;}
    }
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;try{String name;while((name=shots.poll())!=null){pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}}
    private double nativeHz(){return nativeFrames>1&&lastNativeNano>firstNativeNano?(nativeFrames-1)*1e9/(lastNativeNano-firstNativeNano):0;}
    private void validate(){int count;synchronized(evidenceLock){count=rows.size();}check(count==Scenario.values().length,"All six real one-shot physics scenarios passed");check(nativeFrames>30&&nativeHz()>=30,"Actual submitted avatar frames sample at least thirty Hz: "+nativeHz());check(OfficialHeldItems.submissions()>officialBefore,"Actual local player uses official ItemStack rendering");check(capturedShots.get()>=Scenario.values().length,"Actual framebuffer screenshots cover every physics case");}
    private void finish(Throwable error){
        if(done)return;done=true;try{
            var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.groundImpulseWorld"));
            report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("nativeFrames",nativeFrames);report.addProperty("nativeSourceHz",nativeHz());report.addProperty("screenshots",capturedShots.get());report.addProperty("playerTeleportsPerCase",1);report.addProperty("perTickPlayerTeleport",false);report.addProperty("perTickVelocityOverwrite",false);
            report.addProperty("inputProvenance","Production ClientHooks burstDash intent, real network authorization and single BurstDashMotion velocity receipt; actual vanilla keyUp/keyRight/keySprint/keyJump drive subsequent local-player travel. Shift physical edges/air/guard/focus policy are pure-tested; the background fixture does not fake native GLFW Shift state or claim real human key events. Air case deliberately sends a forbidden client intent to verify the authoritative rejection. Duplicate packets re-enter the real production receipt.");
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));synchronized(evidenceLock){report.add("scenarios",rows.deepCopy());report.add("serverTickTrace",serverTrace.deepCopy());}report.add("clientTickTrace",clientTrace.deepCopy());report.add("nativeSubmittedTrace",nativeTrace.deepCopy());RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("ground-impulse-review.json"),RpgConfig.JSON.toJson(report));System.out.println("GROUND IMPULSE REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        var game=Minecraft.getInstance();KeyMapping.releaseAll();if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
}

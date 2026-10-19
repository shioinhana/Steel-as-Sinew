package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** Real local-player travel and actual living-target impulses. No detached model or per-tick player teleport. */
final class BurstSwipeReview {
    private enum Scenario { GROUND_TAP, HELD_RUN, JUMP_BURST, SWIPE_LEFT, SWIPE_RIGHT, GROUND_DOWN, AIR_DOWN }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray rows=new JsonArray(),serverTrace=new JsonArray(),clientTrace=new JsonArray(),nativeTrace=new JsonArray();
    private final Object evidenceLock=new Object();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private volatile Throwable failure;
    private volatile Scenario scenario=Scenario.GROUND_TAP;
    private volatile boolean ready,scriptDone,jumpRequested,jumpIssued;
    private volatile double clientX,clientY,clientZ,clientDy,jumpPeak;
    private volatile boolean clientBurstUp,clientBurstDown,clientLanded;
    private volatile long elapsed;
    private boolean initialized,done,prepared,begun,burstStarted,contactCaptured;
    private int warmup,index,jumpReleaseTicks,previousFps,previousDistance,previousScale,contacts;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private UUID playerId;
    private long started,preparedAt,caseStarted,swingSequence,inputId=7000,nativeFrames,firstNativeNano,lastNativeNano,officialBefore;
    private double startX,startY,startZ,clientStartZ,staminaBefore,integratedRegen,runStartZ,afterReleaseZ;
    private double lastYaw,lastPitch,baselineKnockback;
    private Vec3 beforeContact=Vec3.ZERO;
    private boolean contactGrounded;
    private Mob target;
    private JsonObject row;

    BurstSwipeReview(){
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(this::submitted);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::incoming);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("BURST SWIPE PASS "+message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void near(double expected,double actual,double tolerance,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,message+" expected="+expected+" actual="+actual);}
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private void action(ServerPlayer p,String name,String argument){rpg().action(p,name,argument);}
    private static JsonObject vector(Vec3 value){JsonObject json=new JsonObject();json.addProperty("x",value.x);json.addProperty("y",value.y);json.addProperty("z",value.z);return json;}
    private boolean swipe(){return scenario.ordinal()>=Scenario.SWIPE_LEFT.ordinal();}

    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(environment,"monitorAdapter","")),"Actual burst/swipe window is on DISPLAY5");
        String name=System.getProperty("fsrpg.burstSwipeWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.equals("FSRPG Burst Swipe Review 20261019 01")&&world.getFileName().toString().equals(name)
                &&Files.isRegularFile(world.resolve(".fsrpg-burst-swipe-test-world")),"Only the named marked copied burst/swipe world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=environment.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);
        GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 真实突进、跳跃与长枪扫击 · DISPLAY5");game.resizeDisplay();
        officialBefore=OfficialHeldItems.submissions();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try{
                ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var entity:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))entity.discard();
                p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(false);p.removeAllEffects();p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
                for(int x=-16;x<=16;x++)for(int z=-16;z<=48;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<87;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
                p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    private void prepare(ServerPlayer p,long now){
        action(p,"burstDash","up");actor(p).forceSheathed();if(target!=null)target.discard();target=null;
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        check(actor(p).setStyle(rpg().config().current().styles().get("spear"))==ActionResult.ACCEPTED,"Real spear style selected for "+scenario);
        action(p,"status","");p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.setSprinting(false);actor(p).setResources(40,100);
        preparedAt=now;prepared=true;begun=false;burstStarted=contactCaptured=false;contacts=0;elapsed=0;
        jumpRequested=jumpIssued=false;jumpPeak=0;clientBurstUp=clientBurstDown=clientLanded=false;jumpReleaseTicks=0;
        row=new JsonObject();row.addProperty("id",scenario.name().toLowerCase(Locale.ROOT).replace('_','-'));
    }
    private void spawnTarget(ServerPlayer p){
        target=(Mob)EntityType.HUSK.create(p.level(),EntitySpawnReason.COMMAND);require(target!=null,"Cannot create real husk");
        target.setPos(p.getX(),scenario==Scenario.AIR_DOWN?80.4:80,p.getZ()+3);target.setNoAi(true);target.setNoGravity(true);target.setPersistenceRequired();
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);target.setHealth(200);target.getAttribute(Attributes.ARMOR).setBaseValue(0);target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0);
        target.setOnGround(scenario!=Scenario.AIR_DOWN);target.setDeltaMovement(0,scenario==Scenario.AIR_DOWN?.05:0,0);p.level().addFreshEntity(target);
        baselineKnockback=rpg().config().current().skills().get(actor(p).style().leftCombo().getFirst().skillId()).knockback()*actor(p).rules().spearSwipeKnockbackMultiplier();
        row.addProperty("configuredHalfKnockback",baselineKnockback);row.addProperty("targetUuid",target.getUUID().toString());
    }
    private void spear(ServerPlayer p,String verb,double yaw,double pitch){
        var point=new AttackAim.Point(yaw,pitch,8);
        var points=verb.equals("spearPress")?List.of(point):List.of(new AttackAim.Point(lastYaw,lastPitch,8),point);
        JsonObject request=new JsonObject();request.addProperty("input",inputId);request.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(points)));
        action(p,verb,request.toString());lastYaw=yaw;lastPitch=pitch;
    }
    private void incoming(LivingIncomingDamageEvent event){
        if(!done&&target!=null&&event.getEntity().getUUID().equals(target.getUUID())&&event.getSource().getEntity() instanceof ServerPlayer p&&p.getUUID().equals(playerId)){
            contacts++;if(contacts==1)contactGrounded=event.getEntity().onGround();
        }
    }
    private void begin(ServerPlayer p,long now){
        var a=actor(p);begun=true;caseStarted=now;startX=p.getX();startY=p.getY();startZ=p.getZ();clientStartZ=clientZ;staminaBefore=a.stamina();integratedRegen=0;
        if(swipe()){
            spawnTarget(p);inputId++;double yaw=scenario==Scenario.SWIPE_RIGHT?35:-35,pitch=0;
            if(scenario==Scenario.GROUND_DOWN||scenario==Scenario.AIR_DOWN){yaw=0;pitch=-40;}
            spear(p,"spearPress",yaw,pitch);swingSequence=a.snapshot().swingSequence();check(a.spearAttackHeld(),"A real spear press begins the swipe case "+scenario);
            beforeContact=target.getDeltaMovement();
        }else if(scenario==Scenario.JUMP_BURST)jumpRequested=true;
        else startBurst(p,scenario==Scenario.GROUND_TAP);
    }
    private void startBurst(ServerPlayer p,boolean tap){
        var a=actor(p);long before=a.burstDashSnapshot().sequence();double resource=a.stamina();
        action(p,"burstDash","down 0 1");swingSequence=a.burstDashSnapshot().sequence();burstStarted=true;
        check(swingSequence==before+1,"One fresh burst input produces one server-approved sequence: "+scenario);
        near(5,resource-a.stamina(),.001,"One burst reserves exactly five stamina: "+scenario);
        row.addProperty("initialReservedCost",resource-a.stamina());if(tap)action(p,"burstDash","up");
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||done||scriptDone||failure!=null)return;
        try{
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;long now=p.level().getGameTime();
            if(index>=Scenario.values().length){scriptDone=true;return;}scenario=Scenario.values()[index];
            if(!prepared){prepare(p,now);return;}var a=actor(p);
            if(!begun){if(now-preparedAt>=12&&a.phase()==CombatPhase.READY&&p.onGround()&&Math.abs(clientZ-p.getZ())<.3)begin(p,now);return;}
            elapsed=now-caseStarted;integratedRegen+=a.currentStaminaRegenPerSecond()/20;
            JsonObject trace=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();trace.addProperty("scenario",scenario.name());trace.addProperty("elapsed",elapsed);trace.addProperty("worldGameTick",now);
            trace.add("position",vector(p.position()));trace.add("velocity",vector(p.getDeltaMovement()));trace.add("burst",RpgConfig.JSON.toJsonTree(a.burstDashSnapshot()));synchronized(evidenceLock){serverTrace.add(trace);}
            if(!swipe())check(!a.invulnerable()&&!p.isInvulnerable(),"Shift burst never grants actor/player invulnerability at "+scenario+" tick "+elapsed);
            switch(scenario){
                case GROUND_TAP -> {
                    if(elapsed==10){double distance=p.getZ()-startZ;near(3,distance,.55,"Ten real server ticks cover about three blocks through local-player travel");row.addProperty("serverDistanceAt10",distance);row.addProperty("clientDistanceAt10",clientZ-clientStartZ);}
                    if(elapsed>=18){check(a.burstDashSnapshot().sequence()==swingSequence&&!a.burstDashSnapshot().running(),"A released tap never becomes a paid repeat or continuing run");finishCase(p);}
                }
                case HELD_RUN -> {
                    if(elapsed<30&&elapsed%4==0)action(p,"burstDash","down -1 0");
                    check(a.burstDashSnapshot().sequence()==swingSequence,"Held renewals do not start another burst at "+elapsed);
                    near(1,a.burstDashSnapshot().directionZ(),.0001,"Renewing with another direction cannot steer the initial run");near(startX,p.getX(),.04,"Continued run stays on its original world axis");
                    if(elapsed==10)runStartZ=p.getZ();
                    if(elapsed==30){double distance=p.getZ()-runStartZ;near(5.6,distance,.65,"Twenty actual post-burst ticks run at about 5.6 blocks per second");row.addProperty("runDistance20Ticks",distance);row.addProperty("observedRunSpeed",distance);action(p,"burstDash","up");}
                    if(elapsed==34)afterReleaseZ=p.getZ();
                    if(elapsed>=38){check(!a.burstDashSnapshot().running(),"Up ends continued running");near(0,p.getZ()-afterReleaseZ,.08,"After release only short vanilla friction settling remains");row.addProperty("distanceLastFourTicksAfterRelease",p.getZ()-afterReleaseZ);finishCase(p);}
                }
                case JUMP_BURST -> {
                    if(!burstStarted&&p.getY()>startY+.08){row.addProperty("serverJumpHeightAtStart",p.getY()-startY);row.addProperty("clientYVelocityAtStart",clientDy);startBurst(p,true);}
                    if(elapsed>=34){check(burstStarted,"A real client jump reaches the server before the in-air burst");check(jumpPeak>=1,"The unchanged normal jump still climbs at least one block: "+jumpPeak);check(clientBurstUp&&clientBurstDown,"Native approved horizontal travel retains both ascending and falling Y");check(clientLanded,"Normal collision/gravity lands the real local player");check(p.getZ()-startZ>2.4,"The in-air burst retains its horizontal travel");row.addProperty("clientJumpPeak",jumpPeak);row.addProperty("positiveYDuringBurst",clientBurstUp);row.addProperty("negativeYDuringBurst",clientBurstDown);row.addProperty("landed",clientLanded);row.addProperty("serverHorizontalDistance",p.getZ()-startZ);finishCase(p);}
                }
                default -> updateSwipe(p);
            }
            require(elapsed<100,"Case exceeded five actual server seconds: "+scenario);
        }catch(Throwable error){failure=error;}
    }
    private void updateSwipe(ServerPlayer p){
        var a=actor(p);target.invulnerableTime=0;
        if(contacts>0&&!contactCaptured){
            contactCaptured=true;Vec3 velocity=target.getDeltaMovement();row.add("beforeContactVelocity",vector(beforeContact));row.add("firstContactVelocity",vector(velocity));row.addProperty("groundedAtContact",contactGrounded);
            if(scenario==Scenario.SWIPE_LEFT||scenario==Scenario.SWIPE_RIGHT){
                check(scenario==Scenario.SWIPE_LEFT?velocity.x<0:velocity.x>0,"Real horizontal sweep impulse follows the cut rather than actor forward: "+scenario);
                near(0,velocity.z,.055,"Horizontal cut has no radial forward shove");near(baselineKnockback,Math.hypot(velocity.x,velocity.z),.025,"Actual tangential impulse uses half configured knockback");near(0,velocity.y,.001,"Horizontal swipe adds no hop");
            }else if(scenario==Scenario.GROUND_DOWN){
                check(contactGrounded,"Downward ground fixture is really grounded at contact");near(0,velocity.y,.001,"Downward grounded contact cannot bounce upward");check(velocity.horizontalDistance()<baselineKnockback*.35+.015,"Ground downcut does not invent a forward push");
            }else {check(!contactGrounded&&beforeContact.y>0,"Airborne fixture was rising immediately before the cut");check(velocity.y<0,"Real downward air swipe immediately reverses upward velocity into a fall");}
            shots.add(row.get("id").getAsString()+"-contact");
        }
        if(!contactCaptured)beforeContact=target.getDeltaMovement();
        if(elapsed==1){
            if(scenario==Scenario.SWIPE_LEFT)spear(p,"spearMove",35,0);
            else if(scenario==Scenario.SWIPE_RIGHT)spear(p,"spearMove",-35,0);
            else spear(p,"spearMove",0,35);
        }else if(elapsed<12&&elapsed%2==1){
            if(scenario==Scenario.SWIPE_LEFT||scenario==Scenario.SWIPE_RIGHT)spear(p,"spearMove",-lastYaw,0);
            else spear(p,"spearMove",0,lastPitch>0?-40:35);
        }
        if(elapsed==12){check(contactCaptured&&contacts==1,"Repeated swept crossings claim the same real victim once per held sequence despite cleared invulnerability");row.addProperty("damageEvents",contacts);spear(p,"spearRelease",lastYaw,lastPitch);}
        if(elapsed>=17&&a.phase()==CombatPhase.READY){check(contacts==1&&a.snapshot().swingSequence()==swingSequence,"Release preserves one paid spear sequence and one target contact");finishCase(p);}
    }
    private void finishCase(ServerPlayer p){
        var a=actor(p);if(!swipe()){near(5,staminaBefore+integratedRegen-a.stamina(),.02,"One actual burst costs five after integrating real recovery: "+scenario);row.addProperty("finalNetStaminaCost",staminaBefore+integratedRegen-a.stamina());}
        row.addProperty("passed",true);row.addProperty("actualElapsedTicks",elapsed);row.addProperty("stamina",a.stamina());row.addProperty("integratedRecovery",integratedRegen);row.add("finalServerPosition",vector(p.position()));
        synchronized(evidenceLock){rows.add(row);}shots.add(row.get("id").getAsString());index++;prepared=begun=false;jumpRequested=false;
    }
    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}if(failure!=null)throw new IllegalStateException("Burst/swipe review failed",failure);
            require(System.nanoTime()-started<180_000_000_000L,"Burst/swipe review exceeded three minutes");game.setWindowActive(true);game.getFramerateLimitTracker().onInputReceived();
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);
            if(jumpRequested&&!jumpIssued){jumpIssued=true;jumpReleaseTicks=1;game.options.keyJump.setDown(true);}else if(jumpReleaseTicks>0){jumpReleaseTicks--;game.options.keyJump.setDown(false);}else game.options.keyJump.setDown(false);
            game.options.keyUp.setDown(false);game.options.keyDown.setDown(false);game.options.keyLeft.setDown(false);game.options.keyRight.setDown(false);
            clientX=game.player.getX();clientY=game.player.getY();clientZ=game.player.getZ();clientDy=game.player.getDeltaMovement().y;
            if(scenario==Scenario.JUMP_BURST&&begun){jumpPeak=Math.max(jumpPeak,clientY-startY);if(BurstDashMotion.active()){clientBurstUp|=clientDy>.02;clientBurstDown|=clientDy<-.02;}if(jumpPeak>1&&game.player.onGround())clientLanded=true;}
            JsonObject sample=new JsonObject();sample.addProperty("scenario",scenario.name());sample.addProperty("clientGameTick",game.level.getGameTime());sample.addProperty("nativeNanoTime",System.nanoTime());sample.add("position",vector(game.player.position()));sample.add("velocity",vector(game.player.getDeltaMovement()));sample.addProperty("onGround",game.player.onGround());sample.addProperty("approvedBurstActive",BurstDashMotion.active());clientTrace.add(sample);
            if(scriptDone&&shots.isEmpty()&&pendingShots.get()==0){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void preRender(RenderFrameEvent.Pre event){RenderObservations.beginSubmittedFrame(event);}
    private void submitted(RenderLivingEvent.Post<?,?,?> event){
        RenderObservations.captureSubmittedAvatar(event);var game=Minecraft.getInstance();
        if(!initialized||done||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{
            require(game.getCameraEntity()==game.player&&game.options.getCameraType()==CameraType.THIRD_PERSON_BACK,"Native burst/swipe source must be the real player camera");
            long now=System.nanoTime();if(firstNativeNano==0)firstNativeNano=now;lastNativeNano=now;nativeFrames++;
            JsonObject frame=RenderObservations.submittedSampler(game.player).get();frame.addProperty("scenario",scenario.name());frame.addProperty("nativeNanoTime",now);nativeTrace.add(frame);
        }catch(Throwable error){failure=error;}
    }
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;try{String name;while((name=shots.poll())!=null){pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}}
    private double nativeHz(){return nativeFrames>1&&lastNativeNano>firstNativeNano?(nativeFrames-1)*1e9/(lastNativeNano-firstNativeNano):0;}
    private void validate(){int count;synchronized(evidenceLock){count=rows.size();}check(count==Scenario.values().length,"All seven real local-physics and living-target scenarios passed");check(nativeFrames>30&&nativeHz()>=30,"Actual submitted player frames sample at least thirty Hz: "+nativeHz());check(OfficialHeldItems.submissions()>officialBefore,"Actual local player uses official ItemStack rendering");check(capturedShots.get()>=7,"Actual framebuffer screenshots cover every burst/swipe case");}
    private void finish(Throwable error){
        if(done)return;done=true;
        try{
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.burstSwipeWorld"));
            report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("nativeFrames",nativeFrames);report.addProperty("nativeSourceHz",nativeHz());report.addProperty("screenshots",capturedShots.get());report.addProperty("playerTeleportsPerCase",1);report.addProperty("perTickPlayerTeleport",false);
            report.addProperty("inputProvenance","Server-approved burstDash intent plus real client travel/move; actual vanilla client keyJump; authoritative spear intents and real target hurt/impulse; no physics fixture teleport during cases");
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));synchronized(evidenceLock){report.add("scenarios",rows.deepCopy());report.add("serverTickTrace",serverTrace.deepCopy());}report.add("clientTickTrace",clientTrace.deepCopy());report.add("nativeSubmittedTrace",nativeTrace.deepCopy());RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("burst-swipe-review.json"),RpgConfig.JSON.toJson(report));System.out.println("BURST SWIPE REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.setWindowActive(previousActive);}game.stop();
    }
}

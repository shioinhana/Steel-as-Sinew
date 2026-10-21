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
import java.util.function.Consumer;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Native spear mouse, camera, center-ray and gesture/network acceptance in one marked copied world. */
final class SpearOrbitReview {
    private enum Stage { WARM, MICRO, TURN, PITCH, CENTER_SETUP, CENTER_HIT, SWEEP_SETUP, SWEEP,
        MAPPED, GUI, GUI_RESUME, R_UNLOCK, ROLL_EXIT, ROLL_ENTER, WALL_ADD, WALL_REMOVE, FINISH }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray(),serverTrace=new JsonArray(),nativeTrace=new JsonArray(),damageTrace=new JsonArray();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private final Map<String,ModConfigSpec.DoubleValue> options=new LinkedHashMap<>();
    private final Map<String,Double> previousOptions=new LinkedHashMap<>();
    private volatile Stage stage=Stage.WARM;
    private volatile Throwable failure;
    private volatile boolean ready,done,videoReady,scriptDone,desiredHeld;
    private volatile int acknowledged;
    private volatile int targetPlayerDamageEvents,secondTargetPlayerDamageEvents;
    private volatile JsonObject serverSample=new JsonObject();
    private UUID playerId;
    private Mob target,secondTarget;
    private KeyMapping left;
    private Path clientToml;
    private byte[] originalClientToml;
    private boolean initialized,acted,videoStopped,exitIntermediate,entryIntermediate,configurationRestored;
    private int warmup,warmTicks,clientTicks,stageStarted,commandSerial,awaited,previousFps,previousDistance,previousScale,turnInputs;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private double previousSensitivity,turnTravel,serverTurnTravel,previousServerYaw=Double.NaN;
    private float lockedYaw,lockedPitch;
    private double microYaw,microOffset,edgeYaw,edgeOffset;
    private double firstHealthAfterHit,firstAimYaw,wallBaseline,maxForwardError,maxCenterRayError,maxRollStep,lastRoll=Double.NaN;
    private volatile float cameraEventRoll;
    private long started,nativeFrames,officialBefore,lastWarmTick=-1,holdSequence,baselineSequence,warmMeshesBefore;
    private long exitFirst,exitSettled,entryFirst,entrySettled;
    private JsonObject row;

    SpearOrbitReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,(ViewportEvent.ComputeCameraAngles event)->{if(initialized&&!done)cameraEventRoll=event.getRoll();});
        NeoForge.EVENT_BUS.addListener(this::damaged);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("SPEAR ORBIT PASS "+message);}
    private void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void near(double expected,double actual,double tolerance,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,message+" expected="+expected+" actual="+actual);}
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private int dispatch(Consumer<ServerPlayer> task){int id=++commandSerial;Minecraft.getInstance().getSingleplayerServer().execute(()->{try{var p=Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayer(playerId);task.accept(p);acknowledged=id;}catch(Throwable error){failure=error;}});return id;}
    private void setOption(String name,double value)throws Exception {
        Field field=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField(name);field.setAccessible(true);var option=(ModConfigSpec.DoubleValue)field.get(null);
        options.put(name,option);previousOptions.put(name,option.get());option.set(value);
    }
    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual spear camera window remains on DISPLAY5");
        String name=System.getProperty("fsrpg.spearOrbitWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.equals("FSRPG Spear Orbit Review 20261021 01")&&world.getFileName().toString().equals(name)&&Files.isRegularFile(world.resolve(".fsrpg-spear-orbit-test-world")),"Only the named marked copied spear-orbit world is modified");
        playerId=game.player.getUUID();left=Arrays.stream(game.options.keyMappings).filter(k->k.getName().equals("key.fsrpg.left")).findFirst().orElseThrow();
        previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousSensitivity=game.options.sensitivity().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        clientToml=game.gameDirectory.toPath().resolve("config/fsrpg-client.toml");if(Files.isRegularFile(clientToml))originalClientToml=Files.readAllBytes(clientToml);
        setOption("SPEAR_TURN_CENTER",28);setOption("SPEAR_TURN_EDGE",240);setOption("SPEAR_TURN_EDGE_START",.65);setOption("SPEAR_TURN_RESPONSE",.06);setOption("SPEAR_LOCKED_RANGE",.7);setOption("CAMERA_ROLL",2);setOption("CAMERA_RESPONSE",.12);setOption("CAMERA_TRANSITION",.5);setOption("SHOULDER",.65);setOption("CAMERA_DISTANCE",3);setOption("CAMERA_HEIGHT",.12);
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.sensitivity().set(.5);
        CombatCamera.reset();game.options.setCameraType(CameraType.FIRST_PERSON);game.options.hideGui=false;game.setWindowActive(true);game.mouseHandler.releaseMouse();KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 长枪浮动准星与右后镜头验收 · DISPLAY5");game.resizeDisplay();
        initialized=true;started=System.nanoTime();warmMeshesBefore=CreatureArtRenderer.renderedMeshes();
        dispatch(p->{
            for(var entity:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))entity.discard();
            p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(18);p.setExperienceLevels(0);p.level().setDayTime(18000);p.level().setWeatherParameters(6000,0,false,false);
            for(int x=-12;x<=12;x++)for(int z=-12;z<=20;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));equip(p,true);
            target=spawn(p,new Vec3(1.15,80,3));ready=true;
        });
    }
    private void equip(ServerPlayer p,boolean spear){actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,spear?new ItemStack(Items.DIAMOND_SPEAR):new ItemStack(Items.STICK));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);rpg().action(p,"status","");if(spear)actor(p).setResources(actor(p).maxStamina(),100);}
    private Mob spawn(ServerPlayer p,Vec3 point){Mob mob=(Mob)EntityType.ZOMBIE.create(p.level(),EntitySpawnReason.COMMAND);if(mob==null)throw new AssertionError("Cannot spawn real target");mob.setPos(point.x,80,point.z);mob.setNoAi(true);mob.setNoGravity(true);mob.setPersistenceRequired();mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);mob.setHealth(100);mob.getAttribute(Attributes.ARMOR).setBaseValue(0);mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);p.level().addFreshEntity(mob);return mob;}
    private void enter(Stage next,String id){stage=next;stageStarted=clientTicks;acted=false;if(id!=null){row=new JsonObject();row.addProperty("id",id);row.addProperty("startedNativeNanos",System.nanoTime());}}
    private void complete(){row.addProperty("passed",true);row.add("serverAtCompletion",serverSample.deepCopy());scenarios.add(row);shots.add(row.get("id").getAsString());}
    private double health(String name){return ClientState.number(serverSample,name,100);}
    private long sequence(){return (long)ClientState.number(serverSample,"swingSequence",0);}
    private boolean held(){return ClientState.bool(serverSample,"held",false);}
    private void mouse(double dx,double dy){require(CombatCamera.mouse(dx,dy),"Real spear camera accepted native mouse delta");}
    private void resetFixtureFacing(){awaited=dispatch(p->p.connection.teleport(.5,80,.5,0,0));}
    private void setCursor(double x,double y){var g=Minecraft.getInstance();mouse((x-CombatCamera.normalizedCursorX())*g.getWindow().getScreenWidth()/2.,(y-CombatCamera.normalizedCursorY())*g.getWindow().getScreenHeight()/2.);}
    private Vec3 forward(){var f=Minecraft.getInstance().gameRenderer.getMainCamera().rotation().transform(new Vector3f(0,0,-1));return new Vec3(f).normalize();}
    /** Safe spear animation depth may extend beyond the picked point; compare the two rays themselves. */
    private static double rayMeetingError(Vec3 camera,Vec3 cameraDirection,Vec3 eye,Vec3 eyeDirection){
        Vec3 normal=cameraDirection.cross(eyeDirection),offset=eye.subtract(camera);double length=normal.length();
        return length>1e-8?Math.abs(offset.dot(normal))/length:offset.cross(cameraDirection).length();
    }
    private void tick(ClientTickEvent.Post event) {
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try {
            if(!initialized){if(warmup++>=55)initialize(game);return;}movie.assertHealthy();if(failure!=null)throw new IllegalStateException("Actual spear-orbit review failed",failure);
            require(System.nanoTime()-started<180_000_000_000L,"Spear shoulder review exceeded three minutes");clientTicks++;game.getFramerateLimitTracker().onInputReceived();game.setWindowActive(true);left.setDown(desiredHeld);game.options.keyUse.setDown(false);
            if(stage==Stage.WARM){
                if(!ready||warmTicks<55||CreatureArtRenderer.renderedMeshes()<=warmMeshesBefore)return;
                if(!acted){awaited=dispatch(p->{if(target!=null)target.discard();target=null;});acted=true;return;}
                if(acknowledged<awaited)return;
                check(warmTicks>=55,"Actual submitted spear rig was prewarmed for at least fifty-five distinct game ticks");officialBefore=OfficialHeldItems.submissions();movie.start("spear-orbit","Untouched actual RenderFrameEvent.Post PNGs: floating cursor steering, right-rear camera, real reticle damage, unlocked held sweep, GUI cancellation, roll and wall safety",this::observation);videoReady=true;enter(Stage.MICRO,"right-rear-camera-and-immediate-floating-cursor");return;
            }
            int age=clientTicks-stageStarted;
            switch(stage){
                case MICRO -> {
                    if(!acted&&age>=2){
                        Vec3 offset=game.gameRenderer.getMainCamera().position().subtract(game.player.getEyePosition());
                        Vec3 right=new Vec3(game.gameRenderer.getMainCamera().rotation().transform(new Vector3f(1,0,0)));
                        check(offset.dot(right)>.5&&offset.dot(forward())<-2.8,"Actual shoulder camera is to the right and behind the player");
                        microYaw=CombatCamera.yaw();var before=CombatCamera.aimPoint();setCursor(.3,0);microOffset=CombatCamera.normalizedCursorX();
                        near(.3,microOffset,.002,"Raw viewport mouse input moves the visible normalized cursor immediately");
                        check(Math.abs(AttackAim.wrap(CombatCamera.yaw()-microYaw))<.2,"Mouse flick does not directly snap camera heading");
                        check(Math.abs(AttackAim.wrap(CombatCamera.aimPoint().yaw()-before.yaw()))>2,"Weapon aim responds to cursor before camera catches up");
                        check(CombatCamera.cursorX()>10&&CombatCamera.reticleVisible(),"Spear HUD retains the visible displaced reticle");acted=true;
                    }
                    if(age>=14){double delta=AttackAim.wrap(CombatCamera.yaw()-microYaw);check(delta>1&&delta<10,"Small deflection slowly turns the view without further mouse input");check(CombatCamera.normalizedCursorX()<microOffset&&CombatCamera.normalizedCursorX()>0,"Consumed turn smoothly returns cursor toward center");row.addProperty("smallOffsetTurn",delta);complete();turnInputs=0;turnTravel=0;serverTurnTravel=0;previousServerYaw=Double.NaN;edgeYaw=CombatCamera.yaw();edgeOffset=CombatCamera.normalizedCursorX();enter(Stage.TURN,"near-limit-acceleration-and-multiple-full-turns");}
                }
                case TURN -> {
                    if(turnInputs<100){float before=game.player.getYRot();setCursor(1,0);if(turnInputs>0)turnTravel+=Math.abs(AttackAim.wrap(before-lockedYaw));lockedYaw=before;turnInputs++;}
                    if(age==8){check(Math.abs(AttackAim.wrap(CombatCamera.yaw()-edgeYaw))>20,"Near the physical limit camera turns much faster than central aiming");row.addProperty("edgeTurnFirstEightTicks",Math.abs(AttackAim.wrap(CombatCamera.yaw()-edgeYaw)));}
                    if(age>=108){check(turnTravel>360,"Continuous edge input rotates through a complete heading wrap");check(serverTurnTravel>300,"Normal player rotation packets carry sustained turns to the actual server");row.addProperty("clientAccumulatedTurn",turnTravel);row.addProperty("serverAccumulatedTurn",serverTurnTravel);complete();resetFixtureFacing();enter(Stage.PITCH,"vertical-pose-and-reduced-physical-limits");}
                }
                case PITCH -> {
                    if(age==5)setCursor(0,1);if(age==15)setCursor(0,-1);
                    if(age==6||age==16){var aim=CombatCamera.aimPoint();check(Math.abs(aim.pitch())>10,"Vertical cursor makes the spear solve a visible raised or lowered pose");check(Math.abs(aim.pitch())<=28.001&&Math.abs(AttackAim.wrap(aim.yaw()-game.player.getYRot()))<=31.501,"Actual spear aim stays in the thirty-percent-smaller envelope");shots.add(age==6?"cursor-down":"cursor-up");}
                    check(Math.abs(CombatCamera.pitch())<=24.001,"Camera elevation retains its safe limit while spear can aim vertically");
                    if(age>=24){complete();resetFixtureFacing();enter(Stage.CENTER_SETUP,"visible-reticle-world-ray-and-real-thrust");}
                }
                case CENTER_SETUP -> {
                    if(!acted&&age>=12){setCursor(.25,0);Vec3 point=game.gameRenderer.getMainCamera().position().add(CombatCamera.cursorRay().scale(5.5));awaited=dispatch(p->{target=spawn(p,point);});acted=true;return;}
                    if(acknowledged<awaited||target==null||game.level.getEntity(target.getId())==null||age<6)return;
                    var local=game.level.getEntity(target.getId());Vec3 start=game.gameRenderer.getMainCamera().position(),ray=CombatCamera.cursorRay();var actualHit=local.getBoundingBox().inflate(.08).clip(start,start.add(ray.scale(64)));check(actualHit.isPresent(),"The real centered camera ray intersects the actual target box");
                    var aim=CombatCamera.aimPoint();Vec3 eye=game.player.getEyePosition(),eyeDirection=Vec3.directionFromRotation((float)aim.pitch(),(float)aim.yaw()),pickedDirection=actualHit.orElseThrow().subtract(eye).normalize();double angle=Math.toDegrees(Math.acos(Math.clamp(eyeDirection.normalize().dot(pickedDirection),-1,1)));double meeting=rayMeetingError(start,ray,eye,eyeDirection);check(angle<.05&&meeting<.035,"Eye-to-world spear direction points at the centered real hit while retaining safe animation depth");check(SpearAimField.contains(aim,game.player.getYRot()),"Actual centered target retains the legal held-spear yaw and pitch field");row.addProperty("centerRayWorldErrorBlocks",meeting);row.addProperty("pickedEyeDirectionErrorDegrees",angle);row.add("actualCameraHitPoint",RpgConfig.JSON.toJsonTree(new double[]{actualHit.orElseThrow().x,actualHit.orElseThrow().y,actualHit.orElseThrow().z}));row.add("actualCenterAim",RpgConfig.JSON.toJsonTree(aim));baselineSequence=sequence();desiredHeld=true;enter(Stage.CENTER_HIT,null);
                }
                case CENTER_HIT -> {if(health("targetHealth")<100&&held()&&targetPlayerDamageEvents>0&&CombatCamera.lockedTarget()==null){holdSequence=sequence();check(holdSequence==baselineSequence+1,"Real client key press and network begin one paid server spear swing");firstHealthAfterHit=health("targetHealth");row.addProperty("actualTargetHealthLoss",100-firstHealthAfterHit);check(firstHealthAfterHit<100&&targetPlayerDamageEvents==1,"Actual centered thrust has exactly one positive native damage Post from the real attacking player");check(!ClientState.bool(ClientState.object(ClientState.snapshot(),"targetLock"),"enabled",false),"Actual server snapshot never acquires a spear target lock after hitting");check(CombatCamera.reticleVisible(),"The floating spear reticle remains visible after hitting");complete();enter(Stage.SWEEP_SETUP,"held-center-sweep-keeps-real-server-path");}else require(age<35,"Centered real spear press never reached native target damage and target lock");}
                case SWEEP_SETUP -> {
                    if(!acted){float yaw=CombatCamera.yaw()-20;Vec3 direction=Vec3.directionFromRotation(0,yaw);Vec3 point=game.player.getEyePosition().add(direction.scale(2.5));awaited=dispatch(p->{secondTarget=spawn(p,point);});firstAimYaw=ClientState.number(serverSample,"acceptedAimYaw",0);acted=true;return;}
                    if(acknowledged>=awaited&&secondTarget!=null&&game.level.getEntity(secondTarget.getId())!=null&&age>=4){check(health("secondTargetHealth")==100,"Second target starts outside the previous held ray before sweeping");enter(Stage.SWEEP,null);}
                }
                case SWEEP -> {mouse(-40,0);require(held()&&sequence()==holdSequence,"Held mouse sweep retains the original paid server swing");if(health("secondTargetHealth")<100&&secondTargetPlayerDamageEvents>0){check(Math.abs(AttackAim.wrap(ClientState.number(serverSample,"acceptedAimYaw",0)-firstAimYaw))>8,"Real mouse drag changes the server-accepted held spear aim through network path updates");near(firstHealthAfterHit,health("targetHealth"),.001,"The original held swing does not damage its first target twice");check(targetPlayerDamageEvents==1&&secondTargetPlayerDamageEvents==1,"Same held sweep has exactly one real attacking-player damage Post for each native target");row.addProperty("actualSweptTargetDamage",100-health("secondTargetHealth"));row.addProperty("heldSwingSequence",holdSequence);complete();lockedYaw=CombatCamera.yaw();lockedPitch=CombatCamera.pitch();enter(Stage.MAPPED,"free-cursor-return-and-unlocked-target-motion");}else require(age<35,"Real held sweep never reached the second native target");}
                case MAPPED -> {
                    if(age==1){lockedYaw=CombatCamera.yaw();setCursor(-.75,0);edgeOffset=Math.abs(CombatCamera.normalizedCursorX());}
                    if(age>=12){check(Math.abs(CombatCamera.normalizedCursorX())<edgeOffset,"Reticle smoothly recenters while view consumes a larger deflection");check(AttackAim.wrap(CombatCamera.yaw()-lockedYaw)<-5,"Left cursor offset turns camera and player toward the left");check(CombatCamera.lockedTarget()==null&&CombatCamera.reticleVisible(),"Original and swept enemies do not bind spear facing or hide its reticle");complete();enter(Stage.GUI,"gui-opens-release-held-gesture");}
                }
                case GUI -> {if(!acted){game.setScreen(new InventoryScreen(game.player));acted=true;return;}if(!held()&&ClientState.text(serverSample,"phase","").equals("READY")){check(age<12,"Opening the real inventory releases the held spear before its server lease expires");check(!CombatCamera.gestureHeld(),"Actual client gesture clears when the GUI opens");near(0,CombatCamera.normalizedCursorX()+CombatCamera.normalizedCursorY(),.00001,"Opening GUI clears deferred camera turning input");game.setScreen(null);game.mouseHandler.releaseMouse();enter(Stage.GUI_RESUME,null);}else require(age<25,"Real inventory failed to release the held spear");}
                case GUI_RESUME -> {require(sequence()==holdSequence&&!held(),"Closing the GUI while the same left key remains down does not auto-fire");if(age>=7){desiredHeld=false;complete();enter(Stage.R_UNLOCK,"r-keeps-automatic-draw-without-target-binding");CombatCamera.drawKey();}}
                case R_UNLOCK -> {if(age>=8&&CombatCamera.lockedTarget()==null){check(ClientState.drawn()&&CombatCamera.active(),"R unlocks the target while retaining automatic drawn spear and shoulder camera");check(CombatCamera.reticleVisible(),"The center reticle returns after a real R unlock");complete();resetFixtureFacing();enter(Stage.ROLL_EXIT,"actual-two-degree-roll-and-half-second-transition");awaited=dispatch(p->equip(p,false));}else require(age<25,"R failed to unlock the spear target");}
                case ROLL_EXIT -> {if(CombatCamera.transitionBlend()>.05&&CombatCamera.transitionBlend()<.95)exitIntermediate=true;if(acknowledged>=awaited&&CombatCamera.transitionBlend()<.001&&game.options.getCameraType()==CameraType.FIRST_PERSON){check(exitIntermediate,"Native shoulder exit visits intermediate camera/roll positions");awaited=dispatch(p->equip(p,true));enter(Stage.ROLL_ENTER,null);}else require(age<50,"Native shoulder exit failed to restore first person");}
                case ROLL_ENTER -> {if(CombatCamera.transitionBlend()>.05&&CombatCamera.transitionBlend()<.95)entryIntermediate=true;if(acknowledged>=awaited&&CombatCamera.transitionBlend()>.999&&CombatCamera.active()&&age>8){check(entryIntermediate,"Native shoulder entry visits intermediate camera/roll positions");near(2,CombatCamera.cameraRoll(),.02,"The settled native shoulder roll is two degrees");near(2,cameraEventRoll,.04,"Actual ComputeCameraAngles applies the two-degree roll");check(exitFirst>0&&exitSettled>exitFirst&&entryFirst>0&&entrySettled>entryFirst,"Actual rendered frames cover both transition clocks");near(.5,(exitSettled-exitFirst)/1e9,.18,"Rendered shoulder exit retains the half-second transition");near(.5,(entrySettled-entryFirst)/1e9,.18,"Rendered shoulder entry retains the half-second transition");check(maxRollStep<.65,"Native roll changes smoothly between actual rendered frames");row.addProperty("actualRollEventDegrees",cameraEventRoll);row.addProperty("maximumNativeRollStep",maxRollStep);complete();resetFixtureFacing();enter(Stage.WALL_ADD,"camera-final-wall-safety-and-recovery");wallBaseline=game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition());awaited=dispatch(p->wall(p,true));}else require(age<50,"Native shoulder entry did not settle");}
                case WALL_ADD -> {if(acknowledged>=awaited&&age>=12){Vec3 eye=game.player.getEyePosition(),camera=game.gameRenderer.getMainCamera().position();double distance=eye.distanceTo(camera);check(distance<wallBaseline-.5,"Real wall collision shortens the actual shoulder-camera travel");var hit=game.level.clip(new ClipContext(eye,camera,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,game.player));check(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||eye.distanceTo(hit.getLocation())>=distance-.04,"Final smoothed camera position remains on the visible side of the real wall");row.addProperty("unoccludedCameraDistance",wallBaseline);row.addProperty("wallLimitedCameraDistance",distance);awaited=dispatch(p->wall(p,false));enter(Stage.WALL_REMOVE,null);}}
                case WALL_REMOVE -> {if(acknowledged>=awaited&&age>=16){near(wallBaseline,game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition()),.18,"Camera naturally returns to its configured offset after removing the wall");complete();enter(Stage.FINISH,null);}}
                case FINISH -> {if(age>=4)scriptDone=true;}
                default -> { }
            }
            if(scriptDone&&!videoStopped){movie.stop();videoStopped=true;}
            if(scriptDone&&videoStopped&&shots.isEmpty()&&pendingShots.get()==0&&movie.finish()){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void wall(ServerPlayer p,boolean present){for(int x=-2;x<=2;x++)for(int y=80;y<=83;y++)p.level().setBlock(new BlockPos(x,y,-1),present?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
    private void serverTick(ServerTickEvent.Post event){if(!initialized||!ready||done)return;try{ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;var a=actor(p);JsonObject sample=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();sample.addProperty("stage",stage.name());sample.addProperty("worldGameTick",p.level().getGameTime());sample.addProperty("held",a.spearAttackHeld());sample.addProperty("serverYaw",p.getYRot());sample.addProperty("serverPitch",p.getXRot());sample.add("playerPosition",RpgConfig.JSON.toJsonTree(new double[]{p.getX(),p.getY(),p.getZ()}));if(a.attackAim()!=null){sample.add("acceptedAim",RpgConfig.JSON.toJsonTree(a.attackAim()));sample.addProperty("acceptedAimYaw",a.attackAim().sample(1).yaw());}sample.addProperty("targetHealth",target==null?100:target.getHealth());sample.addProperty("secondTargetHealth",secondTarget==null?100:secondTarget.getHealth());serverSample=sample;if(videoReady)serverTrace.add(sample.deepCopy());if(stage==Stage.TURN){if(Double.isFinite(previousServerYaw))serverTurnTravel+=Math.abs(AttackAim.wrap(p.getYRot()-previousServerYaw));previousServerYaw=p.getYRot();}}catch(Throwable error){failure=error;}}
    private void damaged(LivingDamageEvent.Post event){if(!initialized||done||(event.getEntity()!=target&&event.getEntity()!=secondTarget))return;Entity source=event.getSource().getEntity(),direct=event.getSource().getDirectEntity();boolean nativePlayerMelee=source instanceof ServerPlayer&&source.getUUID().equals(playerId)&&direct==source&&event.getNewDamage()>0;if(nativePlayerMelee){if(event.getEntity()==target)targetPlayerDamageEvents++;else secondTargetPlayerDamageEvents++;}JsonObject value=new JsonObject();value.addProperty("worldGameTick",event.getEntity().level().getGameTime());value.addProperty("stage",stage.name());value.addProperty("targetUUID",event.getEntity().getUUID().toString());value.addProperty("actualPostDamage",event.getNewDamage());value.addProperty("sourceUUID",source==null?null:source.getUUID().toString());value.addProperty("directUUID",direct==null?null:direct.getUUID().toString());value.addProperty("actualPlayerMeleeDamage",nativePlayerMelee);damageTrace.add(value);}
    private void preRender(RenderFrameEvent.Pre event){RenderObservations.beginSubmittedFrame(event);}
    private JsonObject observation(){Minecraft game=Minecraft.getInstance();var camera=game.gameRenderer.getMainCamera();Vec3 direction=forward();JsonObject value=serverSample.deepCopy();value.addProperty("stage",stage.name());value.addProperty("cameraType",game.options.getCameraType().name());value.addProperty("actualCameraEventRoll",cameraEventRoll);value.addProperty("configuredBlendedRoll",CombatCamera.cameraRoll());value.addProperty("transitionBlend",CombatCamera.transitionBlend());value.addProperty("clientYaw",game.player.getYRot());value.addProperty("clientPitch",game.player.getXRot());value.add("cameraPosition",RpgConfig.JSON.toJsonTree(new double[]{camera.position().x,camera.position().y,camera.position().z}));value.add("cameraForward",RpgConfig.JSON.toJsonTree(new double[]{direction.x,direction.y,direction.z}));value.add("cameraQuaternion",RpgConfig.JSON.toJsonTree(new float[]{camera.rotation().x,camera.rotation().y,camera.rotation().z,camera.rotation().w}));
        if(CombatCamera.active()&&CombatCamera.style().equals("spear")){var aim=CombatCamera.aimPoint();value.add("actualCenterAim",RpgConfig.JSON.toJsonTree(aim));Vec3 expected=Vec3.directionFromRotation(CombatCamera.pitch(),CombatCamera.yaw());double error=Math.toDegrees(Math.acos(Math.clamp(expected.normalize().dot(direction),-1,1)));maxForwardError=Math.max(maxForwardError,error);value.addProperty("actualCameraCenterForwardErrorDegrees",error);Vec3 eyeDirection=Vec3.directionFromRotation((float)aim.pitch(),(float)aim.yaw());if(CombatCamera.lockedTarget()==null){double rayError=rayMeetingError(camera.position(),CombatCamera.cursorRay(),game.player.getEyePosition(),eyeDirection);maxCenterRayError=Math.max(maxCenterRayError,rayError);value.addProperty("eyeRayToActualCenterRayDistance",rayError);}value.addProperty("reticleVisible",CombatCamera.reticleVisible());value.addProperty("mappedVirtualCursorX",CombatCamera.cursorX());value.addProperty("mappedVirtualCursorY",CombatCamera.cursorY());value.addProperty("normalizedCursorX",CombatCamera.normalizedCursorX());value.addProperty("normalizedCursorY",CombatCamera.normalizedCursorY());var qr=CombatCamera.cursorRotation();value.addProperty("cursorNativeQuaternionDot",Math.abs(qr.dot(camera.rotation())));}
        try{JsonObject avatar=RenderObservations.submittedSampler(game.player).get();value.add("rig",avatar.remove("rig"));value.add("submittedAvatar",avatar);}catch(IllegalStateException firstPerson){value.add("rig",new JsonObject());value.addProperty("avatarSubmission","No third-person avatar is submitted during actual first-person transition endpoint");}
        return value;
    }
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;nativeFrames++;try{Minecraft game=Minecraft.getInstance();if(stage==Stage.WARM&&ready&&game.player!=null&&ClientState.drawn()&&CombatCamera.style().equals("spear")&&game.options.getCameraType()==CameraType.THIRD_PERSON_BACK){try{var avatar=RenderObservations.submittedSampler(game.player).get();long tick=game.level.getGameTime();if(!avatar.getAsJsonObject("rig").isEmpty()&&tick!=lastWarmTick){warmTicks++;lastWarmTick=tick;}}catch(IllegalStateException notSubmitted){}}
        if(videoReady){double blend=CombatCamera.transitionBlend(),roll=CombatCamera.cameraRoll();long now=System.nanoTime();if(stage==Stage.ROLL_EXIT){if(blend<.999&&exitFirst==0)exitFirst=now;if(blend<.001&&exitFirst>0&&exitSettled==0)exitSettled=now;}if(stage==Stage.ROLL_ENTER){if(blend>.001&&entryFirst==0)entryFirst=now;if(blend>.999&&entryFirst>0&&entrySettled==0)entrySettled=now;}if(stage==Stage.ROLL_EXIT||stage==Stage.ROLL_ENTER){if(Double.isFinite(lastRoll))maxRollStep=Math.max(maxRollStep,Math.abs(roll-lastRoll));lastRoll=roll;JsonObject transition=new JsonObject();transition.addProperty("stage",stage.name());transition.addProperty("nativeNanos",now);transition.addProperty("blend",blend);transition.addProperty("actualEventRoll",cameraEventRoll);nativeTrace.add(transition);}movie.frame(event);}
        String shot;while((shot=shots.poll())!=null){String name=shot;pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",game.getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}
    }catch(Throwable error){failure=error;}}
    private void validate(){check(scenarios.size()==10,"All ten actual spear shoulder scenarios completed");check(capturedShots.get()>=10,"Every scenario has an original framebuffer PNG");check(OfficialHeldItems.submissions()>officialBefore,"Native spear frames submit the official held ItemStack");check(targetPlayerDamageEvents==1&&secondTargetPlayerDamageEvents==1,"Both damage acceptances are backed by positive native Post events from the real player melee source");check(maxForwardError<.15,"Actual camera forward stays aligned with current orbit heading");check(maxCenterRayError<.05,"Visible floating reticle ray and actual attack ray intersect at the same world point");var clip=movie.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Original Post-render PNG sampling exceeds thirty native frames per second");check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native recording contains actual sub-tick render samples");}
    private void finish(Throwable error){if(done)return;done=true;desiredHeld=false;movie.stop();try{movie.assertHealthy();for(var value:previousOptions.entrySet())options.get(value.getKey()).set(value.getValue());if(originalClientToml!=null)Files.write(clientToml,originalClientToml);configurationRestored=true;}catch(Throwable restore){if(error==null)error=restore;}
        try{JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("cause",error==null||error.getCause()==null?null:error.getCause().toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.spearOrbitWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("submittedSpearPrewarmTicks",warmTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());report.addProperty("maxCameraForwardErrorDegrees",maxForwardError);report.addProperty("maxCenterRayWorldErrorBlocks",maxCenterRayError);report.addProperty("clientConfigurationRestored",configurationRestored);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("serverTrace",serverTrace);report.add("nativeTransitions",nativeTrace);report.add("actualDamageEvents",damageTrace);report.add("renderCapture",safeSummary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("spear-orbit-review.json"),RpgConfig.JSON.toJson(report));System.out.println("SPEAR ORBIT REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setScreen(null);game.mouseHandler.releaseMouse();if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.sensitivity().set(previousSensitivity);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();}
    private JsonObject safeSummary(){try{return movie.summary();}catch(Throwable error){JsonObject result=new JsonObject();result.addProperty("error",error.toString());return result;}}
}

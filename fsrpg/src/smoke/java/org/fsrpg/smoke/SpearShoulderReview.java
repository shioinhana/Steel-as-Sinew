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
final class SpearShoulderReview {
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
    private double firstHealthAfterHit,firstAimYaw,wallBaseline,maxForwardError,maxCenterRayError,maxRollStep,lastRoll=Double.NaN;
    private volatile float cameraEventRoll;
    private long started,nativeFrames,officialBefore,lastWarmTick=-1,holdSequence,baselineSequence,warmMeshesBefore;
    private long exitFirst,exitSettled,entryFirst,entrySettled;
    private JsonObject row;

    SpearShoulderReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,(ViewportEvent.ComputeCameraAngles event)->{if(initialized&&!done)cameraEventRoll=event.getRoll();});
        NeoForge.EVENT_BUS.addListener(this::damaged);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("SPEAR SHOULDER PASS "+message);}
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
        String name=System.getProperty("fsrpg.spearShoulderWorld","");Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.equals("FSRPG Spear Shoulder Review 20261021 01")&&world.getFileName().toString().equals(name)&&Files.isRegularFile(world.resolve(".fsrpg-spear-shoulder-test-world")),"Only the named marked copied spear-shoulder world is modified");
        playerId=game.player.getUUID();left=Arrays.stream(game.options.keyMappings).filter(k->k.getName().equals("key.fsrpg.left")).findFirst().orElseThrow();
        previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousSensitivity=game.options.sensitivity().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        clientToml=game.gameDirectory.toPath().resolve("config/fsrpg-client.toml");if(Files.isRegularFile(clientToml))originalClientToml=Files.readAllBytes(clientToml);
        setOption("SPEAR_LOCKED_RANGE",.7);setOption("CAMERA_ROLL",2);setOption("CAMERA_RESPONSE",.12);setOption("CAMERA_TRANSITION",.5);setOption("SHOULDER",.65);setOption("CAMERA_DISTANCE",3);setOption("CAMERA_HEIGHT",.12);
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.sensitivity().set(.5);
        CombatCamera.reset();game.options.setCameraType(CameraType.FIRST_PERSON);game.options.hideGui=false;game.setWindowActive(true);game.mouseHandler.releaseMouse();KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 长枪中心瞄准与肩镜头验收 · DISPLAY5");game.resizeDisplay();
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
    private void turnTo(float yaw,float pitch){mouse(AttackAim.wrap(yaw-CombatCamera.yaw())/.15,(pitch-CombatCamera.pitch())/.15);}
    private Vec3 forward(){var f=Minecraft.getInstance().gameRenderer.getMainCamera().rotation().transform(new Vector3f(0,0,-1));return new Vec3(f).normalize();}
    /** Safe spear animation depth may extend beyond the picked point; compare the two rays themselves. */
    private static double rayMeetingError(Vec3 camera,Vec3 cameraDirection,Vec3 eye,Vec3 eyeDirection){
        Vec3 normal=cameraDirection.cross(eyeDirection),offset=eye.subtract(camera);double length=normal.length();
        return length>1e-8?Math.abs(offset.dot(normal))/length:offset.cross(cameraDirection).length();
    }
    private void tick(ClientTickEvent.Post event) {
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try {
            if(!initialized){if(warmup++>=55)initialize(game);return;}movie.assertHealthy();if(failure!=null)throw new IllegalStateException("Actual spear-shoulder review failed",failure);
            require(System.nanoTime()-started<180_000_000_000L,"Spear shoulder review exceeded three minutes");clientTicks++;game.getFramerateLimitTracker().onInputReceived();game.setWindowActive(true);left.setDown(desiredHeld);game.options.keyUse.setDown(false);
            if(stage==Stage.WARM){
                if(!ready||warmTicks<55||CreatureArtRenderer.renderedMeshes()<=warmMeshesBefore)return;
                if(!acted){awaited=dispatch(p->{if(target!=null)target.discard();target=null;});acted=true;return;}
                if(acknowledged<awaited)return;
                check(warmTicks>=55,"Actual submitted spear rig was prewarmed for at least fifty-five distinct game ticks");officialBefore=OfficialHeldItems.submissions();movie.start("spear-shoulder","Untouched actual RenderFrameEvent.Post PNGs: direct mouse turning, center-ray native damage, held network sweep, GUI cancellation, real camera roll and wall collision",this::observation);videoReady=true;enter(Stage.MICRO,"micro-input-direct-and-immediate-aim");return;
            }
            int age=clientTicks-stageStarted;
            switch(stage){
                case MICRO -> {
                    if(!acted&&age>=2){float before=game.player.getYRot();var aimBefore=CombatCamera.aimPoint();mouse(1,0);double tiny=AttackAim.wrap(game.player.getYRot()-before);near(.15,tiny,.015,"One-pixel mouse movement rotates the actor immediately without a dead zone");near(game.player.getYRot(),CombatCamera.yaw(),.001,"Camera and actor use the same immediate spear yaw");
                        var beforeLarge=CombatCamera.aimPoint();mouse(48,0);var after=CombatCamera.aimPoint();double immediate=AttackAim.wrap(after.yaw()-beforeLarge.yaw());check(immediate>6,"Aim ray uses the current mouse delta before the next rendered camera frame");row.add("initialAim",RpgConfig.JSON.toJsonTree(aimBefore));row.add("immediateAim",RpgConfig.JSON.toJsonTree(after));row.addProperty("onePixelActorTurn",tiny);row.addProperty("immediateAimTurn",immediate);acted=true;}
                    if(age>=7){complete();turnInputs=0;turnTravel=0;serverTurnTravel=0;previousServerYaw=Double.NaN;enter(Stage.TURN,"continuous-heading-turn-without-cursor-overflow");}
                }
                case TURN -> {if(turnInputs<12){float before=game.player.getYRot();mouse(220,0);double change=AttackAim.wrap(game.player.getYRot()-before);near(33,change,.03,"Each continuous mouse delta turns directly, including across yaw wrapping");turnTravel+=change;turnInputs++;}if(age>=17){check(turnTravel>360,"Continuous real mouse deltas rotate beyond one complete turn");check(serverTurnTravel>250,"Normal player rotation packets carry the continuous turns to the real server");row.addProperty("clientAccumulatedTurn",turnTravel);row.addProperty("serverAccumulatedTurn",serverTurnTravel);complete();enter(Stage.PITCH,"preserved-twenty-four-degree-pitch-boundary");}}
                case PITCH -> {if(age==1){mouse(0,1000);near(24,game.player.getXRot(),.001,"Upward spear camera pitch retains its twenty-four-degree limit");}if(age==2){mouse(0,-2000);near(-24,game.player.getXRot(),.001,"Downward spear camera pitch retains its twenty-four-degree limit");}if(age==3)turnTo(0,0);if(age>=15){near(0,CombatCamera.yaw(),.01,"Normal mouse input returns the spear camera to the test direction");complete();enter(Stage.CENTER_SETUP,"center-reticle-world-ray-and-real-thrust");}}
                case CENTER_SETUP -> {
                    if(!acted){Vec3 point=game.gameRenderer.getMainCamera().position().add(forward().scale(5.5));awaited=dispatch(p->{target=spawn(p,point);});acted=true;return;}
                    if(acknowledged<awaited||target==null||game.level.getEntity(target.getId())==null||age<6)return;
                    var local=game.level.getEntity(target.getId());Vec3 start=game.gameRenderer.getMainCamera().position(),ray=forward();var actualHit=local.getBoundingBox().inflate(.08).clip(start,start.add(ray.scale(64)));check(actualHit.isPresent(),"The real centered camera ray intersects the actual target box");
                    var aim=CombatCamera.aimPoint();Vec3 eye=game.player.getEyePosition(),eyeDirection=Vec3.directionFromRotation((float)aim.pitch(),(float)aim.yaw()),pickedDirection=actualHit.orElseThrow().subtract(eye).normalize();double angle=Math.toDegrees(Math.acos(Math.clamp(eyeDirection.normalize().dot(pickedDirection),-1,1)));double meeting=rayMeetingError(start,ray,eye,eyeDirection);check(angle<.05&&meeting<.035,"Eye-to-world spear direction points at the centered real hit while retaining safe animation depth");check(SpearAimField.contains(aim,game.player.getYRot()),"Actual centered target retains the legal held-spear yaw and pitch field");row.addProperty("centerRayWorldErrorBlocks",meeting);row.addProperty("pickedEyeDirectionErrorDegrees",angle);row.add("actualCameraHitPoint",RpgConfig.JSON.toJsonTree(new double[]{actualHit.orElseThrow().x,actualHit.orElseThrow().y,actualHit.orElseThrow().z}));row.add("actualCenterAim",RpgConfig.JSON.toJsonTree(aim));baselineSequence=sequence();desiredHeld=true;enter(Stage.CENTER_HIT,null);
                }
                case CENTER_HIT -> {if(health("targetHealth")<100&&held()&&targetPlayerDamageEvents>0&&CombatCamera.lockedTarget()!=null){holdSequence=sequence();check(holdSequence==baselineSequence+1,"Real client key press and network begin one paid server spear swing");firstHealthAfterHit=health("targetHealth");row.addProperty("actualTargetHealthLoss",100-firstHealthAfterHit);check(firstHealthAfterHit<100&&targetPlayerDamageEvents==1,"Actual centered thrust has exactly one positive native damage Post from the real attacking player");check(CombatCamera.lockedTarget().getUUID().equals(target.getUUID()),"Accepted centered spear attack acquires the actual server target lock");check(!CombatCamera.reticleVisible(),"The production HUD hides its reticle as soon as the spear is locked");complete();enter(Stage.SWEEP_SETUP,"held-center-sweep-keeps-real-server-path");}else require(age<35,"Centered real spear press never reached native target damage and target lock");}
                case SWEEP_SETUP -> {
                    if(!acted){float yaw=CombatCamera.yaw()+20;Vec3 direction=Vec3.directionFromRotation(0,yaw);Vec3 point=game.player.getEyePosition().add(direction.scale(2.5));awaited=dispatch(p->{secondTarget=spawn(p,point);});firstAimYaw=ClientState.number(serverSample,"acceptedAimYaw",0);acted=true;return;}
                    if(acknowledged>=awaited&&secondTarget!=null&&game.level.getEntity(secondTarget.getId())!=null&&age>=4){mouse(20/31.5*game.getWindow().getScreenWidth()/2.,0);enter(Stage.SWEEP,null);}
                }
                case SWEEP -> {require(held()&&sequence()==holdSequence,"Held mouse sweep retains the original paid server swing");if(health("secondTargetHealth")<100&&secondTargetPlayerDamageEvents>0){check(Math.abs(AttackAim.wrap(ClientState.number(serverSample,"acceptedAimYaw",0)-firstAimYaw))>8,"Real mouse drag changes the server-accepted held spear aim through network path updates");near(firstHealthAfterHit,health("targetHealth"),.001,"The original held swing does not damage its first target twice");check(targetPlayerDamageEvents==1&&secondTargetPlayerDamageEvents==1,"Same held sweep has exactly one real attacking-player damage Post for each native target");row.addProperty("actualSweptTargetDamage",100-health("secondTargetHealth"));row.addProperty("heldSwingSequence",holdSequence);complete();lockedYaw=CombatCamera.yaw();lockedPitch=CombatCamera.pitch();enter(Stage.MAPPED,"locked-full-viewport-small-physical-range");}else require(age<35,"Real held sweep never reached the second native target");}
                case MAPPED -> {
                    double width=game.getWindow().getScreenWidth(),height=game.getWindow().getScreenHeight();
                    if(age==1){mouse(width,0);near(31.5,AttackAim.wrap(CombatCamera.aimPoint().yaw()-game.player.getYRot()),.02,"Full virtual right screen maps to the actual 31.5-degree spear limit");}
                    if(age==4){mouse(-2*width,0);near(-31.5,AttackAim.wrap(CombatCamera.aimPoint().yaw()-game.player.getYRot()),.02,"Full virtual left screen maps to the actual minus31.5-degree spear limit");}
                    if(age==7){mouse(width/2,height);near(28,CombatCamera.aimPoint().pitch(),.02,"Full virtual lower screen restores vertical pose motion to actual28 degrees");}
                    if(age==10){mouse(0,-2*height);near(-28,CombatCamera.aimPoint().pitch(),.02,"Full virtual upper screen maps to actual minus28 degrees");}
                    if(age==3)shots.add("locked-mapped-right");if(age==6)shots.add("locked-mapped-left");if(age==9)shots.add("locked-mapped-down");if(age==12)shots.add("locked-mapped-up");
                    if(age==13)mouse(0,height/2);
                    near(lockedYaw,CombatCamera.yaw(),.02,"Invisible mapped mouse cannot rotate the locked camera yaw");near(lockedPitch,CombatCamera.pitch(),.02,"Invisible mapped mouse cannot rotate the locked camera pitch");near(lockedYaw,game.player.getYRot(),.02,"Mapped pole motion leaves the actor facing the locked entity");check(!CombatCamera.reticleVisible(),"No reticle or range box is drawn during locked aiming");
                    if(age>=16){check(CombatCamera.lockedTarget().getUUID().equals(target.getUUID()),"Sweeping a nearer second enemy never steals the original server lock");complete();enter(Stage.GUI,"gui-opens-release-held-gesture");}
                }
                case GUI -> {if(!acted){game.setScreen(new InventoryScreen(game.player));acted=true;return;}if(!held()&&ClientState.text(serverSample,"phase","").equals("READY")){check(age<12,"Opening the real inventory releases the held spear before its server lease expires");check(!CombatCamera.gestureHeld(),"Actual client gesture clears when the GUI opens");game.setScreen(null);game.mouseHandler.releaseMouse();enter(Stage.GUI_RESUME,null);}else require(age<25,"Real inventory failed to release the held spear");}
                case GUI_RESUME -> {require(sequence()==holdSequence&&!held(),"Closing the GUI while the same left key remains down does not auto-fire");if(age>=7){desiredHeld=false;complete();enter(Stage.R_UNLOCK,"r-unlocks-spear-and-keeps-automatic-draw");CombatCamera.drawKey();}}
                case R_UNLOCK -> {if(age>=8&&CombatCamera.lockedTarget()==null){check(ClientState.drawn()&&CombatCamera.active(),"R unlocks the target while retaining automatic drawn spear and shoulder camera");check(CombatCamera.reticleVisible(),"The center reticle returns after a real R unlock");complete();turnTo(0,0);enter(Stage.ROLL_EXIT,"actual-two-degree-roll-and-half-second-transition");awaited=dispatch(p->equip(p,false));}else require(age<25,"R failed to unlock the spear target");}
                case ROLL_EXIT -> {if(CombatCamera.transitionBlend()>.05&&CombatCamera.transitionBlend()<.95)exitIntermediate=true;if(acknowledged>=awaited&&CombatCamera.transitionBlend()<.001&&game.options.getCameraType()==CameraType.FIRST_PERSON){check(exitIntermediate,"Native shoulder exit visits intermediate camera/roll positions");awaited=dispatch(p->equip(p,true));enter(Stage.ROLL_ENTER,null);}else require(age<50,"Native shoulder exit failed to restore first person");}
                case ROLL_ENTER -> {if(CombatCamera.transitionBlend()>.05&&CombatCamera.transitionBlend()<.95)entryIntermediate=true;if(acknowledged>=awaited&&CombatCamera.transitionBlend()>.999&&CombatCamera.active()&&age>8){check(entryIntermediate,"Native shoulder entry visits intermediate camera/roll positions");near(2,CombatCamera.cameraRoll(),.02,"The settled native shoulder roll is two degrees");near(2,cameraEventRoll,.04,"Actual ComputeCameraAngles applies the two-degree roll");check(exitFirst>0&&exitSettled>exitFirst&&entryFirst>0&&entrySettled>entryFirst,"Actual rendered frames cover both transition clocks");near(.5,(exitSettled-exitFirst)/1e9,.18,"Rendered shoulder exit retains the half-second transition");near(.5,(entrySettled-entryFirst)/1e9,.18,"Rendered shoulder entry retains the half-second transition");check(maxRollStep<.65,"Native roll changes smoothly between actual rendered frames");row.addProperty("actualRollEventDegrees",cameraEventRoll);row.addProperty("maximumNativeRollStep",maxRollStep);complete();turnTo(0,0);enter(Stage.WALL_ADD,"camera-final-wall-safety-and-recovery");wallBaseline=game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition());awaited=dispatch(p->wall(p,true));}else require(age<50,"Native shoulder entry did not settle");}
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
        if(CombatCamera.active()&&CombatCamera.style().equals("spear")){var aim=CombatCamera.aimPoint();value.add("actualCenterAim",RpgConfig.JSON.toJsonTree(aim));Vec3 expected=Vec3.directionFromRotation(CombatCamera.pitch(),CombatCamera.yaw());double error=Math.toDegrees(Math.acos(Math.clamp(expected.normalize().dot(direction),-1,1)));maxForwardError=Math.max(maxForwardError,error);value.addProperty("actualCameraCenterForwardErrorDegrees",error);Vec3 eyeDirection=Vec3.directionFromRotation((float)aim.pitch(),(float)aim.yaw());if(CombatCamera.lockedTarget()==null){double rayError=rayMeetingError(camera.position(),direction,game.player.getEyePosition(),eyeDirection);maxCenterRayError=Math.max(maxCenterRayError,rayError);value.addProperty("eyeRayToActualCenterRayDistance",rayError);}value.addProperty("reticleVisible",CombatCamera.reticleVisible());value.addProperty("mappedVirtualCursorX",CombatCamera.cursorX());value.addProperty("mappedVirtualCursorY",CombatCamera.cursorY());}
        try{JsonObject avatar=RenderObservations.submittedSampler(game.player).get();value.add("rig",avatar.remove("rig"));value.add("submittedAvatar",avatar);}catch(IllegalStateException firstPerson){value.add("rig",new JsonObject());value.addProperty("avatarSubmission","No third-person avatar is submitted during actual first-person transition endpoint");}
        return value;
    }
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;nativeFrames++;try{Minecraft game=Minecraft.getInstance();if(stage==Stage.WARM&&ready&&game.player!=null&&ClientState.drawn()&&CombatCamera.style().equals("spear")&&game.options.getCameraType()==CameraType.THIRD_PERSON_BACK){try{var avatar=RenderObservations.submittedSampler(game.player).get();long tick=game.level.getGameTime();if(!avatar.getAsJsonObject("rig").isEmpty()&&tick!=lastWarmTick){warmTicks++;lastWarmTick=tick;}}catch(IllegalStateException notSubmitted){}}
        if(videoReady){double blend=CombatCamera.transitionBlend(),roll=CombatCamera.cameraRoll();long now=System.nanoTime();if(stage==Stage.ROLL_EXIT){if(blend<.999&&exitFirst==0)exitFirst=now;if(blend<.001&&exitFirst>0&&exitSettled==0)exitSettled=now;}if(stage==Stage.ROLL_ENTER){if(blend>.001&&entryFirst==0)entryFirst=now;if(blend>.999&&entryFirst>0&&entrySettled==0)entrySettled=now;}if(stage==Stage.ROLL_EXIT||stage==Stage.ROLL_ENTER){if(Double.isFinite(lastRoll))maxRollStep=Math.max(maxRollStep,Math.abs(roll-lastRoll));lastRoll=roll;JsonObject transition=new JsonObject();transition.addProperty("stage",stage.name());transition.addProperty("nativeNanos",now);transition.addProperty("blend",blend);transition.addProperty("actualEventRoll",cameraEventRoll);nativeTrace.add(transition);}movie.frame(event);}
        String shot;while((shot=shots.poll())!=null){String name=shot;pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",game.getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}
    }catch(Throwable error){failure=error;}}
    private void validate(){check(scenarios.size()==10,"All ten actual spear shoulder scenarios completed");check(capturedShots.get()>=10,"Every scenario has an original framebuffer PNG");check(OfficialHeldItems.submissions()>officialBefore,"Native spear frames submit the official held ItemStack");check(targetPlayerDamageEvents==1&&secondTargetPlayerDamageEvents==1,"Both damage acceptances are backed by positive native Post events from the real player melee source");check(maxForwardError<.15,"Actual camera forward stays aligned with the current centered heading");var clip=movie.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Original Post-render PNG sampling exceeds thirty native frames per second");check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native recording contains actual sub-tick render samples");}
    private void finish(Throwable error){if(done)return;done=true;desiredHeld=false;movie.stop();try{movie.assertHealthy();for(var value:previousOptions.entrySet())options.get(value.getKey()).set(value.getValue());if(originalClientToml!=null)Files.write(clientToml,originalClientToml);configurationRestored=true;}catch(Throwable restore){if(error==null)error=restore;}
        try{JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("cause",error==null||error.getCause()==null?null:error.getCause().toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.spearShoulderWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("submittedSpearPrewarmTicks",warmTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());report.addProperty("maxCameraForwardErrorDegrees",maxForwardError);report.addProperty("maxCenterRayWorldErrorBlocks",maxCenterRayError);report.addProperty("clientConfigurationRestored",configurationRestored);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("serverTrace",serverTrace);report.add("nativeTransitions",nativeTrace);report.add("actualDamageEvents",damageTrace);report.add("renderCapture",safeSummary());RpgSmoke.windowEnvironment(report);Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("spear-shoulder-review.json"),RpgConfig.JSON.toJson(report));System.out.println("SPEAR SHOULDER REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();}catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setScreen(null);game.mouseHandler.releaseMouse();if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.sensitivity().set(previousSensitivity);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();}
    private JsonObject safeSummary(){try{return movie.summary();}catch(Throwable error){JsonObject result=new JsonObject();result.addProperty("error",error.toString());return result;}}
}

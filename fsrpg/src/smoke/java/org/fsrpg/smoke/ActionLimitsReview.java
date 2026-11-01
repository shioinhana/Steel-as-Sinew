package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.SwordMotion;
import org.fsrpg.client.PresentationMotion;
import org.fsrpg.combat.SpearAimField;
import org.fsrpg.combat.CombatPhase;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Focused live spear limits, sword layering and server resource checks through the packaged mod. */
final class ActionLimitsReview {
    private static final Path ROOT=Path.of(System.getProperty("fsrpg.smokeEvidence","../../verification/action-limits-20261013/game")).toAbsolutePath().normalize();
    private static final List<String> CLIPS=List.of("spear-ready-bounded","spear-thrust-sweep-bounded","spear-moving-bounded","sword-old-upper-modern-lower","spear-cursor-frame");
    private static final List<String> JOINTS=List.of("body","head","right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand");
    private final Path directory=ROOT.resolve("run-"+Instant.now().toString().replace(':','-'));
    private final RenderFrameCapture movies=new RenderFrameCapture(directory);
    private final ArrayDeque<String> screenshots=new ArrayDeque<>();
    private final JsonArray images=new JsonArray();
    private final Map<String,Quaternionf> previousJoints=new HashMap<>();
    private final Map<String,Double> peakSteps=new HashMap<>();
    private final Fixture[] actors=new Fixture[2];
    private final Supplier<JsonObject>[] samplers=new Supplier[2];
    private volatile Throwable failure;
    private volatile boolean serverReady;
    private boolean initialized,done,captureStarted;
    private int warmup,elapsed,scene=-1,checks;
    private int captureProfile;
    private int previousFps,previousDistance,previousScale,previousFov;
    private boolean previousPause,previousHidden,previousVsync;
    private CameraType previousCamera;
    private Path world;
    private String fixtureAction="spear_idle",fixturePhase="READY";
    private long sequence=50000;
    private double maximumGripError,maximumAxisError,maximumWristFlex;
    private int sampledFrames;
    private ImportedAssetsScreen importedAssets;
    private int importedAssetTicks;
    private String movementSegment="stationary";
    private JsonObject movementValidation=new JsonObject();
    private JsonObject swordMovementValidation=new JsonObject();
    private final JsonArray serverStamina=new JsonArray();
    private int cursorChecks;
    private double maximumCursorAimYaw,maximumCursorAimPitch,maximumCursorBodyYaw;
    private double maximumSwordReadyTilt;
    private double maximumSwordSourceArmDifference;
    private int swordSourceComparisons;
    private volatile int nearTargetEntityId=-1;
    private int nearEntityChecks;
    private final JsonArray nearEntityMeasurements=new JsonArray();
    private final JsonArray cursorInputTrace=new JsonArray();
    private double minimumPreparedAttackForwardDepth=Double.POSITIVE_INFINITY;

    private static final class Fixture extends RemotePlayer {
        final PlayerSkin skin;
        private double velocityX,velocityZ;
        private double appliedVelocityX,appliedVelocityZ;
        private boolean sprint;
        private String commandedSegment="stationary",appliedSegment="stationary";
        private int commandedTick=-1,appliedTick=-1;
        Fixture(ClientLevel level,PlayerSkin original,int profile) {
            super(level,new GameProfile(UUID.fromString(profile==0?"3908411d-0bee-4b04-b27b-011012000001":"3908411d-0bee-4b04-b27b-011012000002"),profile==0?"SpearStock":"SpearSlim"));
            skin=new PlayerSkin(original.body(),null,null,profile==0?PlayerModelType.WIDE:PlayerModelType.SLIM,false);
            setId(201101+profile);setNoGravity(true);setOnGround(true);
        }
        @Override public PlayerSkin getSkin(){return skin;}
        void drive(int scriptTick,String segment,double x,double z,boolean sprinting) {
            commandedTick=scriptTick;commandedSegment=segment;velocityX=x;velocityZ=z;sprint=sprinting;
        }
        @Override public void aiStep() {
            super.aiStep();
            // RemotePlayer.tick() calls vanilla calculateEntityAnimation after this.
            // Advance from the 20 Hz entity clock; walk speed comes from these metres,
            // rather than an extra walkAnimation.update or a render-time teleport.
            setPos(getX()+velocityX,80,getZ()+velocityZ);setDeltaMovement(velocityX,0,velocityZ);
            setOnGround(true);setSprinting(sprint);appliedTick=commandedTick;appliedSegment=commandedSegment;
            appliedVelocityX=velocityX;appliedVelocityZ=velocityZ;
        }
    }
    ActionLimitsReview() {
        NeoForge.EVENT_BUS.addListener(RenderObservations::beginSubmittedFrame);NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;System.out.println("SPEAR SOLVER PASS "+text);}
    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(directory.resolve("screenshots"));var environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        check(environment.get("monitorAdapter").getAsString().equals("\\\\.\\DISPLAY5"),"Actual test window is on DISPLAY5");
        world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String named=System.getProperty("fsrpg.actionLimitsWorld","");
        check(named.startsWith("FSRPG Action Limits Review ")&&world.getFileName().toString().equals(named),"Only explicitly named copied spear world is modified");
        check(Files.isRegularFile(world.resolve(".fsrpg-action-limits-test-world")),"Copied world bears spear-only development marker");
        previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousFov=game.options.fov().get();
        previousPause=game.options.pauseOnLostFocus;previousHidden=game.options.hideGui;previousVsync=game.options.enableVsync().get();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.hideGui=true;game.options.framerateLimit().set(60);game.options.enableVsync().set(false);
        game.options.renderDistance().set(4);game.options.guiScale().set(2);game.options.fov().set(65);game.options.setCameraType(CameraType.FIRST_PERSON);game.resizeDisplay();
        game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();GLFW.glfwSetWindowTitle(game.getWindow().handle(),"FSRPG 腋下枪姿与转步验收 · DISPLAY5");
        UUID player=game.player.getUUID();initialized=true;
        game.getSingleplayerServer().execute(()->{try {
            var p=game.getSingleplayerServer().getPlayerList().getPlayer(player);verifyServerStamina(p);
            p.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);RpgSmoke.actor(p).forceSheathed();RpgServer.instance().action(p,"status","");
            p.setGameMode(GameType.SPECTATOR);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
            for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++) {
                p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
            }
            p.connection.teleport(4,81.5,6,146.31f,21);serverReady=true;
        }catch(Throwable error){failure=error;}});
    }
    private void fixtures(Minecraft game) {
        for(int profile=0;profile<2;profile++) {
            var actor=new Fixture(game.level,game.player.getSkin(),profile);actors[profile]=actor;game.level.addEntity(actor);
            actor.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));
            actor.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            actor.setPos(profile==0?-1.35:1.35,80,0);actor.xo=actor.xOld=actor.getX();actor.yo=actor.yOld=80;actor.zo=actor.zOld=0;
            place(actor,0,0);send(actor,"spear_idle","READY",0,0,0,0,null,++sequence);
        }
    }
    private void place(Fixture actor,double yaw,double pitch) {
        actor.setYRot((float)yaw);actor.yRotO=(float)yaw;actor.setXRot((float)pitch);actor.xRotO=(float)pitch;
        actor.yBodyRot=actor.yBodyRotO=0;actor.yHeadRot=actor.yHeadRotO=(float)yaw;actor.setOnGround(true);
    }
    private void send(Fixture actor,String action,String phase,int remaining,int windup,int active,int recovery,AttackAim aim,long seq) {
        PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(),"spear","spear.fsvoxel",action,phase,remaining,windup,active,recovery,seq,true,-1,-1,aim));
    }
    private AttackAim trajectory(double yaw,double pitch,boolean sweep) {
        if(!sweep)return new AttackAim(List.of(safeAttackPoint(yaw,pitch)));
        return new AttackAim(List.of(safeAttackPoint(Math.clamp(yaw-10,-SpearAimField.MAX_YAW_DEGREES,SpearAimField.MAX_YAW_DEGREES),Math.clamp(pitch-8,-SpearAimField.MAX_PITCH_DEGREES,SpearAimField.MAX_PITCH_DEGREES)),safeAttackPoint(yaw,pitch),safeAttackPoint(Math.clamp(yaw+10,-SpearAimField.MAX_YAW_DEGREES,SpearAimField.MAX_YAW_DEGREES),Math.clamp(pitch+8,-SpearAimField.MAX_PITCH_DEGREES,SpearAimField.MAX_PITCH_DEGREES))));
    }
    private AttackAim.Point safeAttackPoint(double yaw,double pitch) {
        var point=SpearAimField.constrain(new AttackAim.Point(yaw,pitch,8),0);
        double forward=point.distance()*Math.cos(Math.toRadians(point.yaw()))*Math.cos(Math.toRadians(point.pitch()));
        minimumPreparedAttackForwardDepth=Math.min(minimumPreparedAttackForwardDepth,forward);return point;
    }
    private void update(Minecraft game,int t) {
        if(scene==4){updateCenterAim(game,t);return;}
        if(t==0){var actor=actors[captureProfile];actor.setPos(captureProfile==0?-1.35:1.35,80,0);actor.xo=actor.xOld=actor.getX();actor.yo=actor.yOld=80;actor.zo=actor.zOld=0;}
        game.options.setCameraType(CameraType.FIRST_PERSON);
        double yaw,pitch,velocityX=0,velocityZ=0;boolean sprint=false;
        if(scene==0) {yaw=SpearAimField.MAX_YAW_DEGREES*Math.sin(t*Math.PI/100);pitch=SpearAimField.MAX_PITCH_DEGREES*Math.sin(t*Math.PI/70);}
        else if(scene==1) {yaw=10*Math.sin(t*Math.PI/90);pitch=SpearAimField.MAX_PITCH_DEGREES*Math.sin(t*Math.PI/80);}
        else {
            // Fixed aim exposes stale locomotion/chest samples that sweeping aim masks.
            yaw=0;pitch=0;
            if(t<20)movementSegment="walk-start";
            else if(t<50){movementSegment="forward-walk";velocityZ=.10;}
            else if(t<80){movementSegment="forward-sprint";velocityZ=.17;sprint=true;}
            else if(t<100)movementSegment="walk-stop";
            else if(t<125){movementSegment="strafe-left";velocityX=-.10;}
            else if(t<150){movementSegment="strafe-right";velocityX=.10;}
            else if(t<175){movementSegment="backward-walk";velocityZ=-.10;}
            else {movementSegment=t<190?"moving-thrust":"moving-sweep";velocityX=.06;velocityZ=-.08;}
        }
        if(scene!=2&&scene!=3)movementSegment="stationary";
        int cycle=t%50;boolean sweep=t/50%2==1;String action="spear_idle",phase="READY";int remaining=0,w=0,a=0,r=0;
        AttackAim aim=null;long seq=100000L+scene*10000+captureProfile*5000+t/50;
        if(scene==1&&cycle<36) {
            w=sweep?6:5;a=sweep?10:4;r=sweep?12:9;int total=w+a+r;
            if(cycle<total) {
                action=sweep?"spear_sweep":"spear_thrust";
                if(cycle<w){phase="WINDUP";remaining=w-cycle;}else if(cycle<w+a){phase="ACTIVE";remaining=w+a-cycle;}else {phase="RECOVERY";remaining=total-cycle;}
                // A fixed path belongs to one attack, just as on a remote actor's actual server packets.
                double start=t-cycle;double attackYaw=new double[]{0,10,-10,0}[t/50%4];
                double attackPitch=SpearAimField.MAX_PITCH_DEGREES*Math.sin(start*Math.PI/80);
                aim=trajectory(attackYaw,attackPitch,sweep);
            }
        }
        // Movement commands apply in the next native entity tick. Start packets one
        // tick later so the final fixed-aim backward sample remains a READY sample.
        if((scene==2||scene==3)&&t>=176) {
            sweep=t>=191;cycle=t-(sweep?191:176);w=3;a=sweep?7:3;r=sweep?7:6;
            int total=w+a+r;double attackYaw=sweep?10:-10,attackPitch=(sweep?1:-1)*SpearAimField.MAX_PITCH_DEGREES;
            if(cycle<total) {
                action=sweep?"spear_sweep":"spear_thrust";seq=100000L+scene*10000+captureProfile*5000+4+(sweep?2:0);
                if(cycle<w){phase="WINDUP";remaining=w-cycle;}else if(cycle<w+a){phase="ACTIVE";remaining=w+a-cycle;}else{phase="RECOVERY";remaining=total-cycle;}
                aim=trajectory(attackYaw,attackPitch,sweep);
            } else seq=100000L+scene*10000+captureProfile*5000+5+(sweep?2:0);
        }
        if(scene==3){action=phase.equals("READY")?"rapier_idle":"legacy_rapier_attack1_upper";}
        fixtureAction=action;fixturePhase=phase;
        for(int profile=0;profile<2;profile++) {
            actors[profile].drive(t,movementSegment,velocityX,velocityZ,sprint);
            actors[profile].setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(scene==3?Items.DIAMOND_SWORD:Items.DIAMOND_SPEAR));
            place(actors[profile],scene==1?0:yaw,scene==1?0:pitch);
            if(scene==3)PlayerAnimations.accept(new RpgAnimationPayload(actors[profile].getUUID(),"rapier","legacy_rapier.fsvoxel",action,phase,remaining,w,a,r,seq,true,-1,-1,aim));
            else send(actors[profile],action,phase,remaining,w,a,r,aim,seq);
        }
        // This observer is a spectator, so its angles cannot mutate either spear fixture.
        double centerX=actors[captureProfile].getX(),centerZ=actors[captureProfile].getZ();
        double oldX=actors[captureProfile].xOld,oldZ=actors[captureProfile].zOld;
        game.player.setPos(4+centerX,81.5,6+centerZ);game.player.xo=game.player.xOld=4+oldX;game.player.yo=game.player.yOld=81.5;game.player.zo=game.player.zOld=6+oldZ;
        game.player.setYRot(146.31f);game.player.yRotO=146.31f;game.player.setXRot(21);game.player.xRotO=21;
    }
    private void ready(net.minecraft.server.level.ServerPlayer player) {
        var actor=RpgSmoke.actor(player);actor.forceSheathed();actor.requestDrawn();
        for(int i=0;i<200&&actor.phase()!=CombatPhase.READY;i++)actor.tick();
        actor.setResources(actor.rules().maxStamina(),actor.rules().maxSpirit());player.setOnGround(true);
        check(actor.phase()==CombatPhase.READY&&actor.drawn(),"Authoritative fixture completed its configured draw phase before measurement");
    }
    private void stamina(net.minecraft.server.level.ServerPlayer player,String id,double original,Runnable action) {
        ready(player);var actor=RpgSmoke.actor(player);
        // Independent resource cases must let the genuine repeated-contact window
        // expire; releasing/repressing guard intentionally does not clear it.
        if(id.contains("guard"))for(int i=0;i<=actor.guardRules().repeatHitWindowTicks();i++)actor.tick();
        actor.setResources(actor.rules().maxStamina(),actor.rules().maxSpirit());double before=actor.stamina();action.run();
        double spent=before-actor.stamina();var value=new JsonObject();value.addProperty("action",id);
        value.addProperty("originalCost",original);value.addProperty("expectedCost",original*.2);value.addProperty("actualDeduction",spent);
        value.addProperty("serverPhase",actor.phase().name());value.addProperty("serverActor",player.getUUID().toString());
        value.addProperty("measurementInsideSingleServerTask",true);
        value.addProperty("trigger",id.contains("guard")?"RpgServer.action(guard) then CombatResolver contact on the actual integrated-server actor":"RpgServer.action authoritative request");
        serverStamina.add(value);
        check(Math.abs(spent-original*.2)<.000001,"Integrated-server "+id+" deducts exactly 20% of original cost");
    }
    private void verifyServerStamina(net.minecraft.server.level.ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));player.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        var rpg=RpgServer.instance();var actor=RpgSmoke.actor(player);actor.forceSheathed();actor.setStyle(rpg.config().current().styles().get("spear"));
        player.connection.teleport(player.getX(),player.getY(),player.getZ(),0,0);
        // Establish the changed real ItemStack in the equipment controller first;
        // otherwise the first measured action correctly starts automatic drawing.
        rpg.action(player,"status","");
        for(String button:List.of("left","sweep"))stamina(player,button.equals("left")?"spear_thrust":"spear_sweep",button.equals("left")?20:30,()->{
            var request=new JsonObject();request.addProperty("button",button);request.add("aim",RpgConfig.JSON.toJsonTree(trajectory(0,0,button.equals("sweep"))));
            rpg.action(player,"aimAttack",request.toString());
        });
        stamina(player,"dodge",30,()->rpg.action(player,"dodge",""));
        stamina(player,"spear_light_guard",55,()->{
            rpg.action(player,"guard","light");for(int i=0;i<5;i++)actor.tick();
            // Ignore recovery accrued before contact when measuring this real server actor's deduction.
            actor.setResources(actor.rules().maxStamina(),actor.rules().maxSpirit());
            org.fsrpg.combat.CombatResolver.receiveIncoming(null,actor,0,1,0,true,false,false);
        });
        stamina(player,"spear_perfect_guard",55,()->{
            rpg.action(player,"guard","light");org.fsrpg.combat.CombatResolver.receiveIncoming(null,actor,0,1,0,true,true,false);
        });
        ready(player);
    }
    private void updateCenterAim(Minecraft game,int t) {
        if(t==0) {
            for(var actor:actors)if(actor!=null)actor.discard();
            game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);CombatCamera.reset();
            UUID id=game.player.getUUID();game.getSingleplayerServer().execute(()->{try{
                var player=game.getSingleplayerServer().getPlayerList().getPlayer(id);player.setGameMode(GameType.SURVIVAL);
                player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));player.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
                player.connection.teleport(0,80,0,0,0);ready(player);RpgServer.instance().action(player,"status","");
            }catch(Throwable error){failure=error;}});
        }
        if(t<25)return;
        if(!CombatCamera.active())throw new AssertionError("Local player has no drawn third-person spear camera");
        // Establish any server correction before converting target angles into real mouse input.
        CombatCamera.yaw();CombatCamera.pitch();
        game.options.keyUp.setDown(t>=140&&t<165);game.options.keyLeft.setDown(t>=165&&t<185);
        game.options.keyRight.setDown(t>=185&&t<205);game.options.keyDown.setDown(t>=205&&t<230);
        movementSegment=t<140?"stationary":t<165?"local-forward":t<185?"local-strafe-left":t<205?"local-strafe-right":t<230?"local-backward":"local-stop";
        if(t==90) {
            var camera=game.gameRenderer.getMainCamera();var ray=new Vector3f(0,0,-1).rotate(camera.rotation());
            double distance=(game.player.getZ()+3.25-camera.position().z)/ray.z;
            var where=camera.position().add(new net.minecraft.world.phys.Vec3(ray).scale(distance));
            var nearAim=SpearAimField.target(where.x-game.player.getEyePosition().x,where.y-game.player.getEyePosition().y,where.z-game.player.getEyePosition().z,game.player.getYRot());
            if(nearAim==null||!SpearAimField.contains(nearAim,game.player.getYRot()))
                throw new AssertionError("Near-entity fixture must be inside the actual eye-angle field");
            UUID id=game.player.getUUID();game.getSingleplayerServer().execute(()->{try{
                var player=game.getSingleplayerServer().getPlayerList().getPlayer(id);var target=new net.minecraft.world.entity.monster.zombie.Zombie(player.level());
                target.setNoAi(true);target.setNoGravity(true);target.setPos(where.x,80,where.z);
                target.getPersistentData().putBoolean("fsrpgActionLimitsNearTarget",true);player.level().addFreshEntity(target);nearTargetEntityId=target.getId();
            }catch(Throwable error){failure=error;}});
        }
        if(t==130) {
            int id=nearTargetEntityId;game.getSingleplayerServer().execute(()->{var target=game.getSingleplayerServer().overworld().getEntity(id);if(target!=null)target.discard();});
        }
        double desiredYaw=30*Math.sin(t*Math.PI/50),desiredPitch=18*Math.sin(t*Math.PI/35);
        // Settle the actual shoulder camera before spawning the near entity, then hold its center ray.
        if(t>=75&&t<=130){desiredYaw=0;desiredPitch=0;}
        double sensitivity=game.options.sensitivity().get()*.6+.2,gain=sensitivity*sensitivity*sensitivity*8;
        double beforeYaw=CombatCamera.yaw(),beforePitch=CombatCamera.pitch();
        double dx=AttackAim.wrap(desiredYaw-beforeYaw)/(gain*.15)*(game.options.invertMouseX().get()?-1:1);
        double dy=(desiredPitch-beforePitch)/(gain*.15)*(game.options.invertMouseY().get()?-1:1);
        var trace=new JsonObject();trace.addProperty("scriptTick",t);trace.addProperty("actorYawBefore",game.player.getYRot());trace.addProperty("bodyYawBefore",game.player.yBodyRot);
        trace.addProperty("aimInputMode","center-reticle-camera-angle");trace.addProperty("cameraYawBefore",beforeYaw);trace.addProperty("cameraPitchBefore",beforePitch);
        trace.addProperty("desiredCameraYaw",desiredYaw);trace.addProperty("desiredCameraPitch",desiredPitch);trace.addProperty("mouseInputDx",dx);trace.addProperty("mouseInputDy",dy);trace.addProperty("sensitivityGain",gain);
        trace.add("renderedCameraRotationAtInput",RpgConfig.JSON.toJsonTree(game.gameRenderer.getMainCamera().rotation()));
        boolean used=CombatCamera.mouse(dx,dy);
        trace.addProperty("actorYawAfter",game.player.getYRot());trace.addProperty("bodyYawAfter",game.player.yBodyRot);trace.addProperty("cursorXAfter",CombatCamera.cursorX());trace.addProperty("cursorYAfter",CombatCamera.cursorY());
        trace.addProperty("cameraYawAfter",CombatCamera.yaw());trace.addProperty("cameraPitchAfter",CombatCamera.pitch());trace.add("centerRayWorld",RpgConfig.JSON.toJsonTree(centerRay(game)));cursorInputTrace.add(trace);
        if(!used)throw new AssertionError("Actual spear mouse path did not accept direct center-aim input");
        if(Math.abs(AttackAim.wrap(CombatCamera.yaw()-desiredYaw))>.001||Math.abs(CombatCamera.pitch()-desiredPitch)>.001)
            throw new AssertionError("Actual camera did not reach its directly requested mouse angles");
        if(Math.abs(AttackAim.wrap(game.player.getYRot()-CombatCamera.yaw()))>.001||Math.abs(AttackAim.wrap(game.player.yBodyRot-CombatCamera.yaw()))>.001)
            throw new AssertionError("Actual actor and body did not follow the centered camera heading");
        if(CombatCamera.cursorX()!=0||CombatCamera.cursorY()!=0)throw new AssertionError("Direct center aiming displaced its fixed screen reticle");
        var aim=CombatCamera.aimPoint();double body=Math.abs(AttackAim.wrap(game.player.getYRot()));
        maximumCursorBodyYaw=Math.max(maximumCursorBodyYaw,body);maximumCursorAimYaw=Math.max(maximumCursorAimYaw,Math.abs(AttackAim.wrap(aim.yaw()-game.player.getYRot())));
        maximumCursorAimPitch=Math.max(maximumCursorAimPitch,Math.abs(aim.pitch()));
        trace.add("centerAim",RpgConfig.JSON.toJsonTree(aim));
        if(!SpearAimField.contains(aim,game.player.getYRot()))throw new AssertionError("Centered spear ray resolved outside shared aim field "+aim);
        if(t>=105&&t<=125)verifyNearEntity(game,aim);
        cursorChecks++;
    }
    private static Vector3f centerRay(Minecraft game){return new Vector3f(0,0,-1).rotate(game.gameRenderer.getMainCamera().rotation());}
    private void verifyNearEntity(Minecraft game,AttackAim.Point aim) {
        var target=game.level.getEntity(nearTargetEntityId);if(target==null)throw new AssertionError("Integrated-server near target did not reach actual client picking");
        var camera=game.gameRenderer.getMainCamera();var ray=centerRay(game);
        var hit=target.getBoundingBox().inflate(.08).clip(camera.position(),camera.position().add(new net.minecraft.world.phys.Vec3(ray).scale(64)));
        if(hit.isEmpty())throw new AssertionError("Centered camera ray missed its actual nearby fixture");
        var delta=hit.get().subtract(game.player.getEyePosition());
        var expected=SpearAimField.target(delta.x,delta.y,delta.z,game.player.getYRot());
        double forwardDepth=aim.distance()*Math.cos(Math.toRadians(AttackAim.wrap(aim.yaw()-game.player.getYRot())))*Math.cos(Math.toRadians(aim.pitch()));
        if(expected==null||delta.length()>=SpearAimField.MIN_FORWARD_DISTANCE||Math.abs(AttackAim.wrap(aim.yaw()-expected.yaw()))>.001
                ||Math.abs(aim.pitch()-expected.pitch())>.001||Math.abs(aim.distance()-expected.distance())>.002||forwardDepth<SpearAimField.MIN_FORWARD_DISTANCE-.002)
            throw new AssertionError("Nearby hit must retain exact eye direction and safe extended target depth "+aim+" expected="+expected+" hit="+hit.get());
        var value=new JsonObject();value.addProperty("fixtureTick",elapsed);value.addProperty("actualNearHitDistanceMeters",delta.length());
        value.addProperty("returnedForwardDepthMeters",forwardDepth);value.add("actualNearHitWorld",RpgConfig.JSON.toJsonTree(new double[]{hit.get().x,hit.get().y,hit.get().z}));
        value.add("expectedAim",RpgConfig.JSON.toJsonTree(expected));value.add("returnedAim",RpgConfig.JSON.toJsonTree(aim));nearEntityMeasurements.add(value);
        nearEntityChecks++;
    }
    private void verifySword(JsonObject observation,String profile) {
        if(!observation.has("rightPalmCm")||!RenderObservations.stableLowerSupport(observation))throw new AssertionError("Sword has no supported live rig "+profile);
        if(!observation.getAsJsonObject("renderAnimation").get("model").getAsString().equals("legacy_rapier.fsvoxel"))throw new AssertionError("Sword upper asset changed "+profile);
        try {
            @SuppressWarnings("unchecked") var bindings=(Map<UUID,Object>)diagnosticField(PlayerAnimations.class,null,"bindings");
            Fixture actor=actors[profile.equals("stock")?0:1];Object binding=bindings.get(actor.getUUID());
            var frame=(PlayerAnimations.Frame)diagnosticField(binding.getClass(),binding,"sampled");
            double start=((Number)diagnosticField(binding.getClass(),binding,"transitionStarted")).doubleValue();
            double duration=((Number)diagnosticField(binding.getClass(),binding,"transitionTicks")).doubleValue();
            if(frame.sampledTick()<start+duration+.01)return;
            var state=(RpgAnimationPayload)diagnosticField(binding.getClass(),binding,"state");
            if(state.phase().equals("READY")&&elapsed>20){double tilt=observation.get("torsoTiltDegrees").getAsDouble();maximumSwordReadyTilt=Math.max(maximumSwordReadyTilt,tilt);if(tilt>6)throw new AssertionError("Sword READY movement tilt is excessive "+tilt);}
            double seconds=((Number)diagnosticField(binding.getClass(),binding,"sampledClipSeconds")).doubleValue();
            var motion=(PresentationMotion)diagnosticField(binding.getClass(),binding,"motion");
            var rateField=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField("BREATH_SPEED");rateField.setAccessible(true);
            double rate=((net.neoforged.neoforge.common.ModConfigSpec.DoubleValue)rateField.get(null)).get();
            double breath=motion.breathingSeconds(frame.sampledTick(),rate);
            var source=SwordMotion.sampleUpper(frame.model(),frame.model().rig().clips().get(state.action()),seconds,breath,actor.getXRot(),0);
            var differences=new JsonObject();
            for(String side:List.of("right","left"))for(String suffix:List.of("_arm","_forearm","_hand")) {
                String bone=side+suffix;double difference=angle(source.get(bone).fsQuaternion().normalize(),frame.local().get(bone).fsQuaternion().normalize());
                differences.addProperty(bone,difference);maximumSwordSourceArmDifference=Math.max(maximumSwordSourceArmDifference,difference);
                if(difference>20)throw new AssertionError("Sword arm drifted too far from the authored clip "+profile+" "+bone+" "+difference);
            }
            observation.addProperty("swordSourceStable",true);observation.add("oldSwordSourceArmDifferenceDegrees",differences);swordSourceComparisons++;
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot inspect old sword source through actual sampled frame",error);}
    }
    private JsonObject observe() {
        if(scene==4){JsonObject cursor=samplers[0].get();cursor.addProperty("fixtureTick",elapsed);cursor.addProperty("fixtureAction","spear_idle");cursor.addProperty("fixturePhase","READY");cursor.addProperty("movementSegment",movementSegment);cursor.addProperty("actorYaw",Minecraft.getInstance().player.getYRot());cursor.addProperty("aimInputMode","center-reticle-camera-angle");cursor.addProperty("cursorX",CombatCamera.cursorX());cursor.addProperty("cursorY",CombatCamera.cursorY());cursor.add("centerRayWorld",RpgConfig.JSON.toJsonTree(centerRay(Minecraft.getInstance())));cursor.add("cursorAim",RpgConfig.JSON.toJsonTree(CombatCamera.aimPoint()));try{verify(cursor,"local");}catch(Throwable error){try{failureFrame(cursor,error);}catch(Throwable recording){error.addSuppressed(recording);}throw error;}sampledFrames++;return cursor;}
        JsonObject stock=samplers[0].get();
        {
            JsonObject value=stock;Fixture actor=actors[captureProfile];
            value.addProperty("movementSegment",actor.appliedSegment);value.addProperty("movementScriptTick",actor.appliedTick);
            value.addProperty("commandedMovementSegment",movementSegment);
            value.add("fixtureVelocityMetersPerTick",RpgConfig.JSON.toJsonTree(new double[]{actor.appliedVelocityX,actor.appliedVelocityZ}));
            value.addProperty("constantAimMovement",(scene==2||scene==3)&&actor.appliedTick>=20&&actor.appliedTick<175);
        }
        stock.addProperty("profile",captureProfile==0?"stock":"slim");stock.addProperty("fixtureAction",fixtureAction);stock.addProperty("fixturePhase",fixturePhase);stock.addProperty("fixtureTick",elapsed);
        try{verify(stock,captureProfile==0?"stock":"slim");}
        catch(Throwable error){
            try{failureFrame(stock,error);}catch(Throwable recording){error.addSuppressed(recording);}
            throw error;
        }
        sampledFrames++;
        return stock;
    }
    private void failureFrame(JsonObject observation,Throwable error)throws Exception {
        var saved=new JsonObject();saved.addProperty("error",error.toString());saved.addProperty("capturedAtUtc",Instant.now().toString());
        saved.addProperty("clip",scene>=0&&scene<CLIPS.size()?CLIPS.get(scene):"");saved.addProperty("scene",scene);saved.addProperty("fixtureTick",elapsed);
        saved.addProperty("fixtureAction",fixtureAction);saved.addProperty("fixturePhase",fixturePhase);saved.addProperty("successfulSamplesBeforeFailure",sampledFrames);
        saved.add("observation",observation);var frames=new JsonObject();saved.add("actualSampledFrames",frames);
        for(int profile=0;profile<2;profile++) {
            var actorFrame=new JsonObject();frames.add(profile==0?"stock":"slim",actorFrame);
            try {
                @SuppressWarnings("unchecked") var bindings=(Map<UUID,Object>)diagnosticField(PlayerAnimations.class,null,"bindings");
                Object binding=bindings.get(actors[profile].getUUID());
                var frame=(PlayerAnimations.Frame)diagnosticField(binding.getClass(),binding,"sampled");
                actorFrame.addProperty("sampledTick",frame.sampledTick());actorFrame.add("solvedWorldMatrices",diagnosticMatrices(frame.world()));
                var locals=new JsonObject();frame.local().forEach((bone,pose)->{
                    var q=pose.fsQuaternion();var local=new JsonObject();local.add("quaternion",RpgConfig.JSON.toJsonTree(new float[]{q.x,q.y,q.z,q.w}));
                    local.add("rotationDegrees",RpgConfig.JSON.toJsonTree(pose.rotation()));local.add("positionCm",RpgConfig.JSON.toJsonTree(pose.position()));local.add("scale",RpgConfig.JSON.toJsonTree(pose.scale()));locals.add(bone,local);
                });actorFrame.add("localPose",locals);
                Object motion=diagnosticField(binding.getClass(),binding,"spearMotion");
                @SuppressWarnings("unchecked") var source=(Map<String,Matrix4f>)diagnosticField(motion.getClass(),motion,"preparedSource");
                actorFrame.add("sourceWorldMatrices",diagnosticMatrices(source));
                for(String name:List.of("previous","anchor","committed")) {
                    Object snapshot=diagnosticField(motion.getClass(),motion,name);if(snapshot==null)continue;
                    var history=new JsonObject();actorFrame.add(name,history);history.add("tick",RpgConfig.JSON.toJsonTree(diagnosticField(snapshot.getClass(),snapshot,"tick")));
                    for(String matrices:List.of("source","solved")) {
                        @SuppressWarnings("unchecked") var values=(Map<String,Matrix4f>)diagnosticField(snapshot.getClass(),snapshot,matrices);
                        history.add(matrices+"WorldMatrices",diagnosticMatrices(values));
                    }
                }
            }catch(Throwable diagnostic){actorFrame.addProperty("diagnosticError",diagnostic.toString());}
        }
        RpgConfig.atomicWrite(directory.resolve("failure-frame.json"),RpgConfig.JSON.toJson(saved));
        Screenshot.grab(directory.toFile(),"failure-current-frame.png",Minecraft.getInstance().getMainRenderTarget(),1,unused->{});
    }
    private Object diagnosticField(Class<?> type,Object instance,String name)throws ReflectiveOperationException {
        var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    private JsonObject diagnosticMatrices(Map<String,Matrix4f> matrices) {
        var result=new JsonObject();if(matrices!=null)matrices.forEach((bone,matrix)->result.add(bone,RpgConfig.JSON.toJsonTree(matrix.get(new float[16]))));return result;
    }
    private Vector3f vector(JsonObject object,String key){var a=object.getAsJsonArray(key);return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    private void verify(JsonObject observation,String profile) {
        if(scene==3){verifySword(observation,profile);return;}
        if(!observation.has("rightPalmCm"))throw new AssertionError("Missing live spear runtime frame "+profile);
        if(!observation.has("completeSpearShaftClear")||!observation.get("completeSpearShaftClear").getAsBoolean())throw new AssertionError("Complete rendered spear pole crosses torso/head "+profile);
        if(!RenderObservations.stableLowerSupport(observation))throw new AssertionError("Unstable actual spear lower-body support "+profile);
        var rear=vector(observation,"rightPalmCm");var front=vector(observation,"leftPalmCm");var axis=vector(observation,"rightBladeAxis");
        double separation=rear.distance(front),grip=separation<44-.03||separation>70+.03?1:0,alignment=new Vector3f(front).sub(rear).cross(axis).length();
        maximumGripError=Math.max(maximumGripError,grip);maximumAxisError=Math.max(maximumAxisError,alignment);
        if(grip>.03||alignment>.03)throw new AssertionError("Closed two-hand grip drift "+profile+" spacing="+separation+" axis="+alignment);
        for(String side:List.of("left","right")) {
            double flex=observation.get(side+"WristFlexionDegrees").getAsDouble();maximumWristFlex=Math.max(maximumWristFlex,flex);
            if(flex>.1)throw new AssertionError("Bent spear wrist "+profile+" "+side+" "+flex);
            var hinge=observation.getAsJsonArray(side+"ForearmLocalQuaternion");
            double bend=Math.IEEEremainder(Math.toDegrees(2*Math.atan2(hinge.get(0).getAsDouble(),hinge.get(3).getAsDouble())),360);
            if(bend<9.97||bend>135.03||observation.get(side+"ForearmOffXHingeDegrees").getAsDouble()>.1)
                throw new AssertionError("Spear elbow leaves positive 10..135 degree pure hinge "+profile+" "+side+" "+bend);
        }
        JsonObject rig=observation.getAsJsonObject("rig");
        var torso=rig.getAsJsonArray("body");
        if(torso.get(8).getAsDouble()*axis.x+torso.get(10).getAsDouble()*axis.z<=0)throw new AssertionError("Spear torso turns away from its actual aimed pole "+profile);
        if(profile.equals("local")) {
            var aim=observation.getAsJsonObject("cursorAim");var solved=observation.getAsJsonObject("renderAnimation").getAsJsonObject("lastAim");
            double yawError=Math.abs(AttackAim.wrap(solved.get("yaw").getAsDouble()-aim.get("yaw").getAsDouble()));
            double pitchError=Math.abs(solved.get("pitch").getAsDouble()-aim.get("pitch").getAsDouble());
            observation.addProperty("actualAimYawDifferenceDegrees",yawError);observation.addProperty("actualAimPitchDifferenceDegrees",pitchError);
            double delta=Math.toRadians(observation.get("bodyYaw").getAsDouble()-observation.get("actorYaw").getAsDouble());
            var body=rig.getAsJsonArray("body");double forward=body.get(8).getAsDouble()*axis.x+body.get(10).getAsDouble()*axis.z;
            observation.addProperty("worldTorsoForwardDotActorAim",forward);
            if(forward<=0)throw new AssertionError("Actual local moving torso crossed behind actor aim in "+movementSegment);
            if(yawError>.02||pitchError>.02)throw new AssertionError("Actual local body rotation changes the rendered center-ray aim in "+movementSegment+" yawError="+yawError+" bodyYaw="+observation.get("bodyYaw")+" actorYaw="+observation.get("actorYaw"));
        }
        for(String joint:JOINTS) {
            var a=rig.getAsJsonArray(joint);var values=new float[16];for(int i=0;i<16;i++) {values[i]=a.get(i).getAsFloat();if(!Float.isFinite(values[i]))throw new AssertionError("Non-finite spear joint "+profile+" "+joint);}
            var next=new Matrix4f().set(values).getUnnormalizedRotation(new Quaternionf()).normalize();String key=profile+":"+joint;
            var old=previousJoints.put(key,new Quaternionf(next));if(old!=null) {
                double step=Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(old.dot(next)),0,1)));peakSteps.merge(key,step,Math::max);
            }
        }
    }
    private void tick(ClientTickEvent.Post event) {
        var game=Minecraft.getInstance();if(done||game.player==null||game.level==null||game.getSingleplayerServer()==null)return;
        try {
            if(failure!=null)throw new IllegalStateException("Spear runtime fixture failed",failure);
            if(game.isPaused()){game.setScreen(null);return;}game.getFramerateLimitTracker().onInputReceived();movies.assertHealthy();
            if(!initialized){if(++warmup<100)return;initialize(game);return;}
            if(!serverReady)return;if(actors[0]==null){fixtures(game);warmup=0;return;}
            if(scene<0){if(++warmup<60)return;scene=0;elapsed=0;}
            if(scene>=CLIPS.size()){finish(game);return;}
            update(game,elapsed);
            if(scene<4)actors[1-captureProfile].setPos(3000,80,3000);
            if(elapsed==(scene==4?50:10)) {
                if(scene==4)samplers[0]=RenderObservations.submittedSampler(game.player);
                else{samplers[0]=RenderObservations.submittedSampler(actors[captureProfile]);}
                movies.start(CLIPS.get(scene)+(scene<4&&captureProfile==1?"-slim":""),"single rendered stock/slim profile; authoritative animation packets, actual PlayerAnimations solver and official renderer",this::observe);captureStarted=true;
            }
            if(elapsed==55||elapsed==135)screenshots.add(CLIPS.get(scene)+(scene<4&&captureProfile==1?"-slim":"")+"-"+elapsed);
            if(scene==4&&elapsed==115)screenshots.add("spear-center-aim-near-target-115");
            if(++elapsed>=(scene==4?250:210)){movies.stop();captureStarted=false;elapsed=0;if(scene<4&&captureProfile==0)captureProfile=1;else{captureProfile=0;scene++;}previousJoints.clear();}
        }catch(Throwable error){try{report(false,error.toString());}catch(Exception ignored){}error.printStackTrace();restore(game);done=true;game.stop();}
    }
    private void render(RenderFrameEvent.Post event) {
        if(done)return;try {
            movies.frame(event);movies.assertHealthy();while(!screenshots.isEmpty()) {
                String name=screenshots.poll()+".png";Screenshot.grab(directory.toFile(),name,Minecraft.getInstance().getMainRenderTarget(),1,unused->{});
                var item=new JsonObject();item.addProperty("scene",scene);item.addProperty("file","screenshots/"+name);item.addProperty("fixtureTick",elapsed);images.add(item);
            }
        }catch(Throwable error){failure=error;}
    }
    private void finish(Minecraft game)throws Exception {
        if(!movies.finish())return;
        if(importedAssets==null){importedAssets=new ImportedAssetsScreen();game.setScreen(importedAssets);return;}
        if(++importedAssetTicks==10)screenshots.add("imported-261001-items");
        if(importedAssetTicks<25)return;
        check(importedAssets.observations.size()==9&&importedAssets.drawnFrames()>10,"Nine imported native item models actually rendered in game");
        check(Files.exists(directory.resolve("screenshots/imported-261001-items.png")),"Imported item screenshot completed");
        var summary=movies.summary();
        for(var value:summary.getAsJsonArray("clips")) {var clip=value.getAsJsonObject();check(clip.get("sourceFps").getAsDouble()>=30,"Actual rendered capture >=30fps "+clip.get("id").getAsString());check(clip.get("distinctSamplesWithinGameTick").getAsInt()>0,"Live sub-tick spear samples "+clip.get("id").getAsString());}
        check(OfficialHeldItems.submissions()>0&&OfficialHeldItems.lastItemId().equals("minecraft:diamond_spear"),"Actual vanilla spear ItemStack reached official renderer");
        validateMovement(summary,"spear-moving-bounded",movementValidation);
        validateMovement(summary,"sword-old-upper-modern-lower",swordMovementValidation);
        check(swordSourceComparisons>100,"Stable rendered sword arm rotations preserve the original upper source");
        check(nearEntityChecks>=20,"Actual nearby entity retains exact eye direction and safe extended scalar depth while live spear rig stays safe");
        report(true,"");restore(game);done=true;game.stop();
    }
    private void validateMovement(JsonObject capture,String clipId,JsonObject target) {
        for(String profile:List.of("stock","slim")) {
            String identity=clipId+(profile.equals("slim")?"-slim":"");
            JsonObject clip=null;for(var value:capture.getAsJsonArray("clips"))if(value.getAsJsonObject().get("id").getAsString().equals(identity))clip=value.getAsJsonObject();
            if(clip==null)throw new AssertionError("Missing movement clip "+identity);
            Map<String,List<JsonObject>> segments=new HashMap<>();
            for(var frame:clip.getAsJsonArray("frames")) {
                JsonObject observation=frame.getAsJsonObject().getAsJsonObject("observation");
                segments.computeIfAbsent(observation.get("movementSegment").getAsString(),unused->new ArrayList<>()).add(observation);
            }
            JsonObject actorResult=new JsonObject();target.add(profile,actorResult);
            for(String segment:List.of("walk-start","forward-walk","forward-sprint","walk-stop","strafe-left","strafe-right","backward-walk","moving-thrust","moving-sweep")) {
                var samples=segments.get(segment);check(samples!=null&&samples.size()>=5,profile+" rendered movement segment "+segment);
                JsonObject result=new JsonObject();actorResult.add(segment,result);result.addProperty("frames",samples.size());
                double travel=0;int previousTick=-1;double minimumSpeed=Double.POSITIVE_INFINITY,maximumSpeed=0;
                double minimumKnee=Double.POSITIVE_INFINITY,maximumKnee=0,minimumRoot=Double.POSITIVE_INFINITY,maximumRoot=Double.NEGATIVE_INFINITY;
                double gaitTravel=0,previousGait=Double.NaN,previousSample=Double.NaN;int fresh=0,supportSwitches=0;float oldSupport=-1;
                double bodyRange=0,rightArmRange=0,leftArmRange=0;
                Quaternionf firstBody=rotation(samples.getFirst(),"body"),firstRightArm=rotation(samples.getFirst(),"right_arm"),firstLeftArm=rotation(samples.getFirst(),"left_arm");
                for(var sample:samples) {
                    int actorTick=sample.get("actorTickCount").getAsInt();
                    if(actorTick!=previousTick) {var delta=sample.getAsJsonArray("deltaMovementMetersPerTick");travel+=Math.hypot(delta.get(0).getAsDouble(),delta.get(2).getAsDouble());previousTick=actorTick;}
                    double speed=sample.get("walkAnimationSpeed").getAsDouble();minimumSpeed=Math.min(minimumSpeed,speed);maximumSpeed=Math.max(maximumSpeed,speed);
                    for(String side:List.of("right","left")) {double knee=sample.get(side+"Knee").getAsDouble();minimumKnee=Math.min(minimumKnee,knee);maximumKnee=Math.max(maximumKnee,knee);}
                    double root=sample.getAsJsonArray("rootCm").get(1).getAsDouble();minimumRoot=Math.min(minimumRoot,root);maximumRoot=Math.max(maximumRoot,root);
                    double sampleTick=sample.get("sampledTick").getAsDouble();if(!Double.isNaN(previousSample)&&sampleTick>previousSample+.00001)fresh++;previousSample=sampleTick;
                    JsonObject lower=sample.getAsJsonObject("finalLowerBody");double gait=lower.get("phaseRadians").getAsDouble();
                    if(!Double.isNaN(previousGait)){double step=Math.atan2(Math.sin(gait-previousGait),Math.cos(gait-previousGait));gaitTravel+=Math.abs(step);}previousGait=gait;
                    float support=lower.getAsJsonObject("right").get("support").getAsFloat();if(oldSupport>=0&&support!=oldSupport)supportSwitches++;oldSupport=support;
                    bodyRange=Math.max(bodyRange,angle(firstBody,rotation(sample,"body")));rightArmRange=Math.max(rightArmRange,angle(firstRightArm,rotation(sample,"right_arm")));leftArmRange=Math.max(leftArmRange,angle(firstLeftArm,rotation(sample,"left_arm")));
                }
                result.addProperty("actualTravelMeters",travel);result.addProperty("minimumWalkSpeed",minimumSpeed);result.addProperty("maximumWalkSpeed",maximumSpeed);
                result.addProperty("kneeRangeRadians",maximumKnee-minimumKnee);result.addProperty("rootYRangeCm",maximumRoot-minimumRoot);
                result.addProperty("gaitTravelRadians",gaitTravel);result.addProperty("supportSwitches",supportSwitches);result.addProperty("freshSamplingPairs",fresh);
                result.addProperty("chestRangeDegrees",bodyRange);result.addProperty("rightUpperArmRangeDegrees",rightArmRange);result.addProperty("leftUpperArmRangeDegrees",leftArmRange);
                check(fresh>samples.size()*.8,profile+" fresh per-render pose clock "+segment);
                boolean moving=!segment.equals("walk-start")&&!segment.equals("walk-stop");
                if(moving) {
                    check(travel>.5&&maximumSpeed>.25,profile+" actual displacement drives vanilla walk speed "+segment);
                    check(gaitTravel>.5&&supportSwitches>0&&maximumKnee-minimumKnee>.03,profile+" gait, support and knees animate "+segment);
                    check(maximumRoot-minimumRoot>.03,profile+" moving root remains live "+segment);
                    if(!segment.startsWith("moving-"))check(bodyRange>.1&&Math.min(rightArmRange,leftArmRange)>.05,profile+" upper pose remains live at constant aim "+segment);
                } else check(samples.getLast().get("walkAnimationSpeed").getAsDouble()<.025,profile+" vanilla walk speed settles after stop "+segment);
            }
        }
        target.addProperty("passed",true);
    }
    private Quaternionf rotation(JsonObject sample,String joint) {
        var matrix=sample.getAsJsonObject("rig").getAsJsonArray(joint);var values=new float[16];for(int i=0;i<16;i++)values[i]=matrix.get(i).getAsFloat();
        return new Matrix4f().set(values).getUnnormalizedRotation(new Quaternionf()).normalize();
    }
    private double angle(Quaternionf first,Quaternionf second){return Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(first.dot(second)),0,1)));}
    private void report(boolean passed,String error)throws Exception {
        Files.createDirectories(directory);var report=new JsonObject();report.addProperty("passed",passed);report.addProperty("error",error);report.addProperty("scope","bounded spear/arms; old sword upper with modern lower/mild lean; integrated-server stamina");
        report.addProperty("worldCopy",world==null?"":world.toString());report.addProperty("checks",checks);report.addProperty("sampledFrames",sampledFrames);report.addProperty("maximumGripSpacingErrorCm",maximumGripError);report.addProperty("maximumGripAxisErrorCm",maximumAxisError);report.addProperty("maximumWristFlexionDegrees",maximumWristFlex);
        report.add("peakAdjacentRenderedJointStepsDegrees",RpgConfig.JSON.toJsonTree(peakSteps));report.add("images",images);RpgSmoke.windowEnvironment(report);
        if(importedAssets!=null){report.add("importedItems",importedAssets.observations);report.addProperty("importedItemRenderedFrames",importedAssets.drawnFrames());}
        var aimField=new JsonObject();aimField.addProperty("maxYawDegrees",SpearAimField.MAX_YAW_DEGREES);aimField.addProperty("maxPitchDegrees",SpearAimField.MAX_PITCH_DEGREES);aimField.addProperty("cameraPitchDegrees",SpearAimField.CAMERA_PITCH_DEGREES);aimField.addProperty("minForwardDistanceMeters",SpearAimField.MIN_FORWARD_DISTANCE);report.add("aimField",aimField);report.addProperty("minimumPreparedAttackAimForwardDepthMeters",Double.isFinite(minimumPreparedAttackForwardDepth)?minimumPreparedAttackForwardDepth:0);
        report.add("movementValidation",movementValidation);report.add("swordMovementValidation",swordMovementValidation);report.addProperty("swordSourceComparisons",swordSourceComparisons);report.addProperty("maximumSwordSourceArmDifferenceDegrees",maximumSwordSourceArmDifference);report.add("serverStamina",serverStamina);report.addProperty("aimInputMode","center-reticle-camera-angle");report.addProperty("cursorChecks",cursorChecks);report.add("cursorInputTrace",cursorInputTrace);report.addProperty("nearEntityChecks",nearEntityChecks);report.add("nearEntityMeasurements",nearEntityMeasurements);report.addProperty("nearTargetSemantics","Actual near entity retains eye-to-hit yaw/pitch; returned scalar target distance extends along that exact direction to safe forward depth");report.addProperty("maximumCursorAimYawDegrees",maximumCursorAimYaw);report.addProperty("maximumCursorAimPitchDegrees",maximumCursorAimPitch);report.addProperty("maximumCursorBodyYawDegrees",maximumCursorBodyYaw);report.addProperty("maximumSwordReadyTiltDegrees",maximumSwordReadyTilt);
        var limits=new JsonObject();
        for(String name:List.of("SPEAR_ELBOW_LIMIT","SPEAR_WRIST_LIMIT","SPEAR_CLEARANCE","SPEAR_NECK_LIMIT","SPEAR_VERTICAL_BANK")) {
            var field=Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField(name);field.setAccessible(true);
            var value=(net.neoforged.neoforge.common.ModConfigSpec.DoubleValue)field.get(null);limits.addProperty(name,value.get());
        }
        report.add("liveSolverLimits",limits);
        try(var bytecode=org.fsrpg.client.SpearAimPose.class.getResourceAsStream("SpearAimPose.class")) {
            report.addProperty("loadedSolverBytecodeSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytecode.readAllBytes())));
        }
        var source=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJarSha256",Files.isRegularFile(source)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))):"");
        RpgConfig.atomicWrite(directory.resolve("action-limits-smoke.json"),RpgConfig.JSON.toJson(report));
    }
    private void restore(Minecraft game) {
        KeyMapping.releaseAll();if(!initialized)return;game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.fov().set(previousFov);game.options.pauseOnLostFocus=previousPause;game.options.hideGui=previousHidden;game.options.enableVsync().set(previousVsync);game.options.setCameraType(previousCamera);
        for(var actor:actors)if(actor!=null)actor.discard();
    }
}

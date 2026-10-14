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
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Thirty seconds of spear-only live runtime fixtures, through normal skin/item rendering. */
final class SpearSolverReview {
    private static final Path ROOT=Path.of(System.getProperty("fsrpg.smokeEvidence","../../verification/spear-blend-20261012/game")).toAbsolutePath().normalize();
    private static final List<String> CLIPS=List.of("spear-ready-extremes","spear-thrust-sweep-return","spear-moving-extremes");
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
    private int previousFps,previousDistance,previousScale,previousFov;
    private boolean previousPause,previousHidden,previousVsync;
    private CameraType previousCamera;
    private Path world;
    private String fixtureAction="spear_idle",fixturePhase="READY";
    private long sequence=50000;
    private double maximumGripError,maximumAxisError,maximumWristFlex;
    private int sampledFrames;
    private String movementSegment="stationary";
    private JsonObject movementValidation=new JsonObject();

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
    SpearSolverReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;System.out.println("SPEAR SOLVER PASS "+text);}
    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(directory.resolve("screenshots"));var environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        check(environment.get("monitorAdapter").getAsString().equals("\\\\.\\DISPLAY5"),"Actual test window is on DISPLAY5");
        world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String named=System.getProperty("fsrpg.spearSolverWorld","");
        check(named.startsWith("FSRPG Spear Solver Review ")&&world.getFileName().toString().equals(named),"Only explicitly named copied spear world is modified");
        check(Files.isRegularFile(world.resolve(".fsrpg-spear-solver-test-world")),"Copied world bears spear-only development marker");
        previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();previousFov=game.options.fov().get();
        previousPause=game.options.pauseOnLostFocus;previousHidden=game.options.hideGui;previousVsync=game.options.enableVsync().get();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.hideGui=true;game.options.framerateLimit().set(60);game.options.enableVsync().set(false);
        game.options.renderDistance().set(4);game.options.guiScale().set(2);game.options.fov().set(65);game.options.setCameraType(CameraType.FIRST_PERSON);game.resizeDisplay();
        game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();GLFW.glfwSetWindowTitle(game.getWindow().handle(),"FSRPG 长枪解算 · DISPLAY5");
        UUID player=game.player.getUUID();initialized=true;
        game.getSingleplayerServer().execute(()->{try {
            var p=game.getSingleplayerServer().getPlayerList().getPlayer(player);p.setGameMode(GameType.SPECTATOR);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
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
        if(!sweep)return new AttackAim(List.of(new AttackAim.Point(yaw,pitch,3)));
        return new AttackAim(List.of(new AttackAim.Point(yaw-35,Math.clamp(pitch-12,-80,80),3),new AttackAim.Point(yaw,pitch,3),new AttackAim.Point(yaw+35,Math.clamp(pitch+12,-80,80),3)));
    }
    private void update(Minecraft game,int t) {
        double yaw,pitch,velocityX=0,velocityZ=0;boolean sprint=false;
        if(scene==0) {yaw=170*Math.sin(t*Math.PI/100);pitch=78*Math.sin(t*Math.PI/70);}
        else if(scene==1) {yaw=105*Math.sin(t*Math.PI/90);pitch=58*Math.sin(t*Math.PI/80);}
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
        if(scene!=2)movementSegment="stationary";
        int cycle=t%50;boolean sweep=t/50%2==1;String action="spear_idle",phase="READY";int remaining=0,w=0,a=0,r=0;
        AttackAim aim=null;long seq=100000L+scene*1000+t/50;
        if(scene==1&&cycle<36) {
            w=sweep?6:5;a=sweep?10:4;r=sweep?12:9;int total=w+a+r;
            if(cycle<total) {
                action=sweep?"spear_sweep":"spear_thrust";
                if(cycle<w){phase="WINDUP";remaining=w-cycle;}else if(cycle<w+a){phase="ACTIVE";remaining=w+a-cycle;}else {phase="RECOVERY";remaining=total-cycle;}
                // A fixed path belongs to one attack, just as on a remote actor's actual server packets.
                double start=t-cycle;double attackYaw=new double[]{0,140,-140,0}[t/50%4];
                double attackPitch=58*Math.sin(start*Math.PI/80);
                aim=trajectory(attackYaw,attackPitch,sweep);
            }
        }
        // Movement commands apply in the next native entity tick. Start packets one
        // tick later so the final fixed-aim backward sample remains a READY sample.
        if(scene==2&&t>=176) {
            sweep=t>=191;cycle=t-(sweep?191:176);w=3;a=sweep?7:3;r=sweep?7:6;
            int total=w+a+r;double attackYaw=sweep?110:-95,attackPitch=sweep?60:-60;
            if(cycle<total) {
                action=sweep?"spear_sweep":"spear_thrust";seq=103000L+(sweep?1:0);
                if(cycle<w){phase="WINDUP";remaining=w-cycle;}else if(cycle<w+a){phase="ACTIVE";remaining=w+a-cycle;}else{phase="RECOVERY";remaining=total-cycle;}
                aim=trajectory(attackYaw,attackPitch,sweep);
            }
        }
        fixtureAction=action;fixturePhase=phase;
        for(int profile=0;profile<2;profile++) {
            actors[profile].drive(t,movementSegment,velocityX,velocityZ,sprint);
            place(actors[profile],scene==1?0:yaw,scene==1?0:pitch);send(actors[profile],action,phase,remaining,w,a,r,aim,seq);
        }
        // This observer is a spectator, so its angles cannot mutate either spear fixture.
        double centerX=(actors[0].getX()+actors[1].getX())*.5,centerZ=(actors[0].getZ()+actors[1].getZ())*.5;
        double oldX=(actors[0].xOld+actors[1].xOld)*.5,oldZ=(actors[0].zOld+actors[1].zOld)*.5;
        game.player.setPos(4+centerX,81.5,6+centerZ);game.player.xo=game.player.xOld=4+oldX;game.player.yo=game.player.yOld=81.5;game.player.zo=game.player.zOld=6+oldZ;
        game.player.setYRot(146.31f);game.player.yRotO=146.31f;game.player.setXRot(21);game.player.xRotO=21;
    }
    private JsonObject observe() {
        JsonObject stock=samplers[0].get(),slim=samplers[1].get();
        for(int profile=0;profile<2;profile++) {
            JsonObject value=profile==0?stock:slim;Fixture actor=actors[profile];
            value.addProperty("movementSegment",actor.appliedSegment);value.addProperty("movementScriptTick",actor.appliedTick);
            value.addProperty("commandedMovementSegment",movementSegment);
            value.add("fixtureVelocityMetersPerTick",RpgConfig.JSON.toJsonTree(new double[]{actor.appliedVelocityX,actor.appliedVelocityZ}));
            value.addProperty("constantAimMovement",scene==2&&actor.appliedTick>=20&&actor.appliedTick<175);
        }
        stock.add("slimActor",slim);stock.addProperty("fixtureAction",fixtureAction);stock.addProperty("fixturePhase",fixturePhase);stock.addProperty("fixtureTick",elapsed);
        try{verify(stock,"stock");verify(slim,"slim");}
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
    }
    private Object diagnosticField(Class<?> type,Object instance,String name)throws ReflectiveOperationException {
        var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    private JsonObject diagnosticMatrices(Map<String,Matrix4f> matrices) {
        var result=new JsonObject();if(matrices!=null)matrices.forEach((bone,matrix)->result.add(bone,RpgConfig.JSON.toJsonTree(matrix.get(new float[16]))));return result;
    }
    private Vector3f vector(JsonObject object,String key){var a=object.getAsJsonArray(key);return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    private void verify(JsonObject observation,String profile) {
        if(!observation.has("rightPalmCm"))throw new AssertionError("Missing live spear runtime frame "+profile);
        if(!RenderObservations.stableLowerSupport(observation))throw new AssertionError("Unstable actual spear lower-body support "+profile);
        var rear=vector(observation,"rightPalmCm");var front=vector(observation,"leftPalmCm");var axis=vector(observation,"rightBladeAxis");
        double grip=Math.abs(rear.distance(front)-58),alignment=new Vector3f(front).sub(rear).cross(axis).length();
        maximumGripError=Math.max(maximumGripError,grip);maximumAxisError=Math.max(maximumAxisError,alignment);
        if(grip>.03||alignment>.03)throw new AssertionError("Closed two-hand grip drift "+profile+" spacing="+grip+" axis="+alignment);
        for(String side:List.of("left","right")) {
            double flex=observation.get(side+"WristFlexionDegrees").getAsDouble();maximumWristFlex=Math.max(maximumWristFlex,flex);
            if(flex>.1)throw new AssertionError("Bent spear wrist "+profile+" "+side+" "+flex);
        }
        JsonObject rig=observation.getAsJsonObject("rig");
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
            if(elapsed==10) {
                samplers[0]=RenderObservations.freshSampler(actors[0]);samplers[1]=RenderObservations.freshSampler(actors[1]);
                movies.start(CLIPS.get(scene),"live RemotePlayer authoritative animation packets; actual PlayerAnimations solver, official skin and spear renderer",this::observe);captureStarted=true;
            }
            if(elapsed==55||elapsed==135)screenshots.add(CLIPS.get(scene)+"-"+elapsed);
            if(++elapsed>=210){movies.stop();captureStarted=false;elapsed=0;scene++;previousJoints.clear();}
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
        if(!movies.finish())return;var summary=movies.summary();
        for(var value:summary.getAsJsonArray("clips")) {var clip=value.getAsJsonObject();check(clip.get("sourceFps").getAsDouble()>=30,"Actual rendered capture >=30fps "+clip.get("id").getAsString());check(clip.get("distinctSamplesWithinGameTick").getAsInt()>0,"Live sub-tick spear samples "+clip.get("id").getAsString());}
        check(OfficialHeldItems.submissions()>0&&OfficialHeldItems.lastItemId().equals("minecraft:diamond_spear"),"Actual vanilla spear ItemStack reached official renderer");
        validateMovement(summary);
        report(true,"");restore(game);done=true;game.stop();
    }
    private void validateMovement(JsonObject capture) {
        JsonObject clip=null;for(var value:capture.getAsJsonArray("clips"))if(value.getAsJsonObject().get("id").getAsString().equals("spear-moving-extremes"))clip=value.getAsJsonObject();
        if(clip==null)throw new AssertionError("Missing spear movement clip");
        for(String profile:List.of("stock","slim")) {
            Map<String,List<JsonObject>> segments=new HashMap<>();
            for(var frame:clip.getAsJsonArray("frames")) {
                JsonObject observation=frame.getAsJsonObject().getAsJsonObject("observation");if(profile.equals("slim"))observation=observation.getAsJsonObject("slimActor");
                segments.computeIfAbsent(observation.get("movementSegment").getAsString(),unused->new ArrayList<>()).add(observation);
            }
            JsonObject actorResult=new JsonObject();movementValidation.add(profile,actorResult);
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
        movementValidation.addProperty("passed",true);
    }
    private Quaternionf rotation(JsonObject sample,String joint) {
        var matrix=sample.getAsJsonObject("rig").getAsJsonArray(joint);var values=new float[16];for(int i=0;i<16;i++)values[i]=matrix.get(i).getAsFloat();
        return new Matrix4f().set(values).getUnnormalizedRotation(new Quaternionf()).normalize();
    }
    private double angle(Quaternionf first,Quaternionf second){return Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(first.dot(second)),0,1)));}
    private void report(boolean passed,String error)throws Exception {
        Files.createDirectories(directory);var report=new JsonObject();report.addProperty("passed",passed);report.addProperty("error",error);report.addProperty("scope","spear solver only; live stock/slim RemotePlayer runtime fixtures, no detached editor preview");
        report.addProperty("worldCopy",world==null?"":world.toString());report.addProperty("checks",checks);report.addProperty("sampledFrames",sampledFrames);report.addProperty("maximumGripSpacingErrorCm",maximumGripError);report.addProperty("maximumGripAxisErrorCm",maximumAxisError);report.addProperty("maximumWristFlexionDegrees",maximumWristFlex);
        report.add("peakAdjacentRenderedJointStepsDegrees",RpgConfig.JSON.toJsonTree(peakSteps));report.add("images",images);RpgSmoke.windowEnvironment(report);
        report.add("movementValidation",movementValidation);
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
        RpgConfig.atomicWrite(directory.resolve("spear-solver-smoke.json"),RpgConfig.JSON.toJson(report));
    }
    private void restore(Minecraft game) {
        KeyMapping.releaseAll();if(!initialized)return;game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.fov().set(previousFov);game.options.pauseOnLostFocus=previousPause;game.options.hideGui=previousHidden;game.options.enableVsync().set(previousVsync);game.options.setCameraType(previousCamera);
        for(var actor:actors)if(actor!=null)actor.discard();
    }
}

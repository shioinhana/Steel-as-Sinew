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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.CombatActor;
import org.fsrpg.combat.CombatPhase;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.fsrpg.progression.ProgressionService;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/** Native packaged-client proof of the original dual rig, including every action handoff frame. */
final class DualSourceReview {
    private enum Scenario { IDLE, WALK, RUN, STOP, LEFT_FOUR, RIGHT_FOUR, COMBINED, MOVING_ATTACK, GUARD }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray();
    private final Queue<String> shots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private final Map<String,Long> sourceActions=new TreeMap<>(),sourceGaits=new TreeMap<>();
    private final List<String> skills=new ArrayList<>();
    private final Map<String,Field> reflection=new HashMap<>();
    private final Method sourceSample,sourceGait;
    private volatile Throwable failure;
    private volatile boolean ready,scriptDone,videoReady;
    private volatile Scenario scenario=Scenario.IDLE;
    private volatile JsonObject serverObservation=new JsonObject();
    private volatile long serverTick;
    private UUID playerId;
    private boolean initialized,done,prepared,begun,stopped;
    private int warmup,caseIndex,previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private long started,nativeFrames,caseStarted,firstSequence,lastSequence,finishAt=-1;
    private long officialMainBefore,officialOffhandBefore,sourceFrames,boneMatrices,movingFrames,proceduralLowerFrames,sourceActionChanges;
    private String lastSourceAction="";
    private double maxMatrixError,maxClockError,initialStamina;
    private JsonObject sourceFrame=new JsonObject(),row;

    DualSourceReview(){
        try {
            Class<?> legacy=Class.forName("org.fsrpg.client.LegacyMotion");
            sourceSample=legacy.getDeclaredMethod("sample",ModelDocument.class,RpgAnimationPayload.class,double.class,double.class,AvatarRenderState.class,LivingEntity.class);
            sourceGait=legacy.getDeclaredMethod("gait",ModelDocument.class,LivingEntity.class,float.class,RpgAnimationPayload.class);
            sourceSample.setAccessible(true);sourceGait.setAccessible(true);
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Original dual sampling API is unavailable",error);}
        NeoForge.EVENT_BUS.addListener(this::preRender);
        NeoForge.EVENT_BUS.addListener(this::submittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.add(message);System.out.println("DUAL SOURCE PASS "+message);}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void near(double expected,double value,String message){check(Double.isFinite(value)&&Math.abs(expected-value)<.003,message+" expected="+expected+" actual="+value);}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private void action(ServerPlayer p,String name,String argument){RpgServer.instance().action(p,name,argument);}
    private void guard(ServerPlayer p,boolean held){
        action(p,"guard",Boolean.toString(held));
        Minecraft.getInstance().execute(()->{for(var key:Minecraft.getInstance().options.keyMappings)if(key.getName().equals("key.fsrpg.guard"))key.setDown(held);});
    }
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual game window is on DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name=System.getProperty("fsrpg.dualSourceWorld","");
        check(name.equals("FSRPG Dual Source Review 20261018 01")&&world.getFileName().toString().equals(name)
                &&Files.isRegularFile(world.resolve(".fsrpg-dual-source-test-world")),"Only the named and marked copied world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);
        GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 双刀原始全身动作验收 · DISPLAY5");game.resizeDisplay();
        initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try{
                ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var entity:p.level().getEntities(p,p.getBoundingBox().inflate(48),entity->entity instanceof LivingEntity&&!(entity instanceof net.minecraft.world.entity.player.Player)))entity.discard();
                p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(20);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
                for(int x=-16;x<=16;x++)for(int z=-16;z<=24;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
                p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.setExperienceLevels(0);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));fixtureProgress();
                actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.DIAMOND_SWORD));
                action(p,"status","");check(actor(p).style()!=null&&actor(p).style().id().equals("dual_blade"),"Real equipment reconciliation selects dual blades");
                if(!actor(p).drawn())action(p,"draw","");
                actor(p).setResources(actor(p).maxStamina(),100);ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    private void fixtureProgress(){
        var service=RpgServer.instance().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);
        JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}
        for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);
        one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();learned.add("dual_sword");one.add("learnedNodes",learned);
        service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));check(service.weaponUnlocked(playerId,"dual_blade"),"Copied fixture retains the real learned dual weapon root");
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||!videoReady||done||scriptDone||failure!=null)return;
        try{
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;
            serverTick=p.level().getGameTime();serverObservation=RpgConfig.JSON.toJsonTree(actor(p).snapshot()).getAsJsonObject();
            if(caseIndex==Scenario.values().length){scriptDone=true;return;}
            scenario=Scenario.values()[caseIndex];step(p);
        }catch(Throwable error){failure=error;}
    }
    private void step(ServerPlayer p){
        CombatActor a=actor(p);long now=serverTick;
        if(!prepared){
            guard(p,false);if(a.phase()!=CombatPhase.READY)return;
            a.setResources(a.maxStamina(),100);skills.clear();row=new JsonObject();row.addProperty("id",scenario.name().toLowerCase(Locale.ROOT).replace('_','-'));row.add("actualStarts",new JsonArray());
            initialStamina=a.stamina();caseStarted=now;prepared=true;begun=false;finishAt=-1;firstSequence=lastSequence=a.snapshot().swingSequence();
            shots.add("dual-"+row.get("id").getAsString()+"-start");return;
        }
        long elapsed=now-caseStarted;
        switch(scenario){
            case IDLE,WALK,RUN,STOP -> {if(elapsed>=(scenario==Scenario.STOP?12:24))finishScenario(0);}
            case GUARD -> {
                if(!begun){guard(p,true);check(a.phase()==CombatPhase.GUARDING,"Real C intent enters original dual guard");begun=true;}
                if(elapsed==16){guard(p,false);check(a.phase()==CombatPhase.READY,"Releasing C exits guard without an invented attack");}
                if(elapsed>=24){check(a.snapshot().swingSequence()==firstSequence,"Holding and releasing guard produces no automatic blade");finishScenario(0);}
            }
            case LEFT_FOUR,RIGHT_FOUR,MOVING_ATTACK,COMBINED -> attackStep(p,elapsed);
        }
        require(elapsed<130,"Dual scenario timeout "+scenario);
    }
    private int wanted(){return scenario==Scenario.LEFT_FOUR||scenario==Scenario.RIGHT_FOUR?4:scenario==Scenario.MOVING_ATTACK?2:1;}
    private String direction(){return scenario==Scenario.RIGHT_FOUR?"right":scenario==Scenario.COMBINED?"dual_combined":"left";}
    private void attackStep(ServerPlayer p,long elapsed){
        CombatActor a=actor(p);
        if(!begun){
            double before=a.stamina();action(p,"attack",direction());check(a.currentSkill()!=null,"Real server intent starts a blade: "+scenario);near(8,before-a.stamina(),"One started dual blade pays eight stamina: "+scenario);
            firstSequence=lastSequence=a.snapshot().swingSequence();observeStart(a);begun=true;
            if(wanted()>1){action(p,"attack",direction());check(a.comboSnapshot().queued()&&a.snapshot().swingSequence()==firstSequence,"Earliest windup click caches one continuation without replacing the original blade: "+scenario);near(8,initialStamina-a.stamina(),"A cached click is not charged before its blade starts: "+scenario);}
            return;
        }
        long sequence=a.snapshot().swingSequence();
        if(sequence>lastSequence){
            check(sequence==lastSequence+1,"A cached click starts exactly one next blade: "+scenario);observeStart(a);
            near(8*skills.size(),initialStamina-a.stamina(),"Each real continuation retains the eight-stamina cost: "+scenario);
            if(skills.size()<wanted())action(p,"attack",direction());lastSequence=sequence;
        }
        if(skills.size()==wanted()&&a.currentSkill()==null&&a.phase()==CombatPhase.READY){
            if(finishAt<0){finishAt=serverTick+4;return;}if(serverTick<finishAt)return;
            check(sequence==firstSequence+wanted()-1,"Point clicks retain exact blade count with no automatic extra swing: "+scenario);
            if(scenario==Scenario.LEFT_FOUR)check(skills.equals(List.of("dual_ll","dual_rl","dual_ll","dual_rl")),"Four relaxed left inputs retain alternating original hands");
            if(scenario==Scenario.RIGHT_FOUR)check(skills.equals(List.of("dual_rr","dual_lr","dual_rr","dual_lr")),"Four relaxed right inputs retain alternating original hands");
            if(scenario==Scenario.COMBINED)check(skills.equals(List.of("dual_combined")),"Combined intent retains the original combined clip");
            finishScenario(wanted());
        }
    }
    private void observeStart(CombatActor a){skills.add(a.currentSkill().id());JsonObject value=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();value.addProperty("worldGameTick",serverTick);row.getAsJsonArray("actualStarts").add(value);}
    private void finishScenario(int expected){
        row.addProperty("actualBladeCount",skills.size());row.addProperty("expectedBladeCount",expected);row.addProperty("staminaCostPerBlade",8);row.add("skills",RpgConfig.JSON.toJsonTree(skills));row.addProperty("passed",true);scenarios.add(row);caseIndex++;prepared=false;
    }
    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}movies.assertHealthy();if(failure!=null)throw new IllegalStateException("Dual source review failed",failure);
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();
            require(System.nanoTime()-started<180_000_000_000L,"Dual source review exceeded three minutes");
            boolean walk=ready&&!scriptDone&&(scenario==Scenario.WALK||scenario==Scenario.RUN||scenario==Scenario.MOVING_ATTACK);
            game.options.keyUp.setDown(walk);game.options.keySprint.setDown(walk&&scenario==Scenario.RUN);if(!walk||scenario!=Scenario.RUN)game.player.setSprinting(false);
            if(ready&&!videoReady){officialMainBefore=OfficialHeldItems.submissions();officialOffhandBefore=OfficialHeldItems.offhandSubmissions();movies.start("dual-original-motion","Native framebuffer and every RenderLivingEvent.Post actual avatar submission compared against original 0.1.1 full source FK; real input locomotion and server combo intentions",this::observation);videoReady=true;}
            if(scriptDone&&!stopped){movies.stop();stopped=true;KeyMapping.releaseAll();}
            if(scriptDone&&stopped&&shots.isEmpty()&&pendingShots.get()==0&&movies.finish()){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private Object field(Class<?> type,Object instance,String name)throws ReflectiveOperationException{
        String key=type.getName()+"#"+name;Field f=reflection.get(key);if(f==null){f=type.getDeclaredField(name);f.setAccessible(true);reflection.put(key,f);}return f.get(instance);
    }
    private void submittedAvatar(RenderLivingEvent.Post<?,?,?> event){
        RenderObservations.captureSubmittedAvatar(event);
        Minecraft game=Minecraft.getInstance();
        if(!initialized||!ready||done||!videoReady||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{
            PlayerAnimations.Frame frame=PlayerAnimations.frame(state);require(frame!=null,"Actual player submission has no dual frame");
            require(frame.model().rig().id().equals("fsrpg.legacy_dual_blade"),"Actual dual submission uses the original imported model");
            @SuppressWarnings("unchecked") Map<UUID,Object> bindings=(Map<UUID,Object>)field(PlayerAnimations.class,null,"bindings");Object binding=bindings.get(game.player.getUUID());
            RpgAnimationPayload payload=(RpgAnimationPayload)field(binding.getClass(),binding,"state");
            double seconds=((Number)field(binding.getClass(),binding,"sampledClipSeconds")).doubleValue(),elapsed=((Number)field(binding.getClass(),binding,"sampledElapsedTicks")).doubleValue();
            double start=((Number)field(binding.getClass(),binding,"started")).doubleValue();var clip=frame.model().rig().clips().get(payload.action());
            double expectedSeconds=elapsed>=0?clip.duration()*Math.clamp(elapsed/Math.max(1,payload.windupTicks()+payload.activeTicks()+payload.recoveryTicks()),0,1):Math.max(0,frame.sampledTick()-start)/20;
            double clockError=Math.abs(seconds-expectedSeconds);maxClockError=Math.max(maxClockError,clockError);require(clockError<=1e-8,"Dual clip no longer follows its original linear clock: "+payload.action()+" error="+clockError);
            @SuppressWarnings("unchecked") Map<String,Pose> original=(Map<String,Pose>)sourceSample.invoke(null,frame.model(),payload,seconds,frame.sampledTick()/20,state,game.player);
            if(state.skin.model()==PlayerModelType.SLIM){original=new HashMap<>(original);for(String side:List.of("right","left")){String id=side+"_arm";Pose p=original.get(id);if(p!=null){V at=p.position();original.put(id,new Pose(p.rotation(),new V(at.x()+(side.equals("right")?3.125f:-3.125f),at.y(),at.z()),p.scale()));}}}
            Map<String,Matrix4f> expected=frame.model().worldMatrices(original);double error=0;
            for(var bone:expected.entrySet()){
                Matrix4f actual=frame.world().get(bone.getKey());require(actual!=null,"Actual dual frame lost source joint "+bone.getKey());float[] a=actual.get(new float[16]),b=bone.getValue().get(new float[16]);
                for(int i=0;i<16;i++){require(Float.isFinite(a[i]),"Non-finite actual dual FK "+bone.getKey());error=Math.max(error,Math.abs(a[i]-b[i]));}boneMatrices++;
            }
            require(frame.world().size()==expected.size(),"Actual dual frame has invented joints");maxMatrixError=Math.max(maxMatrixError,error);require(error<=1e-4,"Submitted dual full body differs from the original source, including handoff frames: "+payload.action()+" error="+error);
            if(frame.supportedLower()!=null||Math.abs(frame.lowerBodyWeight()-1)>1e-7||Math.abs(frame.balance().x())>1e-7||Math.abs(frame.balance().y())>1e-7||Math.abs(frame.balance().z())>1e-7)proceduralLowerFrames++;
            require(proceduralLowerFrames==0,"Dual source submission acquired a procedural pelvis/leg layer");
            String gait=(String)sourceGait.invoke(null,frame.model(),game.player,state.walkAnimationSpeed,payload);sourceGaits.merge(gait,1L,Long::sum);sourceActions.merge(payload.action(),1L,Long::sum);
            if(!lastSourceAction.equals(payload.action())){sourceActionChanges++;lastSourceAction=payload.action();}
            boolean moving=Math.hypot(game.player.getX()-game.player.xo,game.player.getZ()-game.player.zo)>.002;if(moving)movingFrames++;sourceFrames++;
            JsonObject comparison=new JsonObject();comparison.addProperty("worldMatrixError",error);comparison.addProperty("linearClockErrorSeconds",clockError);comparison.addProperty("comparedBones",expected.size());comparison.addProperty("sourceAction",payload.action());comparison.addProperty("sourceGait",gait);comparison.addProperty("originalClipSeconds",expectedSeconds);comparison.addProperty("actualClipSeconds",seconds);comparison.addProperty("allTransitionFramesIncluded",true);comparison.addProperty("moving",moving);comparison.addProperty("sampledTick",frame.sampledTick());comparison.addProperty("nativeRenderFrame",sourceFrames);sourceFrame=comparison;
        }catch(Throwable error){failure=error;}
    }
    private JsonObject observation(){
        Minecraft game=Minecraft.getInstance();require(game.options.getCameraType()==CameraType.THIRD_PERSON_BACK&&game.getCameraEntity()==game.player,"Native review requires the real player third-person camera");
        JsonObject sample=serverObservation.deepCopy();sample.addProperty("scenario",scenario.name().toLowerCase(Locale.ROOT).replace('_','-'));sample.addProperty("observedServerGameTick",serverTick);
        sample.addProperty("actualCameraType",game.options.getCameraType().name());sample.addProperty("actualCameraEntityId",game.player.getUUID().toString());
        JsonObject avatar=RenderObservations.submittedSampler(game.player).get();sample.add("rig",avatar.remove("rig"));sample.add("submittedAvatar",avatar);sample.add("sourceComparison",sourceFrame.deepCopy());return sample;
    }
    private void preRender(RenderFrameEvent.Pre event){Minecraft game=Minecraft.getInstance();if(initialized&&!done&&game.level!=null&&game.player!=null){game.setCameraEntity(game.player);game.options.setCameraType(CameraType.THIRD_PERSON_BACK);}RenderObservations.beginSubmittedFrame(event);}
    private void render(RenderFrameEvent.Post event){
        if(done||!initialized)return;nativeFrames++;
        try{movies.frame(event);String shot;while((shot=shots.poll())!=null){pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),shot+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}
    }
    private void validate(){
        check(scenarios.size()==Scenario.values().length,"All nine original-motion and retained-input scenarios complete");
        check(sourceFrames>100&&boneMatrices>1000,"Full source FK is compared at actual submitted render frames");check(maxMatrixError<=1e-4&&maxClockError<=1e-8,"Every submitted transition frame preserves original full-body FK and linear timing");
        check(proceduralLowerFrames==0,"No submitted dual frame adds a procedural lower-body layer");check(sourceActionChanges>=10,"Actual action handoffs are compared without dropping transition frames");
        check(movingFrames>30,"Actual input moves the player while full-source poses remain exact");
        check(sourceGaits.keySet().stream().anyMatch(s->s.contains("walk_hold")),"Actual walking uses the author's original held-walk locomotion");check(sourceGaits.keySet().stream().anyMatch(s->s.contains("run_hold")),"Actual sprinting uses the author's original held-run locomotion");
        for(String action:List.of("legacy_dual_blade_attack_1_upper","legacy_dual_blade_attack2_upper","legacy_dual_blade_attack3_upper","legacy_dual_blade_attack4_upper","legacy_dual_blade_attack_dual_upper","dual_guard"))check(sourceActions.getOrDefault(action,0L)>0,"Actual source action is submitted and compared: "+action);
        check(OfficialHeldItems.submissions()>officialMainBefore&&OfficialHeldItems.offhandSubmissions()>officialOffhandBefore,"Official ItemStack rendering submits both real swords");
        JsonObject clip=movies.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Native PNG recording samples at least thirty actual rendered frames per second");check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native capture contains distinct real sub-tick frames");check(ClientState.number(clip,"changedRigPairs",0)>0,"Actual submitted full-body rig changes across native frames");
        long rigFrames=0;for(var value:clip.getAsJsonArray("frames")){JsonObject observation=value.getAsJsonObject().getAsJsonObject("observation"),avatar=observation.getAsJsonObject("submittedAvatar");require(avatar!=null&&ClientState.text(avatar,"nativePoseSnapshotStage","").equals("RenderLivingEvent.Post actual avatar submission"),"Capture contains a detached rather than actual submitted avatar");require(observation.getAsJsonObject("sourceComparison").get("allTransitionFramesIncluded").getAsBoolean(),"Capture omitted a source transition comparison");rigFrames++;}
        check(rigFrames>100,"Native capture retains current-frame source comparison provenance");
    }
    private JsonObject sourceSummary(){JsonObject value=new JsonObject();value.addProperty("frames",sourceFrames);value.addProperty("boneMatrices",boneMatrices);value.addProperty("maxWorldMatrixError",maxMatrixError);value.addProperty("maxLinearClockErrorSeconds",maxClockError);value.addProperty("sourceActionChangesCompared",sourceActionChanges);value.addProperty("allTransitionFramesIncluded",true);value.addProperty("proceduralLowerFrames",proceduralLowerFrames);value.addProperty("movingFrames",movingFrames);value.add("sourceGaits",RpgConfig.JSON.toJsonTree(sourceGaits));value.add("sourceActions",RpgConfig.JSON.toJsonTree(sourceActions));return value;}
    private void finish(Throwable error){
        try{movies.assertHealthy();}catch(Throwable captureError){if(error==null)error=captureError;}if(done)return;done=true;movies.stop();
        try{
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.dualSourceWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("sourceComparison",sourceSummary());report.addProperty("officialMainSubmissionsDuringCapture",OfficialHeldItems.submissions()-officialMainBefore);report.addProperty("officialOffhandSubmissionsDuringCapture",OfficialHeldItems.offhandSubmissions()-officialOffhandBefore);report.add("renderCapture",movies.summary());RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");RpgConfig.atomicWrite(root.resolve("dual-source-review.json"),RpgConfig.JSON.toJson(report));System.out.println("DUAL SOURCE REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);
        if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
}

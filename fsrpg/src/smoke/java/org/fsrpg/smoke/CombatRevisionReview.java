package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.fsrpg.progression.ProgressionService;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Packaged-client proof of long-running source gait, bounded live turns, held spear contacts and resource accounting. */
final class CombatRevisionReview {
    private enum Scenario { DUAL_IDLE, DUAL_WALK, DUAL_RUN, DUAL_ATTACK_RETURN, SPEAR_TURN, RAPIER_TURN,
        SPEAR_HOLD_SWEEP, SPEAR_TAP, SPEAR_LEASE, SPEAR_CANCEL, STATIC_REGEN, COMBAT_REGEN, MISS_COST, CONTACT_COST }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray scenarios=new JsonArray(),serverTrace=new JsonArray();
    private final Map<UUID,Integer> targetContacts=new ConcurrentHashMap<>();
    private final List<Mob> targets=new ArrayList<>();
    private final Map<String,Field> fields=new HashMap<>();
    private final Map<Scenario,Visual> visuals=new EnumMap<>(Scenario.class);
    private final Queue<String> screenshots=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingShots=new java.util.concurrent.atomic.AtomicInteger(),capturedShots=new java.util.concurrent.atomic.AtomicInteger();
    private volatile Throwable failure;
    private volatile Scenario scenario=Scenario.DUAL_IDLE;
    private volatile long elapsed,observedServerTick;
    private volatile float forcedHeading;
    private volatile boolean ready,scriptDone,videoReady,turning;
    private volatile JsonObject serverObservation=new JsonObject();
    private boolean initialized,done,prepared,begun,stopped;
    private int warmup,index,previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private UUID playerId;
    private long started,caseStarted,actorStarted,sequence,inputId=1000,nativeFrames,officialMainBefore,officialOffBefore;
    private double staminaBefore,expectedRegen,startX,startZ,previousYaw;
    private JsonObject row;

    private static final class Visual {
        long frames,changedLegPairs,sourceIdleFrames,heldFrames,steppingFrames,sameFrameChecks,hardTurns,supportedTurnFrames;
        double firstTick=Double.NaN,lastTick,maxTwist,maxLift,maxIdleError,maxHeldClockError;
        Matrix4f previousLeg;
    }
    CombatRevisionReview(){
        NeoForge.EVENT_BUS.addListener(this::preRender);NeoForge.EVENT_BUS.addListener(this::submitted);
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::incoming);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.add(message);System.out.println("COMBAT REVISION PASS "+message);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void near(double expected,double actual,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<.015,message+" expected="+expected+" actual="+actual);}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private RpgServer rpg(){return RpgServer.instance();}
    private void action(ServerPlayer p,String name,String argument){rpg().action(p,name,argument);}
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual game window is on DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();String name=System.getProperty("fsrpg.combatRevisionWorld","");
        check(name.equals("FSRPG Combat Revision Review 20261018 01")&&world.getFileName().toString().equals(name)
                &&Files.isRegularFile(world.resolve(".fsrpg-combat-revision-test-world")),"Only the named and marked copied world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        // Cold JSON/mesh loading is separate from continuous motion sampling. Warm the
        // same production asset cache before recording; every recorded frame remains native.
        var assets=Class.forName("org.fsrpg.client.AnimationAssets");var load=assets.getDeclaredMethod("load",String.class);load.setAccessible(true);
        for(String model:List.of("legacy_dual_blade.fsvoxel","legacy_rapier.fsvoxel","spear.fsvoxel"))load.invoke(null,model);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);
        GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 转步、长枪保持与体力验收 · DISPLAY5");game.resizeDisplay();initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try{
                ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(64),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
                p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);p.getFoodData().setFoodLevel(20);p.setExperienceLevels(0);p.level().setDayTime(6000);p.level().setWeatherParameters(6000,0,false,false);
                for(int x=-24;x<=24;x++)for(int z=-24;z<=64;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
                p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                fixtureProgress();equip(p,"dual_blade");ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    private void fixtureProgress(){
        var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);
        JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject(),one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}
        for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);
        one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);JsonArray learned=new JsonArray();learned.add("rapier");learned.add("dual_sword");one.add("learnedNodes",learned);
        service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
    }
    private void equip(ServerPlayer p,String style){
        action(p,"guard","false");actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(style.equals("spear")?Items.DIAMOND_SPEAR:Items.DIAMOND_SWORD));
        p.setItemSlot(EquipmentSlot.OFFHAND,style.equals("dual_blade")?new ItemStack(Items.DIAMOND_SWORD):ItemStack.EMPTY);
        check(actor(p).setStyle(rpg().config().current().styles().get(style))==ActionResult.ACCEPTED,"Real fixture selects "+style);
        action(p,"status","");if(!actor(p).drawn())action(p,"draw","");actor(p).setResources(40,100);p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);forcedHeading=0;previousYaw=0;
    }
    private String style(Scenario value){return value.name().startsWith("DUAL")?"dual_blade":value.name().startsWith("SPEAR")?"spear":"rapier";}
    private void clearTargets(){for(Mob target:targets)target.discard();targets.clear();targetContacts.clear();}
    private Mob spawn(ServerPlayer p,double yaw,double radius){
        Mob mob=(Mob)EntityType.HUSK.create(p.level(),EntitySpawnReason.COMMAND);require(mob!=null,"Cannot create real target");
        double a=Math.toRadians(yaw);mob.setPos(p.getX()-Math.sin(a)*radius,80,p.getZ()+Math.cos(a)*radius);mob.setNoAi(true);mob.setNoGravity(true);mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);mob.setHealth(200);if(mob.getAttribute(Attributes.ARMOR)!=null)mob.getAttribute(Attributes.ARMOR).setBaseValue(0);
        if(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)!=null)mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);p.level().addFreshEntity(mob);targets.add(mob);targetContacts.put(mob.getUUID(),0);return mob;
    }
    private void incoming(LivingIncomingDamageEvent event){if(!done&&event.getSource().getEntity() instanceof ServerPlayer p&&p.getUUID().equals(playerId)&&targetContacts.containsKey(event.getEntity().getUUID()))targetContacts.merge(event.getEntity().getUUID(),1,Integer::sum);}
    private void spear(ServerPlayer p,String verb,double from,double to){
        var points=verb.equals("spearPress")?List.of(new AttackAim.Point(to,0,8)):List.of(new AttackAim.Point(from,0,8),new AttackAim.Point(to,0,8));
        JsonObject request=new JsonObject();request.addProperty("input",inputId);request.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(points)));action(p,verb,request.toString());previousYaw=to;
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||done||scriptDone||failure!=null||!videoReady)return;
        try{
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;observedServerTick=p.level().getGameTime();
            if(index>=Scenario.values().length){scriptDone=true;return;}scenario=Scenario.values()[index];CombatActor a=actor(p);
            if(!prepared){clearTargets();equip(p,style(scenario));prepared=true;begun=false;row=new JsonObject();row.addProperty("id",scenario.name().toLowerCase(Locale.ROOT).replace('_','-'));return;}
            if(!begun){
                if(a.phase()!=CombatPhase.READY)return;if((scenario==Scenario.STATIC_REGEN||scenario==Scenario.MISS_COST)&&a.staminaCombatState())return;
                begun=true;caseStarted=observedServerTick;actorStarted=a.snapshot().serverTick();sequence=a.snapshot().swingSequence();staminaBefore=a.stamina();startX=p.getX();startZ=p.getZ();expectedRegen=0;elapsed=0;
                if(scenario==Scenario.DUAL_ATTACK_RETURN)action(p,"attack","left");
                if(scenario==Scenario.SPEAR_HOLD_SWEEP){spawn(p,-25,3.3);spawn(p,25,3.3);inputId++;spear(p,"spearPress",-35,-35);sequence=a.snapshot().swingSequence();check(a.currentSkill()!=null&&a.spearAttackHeld(),"Press immediately starts one authoritative held spear thrust");near(10,staminaBefore-a.stamina(),"Held spear reserves exactly one ten-stamina cost");row.addProperty("initialReservedCost",staminaBefore-a.stamina());}
                if(scenario==Scenario.SPEAR_TAP||scenario==Scenario.SPEAR_LEASE||scenario==Scenario.SPEAR_CANCEL){inputId++;spear(p,"spearPress",0,0);sequence=a.snapshot().swingSequence();if(scenario==Scenario.SPEAR_TAP)spear(p,"spearRelease",0,0);check(a.currentSkill()!=null,"Point/lease/cancel input starts a real spear attack: "+scenario);}
                if(scenario==Scenario.STATIC_REGEN||scenario==Scenario.COMBAT_REGEN){a.setResources(20,100);staminaBefore=20;if(scenario==Scenario.COMBAT_REGEN)a.markCombatActivity();}
                if(scenario==Scenario.MISS_COST||scenario==Scenario.CONTACT_COST){a.setResources(20,100);staminaBefore=20;if(scenario==Scenario.CONTACT_COST)spawn(p,0,2.5);action(p,"attack","left");sequence=a.snapshot().swingSequence();check(a.currentSkill()!=null,"Real resource scenario starts a rapier blade: "+scenario);near(5,staminaBefore-a.stamina(),"One sword blade reserves the full five-stamina cost before contact");}
                return;
            }
            elapsed=observedServerTick-caseStarted;for(Mob target:targets){target.invulnerableTime=0;target.setDeltaMovement(Vec3.ZERO);}
            JsonObject observation=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();observation.addProperty("scenario",scenario.name());observation.addProperty("elapsedGameTicks",elapsed);observation.addProperty("heldSpear",a.spearAttackHeld());observation.addProperty("regenPerSecond",a.currentStaminaRegenPerSecond());
            serverTrace.add(observation.deepCopy());serverObservation=observation;
            switch(scenario){
                case DUAL_IDLE -> {if(elapsed>=40)finishScenario(p);}
                case DUAL_WALK -> {if(elapsed>=68){check(Math.hypot(p.getX()-startX,p.getZ()-startZ)>.8,"Actual walking inputs move the real dual player");finishScenario(p);}}
                case DUAL_RUN -> {if(elapsed>=42){check(Math.hypot(p.getX()-startX,p.getZ()-startZ)>1,"Actual running inputs move the real dual player");finishScenario(p);}}
                case DUAL_ATTACK_RETURN -> {if(elapsed>=44){check(a.phase()==CombatPhase.READY&&a.currentSkill()==null,"A dual blade returns to READY rather than leaving an attack overlay");check(a.snapshot().swingSequence()==sequence+1,"One dual click produces exactly one blade before natural idle");finishScenario(p);}}
                case SPEAR_TURN,RAPIER_TURN -> {
                    forcedHeading=elapsed<10?0:elapsed<18?180:(float)AttackAim.wrap(180+(elapsed-18)*12);turning=true;
                    // Rotation alone must not reset real grounded physics every tick. A
                    // position-teleport here made almost all native turn frames airborne.
                    p.setYRot(forcedHeading);p.setXRot(0);p.yBodyRot=p.yBodyRotO=forcedHeading;p.setYHeadRot(forcedHeading);
                    if(elapsed>=48){turning=false;finishScenario(p);}
                }
                case SPEAR_HOLD_SWEEP -> {
                    if(elapsed<=38){double yaw=elapsed<=8?-35:elapsed<=28?-35+(elapsed-8)*3.5:35;spear(p,"spearMove",previousYaw,yaw);check(a.currentSkill()!=null&&a.spearAttackHeld(),"A held spear stays active on its original paid swing at tick "+elapsed);check(a.snapshot().swingSequence()==sequence,"Held dragging cannot create repeated new attacks at tick "+elapsed);}
                    if(elapsed==39){check(targets.stream().allMatch(t->targetContacts.get(t.getUUID())==1),"Continuous held swept region contacts both real targets exactly once despite cleared vanilla invulnerability");row.add("targetContacts",RpgConfig.JSON.toJsonTree(targetContacts));row.addProperty("heldTicks",elapsed);row.addProperty("additionalSwingCount",a.snapshot().swingSequence()-sequence);spear(p,"spearRelease",previousYaw,previousYaw);screenshots.add("spear-held-two-targets");}
                    if(elapsed>=46){check(!a.spearAttackHeld()&&a.currentSkill()==null&&a.phase()==CombatPhase.READY,"Release retracts the held spear and returns to READY");row.addProperty("releasedToReady",true);finishScenario(p);}
                }
                case SPEAR_TAP -> {
                    if(elapsed==1){inputId++;spear(p,"spearPress",0,0);check(!a.spearAttackHeld()&&a.snapshot().swingSequence()==sequence,"A second press during the existing point cannot borrow its swing or queue an unleased hold");}
                    if(a.currentSkill()==null){check(elapsed==4,"Point spear completes its existing four-tick / 0.2 second action");check(a.snapshot().swingSequence()==sequence,"Tap release cannot create a second thrust");row.addProperty("durationTicks",elapsed);finishScenario(p);}}
                case SPEAR_LEASE -> {
                    if(elapsed==3)p.connection.teleport(p.getX(),p.getY(),p.getZ(),180,0);
                    if(elapsed==4){check(a.spearAttackHeld(),"Root rotation arriving before its cursor packet preserves the existing unexpired hold lease");check(a.attackAim()!=null&&SpearAimField.contains(a.attackAim().sample(1),p.getYRot()),"Waiting for a new forward cursor keeps the authoritative visual spear in front");}
                    if(elapsed>=30){check(!a.spearAttackHeld()&&a.currentSkill()==null,"Lost hold renewals release the spear by the server input lease");check(a.snapshot().swingSequence()==sequence,"Lease expiry cannot generate an extra attack");row.addProperty("leaseCancelled",true);row.addProperty("additionalSwingCount",a.snapshot().swingSequence()-sequence);finishScenario(p);}}
                case SPEAR_CANCEL -> {
                    if(elapsed<=2)spear(p,"spearMove",0,0);
                    if(elapsed==3){a.stun(4);check(!a.spearAttackHeld()&&a.currentSkill()==null,"Real server hit-stun transition immediately cancels a held spear");}
                    if(elapsed==4){spear(p,"spearMove",0,15);check(!a.spearAttackHeld()&&a.currentSkill()==null,"A delayed drag packet cannot resurrect an interrupted spear");row.addProperty("staleMoveRejected",true);}
                    if(elapsed>=12){check(a.phase()==CombatPhase.READY&&a.snapshot().swingSequence()==sequence&&!a.spearAttackHeld(),"Interrupted spear returns to READY with no ghost or extra swing");row.addProperty("interruptCancelled",true);row.addProperty("additionalSwingCount",a.snapshot().swingSequence()-sequence);finishScenario(p);}
                }
                case STATIC_REGEN,COMBAT_REGEN -> {
                    if(scenario==Scenario.COMBAT_REGEN)a.markCombatActivity();
                    if(elapsed>=60){near(scenario==Scenario.STATIC_REGEN?15:7.5,a.stamina()-staminaBefore,"Three real seconds use the correct full/half stamina recovery: "+scenario);near(scenario==Scenario.STATIC_REGEN?5:2.5,a.currentStaminaRegenPerSecond(),"Actual server exposes the selected recovery rate: "+scenario);row.addProperty("recovered",a.stamina()-staminaBefore);row.addProperty("regenPerSecond",a.currentStaminaRegenPerSecond());finishScenario(p);}
                }
                case MISS_COST,CONTACT_COST -> {
                    expectedRegen+=a.currentStaminaRegenPerSecond()/20;
                    if(scenario==Scenario.MISS_COST)check(!a.staminaCombatState(),"An emitted empty sword swing never changes ongoing full-rate recovery at tick "+elapsed);
                    if(a.currentSkill()==null&&a.phase()==CombatPhase.READY){
                        int contacts=targetContacts.values().stream().mapToInt(Integer::intValue).sum();check(contacts==(scenario==Scenario.MISS_COST?0:1),"Real target event count distinguishes miss/contact: "+scenario);
                        near(scenario==Scenario.MISS_COST?2.5:5,staminaBefore+expectedRegen-a.stamina(),"Final sword cost accounts for actual ongoing recovery and miss refund: "+scenario);row.addProperty("observedDamageEvents",contacts);row.addProperty("integratedRecovery",expectedRegen);row.addProperty("finalNetCost",staminaBefore+expectedRegen-a.stamina());row.addProperty("fullRateUninterrupted",scenario==Scenario.MISS_COST&&!a.staminaCombatState());finishScenario(p);
                    }
                }
            }
            require(elapsed<160,"Scenario exceeded eight seconds: "+scenario);
        }catch(Throwable error){failure=error;}
    }
    private void finishScenario(ServerPlayer p){row.addProperty("passed",true);row.addProperty("actualElapsedTicks",elapsed);row.addProperty("stamina",actor(p).stamina());row.addProperty("swingSequence",actor(p).snapshot().swingSequence());scenarios.add(row);screenshots.add(row.get("id").getAsString());index++;prepared=false;begun=false;}
    private Object field(Class<?> type,Object instance,String name)throws ReflectiveOperationException{
        String key=type.getName()+"#"+name;Field f=fields.get(key);if(f==null){f=type.getDeclaredField(name);f.setAccessible(true);fields.put(key,f);}return f.get(instance);
    }
    private void submitted(RenderLivingEvent.Post<?,?,?> event){
        RenderObservations.captureSubmittedAvatar(event);Minecraft game=Minecraft.getInstance();
        if(!initialized||!ready||done||game.player==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.id!=game.player.getId())return;
        try{
            PlayerAnimations.Frame frame=PlayerAnimations.frame(state);if(frame==null)return;
            Visual v=visuals.computeIfAbsent(scenario,k->new Visual());v.frames++;if(Double.isNaN(v.firstTick))v.firstTick=frame.sampledTick();v.lastTick=frame.sampledTick();
            require(frame==PlayerAnimations.frame(state),"Skin and repeated actual renderer samples must share a native frame");v.sameFrameChecks++;
            @SuppressWarnings("unchecked") Map<UUID,Object> bindings=(Map<UUID,Object>)field(PlayerAnimations.class,null,"bindings");Object binding=bindings.get(game.player.getUUID());if(binding==null)return;
            RpgAnimationPayload payload=(RpgAnimationPayload)field(binding.getClass(),binding,"state");double sampledElapsed=((Number)field(binding.getClass(),binding,"sampledElapsedTicks")).doubleValue();
            if(frame.model().rig().id().equals("fsrpg.legacy_dual_blade")){
                Matrix4f leg=frame.world().get("right_leg");if(v.previousLeg!=null&&!leg.equals(v.previousLeg,.002f))v.changedLegPairs++;v.previousLeg=new Matrix4f(leg);
                if(payload.phase().equals("READY")&&state.walkAnimationSpeed<.015f&&game.player.onGround()&&!game.player.isCrouching()){
                    var clip=frame.model().rig().clips().get("legacy_dual_blade_static_idle");double seconds=frame.sampledTick()/20;seconds%=clip.duration();
                    var expected=new HashMap<>(frame.model().rig().sampleLocal(List.of(clip),seconds,Map.of()));
                    if(state.skin.model()==PlayerModelType.SLIM)for(String side:List.of("right","left")){String id=side+"_arm";Pose old=expected.get(id);V at=old.position();expected.put(id,new Pose(old.rotation(),new V(at.x()+(side.equals("right")?3.125f:-3.125f),at.y(),at.z()),old.scale()));}
                    var world=frame.model().worldMatrices(expected);double error=0;
                    for(String part:List.of("body","right_arm","left_arm","right_leg","left_leg")){float[] a=world.get(part).get(new float[16]),b=frame.world().get(part).get(new float[16]);for(int n=0;n<16;n++)error=Math.max(error,Math.abs(a[n]-b[n]));}
                    v.maxIdleError=Math.max(v.maxIdleError,error);require(error<.001,"READY dual player is still stuck in a baked attack endpoint: "+error);v.sourceIdleFrames++;
                }
            }
            if((scenario==Scenario.SPEAR_TURN||scenario==Scenario.RAPIER_TURN)&&frame.supportedLower()!=null&&payload.style().equals(style(scenario))){
                v.supportedTurnFrames++;
                var forward=frame.world().get("body").transformDirection(new Vector3f(0,0,1));double target=-Math.atan2(forward.x,forward.z)*(scenario==Scenario.RAPIER_TURN?SwordMotion.STANCE_FOLLOW:1);
                double twist=Math.abs(Math.atan2(Math.sin(target-frame.supportedLower().pelvisYaw()),Math.cos(target-frame.supportedLower().pelvisYaw())));v.maxTwist=Math.max(v.maxTwist,Math.toDegrees(twist));
                require(Math.toDegrees(twist)<=45.05,"Actual submitted waist exceeds configured 45 degree additional twist: "+Math.toDegrees(twist));
                double lift=24-Math.min(frame.supportedLower().right().sole().y,frame.supportedLower().left().sole().y);v.maxLift=Math.max(v.maxLift,lift);if(lift>.05)v.steppingFrames++;
                var turn=(SpearTurn.State)field(binding.getClass(),binding,scenario==Scenario.SPEAR_TURN?"spearTurn":"swordTurn");v.hardTurns=Math.max(v.hardTurns,turn.hardTurns());
            }
            if(scenario==Scenario.SPEAR_HOLD_SWEEP&&payload.spearHeld()&&elapsed>=8&&payload.phase().equals("ACTIVE")){
                v.heldFrames++;double error=Math.abs(sampledElapsed-payload.windupTicks()-payload.activeTicks());v.maxHeldClockError=Math.max(v.maxHeldClockError,error);require(error<.0001,"Held spear source clip escapes its extended endpoint");
            }
        }catch(Throwable error){failure=error;}
    }
    private void preRender(RenderFrameEvent.Pre event){
        Minecraft game=Minecraft.getInstance();if(initialized&&!done&&game.player!=null){game.setCameraEntity(game.player);game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if(turning){game.player.setYRot(forcedHeading);game.player.yRotO=forcedHeading;game.player.yBodyRot=game.player.yBodyRotO=forcedHeading;game.player.setYHeadRot(forcedHeading);}}
        RenderObservations.beginSubmittedFrame(event);
    }
    private JsonObject observation(){
        Minecraft game=Minecraft.getInstance();require(game.options.getCameraType()==CameraType.THIRD_PERSON_BACK&&game.getCameraEntity()==game.player,"Recording requires real local-player third person");
        JsonObject value=serverObservation.deepCopy();value.addProperty("scenario",scenario.name());value.addProperty("actualCameraType",game.options.getCameraType().name());value.addProperty("actualCameraEntityId",game.player.getUUID().toString());
        JsonObject submitted=RenderObservations.submittedSampler(game.player).get();value.add("rig",submitted.remove("rig"));value.add("submittedAvatar",submitted);return value;
    }
    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}movies.assertHealthy();if(failure!=null)throw new IllegalStateException("Combat revision review failed",failure);
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();require(System.nanoTime()-started<180_000_000_000L,"Review exceeded three minutes");
            boolean walk=ready&&!scriptDone&&(scenario==Scenario.DUAL_WALK||scenario==Scenario.DUAL_RUN||scenario==Scenario.DUAL_ATTACK_RETURN&&elapsed<28);
            game.options.keyUp.setDown(walk);game.options.keySprint.setDown(walk&&scenario==Scenario.DUAL_RUN);if(scenario!=Scenario.DUAL_RUN)game.player.setSprinting(false);
            if(ready&&!videoReady){officialMainBefore=OfficialHeldItems.submissions();officialOffBefore=OfficialHeldItems.offhandSubmissions();movies.start("combat-revision","Actual submitted player rig and native framebuffer, original looping dual locomotion, bounded turning feet, live held spear sweeps; no detached preview",this::observation);videoReady=true;}
            if(videoReady&&!stopped&&index>Scenario.SPEAR_HOLD_SWEEP.ordinal()){movies.stop();stopped=true;}
            if(scriptDone&&!stopped){movies.stop();stopped=true;}
            if(scriptDone&&screenshots.isEmpty()&&pendingShots.get()==0&&movies.finish()){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void render(RenderFrameEvent.Post event){if(!initialized||done)return;nativeFrames++;try{movies.frame(event);String name;while((name=screenshots.poll())!=null){pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}}
    private void validate(){
        check(scenarios.size()==Scenario.values().length,"All fourteen real client/server scenarios complete");
        for(Scenario key:List.of(Scenario.DUAL_WALK,Scenario.DUAL_RUN)){Visual v=visuals.get(key);check(v!=null&&v.changedLegPairs>40,"Native submitted dual leg bones keep changing across original gait loops: "+key);check((v.lastTick-v.firstTick)/20>=(key==Scenario.DUAL_WALK?2.85:1.5),"Native locomotion spans at least three authored cycles: "+key);}
        for(Scenario key:List.of(Scenario.DUAL_IDLE,Scenario.DUAL_ATTACK_RETURN)){Visual v=visuals.get(key);check(v!=null&&v.sourceIdleFrames>5&&v.maxIdleError<.001,"Actual READY rig returns to original natural static idle: "+key);}
        for(Scenario key:List.of(Scenario.SPEAR_TURN,Scenario.RAPIER_TURN)){Visual v=visuals.get(key);check(v!=null&&v.supportedTurnFrames>30,"Native turn fixture actually stays grounded through the real support pipeline: "+key);check(v.maxTwist<=45.05&&v.hardTurns>0,"Actual 180 degree/continuous turn triggers bounded direct lower-body follow: "+key);check(v.steppingFrames>3&&v.maxLift>.1,"Actual large turns also lift an alternating stepping foot: "+key);}
        Visual held=visuals.get(Scenario.SPEAR_HOLD_SWEEP);check(held!=null&&held.heldFrames>30&&held.maxHeldClockError<.0001,"Real held spear stays extended on native frames for longer than one second");
        check(OfficialHeldItems.submissions()>officialMainBefore&&OfficialHeldItems.offhandSubmissions()>officialOffBefore,"Native renderer submits official main and offhand ItemStacks");
        JsonObject clip=movies.summary().getAsJsonArray("clips").get(0).getAsJsonObject();check(ClientState.number(clip,"sourceFps",0)>=30,"Native PNG recording samples at least thirty actual rendered frames per second");check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native recording contains real partial-tick frames");check(ClientState.number(clip,"changedRigPairs",0)>50,"Native captured full-body rig changes across rendered frames");
        for(var frame:clip.getAsJsonArray("frames")){var sample=frame.getAsJsonObject().getAsJsonObject("observation");require(ClientState.text(sample.getAsJsonObject("submittedAvatar"),"nativePoseSnapshotStage","").equals("RenderLivingEvent.Post actual avatar submission"),"Captured rig was detached from its real rendered avatar");}
    }
    private void finish(Throwable error){
        if(done)return;done=true;movies.stop();try{movies.assertHealthy();}catch(Throwable e){if(error==null)error=e;}
        try{
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.combatRevisionWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("scenarios",scenarios);report.add("serverTickTrace",serverTrace);report.add("nativeVisualScenarios",RpgConfig.JSON.toJsonTree(visuals));report.addProperty("productionAssetCachePrewarmedBeforeRecording",true);report.addProperty("officialMainSubmissionsDuringCapture",OfficialHeldItems.submissions()-officialMainBefore);report.addProperty("officialOffhandSubmissionsDuringCapture",OfficialHeldItems.offhandSubmissions()-officialOffBefore);report.add("renderCapture",movies.summary());RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("combat-revision-review.json"),RpgConfig.JSON.toJson(report));System.out.println("COMBAT REVISION REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
}

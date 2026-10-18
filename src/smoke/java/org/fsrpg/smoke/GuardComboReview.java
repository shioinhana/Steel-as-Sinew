package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.GuardCuePresentation;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.creature.CreatureMeleeServer;
import org.fsrpg.network.RpgGuardCuePayload;
import org.fsrpg.progression.ProgressionService;
import org.lwjgl.glfw.GLFW;

/** Packaged-client acceptance: native telegraph frames, real creature contacts and server attack intentions. */
final class GuardComboReview {
    private enum GuardCase { EARLY, HELD_RENEWAL, SUSPEND_RESUME, WINDOW, RELEASE_REPRESS, LATE_PRESS, ACTIVE_INTERRUPT, LATER_ATTACK, CANCEL, DEAD }
    private enum ComboCase { RAPIER_WINDUP, RAPIER_ACTIVE, RAPIER_RESUME, DUAL_LEFT, DUAL_RIGHT, RAPIER_GUARD_CANCEL, DUAL_GUARD_CANCEL }
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final List<JsonObject> cuePackets=new CopyOnWriteArrayList<>(),cueFrames=new CopyOnWriteArrayList<>();
    private final Map<Long,Long> terminalReceipts=new ConcurrentHashMap<>();
    private final Map<Long,Double> lastProgress=new ConcurrentHashMap<>();
    private final Set<Long> screenshotSequences=ConcurrentHashMap.newKeySet();
    private final Queue<String> screenshots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots=new AtomicInteger(),capturedShots=new AtomicInteger();
    private final JsonArray guardCases=new JsonArray(),comboCases=new JsonArray(),serverTrace=new JsonArray();
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final AutoCloseable packetObserver,frameObserver;
    private volatile Throwable failure;
    private volatile boolean ready,scriptDone,videoReady,videoStopped;
    private volatile String visibleCase="setup";
    private volatile long observedServerTick;
    private volatile JsonObject serverObservation=new JsonObject();
    private UUID playerId;
    private Mob enemy,lateEnemy;
    private CreatureMeleeServer.Snapshot attack,lateAttack;
    private CreatureMeleeServer.GuardCueSnapshot schedule;
    private boolean initialized,done,prepared,begun,pressed,mutated,contactChecked,lateContactChecked,queued,secondStarted;
    private int warmup,stage,caseIndex,previousFps,previousDistance,previousScale;
    private boolean previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private long started,nativeFrames,serverTicks,caseStarted,firstSequence,lastSequence,readyAt,finishAt,guardAgeBefore,visibleFlashesBefore;
    private long officialMainBefore,officialOffhandBefore;
    private double initialStamina;
    private float healthBefore,healthAfterFirst;
    private JsonObject row;
    private final List<String> actualSkills=new ArrayList<>();
    private final Set<Long> recordedSequences=new HashSet<>();

    GuardComboReview(){
        packetObserver=GuardCuePresentation.observePackets(this::cuePacket);
        frameObserver=GuardCuePresentation.observeFrames(this::cueFrame);
        NeoForge.EVENT_BUS.addListener(this::preRender);
        NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::serverTick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.add(message);System.out.println("GUARD COMBO PASS "+message);}
    private void near(double expected,double actual,String message){check(Double.isFinite(actual)&&Math.abs(expected-actual)<.003,message+" expected="+expected+" actual="+actual);}
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    private void action(ServerPlayer p,String name,String argument){rpg().action(p,name,argument);}
    private void guard(ServerPlayer p,boolean held){
        action(p,"guard",Boolean.toString(held));
        Minecraft.getInstance().execute(()->{for(var key:Minecraft.getInstance().options.keyMappings)if(key.getName().equals("key.fsrpg.guard"))key.setDown(held);});
    }
    private void cuePacket(RpgGuardCuePayload payload){
        if(!initialized||done)return;
        JsonObject p=RpgConfig.JSON.toJsonTree(payload).getAsJsonObject();p.addProperty("receiptNativeNanoTime",System.nanoTime());cuePackets.add(p);
        if(payload.phase().equals("CANCEL")||payload.phase().equals("END"))terminalReceipts.put(payload.sequence(),System.nanoTime());
    }
    private void cueFrame(GuardCuePresentation.Frame frame){
        if(!initialized||done)return;
        try{
            JsonObject f=RpgConfig.JSON.toJsonTree(frame).getAsJsonObject();f.addProperty("scenarioAtRender",visibleCase);cueFrames.add(f);
            Long terminal=terminalReceipts.get(frame.sequence());
            if(terminal!=null&&frame.nativeNanoTime()>terminal)throw new AssertionError("A terminal attack rendered a ghost flash: "+frame.sequence());
            Double previous=lastProgress.put(frame.sequence(),frame.progress());
            if(previous!=null&&frame.progress()+1e-6<previous)throw new AssertionError("Duplicate cue packet restarted a flash: "+frame.sequence());
            if(frame.serverGameTime()<frame.flashGameTime()||frame.serverGameTime()>=frame.flashGameTime()+2.4+.001)
                throw new AssertionError("Visible flash escaped its authoritative 120 ms envelope");
            if(screenshotSequences.add(frame.sequence()))screenshots.add("gold-flash-sequence-"+frame.sequence());
        }catch(Throwable error){failure=error;}
    }
    private void initialize(Minecraft game)throws Exception{
        Files.createDirectories(root.resolve("screenshots"));JsonObject env=new JsonObject();RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env,"monitorAdapter","")),"Actual game window is on DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name=System.getProperty("fsrpg.guardComboWorld","");
        check(name.equals("FSRPG Guard Combo Review 20261018 01")&&world.getFileName().toString().equals(name)
                &&Files.isRegularFile(world.resolve(".fsrpg-guard-combo-test-world")),"Only the named and marked copied world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;previousActive=game.isWindowActive();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();GLFW.glfwSetWindowSize(handle,1000,680);
        GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);GLFW.glfwSetWindowTitle(handle,"FSRPG 金闪格挡与宽容连段验收 · DISPLAY5");game.resizeDisplay();
        initialized=true;started=System.nanoTime();visibleFlashesBefore=GuardCuePresentation.visibleFlashes();
        game.getSingleplayerServer().execute(()->{
            try{
                ServerPlayer p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(48),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))e.discard();
                p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(false);p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);p.setHealth(200);p.getFoodData().setFoodLevel(18);
                p.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);p.level().setDayTime(18000);p.level().setWeatherParameters(6000,0,false,false);
                for(int x=-8;x<=8;x++)for(int z=-6;z<=12;z++){p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
                p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.setExperienceLevels(0);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                if(p.getAttribute(Attributes.KNOCKBACK_RESISTANCE)!=null)p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
                p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,6000,0,false,false));fixtureProgress();equip(p,"rapier");ready=true;
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
        check(service.weaponUnlocked(playerId,"rapier")&&service.weaponUnlocked(playerId,"dual_blade"),"Copied fixture has both real learned weapon roots");
    }
    private void equip(ServerPlayer p,String style){
        guard(p,false);actor(p).forceSheathed();p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,style.equals("dual_blade")?new ItemStack(Items.DIAMOND_SWORD):ItemStack.EMPTY);
        action(p,"status","");check(actor(p).style()!=null&&actor(p).style().id().equals(style),"Real equipment reconciliation selects the intended fixture style: "+style);
        actor(p).setResources(actor(p).maxStamina(),100);p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.invulnerableTime=0;p.hurtTime=0;p.setHealth(200);
    }
    private Mob spawn(ServerPlayer p,double x){
        Mob m=(Mob)EntityType.VINDICATOR.create(p.level(),EntitySpawnReason.COMMAND);if(m==null)throw new AssertionError("Cannot spawn actual Vindicator");
        m.setPos(x,80,1.7);m.setYRot(180);m.yBodyRot=m.yBodyRotO=180;m.setNoGravity(true);m.setNoAi(true);m.setPersistenceRequired();
        m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);m.setHealth(200);m.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4);
        if(m.getAttribute(Attributes.ARMOR)!=null)m.getAttribute(Attributes.ARMOR).setBaseValue(0);if(m.getAttribute(Attributes.MOVEMENT_SPEED)!=null)m.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        if(m.getAttribute(Attributes.KNOCKBACK_RESISTANCE)!=null)m.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        p.level().addFreshEntity(m);m.setTarget(p);return m;
    }
    private void serverTick(ServerTickEvent.Post event){
        if(!initialized||!ready||done||scriptDone||failure!=null||!videoReady)return;
        try{
            ServerPlayer p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;serverTicks++;observedServerTick=p.level().getGameTime();
            serverObservation=RpgConfig.JSON.toJsonTree(actor(p).snapshot()).getAsJsonObject();serverObservation.addProperty("scenario",visibleCase);
            if(stage==0){if(caseIndex<GuardCase.values().length)guardStep(p,GuardCase.values()[caseIndex]);else{stage=1;caseIndex=0;prepared=false;}}
            else if(caseIndex<ComboCase.values().length)comboStep(p,ComboCase.values()[caseIndex]);else scriptDone=true;
        }catch(Throwable error){failure=error;}
    }
    private void guardStep(ServerPlayer p,GuardCase kind){
        long now=p.level().getGameTime();
        if(!prepared){
            discardEnemies();equip(p,"rapier");enemy=spawn(p,.5);visibleCase="guard-"+kind.name().toLowerCase(Locale.ROOT);caseStarted=now;
            row=new JsonObject();row.addProperty("id",visibleCase);prepared=true;begun=pressed=mutated=contactChecked=lateContactChecked=false;return;
        }
        if(!begun){
            if(actor(p).phase()!=CombatPhase.READY||now-caseStarted<8)return;
            healthBefore=p.getHealth();if(kind==GuardCase.EARLY||kind==GuardCase.HELD_RENEWAL||kind==GuardCase.RELEASE_REPRESS)guard(p,true);
            if(kind==GuardCase.SUSPEND_RESUME){
                action(p,"guard","suspend");row.addProperty("suspendedGameTick",now);
                check(actor(p).phase()==CombatPhase.READY&&!actor(p).guardHeld(),"Suspending physically held C keeps the real actor ready without starting a guard");
            }
            check(!enemy.doHurtTarget(p.level(),p),"Real Vindicator starts animated windup without immediate damage: "+kind);
            attack=rpg().creatureMelee().snapshot(enemy);schedule=rpg().creatureMelee().guardCueSnapshot(enemy);
            check(attack!=null&&schedule!=null&&schedule.flashGameTick()>now,"BEGIN carries a future server flash schedule: "+kind);
            check(schedule.contactGameTick()==attack.contactGameTick()&&schedule.closeGameTick()==attack.contactGameTick(),"Guard cue and original contact use the same authoritative clock: "+kind);
            check(schedule.contactGameTick()-schedule.flashGameTick()==6&&schedule.closeGameTick()-schedule.openGameTick()==6,"Current configured lead and input interval are each six ticks: "+kind);
            for(int duplicate=0;duplicate<4;duplicate++)rpg().creatureMelee().startTracking(p,enemy);
            row.add("schedule",RpgConfig.JSON.toJsonTree(schedule));caseStarted=now;begun=true;return;
        }
        JsonObject trace=RpgConfig.JSON.toJsonTree(actor(p).snapshot()).getAsJsonObject();trace.addProperty("case",visibleCase);trace.addProperty("worldGameTick",now);trace.addProperty("health",p.getHealth());serverTrace.add(trace);
        if(kind==GuardCase.HELD_RENEWAL&&now<attack.contactGameTick()){
            int age=actor(p).snapshot().guardElapsedTicks();guard(p,true);check(actor(p).snapshot().guardElapsedTicks()==age,"Held-C renewal does not refresh guard start age at "+now);guardAgeBefore=age;
        }
        if(kind==GuardCase.CANCEL&&!mutated&&now==caseStarted+2){enemy.setTarget(null);mutated=true;}
        if(kind==GuardCase.DEAD&&!mutated&&now==schedule.flashGameTick()+1){enemy.setHealth(0);mutated=true;}
        if(kind==GuardCase.ACTIVE_INTERRUPT&&!mutated&&now==schedule.openGameTick()-2){
            action(p,"attack","left");check(actor(p).phase()==CombatPhase.WINDUP,"Actual own sword enters windup before the guard cue");action(p,"attack","left");
            check(actor(p).comboSnapshot().queued(),"Own follow-up is really queued before precision interruption");mutated=true;
        }
        boolean precise=kind==GuardCase.WINDOW||kind==GuardCase.RELEASE_REPRESS||kind==GuardCase.ACTIVE_INTERRUPT||kind==GuardCase.LATER_ATTACK;
        if(kind==GuardCase.SUSPEND_RESUME&&!pressed&&now==schedule.openGameTick()+1){
            action(p,"guard","held");row.addProperty("resumedGameTick",now);pressed=true;
            check(actor(p).phase()==CombatPhase.GUARDING&&actor(p).guardHeld(),"Held-C resume inside the cue enters ordinary guard without inventing a fresh input edge");
        }
        if(precise&&!pressed&&now==schedule.openGameTick()+1){
            if(kind==GuardCase.ACTIVE_INTERRUPT)check(actor(p).phase()==CombatPhase.ACTIVE,"Guard input occurs during actual own ACTIVE phase");
            if(kind==GuardCase.RELEASE_REPRESS)guard(p,false);
            double beforeGuard=actor(p).stamina();
            guard(p,true);check(actor(p).phase()==CombatPhase.GUARDING,"Fresh in-window C enters guarding: "+kind);
            near(beforeGuard,actor(p).stamina(),"Guard input neither refunds a canceled blade nor prepays a second attack: "+kind);
            check(actor(p).currentSkill()==null&&!actor(p).comboSnapshot().queued(),"Precision C clears current attack and buffered follow-up: "+kind);
            row.addProperty("freshPressGameTick",now);row.addProperty("sequenceAtGuard",actor(p).snapshot().swingSequence());pressed=true;
        }
        if(kind==GuardCase.LATE_PRESS&&!pressed&&now==schedule.closeGameTick()){
            guard(p,true);row.addProperty("freshPressGameTick",now);pressed=true;
            check(rpg().perfectGuardStunRemaining(enemy)==0&&actor(p).counterRemainingTicks()==0,"C on the exclusive closing tick cannot retroactively authorize a perfect guard");
        }
        if(kind==GuardCase.LATER_ATTACK&&pressed&&lateEnemy==null&&now==schedule.openGameTick()+2){
            lateEnemy=spawn(p,.9);check(!lateEnemy.doHurtTarget(p.level(),p),"A different enemy begins only after the fresh C edge");lateAttack=rpg().creatureMelee().snapshot(lateEnemy);
            check(lateAttack!=null&&lateAttack.sequence()!=attack.sequence(),"Later enemy has a separate real attack sequence");row.add("laterAttack",RpgConfig.JSON.toJsonTree(lateAttack));
        }
        if(now<attack.contactGameTick())near(healthBefore,p.getHealth(),"No original damage before actual animation contact: "+kind+" @"+now);
        if(!contactChecked&&now>=attack.contactGameTick()){
            contactChecked=true;healthAfterFirst=p.getHealth();row.addProperty("observedContactGameTick",now);row.addProperty("firstHealthLoss",healthBefore-healthAfterFirst);
            row.addProperty("observedParryStunTicks",rpg().perfectGuardStunRemaining(enemy));row.addProperty("observedCounterRemainingTicks",actor(p).counterRemainingTicks());
            if(kind==GuardCase.CANCEL||kind==GuardCase.DEAD){near(healthBefore,p.getHealth(),"Canceled/dead attacker produces no ghost hit: "+kind);check(rpg().creatureMelee().snapshot(enemy)==null,"Canceled/dead attack has no remaining server timeline: "+kind);}
            else if(precise){near(healthBefore,p.getHealth(),"Window-authorized C blocks the real original attack completely: "+kind);check(rpg().perfectGuardStunRemaining(enemy)==4,"Bound enemy gets exact four-tick perfect-guard stun: "+kind);check(actor(p).counterRemainingTicks()>0,"Real creature parry opens the counter opportunity: "+kind);}
            else if(kind==GuardCase.LATE_PRESS){near(4,healthBefore-p.getHealth(),"Late C cannot undo the already completed original contact");check(rpg().perfectGuardStunRemaining(enemy)==0,"Late C does not stun the attacking creature");}
            else {check(p.getHealth()<healthBefore,"Early, held or resumed C gives an ordinary block, not a perfect parry: "+kind);check(rpg().perfectGuardStunRemaining(enemy)==0&&actor(p).counterRemainingTicks()==0,"Early/renewed/resumed guard never stuns the creature or grants a perfect counter: "+kind);near(4*actor(p).guardRules().damageMultiplier(),healthBefore-p.getHealth(),"Original ordinary block damage is retained: "+kind);}
        }
        if(kind==GuardCase.LATER_ATTACK&&lateAttack!=null&&!lateContactChecked&&now>=lateAttack.contactGameTick()){
            lateContactChecked=true;p.invulnerableTime=0;check(rpg().perfectGuardStunRemaining(lateEnemy)==0,"Earlier C cannot steal a perfect parry on a later unbound enemy sequence");
            check(p.getHealth()<healthAfterFirst,"Later unbound attack really reaches guard/damage handling");row.addProperty("laterHealthLoss",healthAfterFirst-p.getHealth());row.addProperty("laterObservedParryStunTicks",rpg().perfectGuardStunRemaining(lateEnemy));
        }
        long end=lateAttack!=null&&kind==GuardCase.LATER_ATTACK?lateAttack.endGameTick():attack.endGameTick();
        if(now<end+3)return;
        if(kind==GuardCase.HELD_RENEWAL)check(guardAgeBefore>=6,"Held-C test covered a full cue interval without refreshing the input edge");
        if(kind==GuardCase.ACTIVE_INTERRUPT)check(actor(p).currentSkill()==null&&!actor(p).comboSnapshot().queued(),"Precision guard leaves no delayed sword attack after enemy recovery");
        row.addProperty("passed",true);guardCases.add(row);guard(p,false);discardEnemies();caseIndex++;prepared=false;lateAttack=null;
    }
    private void discardEnemies(){if(enemy!=null&&!enemy.isRemoved())enemy.discard();if(lateEnemy!=null&&!lateEnemy.isRemoved())lateEnemy.discard();enemy=lateEnemy=null;}
    private void observeStart(CombatActor a){
        long sequence=a.snapshot().swingSequence();if(a.currentSkill()==null||!recordedSequences.add(sequence))return;
        actualSkills.add(a.currentSkill().id());JsonObject start=RpgConfig.JSON.toJsonTree(a.snapshot()).getAsJsonObject();start.addProperty("worldGameTick",observedServerTick);row.getAsJsonArray("actualStarts").add(start);
    }
    private void attack(ServerPlayer p,String direction){
        action(p,"attack",direction);CombatActor a=actor(p);observeStart(a);
        if(a.comboSnapshot().queued()){
            if(!row.has("queueEdges"))row.add("queueEdges",new JsonArray());JsonObject edge=RpgConfig.JSON.toJsonTree(a.comboSnapshot()).getAsJsonObject();
            edge.addProperty("worldGameTick",observedServerTick);edge.addProperty("phase",a.phase().name());edge.addProperty("direction",direction);edge.addProperty("currentSwingSequence",a.snapshot().swingSequence());edge.addProperty("stamina",a.stamina());row.getAsJsonArray("queueEdges").add(edge);
        }
    }
    private void comboStep(ServerPlayer p,ComboCase kind){
        CombatActor a=actor(p);long now=p.level().getGameTime();boolean dual=kind.name().startsWith("DUAL");
        if(!prepared){
            equip(p,dual?"dual_blade":"rapier");visibleCase="combo-"+kind.name().toLowerCase(Locale.ROOT);row=new JsonObject();row.addProperty("id",visibleCase);row.add("actualStarts",new JsonArray());
            actualSkills.clear();recordedSequences.clear();prepared=true;begun=queued=secondStarted=false;readyAt=-1;finishAt=-1;caseStarted=now;return;
        }
        if(!begun){
            if(a.phase()!=CombatPhase.READY)return;initialStamina=a.stamina();attack(p,kind==ComboCase.DUAL_RIGHT?"right":"left");firstSequence=a.snapshot().swingSequence();lastSequence=firstSequence;caseStarted=now;
            row.addProperty("initialStamina",initialStamina);row.addProperty("firstStartedGameTick",now);
            check(a.currentSkill()!=null,"Real first attack starts through server action: "+kind);near(dual?8:5,initialStamina-a.stamina(),"First click pays exactly one sword cost: "+kind);begun=true;
            if(kind!=ComboCase.RAPIER_ACTIVE&&kind!=ComboCase.RAPIER_RESUME){
                attack(p,kind==ComboCase.DUAL_RIGHT?"right":"left");queued=true;
                check(a.snapshot().swingSequence()==firstSequence&&a.comboSnapshot().queued(),"Earliest windup click queues without replacing current attack: "+kind);
                near(dual?8:5,initialStamina-a.stamina(),"Queue acceptance cannot charge a second blade early: "+kind);
                if(kind==ComboCase.RAPIER_GUARD_CANCEL||kind==ComboCase.DUAL_GUARD_CANCEL){guard(p,true);check(a.phase()==CombatPhase.GUARDING&&!a.comboSnapshot().queued()&&a.currentSkill()==null,"Ordinary defensive cancellation removes the queued next blade: "+kind);finishAt=now+16;}
            }
            return;
        }
        observeStart(a);
        if(kind==ComboCase.RAPIER_ACTIVE&&!queued&&a.phase()==CombatPhase.ACTIVE){
            attack(p,"left");queued=true;check(a.snapshot().swingSequence()==firstSequence&&a.comboSnapshot().queued(),"ACTIVE-phase click queues without swallowing or replacing the current blade");near(5,initialStamina-a.stamina(),"Active buffer still costs only the already started sword");
        }
        if(kind==ComboCase.RAPIER_RESUME&&!secondStarted&&a.currentSkill()==null&&a.phase()==CombatPhase.READY){
            if(readyAt<0){readyAt=now;check(a.snapshot().swingSequence()==firstSequence,"Natural recovery completes the first blade without an invented attack");}
            if(now==readyAt+6){attack(p,"left");check(a.comboSnapshot().stage()==2,"A relaxed click 0.3 seconds after full recovery continues stage two");secondStarted=true;}
        }
        long sequence=a.snapshot().swingSequence();
        if(sequence>lastSequence){
            if(sequence==firstSequence+1)row.addProperty("secondStartedGameTick",now);
            check(sequence==lastSequence+1,"One accepted continuation creates exactly one additional blade: "+kind);
            near((dual?8:5)*(sequence-firstSequence+1),initialStamina-a.stamina(),"Started blades each pay exactly one configured cost: "+kind);
            if(dual&&sequence<firstSequence+3)attack(p,kind==ComboCase.DUAL_RIGHT?"right":"left");lastSequence=sequence;
        }
        boolean canceled=kind==ComboCase.RAPIER_GUARD_CANCEL||kind==ComboCase.DUAL_GUARD_CANCEL;
        int wanted=canceled?1:dual?4:2;
        if(canceled&&now>=finishAt){check(sequence==firstSequence&&actualSkills.size()==1&&!a.comboSnapshot().queued(),"Held guard never releases a buffered ghost blade: "+kind);finishCombo(p,kind,wanted);return;}
        if(!canceled&&actualSkills.size()==wanted&&a.currentSkill()==null&&a.phase()==CombatPhase.READY){
            if(finishAt<0){finishAt=now+2;return;}if(now<finishAt)return;
            check(sequence==firstSequence+wanted-1,"Point clicks produce the requested count without automatic extra swings: "+kind);
            if(dual){List<String> expected=kind==ComboCase.DUAL_RIGHT?List.of("dual_rr","dual_lr","dual_rr","dual_lr"):List.of("dual_ll","dual_rl","dual_ll","dual_rl");check(actualSkills.equals(expected),"Four same-direction dual inputs alternate real hands without unsupported rejection: "+kind);}
            else check(actualSkills.equals(List.of("sword_left","rapier_downcut")),"Relaxed single-sword input preserves the intended first two original stages: "+kind);
            finishCombo(p,kind,wanted);
        }
        check(now-caseStarted<120,"Combo scenario completes within six seconds: "+kind);
    }
    private void finishCombo(ServerPlayer p,ComboCase kind,int wanted){row.addProperty("actualBladeCount",actualSkills.size());row.addProperty("expectedBladeCount",wanted);row.add("skills",RpgConfig.JSON.toJsonTree(actualSkills));row.addProperty("passed",true);comboCases.add(row);guard(p,false);caseIndex++;prepared=false;}
    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}movies.assertHealthy();if(failure!=null)throw new IllegalStateException("Server/native guard-combo review failed",failure);
            if(game.screen instanceof net.minecraft.client.gui.screens.PauseScreen)game.setScreen(null);game.getFramerateLimitTracker().onInputReceived();
            if(System.nanoTime()-started>180_000_000_000L)throw new AssertionError("Guard/combo review exceeded three minutes");
            if(ready&&!videoReady){
                officialMainBefore=OfficialHeldItems.submissions();officialOffhandBefore=OfficialHeldItems.offhandSubmissions();
                movies.start("guard-and-combo","Unmodified native framebuffer frames; RenderLivingEvent.Post submitted player rig, real server creature contacts, world-positioned gold cue and official ItemStack sword animation",this::observation);videoReady=true;
            }
            if(scriptDone&&!videoStopped){movies.stop();videoStopped=true;}
            if(scriptDone&&videoStopped&&screenshots.isEmpty()&&pendingShots.get()==0&&movies.finish()){validate();finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private JsonObject observation(){
        Minecraft game=Minecraft.getInstance();
        if(game.options.getCameraType()!=CameraType.THIRD_PERSON_BACK||game.getCameraEntity()!=game.player)
            throw new AssertionError("Native avatar recording requires the real local-player third-person camera");
        JsonObject sample=serverObservation.deepCopy();sample.addProperty("observedServerGameTick",observedServerTick);sample.addProperty("scenario",visibleCase);
        sample.addProperty("actualCameraType",game.options.getCameraType().name());sample.addProperty("actualCameraEntityId",game.getCameraEntity().getUUID().toString());
        JsonObject avatar=RenderObservations.submittedSampler(game.player).get();
        sample.add("rig",avatar.remove("rig"));sample.add("submittedAvatar",avatar);
        var frame=GuardCuePresentation.lastFrame();if(frame!=null)sample.add("visibleWorldProjectedGuardCue",RpgConfig.JSON.toJsonTree(frame));return sample;
    }
    private void preRender(RenderFrameEvent.Pre event){
        // Sheath/draw presentation packets can restore the user's former first-person camera
        // between client ticks. Fix the copied review camera before this world's actual render.
        Minecraft game=Minecraft.getInstance();
        if(initialized&&!done&&game.level!=null&&game.player!=null){game.setCameraEntity(game.player);game.options.setCameraType(CameraType.THIRD_PERSON_BACK);}
        RenderObservations.beginSubmittedFrame(event);
    }
    private void render(RenderFrameEvent.Post event){
        if(done||!initialized)return;nativeFrames++;
        try{movies.frame(event);String shot;while((shot=screenshots.poll())!=null){String name=shot;pendingShots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedShots.incrementAndGet();pendingShots.decrementAndGet();});}}catch(Throwable error){failure=error;}
    }
    private void validate(){
        check(guardCases.size()==GuardCase.values().length&&comboCases.size()==ComboCase.values().length,"All actual guard and sword scenarios completed");
        check(!cueFrames.isEmpty()&&capturedShots.get()>0,"Real framebuffer presentation includes a visible world-projected gold flash");
        check(cuePackets.stream().anyMatch(p->ClientState.text(p,"phase","").equals("BEGIN")&&ClientState.number(p,"flashGameTime",0)>ClientState.number(p,"serverGameTime",0)),"A real received BEGIN packet announces flash time in advance");
        check(cuePackets.stream().anyMatch(p->ClientState.text(p,"phase","").equals("CANCEL")),"Real cancellation payload reaches client cue presentation");
        check(GuardCuePresentation.visibleFlashes()-visibleFlashesBefore==screenshotSequences.size(),"Repeated real BEGIN/tracking packets create exactly one visible flash per rendered attack sequence");
        for(var f:cueFrames){check(Double.isFinite(ClientState.number(f,"screenX",Double.NaN))&&Double.isFinite(ClientState.number(f,"screenY",Double.NaN)),"Native gold cue has finite world-projected coordinates");}
        var summary=movies.summary();var clip=summary.getAsJsonArray("clips").get(0).getAsJsonObject();
        check(ClientState.number(clip,"sourceFps",0)>=30,"Native recording really samples at least thirty rendered frames per second");
        check(ClientState.number(clip,"distinctSamplesWithinGameTick",0)>0,"Native recording contains actual sub-tick source frames");
        check(ClientState.number(clip,"changedRigPairs",0)>0,"Actual submitted player FK changes across native captured frames");
        check(OfficialHeldItems.submissions()>officialMainBefore,"The real renderer submits official main-hand ItemStack geometry during this recording");
        check(OfficialHeldItems.offhandSubmissions()>officialOffhandBefore,"The real renderer submits the official second sword during this recording");
        long submittedRigFrames=0,invalidSubmittedProvenance=0;
        for(var value:clip.getAsJsonArray("frames")){
            JsonObject observation=value.getAsJsonObject().getAsJsonObject("observation"),avatar=observation.getAsJsonObject("submittedAvatar");
            if(avatar==null||!ClientState.text(avatar,"nativePoseSnapshotStage","").equals("RenderLivingEvent.Post actual avatar submission"))invalidSubmittedProvenance++;
            if(!observation.getAsJsonObject("rig").isEmpty())submittedRigFrames++;
        }
        check(invalidSubmittedProvenance==0,"Every captured rig comes from the current-frame avatar submission");
        check(submittedRigFrames>30,"Recording contains more than thirty real submitted nonempty player rigs");
    }
    private void finish(Throwable error){
        try{movies.assertHealthy();}catch(Throwable captureError){if(error==null)error=captureError;}
        if(done)return;done=true;movies.stop();try{packetObserver.close();frameObserver.close();}catch(Exception closing){if(error==null)error=closing;}
        try{
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.guardComboWorld"));report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);
            report.addProperty("serverTicks",serverTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedShots.get());report.addProperty("timingSource","Project-configured six-tick lead/window and 120 ms flash; not verified ZZZ frame data");
            report.addProperty("visibleFlashes",GuardCuePresentation.visibleFlashes()-visibleFlashesBefore);report.addProperty("distinctVisibleSequences",screenshotSequences.size());
            report.addProperty("officialMainSubmissionsDuringCapture",OfficialHeldItems.submissions()-officialMainBefore);report.addProperty("officialOffhandSubmissionsDuringCapture",OfficialHeldItems.offhandSubmissions()-officialOffhandBefore);
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("guardScenarios",guardCases);report.add("comboScenarios",comboCases);report.add("receivedCuePackets",RpgConfig.JSON.toJsonTree(cuePackets));report.add("nativeWorldProjectedCueFrames",RpgConfig.JSON.toJsonTree(cueFrames));report.add("serverTickTrace",serverTrace);report.add("renderCapture",safeCaptureSummary());RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("guard-combo-review.json"),RpgConfig.JSON.toJson(report));System.out.println("GUARD COMBO REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();game.setCameraEntity(game.player);
        if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}game.stop();
    }
    private JsonObject safeCaptureSummary(){
        try{return movies.summary();}
        catch(Throwable captureError){JsonObject summary=new JsonObject();summary.addProperty("passed",false);summary.addProperty("error",captureError.toString());summary.addProperty("captureEvent","RenderFrameEvent.Post");return summary;}
    }
}

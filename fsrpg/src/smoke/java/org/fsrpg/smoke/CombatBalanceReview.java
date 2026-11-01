package org.fsrpg.smoke;

import com.google.gson.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.*;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.archery.ArcheryProgress;
import org.fsrpg.client.ClientState;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.progression.ProgressionService;
import org.lwjgl.glfw.GLFW;

/** Real packaged-server damage, NPC ticks, physical-intent dispatch and spawned vanilla arrows. */
final class CombatBalanceReview {
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final List<String> checks=new CopyOnWriteArrayList<>();
    private final JsonArray stuns=new JsonArray(), melee=new JsonArray(), shots=new JsonArray(), counterPackets=new JsonArray();
    private final Queue<String> screenshots=new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingScreenshots=new AtomicInteger(), capturedScreenshots=new AtomicInteger();
    private volatile Throwable failure;
    private volatile boolean ready, scriptDone;
    private UUID playerId;
    private boolean initialized,done,sawCounterHint;
    private int warmup,stage,costIndex,bowIndex,previousFps,previousDistance,previousScale;
    private boolean prepared,bowDrawing,previousPause,previousVsync,previousHidden,previousActive;
    private CameraType previousCamera;
    private long started,serverTicks,nativeFrames,perfectGameTick,counterActorTick,chargeGameTick;
    private int enemyTickBefore,enemyCompletedTicks,counterDuration,chargeHold;
    private Zombie attacker;
    private Mob target;
    private float targetBefore,healthBefore;
    private double expectedCounterDamage;
    private final AutoCloseable snapshotObserver;
    private final String[] costStyles={"rapier","dual_blade","spear"};
    private final double[] meleeCosts={5,8,10};
    private final String[] bowStyles={"bow_rapid","bow_rapid","bow_snipe","bow_snipe","bow_rapid","bow_rapid"};
    private final double[] bowCosts={5,3.5,15,10.5,0,0};

    CombatBalanceReview() {
        // Several full snapshots may be delivered in one client tick. Observe receipt of each
        // real server packet, so a later accepted counter cannot hide its earlier short window.
        snapshotObserver=ClientState.observeSnapshots(snapshot->{
            if(done||!initialized||!ready)return;
            double remaining=ClientState.number(snapshot,"counterRemainingTicks",0);
            if(remaining<=0)return;
            sawCounterHint=true;
            JsonObject packet=new JsonObject();packet.addProperty("serverTick",ClientState.number(snapshot,"serverTick",-1));
            packet.addProperty("remainingTicks",remaining);packet.addProperty("phase",ClientState.text(snapshot,"phase",""));
            packet.addProperty("clientReceiptNanos",System.nanoTime());counterPackets.add(packet);
        });
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post event)->{if(event.getEntity()==attacker)enemyCompletedTicks++;});
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean value,String text) {if(!value)throw new AssertionError(text);checks.add(text);System.out.println("COMBAT BALANCE PASS "+text);}
    private void near(double expected,double actual,String text) {check(Math.abs(expected-actual)<.002,text+" expected="+expected+" actual="+actual);}
    private RpgServer rpg(){return RpgServer.instance();}
    private CombatActor actor(ServerPlayer player){return RpgSmoke.actor(player);}
    private void action(ServerPlayer player,String action,String argument){rpg().action(player,action,argument);}

    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(root.resolve("screenshots"));
        JsonObject environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        check("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()),"Real review window belongs to DISPLAY5");
        Path world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name=System.getProperty("fsrpg.combatBalanceWorld","");
        check(name.startsWith("FSRPG Combat Balance Review ")&&name.equals(world.getFileName().toString())
                &&Files.isRegularFile(world.resolve(".fsrpg-combat-balance-test-world")),"Only named marked copied world is modified");
        playerId=game.player.getUUID();previousFps=game.options.framerateLimit().get();previousDistance=game.options.renderDistance().get();previousScale=game.options.guiScale().get();
        previousPause=game.options.pauseOnLostFocus;previousVsync=game.options.enableVsync().get();previousHidden=game.options.hideGui;
        previousCamera=game.options.getCameraType();previousActive=game.isWindowActive();
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.guiScale().set(2);
        game.options.hideGui=false;game.options.setCameraType(CameraType.THIRD_PERSON_BACK);game.setWindowActive(true);KeyMapping.releaseAll();game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=environment.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle,1000,680);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+35,bounds.get("y").getAsInt()+45);
        GLFW.glfwSetWindowTitle(handle,"FSRPG 完美格挡、敌人僵直与耗体验收 · DISPLAY5");game.resizeDisplay();
        initialized=true;started=System.nanoTime();
        game.getSingleplayerServer().execute(()->{
            try {
                ServerPlayer player=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                for(var entity:player.level().getEntities(player,player.getBoundingBox().inflate(40),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)))entity.discard();
                player.setGameMode(GameType.SURVIVAL);player.setInvulnerable(false);player.setHealth(20);player.getFoodData().setFoodLevel(20);
                player.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);player.level().setDayTime(18000);player.level().setWeatherParameters(6000,0,false,false);
                for(int x=-8;x<=8;x++)for(int z=-6;z<=12;z++){
                    player.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                    for(int y=80;y<85;y++)player.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
                }
                player.connection.teleport(.5,80,.5,0,0);player.setDeltaMovement(Vec3.ZERO);player.getInventory().clearContent();player.getInventory().setSelectedSlot(0);
                fixtureProgress(player);
                equip(player,"rapier");
                attacker=new Zombie(player.level());attacker.setPos(.5,80,3.5);attacker.setPersistenceRequired();attacker.setNoGravity(true);attacker.setNoAi(true);
                player.level().addFreshEntity(attacker);
                target=(Mob)EntityType.COW.create(player.level(),EntitySpawnReason.COMMAND);
                if(target==null)throw new AssertionError("Cannot create actual damage target");
                target.setPos(.5,80,2.5);target.setNoAi(true);target.setPersistenceRequired();
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);target.setHealth(200);player.level().addFreshEntity(target);
                ready=true;
            }catch(Throwable error){failure=error;}
        });
    }
    /** Change only the copied test player's progression; real server restrictions remain active. */
    private void fixtureProgress(ServerPlayer player) {
        var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);
        JsonObject saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject();JsonObject one=saved.getAsJsonObject("players").getAsJsonObject(playerId.toString());
        for(String name:List.of("mind","body","footwork")){JsonObject points=new JsonObject();points.addProperty("total",0);points.addProperty("available",0);one.add(name,points);one.addProperty(name+"Unlocked",false);}
        for(String name:List.of("rapierKills","dualHits","archeryScore","dualStacks","physiquePercent","insightBasePercent","bossInsightBonus"))one.addProperty(name,0);
        one.addProperty("migrated",true);one.addProperty("lastDecayDay",day);
        JsonArray learned=new JsonArray();for(String id:List.of("rapier","dual_sword","archery"))learned.add(id);one.add("learnedNodes",learned);
        service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
        check(service.weaponUnlocked(playerId,"rapier")&&service.weaponUnlocked(playerId,"dual_blade")&&service.weaponUnlocked(playerId,"bow_rapid"),"Copied fixture has real server-learned weapon roots");
    }
    private void equip(ServerPlayer player,String style) {
        actor(player).forceSheathed();
        Item main=style.equals("spear")?Items.DIAMOND_SPEAR:style.startsWith("bow_")?Items.BOW:Items.DIAMOND_SWORD;
        player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(main));player.setItemSlot(EquipmentSlot.OFFHAND,style.equals("dual_blade")?new ItemStack(Items.IRON_SWORD):ItemStack.EMPTY);
        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))player.setItemSlot(slot,ItemStack.EMPTY);
        check(actor(player).setStyle(rpg().config().current().styles().get(style))==ActionResult.ACCEPTED,"Actual server fixture selects "+style);
        action(player,"status","");actor(player).setResources(100,100);
    }
    private void heldGuard(boolean held) {
        Minecraft.getInstance().execute(()->{
            for(var mapping:Minecraft.getInstance().options.keyMappings)if(mapping.getName().equals("key.fsrpg.guard"))mapping.setDown(held);
        });
    }
    private void left(ServerPlayer player,String style) {
        if(style.equals("spear")||style.equals("dual_blade")){
            var request=new JsonObject();request.addProperty("button","left");request.add("aim",RpgConfig.JSON.toJsonTree(AttackAim.at(0,0)));
            action(player,"aimAttack",request.toString());
        }else action(player,"attack","left");
    }

    private void serverTick(ServerTickEvent.Post event) {
        if(!initialized||!ready||done||scriptDone||failure!=null)return;
        try {
            ServerPlayer player=event.getServer().getPlayerList().getPlayer(playerId);if(player==null)return;serverTicks++;
            CombatActor actor=actor(player);
            switch(stage){
                case 0->{
                    if(actor.phase()!=CombatPhase.READY)return;
                    actor.setResources(100,100);action(player,"guard","true");heldGuard(true);
                    healthBefore=player.getHealth();player.invulnerableTime=0;enemyTickBefore=enemyCompletedTicks;perfectGameTick=player.level().getGameTime();
                    attacker.setNoAi(false);
                    check(!attacker.isNoAi(),"Stun target has live AI before the real melee event");
                    attacker.doHurtTarget(player.level(),player);
                    near(healthBefore,player.getHealth(),"Actual mob melee triggers perfect guard without player health loss");
                    check(actor.counterRemainingTicks()==4,"Real successful guard grants four server counter ticks");
                    check(rpg().perfectGuardStunRemaining(attacker)==4,"Real attacking NPC immediately receives four-tick stun");
                    screenshots.add("01-perfect-guard");stage=1;
                }
                case 1->{
                    long elapsed=player.level().getGameTime()-perfectGameTick;int remaining=rpg().perfectGuardStunRemaining(attacker);
                    JsonObject row=new JsonObject();row.addProperty("elapsedGameTicks",elapsed);row.addProperty("remaining",remaining);row.addProperty("npcNativeAgeTicks",attacker.tickCount);row.addProperty("npcCompletedWorkTicks",enemyCompletedTicks);row.addProperty("npcNoAi",attacker.isNoAi());stuns.add(row);
                    check(remaining==Math.max(0,4-elapsed),"NPC stun exact remaining at elapsed "+elapsed);
                    if(elapsed<4)check(enemyCompletedTicks==enemyTickBefore,"NPC actual tick/AI remains paused at elapsed "+elapsed);
                    if(elapsed==1){
                        targetBefore=target.getHealth();target.invulnerableTime=0;actor.setResources(100,100);left(player,"rapier");
                        near(1.5,actor.attackDamageMultiplier(),"Actual left dispatch starts one enhanced rapier attack");
                        check(actor.currentSkill()!=null,"Counter has real authoritative action");
                        counterDuration=actor.currentSkill().durationTicks();counterActorTick=actor.snapshot().serverTick();
                        expectedCounterDamage=(actor.currentSkill().damage()+rpg().progression().effects(playerId).damageBonus())*1.5*rpg().config().current().weapons().damageMultiplier("minecraft:diamond_sword");
                    }
                    if(elapsed==2||elapsed==3){
                        long sequence=actor.snapshot().swingSequence();action(player,"guard","true");
                        check(actor.currentSkill()!=null&&actor.snapshot().swingSequence()==sequence,"Stale held-C renewal cannot cancel actual counter at elapsed "+elapsed);
                        near(1.5,actor.attackDamageMultiplier(),"Actual counter retains multiplier under held-C renewal");
                        if(elapsed==2){
                            float health=player.getHealth();player.invulnerableTime=0;attacker.doHurtTarget(player.level(),player);
                            near(health,player.getHealth(),"Even an explicit real mob melee attempt cannot deal damage during enemy stun");
                            screenshots.add("02-left-counter");
                        }
                    }
                    if(elapsed==4){check(enemyCompletedTicks>enemyTickBefore,"NPC actual AI/entity tick resumes at exact fourth tick");attacker.setPos(.5,80,30.5);attacker.setNoAi(true);stage=2;}
                }
                case 2->{
                    if(actor.currentSkill()!=null){action(player,"guard","true");return;}
                    check(actor.snapshot().serverTick()-counterActorTick==counterDuration,"Held-C counter plays every configured windup/contact/recovery tick");
                    near(expectedCounterDamage,targetBefore-target.getHealth(),"Actual counter target loses exactly 1.5 times ordinary unarmored attack damage");
                    actor.setResources(100,100);left(player,"rapier");near(1,actor.attackDamageMultiplier(),"Real next left attack has ordinary multiplier");
                    heldGuard(false);action(player,"guard","false");prepared=false;stage=3;
                }
                case 3->{
                    if(costIndex>=costStyles.length){target.discard();attacker.discard();equip(player,"bow_rapid");player.getInventory().setItem(9,new ItemStack(Items.ARROW,64));stage=4;return;}
                    String style=costStyles[costIndex];if(!prepared){equip(player,style);prepared=true;return;}
                    if(actor.phase()!=CombatPhase.READY)return;
                    actor.setResources(100,100);double before=actor.stamina();left(player,style);
                    check(actor.currentSkill()!=null,"Real "+style+" left starts authoritative action");near(meleeCosts[costIndex],before-actor.stamina(),"Real "+style+" resource debit");
                    JsonObject row=new JsonObject();row.addProperty("style",style);row.addProperty("skill",actor.currentSkill().id());row.addProperty("staminaSpent",before-actor.stamina());row.addProperty("durationTicks",actor.currentSkill().durationTicks());melee.add(row);
                    if(style.equals("spear")){check(actor.currentSkill().durationTicks()==4,"Real point thrust is exactly 0.2 seconds");screenshots.add("03-spear-200ms");}
                    costIndex++;prepared=false;
                }
                case 4->bowStep(player);
                default->throw new AssertionError("Unknown combat smoke stage "+stage);
            }
        }catch(Throwable error){failure=error;}
    }

    private ArcheryProgress bowProgress(ServerPlayer player){var value=rpg().progression().archeryView(playerId);var effect=rpg().progression().effects(playerId);return new ArcheryProgress(value.unlocked(),value.score(),ArcheryProgress.Branch150.valueOf(value.branch150()),ArcheryProgress.Branch250.valueOf(value.branch250()),effect.damageBonus(),effect.bowDrawFactor());}
    private int ammo(ServerPlayer player){int count=0;for(int i=0;i<player.getInventory().getContainerSize();i++){ItemStack stack=player.getInventory().getItem(i);if(stack.is(Items.ARROW))count+=stack.getCount();}return count;}
    private int arrows(ServerPlayer player){return player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(128),arrow->arrow.getOwner()==player).size();}
    private void bowStep(ServerPlayer player){
        CombatActor actor=actor(player);if(actor.phase()!=CombatPhase.READY)return;
        if(bowIndex>=bowStyles.length){scriptDone=true;return;}
        String style=bowStyles[bowIndex];
        if(!bowDrawing){
            if(!actor.style().id().equals(style))action(player,"weaponStyle",style);
            actor.setResources(100,100);action(player,"archeryCharge","true");var snapshot=rpg().archery().snapshot(player,bowProgress(player));
            check(snapshot.charging(),"Real server bow draw starts for "+style+" case "+bowIndex);
            boolean full=bowIndex==0||bowIndex==2;
            chargeHold=bowIndex==4?0:full?(int)Math.ceil(snapshot.requiredDrawTicks()):Math.max(1,(int)Math.floor(snapshot.requiredDrawTicks()*.5));
            chargeGameTick=player.level().getGameTime();bowDrawing=true;
            if(chargeHold>0)return;
        }
        if(player.level().getGameTime()-chargeGameTick<chargeHold)return;
        var snapshot=rpg().archery().snapshot(player,bowProgress(player));
        if(bowIndex==5)actor.setResources(3.49,100);
        double before=actor.stamina();int ammoBefore=ammo(player),arrowsBefore=arrows(player);
        action(player,"archeryCharge","false");
        boolean released=bowIndex<4;near(bowCosts[bowIndex],before-actor.stamina(),"Real bow release stamina case "+bowIndex);
        check(ammo(player)==ammoBefore-(released?1:0),"Real bow ammo debit matches successful release case "+bowIndex);
        check(arrows(player)==arrowsBefore+(released?1:0),"Real vanilla arrow spawn matches release case "+bowIndex);
        if(bowIndex==4)check(snapshot.chargeProgress()<rpg().archery().rules().minReleasePower(),"Original minimum draw-power gate remains enforced");
        if(bowIndex==5)check(snapshot.chargeProgress()>=rpg().archery().rules().minReleasePower(),"Insufficient-stamina rejection uses an otherwise valid partial draw");
        JsonObject row=new JsonObject();row.addProperty("style",style);row.addProperty("case",bowIndex);row.addProperty("power",snapshot.chargeProgress());row.addProperty("requiredDrawTicks",snapshot.requiredDrawTicks());row.addProperty("staminaSpent",before-actor.stamina());row.addProperty("ammoUsed",ammoBefore-ammo(player));row.addProperty("newActualArrows",arrows(player)-arrowsBefore);shots.add(row);
        bowDrawing=false;bowIndex++;
    }

    private void tick(ClientTickEvent.Post event){
        Minecraft game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++>=20)initialize(game);return;}
            if(failure!=null)throw new IllegalStateException("Server validation failed",failure);
            game.getFramerateLimitTracker().onInputReceived();
            if(System.nanoTime()-started>30_000_000_000L)throw new AssertionError("Combat balance review exceeded thirty seconds");
            if(scriptDone&&screenshots.isEmpty()&&pendingScreenshots.get()==0){check(capturedScreenshots.get()>=3,"Three actual framebuffer screenshots saved");check(sawCounterHint&&counterPackets.size()>0,"Authoritative counter-window packet reached the client HUD state");finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void render(RenderFrameEvent.Post event){
        if(done||!initialized)return;nativeFrames++;
        try{String name;while((name=screenshots.poll())!=null){pendingScreenshots.incrementAndGet();Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->{capturedScreenshots.incrementAndGet();pendingScreenshots.decrementAndGet();});}}catch(Throwable error){failure=error;}
    }
    private void finish(Throwable error){
        if(done)return;done=true;
        try{snapshotObserver.close();}catch(Exception closeError){if(error==null)error=closeError;}
        try{
            JsonObject report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("copiedWorld",System.getProperty("fsrpg.combatBalanceWorld"));
            report.addProperty("elapsedSeconds",(System.nanoTime()-started)/1e9);report.addProperty("serverTicks",serverTicks);report.addProperty("nativeFrames",nativeFrames);report.addProperty("screenshots",capturedScreenshots.get());
            report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("npcStunTicks",stuns);report.add("meleeDebits",melee);report.add("bowReleases",shots);report.add("receivedCounterWindowPackets",counterPackets);RpgSmoke.windowEnvironment(report);
            Path jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",Files.isRegularFile(jar)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("combat-balance-review.json"),RpgConfig.JSON.toJson(report));System.out.println("COMBAT BALANCE REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reportError){reportError.printStackTrace();}
        Minecraft game=Minecraft.getInstance();KeyMapping.releaseAll();
        if(initialized){game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);game.options.guiScale().set(previousScale);game.options.pauseOnLostFocus=previousPause;game.options.enableVsync().set(previousVsync);game.options.hideGui=previousHidden;game.options.setCameraType(previousCamera);game.setWindowActive(previousActive);}
        game.stop();
    }
}

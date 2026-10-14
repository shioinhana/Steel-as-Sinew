package org.fsrpg.smoke;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.*;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.archery.*;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.progression.*;
import org.lwjgl.glfw.GLFW;

/** Real client input and integrated-server validation; only a marked, explicitly named copy. */
final class LegacySkillReview {
    private static final Path ROOT=Path.of(System.getProperty("fsrpg.smokeEvidence","../../verification/legacy-skills-20261012/game")).toAbsolutePath().normalize();
    private static final List<String> SCENES=List.of("learning-and-persistence","tree-rapier","tree-dual","tree-bow",
            "rapier-four-cuts-tp","rapier-four-cuts-fp","thrust-charge-tp","thrust-cancel-fp","thrust-parry-tp",
            "dual-four-hands-tp","dual-four-hands-fp","bow-two-stances-tp","bow-scope-and-auto-fp","bow-branch-effects");
    private final Path directory=ROOT.resolve("run-"+Instant.now().toString().replace(':','-'));
    private final RenderFrameCapture movies=new RenderFrameCapture(directory);
    private final List<String> checks=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final JsonArray images=new JsonArray(),started=new JsonArray(),thrustGeometry=new JsonArray();
    private final Queue<String> screenshots=new ConcurrentLinkedQueue<>();
    private final Set<String> observedSkills=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Throwable failure;
    private volatile boolean ready;
    private volatile boolean awaitingCombatReady;
    private int combatReadyTicks;
    private String fixtureStyle="";
    private boolean initialized,done,sceneStarted,captureStarted;
    private int warmup,scene=-1,elapsed,previousScale,previousFps,previousDistance;
    private boolean previousPause,previousHidden,previousVsync;
    private CameraType previousCamera;
    private CameraType beforeScopeCamera;
    private long beforeTrajectorySamples,beforeLandingMarkers;
    private UUID playerId;
    private Path world;
    private volatile Zombie target;
    private volatile AbstractArrow piercedArrow;
    private volatile int minimumBlockCharges=3;
    private volatile float targetBefore,playerBefore;
    private volatile int ammoBefore,durabilityBefore,scoreBefore,dualScoreBefore;
    private volatile long lastSwing=-1;
    private volatile int comboRequests;
    private volatile boolean chain;
    private JsonObject fullFixture;

    LegacySkillReview() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event)->fixedAim());
        NeoForge.EVENT_BUS.addListener(this::tick); NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event)->{
            if(done||!initialized||scene!=6)return;
            var p=event.getServer().getPlayerList().getPlayer(playerId);
            if(p!=null&&actor(p).phase()==CombatPhase.ACTIVE)recordThrustGeometry(p,"server-pre-active");
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private void check(boolean ok,String text) { if(!ok)throw new AssertionError(text); checks.add(text); System.out.println("LEGACY SKILL PASS "+text); }
    private void server(Consumer<ServerPlayer> work) {
        var game=Minecraft.getInstance(); game.getSingleplayerServer().execute(()->{
            try { var p=game.getSingleplayerServer().getPlayerList().getPlayer(playerId);if(p==null)throw new IllegalStateException("Missing smoke player");work.accept(p); }
            catch(Throwable error) { failure=error; }
        });
    }
    private RpgServer rpg() { return RpgServer.instance(); }
    private CombatActor actor(ServerPlayer p) { return RpgSmoke.actor(p); }
    private void action(ServerPlayer p,String verb,String argument) { rpg().action(p,verb,argument); }
    private ArcheryProgress bowProgress(ServerPlayer p) {
        var b=rpg().progression().archeryView(p.getUUID()); var e=rpg().progression().effects(p.getUUID());
        return new ArcheryProgress(b.unlocked(),b.score(),ArcheryProgress.Branch150.valueOf(b.branch150()),ArcheryProgress.Branch250.valueOf(b.branch250()),e.damageBonus(),e.bowDrawFactor());
    }
    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(directory.resolve("screenshots")); var monitor=new JsonObject();RpgSmoke.windowEnvironment(monitor);
        check(monitor.get("monitorAdapter").getAsString().equals("\\\\.\\DISPLAY5"),"Actual game window is on DISPLAY5");
        world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String configured=System.getProperty("fsrpg.legacySkillWorld","");
        check(configured.startsWith("FSRPG Legacy Skill Review ")&&world.getFileName().toString().equals(configured),"Only the named legacy-skill world copy is modified");
        check(Files.isRegularFile(world.resolve(".fsrpg-legacy-skill-test-world")),"World copy has its development-only marker");
        playerId=game.player.getUUID();previousScale=game.options.guiScale().get();previousFps=game.options.framerateLimit().get();
        previousDistance=game.options.renderDistance().get();previousPause=game.options.pauseOnLostFocus;previousHidden=game.options.hideGui;
        previousVsync=game.options.enableVsync().get();previousCamera=game.options.getCameraType();
        game.options.pauseOnLostFocus=false;game.options.hideGui=false;game.options.framerateLimit().set(60);
        game.options.enableVsync().set(false);game.options.guiScale().set(2);game.options.renderDistance().set(4);game.resizeDisplay();
        game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();
        GLFW.glfwSetWindowTitle(game.getWindow().handle(),"FSRPG 旧版技能与动作迁移验收 · DISPLAY5");
        initialized=true;
        server(p->{
            p.setGameMode(GameType.SURVIVAL);p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);
            p.level().setDayTime(18000);p.level().setWeatherParameters(6000,0,false,false);
            for(int x=-10;x<=10;x++)for(int z=-6;z<=14;z++) {
                p.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                for(int y=80;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
            }
            for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
            p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.getInventory().clearContent();
            // Re-runs use the same marked copy; retained targets must not intercept the new target's hits.
            var oldTargets=p.level().getEntitiesOfClass(LivingEntity.class,new AABB(-10,79,-6,11,85,15),e->!(e instanceof net.minecraft.world.entity.player.Player));
            oldTargets.forEach(LivingEntity::discard);check(true,"Cleared "+oldTargets.size()+" retained living arena targets from this marked world copy");
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,12000,0,false,false,false));
            ready=true;
        });
    }
    private JsonObject savedPlayer(JsonObject snapshot) { return snapshot.getAsJsonObject("players").getAsJsonObject(playerId.toString()); }
    private void fixture(ServerPlayer p) {
        var service=rpg().progression();long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();
        service.initializeAptitudes(playerId,0,0,day);service.onSleep(playerId);
        var saved=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject();var one=savedPlayer(saved);
        for(String key:List.of("mind","body","footwork")) { var points=new JsonObject();points.addProperty("total",300);points.addProperty("available",300);one.add(key,points);one.addProperty(key+"Unlocked",true); }
        one.addProperty("rapierKills",350);one.addProperty("dualHits",500);one.addProperty("archeryScore",350);
        one.addProperty("trialPassed",true);one.addProperty("dragonKilled",true);one.addProperty("insightBasePercent",0);one.addProperty("bossInsightBonus",0);
        one.add("learnedNodes",new JsonArray());one.addProperty("lastDecayDay",day);var growth=new JsonObject();
        for(String key:List.of("rapier","dual_blade","bow"))growth.addProperty(key,day);one.add("growthDays",growth);
        service.restore(RpgConfig.JSON.fromJson(saved,ProgressionService.Snapshot.class));
        check(!service.weaponUnlocked(playerId,"rapier"),"Fresh fixture cannot use an unlearned rapier tree");
        for(String node:List.of("rapier","dual_sword","archery")) {
            var before=service.view(playerId);var definition=service.catalog().node(node);
            action(p,"learnSkill",node);var after=service.view(playerId);
            check(service.unlocked(playerId,node),"Authoritative learn action unlocks "+node);
            check(after.mind().available()==before.mind().available()-definition.cost().mind()
                    &&after.body().available()==before.body().available()-definition.cost().body()
                    &&after.footwork().available()==before.footwork().available()-definition.cost().footwork(),"Learning deducts exactly configured costs: "+node);
            action(p,"learnSkill",node);check(service.view(playerId).mind().available()==after.mind().available(),"Duplicate learning does not spend twice: "+node);
        }
        check(service.effects(playerId).rapierThrust()&&service.effects(playerId).rapierParry()&&service.effects(playerId).rapierExecution(),"Legacy rapier milestones unlock from server totals, kills and dragon requirement");
        check(service.effects(playerId).dualComboCap()>1,"Legacy dual tiers unlock from actual required totals and trial flag");
        action(p,"learnSkill","archery_ru_suo");check(service.archeryView(playerId).branch150().equals("RU_SUO"),"150 branch selection is authoritative");
        var branchBefore=service.snapshot();action(p,"learnSkill","archery_nie_shi");
        check(service.archeryView(playerId).branch150().equals("RU_SUO"),"150 mutually exclusive branch cannot be learned after its peer");
        check(service.view(playerId).learnedNodes().equals(branchBefore.players().get(playerId).learnedNodes()),"Rejected branch choice preserves learned nodes");
        action(p,"learnSkill","archery_yan_xia_dian");action(p,"learnSkill","archery_chuan_tang_feng");
        check(service.archeryView(playerId).branch250().equals("YAN_XIA_DIAN"),"250 mutually exclusive branch preserves first selection");
        try {
            var store=new ProgressionStore(directory.resolve("progression-roundtrip.json"),RpgConfig.JSON);store.save(service.snapshot());
            var fresh=new ProgressionService(service.catalog());fresh.restore(store.load());
            check(fresh.view(playerId).equals(service.view(playerId)),"Skill tree totals, branches and qualifications survive a real file roundtrip");
        } catch(Exception error) { throw new RuntimeException(error); }
        fullFixture=RpgConfig.JSON.toJsonTree(service.snapshot()).getAsJsonObject();action(p,"status","");
    }
    private Zombie target(ServerPlayer p,double distance,float health) {
        if(target!=null)target.discard();var mob=new Zombie(p.level());mob.setNoAi(true);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Math.max(20,health));mob.setHealth(health);mob.setPos(.5,80,.5+distance);
        mob.getPersistentData().putBoolean("fsrpgLegacySkillTarget",true);
        p.level().addFreshEntity(mob);target=mob;targetBefore=health;return mob;
    }
    private void equip(ServerPlayer p,Item main,Item off,String style,float health) {
        if(target!=null)target.discard();p.connection.teleport(.5,80,.5,0,0);p.setDeltaMovement(Vec3.ZERO);p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(main));p.setItemSlot(EquipmentSlot.OFFHAND,off==Items.AIR?ItemStack.EMPTY:new ItemStack(off));
        actor(p).forceSheathed();
        check(actor(p).setStyle(rpg().config().current().styles().get(style))==ActionResult.ACCEPTED,"Copied-world equipment fixture selects "+style);
        fixtureStyle=style;action(p,"status","");
        actor(p).setResources(actor(p).rules().maxStamina(),actor(p).rules().maxSpirit());
        if(health>0)target(p,2.5,health);observedSkills.clear();lastSwing=-1;
    }
    private void setup(ServerPlayer p,int s) {
        chain=false;comboRequests=0;
        switch(s) {
            case 0 -> fixture(p);
            case 4,5,6,7,8 -> equip(p,Items.DIAMOND_SWORD,Items.AIR,"rapier",250);
            case 9,10 -> {equip(p,Items.DIAMOND_SWORD,Items.IRON_SWORD,"dual_blade",500);dualScoreBefore=rpg().progression().view(playerId).dualHits();}
            case 11 -> {
                var data=fullFixture.deepCopy();var one=savedPlayer(data);one.addProperty("archeryScore",0);
                var learned=one.getAsJsonArray("learnedNodes");var keep=new JsonArray();for(var id:learned)if(!id.getAsString().startsWith("archery_"))keep.add(id);one.add("learnedNodes",keep);
                rpg().progression().restore(RpgConfig.JSON.fromJson(data,ProgressionService.Snapshot.class));
                equip(p,Items.BOW,Items.AIR,"bow_rapid",0);p.getInventory().setItem(9,new ItemStack(Items.ARROW,64));target(p,7,150);
                ammoBefore=64;durabilityBefore=0;scoreBefore=0;action(p,"status","");
            }
            case 12 -> {
                rpg().progression().restore(RpgConfig.JSON.fromJson(fullFixture,ProgressionService.Snapshot.class));
                equip(p,Items.BOW,Items.AIR,"bow_rapid",0);p.getInventory().setItem(9,new ItemStack(Items.ARROW,64));target(p,7,500);ammoBefore=64;
                // A real wall gives the prediction an unambiguous block landing beyond the live target.
                for(int x=-2;x<=2;x++)for(int y=80;y<=82;y++)p.level().setBlock(new BlockPos(x,y,14),Blocks.STONE.defaultBlockState(),3);
            }
            case 13 -> branchEffects(p);
            default -> action(p,"status","");
        }
        combatReadyTicks=0;awaitingCombatReady=s>=4;
        ready=!awaitingCombatReady;
    }
    private void mouse(int button,boolean down,boolean click) {
        var key=InputConstants.Type.MOUSE.getOrCreate(button);KeyMapping.set(key,down);if(click)KeyMapping.click(key);
    }
    private void click(int button) { mouse(button,false,true); }
    private void key(int code) { var k=InputConstants.Type.KEYSYM.getOrCreate(code);KeyMapping.click(k); }
    /** The input fixture aims along +Z; GUI cursor re-capture and physical mouse movement are not test input. */
    private void fixedAim() {
        var game=Minecraft.getInstance();
        if(done||!initialized||scene<4||game.player==null)return;
        game.player.setYRot(0);game.player.setXRot(0);game.player.yRotO=0;game.player.xRotO=0;
        game.player.yHeadRot=0;game.player.yHeadRotO=0;game.player.yBodyRot=0;game.player.yBodyRotO=0;
        if(scene==9||scene==10)CombatCamera.reset();
    }
    private void screen(String tree,String selection) throws Exception {
        var g=Minecraft.getInstance();g.setScreen(new SkillTreeScreen());
        for(var child:List.copyOf(g.screen.children()))if(child instanceof Button b&&b.getMessage().getString().contains(tree)) {b.onPress(new net.minecraft.client.input.MouseButtonEvent(0,0,new net.minecraft.client.input.MouseButtonInfo(0,0)));break;}
        var field=SkillTreeScreen.class.getDeclaredField("selected");field.setAccessible(true);field.set(g.screen,selection);
        var method=SkillTreeScreen.class.getDeclaredMethod("rebuild");method.setAccessible(true);method.invoke(g.screen);
    }
    private void startCapture(String id) throws Exception {
        var g=Minecraft.getInstance();var sample=RenderObservations.sampler(g.player);
        movies.start(id,"actual legacy migration client input and authoritative server",()->{
            var j=sample.get();j.addProperty("camera",g.options.getCameraType().name());j.add("archery",ClientState.object(ClientState.snapshot(),"archery"));return j;
        });captureStarted=true;
    }
    private void at(int t,int s) throws Exception {
        var game=Minecraft.getInstance();String name=SCENES.get(s);
        if(s>=1&&s<=3) {
            if(t==2)screen(s==1?"西洋剑":s==2?"双刀":"弓",s==1?"rapier_parry_thrust":s==2?"dual_trial":"archery_ru_suo");
            if(t==20)screenshots.add(name+"-detail");
            if(s==3&&t==35){screen("弓","archery_nie_shi");screenshots.add(name+"-exclusive-detail");}
            return;
        }
        if(s>=4&&s<=12&&t==2) {
            game.setScreen(null);game.options.setCameraType(s==5||s==7||s==10||s==12?CameraType.FIRST_PERSON:CameraType.THIRD_PERSON_BACK);
            startCapture(name);
        }
        if((s==4||s==5)&&t==7) {chain=true;click(0);}
        if((s==4||s==5)&&t==65) {
            chain=false;server(p->{check(observedSkills.containsAll(Set.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust")),"Real client clicks executed all four migrated rapier cuts: "+name);check(target.getHealth()<targetBefore,"Actual four-cut combat damaged the real hostile target: "+name);});
        }
        if((s==4||s==5)&&t==30)screenshots.add(name+"-active");
        if(s==6) {
            if(t==7)mouse(1,true,true);
            if(t==27)server(p->{check(actor(p).chargeSnapshot().fullyCharged(),"Server reaches sixteen-tick full thrust charge");check(target.getHealth()==targetBefore,"Held thrust windup cannot damage a real target");});
            if(t==28)screenshots.add("thrust-charge-tp-full");
            if(t==31)mouse(1,false,false);
            if(t==35)screenshots.add("thrust-charge-tp-release");
            if(t==37){game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);screenshots.add("thrust-charge-tp-front");}
            if(t==40)game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if(t==55)server(p->{
                var context=thrustContext(p,"damage-assertion");
                recordThrustGeometry(p,"damage-assertion");
                if(target.getHealth()>=targetBefore)throw new AssertionError("Released charged thrust applies actual server damage; context="+context);
                check(true,"Released charged thrust applies actual server damage");
            });
        }
        if(s==7) {
            if(t==7)mouse(1,true,true);
            if(t==10)click(0);
            if(t==12)mouse(1,false,false);
            if(t==15)server(p->{check(!actor(p).chargeSnapshot().holding(),"Different client left attack cancels thrust preparation");check(actor(p).cooldownRemaining("sword_thrust")==0,"Cancelling before committing thrust does not begin its cooldown");});
            if(t==18)screenshots.add(name+"-cancelled");
            if(t==30)mouse(1,true,true);
            if(t==35)server(p->{check(actor(p).chargeSnapshot().holding()&&actor(p).phase()==CombatPhase.WINDUP,"Real client begins a second held preparation before opening P");targetBefore=target.getHealth();});
            if(t==37)key(GLFW.GLFW_KEY_P);
            if(t==40)screenshots.add(name+"-gui-cancelled");
            if(t==41){check(game.screen instanceof SkillTreeScreen,"Actual P input opens the skill tree during thrust preparation");server(p->{check(!actor(p).chargeSnapshot().active()&&actor(p).currentSkill()==null,"Opening a real GUI cancels server thrust preparation without releasing it");check(actor(p).cooldownRemaining("sword_thrust")==0,"GUI cancellation leaves the uncommitted thrust cooldown empty");});}
            if(t==42)mouse(1,false,false);
            if(t==44)game.setScreen(null);
            if(t==47)server(p->check(target.getHealth()==targetBefore&&actor(p).cooldownRemaining("sword_thrust")==0,"Closing the GUI and later releasing the right mouse button cannot resurrect a cancelled thrust"));
        }
        if(s==8) {
            if(t==7)mouse(1,true,true);
            if(t==11)server(p->{playerBefore=p.getHealth();p.invulnerableTime=0;p.hurtServer(p.level(),p.damageSources().mobAttack(target),4);check(p.getHealth()==playerBefore,"Real hostile damage during unfilled thrust is parried");check(actor(p).chargeSnapshot().parried()&&actor(p).chargeSnapshot().fullyCharged(),"Parry promotes charge to full on server");});
            if(t==16)screenshots.add(name+"-parried-full");
            if(t==18)server(p->{p.invulnerableTime=0;p.hurtServer(p.level(),p.damageSources().mobAttack(target),4);check(p.getHealth()<playerBefore,"A second hit against full charged guard breaks through");check(actor(p).cooldownRemaining("sword_thrust")>150,"Full-charge break applies the long legacy cooldown");});
            if(t==20)mouse(1,false,false);
        }
        if(s==9||s==10) {
            if(t==7||t==20)click(0);
            if(t==33||t==46)click(1);
            if(t==61)server(p->{check(observedSkills.containsAll(Set.of("dual_ll","dual_rl","dual_rr","dual_lr")),"Legacy directional controls route to all four distinct hand attacks: "+name);check(rpg().progression().view(playerId).dualHits()>dualScoreBefore,"Only actual dual hits grow the skill-tree score: "+name);});
            if(t==65)click(0); // LL follows LR: second consecutive swing of the left hand.
            if(t==78)click(1); // With the right hand already on the right, this routes back through LR.
            if(t==82)server(p->{
                var actual=actor(p).currentSkill();var authored=rpg().config().current().skills().get("dual_lr");
                int expected=rpg().progression().effects(playerId).attackIntervalTicks(authored.windupTicks()+authored.activeTicks()+3);
                check(actual!=null&&actual.id().equals("dual_lr")&&actual.windupTicks()+actual.activeTicks()==expected,"Real third consecutive same-hand cut applies the twelve-tick legacy gate before body reduction: "+name);
            });
            if(t==92){mouse(0,true,false);mouse(1,true,false);}
            if(t==94)server(p->check(actor(p).currentSkill()!=null&&actor(p).currentSkill().id().equals("dual_combined"),"Both held client buttons trigger the migrated linked slash: "+name));
            if(t==95)screenshots.add(name+"-linked");
            if(t==100){mouse(0,false,false);mouse(1,false,false);}
        }
        if(s==11) {
            if(t==5)server(p->check(actor(p).drawn()&&actor(p).style().id().equals("bow_rapid"),"Equipped bow automatically enters rapid drawn stance"));
            if(t==7)mouse(1,true,false);
            if(t==22)mouse(1,false,false);
            if(t==28)server(p->{check(p.getMainHandItem().getDamageValue()==1,"Actual rapid release costs one bow durability");check(count(p,Items.ARROW)==63,"Rapid release consumes exactly one real arrow");check(target.getHealth()<targetBefore,"Real rapid projectile damages hostile target");targetBefore=target.getHealth();});
            if(t==31)mouse(1,true,false);
            if(t==33)server(p->check(rpg().archery().snapshot(p,bowProgress(p)).charging(),"Actual client begins bow charge before opening P"));
            if(t==37)key(GLFW.GLFW_KEY_P);
            if(t==40)screenshots.add(name+"-gui-cancelled");
            if(t==41){check(game.screen instanceof SkillTreeScreen,"Actual P input opens the skill tree during bow charge");server(p->{check(!rpg().archery().snapshot(p,bowProgress(p)).charging(),"Opening a real GUI cancels server bow charge");check(count(p,Items.ARROW)==63&&p.getMainHandItem().getDamageValue()==1,"GUI bow cancellation does not consume arrows or durability");});}
            if(t==42)mouse(1,false,false);
            if(t==44)game.setScreen(null);
            if(t==47)server(p->check(count(p,Items.ARROW)==63&&p.getMainHandItem().getDamageValue()==1&&target.getHealth()==targetBefore,"Closing the GUI and later releasing the bow cannot fire a cancelled arrow"));
            if(t==50)key(GLFW.GLFW_KEY_R);
            if(t==56)server(p->check(actor(p).style().id().equals("bow_snipe"),"R client key switches rapid into sniper stance"));
            if(t==57)mouse(1,true,false);
            if(t==86)screenshots.add(name+"-snipe-full");
            if(t==89)mouse(1,false,false);
            if(t==96)server(p->{check(p.getMainHandItem().getDamageValue()==4,"Actual sniper release costs exactly three additional durability");check(count(p,Items.ARROW)==62,"Sniper release consumes one additional real arrow");check(target.getHealth()<targetBefore,"Real sniper projectile applies damage");target(p,7,1);scoreBefore=rpg().progression().view(playerId).archeryScore();});
            if(t==98)key(GLFW.GLFW_KEY_R);
            if(t==103)mouse(1,true,false);
            if(t==115)mouse(1,false,false);
            if(t==123)server(p->{check(actor(p).style().id().equals("bow_rapid"),"Next R cycles sniper back to rapid");check(!target.isAlive(),"A tagged bow projectile performs an actual hostile kill");check(rpg().progression().view(playerId).archeryScore()>scoreBefore,"Only actual hostile bow kill triggers authoritative mastery growth");});
        }
        if(s==12) {
            if(t==49){game.options.setCameraType(CameraType.THIRD_PERSON_BACK);beforeScopeCamera=game.options.getCameraType();beforeTrajectorySamples=ArcheryPresentation.trajectorySamples();beforeLandingMarkers=ArcheryPresentation.landingMarkers();}
            if(t==7){mouse(0,true,true);}
            if(t==37){mouse(0,false,false);screenshots.add(name+"-auto");}
            if(t==42)server(p->check(count(p,Items.ARROW)<ammoBefore-1,"Actual held left automatic input fires several real arrows after the fifty-score gate"));
            if(t==44)key(GLFW.GLFW_KEY_R);
            if(t==51)mouse(1,true,false);
            if(t==78){
                check(ArcheryPresentation.scoped()&&game.options.getCameraType()==CameraType.FIRST_PERSON,"Actual scoped client camera switches into first person");
                check(ArcheryPresentation.magnification()>1&&ArcheryPresentation.lastFov()>0&&ArcheryPresentation.lastFov()<game.options.fov().get(),"Actual scope rendering reduces the viewport field of view");
                check(ArcheryPresentation.trajectorySamples()>beforeTrajectorySamples&&ArcheryPresentation.landingMarkers()>beforeLandingMarkers&&ArcheryPresentation.lastLanding()!=null,"Real trajectory rendering samples the world and creates a block landing marker");
                screenshots.add(name+"-scope");server(p->{var bow=rpg().archery().snapshot(p,bowProgress(p));check(bow.scopeMagnification()>1,"Three-hundred-score sniper scope follows actual server charge");check(bow.trajectoryUnlocked(),"Hundred-score trajectory is available from server mastery");});
            }
            if(t==82)mouse(1,false,false);
            if(t==93)check(!ArcheryPresentation.scoped()&&game.options.getCameraType()==beforeScopeCamera,"Released sniper shot restores the actual pre-scope camera");
        }
        if(s==13) {
            if(t==12)server(p->{
                target(p,9,200);piercedArrow=null;minimumBlockCharges=3;
                for(int z:List.of(2,4,6))for(int y=80;y<=82;y++)p.level().setBlock(new BlockPos(0,y,z),Blocks.STONE.defaultBlockState(),3);
            });
            if(t==20)mouse(1,true,false);
            if(t==29)mouse(1,false,false);
            if(t==40){screenshots.add(name+"-three-walls");server(p->{
                check(piercedArrow!=null&&piercedArrow.getPierceLevel()==127,"Chuan Tang Feng actual spawned arrow uses vanilla entity penetration");
                check(minimumBlockCharges==0,"Chuan Tang Feng actual arrow traverses three real stone impacts");
                check(target.getHealth()<targetBefore,"Actual penetrating projectile damages hostile beyond all three walls");
            });}
        }
    }
    private int count(ServerPlayer p,Item item) { int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n; }
    private void branchEffects(ServerPlayer p) {
        var data=fullFixture.deepCopy();var one=savedPlayer(data);var keep=new JsonArray();
        for(var id:one.getAsJsonArray("learnedNodes"))if(!id.getAsString().startsWith("archery_"))keep.add(id);one.add("learnedNodes",keep);
        rpg().progression().restore(RpgConfig.JSON.fromJson(data,ProgressionService.Snapshot.class));
        action(p,"learnSkill","archery_nie_shi");action(p,"learnSkill","archery_chuan_tang_feng");
        check(bowProgress(p).branch150()==ArcheryProgress.Branch150.NIE_SHI,"Alternate 150 branch activates in a separate copied fixture");
        check(bowProgress(p).branch250()==ArcheryProgress.Branch250.CHUAN_TANG_FENG,"Alternate 250 branch activates in a separate copied fixture");
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.POTATO));action(p,"status","");
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));action(p,"status","");
        check(actor(p).style().id().equals("bow_rapid"),"Taking the bow out again defaults to rapid even after a previous sniper stance");
        equip(p,Items.BOW,Items.AIR,"bow_rapid",0);p.getInventory().setItem(9,new ItemStack(Items.ARROW,64));
        // Only this marked world fixture removes our prior scene's projectiles; live skill behavior is unchanged.
        for(var previous:p.level().getEntitiesOfClass(AbstractArrow.class,p.getBoundingBox().inflate(32),arrow->arrow.getOwner()==p))previous.discard();
        piercedArrow=null;minimumBlockCharges=rpg().archery().rules().chuanTangFengBlockPierce();
        var hostile=target(p,6,20);var arrow=new Arrow(p.level(),hostile,new ItemStack(Items.ARROW),null);
        arrow.setPos(p.getX(),p.getEyeY(),p.getZ()+1);p.level().addFreshEntity(arrow);float before=p.getHealth();int ammo=count(p,Items.ARROW);
        p.invulnerableTime=0;p.hurtServer(p.level(),p.damageSources().arrow(arrow,hostile),4);
        check(p.getHealth()==before,"Nie Shi cancels actual incoming projectile damage");
        check(count(p,Items.ARROW)==ammo+1&&arrow.isRemoved(),"Captured real arrow is transferred once and discarded");
        try {
            Path saved=directory.resolve("archery-roundtrip.json");rpg().archery().save(saved);
            var fresh=new ArcheryServer(rpg().archery().rules(),new ArcheryServer.Sink(){});fresh.restore(saved);
            Path reloaded=directory.resolve("archery-reloaded.json");fresh.save(reloaded);
            check(JsonParser.parseString(Files.readString(saved)).equals(JsonParser.parseString(Files.readString(reloaded))),"Transient branch charge and capture cooldown survive an exact real file roundtrip");
        } catch(Exception error) { throw new RuntimeException(error); }
        action(p,"status","");
    }
    private void serverTick(ServerTickEvent.Post event) {
        if(done||!initialized)return;
        try {
            var p=event.getServer().getPlayerList().getPlayer(playerId);if(p==null)return;var a=actor(p);var snapshot=a.snapshot();
            if(scene==6&&a.currentSkill()!=null&&snapshot.skillElapsedTicks()>=a.currentSkill().windupTicks()-1
                    &&snapshot.skillElapsedTicks()<=a.currentSkill().windupTicks()+a.currentSkill().activeTicks())recordThrustGeometry(p,"server-post-transition");
            if(awaitingCombatReady) {
                if(a.drawn()&&a.phase()==CombatPhase.READY&&a.style()!=null&&a.style().id().equals(fixtureStyle)) {
                    check(true,"Actual server confirms drawn READY stance before combat scene "+SCENES.get(scene));
                    action(p,"status","");awaitingCombatReady=false;ready=true;
                } else if(++combatReadyTicks>200) throw new AssertionError("Combat fixture did not reach drawn READY "+fixtureStyle+": "+snapshot);
            }
            if(scene==13) {
                if(piercedArrow==null)for(var arrow:p.level().getEntitiesOfClass(AbstractArrow.class,p.getBoundingBox().inflate(16),ArcheryServer::isArcheryArrow)) {
                    if(arrow.getOwner()==p&&arrow.getPierceLevel()==rpg().archery().rules().chuanTangFengEntityPierce()
                            &&arrow.getPersistentData().contains("fsrpgArcheryBlocks")){piercedArrow=arrow;break;}
                }
                if(piercedArrow!=null)minimumBlockCharges=Math.min(minimumBlockCharges,piercedArrow.getPersistentData().getIntOr("fsrpgArcheryBlocks",3));
            }
            if(a.currentSkill()!=null&&snapshot.swingSequence()!=lastSwing) {
                lastSwing=snapshot.swingSequence();String id=a.currentSkill().id();observedSkills.add(id);
                synchronized(started){var j=new JsonObject();j.addProperty("scene",scene);j.addProperty("skill",id);j.addProperty("serverTick",p.level().getGameTime());j.addProperty("sequence",lastSwing);j.addProperty("windupTicks",a.currentSkill().windupTicks());j.addProperty("activeTicks",a.currentSkill().activeTicks());started.add(j);}
                if(chain&&comboRequests++<3)Minecraft.getInstance().execute(()->click(0));
            }
        } catch(Throwable error) { failure=error; }
    }
    private JsonObject thrustContext(ServerPlayer p,String hook) {
        var a=actor(p);var skill=a.currentSkill();var j=new JsonObject();
        j.addProperty("hook",hook);j.addProperty("serverTick",p.level().getGameTime());j.addProperty("clientTick",elapsed);
        j.addProperty("phase",a.phase().name());j.addProperty("skill",skill==null?"":skill.id());
        j.addProperty("skillElapsedTicks",a.snapshot().skillElapsedTicks());
        j.add("playerPosition",RpgConfig.JSON.toJsonTree(new double[]{p.getX(),p.getY(),p.getZ()}));
        j.add("playerRotation",RpgConfig.JSON.toJsonTree(new float[]{p.getYRot(),p.getXRot()}));
        j.add("playerLook",RpgConfig.JSON.toJsonTree(new double[]{p.getLookAngle().x,p.getLookAngle().y,p.getLookAngle().z}));
        j.addProperty("expectedTarget",target==null?"":target.getUUID().toString());j.addProperty("expectedHealthBefore",targetBefore);
        var candidates=new JsonArray();
        for(var mob:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(8),e->e!=p)) {
            var candidate=new JsonObject();candidate.addProperty("uuid",mob.getUUID().toString());
            candidate.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
            candidate.add("position",RpgConfig.JSON.toJsonTree(new double[]{mob.getX(),mob.getY(),mob.getZ()}));
            candidate.addProperty("health",mob.getHealth());candidate.addProperty("alive",mob.isAlive());candidate.addProperty("lineOfSight",p.hasLineOfSight(mob));
            var origin=p.getEyePosition();var box=mob.getBoundingBox();
            var contact=new Vec3(Math.clamp(origin.x,box.minX,box.maxX),Math.clamp(origin.y,box.minY,box.maxY),Math.clamp(origin.z,box.minZ,box.maxZ));
            double distance=origin.distanceTo(contact);candidate.addProperty("attackDistance",distance);
            var forward=p.getLookAngle().multiply(1,0,1).normalize();var direction=mob.position().subtract(p.position()).multiply(1,0,1).normalize();
            double angle=forward.lengthSqr()==0||direction.lengthSqr()==0?0:Math.toDegrees(Math.acos(Math.clamp(forward.dot(direction),-1,1)));
            candidate.addProperty("facingAngle",angle);candidate.addProperty("alreadyHit",a.hasHit(mob.getUUID()));
            if(skill!=null){candidate.addProperty("reach",skill.reach());candidate.addProperty("arcDegrees",skill.arcDegrees());candidate.addProperty("withinReach",distance<=skill.reach());candidate.addProperty("withinArc",CombatResolver.withinArc(angle,skill.arcDegrees()));}
            candidates.add(candidate);
        }
        j.add("nearbyLiving",candidates);return j;
    }
    private void recordThrustGeometry(ServerPlayer p,String hook) {
        var j=thrustContext(p,hook);synchronized(thrustGeometry){thrustGeometry.add(j);}
        System.out.println("LEGACY THRUST GEOMETRY "+j);
    }
    private void render(RenderFrameEvent.Post event) {
        if(done)return;
        try {
            movies.frame(event);movies.assertHealthy();String name;
            while((name=screenshots.poll())!=null) {
                String image=name+".png";Screenshot.grab(directory.toFile(),image,Minecraft.getInstance().getMainRenderTarget(),1,unused->{});
                var j=new JsonObject();j.addProperty("scene",scene);j.addProperty("file","screenshots/"+image);j.addProperty("clientTick",elapsed);images.add(j);
            }
        } catch(Throwable error) { failure=error; }
    }
    private void tick(ClientTickEvent.Post event) {
        var game=Minecraft.getInstance();if(done||game.player==null||game.level==null||game.getSingleplayerServer()==null)return;
        try {
            if(failure!=null)throw new IllegalStateException("Legacy server fixture failed",failure);
            fixedAim();
            if(game.isPaused()){game.setScreen(null);return;}game.getFramerateLimitTracker().onInputReceived();
            if(!initialized){if(++warmup<80)return;initialize(game);return;}
            if(!ready)return;
            if(!sceneStarted) {
                if(scene+1>=SCENES.size()) {finish(game);return;}
                scene++;elapsed=0;sceneStarted=true;ready=false;KeyMapping.releaseAll();game.setScreen(null);CombatCamera.reset();
                server(p->setup(p,scene));return;
            }
            at(++elapsed,scene);
            int duration=scene==11?134:scene==9||scene==10?114:scene==12?96:scene>=4&&scene<=8?84:50;
            if(elapsed>=duration) {if(captureStarted){movies.stop();captureStarted=false;}KeyMapping.releaseAll();sceneStarted=false;}
            if(elapsed>duration+300)throw new AssertionError("Scene timed out: "+SCENES.get(scene));
        } catch(Throwable error) { try {report(false,error.toString());}catch(Exception ignored){}error.printStackTrace();restoreOptions(game);done=true;game.stop(); }
    }
    private void finish(Minecraft game) throws Exception {
        if(!movies.finish())return;var capture=movies.summary();
        for(var value:capture.getAsJsonArray("clips")) {var clip=value.getAsJsonObject();check(clip.get("sourceFps").getAsDouble()>=30,"Actual rendered recording exceeds thirty fps: "+clip.get("id").getAsString());}
        report(true,"");restoreOptions(game);done=true;game.stop();
    }
    private void report(boolean passed,String error) throws Exception {
        Files.createDirectories(directory);var report=new JsonObject();report.addProperty("passed",passed);report.addProperty("error",error);
        report.addProperty("worldCopy",world==null?"":world.toString());report.addProperty("scene",scene);report.add("checks",RpgConfig.JSON.toJsonTree(checks));report.add("images",images);
        synchronized(started){report.add("actualStartedSkills",started.deepCopy());}RpgSmoke.windowEnvironment(report);
        synchronized(thrustGeometry){report.add("thrustGeometry",thrustGeometry.deepCopy());}
        report.addProperty("javaExecutable",ProcessHandle.current().info().command().orElse(""));
        var source=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.addProperty("modJarSha256",Files.isRegularFile(source)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))):"");
        RpgConfig.atomicWrite(directory.resolve("legacy-skill-smoke.json"),RpgConfig.JSON.toJson(report));
    }
    private void restoreOptions(Minecraft game) {
        KeyMapping.releaseAll();if(!initialized)return;
        game.options.guiScale().set(previousScale);game.options.framerateLimit().set(previousFps);game.options.renderDistance().set(previousDistance);
        game.options.pauseOnLostFocus=previousPause;game.options.hideGui=previousHidden;game.options.enableVsync().set(previousVsync);game.options.setCameraType(previousCamera);
    }
}

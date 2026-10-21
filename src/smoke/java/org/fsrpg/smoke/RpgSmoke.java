package org.fsrpg.smoke;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsvoxel.client.model.ModelPartBridge;
import org.slf4j.*;
import org.lwjgl.glfw.GLFW;

/** Development-only end-to-end checks; this source set is excluded from released JARs. */
@Mod(value="fsrpg", dist=Dist.CLIENT)
public final class RpgSmoke {
    private static final Logger LOG=LoggerFactory.getLogger("FSRPG SMOKE");
    private static final Path EVIDENCE=Path.of(System.getProperty("fsrpg.smokeEvidence", "../../verification/rapier-r6")).toAbsolutePath().normalize();
    private static final RenderFrameCapture movies = new RenderFrameCapture(EVIDENCE);
    private static final ArrayDeque<String> screenshotRequests = new ArrayDeque<>();
    private static int tick;
    private static volatile Throwable failure;
    private static volatile Zombie target;
    private static volatile Arrow arrow;
    private static volatile float hitHealth;
    private static volatile int breadAfterClaim;
    private static int checks;
    private static final List<String> passed=new ArrayList<>();
    private static boolean done;
    private static final Set<Integer> comboStages = new HashSet<>();
    private static final Set<Integer> requestedComboStages = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean firstAttack, windupObserved, windupSafe, comboActive, fifthStageReached;
    private static NativeReview review;
    private static final RefinementReview refinement = new RefinementReview();
    private static boolean refinementPassed;
    private static final InputMovementReview inputMovement = new InputMovementReview();
    private static boolean inputMovementPassed;
    private static final IdleInspectionReview idleInspection = new IdleInspectionReview();
    private static boolean idleInspectionPassed;
    private static final CancelReview cancelReview = new CancelReview();
    private static boolean cancelReviewPassed;
    public RpgSmoke(IEventBus bus) {
        if(Boolean.getBoolean("fsrpg.spearOrbitSmoke")){new SpearOrbitReview();return;}
        if(Boolean.getBoolean("fsrpg.rapierFencingSmoke")){new RapierFencingReview();return;}
        if(Boolean.getBoolean("fsrpg.spearShoulderSmoke")){new SpearShoulderReview();return;}
        if(Boolean.getBoolean("fsrpg.defenseFlowSmoke")){new DefenseFlowReview();return;}
        if(Boolean.getBoolean("fsrpg.rapierMotionSmoke")){new RapierMotionReview();return;}
        if(Boolean.getBoolean("fsrpg.weaponClashSmoke")){new WeaponClashReview();return;}
        if(Boolean.getBoolean("fsrpg.runAirSmoke")){new RunAirReview();return;}
        if(Boolean.getBoolean("fsrpg.inspectionCarrySmoke")){new InspectionCarryReview();return;}
        if(Boolean.getBoolean("fsrpg.groundImpulseSmoke")){new GroundImpulseReview();return;}
        if(Boolean.getBoolean("fsrpg.shoulderCombatSmoke")){new ShoulderCombatReview();return;}
        if(Boolean.getBoolean("fsrpg.burstSwipeSmoke")){new BurstSwipeReview();return;}
        if(Boolean.getBoolean("fsrpg.spearGestureSmoke")){new SpearGestureReview();return;}
        if(Boolean.getBoolean("fsrpg.combatRevisionSmoke")){new CombatRevisionReview();return;}
        if(Boolean.getBoolean("fsrpg.dualSourceSmoke")){new DualSourceReview();return;}
        if(Boolean.getBoolean("fsrpg.guardComboSmoke")){new GuardComboReview();return;}
        if(Boolean.getBoolean("fsrpg.creatureCombatSmoke")){new CreatureCombatReview();return;}
        if(Boolean.getBoolean("fsrpg.combatBalanceSmoke")){new CombatBalanceReview();return;}
        if(Boolean.getBoolean("fsrpg.whiteAdventurerSmoke")){new WhiteAdventurerReview();return;}
        if(Boolean.getBoolean("fsrpg.spearPerformanceSmoke")){new SpearPerformanceReview();return;}
        if(Boolean.getBoolean("fsrpg.rapierLockSmoke")){new RapierLockReview();return;}
        if(Boolean.getBoolean("fsrpg.artSmoke")){new ArtAssetsReview();return;}
        if(Boolean.getBoolean("fsrpg.actionLimitsSmoke")){new ActionLimitsReview();return;}
        if(Boolean.getBoolean("fsrpg.spearSolverSmoke")){new SpearSolverReview();return;}
        if(Boolean.getBoolean("fsrpg.legacySkillSmoke")){new LegacySkillReview();return;}
        if(Boolean.getBoolean("fsrpg.collectionSmoke")) {new CollectionReview();return;}
        if(Boolean.getBoolean("fsrpg.hudSmoke")) {new HudReview();return;}
        if(Boolean.getBoolean("fsrpg.poseSmoke")) {new PosePreview();return;}
        if(Boolean.getBoolean("fsrpg.weaponSmoke")) {new WeaponReview();return;}
        NeoForge.EVENT_BUS.addListener(RpgSmoke::tick);NeoForge.EVENT_BUS.addListener(RpgSmoke::serverTick);
        NeoForge.EVENT_BUS.addListener(RpgSmoke::renderFrame);
        // Physical clicks must not contaminate a scripted idle interval. Scripted key-map
        // dispatch below still runs through normal client controls and server validation.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) -> {
            if (!done) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.Key event) -> {
            if (!done) for (var mapping : Minecraft.getInstance().options.keyMappings) {
                if (mapping.getKey().getValue() == event.getKey()) {
                    mapping.setDown(false); while (mapping.consumeClick()) { }
                }
            }
        });
        ClientState.observeSnapshots(state->{
            if(firstAttack&&ClientState.text(state,"phase","").equals("WINDUP")&&ClientState.text(state,"skill","").equals("sword_left")&&ClientState.number(state,"phaseTicksRemaining",0)>0)windupObserved=true;
            if(comboActive&&state.has("combo")){int stage=(int)ClientState.number(state.getAsJsonObject("combo"),"stage",0);if(stage>0)comboStages.add(stage);}
        });
    }
    private static void serverTick(ServerTickEvent.Post event) {
        if(done||event.getServer().getPlayerList().getPlayers().isEmpty())return;
        try {
            ServerPlayer player=event.getServer().getPlayerList().getPlayers().getFirst();CombatActor combat=actor(player);
            if(firstAttack&&target!=null&&combat.phase()==CombatPhase.WINDUP) {
                if(target.getHealth()!=20)throw new AssertionError("actual server WINDUP dealt damage");windupSafe=true;
            }
            if(comboActive) {
                var combo=combat.comboSnapshot();
                if(combo.stage()==5)fifthStageReached=true;
                if(combo.stage()>0&&combo.stage()<5) {
                    var gate=combat.style().leftCombo().get(combo.stage()-1);
                    if(combo.elapsedTicks()>=1&&requestedComboStages.add(combo.stage())) {
                        LOG.info("COMBO EARLY INPUT stage={} elapsed={}",combo.stage(),combo.elapsedTicks());
                        Minecraft.getInstance().execute(()->{LOG.info("COMBO MOUSE dispatch stage={}",combo.stage());mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);});
                    }
                }
            }
        }catch(Throwable error){failure=error;}
    }
    static void check(boolean value,String name) { if(!value)throw new AssertionError(name);checks++;passed.add(name);LOG.info("PASS {}",name); }
    static CombatActor actor(ServerPlayer p) {
        try { var method=RpgServer.class.getDeclaredMethod("actor",ServerPlayer.class);method.setAccessible(true);return (CombatActor)method.invoke(RpgServer.instance(),p); }
        catch(Exception error) {throw new RuntimeException(error);}
    }
    static void server(Consumer<ServerPlayer> work) {
        Minecraft game=Minecraft.getInstance(); UUID id=game.player.getUUID();
        game.getSingleplayerServer().execute(()->{ try {work.accept(game.getSingleplayerServer().getPlayerList().getPlayer(id));}catch(Throwable error){failure=error;} });
    }
    private static int bread(ServerPlayer p) {int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++){var s=p.getInventory().getItem(i);if(s.is(Items.BREAD))n+=s.getCount();}return n;}
    static void screenshot(String name) { screenshotRequests.add(name); }
    private static void renderFrame(RenderFrameEvent.Post event) {
        if (done) return;
        try {
            movies.frame(event); movies.assertHealthy();
            String name;
            while ((name = screenshotRequests.poll()) != null) {
                Screenshot.grab(EVIDENCE.toFile(), name + ".png", Minecraft.getInstance().getMainRenderTarget(), 1,
                        message -> LOG.info("SCREENSHOT {}", message.getString()));
            }
        } catch (Throwable error) { failure = error; }
    }
    private static String phase() {return ClientState.text(ClientState.snapshot(),"phase","");}
    static void mouse(int button) {KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(button));}
    static void guardKey(boolean down) {
        var key=InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_E);KeyMapping.set(key,down);if(down)KeyMapping.click(key);
    }
    private static void environment(JsonObject report) throws Exception {
        var source=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.addProperty("modJarSha256",Files.isRegularFile(source)&&source.toString().endsWith(".jar")?
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))):"");
        windowEnvironment(report);
    }
    /** Observe the actual window's monitor; desktop coordinates and primary status can change. */
    static void windowEnvironment(JsonObject report) {
        int[] x={0},y={0},width={0},height={0};long window=Minecraft.getInstance().getWindow().handle();
        GLFW.glfwGetWindowPos(window,x,y);GLFW.glfwGetWindowSize(window,width,height);
        report.addProperty("windowX",x[0]);report.addProperty("windowY",y[0]);report.addProperty("windowWidth",width[0]);report.addProperty("windowHeight",height[0]);
        String adapter="";var bounds=new JsonObject();var monitors=GLFW.glfwGetMonitors();
        if(monitors!=null)for(int i=0;i<monitors.limit();i++) {
            long monitor=monitors.get(i);int[] mx={0},my={0};GLFW.glfwGetMonitorPos(monitor,mx,my);var mode=GLFW.glfwGetVideoMode(monitor);
            if(mode!=null&&x[0]+width[0]/2>=mx[0]&&x[0]+width[0]/2<mx[0]+mode.width()&&y[0]+height[0]/2>=my[0]&&y[0]+height[0]/2<my[0]+mode.height()) {
                adapter=org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Adapter(monitor);
                bounds.addProperty("x",mx[0]);bounds.addProperty("y",my[0]);
                bounds.addProperty("width",mode.width());bounds.addProperty("height",mode.height());
                break;
            }
        }
        report.addProperty("monitorAdapter",adapter);
        report.add("monitorBounds",bounds);
    }
    private static void tick(ClientTickEvent.Post event) {
        Minecraft game=Minecraft.getInstance();
        if(done || game.player==null || game.getSingleplayerServer()==null)return;
        try {
            // A pause menu must not advance fixture ticks against a frozen animation clock.
            if(tick>490 && game.isPaused()) { game.setScreen(null); return; }
            game.getFramerateLimitTracker().onInputReceived();
            if(failure!=null)throw new RuntimeException("Server smoke failure",failure);
            movies.assertHealthy();
            tick++;
            if (tick > 490) {
                if (!refinementPassed) { refinementPassed = refinement.tick(); return; }
                if (!inputMovementPassed) { inputMovementPassed = inputMovement.tick(movies); return; }
                if (!cancelReviewPassed) { cancelReviewPassed = cancelReview.tick(movies); return; }
                if (!idleInspectionPassed) { idleInspectionPassed = idleInspection.tick(movies); return; }
                if (review.tick()) {
                    if (!movies.finish()) return;
                    JsonObject renderCapture = movies.summary();
                    for (var value : renderCapture.getAsJsonArray("clips")) {
                        var clip = value.getAsJsonObject();
                        check(clip.get("sourceFps").getAsDouble() >= 30, "actual rendered capture >=30 fps " + clip.get("id").getAsString());
                        check(clip.get("distinctSamplesWithinGameTick").getAsInt() > 0 && clip.get("changedRigPairs").getAsInt() > 0,
                                "moving sub-tick animation samples " + clip.get("id").getAsString());
                    }
                    JsonObject report = com.google.gson.JsonParser.parseString(Files.readString(EVIDENCE.resolve("game-smoke.json"))).getAsJsonObject();
                    report.addProperty("reviewImages", review.images());
                    report.addProperty("reviewPosePairChecks",review.posePairs());report.addProperty("fixturePoseFrozen",true);report.addProperty("staticFixtureMechanism","detached native render preview");
                    report.addProperty("movingReviewImages", review.movingImages());
                    report.addProperty("movingLegChecks", review.motionLegChecks());
                    report.addProperty("refinementMechanicsPassed", refinementPassed);
                    report.addProperty("actualInputMovementPassed", inputMovementPassed);
                    report.addProperty("actualInputMovementChecks", inputMovement.checks());
                    report.addProperty("idleInspectionPassed", idleInspectionPassed);
                    report.addProperty("idleInspectionChecks", idleInspection.checks());
                    report.addProperty("cancelReviewPassed", cancelReviewPassed);
                    report.addProperty("cancelReviewChecks", cancelReview.checks());
                    report.addProperty("ambientMotionChecks", review.ambientChecks());
                    report.addProperty("ambientImages", review.ambientImages());
                    check(review.officialItems().containsAll(Set.of("minecraft:diamond_sword", "minecraft:iron_sword", "minecraft:trident")),
                            "three actual mainhand item models reached official renderer");
                    report.add("officialItemIds", RpgConfig.JSON.toJsonTree(review.officialItems()));
                    report.addProperty("officialItemSubmissions", OfficialHeldItems.submissions());
                    report.addProperty("officialFoilSubmissions", OfficialHeldItems.foilSubmissions());
                    report.addProperty("officialOffhandSubmissions", OfficialHeldItems.offhandSubmissions());
                    report.addProperty("wristAttachedOffhandSubmissions", OfficialHeldItems.offhandAttachedSubmissions());
                    for (var value : renderCapture.getAsJsonArray("clips")) value.getAsJsonObject().remove("frames");
                    report.add("renderCapture", renderCapture);
                    report.addProperty("passed",true);
                    report.addProperty("checks",checks);report.add("checksPassed",RpgConfig.JSON.toJsonTree(passed));
                    RpgConfig.atomicWrite(EVIDENCE.resolve("game-smoke.json"),RpgConfig.JSON.toJson(report));
                    LOG.info("FSRPG REVIEW COMPLETE {} screenshots (stock/slim skin + armor)",review.images());done=true;game.stop();
                }
                return;
            }
            // Real key-map dispatch, with the same held intent maintained across window focus changes.
            if(tick>=380&&tick<389||tick>=406&&tick<440||tick>=470&&tick<478)
                KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_E),true);
            if(tick>=276&&tick<=282) {
                game.options.hideGui=true;
                game.player.setYRot(-50);game.player.yRotO=-50;
                game.player.yBodyRot=game.player.yBodyRotO=0;
                game.player.yHeadRot=game.player.yHeadRotO=0;
            }
            if(tick>=300&&tick<375&&ClientState.snapshot().has("combo")) {
                int stage=ClientState.snapshot().getAsJsonObject("combo").get("stage").getAsInt();if(stage>0)comboStages.add(stage);
            }
            switch(tick) {
                case 20 -> {
                    KeyMapping.releaseAll();
                    GLFW.glfwSetWindowTitle(game.getWindow().handle(), "FSRPG 自动验证 · 屏幕二 · 完成后自动关闭");
                    Files.createDirectories(EVIDENCE.resolve("screenshots"));
                    Files.createDirectories(EVIDENCE.resolve("screenshots/review"));Files.createDirectories(EVIDENCE.resolve("screenshots/frames"));
                    Files.createDirectories(EVIDENCE.resolve("screenshots/moving"));
                    game.getTutorial().setStep(TutorialSteps.NONE);
                    game.options.pauseOnLostFocus=false;
                    game.options.framerateLimit().set(60); game.options.enableVsync().set(false);
                    game.options.guiScale().set(2);
                    game.options.renderDistance().set(4);game.options.simulationDistance().set(5);
                    for(var key:game.options.keyMappings)if(key.getName().equals("key.fsrpg.guard"))key.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_E));
                    KeyMapping.resetMapping();
                    game.resizeDisplay();
                    server(p->{
                        p.setGameMode(GameType.SURVIVAL);
                        for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++)p.level().setBlock(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState(),3);
                        p.connection.teleport(0.5,80,0.5,0,0);
                        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
                        p.setHealth(p.getMaxHealth()); actor(p).setResources(100,100);
                        Zombie mob=EntityType.ZOMBIE.create(p.level(),EntitySpawnReason.COMMAND);
                        mob.setNoAi(true);mob.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
                        mob.setPos(4.5,80,2.5);p.level().addFreshEntity(mob);target=mob;
                    });
                }
                case 31 -> {game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);game.options.fov().set(50);game.options.hideGui=true;}
                case 34 -> screenshot("rapier-00-stock-skin-baseline");
                case 36 -> {game.options.hideGui=false;server(p->{
                    p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
                    p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
                });}
                case 40 -> {check(ClientState.skills().size()==15,"fifteen skills including dual and spear synchronized");check(ClientState.quests().size()==3,"quest journal synchronized");check(ClientState.objects(ClientState.snapshot(),"styles").size()==4,"four weapon styles synchronized");ClientHooks.send("draw","");}
                case 53 -> {game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);game.options.fov().set(50);}
                case 54 -> {check(PlayerAnimations.active(game.player.getUUID()),"actual player has tracked FS animation binding");screenshot("rapier-01-native-idle");}
                case 55 -> {check(ClientState.drawn(),"draw state round trip");firstAttack=true;server(p->target.setPos(0.5,80,2.5));ClientHooks.send("attack","left");}
                case 77 -> server(p->{check(windupObserved,"actual received network snapshot entered windup with remaining ticks");check(windupSafe,"actual server windup caused no early damage");hitHealth=target.getHealth();check(hitHealth>0&&hitHealth<20,"effective frame damages Minecraft zombie");});
                case 95 -> {firstAttack=false;server(p->{check(target.getHealth()==hitHealth,"one target hit only once per swing");target.hurtServer(p.level(),p.damageSources().playerAttack(p),1000);});}
                case 110 -> ClientHooks.send("claimQuest","fsrpg:first_hunt");
                case 120 -> server(p->{breadAfterClaim=bread(p);check(breadAfterClaim>=3,"quest reward delivered to inventory");});
                case 125 -> ClientHooks.send("claimQuest","fsrpg:first_hunt");
                case 135 -> server(p->check(bread(p)==breadAfterClaim,"second claim grants no duplicate reward"));
                case 140 -> {
                    check(ClientState.quests().get(0).get("status").getAsString().equals("CLAIMED"),"quest state claim synchronized");
                    ClientHooks.openEditor();
                }
                case 155 -> {check(game.screen instanceof SkillEditorScreen,"timing editor opens in game");check(game.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().matches("[1-5]")).count()==5,"five stage preview selectors present");screenshot("rapier-02-timing-editor");}
                case 165 -> {
                    EditBox first=(EditBox)game.screen.children().stream().filter(c->c instanceof EditBox).findFirst().orElseThrow();
                    first.setValue("5");
                    Button save=(Button)game.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().contains("保存")).findFirst().orElseThrow();
                    check(save.active,"authorized save enabled");save.onPress(null);
                }
                case 180 -> {check(ClientState.skills().get(0).get("windupTicks").getAsInt()==5,"editor save changed authoritative config");screenshot("rapier-03-editor-saved");}
                case 190 -> {ClientHooks.send("editSkill","{\"id\":\"sword_left\",\"changes\":{\"activeTicks\":0}}");}
                case 205 -> {check(ClientState.skills().get(0).get("activeTicks").getAsInt()==3,"invalid window edit retained live value");ClientHooks.send("editSkill","{\"id\":\"sword_left\",\"changes\":{\"windupTicks\":4}}");}
                case 215 -> {ClientHooks.openJournal();}
                case 230 -> {check(game.screen instanceof QuestJournalScreen,"quest journal opens in game");screenshot("rapier-04-quest-journal");}
                case 235 -> game.setScreen(null);
                case 240 -> server(p->{
                    actor(p).setResources(100,100);
                    p.connection.teleport(0.5,80,0.5,0,0);
                    RpgServer.instance().action(p,"guard","true");
                    Arrow shot=new Arrow(p.level(),0.5,81.2,1.9,new ItemStack(Items.ARROW),null);
                    shot.setDeltaMovement(0,0,-0.8);shot.setNoGravity(true);p.level().addFreshEntity(shot);arrow=shot;
                });
                case 250 -> server(p->{check(arrow.getOwner()==p&&arrow.getDeltaMovement().z>0,"perfect guard reflected actual vanilla arrow");RpgServer.instance().action(p,"guard","false");actor(p).setResources(100,100);RpgServer.instance().action(p,"dodge","");});
                case 265 -> server(p->check(p.getZ()>2,"server dodge moves player using collision path"));
                case 270 -> {game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);check(ModelPartBridge.diagnostics().renders()>0,"standalone FSVOXEL renderer active");check(PlayerAnimations.appliedModels()>0,"native player pose adapter actually ran");check(OfficialHeldItems.submissions()>0&&OfficialHeldItems.lastItemId().equals("minecraft:diamond_sword"),"actual mainhand diamond sword rendered through official ItemStack layer");check(SkinnedArms.renderedSegments()>0,"original skin rendered as FS upper forearm and weighted wrist");screenshot("rapier-05-combat-hud");}
                case 273 -> server(p->{for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);});
                case 280 -> screenshot("rapier-10-skin-idle-side");
                case 283 -> game.options.hideGui=false;
                case 285 -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V));
                case 292 -> {check(game.screen instanceof StyleSelectionScreen,"actual V key opens weapon style selector");check(game.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().startsWith("预留")).count()==1,"disabled future styles visible in selector");screenshot("rapier-11-style-selector");game.setScreen(null);}
                case 295 -> server(p->p.connection.teleport(0.5,80,0.5,0,0));
                case 300 -> {
                    server(p->{actor(p).setResources(100,100);p.connection.teleport(0.5,80,0.5,0,0);});
                    comboActive=true;
                }
                case 305 -> {
                    var observe = RenderObservations.sampler(game.player);
                    movies.start("gameplay-combo", "actual server-authoritative five-click combo", () -> {
                        JsonObject sample = observe.get();
                        sample.addProperty("serverPhase", phase());
                        sample.addProperty("serverSkill", ClientState.text(ClientState.snapshot(), "skill", ""));
                        return sample;
                    });
                    mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                }
                case 357 -> screenshot("rapier-06-five-stage-combo");
                case 375 -> {movies.stop();check(fifthStageReached&&requestedComboStages.size()==4,"early five-click input chains immediately after complete active phases");check(comboStages.containsAll(Set.of(1,2,3,4,5)),"all five combo stages synchronized to client");check(PlayerAnimations.transitionFrames()>0,"actual action changes blend from preceding skin pose");comboActive=false;}
                case 380 -> {guardKey(true);}
                case 389 -> {check(game.screen==null,"drawn E conflicts with inventory without opening it");check(phase().equals("GUARDING"),"held E enters authoritative guard");screenshot("rapier-07-native-guard");guardKey(false);}
                case 400 -> {check(phase().equals("READY"),"releasing E leaves guard");server(p->actor(p).setResources(100,100));mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);}
                case 406 -> {check(phase().equals("WINDUP"),"actual RMB uses short charge window");screenshot("rapier-08-charge");guardKey(true);}
                case 412 -> screenshot("rapier-09-thrust");
                case 440 -> {check(phase().equals("GUARDING"),"held E retries after busy attack returns ready");guardKey(false);}
                case 452 -> ClientHooks.send("draw","");
                case 470 -> {check(!ClientState.drawn(),"sheathing state round trip");guardKey(true);}
                case 478 -> {check(game.screen instanceof InventoryScreen,"sheathed E still opens vanilla inventory");guardKey(false);game.setScreen(null);}
                case 490 -> {
                    JsonObject report=new JsonObject();report.addProperty("passed",false);report.addProperty("gameplayPassed",true);report.addProperty("checks",checks);report.addProperty("clientTicks",tick);
                    report.add("checksPassed",RpgConfig.JSON.toJsonTree(passed));report.add("engine",RpgConfig.JSON.toJsonTree(ModelPartBridge.diagnostics()));
                    report.addProperty("nativeModelApplications",PlayerAnimations.appliedModels());report.addProperty("officialItemSubmissions",OfficialHeldItems.submissions());report.addProperty("skinnedArmSegments",SkinnedArms.renderedSegments());
                    report.addProperty("transitionFrames",PlayerAnimations.transitionFrames());
                    report.addProperty("skinModel",game.player.getSkin().model().toString());
                    environment(report);
                    RpgConfig.atomicWrite(EVIDENCE.resolve("game-smoke.json"),RpgConfig.JSON.toJson(report));
                    LOG.info("FSRPG SMOKE PASS {} checks",checks);review=new NativeReview(RpgSmoke::screenshot, movies);
                }
            }
        } catch(Throwable error) {
            LOG.error("FSRPG SMOKE FAIL tick="+tick,error);done=true;
            try {Files.createDirectories(EVIDENCE);Files.writeString(EVIDENCE.resolve("rapier-game-smoke-failure.txt"),"tick="+tick+"\n"+error);}catch(Exception ignored){}
            game.stop();
        }
    }
}

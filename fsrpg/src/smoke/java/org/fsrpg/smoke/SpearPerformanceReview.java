package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
import org.fsrpg.client.ClientHooks;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.client.SpearAimPose;
import org.fsrpg.client.SpearGrip;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.CombatPhase;
import org.fsrpg.combat.SpearAimField;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** No movie readback or detached pose solves: native, uncapped packaged-client frame timings. */
final class SpearPerformanceReview {
    private static final boolean USER_SETTINGS = Boolean.getBoolean("fsrpg.spearPerformanceUserSettings");
    private static final List<String> SCENES = USER_SETTINGS ? List.of("sheathed-control", "spear-stress-30s") : Boolean.getBoolean("fsrpg.spearPerformanceQuick")
            ? List.of("sheathed-control", "spear-idle", "spear-aim-sweep", "spear-movement", "spear-attacks")
            : List.of("sheathed-control", "spear-idle", "spear-aim-sweep", "spear-movement", "spear-attacks", "spear-stress-30s");
    private static final long WARMUP = USER_SETTINGS ? 8_000_000_000L : 3_000_000_000L, SAMPLE = 8_000_000_000L;
    private static final long STRESS_SAMPLE = Long.getLong("fsrpg.spearPerformanceStressSeconds", 30) * 1_000_000_000L;
    private static final List<String> COUNTERS = List.of("frameRequests", "frameCacheHits", "spearSolveCalls", "spearSolveNanos", "maxSpearSolveNanos", "lastSpearSolveNanos", "spearContinuationCalls", "renderFrameId");
    private static final List<String> OBSERVED_JOINTS = List.of("body", "right_arm", "right_forearm", "right_hand", "left_arm", "left_forearm", "left_hand");
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray results = new JsonArray();
    private final ArrayList<Double> intervals = new ArrayList<>(), renderTimes = new ArrayList<>();
    private final ArrayList<Double> unarmoredIntervals = new ArrayList<>(), armoredIntervals = new ArrayList<>(), unarmoredRenderTimes = new ArrayList<>(), armoredRenderTimes = new ArrayList<>();
    private final ArrayList<Long> timestamps = new ArrayList<>();
    private final ArrayList<JsonObject> slowFrames = new ArrayList<>();
    private final java.util.HashMap<String,Method> counterMethods = new java.util.HashMap<>();
    private final java.util.HashMap<String,Long> counterStart = new java.util.HashMap<>();
    private final Vector3f rear = new Vector3f(), front = new Vector3f(), shaft = new Vector3f(), separation = new Vector3f();
    private final java.util.HashMap<String,Quaternionf> previousJointRotations = new java.util.HashMap<>();
    private final java.util.HashMap<String,Double> maximumJointSteps = new java.util.HashMap<>();
    private final Quaternionf jointRotation = new Quaternionf();
    private final JsonArray serverThrusts = new JsonArray();
    private final JsonArray canceledServerThrusts = new JsonArray();
    private final RenderFrameCapture visualCapture = new RenderFrameCapture(root.resolve("visual"));
    private boolean visualPending, visualActive, visualStopped;
    private boolean armorRequested;
    private double armorTransitionSeconds = -1;
    private volatile Throwable failure;
    private volatile boolean ready;
    private volatile boolean sceneSetupComplete, sceneServerReady;
    private volatile String sceneServerPhase = "", sceneServerStyle = "";
    private boolean initialized, done, screenshotTaken, previousActive, previousPause, previousVsync, previousHidden;
    private int initializationTicks, scene = -1, sceneTick, previousFps, previousDistance, previousSimulation, previousScale, previousFov;
    private int dismissedPauseScreens, dismissedTimedPauseScreens, warmupReadinessRestarts;
    private CameraType previousCamera;
    private long started, sceneStarted, lastPost, renderStarted, sampleStart, officialStart, changedPosePairs, distinctSubtickPairs, attackFrames;
    private long scenePreparationStarted;
    private double scenePreparationSeconds;
    private long cursorSamples, cachedPoseFrames;
    private long lastCenterAimTraceNanos;
    private final JsonArray centerAimInputTrace=new JsonArray();
    private long qualityFrames, shaftFailures, badGripFrames, lastQuality, qualityNanos, gcCountStart, gcMillisStart, duplicateSolveFrames;
    private long poseJumpsOver25Degrees;
    private double maximumNativePoseGapTicks;
    private long previousSolveCalls = -1, previousNativeFrame = -1;
    private long continuationBeforeRender = -1;
    private double maximumGripAxisError, minimumGripSpacing = Double.POSITIVE_INFINITY, maximumGripSpacing;
    private volatile long activeServerSequence = -1, activeServerStarted = -1;
    private volatile int activeServerDuration;
    private double previousPoseTick = Double.NaN, previousPartial = Double.NaN;
    private float previousPalmX = Float.NaN, previousPalmY, previousPalmZ;
    private final JsonObject actions = new JsonObject();
    private Field bindingsField, sampledField;

    SpearPerformanceReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(this::post);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener((RenderLivingEvent.Post<?,?,?> event) -> { if (visualActive) RenderObservations.captureSubmittedAvatar(event); });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private int actualScene() { return USER_SETTINGS && scene == 1 ? 5 : scene; }
    private long sampleDuration() { return actualScene() == 5 ? STRESS_SAMPLE : SAMPLE; }
    private long counter(String name) {
        var method = counterMethods.get(name); if (method == null) return -1;
        try { return ((Number)method.invoke(null)).longValue(); } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum(); }
    private long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum(); }
    private void serverTick(ServerTickEvent.Post event) {
        if (!initialized || done || scene < 0 || !ready || !sceneSetupComplete) return;
        try {
            var game = Minecraft.getInstance(); if (game.player == null) return;
            var player = event.getServer().getPlayerList().getPlayer(game.player.getUUID()); if (player == null) return;
            var actor = RpgSmoke.actor(player); var state = actor.snapshot(); var skill = actor.currentSkill();
            sceneServerPhase = actor.phase().name(); sceneServerStyle = actor.style() == null ? "" : actor.style().id();
            sceneServerReady = scene == 0 ? !actor.drawn() && player.getMainHandItem().isEmpty()
                    : actor.drawn() && sceneServerStyle.equals("spear") && player.getMainHandItem().is(Items.DIAMOND_SPEAR);
            if (actualScene() < 4) return;
            boolean thrust = skill != null && skill.id().equals("spear_thrust");
            if (activeServerSequence >= 0 && (!thrust || state.swingSequence() != activeServerSequence)) {
                long elapsed = state.serverTick() - activeServerStarted;
                var value = new JsonObject(); value.addProperty("swingSequence", activeServerSequence);
                value.addProperty("configuredDurationTicks", activeServerDuration);
                value.addProperty("observedDurationTicks", elapsed);
                value.addProperty("observedSeconds", elapsed / 20.0);
                // A legitimate next input can cancel windup/recovery. A new skill
                // sequence before the old duration is an interruption, not completion.
                if (skill != null && state.swingSequence() != activeServerSequence && elapsed < activeServerDuration) {
                    value.addProperty("reason", "replaced-by-new-skill-before-duration");
                    value.addProperty("replacementSkill", skill.id()); value.addProperty("replacementSwingSequence", state.swingSequence());
                    value.addProperty("replacementPhase", actor.phase().name());
                    synchronized (canceledServerThrusts) { canceledServerThrusts.add(value); }
                } else {
                    synchronized (serverThrusts) { serverThrusts.add(value); }
                    if (Boolean.getBoolean("fsrpg.spearPerformanceExpectFastThrust"))
                        require(activeServerDuration == 4 && elapsed == 4, "Real server thrust must finish in exactly four ticks / 0.2 s: " + value);
                }
                activeServerSequence = -1;
            }
            if (thrust && activeServerSequence < 0) {
                activeServerSequence = state.swingSequence(); activeServerStarted = state.serverTick() - state.skillElapsedTicks();
                activeServerDuration = skill.durationTicks();
            }
        } catch (Throwable error) { failure = error; }
    }

    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void server(Consumer<ServerPlayer> work) {
        var game = Minecraft.getInstance(); var uuid = game.player.getUUID();
        game.getSingleplayerServer().execute(() -> { try { work.accept(game.getSingleplayerServer().getPlayerList().getPlayer(uuid)); } catch (Throwable error) { failure = error; } });
    }

    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots"));
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        require("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()), "Performance window is outside DISPLAY5");
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var named = System.getProperty("fsrpg.spearPerformanceWorld", "");
        require(named.startsWith("FSRPG Spear Performance Review ") && named.equals(world.getFileName().toString())
                && Files.isRegularFile(world.resolve(".fsrpg-spear-performance-test-world")), "Only explicitly marked copied performance world may be modified");
        previousFps = game.options.framerateLimit().get(); previousDistance = game.options.renderDistance().get();
        previousSimulation = game.options.simulationDistance().get();
        previousScale = game.options.guiScale().get(); previousFov = game.options.fov().get();
        previousPause = game.options.pauseOnLostFocus; previousVsync = game.options.enableVsync().get(); previousHidden = game.options.hideGui;
        previousCamera = game.options.getCameraType(); previousActive = game.isWindowActive();
        game.options.framerateLimit().set(USER_SETTINGS ? 120 : 260); game.options.enableVsync().set(USER_SETTINGS); game.options.pauseOnLostFocus = false;
        game.options.renderDistance().set(USER_SETTINGS ? 12 : 3); game.options.guiScale().set(2); game.options.fov().set(65);
        if (USER_SETTINGS) game.options.simulationDistance().set(12);
        game.options.hideGui = false; game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        long handle = game.getWindow().handle(); var bounds = environment.getAsJsonObject("monitorBounds");
        GLFW.glfwSetWindowSize(handle, 1000, 680); GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 35, bounds.get("y").getAsInt() + 45);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 长枪实机性能 · DISPLAY5"); game.resizeDisplay();
        game.getTutorial().setStep(TutorialSteps.NONE); KeyMapping.releaseAll();
        // Internal game input flag only. This never focuses the OS window.
        game.setWindowActive(true); initialized = true; started = System.nanoTime();
        bindingsField = PlayerAnimations.class.getDeclaredField("bindings"); bindingsField.setAccessible(true);
        for (String name : COUNTERS) try { counterMethods.put(name, PlayerAnimations.class.getMethod(name)); } catch (NoSuchMethodException oldRelease) { }
        server(p -> {
            p.setGameMode(GameType.SURVIVAL); p.setInvulnerable(true); p.setHealth(20); p.getFoodData().setFoodLevel(20);
            p.level().setDayTime(6000); p.level().setWeatherParameters(6000, 0, false, false);
            for (var entity : p.level().getEntities(p, p.getBoundingBox().inflate(32), e -> e instanceof LivingEntity && !(e instanceof net.minecraft.world.entity.player.Player))) entity.discard();
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 18; z++) {
                p.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 80; y < 86; y++) p.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            p.connection.teleport(.5, 80, .5, 0, 0);
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) p.setItemSlot(slot, ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            RpgSmoke.actor(p).forceSheathed(); RpgServer.instance().action(p, "status", ""); ready = true;
        });
    }

    private void beginScene(Minecraft game) throws Exception {
        if (scene >= 0) summarize();
        scene++; if (scene == SCENES.size()) {
            if (Boolean.getBoolean("fsrpg.spearPerformanceVideo")) {
                visualPending = true; scenePreparationStarted = System.nanoTime(); sceneStarted = 0; sceneTick = 0;
                KeyMapping.releaseAll();
                return;
            }
            finish(null); return;
        }
        System.out.println("SPEAR PERFORMANCE SCENE " + SCENES.get(scene) + " " + Instant.now());
        KeyMapping.releaseAll(); intervals.clear(); renderTimes.clear(); timestamps.clear(); slowFrames.clear(); sceneTick = 0;
        unarmoredIntervals.clear(); armoredIntervals.clear(); unarmoredRenderTimes.clear(); armoredRenderTimes.clear(); armorRequested = false; armorTransitionSeconds = -1;
        sceneStarted = 0; scenePreparationStarted = System.nanoTime(); scenePreparationSeconds = 0; warmupReadinessRestarts = 0;
        sceneSetupComplete = sceneServerReady = false; sceneServerPhase = sceneServerStyle = "";
        sampleStart = lastPost = 0; screenshotTaken = false;
        changedPosePairs = distinctSubtickPairs = attackFrames = cursorSamples = cachedPoseFrames = 0;
        lastCenterAimTraceNanos=0;centerAimInputTrace.asList().clear();
        qualityFrames = shaftFailures = badGripFrames = qualityNanos = lastQuality = duplicateSolveFrames = 0;
        maximumGripAxisError = maximumGripSpacing = 0; minimumGripSpacing = Double.POSITIVE_INFINITY;
        previousSolveCalls = previousNativeFrame = -1;
        poseJumpsOver25Degrees = 0; maximumNativePoseGapTicks = 0; previousJointRotations.clear(); maximumJointSteps.clear();
        previousPoseTick = previousPartial = Double.NaN; previousPalmX = Float.NaN; officialStart = OfficialHeldItems.submissions();
        if (scene == 0) sceneSetupComplete = true;
        else server(p -> {
            p.connection.teleport(.5, 80, .5, 0, 0);
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) p.setItemSlot(slot, ItemStack.EMPTY);
            if (!p.getMainHandItem().is(Items.DIAMOND_SPEAR)) p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SPEAR));
            p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            // Reconcile actual equipment through the real server controller. A single matching
            // spear style begins DRAWING automatically; selecting a style during that phase is BUSY.
            RpgServer.instance().action(p, "status", ""); var actor = RpgSmoke.actor(p); actor.setResources(100, 100);
            if (actor.phase() == CombatPhase.SHEATHED) RpgServer.instance().action(p, "draw", "");
            sceneSetupComplete = true;
        });
    }

    private boolean clientSceneReady(Minecraft game) {
        return scene == 0 ? !ClientState.drawn() && game.player.getMainHandItem().isEmpty()
                : ClientState.drawn() && CombatCamera.style().equals("spear") && game.player.getMainHandItem().is(Items.DIAMOND_SPEAR);
    }

    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (done || game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            // Smoke input never takes OS focus. An incidental Escape must not freeze the
            // integrated server in DRAWING; measured pauses remain in the native timings.
            if (game.screen instanceof PauseScreen) {
                dismissedPauseScreens++;
                if (sampleStart != 0) dismissedTimedPauseScreens++;
                game.setScreen(null);
            }
            if (!initialized) { if (++initializationTicks >= 60) initialize(game); return; }
            game.setWindowActive(true);
            if (failure != null) throw new IllegalStateException("Asynchronous performance fixture failed", failure);
            game.getFramerateLimitTracker().onInputReceived();
            if (System.nanoTime() - started > 210_000_000_000L + STRESS_SAMPLE) throw new AssertionError("Performance review timeout");
            if (!ready) return;
            if (visualPending) {
                long waited = System.nanoTime() - scenePreparationStarted;
                require(waited < 5_000_000_000L, "Previous attack did not settle before untimed native video");
                if (waited < 250_000_000L || !sceneServerPhase.equals("READY") || activeServerSequence >= 0
                        || !ClientState.text(ClientState.snapshot(), "phase", "").equals("READY") || !clientSceneReady(game)) return;
                visualPending = false; visualActive = true; sceneStarted = System.nanoTime(); sceneTick = 0;
                var submitted=RenderObservations.submittedSampler(game.player);
                visualCapture.start("spear-short-thrust-native", "Separate untimed 5-second local-player center aim / rapid 0.2-second thrust visual acceptance; each real RenderFrameEvent.Post", ()->{
                    var observed=submitted.get();observed.addProperty("aimInputMode","center-reticle-camera-angle");
                    observed.addProperty("cameraYaw",CombatCamera.yaw());observed.addProperty("cameraPitch",CombatCamera.pitch());
                    observed.add("centerRayWorld",RpgConfig.JSON.toJsonTree(new org.joml.Vector3f(0,0,-1).rotate(game.gameRenderer.getMainCamera().rotation())));return observed;
                });
                return;
            }
            if (visualActive) {
                visualCapture.assertHealthy();
                if (System.nanoTime() - sceneStarted >= 5_000_000_000L) {
                    if (!visualStopped) { visualCapture.stop(); visualStopped = true; }
                    if (visualCapture.finish()) {
                        var summary = visualCapture.summary();
                        for (var raw : summary.getAsJsonArray("clips")) {
                            var clip = raw.getAsJsonObject(); require(clip.get("sourceFps").getAsDouble() >= 30, "Native visual capture below 30 Hz");
                            require(clip.get("distinctSamplesWithinGameTick").getAsLong() > 0, "Native visual clip has no subtick motion");
                        }
                        finish(null);
                    }
                    return;
                }
                if (++sceneTick % 5 == 1 && sceneServerPhase.equals("READY") && activeServerSequence < 0
                        && ClientState.text(ClientState.snapshot(), "phase", "").equals("READY")) {
                    server(p -> RpgSmoke.actor(p).setResources(100,100)); CombatCamera.attack("left");
                }
                return;
            }
            if (sceneTick % 20 == 0) {
                var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
                require("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()), "Performance window moved outside DISPLAY5");
            }
            if (scene < 0 || sceneStarted != 0 && System.nanoTime() - sceneStarted >= WARMUP + sampleDuration()) beginScene(game);
            if (done || visualPending || visualActive) return;
            if (sceneStarted != 0 && sampleStart == 0 && (!sceneServerReady || !clientSceneReady(game))) {
                sceneStarted = 0; warmupReadinessRestarts++; screenshotTaken = false;
            }
            if (sceneStarted == 0) {
                KeyMapping.releaseAll();
                require(System.nanoTime() - scenePreparationStarted < 15_000_000_000L,
                        "Scene equipment/draw readiness timeout: " + SCENES.get(scene) + " server=" + sceneServerPhase + "/" + sceneServerStyle
                                + " client=" + ClientState.text(ClientState.snapshot(), "phase", "") + "/" + CombatCamera.style());
                if (!sceneSetupComplete || !sceneServerReady || !clientSceneReady(game)) return;
                sceneStarted = System.nanoTime(); scenePreparationSeconds = (sceneStarted - scenePreparationStarted) / 1e9; sceneTick = 0;
                System.out.println("SPEAR PERFORMANCE READY " + SCENES.get(scene) + " server=" + sceneServerPhase + " setupSeconds=" + scenePreparationSeconds);
            }
            sceneTick++;
            if (scene > 0 && System.nanoTime() - sceneStarted > WARMUP) require(clientSceneReady(game), "Spear must actually be drawn during timed scenes");
            if (actualScene() == 5 && !armorRequested && System.nanoTime() - sceneStarted >= WARMUP + STRESS_SAMPLE / 2) {
                armorRequested = true;
                server(p -> {
                    p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                });
            }
            boolean moving = actualScene() == 3 || actualScene() == 5; int turn = actualScene() == 5 ? 20 : 40;
            game.options.keyUp.setDown(moving && sceneTick % (turn * 4) < turn);
            game.options.keyRight.setDown(moving && sceneTick % (turn * 4) >= turn && sceneTick % (turn * 4) < turn * 2);
            game.options.keyDown.setDown(moving && sceneTick % (turn * 4) >= turn * 2 && sceneTick % (turn * 4) < turn * 3);
            game.options.keyLeft.setDown(moving && sceneTick % (turn * 4) >= turn * 3);
            if (actualScene() >= 4) {
                if (sceneTick % 30 == 1) server(p -> RpgSmoke.actor(p).setResources(100, 100));
                if (sceneTick % (actualScene() == 5 ? 5 : 20) == 1) CombatCamera.attack("left");
                if (sceneTick % 60 == 45 && actualScene() != 5) {
                    var aim = CombatCamera.aimPoint(); var request = new JsonObject(); request.addProperty("button", "sweep");
                    var points = List.of(new AttackAim.Point(aim.yaw() - 12, Math.clamp(aim.pitch() - 6, -70, 70), aim.distance()),
                            aim, new AttackAim.Point(aim.yaw() + 12, Math.clamp(aim.pitch() + 6, -70, 70), aim.distance()));
                    if (points.stream().allMatch(point -> SpearAimField.contains(point, game.player.getYRot()))) {
                        request.add("aim", RpgConfig.JSON.toJsonTree(new AttackAim(points))); ClientHooks.send("aimAttack", request.toString());
                    }
                }
            }
        } catch (Throwable error) { finish(error); }
    }

    private void pre(RenderFrameEvent.Pre event) {
        if (!initialized || done || scene < 0 || sceneStarted == 0) return;
        if (visualActive) RenderObservations.beginSubmittedFrame(event);
        renderStarted = System.nanoTime();
        continuationBeforeRender = counter("spearContinuationCalls");
        try {
            if (actualScene() >= 2 && actualScene() != 3 && CombatCamera.active()) {
                var game = Minecraft.getInstance(); double seconds = (renderStarted - sceneStarted) / 1e9;
                double desiredYaw=60*Math.sin(seconds*(actualScene()==5?5.1:1.7));
                double desiredPitch=22*Math.sin(seconds*(actualScene()==5?7.3:2.3));
                double beforeYaw=CombatCamera.yaw(),beforePitch=CombatCamera.pitch();
                double gain = Math.pow(game.options.sensitivity().get() * .6 + .2, 3) * 8;
                double dx=AttackAim.wrap(desiredYaw-beforeYaw)/(gain*.15)*(game.options.invertMouseX().get()?-1:1);
                double dy=(desiredPitch-beforePitch)/(gain*.15)*(game.options.invertMouseY().get()?-1:1);
                require(CombatCamera.mouse(dx,dy), "Native direct center-aim mouse input was rejected");
                require(Math.abs(AttackAim.wrap(CombatCamera.yaw()-desiredYaw))<.001&&Math.abs(CombatCamera.pitch()-desiredPitch)<.001,"Native center-aim camera missed its requested angles");
                require(Math.abs(AttackAim.wrap(game.player.getYRot()-CombatCamera.yaw()))<.001&&CombatCamera.cursorX()==0&&CombatCamera.cursorY()==0,"Native center aiming must turn the actor and retain a fixed reticle");
                // Sample input evidence at 4 Hz rather than allocating a full trace on every timed frame.
                if(renderStarted-lastCenterAimTraceNanos>=250_000_000L){
                    lastCenterAimTraceNanos=renderStarted;var trace=new JsonObject();trace.addProperty("sceneSeconds",seconds);
                    trace.addProperty("desiredCameraYaw",desiredYaw);trace.addProperty("desiredCameraPitch",desiredPitch);trace.addProperty("mouseInputDx",dx);trace.addProperty("mouseInputDy",dy);
                    trace.addProperty("actualCameraYaw",CombatCamera.yaw());trace.addProperty("actualCameraPitch",CombatCamera.pitch());trace.addProperty("actorYaw",game.player.getYRot());
                    trace.add("centerRayWorld",RpgConfig.JSON.toJsonTree(new org.joml.Vector3f(0,0,-1).rotate(game.gameRenderer.getMainCamera().rotation())));centerAimInputTrace.add(trace);
                }
                cursorSamples++;
            }
        } catch (Throwable error) { failure = error; }
    }

    private void post(RenderFrameEvent.Post event) {
        if (!initialized || done || scene < 0 || sceneStarted == 0) return;
        if (visualActive) {
            try { if (!visualStopped) visualCapture.frame(event); visualCapture.assertHealthy(); }
            catch (Throwable error) { failure = error; }
            return;
        }
        long now = System.nanoTime();
        try {
            long elapsed = now - sceneStarted;
            if (elapsed < WARMUP) {
                if (elapsed > 1_500_000_000L && !screenshotTaken) {
                    Screenshot.grab(root.toFile(), SCENES.get(scene) + ".png", Minecraft.getInstance().getMainRenderTarget(), 1, text -> {}); screenshotTaken = true;
                }
                lastPost = 0; return;
            }
            if (elapsed >= WARMUP + sampleDuration()) return;
            if (scene > 0) require(clientSceneReady(Minecraft.getInstance()), "Spear must actually be drawn for every timed render frame");
            if (sampleStart == 0) {
                sampleStart = now; gcCountStart = gcCount(); gcMillisStart = gcMillis();
                counterStart.clear(); for (String name : COUNTERS) counterStart.put(name, counter(name));
            }
            if (lastPost != 0) intervals.add((now - lastPost) / 1e6); lastPost = now;
            timestamps.add(now); renderTimes.add((now - renderStarted) / 1e6);
            boolean armored = !Minecraft.getInstance().player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
            if (armored && armorTransitionSeconds < 0) armorTransitionSeconds = (now - sampleStart) / 1e9;
            if (actualScene() == 5) {
                (armored ? armoredRenderTimes : unarmoredRenderTimes).add(renderTimes.getLast());
                if (!intervals.isEmpty()) (armored ? armoredIntervals : unarmoredIntervals).add(intervals.getLast());
            }
            if (scene > 0) observeCachedPose(now);
            String skill = ClientState.text(ClientState.snapshot(), "skill", "");
            if (actualScene() >= 4 && (skill.equals("spear_thrust") || skill.equals("spear_sweep"))) {
                attackFrames++; actions.addProperty(skill, (actions.has(skill) ? actions.get(skill).getAsLong() : 0) + 1);
            }
            long solves = counter("spearSolveCalls"), nativeFrame = counter("renderFrameId");
            if (previousSolveCalls >= 0 && nativeFrame != previousNativeFrame && solves - previousSolveCalls > 1) duplicateSolveFrames++;
            previousSolveCalls = solves; previousNativeFrame = nativeFrame;
            double interval = intervals.isEmpty() ? 0 : intervals.getLast(), render = renderTimes.getLast();
            if (interval > 25 || render > 25) {
                var slow = new JsonObject(); slow.addProperty("nativePostNanos", now); slow.addProperty("sceneSeconds", elapsed / 1e9);
                slow.addProperty("frameIntervalMs", interval); slow.addProperty("renderMs", render); slow.addProperty("phase", ClientState.text(ClientState.snapshot(), "phase", ""));
                slow.addProperty("skill", skill); slow.addProperty("lastSpearSolveMs", counter("lastSpearSolveNanos") / 1e6);
                long continuationAfterRender = counter("spearContinuationCalls");
                slow.addProperty("continuationAttemptsInFrame", continuationBeforeRender < 0 || continuationAfterRender < 0 ? -1 : continuationAfterRender - continuationBeforeRender);
                slow.addProperty("actualIronArmorSubmitted", armored);
                slow.addProperty("gcCount", gcCount() - gcCountStart); slow.addProperty("gcMillis", gcMillis() - gcMillisStart); slowFrames.add(slow);
            }
        } catch (Throwable error) { failure = error; }
    }

    private void observeCachedPose(long now) throws Exception {
        @SuppressWarnings("unchecked") var bindings = (Map<UUID, Object>) bindingsField.get(null);
        Object binding = bindings.get(Minecraft.getInstance().player.getUUID()); if (binding == null) return;
        if (sampledField == null) { sampledField = binding.getClass().getDeclaredField("sampled"); sampledField.setAccessible(true); }
        // Inspect the already submitted frame. Calling frame() or setupAnim() here could hide extra solver work in the benchmark.
        var frame = (PlayerAnimations.Frame) sampledField.get(binding); if (frame == null) return;
        cachedPoseFrames++;
        double tick = frame.sampledTick(), partial = tick - Math.floor(tick);
        if (Double.isFinite(previousPoseTick)) maximumNativePoseGapTicks = Math.max(maximumNativePoseGapTicks, tick - previousPoseTick);
        if (Double.isFinite(previousPoseTick) && tick > previousPoseTick && Math.floor(tick) == Math.floor(previousPoseTick) && Math.abs(partial - previousPartial) > 1e-6) distinctSubtickPairs++;
        previousPoseTick = tick; previousPartial = partial;
        var hand = frame.world().get("right_hand"); if (hand == null) return;
        float x = hand.m30(), y = hand.m31(), z = hand.m32();
        if (Float.isFinite(previousPalmX) && Math.abs(x - previousPalmX) + Math.abs(y - previousPalmY) + Math.abs(z - previousPalmZ) > .00001) changedPosePairs++;
        previousPalmX = x; previousPalmY = y; previousPalmZ = z;
        for (String name : OBSERVED_JOINTS) {
            var matrix = frame.world().get(name); if (matrix == null) continue;
            matrix.getUnnormalizedRotation(jointRotation).normalize();
            var old = previousJointRotations.get(name);
            if (old == null) previousJointRotations.put(name, new Quaternionf(jointRotation));
            else {
                double step = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(old.dot(jointRotation)), 0, 1)));
                maximumJointSteps.merge(name, step, Math::max); if (step > 25) poseJumpsOver25Degrees++; old.set(jointRotation);
            }
        }
        // Read the already submitted FK at native render cadence. No frame(), setupAnim() or IK solve is invoked.
        if (now - lastQuality >= 16_000_000L) {
            lastQuality = now; long startedQuality = System.nanoTime(); qualityFrames++;
            var right = frame.world().get("right_hand"); var left = frame.world().get("left_hand");
            require(left != null, "Missing submitted spear support hand");
            right.transformPosition(rear.set(0,-6.25f,0)); left.transformPosition(front.set(0,-6.25f,0));
            right.transformDirection(shaft.set(0,0,1)).normalize(); separation.set(front).sub(rear);
            double spacing = separation.length(), error = separation.cross(shaft).length();
            minimumGripSpacing = Math.min(minimumGripSpacing, spacing); maximumGripSpacing = Math.max(maximumGripSpacing, spacing);
            maximumGripAxisError = Math.max(maximumGripAxisError, error);
            if (!Double.isFinite(spacing) || spacing < SpearGrip.MINIMUM_CM - .04 || spacing > SpearGrip.MAXIMUM_CM + .04 || error > .04) badGripFrames++;
            if (!SpearAimPose.hasClearShaft(frame.world())) shaftFailures++;
            qualityNanos += System.nanoTime() - startedQuality;
        }
    }

    private JsonObject distribution(List<Double> values) {
        var result = new JsonObject(); if (values.isEmpty()) return result;
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        result.addProperty("p50Ms", sorted[(int) Math.floor((sorted.length - 1) * .50)]);
        result.addProperty("p95Ms", sorted[(int) Math.floor((sorted.length - 1) * .95)]);
        result.addProperty("p99Ms", sorted[(int) Math.floor((sorted.length - 1) * .99)]);
        result.addProperty("maxMs", sorted[sorted.length - 1]);
        result.addProperty("meanMs", values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        result.addProperty("over16Ms", values.stream().filter(value -> value > 1000.0 / 60).count());
        result.addProperty("over33Ms", values.stream().filter(value -> value > 1000.0 / 30).count());
        result.addProperty("over50Ms", values.stream().filter(value -> value > 50).count());
        result.addProperty("over100Ms", values.stream().filter(value -> value > 100).count()); return result;
    }

    private void summarize() {
        require(timestamps.size() >= 5, "Insufficient native rendered frames for " + SCENES.get(scene));
        if (actualScene() >= 4) require(attackFrames > 0, "Timed attack scene had no real server-synchronized attack frames");
        if (scene > 0) {
            require(cachedPoseFrames > 0, "Spear rig did not reach the real local player renderer");
            require(OfficialHeldItems.submissions() > officialStart, "Official ItemStack renderer did not receive spear");
            require(qualityFrames > 0, "No submitted FK quality frames");
            if (Boolean.getBoolean("fsrpg.spearPerformanceRequireQuality")) {
                require(badGripFrames == 0 && shaftFailures == 0, "Actual submitted grip/shaft failures: grip=" + badGripFrames + " shaft=" + shaftFailures);
                require(distinctSubtickPairs > 0 && changedPosePairs > 0, "Submitted spear motion must change within game ticks");
            }
        }
        double duration = (timestamps.getLast() - timestamps.getFirst()) / 1e9;
        if (scene > 0 && Boolean.getBoolean("fsrpg.spearPerformanceRequireQuality"))
            require((timestamps.size() - 1) / duration >= 30 && qualityFrames / duration >= 30, "Native spear motion / submitted quality observation must sustain 30 Hz");
        var result = new JsonObject(); result.addProperty("scene", SCENES.get(scene)); result.addProperty("frames", timestamps.size());
        result.addProperty("sampleSeconds", duration); result.addProperty("fps", (timestamps.size() - 1) / duration);
        result.addProperty("motionAtLeast30Hz", (timestamps.size() - 1) / duration >= 30);
        result.addProperty("equipmentSetupSecondsBeforeWarmup", scenePreparationSeconds);
        result.addProperty("warmupReadinessRestarts", warmupReadinessRestarts);
        result.add("frameInterval", distribution(intervals)); result.add("renderPreToPost", distribution(renderTimes));
        result.addProperty("changedCachedPosePairs", changedPosePairs); result.addProperty("nativeSubtickPosePairs", distinctSubtickPairs);
        result.addProperty("observedRenderedRigFrames", cachedPoseFrames);
        result.addProperty("actualMouseSamples", cursorSamples); result.addProperty("attackFrames", attackFrames);
        result.addProperty("aimInputMode","center-reticle-camera-angle");result.add("centerAimInputTrace",centerAimInputTrace.deepCopy());
        result.addProperty("officialHeldItemSubmissions", OfficialHeldItems.submissions() - officialStart);
        result.addProperty("qualityFrames", qualityFrames); result.addProperty("qualityObservedHz", qualityFrames / duration);
        result.addProperty("qualityObserverTimeMs", qualityNanos / 1e6); result.addProperty("completeShaftClearFailures", shaftFailures);
        result.addProperty("badGripFrames", badGripFrames); result.addProperty("maximumGripAxisErrorCm", maximumGripAxisError);
        result.addProperty("maximumNativePoseGapTicks", maximumNativePoseGapTicks); result.addProperty("jointFrameStepsOver25Degrees", poseJumpsOver25Degrees);
        result.add("maximumNativeJointStepDegrees", RpgConfig.JSON.toJsonTree(maximumJointSteps));
        result.addProperty("minimumGripSpacingCm", Double.isFinite(minimumGripSpacing) ? minimumGripSpacing : 0); result.addProperty("maximumGripSpacingCm", maximumGripSpacing);
        result.addProperty("garbageCollectionCount", gcCount() - gcCountStart); result.addProperty("garbageCollectionTimeMs", gcMillis() - gcMillisStart);
        result.addProperty("framesWithMoreThanOneSpearSolve", duplicateSolveFrames);
        if (actualScene() == 5) {
            require(armorTransitionSeconds >= 0 && !armoredIntervals.isEmpty(), "Stress scene must render actual server-equipped armor");
            result.addProperty("actualArmorTransitionSampleSeconds", armorTransitionSeconds);
            var normal = new JsonObject(); normal.addProperty("frames", unarmoredRenderTimes.size()); normal.add("frameInterval", distribution(unarmoredIntervals)); normal.add("renderPreToPost", distribution(unarmoredRenderTimes));
            var armor = new JsonObject(); armor.addProperty("frames", armoredRenderTimes.size()); armor.add("frameInterval", distribution(armoredIntervals)); armor.add("renderPreToPost", distribution(armoredRenderTimes));
            result.add("unarmoredStress", normal); result.add("ironArmorStress", armor);
        }
        if (Boolean.getBoolean("fsrpg.spearPerformanceRequireQuality") && counter("spearSolveCalls") >= 0)
            require(duplicateSolveFrames == 0, "One local actor was solved more than once in the same native frame");
        var counters = new JsonObject(); for (String name : COUNTERS) {
            long value = counter(name), initial = counterStart.getOrDefault(name, -1L);
            counters.addProperty(name, value < 0 || initial < 0 ? -1 : name.equals("maxSpearSolveNanos") || name.equals("lastSpearSolveNanos") ? value : value - initial);
        }
        result.add("solverCounters", counters); result.add("slowFramesOver25Ms", RpgConfig.JSON.toJsonTree(slowFrames));
        result.add("sourcePostTimestampsNanos", RpgConfig.JSON.toJsonTree(timestamps));
        result.add("sourceFrameIntervalsMs", RpgConfig.JSON.toJsonTree(intervals)); results.add(result);
        System.out.println("SPEAR PERFORMANCE RESULT " + SCENES.get(scene) + " fps=" + (timestamps.size() - 1) / duration + " " + result.get("frameInterval"));
    }

    private void finish(Throwable error) {
        if (done) return; done = true;
        try {
            if (error == null && Boolean.getBoolean("fsrpg.spearPerformanceExpectFastThrust") && serverThrusts.size() < 8)
                error = new AssertionError("At least eight completed real server thrusts must be timed");
            var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("capturedAtUtc", Instant.now().toString()); report.addProperty("captureEvent", "RenderFrameEvent.Post, native System.nanoTime timestamps");
            report.addProperty("method", "Same local player, 1000x680, distance 3, VSync off, uncapped active render; 3-second warmup + 8-second measurement for five scenes, then optional 30-second direct camera-angle center aiming, movement and 4 Hz attack pressure (one intent per five client ticks). Native Pre/Post timings, P99/max and slow-frame phase. Read-only submitted FK quality at about 60 Hz, included observer overhead; no video or detached solves. Screenshots only in discarded warmup.");
            if (USER_SETTINGS) report.addProperty("method", "Official video settings confirmation in isolated development instance: same packaged JAR and copied world, VSync enabled, 120 FPS cap, render/simulation distance 12, 1000x680. Control: 8-second warmup + 8-second sample; spear direct center aim / movement / 4 Hz short thrust / iron-armor stress: 8-second warmup + 30-second sample. Native Pre/Post timestamps and read-only submitted FK; official options/config/worlds untouched.");
            report.addProperty("officialVideoSettingsConfirmation", USER_SETTINGS); report.addProperty("configuredVsync", USER_SETTINGS);
            report.addProperty("configuredFpsCap", USER_SETTINGS ? 120 : 260); report.addProperty("configuredRenderDistance", USER_SETTINGS ? 12 : 3);
            report.addProperty("completedScenes", results.size()); report.addProperty("copiedWorld", System.getProperty("fsrpg.spearPerformanceWorld"));
            report.addProperty("dismissedPauseScreens", dismissedPauseScreens); report.addProperty("dismissedTimedPauseScreens", dismissedTimedPauseScreens);
            report.add("scenes", results); report.add("actualAttackFramesBySkill", actions); RpgSmoke.windowEnvironment(report);
            synchronized (serverThrusts) { report.add("realServerThrustTimings", serverThrusts.deepCopy()); }
            synchronized (canceledServerThrusts) { report.add("canceledServerThrusts", canceledServerThrusts.deepCopy()); }
            if (visualActive) report.add("separateUntimedNativeVisualCapture", visualCapture.summary());
            var source = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); report.addProperty("modJar", source.toString());
            report.addProperty("modJarSha256", Files.isRegularFile(source) ? HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))) : "loose-classes");
            RpgConfig.atomicWrite(root.resolve("spear-performance-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("SPEAR PERFORMANCE REVIEW COMPLETE passed=" + (error == null) + " " + root); if (error != null) error.printStackTrace();
        } catch (Throwable reportError) { reportError.printStackTrace(); }
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) {
            game.options.framerateLimit().set(previousFps); game.options.renderDistance().set(previousDistance); game.options.guiScale().set(previousScale); game.options.fov().set(previousFov);
            game.options.simulationDistance().set(previousSimulation);
            game.options.pauseOnLostFocus = previousPause; game.options.enableVsync().set(previousVsync); game.options.hideGui = previousHidden; game.options.setCameraType(previousCamera); game.setWindowActive(previousActive);
        }
        game.stop();
    }
}

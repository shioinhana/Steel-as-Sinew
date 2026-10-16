package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
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
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientHooks;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** No movie readback or detached pose solves: native, uncapped packaged-client frame timings. */
final class SpearPerformanceReview {
    private static final List<String> SCENES = List.of("sheathed-control", "spear-idle", "spear-aim-sweep", "spear-movement", "spear-attacks");
    private static final long WARMUP = 3_000_000_000L, SAMPLE = 8_000_000_000L;
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray results = new JsonArray();
    private final ArrayList<Double> intervals = new ArrayList<>(), renderTimes = new ArrayList<>();
    private final ArrayList<Long> timestamps = new ArrayList<>();
    private volatile Throwable failure;
    private volatile boolean ready;
    private boolean initialized, done, screenshotTaken, previousActive, previousPause, previousVsync, previousHidden;
    private int initializationTicks, scene = -1, sceneTick, previousFps, previousDistance, previousScale, previousFov;
    private CameraType previousCamera;
    private long started, sceneStarted, lastPost, renderStarted, sampleStart, officialStart, changedPosePairs, distinctSubtickPairs, attackFrames;
    private long cursorSamples, cachedPoseFrames;
    private double previousPoseTick = Double.NaN, previousPartial = Double.NaN;
    private float previousPalmX = Float.NaN, previousPalmY, previousPalmZ;
    private final JsonObject actions = new JsonObject();
    private Field bindingsField, sampledField;

    SpearPerformanceReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(this::post);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
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
        previousScale = game.options.guiScale().get(); previousFov = game.options.fov().get();
        previousPause = game.options.pauseOnLostFocus; previousVsync = game.options.enableVsync().get(); previousHidden = game.options.hideGui;
        previousCamera = game.options.getCameraType(); previousActive = game.isWindowActive();
        game.options.framerateLimit().set(260); game.options.enableVsync().set(false); game.options.pauseOnLostFocus = false;
        game.options.renderDistance().set(3); game.options.guiScale().set(2); game.options.fov().set(65);
        game.options.hideGui = false; game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        long handle = game.getWindow().handle(); var bounds = environment.getAsJsonObject("monitorBounds");
        GLFW.glfwSetWindowSize(handle, 1000, 680); GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 35, bounds.get("y").getAsInt() + 45);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 长枪实机性能 · DISPLAY5"); game.resizeDisplay();
        game.getTutorial().setStep(TutorialSteps.NONE); KeyMapping.releaseAll();
        // Internal game input flag only. This never focuses the OS window.
        game.setWindowActive(true); initialized = true; started = System.nanoTime();
        bindingsField = PlayerAnimations.class.getDeclaredField("bindings"); bindingsField.setAccessible(true);
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

    private void beginScene(Minecraft game) {
        if (scene >= 0) summarize();
        scene++; if (scene == SCENES.size()) { finish(null); return; }
        System.out.println("SPEAR PERFORMANCE SCENE " + SCENES.get(scene) + " " + Instant.now());
        KeyMapping.releaseAll(); intervals.clear(); renderTimes.clear(); timestamps.clear(); sceneTick = 0;
        sceneStarted = System.nanoTime(); sampleStart = lastPost = 0; screenshotTaken = false;
        changedPosePairs = distinctSubtickPairs = attackFrames = cursorSamples = cachedPoseFrames = 0;
        previousPoseTick = previousPartial = Double.NaN; previousPalmX = Float.NaN; officialStart = OfficialHeldItems.submissions();
        if (scene == 1) server(p -> {
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SPEAR));
            RpgServer.instance().action(p, "weaponStyle", "spear"); var actor = RpgSmoke.actor(p); actor.setResources(100, 100);
            if (!actor.drawn()) RpgServer.instance().action(p, "draw", ""); RpgServer.instance().action(p, "status", "");
        });
    }

    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (done || game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) { if (++initializationTicks >= 60) initialize(game); return; }
            if (failure != null) throw new IllegalStateException("Asynchronous performance fixture failed", failure);
            game.getFramerateLimitTracker().onInputReceived();
            if (System.nanoTime() - started > 140_000_000_000L) throw new AssertionError("Performance review timeout");
            if (!ready) return;
            if (sceneTick % 20 == 0) {
                var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
                require("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()), "Performance window moved outside DISPLAY5");
            }
            if (scene < 0 || System.nanoTime() - sceneStarted >= WARMUP + SAMPLE) beginScene(game);
            if (done) return; sceneTick++;
            if (scene > 0 && System.nanoTime() - sceneStarted > WARMUP) require(ClientState.drawn() && CombatCamera.style().equals("spear"), "Spear must actually be drawn during timed scenes");
            game.options.keyUp.setDown(scene == 3 && sceneTick % 160 < 40);
            game.options.keyRight.setDown(scene == 3 && sceneTick % 160 >= 40 && sceneTick % 160 < 80);
            game.options.keyDown.setDown(scene == 3 && sceneTick % 160 >= 80 && sceneTick % 160 < 120);
            game.options.keyLeft.setDown(scene == 3 && sceneTick % 160 >= 120);
            if (scene == 4) {
                if (sceneTick % 30 == 1) server(p -> RpgSmoke.actor(p).setResources(100, 100));
                if (sceneTick % 60 == 15) CombatCamera.attack("left");
                if (sceneTick % 60 == 45) {
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
        if (!initialized || done || scene < 0) return;
        renderStarted = System.nanoTime();
        try {
            if (scene >= 2 && scene != 3 && CombatCamera.active()) {
                var game = Minecraft.getInstance(); double seconds = (renderStarted - sceneStarted) / 1e9;
                var field = CombatCamera.spearFrame();
                double x = field.left() + (.5 + .47 * Math.sin(seconds * 1.7)) * (field.right() - field.left());
                double y = field.top() + (.5 + .47 * Math.sin(seconds * 2.3)) * (field.bottom() - field.top());
                double gain = Math.pow(game.options.sensitivity().get() * .6 + .2, 3) * 8;
                require(CombatCamera.mouse((x - CombatCamera.cursorX()) / gain * (game.options.invertMouseX().get() ? -1 : 1),
                        (y - CombatCamera.cursorY()) / gain * (game.options.invertMouseY().get() ? -1 : 1)), "Native spear mouse input was rejected");
                cursorSamples++;
            }
        } catch (Throwable error) { failure = error; }
    }

    private void post(RenderFrameEvent.Post event) {
        if (!initialized || done || scene < 0) return;
        long now = System.nanoTime();
        try {
            long elapsed = now - sceneStarted;
            if (elapsed < WARMUP) {
                if (elapsed > 1_500_000_000L && !screenshotTaken) {
                    Screenshot.grab(root.toFile(), SCENES.get(scene) + ".png", Minecraft.getInstance().getMainRenderTarget(), 1, text -> {}); screenshotTaken = true;
                }
                lastPost = 0; return;
            }
            if (elapsed >= WARMUP + SAMPLE) return;
            if (sampleStart == 0) sampleStart = now;
            if (lastPost != 0) intervals.add((now - lastPost) / 1e6); lastPost = now;
            timestamps.add(now); renderTimes.add((now - renderStarted) / 1e6);
            if (scene > 0) observeCachedPose();
            String skill = ClientState.text(ClientState.snapshot(), "skill", "");
            if (scene == 4 && (skill.equals("spear_thrust") || skill.equals("spear_sweep"))) {
                attackFrames++; actions.addProperty(skill, (actions.has(skill) ? actions.get(skill).getAsLong() : 0) + 1);
            }
        } catch (Throwable error) { failure = error; }
    }

    private void observeCachedPose() throws Exception {
        @SuppressWarnings("unchecked") var bindings = (Map<UUID, Object>) bindingsField.get(null);
        Object binding = bindings.get(Minecraft.getInstance().player.getUUID()); if (binding == null) return;
        if (sampledField == null) { sampledField = binding.getClass().getDeclaredField("sampled"); sampledField.setAccessible(true); }
        // Inspect the already submitted frame. Calling frame() or setupAnim() here could hide extra solver work in the benchmark.
        var frame = (PlayerAnimations.Frame) sampledField.get(binding); if (frame == null) return;
        cachedPoseFrames++;
        double tick = frame.sampledTick(), partial = tick - Math.floor(tick);
        if (Double.isFinite(previousPoseTick) && tick > previousPoseTick && Math.floor(tick) == Math.floor(previousPoseTick) && Math.abs(partial - previousPartial) > 1e-6) distinctSubtickPairs++;
        previousPoseTick = tick; previousPartial = partial;
        var hand = frame.world().get("right_hand"); if (hand == null) return;
        float x = hand.m30(), y = hand.m31(), z = hand.m32();
        if (Float.isFinite(previousPalmX) && Math.abs(x - previousPalmX) + Math.abs(y - previousPalmY) + Math.abs(z - previousPalmZ) > .00001) changedPosePairs++;
        previousPalmX = x; previousPalmY = y; previousPalmZ = z;
    }

    private JsonObject distribution(List<Double> values) {
        var result = new JsonObject(); if (values.isEmpty()) return result;
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        result.addProperty("p50Ms", sorted[(int) Math.floor((sorted.length - 1) * .50)]);
        result.addProperty("p95Ms", sorted[(int) Math.floor((sorted.length - 1) * .95)]);
        result.addProperty("p99Ms", sorted[(int) Math.floor((sorted.length - 1) * .99)]);
        result.addProperty("maxMs", sorted[sorted.length - 1]);
        result.addProperty("meanMs", values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        result.addProperty("over33Ms", values.stream().filter(value -> value > 1000.0 / 30).count());
        result.addProperty("over50Ms", values.stream().filter(value -> value > 50).count()); return result;
    }

    private void summarize() {
        require(timestamps.size() >= 5, "Insufficient native rendered frames for " + SCENES.get(scene));
        if (scene > 0) {
            require(cachedPoseFrames > 0, "Spear rig did not reach the real local player renderer");
            require(OfficialHeldItems.submissions() > officialStart, "Official ItemStack renderer did not receive spear");
        }
        double duration = (timestamps.getLast() - timestamps.getFirst()) / 1e9;
        var result = new JsonObject(); result.addProperty("scene", SCENES.get(scene)); result.addProperty("frames", timestamps.size());
        result.addProperty("sampleSeconds", duration); result.addProperty("fps", (timestamps.size() - 1) / duration);
        result.addProperty("motionAtLeast30Hz", (timestamps.size() - 1) / duration >= 30);
        result.add("frameInterval", distribution(intervals)); result.add("renderPreToPost", distribution(renderTimes));
        result.addProperty("changedCachedPosePairs", changedPosePairs); result.addProperty("nativeSubtickPosePairs", distinctSubtickPairs);
        result.addProperty("observedRenderedRigFrames", cachedPoseFrames);
        result.addProperty("actualMouseSamples", cursorSamples); result.addProperty("attackFrames", attackFrames);
        result.addProperty("officialHeldItemSubmissions", OfficialHeldItems.submissions() - officialStart);
        result.add("sourcePostTimestampsNanos", RpgConfig.JSON.toJsonTree(timestamps));
        result.add("sourceFrameIntervalsMs", RpgConfig.JSON.toJsonTree(intervals)); results.add(result);
        System.out.println("SPEAR PERFORMANCE RESULT " + SCENES.get(scene) + " fps=" + (timestamps.size() - 1) / duration + " " + result.get("frameInterval"));
    }

    private void finish(Throwable error) {
        if (done) return; done = true;
        try {
            var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("capturedAtUtc", Instant.now().toString()); report.addProperty("captureEvent", "RenderFrameEvent.Post, native System.nanoTime timestamps");
            report.addProperty("method", "Same local player, 1000x680, distance 3, VSync off, FPS cap 260, 3-second warmup + 8-second measurement per scene; screenshots only in discarded warmup; no video or detached solves");
            report.addProperty("completedScenes", results.size()); report.addProperty("copiedWorld", System.getProperty("fsrpg.spearPerformanceWorld"));
            report.add("scenes", results); report.add("actualAttackFramesBySkill", actions); RpgSmoke.windowEnvironment(report);
            var source = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); report.addProperty("modJar", source.toString());
            report.addProperty("modJarSha256", Files.isRegularFile(source) ? HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))) : "loose-classes");
            RpgConfig.atomicWrite(root.resolve("spear-performance-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("SPEAR PERFORMANCE REVIEW COMPLETE passed=" + (error == null) + " " + root); if (error != null) error.printStackTrace();
        } catch (Throwable reportError) { reportError.printStackTrace(); }
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) {
            game.options.framerateLimit().set(previousFps); game.options.renderDistance().set(previousDistance); game.options.guiScale().set(previousScale); game.options.fov().set(previousFov);
            game.options.pauseOnLostFocus = previousPause; game.options.enableVsync().set(previousVsync); game.options.hideGui = previousHidden; game.options.setCameraType(previousCamera); game.setWindowActive(previousActive);
        }
        game.stop();
    }
}

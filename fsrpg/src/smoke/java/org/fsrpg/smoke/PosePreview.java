package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.client.PoseLayers;
import org.fsrpg.config.RpgConfig;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Fast static native-render iteration, deliberately separate from gameplay/motion approval. */
final class PosePreview {
    record Stance(String label, String action, double seconds) {}
    private record View(String label, double x, double z, double eyeY, double targetY, int fov) {}
    private static final List<Stance> STANCES = List.of(new Stance("neutral", "dual_idle_00", 0),
            new Stance("both-left", "dual_idle_11", 0), new Stance("both-right", "dual_idle_22", 0));
    private static final List<View> VIEWS = List.of(
            new View("front", .5, 4.1, 81.05, 81.05, 42),
            new View("back", .5, -3.1, 81.05, 81.05, 42),
            new View("oblique", 3.05, 3.05, 81.05, 81.05, 42),
            new View("back-grip-close", 1.1, -1.1, 81.1, 81.0, 40));
    private static final int WARMUP_TICKS = 40, VIEW_TICKS = 6;
    record Plan(List<Stance> poses, List<Integer> profiles, List<View> views) {
        int total() { return poses.size() * profiles.size() * views.size(); }
    }
    private Plan plan = new Plan(STANCES, List.of(0, 1, 2, 3), VIEWS);
    private Path samplePlanPath;
    private String samplePlanSha;
    private static final Path ROOT = Path.of(System.getProperty("fsrpg.poseEvidence", "../../verification/pose-preview")).toAbsolutePath().normalize();
    private final Path directory = ROOT.resolve("run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
    private final JsonArray images = new JsonArray();
    private final AtomicInteger pending = new AtomicInteger();
    private Fixture actor;
    private ArmorStand camera;
    private ModelDocument model;
    private Path modelPath;
    private String modelSha;
    private Map<String, Pose> pose;
    private PlayerAnimations.Frame rendered;
    private JsonObject current;
    private volatile Throwable failure;
    private volatile boolean serverReady;
    private int tick, scene = -1, renderedFrames;
    private long startedNanos, mainBefore, offBefore;
    private float cameraYaw, cameraPitch;
    private boolean captureRequested, done;

    private static final class Fixture extends RemotePlayer {
        PlayerSkin skin;
        Fixture(ClientLevel level, PlayerSkin skin) {
            super(level, new GameProfile(UUID.fromString("ed0201d0-6570-42ad-9235-d5afeca11000"), "DualPosePreview"));
            this.skin = skin; setId(200101); setNoGravity(true);
        }
        @Override public PlayerSkin getSkin() { return skin; }
    }

    PosePreview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::playerRender);
        NeoForge.EVENT_BUS.addListener(this::cameraAngles);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e) -> { if (!done) e.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key e) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void playerRender(RenderPlayerEvent.Pre<?> event) {
        if (!done && actor != null && pose != null && event.getRenderState().id == actor.getId()) {
            AvatarRenderState state = event.getRenderState();
            PlayerAnimations.preview(state, model, pose);
            rendered = PlayerAnimations.frame(state); renderedFrames++;
        }
    }
    private void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!done && camera != null) { event.setYaw(cameraYaw); event.setPitch(cameraPitch); event.setRoll(0); }
    }
    private static JsonObject planJson(Plan plan) {
        var result = new JsonObject(); result.add("poses", RpgConfig.JSON.toJsonTree(plan.poses));
        result.add("profiles", RpgConfig.JSON.toJsonTree(plan.profiles));
        result.add("views", RpgConfig.JSON.toJsonTree(plan.views.stream().map(View::label).toList()));
        return result;
    }
    private static void keys(JsonObject object, Set<String> allowed, String context) {
        for (String key : object.keySet()) if (!allowed.contains(key))
            throw new IllegalArgumentException("Unknown " + context + " field: " + key);
    }
    private static JsonArray array(JsonObject object, String name, int limit) {
        if (!object.has(name) || !object.get(name).isJsonArray()) throw new IllegalArgumentException(name + " must be an array");
        JsonArray result = object.getAsJsonArray(name);
        if (result.isEmpty() || result.size() > limit) throw new IllegalArgumentException(name + " must contain 1.." + limit + " entries");
        return result;
    }
    private static String text(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isString())
            throw new IllegalArgumentException(name + " must be a string");
        String value = object.get(name).getAsString();
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Unsafe " + name + ": " + value);
        return value;
    }
    /** Parse all requested samples before changing the fixture or starting any screenshot. */
    static Plan parsePlan(JsonObject json, ModelDocument model) {
        keys(json, Set.of("poses", "profiles", "views"), "sample plan");
        var poses = new ArrayList<Stance>(); var labels = new HashSet<String>();
        for (var item : array(json, "poses", 64)) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("Each pose must be an object");
            var pose = item.getAsJsonObject(); keys(pose, Set.of("label", "action", "seconds"), "pose");
            String label = text(pose, "label"), action = text(pose, "action");
            if (!labels.add(label)) throw new IllegalArgumentException("Duplicate pose label: " + label);
            var clip = model.rig().clips().get(action);
            if (clip == null) throw new IllegalArgumentException("Unknown pose action: " + action);
            if (!pose.has("seconds") || !pose.get("seconds").isJsonPrimitive() || !pose.getAsJsonPrimitive("seconds").isNumber())
                throw new IllegalArgumentException("Pose seconds must be a number: " + label);
            double seconds = pose.get("seconds").getAsDouble();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > clip.duration())
                throw new IllegalArgumentException("Pose seconds outside clip [0," + clip.duration() + "]: " + label + "=" + seconds);
            poses.add(new Stance(label, action, seconds));
        }
        var profiles = new ArrayList<Integer>();
        if (!json.has("profiles")) profiles.addAll(List.of(0, 1, 2, 3));
        else for (var item : array(json, "profiles", 4)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Profile must be an integer 0..3");
            double value = item.getAsDouble();
            if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > 3 || profiles.contains((int)value))
                throw new IllegalArgumentException("Profile must be a unique integer 0..3: " + value);
            profiles.add((int)value);
        }
        var views = new ArrayList<View>();
        if (!json.has("views")) views.addAll(VIEWS);
        else for (var item : array(json, "views", VIEWS.size())) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("View must name a configured camera");
            String label = item.getAsString();
            View view = VIEWS.stream().filter(v -> v.label.equals(label)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown view: " + label));
            if (views.contains(view)) throw new IllegalArgumentException("Duplicate view: " + label);
            views.add(view);
        }
        var result = new Plan(List.copyOf(poses), List.copyOf(profiles), List.copyOf(views));
        if (result.total() > 64) throw new IllegalArgumentException("Sample plan exceeds 64 screenshots: " + result.total());
        return result;
    }
    private void loadPlan() throws Exception {
        String configured = System.getProperty("fsrpg.poseSamples");
        if (configured == null) { plan = parsePlan(planJson(plan), model); return; }
        samplePlanPath = Path.of(configured);
        if (!samplePlanPath.isAbsolute()) throw new IllegalArgumentException("fsrpg.poseSamples must be an absolute JSON file path");
        samplePlanPath = samplePlanPath.normalize();
        if (!Files.isRegularFile(samplePlanPath) || Files.size(samplePlanPath) > 65536)
            throw new IllegalArgumentException("Sample plan must be a JSON file at most 64 KiB: " + samplePlanPath);
        byte[] bytes = Files.readAllBytes(samplePlanPath);
        samplePlanSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var json = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!json.isJsonObject()) throw new IllegalArgumentException("Sample plan root must be an object");
        plan = parsePlan(json.getAsJsonObject(), model);
    }
    private void initialize(Minecraft game) throws Exception {
        startedNanos = System.nanoTime();
        Files.createDirectories(directory.resolve("screenshots"));
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        if (!environment.get("monitorAdapter").getAsString().equals("\\\\.\\DISPLAY5"))
            throw new IllegalStateException("Pose preview requires the configured DISPLAY5 monitor: " + environment);
        modelPath = FMLPaths.CONFIGDIR.get().resolve("fsrpg/models/dual_blade.fsvoxel").toAbsolutePath().normalize();
        model = ModelLoader.load(modelPath); modelSha = sha(modelPath);
        loadPlan();
        actor = new Fixture(game.level, game.player.getSkin()); game.level.addEntity(actor);
        camera = new ArmorStand(game.level, .5, 80, -3);
        game.setCameraEntity(camera); game.options.setCameraType(CameraType.FIRST_PERSON);
        game.options.hideGui = true; game.options.pauseOnLostFocus = false;
        game.options.framerateLimit().set(60); game.options.enableVsync().set(false);
        game.options.renderDistance().set(4); game.options.guiScale().set(2); game.resizeDisplay();
        GLFW.glfwSetWindowTitle(game.getWindow().handle(), "FSRPG 双刀姿态快速预览 · DISPLAY5");
        UUID playerId = game.player.getUUID();
        game.getSingleplayerServer().execute(() -> {
            try {
                var player = game.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                if (RpgSmoke.actor(player).drawn()) RpgServer.instance().action(player, "draw", "");
                player.setGameMode(GameType.SPECTATOR); player.connection.teleport(.5, 82, -12, 0, 0);
                player.level().setDayTime(6000); player.level().setWeatherParameters(6000, 0, false, false);
                for (int x=-7; x<=7; x++) for (int z=-7; z<=7; z++)
                    player.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                serverReady = true;
            } catch (Throwable error) { failure = error; }
        });
    }
    private void configure(Minecraft game, int index) {
        scene = index; rendered = null; renderedFrames = 0;
        int profile = plan.profiles.get(index / (plan.poses.size() * plan.views.size()));
        var stance = plan.poses.get(index / plan.views.size() % plan.poses.size());
        var view = plan.views.get(index % plan.views.size());
        boolean slim = profile % 2 == 1, armor = profile >= 2;
        var skin = game.player.getSkin(); actor.skin = new PlayerSkin(skin.body(), null, null, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, false);
        actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        actor.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
        actor.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        actor.setItemSlot(EquipmentSlot.CHEST, armor ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY);
        actor.setItemSlot(EquipmentSlot.LEGS, armor ? new ItemStack(Items.DIAMOND_LEGGINGS) : ItemStack.EMPTY);
        actor.setItemSlot(EquipmentSlot.FEET, armor ? new ItemStack(Items.DIAMOND_BOOTS) : ItemStack.EMPTY);
        pose = new PoseLayers.Playback(model.rig(), stance.action).sample(stance.seconds, 0, 0, 0, 0, .25f, 55).pose();
        cameraYaw = (float) Math.toDegrees(Math.atan2(view.x - .5, .5 - view.z));
        cameraPitch = (float) Math.toDegrees(Math.atan2(view.eyeY - view.targetY, Math.hypot(view.x - .5, view.z - .5)));
        camera.setPos(view.x, view.eyeY - camera.getEyeHeight(), view.z);
        camera.setYRot(cameraYaw); camera.setXRot(cameraPitch); camera.setOldPosAndRot();
        game.options.fov().set(view.fov);
        mainBefore = OfficialHeldItems.submissions(); offBefore = OfficialHeldItems.offhandAttachedSubmissions();
        current = new JsonObject();
        String name = String.format("%02d-%s-%s-%s-%s", index, slim ? "slim" : "stock", armor ? "armor" : "skin", stance.label, view.label);
        current.addProperty("file", "screenshots/" + name + ".png"); current.addProperty("name", name);
        current.addProperty("action", stance.action); current.addProperty("sampledClipSeconds", stance.seconds);
        current.addProperty("profile", profile);
        current.addProperty("skinModel", slim ? "slim" : "wide"); current.addProperty("armor", armor); current.addProperty("view", view.label);
        current.addProperty("cameraYaw", cameraYaw); current.addProperty("cameraPitch", cameraPitch); current.addProperty("fov", view.fov);
        System.out.println("POSE PREVIEW " + name);
    }
    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (startedNanos == 0) initialize(game);
            if (failure != null) throw new RuntimeException(failure);
            if ((System.nanoTime() - startedNanos) / 1e9 > 30) throw new AssertionError("Static pose preview exceeded 30 seconds after world readiness");
            if (game.isPaused()) game.setScreen(null);
            KeyMapping.releaseAll(); game.getFramerateLimitTracker().onInputReceived();
            actor.setPos(.5, 80, .5); actor.setYRot(0); actor.setXRot(0);
            actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = 0;
            actor.setOldPosAndRot(); actor.setOnGround(true); actor.setDeltaMovement(0, 0, 0);
            game.setCameraEntity(camera); game.options.setCameraType(CameraType.FIRST_PERSON);
            if (!serverReady) return;
            int t = tick++;
            if (t < WARMUP_TICKS) return;
            int index = (t - WARMUP_TICKS) / VIEW_TICKS;
            if (index >= plan.total()) {
                if (pending.get() == 0 && !captureRequested) finish(null);
                return;
            }
            if (index != scene) configure(game, index);
            if ((t - WARMUP_TICKS) % VIEW_TICKS == VIEW_TICKS - 1) captureRequested = true;
        } catch (Throwable error) { finish(error); }
    }
    private void render(RenderFrameEvent.Post event) {
        if (done || !captureRequested) return;
        try {
            captureRequested = false;
            if (rendered == null || renderedFrames < 2 || OfficialHeldItems.submissions() <= mainBefore
                    || OfficialHeldItems.offhandAttachedSubmissions() <= offBefore
                    || !OfficialHeldItems.lastItemId().equals("minecraft:diamond_sword")
                    || !OfficialHeldItems.lastOffhandItemId().equals("minecraft:iron_sword"))
                throw new AssertionError("Pose was not submitted through both official held-item render paths: " + current);
            current.addProperty("nativePlayerSubmissions", renderedFrames);
            current.addProperty("mainItemSubmissions", OfficialHeldItems.submissions() - mainBefore);
            current.addProperty("offhandItemSubmissions", OfficialHeldItems.offhandAttachedSubmissions() - offBefore);
            var rig = new JsonObject();
            for (String side : List.of("right", "left")) for (String joint : List.of("_arm", "_forearm", "_hand")) {
                Matrix4f matrix = rendered.world().get(side + joint);
                rig.add(side + joint, RpgConfig.JSON.toJsonTree(matrix.get(new float[16])));
                if (joint.equals("_hand")) {
                    Vector3f palm = matrix.transformPosition(new Vector3f(0, -6.25f, 0));
                    current.add(side + "PalmCm", RpgConfig.JSON.toJsonTree(new float[]{palm.x, palm.y, palm.z}));
                }
            }
            current.add("actualNativeRig", rig);
            JsonObject record = current; images.add(record); pending.incrementAndGet();
            Screenshot.grab(directory.toFile(), record.get("name").getAsString() + ".png", Minecraft.getInstance().getMainRenderTarget(), 1, message -> {
                try {
                    Path file = directory.resolve(record.get("file").getAsString());
                    if (!Files.isRegularFile(file) || Files.size(file) < 1024) throw new IllegalStateException("Native screenshot failed: " + message.getString());
                    record.addProperty("sha256", sha(file));
                } catch (Throwable error) { failure = error; }
                finally { pending.decrementAndGet(); }
            });
        } catch (Throwable error) { finish(error); }
    }
    private static String sha(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private void finish(Throwable error) {
        if (done) return;
        done = true;
        try {
            var report = new JsonObject(); report.addProperty("capturePassed", error == null && images.size() == plan.total() && pending.get() == 0);
            report.addProperty("scope", "Static native dual stance preview only; no visual approval or gameplay/motion verdict");
            report.addProperty("error", error == null ? null : error.toString()); report.addProperty("expectedImages", plan.total());
            report.addProperty("samplePlanPath", samplePlanPath == null ? null : samplePlanPath.toString());
            report.addProperty("samplePlanSha256", samplePlanSha); report.add("samplePlan", planJson(plan));
            report.addProperty("secondsAfterWorldReady", (System.nanoTime() - startedNanos) / 1e9);
            report.addProperty("modelPath", String.valueOf(modelPath)); report.addProperty("modelSha256", modelSha);
            Path source = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            report.addProperty("jarSha256", Files.isRegularFile(source) ? sha(source) : "development-classes");
            report.addProperty("directory", directory.toString()); RpgSmoke.windowEnvironment(report); report.add("views", images);
            RpgConfig.atomicWrite(directory.resolve("pose-preview.json"), RpgConfig.JSON.toJson(report));
            RpgConfig.atomicWrite(ROOT.resolve("latest.json"), RpgConfig.JSON.toJson(report));
            System.out.println("POSE PREVIEW COMPLETE " + directory + " capturePassed=" + report.get("capturePassed"));
        } catch (Throwable reportError) { reportError.printStackTrace(); }
        if (error != null) error.printStackTrace();
        var game = Minecraft.getInstance(); if (actor != null) actor.discard();
        game.setCameraEntity(game.player); game.stop();
    }
}

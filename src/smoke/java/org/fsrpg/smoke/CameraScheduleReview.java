package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.CameraAdjustment;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.ShoulderInteraction;
import org.fsrpg.client.ShoulderOcclusionPresentation;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgActionPayload;
import org.lwjgl.glfw.GLFW;

/** Copied-world key-mapping, real vehicle and rendered-occlusion acceptance. */
final class CameraScheduleReview {
    private enum Case { VIEW_MODES, GROUND, HORSE, BOAT, DISMOUNT, PERSISTENCE, BLOCK, CLEAR, ENTITY, DRAWN }
    private static final Field MOUSE_GRABBED = field(MouseHandler.class, "mouseGrabbed");
    private static final String PROBE = "fsrpg_camera_schedule_review";
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final boolean reload = Boolean.getBoolean("fsrpg.cameraScheduleReload");
    private final RenderFrameCapture movie = new RenderFrameCapture(root);
    private final JsonArray checks = new JsonArray(), scenes = new JsonArray(), screenshots = new JsonArray();
    private final ConcurrentLinkedQueue<JsonObject> packets = new ConcurrentLinkedQueue<>(), serverEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<KeyMapping> pendingClicks = new ConcurrentLinkedQueue<>();
    private final JsonArray inputTrace = new JsonArray();
    private final AtomicInteger pendingShots = new AtomicInteger();
    private volatile Case scenario = reload ? Case.GROUND : Case.VIEW_MODES;
    private volatile boolean prepared, probeReady, sawDrawnAttack;
    private volatile Throwable failure;
    private volatile JsonObject serverState = new JsonObject();
    private JsonObject baseline, groundSaved, mountedSaved, mountedBeforeBoat, expected;
    private UUID playerId;
    private Entity vehicle;
    private Mob cover;
    private volatile int vehicleId = -1, coverId = -1;
    private BlockPos target = new BlockPos(-1, 81, 3), wall = new BlockPos(0, 81, 1);
    private Vec3 aimPoint;
    private boolean initialized, done, complete, finishing, recording, drawRequested, slotRequested;
    private boolean modifier, shift, up, down, left, right, attack;
    private boolean oldMouse, oldPause, oldVsync, oldActive, oldHide;
    private int oldFps, oldDistance, oldScale, oldFov;
    private CameraType oldCamera;
    private KeyMapping drawKey, modifierKey, upKey, downKey, leftKey, rightKey;
    private Channel channel;
    private int warmup, tick, local, phase, phaseTick, index, stable, nativeFrames, obstructionFrames, steadyRenderFrames;
    private long started, clipStarted, worldDrawsBefore, labelsBefore, lastFrameNanos;
    private double shoulderSide = -1, groundDistance;
    private String capture;

    CameraScheduleReview() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::preTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::broken);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }
    private static Field field(Class<?> type, String name) {
        try { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void check(boolean condition, String message) { require(condition, message); checks.add(message); System.out.println("CAMERA SCHEDULE PASS " + message); }
    private static String text(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : ""; }
    private static double number(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsDouble() : 0; }
    private static boolean bool(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    private static JsonObject vec(Vec3 value) { var o = new JsonObject(); o.addProperty("x", value.x); o.addProperty("y", value.y); o.addProperty("z", value.z); return o; }
    private static JsonObject pos(BlockPos value) { return vec(Vec3.atLowerCornerOf(value)); }
    private static String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static JsonObject profile(String name) { return CameraAdjustment.diagnostics().getAsJsonObject(name).deepCopy(); }
    private static boolean sameProfile(JsonObject a, JsonObject b) {
        return Math.abs(number(a, "side") - number(b, "side")) < 1e-8
                && Math.abs(number(a, "height") - number(b, "height")) < 1e-8
                && Math.abs(number(a, "distance") - number(b, "distance")) < 1e-8;
    }
    private JsonObject row(String kind) {
        var o = new JsonObject(); o.addProperty("case", scenario.name()); o.addProperty("kind", kind);
        o.addProperty("tick", tick); o.addProperty("phase", phase); o.addProperty("phaseTick", phaseTick); o.addProperty("nativeNanos", System.nanoTime()); return o;
    }
    private void server(Consumer<ServerPlayer> task) {
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try { task.accept(Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayer(playerId)); }
            catch (Throwable error) { failure = error; }
        });
    }
    private void monitor() {
        var state = new JsonObject(); RpgSmoke.windowEnvironment(state);
        require("\\\\.\\DISPLAY5".equals(text(state, "monitorAdapter")), "Camera acceptance left DISPLAY5: " + state);
    }
    private KeyMapping mapping(Minecraft game, String name) {
        for (var key : game.options.keyMappings) if (key.getName().equals("key.fsrpg." + name)) return key;
        throw new AssertionError("Missing actual key binding: " + name);
    }
    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots")); monitor();
        String name = System.getProperty("fsrpg.cameraScheduleWorld", "");
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.startsWith("FSRPG Camera Schedule Review ") && world.getFileName().toString().equals(name)
                && Files.isRegularFile(world.resolve(".fsrpg-camera-schedule-test-world")), "Only the named marked copied camera world is modified");
        playerId = game.player.getUUID(); oldFps = game.options.framerateLimit().get(); oldDistance = game.options.renderDistance().get();
        oldScale = game.options.guiScale().get(); oldFov = game.options.fov().get(); oldPause = game.options.pauseOnLostFocus;
        oldVsync = game.options.enableVsync().get(); oldActive = game.isWindowActive(); oldHide = game.options.hideGui; oldCamera = game.options.getCameraType();
        oldMouse = MOUSE_GRABBED.getBoolean(game.mouseHandler);
        game.options.framerateLimit().set(40); game.options.renderDistance().set(3); game.options.guiScale().set(2); game.options.fov().set(65);
        game.options.pauseOnLostFocus = false; game.options.enableVsync().set(false); game.options.hideGui = false;
        game.setWindowActive(true); CombatCamera.reset(); game.options.setCameraType(CameraType.FIRST_PERSON); KeyMapping.releaseAll();
        MOUSE_GRABBED.setBoolean(game.mouseHandler, true); game.getTutorial().setStep(TutorialSteps.NONE);
        drawKey = mapping(game, "draw"); modifierKey = mapping(game, "camera_modifier"); upKey = mapping(game, "camera_up"); downKey = mapping(game, "camera_down");
        leftKey = mapping(game, "camera_left"); rightKey = mapping(game, "camera_right");
        var env = new JsonObject(); RpgSmoke.windowEnvironment(env); var bounds = env.getAsJsonObject("monitorBounds");
        long handle = game.getWindow().handle(); GLFW.glfwSetWindowSize(handle, 1100, 720);
        GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 30, bounds.get("y").getAsInt() + 35);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 载具镜头与遮挡高亮验收 · DISPLAY5"); game.resizeDisplay();
        channel = game.getConnection().getConnection().channel();
        channel.eventLoop().execute(() -> {
            try { channel.pipeline().addLast(PROBE, new ChannelDuplexHandler() {
                @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
                    try { observePacket(packet); } catch (Throwable error) { failure = error; }
                    super.write(context, packet, promise);
                }
            }); probeReady = true; } catch (Throwable error) { failure = error; }
        });
        baseline = CameraAdjustment.diagnostics().deepCopy();
        if (reload) {
            expected = RpgConfig.JSON.fromJson(Files.readString(root.resolve("camera-profiles-expected.json")), JsonObject.class);
            check(expected != null && expected.get("passed").getAsBoolean(), "Fresh process reads the first process's accepted expected profiles from disk");
            check(sameProfile(profile("ground"), expected.getAsJsonObject("ground"))
                    && sameProfile(profile("mounted"), expected.getAsJsonObject("mounted")), "Fresh NeoForge client loads both saved camera profiles before any adjustment");
        }
        initialized = true; started = System.nanoTime(); prepare();
    }
    private void releaseKeys() { modifier = shift = up = down = left = right = attack = false; pendingClicks.clear(); }
    private void keyState(KeyMapping key, boolean value) {
        for (var mapping : Minecraft.getInstance().options.keyMappings) if (mapping.getKey().equals(key.getKey())) mapping.setDown(value);
    }
    private void preTick(ClientTickEvent.Pre event) {
        if (!initialized || done) return;
        try {
            var game = Minecraft.getInstance(); game.setWindowActive(true);
            // Menus must not call releaseMouse against our synthetic input gate: that
            // would move the user's OS pointer even though the test never captured it.
            boolean menuRegression = scenario == Case.PERSISTENCE && phase == 2 && phaseTick < 15;
            MOUSE_GRABBED.setBoolean(game.mouseHandler, !menuRegression);
            keyState(game.options.keyShift, shift); keyState(modifierKey, modifier); keyState(upKey, up); keyState(downKey, down);
            keyState(leftKey, left); keyState(rightKey, right); keyState(game.options.keyAttack, attack);
            // KeyboardHandler publishes held state and clicks before a game tick.
            // Injecting a click in Post lets another Post listener consume it before
            // movement can arbitrate Ctrl+J/K. Deliver every click here instead.
            KeyMapping key;
            while ((key = pendingClicks.poll()) != null) {
                KeyMapping.click(key.getKey());
                var input = row("KEY_CLICK_PRE"); input.addProperty("binding", key.getName());
                input.addProperty("modifierDown", modifierKey.isDown()); input.addProperty("keyDown", key.isDown());
                inputTrace.add(input);
            }
        } catch (Throwable error) { failure = error; }
    }
    private void click(KeyMapping key) { pendingClicks.add(key); }
    private void pressAttack() { attack = true; click(Minecraft.getInstance().options.keyAttack); }
    private void nextPhase() { phase++; phaseTick = 0; stable = 0; }
    private boolean drawnCase() { return scenario != Case.VIEW_MODES && scenario.ordinal() <= Case.PERSISTENCE.ordinal() || scenario == Case.DRAWN; }
    private void prepare() {
        releaseKeys(); prepared = complete = drawRequested = slotRequested = false; local = phase = phaseTick = stable = steadyRenderFrames = 0;
        if (scenario == Case.DISMOUNT || scenario == Case.PERSISTENCE) { prepared = true; return; }
        boolean corner = scenario == Case.BLOCK || scenario == Case.ENTITY || scenario == Case.DRAWN;
        target = new BlockPos(shoulderSide > 0 ? 1 : -1, 81, 3);
        aimPoint = new Vec3(shoulderSide > 0 ? 1.15 : -.15, 81.5, 3.001);
        server(p -> {
            if (scenario == Case.CLEAR) { p.level().setBlock(wall, Blocks.AIR.defaultBlockState(), 3); prepared = true; return; }
            p.stopRiding(); if (vehicle != null) vehicle.discard(); vehicle = null; vehicleId = -1;
            if (cover != null) cover.discard(); cover = null; coverId = -1;
            // The exact marked copied test world owns this bounded fixture area.
            // Remove only its residual non-player entities from an earlier test run.
            for (var leftover : p.level().getEntities(p, new AABB(-10, 78, -10, 11, 89, 13), entity -> !(entity instanceof net.minecraft.world.entity.player.Player))) leftover.discard();
            p.setGameMode(GameType.SURVIVAL); p.setInvulnerable(true); p.getFoodData().setFoodLevel(20);
            p.getInventory().clearContent(); p.getInventory().setSelectedSlot(0);
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(drawnCase() ? Items.DIAMOND_SWORD : Items.APPLE));
            if (scenario == Case.VIEW_MODES) {
                p.getInventory().setItem(1, new ItemStack(Items.DIAMOND_SWORD));
                p.getInventory().setItem(2, new ItemStack(Items.BOW));
                p.getInventory().setItem(3, new ItemStack(Items.DIAMOND_SPEAR));
                RpgServer.instance().progression().learnFree(p.getUUID(), "spear");
            }
            p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY); RpgSmoke.actor(p).forceSheathed();
            RpgServer.instance().progression().learnFree(p.getUUID(), "rapier");
            RpgSmoke.actor(p).setStyle(RpgServer.instance().config().current().styles().get("rapier"));
            RpgSmoke.actor(p).setResources(RpgSmoke.actor(p).maxStamina(), 100);
            RpgServer.instance().action(p, "status", "");
            p.level().setDayTime(6000); p.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 11; z++) {
                p.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 80; y <= 86; y++) p.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            if (corner) p.level().setBlock(target, Blocks.DIRT.defaultBlockState(), 2);
            p.connection.teleport(corner ? (shoulderSide > 0 ? .85 : .15) : .5, 80, .5, 0, 0); p.setDeltaMovement(Vec3.ZERO);
            if (scenario == Case.HORSE) mountHorse(p);
            if (scenario == Case.BOAT) mountBoat(p);
            prepared = true;
        });
    }
    private void mountHorse(ServerPlayer p) {
        var horse = EntityType.HORSE.create(p.level(), EntitySpawnReason.COMMAND); require(horse != null, "Cannot create native horse");
        horse.setPos(.5, 80, .5); horse.setTamed(true); horse.setOwner(p); horse.setNoAi(true); horse.setPersistenceRequired();
        horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE)); p.level().addFreshEntity(horse);
        checkMount(p, horse); vehicle = horse; vehicleId = horse.getId();
    }
    private void mountBoat(ServerPlayer p) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) p.level().setBlock(new BlockPos(x, 80, z), Blocks.WATER.defaultBlockState(), 3);
        var boat = EntityType.OAK_BOAT.create(p.level(), EntitySpawnReason.COMMAND); require(boat != null, "Cannot create native oak boat");
        boat.setPos(.5, 80.7, .5); p.level().addFreshEntity(boat); checkMount(p, boat); vehicle = boat; vehicleId = boat.getId();
    }
    private static void checkMount(ServerPlayer player, Entity mount) { require(player.startRiding(mount, true, false), "Real server passenger fixture failed"); }
    private boolean ready(Minecraft game) {
        if (!prepared || !probeReady || local < 18 || !bool(serverState, "ready")) return false;
        if (!slotRequested) { click(game.options.keyHotbarSlots[0]); slotRequested = true; return false; }
        if (game.player.getInventory().getSelectedSlot() != 0 || number(serverState, "selectedSlot") != 0) return false;
        if (scenario == Case.VIEW_MODES) return game.player.getMainHandItem().is(Items.APPLE) && !ClientState.drawn();
        if (drawnCase()) {
            if (!game.player.getMainHandItem().is(Items.DIAMOND_SWORD)) return false;
            if (!ClientState.drawn() && !drawRequested && ClientState.bool(ClientState.snapshot(), "weaponEquipped", false)) { click(drawKey); drawRequested = true; return false; }
            if (!ClientState.drawn() || !text(serverState, "actorPhase").equals("READY")) return false;
        } else {
            if (!game.player.getMainHandItem().is(Items.APPLE) || ClientState.drawn()) return false;
            if (!CombatCamera.cameraOnly() && !drawRequested) { click(drawKey); drawRequested = true; return false; }
            if (!CombatCamera.cameraOnly()) return false;
        }
        return CombatCamera.active() && CombatCamera.transitionBlend() > .99;
    }
    private boolean aim() {
        var game = Minecraft.getInstance(); Vec3 delta = aimPoint.subtract(game.gameRenderer.getMainCamera().position());
        double yaw = -Math.toDegrees(Math.atan2(delta.x, delta.z)), pitch = -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        double dy = wrap(yaw - CombatCamera.yaw()), dp = pitch - CombatCamera.pitch();
        double sensitivity = game.options.sensitivity().get() * .6 + .2, gain = sensitivity * sensitivity * sensitivity * 8 * .15;
        require(CombatCamera.mouse(dy / (gain * (game.options.invertMouseX().get() ? -1 : 1)), dp / (gain * (game.options.invertMouseY().get() ? -1 : 1))), "Fixture aims through the actual shoulder mouse entry");
        return Math.hypot(dy, dp) < .15;
    }
    private static double wrap(double a) { return a - Math.floor((a + 180) / 360) * 360; }
    private boolean rawTarget() {
        var game = Minecraft.getInstance();
        if (ClientState.drawn()) return true; // Drawn display performs its own camera ray, without owning hitResult.
        return game.hitResult instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(target);
    }
    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance(); if (done || game.player == null || game.level == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) { if (++warmup >= 60) initialize(game); return; }
            if (failure != null) throw new IllegalStateException("Native camera fixture failed", failure);
            require(System.nanoTime() - started < 240_000_000_000L, "Camera review exceeded four minutes");
            movie.assertHealthy(); game.getFramerateLimitTracker().onInputReceived(); tick++;
            if (reload) { reloadTick(game); return; }
            if (finishing) { if (capture == null && pendingShots.get() == 0 && movie.finish()) { validate(); finish(null); } return; }
            if (complete) {
                if (capture == null && pendingShots.get() == 0) {
                    if (++index == Case.values().length) finishing = true;
                    else { scenario = Case.values()[index]; prepare(); }
                }
                return;
            }
            if (!prepared) return; local++; phaseTick++;
            require(local < 420, "Camera case timed out " + scenario + " phase " + phase + " " + observation());
            if (phase == 0) {
                if (!ready(game)) return;
                if ((scenario == Case.GROUND || scenario == Case.HORSE) && (local < 80 || steadyRenderFrames < 40)) return;
                if (scenario == Case.HORSE || scenario == Case.BOAT || scenario == Case.DISMOUNT) {
                    if (!game.player.isPassenger() || game.player.getVehicle().getId() != vehicleId || number(serverState, "vehicleId") != vehicleId || local < 40) return;
                }
                if (scenario == Case.BLOCK || scenario == Case.CLEAR || scenario == Case.ENTITY || scenario == Case.DRAWN) {
                    stable = aim() && rawTarget() ? stable + 1 : 0; if (stable < 5) return;
                }
                beginCase(game); nextPhase(); return;
            }
            runCase(game);
        } catch (Throwable error) { finish(error); }
    }
    private void beginCase(Minecraft game) throws Exception {
        var camera = CameraAdjustment.diagnostics();
        switch (scenario) {
            case VIEW_MODES -> {
                check(game.options.getCameraType() == CameraType.FIRST_PERSON && !CombatCamera.active(), "Perspective flow starts in actual first person with an ordinary held ItemStack");
                click(game.options.keyTogglePerspective);
            }
            case GROUND -> {
                check(ClientState.drawn() && text(camera, "profile").equals("ground"), "Actual R enters drawn combat with the ground camera profile");
                groundSaved = profile("ground"); groundDistance = game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition());
                shoulderSide = Math.signum(game.gameRenderer.getMainCamera().position().x - game.player.getEyePosition().x); if (shoulderSide == 0) shoulderSide = -1;
                startClip("ground-camera-tuning"); modifier = up = true; click(upKey);
            }
            case HORSE -> {
                check(text(serverState, "vehicleType").equals("minecraft:horse") && text(camera, "profile").equals("mounted"), "A real server horse and its client passenger select the mounted profile");
                check(number(camera, "distance") > number(groundSaved, "distance") + .5
                        && game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition()) > groundDistance + .4, "Drawn mounted camera visibly settles farther away than the ground camera");
                check(sameProfile(profile("ground"), groundSaved), "Mounting preserves the saved ground camera coordinates");
                mountedSaved = profile("mounted"); startClip("horse-camera-tuning"); modifier = true;
            }
            case BOAT -> {
                check(text(serverState, "vehicleType").equals("minecraft:oak_boat") && text(camera, "profile").equals("mounted"), "A real floating oak boat selects the shared mounted profile");
                check(sameProfile(profile("mounted"), mountedSaved), "The boat reuses the exact camera position adjusted on the horse");
                mountedBeforeBoat = profile("mounted"); modifier = down = right = true; click(downKey); click(rightKey);
            }
            case DISMOUNT -> { check(game.player.isPassenger(), "Standalone crouch begins while genuinely riding the boat"); shift = true; }
            case PERSISTENCE -> { }
            case BLOCK, DRAWN -> {
                worldDrawsBefore = (long) number(ShoulderOcclusionPresentation.diagnostics(), "worldDraws"); labelsBefore = (long) number(ShoulderOcclusionPresentation.diagnostics(), "labelDraws");
                server(p -> p.level().setBlock(wall, Blocks.STONE.defaultBlockState(), 3));
                if (scenario == Case.BLOCK) startClip("blocked-object-highlight");
            }
            case CLEAR -> {
                check(!bool(ShoulderOcclusionPresentation.diagnostics(), "active"), "Removing the obstruction immediately clears its active highlight state");
                worldDrawsBefore = (long) number(ShoulderOcclusionPresentation.diagnostics(), "worldDraws"); labelsBefore = (long) number(ShoulderOcclusionPresentation.diagnostics(), "labelDraws");
            }
            case ENTITY -> {
                Vec3 eye = game.player.getEyePosition(), intended = game.hitResult.getLocation().subtract(eye).normalize(), nativeDirection = game.player.getViewVector(1).normalize();
                Vec3 center = eye.add(intended.add(nativeDirection).scale(.35));
                worldDrawsBefore = (long) number(ShoulderOcclusionPresentation.diagnostics(), "worldDraws"); labelsBefore = (long) number(ShoulderOcclusionPresentation.diagnostics(), "labelDraws");
                server(p -> {
                    cover = EntityType.CHICKEN.create(p.level(), EntitySpawnReason.COMMAND); require(cover != null, "Cannot create native entity obstruction");
                    cover.setPos(center.add(0, -cover.getBbHeight() / 2, 0)); cover.setNoAi(true); cover.setNoGravity(true); cover.setPersistenceRequired();
                    cover.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); cover.setHealth(100); p.level().addFreshEntity(cover); coverId = cover.getId();
                });
            }
        }
    }
    private void runCase(Minecraft game) throws Exception {
        var camera = CameraAdjustment.diagnostics();
        switch (scenario) {
            case VIEW_MODES -> viewModes(game);
            case GROUND -> {
                require(game.screen == null, "Control camera keys opened a conflicting GUI: " + (game.screen == null ? "NONE" : game.screen.getClass().getName()));
                if (modifier && (up || down || left || right)) require(!game.player.input.keyPresses.sprint() && !game.player.isSprinting(), "Control adjustment leaked into native sprint input");
                if (phaseTick == 6) { up = false; check(number(profile("ground"), "height") > number(groundSaved, "height") + .05, "Control I raises the ground camera through actual key mappings"); }
                if (phaseTick == 8) { down = true; click(downKey); }
                if (phaseTick == 11) { down = false; check(number(profile("ground"), "height") < number(groundSaved, "height") + .14, "Control K lowers the camera without opening its normal editor"); }
                if (phaseTick == 14) { left = true; click(leftKey); }
                if (phaseTick == 20) { left = false; check(number(profile("ground"), "side") < number(groundSaved, "side") - .05, "Control J shifts the camera left without opening its normal journal"); }
                if (phaseTick == 22) { right = true; click(rightKey); }
                if (phaseTick == 25) { right = false; }
                if (phaseTick == 28) modifier = false;
                if (phaseTick >= 42) {
                    check(number(profile("ground"), "side") > number(groundSaved, "side") - .14 && !sameProfile(profile("ground"), groundSaved), "Control L shifts back right and leaves a distinct saved ground position");
                    groundSaved = profile("ground"); check(number(camera, "saveCount") > 0, "Releasing camera keys persists changes rather than writing on every render frame");
                    shot("four-directions-saved"); completeCase();
                }
            }
            case HORSE -> {
                if (phaseTick == 2) { up = true; click(upKey); }
                if (phaseTick == 9) up = false;
                if (phaseTick == 12) { left = true; click(leftKey); }
                if (phaseTick == 19) left = false;
                if (phaseTick >= 3 && phaseTick <= 24) require(game.player.isPassenger() && number(serverState, "vehicleId") == vehicleId, "Control+camera adjustment unexpectedly dismounted the horse");
                if (phaseTick == 24) modifier = false;
                if (phaseTick >= 42) {
                    check(game.player.isPassenger() && number(serverState, "vehicleId") == vehicleId && Math.abs(number(serverState, "vehicleY") - 80) < .15, "Mounted adjustment keeps the real horse passenger attached and does not jump");
                    check(number(profile("mounted"), "height") > number(mountedSaved, "height") + .08
                            && number(profile("mounted"), "side") < number(mountedSaved, "side") - .08, "Horse I/J adjustment modifies only the mounted profile");
                    check(sameProfile(profile("ground"), groundSaved), "Horse adjustments leave ground coordinates unchanged");
                    mountedSaved = profile("mounted"); shot("mounted-position-saved"); completeCase();
                }
            }
            case BOAT -> {
                if (phaseTick == 5) { down = right = false; }
                if (phaseTick == 9) modifier = false;
                if (phaseTick >= 25) {
                    check(game.player.isPassenger() && number(serverState, "vehicleId") == vehicleId, "Control K/L adjustment keeps the real boat passenger attached");
                    check(number(profile("mounted"), "height") < number(mountedBeforeBoat, "height") - .04
                            && number(profile("mounted"), "side") > number(mountedBeforeBoat, "side") + .04, "Boat adjustment changes the same persistent mounted camera in the opposite two directions");
                    mountedSaved = profile("mounted"); check(sameProfile(profile("ground"), groundSaved), "Boat adjustment leaves the ordinary profile intact");
                    shot("boat-shares-mounted-profile"); completeCase();
                }
            }
            case DISMOUNT -> {
                if (phaseTick == 4) { shift = false; check(!game.player.isPassenger() && number(serverState, "vehicleId") == -1, "Standalone Shift performs vanilla dismount immediately without an adjustment-chord delay"); }
                if (phaseTick >= 40) {
                    check(text(camera, "profile").equals("ground") && sameProfile(profile("ground"), groundSaved)
                            && sameProfile(profile("mounted"), mountedSaved), "Dismount smoothly returns to the independently saved ground position");
                    shot("ground-position-restored"); completeCase();
                }
            }
            case PERSISTENCE -> {
                if (phase == 1) {
                    require(phaseTick < 160, "Real asynchronous camera save failed to finish before independent disk verification: " + camera);
                    if (phaseTick >= 12 && !bool(camera, "pendingSave")) { saveExpected(); click(leftKey); nextPhase(); }
                    return;
                }
                if (phaseTick == 2) {
                    check(game.screen instanceof org.fsrpg.client.QuestJournalScreen, "Actual J without Control still opens the original quest journal");
                    inputTrace.add(observation()); closeMenu(game);
                }
                if (phaseTick == 5) click(downKey);
                if (phaseTick == 7) {
                    check(game.screen instanceof org.fsrpg.client.SkillEditorScreen, "Actual K without Control still opens the original skill editor");
                    inputTrace.add(observation()); closeMenu(game);
                }
                if (phaseTick == 10) click(rightKey);
                if (phaseTick == 12) {
                    check(game.screen instanceof net.minecraft.client.gui.screens.advancements.AdvancementsScreen, "Actual L without Control still opens the vanilla advancements screen");
                    inputTrace.add(observation()); closeMenu(game);
                }
                if (phaseTick >= 15) {
                    check(game.screen == null && sameProfile(profile("ground"), groundSaved) && sameProfile(profile("mounted"), mountedSaved), "Unmodified J/K/L menu shortcuts leave both saved camera profiles unchanged");
                    completeCase();
                }
            }
            case BLOCK, DRAWN -> {
                if (phaseTick == 12) { verifyHighlight("BLOCK"); shot("blocker-outline-and-label"); }
                if (phaseTick == 16) pressAttack();
                if (phaseTick == 19) attack = false;
                if (phaseTick >= 42) {
                    check(!bool(serverState, "targetAir") && !bool(serverState, "wallAir"), "Target and actual blocking wall remain intact");
                    if (scenario == Case.BLOCK) {
                        check(ShoulderInteraction.blocked() && nativeActions() == 0 && breaks() == 0, "Highlighted ordinary obstruction still prevents all native attack/mining requests");
                    } else {
                        check(sawDrawnAttack && packets.stream().anyMatch(v -> text(v, "case").equals("DRAWN") && text(v, "kind").equals("CUSTOM") && List.of("attack", "aimAttack", "targetAttack").contains(text(v, "action"))), "Drawn obstruction presentation preserves the existing authored combat attack path");
                        check(nativeActions() == 0, "Drawn visualization never redirects into native block destruction");
                    }
                    completeCase();
                }
            }
            case CLEAR -> {
                if (phaseTick == 10) {
                    var display = ShoulderOcclusionPresentation.diagnostics();
                    check(!bool(display, "active") && number(display, "worldDraws") == worldDrawsBefore && number(display, "labelDraws") == labelsBefore, "Clear line of sight emits no stale highlighted geometry or blocked label");
                    shot("highlight-cleared"); pressAttack();
                }
                if (bool(serverState, "targetAir")) attack = false;
                if (phaseTick >= 55) {
                    check(bool(serverState, "targetAir") && actions("START_DESTROY_BLOCK") == 1 && actions("STOP_DESTROY_BLOCK") == 1 && breaks() == 1, "After obstruction disappears, the actual attack key mines only the reticle block through vanilla START/STOP and server break");
                    completeCase();
                }
            }
            case ENTITY -> {
                if (phaseTick == 14) { verifyHighlight("ENTITY"); shot("actual-near-entity-outline-and-label"); pressAttack(); }
                if (phaseTick == 17) attack = false;
                if (phaseTick >= 32) {
                    check(nativeActions() == 0 && breaks() == 0 && !bool(serverState, "targetAir") && number(serverState, "coverHealth") == 100, "Highlighted entity obstruction is neither attacked nor bypassed to mine the rear block");
                    completeCase();
                }
            }
        }
    }
    private void viewModes(Minecraft game) {
        require(game.screen == null, "Perspective keys unexpectedly opened a screen");
        switch (phase) {
            case 1 -> {
                if (phaseTick < 24) return;
                check(customShoulder(game) && CombatCamera.cameraOnly(), "Actual F5 enters back third person using the configured custom shoulder composition");
                shot("f5-custom-shoulder"); click(game.options.keyTogglePerspective); nextPhase();
            }
            case 2 -> {
                if (phaseTick < 12) return;
                check(game.options.getCameraType() == CameraType.THIRD_PERSON_FRONT && !CombatCamera.active(), "The next actual F5 selects vanilla front third person without custom shoulder control");
                click(game.options.keyTogglePerspective); nextPhase();
            }
            case 3 -> {
                if (phaseTick < 12) return;
                check(game.options.getCameraType() == CameraType.FIRST_PERSON && !CombatCamera.active()
                        && game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition()) < .04, "The next actual F5 returns fully to the player's first-person eyes");
                click(game.options.keyHotbarSlots[1]); nextPhase();
            }
            case 4 -> {
                if (phaseTick < 12 || !heldItem(game, Items.DIAMOND_SWORD, 1) || !ClientState.bool(ClientState.snapshot(), "weaponEquipped", false)) return;
                check(game.options.getCameraType() == CameraType.FIRST_PERSON, "Selecting a weapon alone preserves the user's first-person selection");
                click(drawKey); nextPhase();
            }
            case 5 -> {
                if (phaseTick < 24 || !ClientState.drawn() || !text(serverState, "actorPhase").equals("READY")) return;
                check(customShoulder(game) && !CombatCamera.cameraOnly(), "Actual R draws the equipped sword and enters the custom back third-person view");
                shot("r-draw-enters-third-person"); click(game.options.keyTogglePerspective); nextPhase();
            }
            case 6 -> {
                if (phaseTick < 12) return;
                check(ClientState.drawn() && game.options.getCameraType() == CameraType.THIRD_PERSON_FRONT && !CombatCamera.active(), "F5 remains authoritative while drawn and can select front third person");
                click(game.options.keyTogglePerspective); nextPhase();
            }
            case 7 -> {
                if (phaseTick < 24) return;
                check(ClientState.drawn() && game.options.getCameraType() == CameraType.FIRST_PERSON && !CombatCamera.active(), "Drawn state does not force the user's F5 first-person choice back to third person");
                click(game.options.keyTogglePerspective); nextPhase();
            }
            case 8 -> {
                if (phaseTick < 24) return;
                check(customShoulder(game), "Actual F5 can re-enter the custom back view while the weapon remains drawn");
                click(drawKey); nextPhase();
            }
            case 9 -> {
                if (phaseTick < 16 || ClientState.drawn()) return;
                check(customShoulder(game) && CombatCamera.cameraOnly(), "Manually sheathing with actual R preserves the selected custom back third-person view");
                click(drawKey); nextPhase();
            }
            case 10 -> {
                if (phaseTick < 24 || !ClientState.drawn() || !text(serverState, "actorPhase").equals("READY")) return;
                click(game.options.keyHotbarSlots[0]); nextPhase();
            }
            case 11 -> {
                if (phaseTick < 16 || !heldItem(game, Items.APPLE, 0) || ClientState.drawn()) return;
                check(customShoulder(game) && CombatCamera.cameraOnly(), "Switching the actual hotbar from the drawn sword to an ordinary apple preserves custom third person");
                shot("ordinary-item-keeps-third-person"); click(game.options.keyHotbarSlots[2]); nextPhase();
            }
            case 12 -> {
                if (phaseTick < 16 || !heldItem(game, Items.BOW, 2)) return;
                check(customShoulder(game), "Switching the actual hotbar to a different weapon ItemStack preserves custom third person");
                shot("different-weapon-keeps-third-person"); click(game.options.keyHotbarSlots[3]); nextPhase();
            }
            case 13 -> {
                if (phaseTick < 16 || !heldItem(game, Items.DIAMOND_SPEAR, 3) || !ClientState.drawn() || !text(serverState, "actorPhase").equals("READY")) return;
                click(drawKey); nextPhase();
            }
            case 14 -> {
                if (phaseTick < 16) return;
                check(customShoulder(game) && CombatCamera.style().equals("spear"), "Actual spear equipment and R retain its combat controls in custom back third person");
                click(game.options.keyTogglePerspective); nextPhase();
            }
            case 15 -> {
                if (phaseTick < 12) return;
                check(game.options.getCameraType() == CameraType.THIRD_PERSON_FRONT && !CombatCamera.active(), "F5 can leave the spear shoulder view without replacing the native front perspective");
                click(game.options.keyTogglePerspective); nextPhase();
            }
            case 16 -> {
                if (phaseTick < 16) return;
                check(game.options.getCameraType() == CameraType.FIRST_PERSON && !CombatCamera.active() && ClientState.drawn(), "The spear remains drawn in the player's actual F5 first-person view");
                pressAttack(); nextPhase();
            }
            case 17 -> {
                require(game.options.getCameraType() == CameraType.FIRST_PERSON && !CombatCamera.active(), "A first-person spear attack changed the user's perspective");
                if (phaseTick < 6) return;
                check(bool(serverState, "spearAttackHeld") && !text(serverState, "currentSkill").isEmpty() && CombatCamera.gestureHeld()
                        && packets.stream().anyMatch(v -> text(v, "case").equals("VIEW_MODES") && text(v, "action").equals("spearPress")), "The actual first-person attack key starts the authoritative held spear thrust through its existing spearPress path");
                shot("first-person-spear-attack"); attack = false; nextPhase();
            }
            case 18 -> {
                if (phaseTick < 10) return;
                check(!bool(serverState, "spearAttackHeld") && text(serverState, "currentSkill").isEmpty() && !CombatCamera.gestureHeld()
                        && game.options.getCameraType() == CameraType.FIRST_PERSON && !CombatCamera.active()
                        && packets.stream().anyMatch(v -> text(v, "case").equals("VIEW_MODES") && text(v, "action").equals("spearRelease")) && nativeActions() == 0,
                        "Releasing the real first-person attack key ends the spear gesture without native block attacks or a forced camera switch");
                completeCase();
            }
            default -> throw new AssertionError("Unexpected perspective phase " + phase);
        }
    }
    private boolean heldItem(Minecraft game, net.minecraft.world.item.Item item, int slot) {
        return game.player.getInventory().getSelectedSlot() == slot && game.player.getMainHandItem().is(item)
                && number(serverState, "selectedSlot") == slot && text(serverState, "mainItem").equals(BuiltInRegistries.ITEM.getKey(item).toString());
    }
    private boolean customShoulder(Minecraft game) {
        if (game.options.getCameraType() != CameraType.THIRD_PERSON_BACK || !CombatCamera.active() || CombatCamera.transitionBlend() < .99) return false;
        var mainCamera = game.gameRenderer.getMainCamera(); var settings = CameraAdjustment.position();
        var offset = new org.joml.Vector3f((float) settings.side(), (float) settings.height(), (float) settings.distance()).rotate(mainCamera.rotation());
        Vec3 expectedPosition = game.player.getEyePosition().add(offset.x, offset.y, offset.z);
        return mainCamera.position().distanceTo(expectedPosition) < .08;
    }
    private void closeMenu(Minecraft game) throws Exception {
        // Keep native Screen.onClose; the already-grabbed Java gate makes
        // MouseHandler.grabMouse a no-op, so the OS pointer is never captured.
        MOUSE_GRABBED.setBoolean(game.mouseHandler, true);
        game.screen.onClose();
    }
    private void verifyHighlight(String type) {
        var d = ShoulderOcclusionPresentation.diagnostics(); var obstruction = d.getAsJsonObject("obstruction");
        check(bool(d, "active") && "被遮挡".equals(text(d, "label")) && type.equals(text(obstruction, "type")), "Production highlights the real " + type + " obstruction and names it exactly 被遮挡");
        if (type.equals("BLOCK")) check(pos(wall).equals(obstruction.get("blockPos")), "Block highlight identifies the actual near wall, not the selected target behind it");
        else check(number(obstruction, "entityId") == coverId, "Entity highlight identifies the actual nearby blocking entity");
        check(number(d, "worldDraws") >= worldDrawsBefore + 3 && number(d, "labelDraws") >= labelsBefore + 3, "Real world highlight and actual HUD label each draw on multiple native rendered frames before clicking");
        check(text(d, "mode").equals(scenario == Case.DRAWN ? "drawn" : "ordinary"), "Occlusion presentation uses the correct ordinary/drawn mode");
    }
    private void saveExpected() throws Exception {
        check(sameProfile(profile("ground"), groundSaved) && sameProfile(profile("mounted"), mountedSaved), "Both live profiles retain independent adjusted positions");
        var config = Minecraft.getInstance().gameDirectory.toPath().resolve("config/fsrpg-client.toml"); String source = Files.readString(config);
        check(close(readToml(source, "rapierShoulderOffset"), number(groundSaved, "side")) && close(readToml(source, "shoulderCameraHeight"), number(groundSaved, "height"))
                && close(readToml(source, "mountedShoulderOffset"), number(mountedSaved, "side")) && close(readToml(source, "mountedCameraHeight"), number(mountedSaved, "height"))
                && close(readToml(source, "mountedCameraDistance"), number(mountedSaved, "distance")), "Independent fresh disk read contains both persisted positions and the mounted distance");
        Files.writeString(root.resolve("profiles-after-adjustment.toml"), source);
        var e = new JsonObject(); e.addProperty("passed", true); e.add("ground", groundSaved); e.add("mounted", mountedSaved);
        e.addProperty("configFileSha256", sha(config)); e.addProperty("modJarSha256", testedSha());
        e.addProperty("verification", "Next process must load these values before any adjustment; this file is expected evidence, not a source of config values");
        RpgConfig.atomicWrite(root.resolve("camera-profiles-expected.json"), RpgConfig.JSON.toJson(e));
    }
    private static double readToml(String source, String key) {
        var match = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*=\\s*([^#\\r\\n]+)").matcher(source);
        require(match.find(), "Persisted TOML key missing: " + key); return Double.parseDouble(match.group(1).trim());
    }
    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-8; }
    private void completeCase() {
        releaseKeys(); var result = observation(); result.addProperty("completed", scenario.name()); scenes.add(result); stopClip(); complete = true;
    }
    private void reloadTick(Minecraft game) throws Exception {
        if (!prepared) return; local++; phaseTick++;
        require(local < 240, "Fresh-process camera restoration timed out: " + observation());
        if (phase == 0) {
            if (!ready(game) || local < 36) return;
            check(text(CameraAdjustment.diagnostics(), "profile").equals("ground") && sameProfile(profile("ground"), expected.getAsJsonObject("ground")), "Fresh process actually renders the remembered ordinary position");
            scenes.add(observation()); prepared = false;
            server(p -> { mountHorse(p); prepared = true; }); nextPhase();
        } else if (phaseTick >= 40 && game.player.isPassenger() && number(serverState, "vehicleId") == vehicleId) {
            check(text(CameraAdjustment.diagnostics(), "profile").equals("mounted") && sameProfile(profile("mounted"), expected.getAsJsonObject("mounted")), "Fresh process selects the exact remembered mounted position on a real horse");
            check(sameProfile(profile("ground"), expected.getAsJsonObject("ground")) && number(CameraAdjustment.diagnostics(), "adjustedTicks") == 0, "Reload verification performs no camera adjustment or overwrite of either position");
            check(testedSha().equals(text(expected, "modJarSha256")), "Persistence is verified in a second process using the identical tested JAR");
            scenes.add(observation()); finish(null);
        }
    }
    private long actions(String name) { return packets.stream().filter(v -> text(v, "case").equals(scenario.name()) && text(v, "kind").equals(name)).count(); }
    private long nativeActions() { return packets.stream().filter(v -> text(v, "case").equals(scenario.name()) && List.of("START_DESTROY_BLOCK", "STOP_DESTROY_BLOCK", "ABORT_DESTROY_BLOCK", "ATTACK").contains(text(v, "kind"))).count(); }
    private long breaks() { return serverEvents.stream().filter(v -> text(v, "case").equals(scenario.name()) && !bool(v, "canceled")).count(); }
    private void observePacket(Object packet) {
        if (!initialized || done) return;
        if (packet instanceof ServerboundPlayerActionPacket action) {
            var o = row(action.getAction().name()); o.add("pos", pos(action.getPos())); packets.add(o);
        } else if (packet instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket interact) {
            interact.dispatch(new net.minecraft.network.protocol.game.ServerboundInteractPacket.Handler() {
                public void onInteraction(net.minecraft.world.InteractionHand hand) {}
                public void onInteraction(net.minecraft.world.InteractionHand hand, Vec3 where) {}
                public void onAttack() { packets.add(row("ATTACK")); }
            });
        } else if (packet instanceof ServerboundCustomPayloadPacket custom && custom.payload() instanceof RpgActionPayload action) {
            var o = row("CUSTOM"); o.addProperty("action", action.action()); o.addProperty("argument", action.argument()); packets.add(o);
        }
    }
    private void broken(BlockEvent.BreakEvent event) {
        if (!initialized || done || !(event.getPlayer() instanceof ServerPlayer p) || !p.getUUID().equals(playerId)) return;
        var o = row("BREAK"); o.add("pos", pos(event.getPos())); o.addProperty("canceled", event.isCanceled()); serverEvents.add(o);
    }
    private void serverTick(ServerTickEvent.Post event) {
        if (!initialized || done || !prepared) return;
        try {
            var p = event.getServer().getPlayerList().getPlayer(playerId); if (p == null) return;
            var o = row("SERVER_STATE"); o.addProperty("ready", true); o.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
            o.addProperty("mainItem", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
            o.addProperty("vehicleId", p.getVehicle() == null ? -1 : p.getVehicle().getId());
            o.addProperty("vehicleType", p.getVehicle() == null ? "NONE" : BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString());
            o.addProperty("vehicleY", p.getVehicle() == null ? 0 : p.getVehicle().getY());
            o.addProperty("targetAir", p.level().getBlockState(target).isAir()); o.addProperty("wallAir", p.level().getBlockState(wall).isAir());
            o.addProperty("coverHealth", cover == null ? -1 : cover.getHealth());
            String actorPhase = RpgSmoke.actor(p).phase().name(); o.addProperty("actorPhase", actorPhase);
            o.addProperty("spearAttackHeld", RpgSmoke.actor(p).spearAttackHeld());
            o.addProperty("currentSkill", RpgSmoke.actor(p).currentSkill() == null ? "" : RpgSmoke.actor(p).currentSkill().id());
            if (scenario == Case.DRAWN && List.of("WINDUP", "ACTIVE", "RECOVERY").contains(actorPhase)) sawDrawnAttack = true;
            serverState = o;
        } catch (Throwable error) { failure = error; }
    }
    private JsonObject observation() {
        var game = Minecraft.getInstance(); var o = row("FRAME");
        o.add("cameraSettings", CameraAdjustment.diagnostics().deepCopy()); o.add("occlusion", ShoulderOcclusionPresentation.diagnostics().deepCopy());
        o.add("interaction", ShoulderInteraction.diagnostics().deepCopy()); o.add("server", serverState.deepCopy());
        o.add("camera", vec(game.gameRenderer.getMainCamera().position())); o.add("eye", vec(game.player.getEyePosition()));
        o.addProperty("cameraDistance", game.gameRenderer.getMainCamera().position().distanceTo(game.player.getEyePosition()));
        o.addProperty("cameraBlend", CombatCamera.transitionBlend()); o.addProperty("drawn", ClientState.drawn());
        o.addProperty("cameraType", game.options.getCameraType().name()); o.addProperty("cameraActive", CombatCamera.active()); o.addProperty("cameraOnly", CombatCamera.cameraOnly());
        o.addProperty("gestureHeld", CombatCamera.gestureHeld());
        o.addProperty("mainItem", BuiltInRegistries.ITEM.getKey(game.player.getMainHandItem().getItem()).toString()); o.addProperty("selectedSlot", game.player.getInventory().getSelectedSlot());
        o.addProperty("clientVehicleId", game.player.getVehicle() == null ? -1 : game.player.getVehicle().getId());
        o.addProperty("screen", game.screen == null ? "NONE" : game.screen.getClass().getName());
        o.addProperty("modifier", modifier); o.addProperty("shift", shift); o.addProperty("nativeSprintInput", game.player.input.keyPresses.sprint()); o.addProperty("playerSprinting", game.player.isSprinting()); o.addProperty("up", up); o.addProperty("down", down); o.addProperty("left", left); o.addProperty("right", right);
        return o;
    }
    private void startClip(String name) throws Exception { movie.start(name, "Actual Control key mappings, real horse/boat and actual blocker geometry/label renders", this::observation); recording = true; clipStarted = System.nanoTime(); }
    private void stopClip() { if (recording) { movie.stop(); recording = false; } }
    private void shot(String name) { require(capture == null, "Overlapping native screenshots"); capture = scenario.name().toLowerCase() + "-" + name; }
    private void render(RenderFrameEvent.Post event) {
        if (!initialized || done || Minecraft.getInstance().player == null) return; nativeFrames++;
        try {
            long now = System.nanoTime(); steadyRenderFrames = lastFrameNanos > 0 && now - lastFrameNanos <= 100_000_000L ? steadyRenderFrames + 1 : 0; lastFrameNanos = now;
            var display = ShoulderOcclusionPresentation.diagnostics();
            if (bool(display, "active") && number(display, "lastDrawFrame") == number(display, "frameSequence") && number(display, "lastLabelFrame") == number(display, "frameSequence")) obstructionFrames++;
            if (recording) { monitor(); movie.frame(event); require(System.nanoTime() - clipStarted < 5_000_000_000L, "Native clip exceeded five seconds"); }
            if (capture == null) return;
            monitor(); String name = capture; capture = null; pendingShots.incrementAndGet();
            var game = Minecraft.getInstance(); Screenshot.grab(root.toFile(), name + ".png", game.getMainRenderTarget(), 1, message -> pendingShots.decrementAndGet());
            var o = observation(); o.addProperty("file", "screenshots/" + name + ".png"); o.addProperty("nativeFrame", nativeFrames); screenshots.add(o);
        } catch (Throwable error) { failure = error; }
    }
    private void validate() throws Exception {
        check(scenes.size() == Case.values().length, "All ten actual perspective, camera, vehicle, persistence and obstruction scenarios completed");
        check(obstructionFrames >= 20, "Blocked geometry and its label were both rendered in at least twenty actual frames");
        var clips = movie.summary().getAsJsonArray("clips"); check(clips.size() == 3, "Three bounded clips cover ground adjustment, mounted adjustment and blocker highlighting");
        double total = 0; for (var value : clips) {
            var c = value.getAsJsonObject(); total += number(c, "captureDurationSeconds");
            check(number(c, "sourceFps") >= 30 && number(c, "capturedFrames") == number(c, "writtenFrames"), "Every native capture PNG was written at at least 30 Hz: " + text(c, "id"));
            check(number(c, "maximumFrameGapSeconds") <= .25, "Every actual native frame gap stays within the independent encoder's quarter-second limit: " + text(c, "id"));
        }
        check(total <= 15 && screenshots.size() >= 8, "Native recording stays below fifteen seconds and screenshots cover every visible result");
        for (var value : screenshots) require(Files.size(root.resolve(text(value.getAsJsonObject(), "file"))) > 1000, "Native screenshot missing");
    }
    private String testedSha() throws Exception { return sha(Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI())); }
    private void finish(Throwable error) {
        if (done) return; done = true; releaseKeys(); stopClip();
        try {
            var o = new JsonObject(); o.addProperty("passed", error == null); o.addProperty("error", error == null ? null : error.toString());
            o.addProperty("reloadVerification", reload); o.addProperty("world", System.getProperty("fsrpg.cameraScheduleWorld"));
            o.addProperty("checks", checks.size()); o.add("checksPassed", checks); o.add("scenes", scenes); o.add("screenshots", screenshots);
            o.addProperty("inputProvenance", "Actual KeyMapping held states then queued clicks published in ClientTick.Pre -> native movement and production handlers; observations run at ClientTick.Post LOWEST; no direct camera parameter mutation, attack packet construction or combat snapshot spoofing");
            o.add("inputTrace", inputTrace);
            o.addProperty("osPointerCaptured", false); o.addProperty("nativeFrames", nativeFrames); o.addProperty("obstructionRenderedFrames", obstructionFrames);
            o.add("initialCameraSettings", baseline); o.add("finalObservation", observation()); o.add("packetTrace", RpgConfig.JSON.toJsonTree(packets)); o.add("serverEvents", RpgConfig.JSON.toJsonTree(serverEvents));
            if (!reload) o.add("renderCapture", movie.summary()); RpgSmoke.windowEnvironment(o);
            var jar = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); o.addProperty("modJar", jar.toString()); o.addProperty("modJarSha256", sha(jar));
            RpgConfig.atomicWrite(root.resolve(reload ? "camera-schedule-reload-review.json" : "camera-schedule-review.json"), RpgConfig.JSON.toJson(o));
            System.out.println("CAMERA SCHEDULE REVIEW COMPLETE reload=" + reload + " passed=" + (error == null)); if (error != null) error.printStackTrace();
        } catch (Throwable reporting) { reporting.printStackTrace(); }
        if (channel != null) channel.eventLoop().execute(() -> { if (channel.pipeline().get(PROBE) != null) channel.pipeline().remove(PROBE); });
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) try {
            MOUSE_GRABBED.setBoolean(game.mouseHandler, oldMouse); CombatCamera.reset(); game.options.setCameraType(oldCamera);
            game.options.framerateLimit().set(oldFps); game.options.renderDistance().set(oldDistance); game.options.guiScale().set(oldScale); game.options.fov().set(oldFov);
            game.options.pauseOnLostFocus = oldPause; game.options.enableVsync().set(oldVsync); game.options.hideGui = oldHide; game.setWindowActive(oldActive);
        } catch (Throwable restore) { restore.printStackTrace(); }
        game.stop();
    }
}

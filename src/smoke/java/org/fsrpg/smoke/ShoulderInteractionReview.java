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
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.ShoulderInteraction;
import org.fsrpg.client.ShoulderInteractionView;
import org.fsrpg.client.ShoulderViewRay;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgActionPayload;
import org.lwjgl.glfw.GLFW;

/** Copied-world acceptance through real key mappings and vanilla interaction/network paths. */
final class ShoulderInteractionReview {
    private enum Case { MINE, RELEASE, SWITCH, ENTITY, BLOCK_WALL, ENTITY_WALL, ENTITY_COVER, MID_WALL, RANGE, RETURN, DRAWN, NATIVE_USE, NATIVE_PICK, NATIVE_PIERCING }
    private static final Field MOUSE_GRABBED = field(MouseHandler.class, "mouseGrabbed");
    private static final Field OVERLAY = field(Gui.class, "overlayMessageString");
    private static final Field OVERLAY_TIME = field(Gui.class, "overlayMessageTime");
    private static final Field ENTITY_PACKET_ID = field(ServerboundInteractPacket.class, "entityId");
    private static final String PACKET_PROBE = "fsrpg_shoulder_interaction_review";
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie = new RenderFrameCapture(root);
    private final JsonArray checks = new JsonArray(), scenes = new JsonArray(), screenshots = new JsonArray();
    private final ConcurrentLinkedQueue<JsonObject> packets = new ConcurrentLinkedQueue<>(), inputs = new ConcurrentLinkedQueue<>(), serverEvents = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingShots = new AtomicInteger();
    private volatile Case scenario = Case.MINE;
    private volatile Throwable failure;
    private volatile boolean prepared, packetProbeReady;
    private volatile JsonObject serverState = new JsonObject();
    private JsonObject renderedOutline;
    private UUID playerId;
    private Mob target, cover;
    private volatile int targetId = -1, coverId = -1;
    private Vec3 coverFixturePosition;
    private BlockPos a = new BlockPos(0, 81, 3), b = new BlockPos(-1, 81, 3), wall = new BlockPos(0, 81, 1);
    private Vec3 aimPoint;
    private boolean initialized, done, held, recording, finishing, caseComplete, oldMouseGrabbed, oldPause, oldVsync, oldActive, oldHidden;
    private boolean entering, wallRequested, sawTransition, sawDrawnAction, hotbarSelectionRequested;
    private int oldFps, oldDistance, oldScale, oldFov, warmup, tick, local, phase, phaseTick, stableAim, index, nativeFrames, outlineFrames;
    private int startEntries, continueEntries, middleTransitionFrames, midWallBlockedOutlineFrames, renderedOutlineFrames;
    private int piercingProjectedFrames;
    private long started, clipStarted, viewFramesBefore;
    private long caseBlockedBefore, caseNoticesBefore;
    private double shoulderSide = 1;
    private CameraType oldCamera;
    private KeyMapping drawKey;
    private Channel channel;
    private String capture;

    ShoulderInteractionReview() {
        NeoForge.EVENT_BUS.addListener(this::preTick);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::interaction);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::outline);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::broken);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::attacked);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private static Field field(Class<?> type, String name) {
        try { var value = type.getDeclaredField(name); value.setAccessible(true); return value; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void check(boolean value, String message) { require(value, message); checks.add(message); System.out.println("SHOULDER INTERACTION PASS " + message); }
    private static String text(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsString() : ""; }
    private static double number(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsDouble() : 0; }
    private static boolean bool(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    private static JsonObject vector(Vec3 v) { var out = new JsonObject(); out.addProperty("x", v.x); out.addProperty("y", v.y); out.addProperty("z", v.z); return out; }
    private static JsonObject pos(BlockPos p) { return vector(Vec3.atLowerCornerOf(p)); }
    private JsonObject row(String kind) {
        var out = new JsonObject(); out.addProperty("case", scenario.name()); out.addProperty("kind", kind);
        out.addProperty("clientTick", tick); out.addProperty("phase", phase); out.addProperty("nativeNanos", System.nanoTime()); return out;
    }
    private static JsonObject hit(HitResult value) {
        var out = new JsonObject(); if (value == null) { out.addProperty("type", "NULL"); return out; }
        out.addProperty("type", value.getType().name()); out.add("location", vector(value.getLocation()));
        if (value instanceof BlockHitResult block) { out.add("blockPos", pos(block.getBlockPos())); out.addProperty("face", block.getDirection().name()); }
        if (value instanceof EntityHitResult entity) out.addProperty("entityId", entity.getEntity().getId()); return out;
    }
    private void monitor() {
        var env = new JsonObject(); RpgSmoke.windowEnvironment(env);
        require("\\\\.\\DISPLAY5".equals(text(env, "monitorAdapter")), "Interaction review left DISPLAY5: " + env);
    }
    private void server(Consumer<ServerPlayer> task) {
        var game = Minecraft.getInstance(); var id = playerId;
        game.getSingleplayerServer().execute(() -> { try { task.accept(game.getSingleplayerServer().getPlayerList().getPlayer(id)); } catch (Throwable error) { failure = error; } });
    }

    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots")); monitor();
        String name = System.getProperty("fsrpg.shoulderInteractionWorld", "");
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(name.startsWith("FSRPG Shoulder Interaction Review ") && world.getFileName().toString().equals(name)
                && Files.isRegularFile(world.resolve(".fsrpg-shoulder-interaction-test-world")), "Only the exact marked copied shoulder-interaction world is modified");
        playerId = game.player.getUUID(); oldFps = game.options.framerateLimit().get(); oldDistance = game.options.renderDistance().get();
        oldScale = game.options.guiScale().get(); oldFov = game.options.fov().get(); oldPause = game.options.pauseOnLostFocus;
        oldVsync = game.options.enableVsync().get(); oldActive = game.isWindowActive(); oldHidden = game.options.hideGui; oldCamera = game.options.getCameraType();
        oldMouseGrabbed = MOUSE_GRABBED.getBoolean(game.mouseHandler);
        game.options.framerateLimit().set(40); game.options.renderDistance().set(3); game.options.guiScale().set(2); game.options.fov().set(65);
        game.options.pauseOnLostFocus = false; game.options.enableVsync().set(false); game.options.hideGui = false;
        game.setWindowActive(true); CombatCamera.reset(); game.options.setCameraType(CameraType.FIRST_PERSON); KeyMapping.releaseAll();
        // This sets only the vanilla input gate. Never capture, move or focus the user's OS pointer.
        MOUSE_GRABBED.setBoolean(game.mouseHandler, true); game.getTutorial().setStep(TutorialSteps.NONE);
        for (var mapping : game.options.keyMappings) if (mapping.getName().equals("key.fsrpg.draw")) drawKey = mapping;
        check(drawKey != null, "Actual R draw/camera-only key mapping is available");
        var env = new JsonObject(); RpgSmoke.windowEnvironment(env); var bounds = env.getAsJsonObject("monitorBounds");
        long handle = game.getWindow().handle(); GLFW.glfwSetWindowSize(handle, 1100, 720);
        GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 30, bounds.get("y").getAsInt() + 35);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 原版准星攻击与挖掘验收 · DISPLAY5"); game.resizeDisplay();
        channel = game.getConnection().getConnection().channel();
        channel.eventLoop().execute(() -> {
            try {
                channel.pipeline().addLast(PACKET_PROBE, new ChannelDuplexHandler() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        try { observePacket(message); } catch (Throwable error) { failure = error; }
                        super.write(context, message, promise);
                    }
                }); packetProbeReady = true;
            } catch (Throwable error) { failure = error; }
        });
        initialized = true; started = System.nanoTime(); viewFramesBefore = ShoulderInteractionView.frames(); prepareCase();
    }

    private void prepareCase() {
        prepared = false; held = caseComplete = false; local = phase = phaseTick = stableAim = 0; entering = wallRequested = hotbarSelectionRequested = false;
        var selection = ShoulderInteraction.diagnostics();
        caseBlockedBefore = (long) number(selection, "blockedAttempts"); caseNoticesBefore = (long) number(selection, "noticeCount");
        boolean corner = scenario == Case.BLOCK_WALL || scenario == Case.ENTITY_WALL || scenario == Case.ENTITY_COVER || scenario == Case.MID_WALL;
        a = new BlockPos(corner ? (shoulderSide > 0 ? 1 : -1) : 0, 81, scenario == Case.RANGE ? 5 : 3);
        b = scenario == Case.SWITCH ? new BlockPos(1, 81, 3) : new BlockPos(-1, 81, 3);
        if (nativeCompatibility()) b = new BlockPos(shoulderSide > 0 ? -1 : 1, 81, 3);
        aimPoint = new Vec3(corner ? (shoulderSide > 0 ? 1.15 : -.15) : scenario == Case.RANGE ? .98 : .5, 81.5, a.getZ() + .001);
        boolean entity = scenario == Case.ENTITY || scenario == Case.ENTITY_WALL;
        if (scenario == Case.DRAWN) { CombatCamera.reset(); Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON); }
        server(p -> {
            if (target != null) { target.discard(); target = null; } targetId = -1;
            if (cover != null) { cover.discard(); cover = null; } coverId = -1; coverFixturePosition = null;
            p.setGameMode(scenario == Case.NATIVE_PICK ? GameType.CREATIVE : GameType.SURVIVAL); p.setInvulnerable(true); p.getFoodData().setFoodLevel(20);
            p.getInventory().clearContent(); p.getInventory().setSelectedSlot(0);
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(scenario == Case.DRAWN ? Items.DIAMOND_SWORD : Items.APPLE));
            p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY); RpgSmoke.actor(p).forceSheathed();
            if (scenario == Case.DRAWN) {
                RpgServer.instance().progression().learnFree(p.getUUID(), "rapier");
                RpgSmoke.actor(p).setStyle(RpgServer.instance().config().current().styles().get("rapier"));
                RpgSmoke.actor(p).setResources(RpgSmoke.actor(p).maxStamina(), 100);
            }
            RpgServer.instance().action(p, "status", "");
            p.level().setDayTime(6000); p.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -8; x <= 8; x++) for (int z = -7; z <= 11; z++) {
                p.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 80; y <= 84; y++) p.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            if (!entity && scenario != Case.DRAWN) {
                p.level().setBlock(a, (scenario == Case.RELEASE || scenario == Case.SWITCH || scenario == Case.MID_WALL ? Blocks.OAK_LOG : Blocks.DIRT).defaultBlockState(), 2);
                if (scenario == Case.MINE || scenario == Case.SWITCH) p.level().setBlock(b, Blocks.DIRT.defaultBlockState(), 2);
                if (scenario == Case.NATIVE_USE) {
                    p.level().setBlock(b.relative(Direction.SOUTH), Blocks.STONE.defaultBlockState(), 2);
                    p.level().setBlock(b, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.NORTH), 2);
                } else if (scenario == Case.NATIVE_PICK || scenario == Case.NATIVE_PIERCING) p.level().setBlock(b, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
            }
            if (entity) {
                target = EntityType.HUSK.create(p.level(), EntitySpawnReason.COMMAND); require(target != null, "Cannot create native entity target");
                target.setPos(corner ? (shoulderSide > 0 ? 1.35 : -.35) : .5, 80, 3.0); target.setNoAi(true); target.setPersistenceRequired();
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
                target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); p.level().addFreshEntity(target); targetId = target.getId();
            }
            p.connection.teleport(corner ? (shoulderSide > 0 ? .85 : .15) : .5, 80, .5, 0, 0); p.setDeltaMovement(Vec3.ZERO); prepared = true;
        });
    }

    private void preTick(ClientTickEvent.Pre event) {
        if (!initialized || done) return;
        var game = Minecraft.getInstance(); if (game.player == null) return;
        try { game.setWindowActive(true); MOUSE_GRABBED.setBoolean(game.mouseHandler, true); game.options.keyAttack.setDown(held); }
        catch (Throwable error) { failure = error; }
    }
    private void press() { held = true; KeyMapping.click(Minecraft.getInstance().options.keyAttack.getKey()); }
    private void release() { held = false; Minecraft.getInstance().options.keyAttack.setDown(false); }
    private void rKey() { KeyMapping.click(drawKey.getKey()); }
    private boolean nativeCompatibility() { return scenario == Case.NATIVE_USE || scenario == Case.NATIVE_PICK || scenario == Case.NATIVE_PIERCING; }
    private boolean nativeAtBlock(BlockPos p) {
        var game = Minecraft.getInstance(); return game.player.raycastHitResult(1, game.player) instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(p);
    }
    private void nextPhase() { phase++; phaseTick = 0; }
    private boolean atBlock(BlockPos p) { return Minecraft.getInstance().hitResult instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(p); }
    private static boolean selectedBlock(JsonObject hit, BlockPos p) {
        return hit != null && text(hit, "type").equals("BLOCK") && pos(p).equals(hit.get("blockPos"));
    }
    private boolean atTarget() {
        if (scenario == Case.ENTITY || scenario == Case.ENTITY_WALL)
            return Minecraft.getInstance().hitResult instanceof EntityHitResult h && h.getEntity().getId() == targetId;
        return atBlock(a);
    }
    private boolean aim(Vec3 point) {
        var game = Minecraft.getInstance(); Vec3 camera = game.gameRenderer.getMainCamera().position(), delta = point.subtract(camera);
        double yaw = -Math.toDegrees(Math.atan2(delta.x, delta.z)), pitch = -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        double currentYaw = CombatCamera.active() ? CombatCamera.yaw() : game.player.getYRot();
        double currentPitch = CombatCamera.active() ? CombatCamera.pitch() : game.player.getXRot();
        double dy = wrap(yaw - currentYaw), dp = pitch - currentPitch;
        double sensitivity = game.options.sensitivity().get() * .6 + .2, gain = sensitivity * sensitivity * sensitivity * 8 * .15;
        if (!CombatCamera.mouse(dy / (gain * (game.options.invertMouseX().get() ? -1 : 1)), dp / (gain * (game.options.invertMouseY().get() ? -1 : 1)))) game.player.turn(dy / .15, dp / .15);
        return Math.hypot(dy, dp) < .20;
    }
    private boolean aimNativeEye(Vec3 point) {
        var game = Minecraft.getInstance(); Vec3 delta = point.subtract(game.player.getEyePosition());
        double yaw = -Math.toDegrees(Math.atan2(delta.x, delta.z)), pitch = -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        double dy = wrap(yaw - CombatCamera.yaw()), dp = pitch - CombatCamera.pitch();
        double sensitivity = game.options.sensitivity().get() * .6 + .2, gain = sensitivity * sensitivity * sensitivity * 8 * .15;
        require(CombatCamera.mouse(dy / (gain * (game.options.invertMouseX().get() ? -1 : 1)), dp / (gain * (game.options.invertMouseY().get() ? -1 : 1))),
                "Native compatibility fixture must steer through the live shoulder mouse entry");
        return Math.hypot(dy, dp) < .20;
    }
    private static double wrap(double degrees) { return degrees - Math.floor((degrees + 180) / 360) * 360; }
    private Vec3 targetAim() {
        if (scenario == Case.ENTITY || scenario == Case.ENTITY_WALL) {
            var e = Minecraft.getInstance().level.getEntity(targetId);
            if (e != null) return e.getBoundingBox().getCenter();
        }
        return aimPoint;
    }
    private void addWall() {
        wallRequested = true; server(p -> p.level().setBlock(wall, Blocks.STONE.defaultBlockState(), 3));
    }
    private void addEntityCover() {
        var game = Minecraft.getInstance(); Vec3 eye = game.player.getEyePosition();
        Vec3 intended = game.hitResult.getLocation().subtract(eye).normalize(), nativeDirection = game.player.getViewVector(1).normalize();
        // A real 0.4 m chicken spans both eye rays near the player while the offset camera looks past it.
        // Do not move it again after spawning, or alter any hit result to manufacture this geometry.
        Vec3 center = eye.add(intended.add(nativeDirection).scale(.35));
        server(p -> {
            cover = EntityType.CHICKEN.create(p.level(), EntitySpawnReason.COMMAND); require(cover != null, "Cannot create native near-entity cover");
            coverFixturePosition = center.add(0, -cover.getBbHeight() / 2, 0); cover.setPos(coverFixturePosition);
            cover.setNoAi(true); cover.setNoGravity(true); cover.setPersistenceRequired(); cover.setDeltaMovement(Vec3.ZERO);
            cover.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); cover.setHealth(100);
            cover.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); p.level().addFreshEntity(cover); coverId = cover.getId();
        });
    }
    private boolean eyeObstructed() {
        var game = Minecraft.getInstance(); var obstruction = game.level.clip(new ClipContext(game.player.getEyePosition(), targetAim(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, game.player));
        return obstruction.getType() == HitResult.Type.BLOCK && obstruction.getBlockPos().equals(wall);
    }
    private long packets(String kind) { return packets.stream().filter(p -> text(p, "case").equals(scenario.name()) && text(p, "kind").equals(kind)).count(); }
    private long blockPackets(String kind, BlockPos p) { return packets.stream().filter(v -> text(v, "case").equals(scenario.name()) && text(v, "kind").equals(kind) && pos(p).equals(v.get("pos"))).count(); }
    private long serverEvents(String kind) { return serverEvents.stream().filter(v -> text(v, "case").equals(scenario.name()) && text(v, "kind").equals(kind) && !bool(v, "canceled")).count(); }
    private void noCustomAttack() {
        check(packets.stream().noneMatch(v -> text(v, "case").equals(scenario.name()) && text(v, "kind").equals("CUSTOM") && !text(v, "action").equals("status")),
                scenario + ": ordinary left interaction sends no custom RPG action (status heartbeat allowed)");
    }
    private void startClip(String id) throws Exception { movie.start(id, "Real key mappings, vanilla packet/server outcomes and rendered crosshair/outline", this::observation); recording = true; clipStarted = System.nanoTime(); }
    private void stopClip() { if (recording) { movie.stop(); recording = false; } }
    private void shot(String name) { require(capture == null, "Overlapping native screenshot requests"); capture = scenario.name().toLowerCase() + "-" + name; }

    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance(); if (done || game.player == null || game.level == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) { if (++warmup >= 60) initialize(game); return; }
            if (failure != null) throw new IllegalStateException("Native shoulder interaction fixture failed", failure);
            require(System.nanoTime() - started < 240_000_000_000L, "Shoulder interaction review exceeded four minutes");
            movie.assertHealthy(); game.getFramerateLimitTracker().onInputReceived(); tick++;
            if (finishing) { if (capture == null && pendingShots.get() == 0 && movie.finish()) { validate(); finish(null); } return; }
            if (caseComplete) {
                // Keep this fixture intact until its actual framebuffer has been captured.
                if (capture == null && pendingShots.get() == 0) {
                    if (++index == Case.values().length) finishing = true;
                    else { scenario = Case.values()[index]; prepareCase(); }
                }
                return;
            }
            if (!prepared || !packetProbeReady) return; local++; phaseTick++;
            require(local < 320, "Interaction scenario timed out: " + scenario + " phase " + phase + " " + ShoulderInteraction.diagnostics());
            if (phase == 0) {
                if (local < 12 || !bool(serverState, "ready")) return;
                // Native creative pick may select another client hotbar slot. Server fixture inventory
                // preparation does not select that slot on the client; use the genuine hotbar key path.
                if (!hotbarSelectionRequested) {
                    KeyMapping.click(game.options.keyHotbarSlots[0].getKey()); hotbarSelectionRequested = true;
                    inputs.add(row("HOTBAR_1_KEY")); return;
                }
                if (game.player.getInventory().getSelectedSlot() != 0 || number(serverState, "selectedSlot") != 0) return;
                if (scenario == Case.DRAWN) {
                    if (!game.player.getMainHandItem().is(Items.DIAMOND_SWORD)) return;
                    if (!entering && ClientState.bool(ClientState.snapshot(), "weaponEquipped", false)) { rKey(); entering = true; }
                    if (!ClientState.drawn() || !text(serverState, "actorPhase").equals("READY")) return;
                    check(!ShoulderInteraction.active(), "Drawn skill mode bypasses ordinary shoulder interaction correction");
                    press(); nextPhase(); return;
                }
                if (!game.player.getMainHandItem().is(Items.APPLE) || !text(serverState, "mainItem").equals("minecraft:apple")
                        || ClientState.drawn() || ClientState.bool(ClientState.snapshot(), "weaponEquipped", false)
                        || !ClientState.text(ClientState.snapshot(), "phase", "").equals("SHEATHED")) return;
                if (!CombatCamera.cameraOnly() && !entering) { rKey(); entering = true; return; }
                if (!CombatCamera.cameraOnly() || CombatCamera.transitionBlend() < .99) return;
                if (scenario == Case.MINE) {
                    var camera = game.gameRenderer.getMainCamera().position(); shoulderSide = Math.signum(camera.x - game.player.getEyePosition().x);
                    if (shoulderSide == 0) shoulderSide = 1;
                    aimPoint = new Vec3(shoulderSide > 0 ? .055 : .945, 81.5, a.getZ() + .001);
                    // Put the decoy on the side the uncorrected eye ray misses into.
                    if (shoulderSide < 0 && b.getX() < 0) { b = new BlockPos(1, 81, 3); server(p -> p.level().setBlock(b, Blocks.DIRT.defaultBlockState(), 2)); }
                }
                boolean pointed = nativeCompatibility()
                        ? aimNativeEye(new Vec3(b.getX() + .5, b.getY() + .5, b.getZ() + (scenario == Case.NATIVE_USE ? .8125 : .001)))
                        : aim(targetAim());
                stableAim = pointed && atTarget() && (!nativeCompatibility() || nativeAtBlock(b)) ? stableAim + 1 : 0;
                if (stableAim < 5) return;
                check(CombatCamera.cameraOnly() && !ClientState.drawn(), scenario + ": actual R selects local camera-only mode with an ordinary apple");
                if (scenario != Case.RANGE) check(!ShoulderInteraction.blocked(), scenario + ": unobstructed fixture is initially accepted before native input");
                if (scenario == Case.BLOCK_WALL || scenario == Case.ENTITY_WALL) { addWall(); nextPhase(); return; }
                if (scenario == Case.ENTITY_COVER) { addEntityCover(); nextPhase(); return; }
                if (nativeCompatibility()) {
                    check(atBlock(a) && nativeAtBlock(b), "Compatibility fixture displays camera block A while the genuine vanilla eye pick selects different block B");
                    var proof = row("NATIVE_COMPATIBILITY_GEOMETRY"); proof.add("cameraPick", hit(game.hitResult)); proof.add("nativeEyePick", hit(game.player.raycastHitResult(1, game.player))); inputs.add(proof);
                    if (scenario == Case.NATIVE_USE) KeyMapping.click(game.options.keyUse.getKey());
                    else if (scenario == Case.NATIVE_PICK) KeyMapping.click(game.options.keyPickItem.getKey());
                    else server(p -> {
                        // Keep this ordinary-input fixture outside the existing RPG weapon-ID auto-draw rules.
                        var nativeSpear = Items.IRON_SPEAR.getDefaultInstance(); var nativePiercingStick = new ItemStack(Items.STICK);
                        require(nativeSpear.has(DataComponents.PIERCING_WEAPON) && nativeSpear.has(DataComponents.ATTACK_RANGE),
                                "Vanilla iron spear must provide the native piercing and attack-range components");
                        nativePiercingStick.set(DataComponents.PIERCING_WEAPON, nativeSpear.get(DataComponents.PIERCING_WEAPON));
                        nativePiercingStick.set(DataComponents.ATTACK_RANGE, nativeSpear.get(DataComponents.ATTACK_RANGE));
                        p.setItemSlot(EquipmentSlot.MAINHAND, nativePiercingStick); RpgServer.instance().action(p, "status", "");
                    });
                    nextPhase(); return;
                }
                if (scenario == Case.RANGE) {
                    check(game.player.getEyePosition().distanceTo(game.hitResult.getLocation()) > game.player.blockInteractionRange(), "Range fixture is beyond the player's real vanilla block reach");
                    press(); nextPhase(); return;
                }
                if (scenario == Case.RETURN) { startClip("return-to-first-person"); rKey(); nextPhase(); return; }
                if (scenario == Case.MINE) {
                    var original = game.player.raycastHitResult(1, game.player);
                    check(!(original instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(a)), "Near target is under the rendered reticle while the uncorrected vanilla eye ray points elsewhere");
                    startClip("crosshair-native-mining");
                }
                shot("reticle-before-input"); press(); nextPhase(); return;
            }
            runCase(game);
        } catch (Throwable error) { finish(error); }
    }

    private void runCase(Minecraft game) throws Exception {
        switch (scenario) {
            case MINE -> {
                if (phaseTick == 5) shot("native-cracks");
                if (bool(serverState, "aAir")) release();
                if (phaseTick >= 42) {
                    check(bool(serverState, "aAir") && !bool(serverState, "bAir"), "Vanilla mining destroys only the camera-reticle block, leaving the eye-ray decoy intact");
                    check(blockPackets("START", a) >= 1 && blockPackets("STOP", a) == 1 && serverEvents("BREAK") == 1, "Actual START/STOP packets culminate in exactly one authoritative block break");
                    stopClip(); completeCase();
                }
            }
            case RELEASE -> {
                if (phaseTick == 6) { release(); shot("released-before-break"); }
                if (phaseTick >= 80) {
                    check(blockPackets("START", a) == 1 && blockPackets("ABORT", a) >= 1 && blockPackets("STOP", a) == 0, "Releasing the real attack key produces vanilla ABORT without STOP");
                    check(!bool(serverState, "aAir") && serverEvents("BREAK") == 0, "Released partial mining never destroys the server block after its normal break duration"); completeCase();
                }
            }
            case SWITCH -> {
                if (phase == 1 && phaseTick >= 6) { aimPoint = new Vec3(b.getX() + .5, 81.5, b.getZ() + .001); nextPhase(); }
                if (phase == 2) {
                    aim(aimPoint);
                    if (bool(serverState, "bAir")) release();
                    if (phaseTick >= 48) {
                        check(!bool(serverState, "aAir") && bool(serverState, "bAir"), "Holding attack while moving the reticle destroys the new target and preserves the unfinished old target");
                        check(blockPackets("ABORT", a) >= 1 && blockPackets("START", b) >= 1 && blockPackets("STOP", b) == 1, "Retargeted native mining sends ABORT(old), START(new), STOP(new)");
                        shot("retargeted-server-result"); completeCase();
                    }
                }
            }
            case ENTITY -> {
                if (phaseTick == 2) release();
                if (phaseTick >= 15) {
                    check(packets("ATTACK") == 1 && serverEvents("ENTITY_ATTACK") == 1 && number(serverState, "targetHealth") < 100, "One ordinary key press sends one vanilla entity ATTACK and injures the authoritative reticle target");
                    check(packets.stream().anyMatch(v -> text(v, "case").equals(scenario.name()) && text(v, "kind").equals("ATTACK") && number(v, "entityId") == targetId)
                                    && serverEvents.stream().anyMatch(v -> text(v, "case").equals(scenario.name()) && text(v, "kind").equals("ENTITY_ATTACK") && number(v, "entityId") == targetId),
                            "Packet and authoritative attack event identify exactly the camera-reticle entity");
                    shot("native-entity-damage"); completeCase();
                }
            }
            case BLOCK_WALL, ENTITY_WALL -> {
                if (phase == 1) {
                    aim(targetAim());
                    if (phaseTick < 12) return;
                    check(atTarget() && eyeObstructed(), "Corner fixture remains camera-visible while the real eye-to-target path crosses the wall");
                    check(ShoulderInteraction.blocked(), "Production selection identifies the eye obstruction");
                    if (scenario == Case.BLOCK_WALL) startClip("blocked-native-interaction");
                    press(); nextPhase(); return;
                }
                if (phaseTick == 7) { checkNotice(); shot("blocked-warning-and-raw-outline"); }
                if (phaseTick == 14) release();
                if (phaseTick >= (scenario == Case.BLOCK_WALL ? 42 : 25)) {
                    check(packets("START") == 0 && packets("ATTACK") == 0 && packets("STOP") == 0, "Blocked left input emits no native destroy or attack request");
                    check(!bool(serverState, "wallAir") && (scenario == Case.BLOCK_WALL ? !bool(serverState, "aAir") : number(serverState, "targetHealth") == 100), "Obstruction neither damages the hidden target nor redirects the click into breaking the wall");
                    stopClip(); completeCase();
                }
            }
            case ENTITY_COVER -> {
                if (phase == 1) {
                    aim(targetAim()); if (phaseTick < 12 || coverId < 0) return;
                    var nativePick = game.player.raycastHitResult(1, game.player);
                    var near = game.level.getEntity(coverId); var diagnostics = ShoulderInteraction.diagnostics();
                    check(nativePick instanceof EntityHitResult e && e.getEntity().getId() == coverId,
                            "The genuine vanilla eye pick selects the nearby immobile entity");
                    check(atBlock(a) && near != null && near.getBoundingBox().clip(game.player.getEyePosition(), game.hitResult.getLocation()).isPresent(),
                            "The camera still selects the rear block while the real eye-to-block segment crosses the near entity");
                    var obstruction = diagnostics.getAsJsonObject("obstruction");
                    check(ShoulderInteraction.blocked() && text(diagnostics, "reason").equals("OBSTRUCTED")
                                    && obstruction != null && text(obstruction, "type").equals("ENTITY") && number(obstruction, "entityId") == coverId,
                            "Production occlusion identifies that exact nearer entity without replacing the raw block selection");
                    var proof = row("ENTITY_COVER_GEOMETRY"); proof.add("nativeEyePick", hit(nativePick)); proof.add("cameraPick", hit(game.hitResult));
                    proof.addProperty("coverEntityId", coverId); proof.add("coverPosition", vector(near.position()));
                    proof.addProperty("coverWidth", near.getBbWidth()); proof.addProperty("coverHeight", near.getBbHeight()); inputs.add(proof);
                    press(); nextPhase(); return;
                }
                if (phaseTick == 7) { checkNotice(); shot("near-entity-blocks-eye-only"); }
                if (phaseTick == 14) release();
                if (phaseTick >= 25) {
                    check(packets("START") == 0 && packets("STOP") == 0 && packets("ATTACK") == 0,
                            "Entity-covered block click emits neither destruction nor redirected entity attack packets");
                    check(!bool(serverState, "aAir") && number(serverState, "coverHealth") == 100
                                    && number(serverState, "coverDisplacement") < .0001 && serverEvents("BREAK") == 0 && serverEvents("ENTITY_ATTACK") == 0,
                            "The authoritative rear block and immobile near entity remain unchanged after blocked input");
                    completeCase();
                }
            }
            case MID_WALL -> {
                if (phaseTick == 6) { check(blockPackets("START", a) >= 1, "Native mining is genuinely in progress before the wall appears"); addWall(); }
                if (phaseTick == 17) {
                    // A canceled continueAttack can leave its effective MISS until the next render pick.
                    // Check intent/occlusion here and prove visible selection separately at RenderFrame.Post.
                    var selection = ShoulderInteraction.diagnostics();
                    check(eyeObstructed() && bool(selection, "blocked") && text(selection, "reason").equals("OBSTRUCTED")
                                    && selectedBlock(selection.getAsJsonObject("raw"), a) && selectedBlock(selection.getAsJsonObject("obstruction"), wall),
                            "Added wall obstructs the mining eye path while the raw camera selection remains the original block");
                    check(midWallBlockedOutlineFrames >= 3, "Blocked ongoing mining preserves the original displayed target and native outline across actual rendered frames");
                    checkNotice(); shot("mid-mine-abort");
                }
                if (phaseTick == 35) release();
                if (phaseTick >= 85) {
                    check(blockPackets("ABORT", a) >= 1 && blockPackets("STOP", a) == 0, "Fresh continueAttack occlusion check aborts an existing native mining operation");
                    check(!bool(serverState, "aAir") && !bool(serverState, "wallAir") && serverEvents("BREAK") == 0, "Blocked ongoing mining remains stopped on the server after the full break duration"); completeCase();
                }
            }
            case RANGE -> {
                if (phaseTick == 6) { checkNotice(); shot("beyond-official-reach"); }
                if (phaseTick == 10) release();
                if (phaseTick >= 20) {
                    check(packets("START") == 0 && packets("ATTACK") == 0 && !bool(serverState, "aAir"), "Camera reach never enlarges the player's native block interaction range");
                    check(text(ShoulderInteraction.diagnostics(), "reason").equals("OUT_OF_RANGE"), "Out-of-range rejection is explicit in the live selection"); completeCase();
                }
            }
            case RETURN -> {
                aim(aimPoint); double blend = CombatCamera.transitionBlend();
                if (blend > .15 && blend < .85) { sawTransition = true; }
                if (!held && phase == 1 && blend > .25 && blend < .8) { shot("transition-reticle"); press(); nextPhase(); }
                if (bool(serverState, "aAir")) release();
                if (local > 100 && blend < .001) {
                    check(sawTransition && middleTransitionFrames >= 3, "Actual rendered return contains multiple intermediate shoulder-to-eye frames");
                    check(game.options.getCameraType().isFirstPerson() && !ShoulderInteraction.active(), "Fully returned first person restores the ordinary vanilla interaction path");
                    check(bool(serverState, "aAir") && blockPackets("STOP", a) == 1, "Mining started during the camera return completes through the original vanilla server path");
                    shot("first-person-restored"); stopClip(); completeCase();
                }
            }
            case DRAWN -> {
                if (phaseTick == 2) release();
                if (phaseTick >= 45) {
                    check(sawDrawnAction && packets.stream().anyMatch(v -> text(v, "case").equals("DRAWN") && text(v, "kind").equals("CUSTOM") && List.of("attack", "aimAttack", "targetAttack").contains(text(v, "action"))), "Drawn R plus the real left binding still reaches the existing authored combat skill path");
                    check(packets("ATTACK") == 0 && packets("START") == 0, "Drawn combat remains separate from ordinary vanilla entity/block interaction");
                    shot("drawn-skill-bypass"); completeCase();
                }
            }
            case NATIVE_USE -> {
                if (phaseTick >= 16) {
                    check(blockPackets("USE_ITEM_ON", b) == 1 && packets("USE_ITEM_ON") == 1 && bool(serverState, "leverPowered"),
                            "Actual right key uses vanilla block B and powers its authoritative lever exactly once");
                    check(atBlock(a), "Right-use finally restores the camera raw block A for display");
                    check(packets("START") == 0 && packets("STOP") == 0 && packets("ATTACK") == 0, "Native right-use does not emit left attack or destroy requests");
                    shot("native-lever-use-restores-camera-selection"); completeCase();
                }
            }
            case NATIVE_PICK -> {
                if (phaseTick >= 16) {
                    check(game.player.getAbilities().instabuild && blockPackets("PICK_BLOCK", b) == 1 && packets("PICK_BLOCK") == 1,
                            "Actual creative middle key sends the original pick-block packet for vanilla eye target B");
                    check(text(serverState, "mainItem").equals("minecraft:gold_block") && game.player.getMainHandItem().is(Items.GOLD_BLOCK),
                            "The server and client select B's gold-block item rather than camera A's dirt");
                    check(atBlock(a) && packets("START") == 0 && packets("ATTACK") == 0, "Middle-pick finally restores raw A without attack/destruction");
                    shot("native-creative-pick-restores-camera-selection"); completeCase();
                }
            }
            case NATIVE_PIERCING -> {
                if (phase == 1) {
                    if (phaseTick < 12 || !game.player.getMainHandItem().has(DataComponents.PIERCING_WEAPON)) return;
                    var heldItem = game.player.getMainHandItem(); var nativeSpear = Items.IRON_SPEAR.getDefaultInstance();
                    check(heldItem.is(Items.STICK) && CombatCamera.cameraOnly() && !ClientState.drawn()
                                    && nativeSpear.get(DataComponents.PIERCING_WEAPON).equals(heldItem.get(DataComponents.PIERCING_WEAPON))
                                    && nativeSpear.get(DataComponents.ATTACK_RANGE).equals(heldItem.get(DataComponents.ATTACK_RANGE)),
                            "Native piercing and attack-range components from the default iron spear are on an ordinary stick, not an unmodified iron spear, with camera-only active and no RPG weapon draw");
                    check(!ShoulderInteraction.active() && ShoulderInteraction.nativeDirectionalAttack() && sameNativePick(game),
                            "Native piercing bypasses camera selection and keeps the original eye-ray hitResult");
                    check(piercingProjectedFrames >= 8, "Native piercing-component targeting is visibly projected by multiple actual rendered view frames");
                    press(); nextPhase(); return;
                }
                if (phaseTick == 2) release();
                if (phaseTick >= 16) {
                    check(packets("STAB") == 1 && packets("ATTACK") == 0 && packets("START") == 0 && packets("STOP") == 0,
                            "The real attack key with the native piercing-component stick emits only vanilla STAB rather than camera attack/mining requests");
                    check(packets.stream().noneMatch(v -> text(v, "case").equals("NATIVE_PIERCING") && number(v, "phase") >= 2 && text(v, "kind").equals("MOVE_ROT")),
                            "Stationary native piercing-component input adds no forced rotation packet; normal vanilla position traffic remains observable");
                    shot("native-stab-projected-crosshair"); completeCase();
                }
            }
        }
    }

    private static boolean sameNativePick(Minecraft game) {
        var nativeHit = game.player.raycastHitResult(game.getDeltaTracker().getGameTimeDeltaPartialTick(false), game.getCameraEntity());
        var displayed = game.hitResult;
        if (displayed == null || displayed.getType() != nativeHit.getType() || displayed.getLocation().distanceToSqr(nativeHit.getLocation()) > .000001) return false;
        if (nativeHit instanceof BlockHitResult block) return displayed instanceof BlockHitResult other && block.getBlockPos().equals(other.getBlockPos());
        if (nativeHit instanceof EntityHitResult entity) return displayed instanceof EntityHitResult other && entity.getEntity().getId() == other.getEntity().getId();
        return true;
    }

    private void checkNotice() throws Exception {
        var diagnostics = ShoulderInteraction.diagnostics();
        String reason = scenario == Case.RANGE ? "OUT_OF_RANGE" : "OBSTRUCTED";
        check(number(diagnostics, "blockedAttempts") > caseBlockedBefore && number(diagnostics, "noticeCount") > caseNoticesBefore
                        && text(diagnostics, "lastAttemptReason").equals(reason), "This rejected native interaction produces its own production obstruction/range notice");
        check(number(diagnostics, "noticeCount") - caseNoticesBefore <= 2, "Held rejected input rate-limits the visible warning");
        var message = (Component) OVERLAY.get(Minecraft.getInstance().gui);
        check(message != null && message.getString().equals(text(diagnostics, "lastNoticeText")) && !message.getString().isBlank() && OVERLAY_TIME.getInt(Minecraft.getInstance().gui) > 0,
                "The actual vanilla HUD overlay contains the visible rejection message");
    }
    private void completeCase() {
        release(); if (scenario != Case.DRAWN) noCustomAttack();
        var result = observation(); result.addProperty("completed", scenario.name()); scenes.add(result);
        stopClip(); caseComplete = true;
    }

    private void observePacket(Object packet) throws Exception {
        if (!initialized || done) return;
        if (packet instanceof ServerboundPlayerActionPacket action) {
            String kind = switch (action.getAction()) { case START_DESTROY_BLOCK -> "START"; case STOP_DESTROY_BLOCK -> "STOP"; case ABORT_DESTROY_BLOCK -> "ABORT"; case STAB -> "STAB"; default -> "OTHER_PLAYER_ACTION"; };
            var value = row(kind); value.add("pos", pos(action.getPos())); value.addProperty("face", action.getDirection().name()); value.addProperty("sequence", action.getSequence()); packets.add(value);
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            var value = row("USE_ITEM_ON"); value.add("pos", pos(use.getHitResult().getBlockPos())); value.addProperty("hand", use.getHand().name()); packets.add(value);
        } else if (packet instanceof ServerboundPickItemFromBlockPacket pick) {
            var value = row("PICK_BLOCK"); value.add("pos", pos(pick.pos())); packets.add(value);
        } else if (packet instanceof ServerboundMovePlayerPacket move && nativeCompatibility()) {
            var value = row(move instanceof ServerboundMovePlayerPacket.Rot || move instanceof ServerboundMovePlayerPacket.PosRot ? "MOVE_ROT" : "MOVE");
            value.addProperty("packetClass", move.getClass().getSimpleName()); packets.add(value);
        } else if (packet instanceof ServerboundInteractPacket interact) {
            int entityId = ENTITY_PACKET_ID.getInt(interact);
            interact.dispatch(new ServerboundInteractPacket.Handler() {
                public void onInteraction(InteractionHand hand) {}
                public void onInteraction(InteractionHand hand, Vec3 location) {}
                public void onAttack() { var value = row("ATTACK"); value.addProperty("entityId", entityId); packets.add(value); }
            });
        } else if (packet instanceof ServerboundCustomPayloadPacket custom && custom.payload() instanceof RpgActionPayload action) {
            var value = row("CUSTOM"); value.addProperty("action", action.action()); value.addProperty("argument", action.argument()); packets.add(value);
        }
    }
    private void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!initialized || done || !event.isAttack()) return;
        var methods = StackWalker.getInstance().walk(stream -> stream.filter(f -> f.getClassName().equals("net.minecraft.client.Minecraft")).map(StackWalker.StackFrame::getMethodName).toList());
        if (methods.contains("startAttack")) startEntries++;
        if (methods.contains("continueAttack")) continueEntries++;
        var value = row(methods.contains("startAttack") ? "VANILLA_START_ATTACK" : methods.contains("continueAttack") ? "VANILLA_CONTINUE_ATTACK" : "OTHER_INPUT");
        value.addProperty("canceled", event.isCanceled()); value.add("hit", hit(Minecraft.getInstance().hitResult)); inputs.add(value);
    }
    private void outline(ExtractBlockOutlineRenderStateEvent event) {
        if (!initialized || done) return;
        try {
            require(event.getHitResult().equals(Minecraft.getInstance().hitResult), "Native outline extraction disagrees with the displayed hitResult"); outlineFrames++;
            event.addCustomRenderer((state, buffers, poses, translucentPass, levelState) -> {
                // Observe the actual native draw invocation; returning false preserves vanilla rendering.
                // LevelRenderer resets its mutable LevelRenderState before RenderFrame.Post.
                if (initialized && !done && state.isTranslucent() == translucentPass) try {
                    var game = Minecraft.getInstance();
                    require(state.pos().equals(event.getBlockPos()) && atBlock(state.pos()),
                            "Actual native outline draw disagrees with its extracted block and displayed reticle");
                    require(!state.shape().isEmpty(), "Actual native outline draw must have nonempty geometry");
                    var proof = row("NATIVE_OUTLINE_RENDER"); proof.add("pos", pos(state.pos())); proof.add("hit", hit(game.hitResult));
                    var selection = ShoulderInteraction.diagnostics(); proof.add("raw", selection.get("raw").deepCopy());
                    proof.addProperty("blocked", bool(selection, "blocked"));
                    proof.addProperty("nativeFrame", nativeFrames + 1); proof.addProperty("translucentPass", translucentPass);
                    proof.addProperty("callback", "CustomBlockOutlineRenderer.render; returns false");
                    renderedOutline = proof; renderedOutlineFrames++;
                } catch (Throwable error) { failure = error; }
                return false;
            });
        }
        catch (Throwable error) { failure = error; }
    }
    private void broken(BlockEvent.BreakEvent event) {
        if (!initialized || done || !(event.getPlayer() instanceof ServerPlayer p) || !p.getUUID().equals(playerId)) return;
        var value = row("BREAK"); value.add("pos", pos(event.getPos())); value.addProperty("canceled", event.isCanceled()); serverEvents.add(value);
    }
    private void attacked(AttackEntityEvent event) {
        if (!initialized || done || !(event.getEntity() instanceof ServerPlayer p) || !p.getUUID().equals(playerId)) return;
        var value = row("ENTITY_ATTACK"); value.addProperty("entityId", event.getTarget().getId()); value.addProperty("canceled", event.isCanceled()); serverEvents.add(value);
    }
    private void serverTick(ServerTickEvent.Post event) {
        if (!initialized || done || !prepared) return;
        try {
            var p = event.getServer().getPlayerList().getPlayer(playerId); if (p == null) return;
            var value = row("SERVER_STATE"); value.addProperty("ready", true); value.addProperty("aAir", p.level().getBlockState(a).isAir());
            value.addProperty("bAir", p.level().getBlockState(b).isAir()); value.addProperty("wallAir", p.level().getBlockState(wall).isAir());
            value.addProperty("targetHealth", target == null ? -1 : target.getHealth());
            value.addProperty("coverHealth", cover == null ? -1 : cover.getHealth());
            value.addProperty("coverDisplacement", cover == null || coverFixturePosition == null ? -1 : cover.position().distanceTo(coverFixturePosition));
            value.addProperty("mainItem", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
            value.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
            var bState = p.level().getBlockState(b); value.addProperty("leverPowered", bState.is(Blocks.LEVER) && bState.getValue(LeverBlock.POWERED));
            String actorPhase = RpgSmoke.actor(p).phase().name(); value.addProperty("actorPhase", actorPhase);
            if (scenario == Case.DRAWN && List.of("WINDUP", "ACTIVE", "RECOVERY").contains(actorPhase)) sawDrawnAction = true;
            serverState = value;
        } catch (Throwable error) { failure = error; }
    }
    private JsonObject observation() {
        var game = Minecraft.getInstance(); var out = row("FRAME");
        out.add("hit", hit(game.hitResult)); out.add("selection", ShoulderInteraction.diagnostics().deepCopy());
        out.add("camera", vector(game.gameRenderer.getMainCamera().position())); out.add("eye", vector(game.player.getEyePosition()));
        out.addProperty("cameraBlend", CombatCamera.transitionBlend()); out.addProperty("cameraOnly", CombatCamera.cameraOnly());
        out.addProperty("cameraType", game.options.getCameraType().name()); out.addProperty("attackKeyDown", game.options.keyAttack.isDown());
        out.addProperty("selectedSlot", game.player.getInventory().getSelectedSlot());
        out.addProperty("mainItem", BuiltInRegistries.ITEM.getKey(game.player.getMainHandItem().getItem()).toString());
        out.addProperty("actualProjectedViewFrames", ShoulderInteractionView.frames());
        if (scenario == Case.NATIVE_PIERCING && game.hitResult != null && ShoulderInteractionView.current() != null) {
            var p = game.hitResult.getLocation(); var projected = ShoulderInteractionView.current().project(new ShoulderViewRay.Point(p.x, p.y, p.z));
            var pixel = new JsonObject(); pixel.addProperty("ndcX", projected.x()); pixel.addProperty("ndcY", projected.y()); pixel.addProperty("inFront", projected.inFront()); out.add("nativeHitProjection", pixel);
        }
        out.add("server", serverState.deepCopy()); out.addProperty("phase", phase);
        if (renderedOutline != null && number(renderedOutline, "nativeFrame") == nativeFrames) {
            out.add("outlinePos", renderedOutline.get("pos").deepCopy()); out.add("outlineRender", renderedOutline.deepCopy());
        }
        try {
            var notice = (Component) OVERLAY.get(game.gui); if (notice != null) out.addProperty("visibleOverlay", notice.getString());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        return out;
    }
    private void render(RenderFrameEvent.Post event) {
        if (!initialized || done || Minecraft.getInstance().player == null) return;
        nativeFrames++;
        try {
            if (scenario == Case.RETURN && CombatCamera.transitionBlend() > .15 && CombatCamera.transitionBlend() < .85) middleTransitionFrames++;
            var game = Minecraft.getInstance();
            if (scenario == Case.NATIVE_PIERCING && ShoulderInteraction.nativeDirectionalAttack() && ShoulderInteractionView.current() != null && game.hitResult != null) {
                var p = game.hitResult.getLocation(); var projected = ShoulderInteractionView.current().project(new ShoulderViewRay.Point(p.x, p.y, p.z));
                require(sameNativePick(game), "Rendered native piercing-component hitResult differs from the original player ray");
                require(projected.inFront() && Math.abs(projected.x()) < .95 && Math.abs(projected.y()) < .95, "Native piercing-component target projection is outside the visible world frame");
                piercingProjectedFrames++;
            }
            boolean outlineThisFrame = renderedOutline != null && number(renderedOutline, "nativeFrame") == nativeFrames;
            if (outlineThisFrame) require(game.hitResult instanceof BlockHitResult selected && selected.getType() == HitResult.Type.BLOCK
                            && pos(selected.getBlockPos()).equals(renderedOutline.get("pos")),
                    "Rendered native block outline and reticle selection diverged");
            if (scenario == Case.MID_WALL && wallRequested && ShoulderInteraction.blocked()
                    && selectedBlock(ShoulderInteraction.diagnostics().getAsJsonObject("raw"), a)
                    && atBlock(a) && outlineThisFrame && pos(a).equals(renderedOutline.get("pos"))) {
                if (midWallBlockedOutlineFrames++ == 0) {
                    var proof = observation(); proof.addProperty("kind", "MID_WALL_BLOCKED_RENDER");
                    proof.addProperty("nativeFrame", nativeFrames); inputs.add(proof);
                }
            }
            if (recording) { monitor(); movie.frame(event); require(System.nanoTime() - clipStarted < 6_000_000_000L, "Native recording exceeded the per-clip bound"); }
            if (capture == null) return;
            monitor(); String name = capture; capture = null; pendingShots.incrementAndGet();
            Screenshot.grab(root.toFile(), name + ".png", game.getMainRenderTarget(), 1, message -> pendingShots.decrementAndGet());
            var value = observation(); value.addProperty("file", "screenshots/" + name + ".png"); value.addProperty("nativeFrame", nativeFrames); screenshots.add(value);
        } catch (Throwable error) { failure = error; }
    }
    private void validate() throws Exception {
        check(scenes.size() == Case.values().length, "All fourteen actual native interaction and compatibility scenarios completed");
        check(startEntries >= 4 && continueEntries >= 15, "Real Minecraft.startAttack and continueAttack were observed through the original input event stack");
        check(outlineFrames > 40, "Native block outline extraction repeatedly agrees with the displayed reticle hitResult");
        check(renderedOutlineFrames > 40, "Actual native outline draw callbacks repeatedly agree with the displayed reticle and nonempty outline geometry");
        check(ShoulderInteractionView.frames() - viewFramesBefore > 100, "Interaction rays were captured from more than one hundred actual rendered view/projection frames");
        var capture = movie.summary(); double total = 0;
        check(capture.getAsJsonArray("clips").size() == 3, "Three bounded real-render-frame clips cover mining, obstruction and camera return");
        for (var element : capture.getAsJsonArray("clips")) {
            var clip = element.getAsJsonObject(); total += number(clip, "captureDurationSeconds");
            check(number(clip, "sourceFps") >= 30 && number(clip, "capturedFrames") == number(clip, "writtenFrames"), "Every actual native frame was written at at least 30 Hz: " + text(clip, "id"));
            double previous = -1; for (var frame : clip.getAsJsonArray("frames")) {
                var value = frame.getAsJsonObject(); require(number(value, "capturedAtSeconds") > previous && !bool(value, "paused"), "Capture timestamps must advance in an unpaused real world"); previous = number(value, "capturedAtSeconds");
            }
        }
        check(total <= 15, "Native video capture stays below fifteen source seconds");
        check(screenshots.size() >= 12, "Native screenshots cover reticle, cracks, blocked warning, entity, range, transition and skill bypass");
        for (var shot : screenshots) require(Files.size(root.resolve(text(shot.getAsJsonObject(), "file"))) > 1000, "Missing native screenshot");
    }
    private void finish(Throwable error) {
        if (done) return; done = true; release(); stopClip();
        try {
            var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("world", System.getProperty("fsrpg.shoulderInteractionWorld")); report.addProperty("checks", checks.size()); report.add("checksPassed", checks);
            report.addProperty("inputProvenance", "KeyMapping -> Minecraft.handleKeybinds -> startAttack/continueAttack; no direct gameMode attacks, mining calls, damage, destruction or interaction packets");
            report.addProperty("osPointerCaptured", false); report.addProperty("vanillaStartAttackEntries", startEntries); report.addProperty("vanillaContinueAttackEntries", continueEntries);
            report.addProperty("nativeFrames", nativeFrames); report.addProperty("nativeOutlineExtractions", outlineFrames); report.addProperty("intermediateReturnFrames", middleTransitionFrames);
            report.addProperty("midWallBlockedOutlineFrames", midWallBlockedOutlineFrames);
            report.addProperty("nativeOutlineDrawCallbacks", renderedOutlineFrames);
            report.addProperty("actualProjectedViewFrames", ShoulderInteractionView.frames() - viewFramesBefore);
            report.addProperty("nativePiercingProjectedFrames", piercingProjectedFrames);
            report.addProperty("nativePiercingFixture", "Ordinary minecraft:stick carrying the default minecraft:iron_spear PIERCING_WEAPON and ATTACK_RANGE components; not an unmodified iron spear; existing RPG weapon-ID auto-draw behavior is preserved");
            report.add("scenes", scenes); report.add("screenshots", screenshots); report.add("packetTrace", RpgConfig.JSON.toJsonTree(packets));
            report.add("nativeInputTrace", RpgConfig.JSON.toJsonTree(inputs)); report.add("serverEvents", RpgConfig.JSON.toJsonTree(serverEvents));
            report.add("lastServerState", serverState); report.add("selection", ShoulderInteraction.diagnostics()); report.add("renderCapture", movie.summary()); RpgSmoke.windowEnvironment(report);
            var jar = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); report.addProperty("modJar", jar.toString());
            report.addProperty("modJarSha256", Files.isRegularFile(jar) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))) : "loose-classes");
            RpgConfig.atomicWrite(root.resolve("shoulder-interaction-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("SHOULDER INTERACTION REVIEW COMPLETE passed=" + (error == null)); if (error != null) error.printStackTrace();
        } catch (Throwable reporting) { reporting.printStackTrace(); }
        if (channel != null) channel.eventLoop().execute(() -> { if (channel.pipeline().get(PACKET_PROBE) != null) channel.pipeline().remove(PACKET_PROBE); });
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) try {
            MOUSE_GRABBED.setBoolean(game.mouseHandler, oldMouseGrabbed); CombatCamera.reset(); game.options.setCameraType(oldCamera);
            game.options.framerateLimit().set(oldFps); game.options.renderDistance().set(oldDistance); game.options.guiScale().set(oldScale); game.options.fov().set(oldFov);
            game.options.pauseOnLostFocus = oldPause; game.options.enableVsync().set(oldVsync); game.options.hideGui = oldHidden; game.setWindowActive(oldActive);
        } catch (ReflectiveOperationException restore) { restore.printStackTrace(); }
        game.stop();
    }
}

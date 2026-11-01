package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientHooks;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.client.PresentationMotion;
import org.fsrpg.client.SwordMotion;
import org.fsrpg.combat.CombatPhase;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

/** Packaged-client review: real input, server lock selection, submitted avatar and framebuffer. */
final class RapierLockReview {
    private static final List<String> UPPER = List.of("body", "head", "right_arm", "right_forearm", "right_hand", "left_arm", "left_forearm", "left_hand");
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movies = new RenderFrameCapture(root);
    private final List<String> passed = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final JsonArray lockChecks = new JsonArray(), movementChecks = new JsonArray();
    private final ArrayDeque<String> shots = new ArrayDeque<>();
    private final List<JsonObject> movementFrames = new ArrayList<>();
    private volatile Throwable failure;
    private volatile boolean serverReady;
    private volatile LivingEntity first, second, passive;
    private volatile float targetHealth, passiveHealth;
    private boolean initialized, done, captureActive, finishing;
    private int warmup, tick, screenshotsPending;
    private int previousFps, previousDistance, previousScale, previousFov;
    private boolean previousPause, previousVsync, previousHidden, previousActive;
    private CameraType previousCamera;
    private float orbitYaw, actorYaw;
    private long started, sourceComparisons, recordedFrames, offhandBefore;
    private double maximumSourceDegrees, maximumFacingError;
    private float freeAttackYaw;
    private volatile Vec3 walkDirection, dodgeStart;
    private final JsonObject freeAttackCheck = new JsonObject(), dodgeCheck = new JsonObject();
    private String movementSegment = "stationary";

    RapierLockReview() {
        NeoForge.EVENT_BUS.addListener(RenderObservations::beginSubmittedFrame);
        NeoForge.EVENT_BUS.addListener(RenderObservations::captureSubmittedAvatar);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e) -> { if (!done) e.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key e) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
        passed.add(text); System.out.println("RAPIER LOCK PASS " + text);
    }

    private void server(Consumer<ServerPlayer> action) {
        var game = Minecraft.getInstance(); UUID uuid = game.player.getUUID();
        game.getSingleplayerServer().execute(() -> {
            try { action.accept(game.getSingleplayerServer().getPlayerList().getPlayer(uuid)); }
            catch (Throwable error) { failure = error; }
        });
    }

    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots"));
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        check("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()), "Window actually belongs to DISPLAY5");
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String named = System.getProperty("fsrpg.rapierLockWorld", "");
        check(named.startsWith("FSRPG Rapier Lock Review ") && named.equals(world.getFileName().toString())
                && Files.isRegularFile(world.resolve(".fsrpg-rapier-lock-test-world")), "Only marked, explicitly named copied world is modified");
        previousFps = game.options.framerateLimit().get(); previousDistance = game.options.renderDistance().get();
        previousScale = game.options.guiScale().get(); previousFov = game.options.fov().get();
        previousPause = game.options.pauseOnLostFocus; previousVsync = game.options.enableVsync().get();
        previousHidden = game.options.hideGui; previousCamera = game.options.getCameraType(); previousActive = game.isWindowActive();
        game.options.pauseOnLostFocus = false; game.options.framerateLimit().set(60); game.options.enableVsync().set(false);
        game.options.renderDistance().set(3); game.options.guiScale().set(2); game.options.fov().set(65);
        game.options.hideGui = false; game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        var bounds = environment.getAsJsonObject("monitorBounds"); long handle = game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle, 1000, 680);
        GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 35, bounds.get("y").getAsInt() + 45);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 西洋剑锁敌与原稿体态验收 · DISPLAY5");
        game.resizeDisplay(); game.getTutorial().setStep(TutorialSteps.NONE); KeyMapping.releaseAll();
        // This changes the game's input flag only; the OS window stays on DISPLAY5 and never requests focus.
        game.setWindowActive(true);
        for (var key : game.options.keyMappings) if (key.getName().equals("key.fsrpg.guard"))
            check(key.getDefaultKey().getValue() == GLFW.GLFW_KEY_C && key.getKey().getValue() == GLFW.GLFW_KEY_C,
                    "Default and loaded guard binding are C");
        initialized = true; started = System.nanoTime();
        server(p -> {
            for (var entity : p.level().getEntities(p, p.getBoundingBox().inflate(28), e -> e instanceof LivingEntity && !(e instanceof net.minecraft.world.entity.player.Player))) entity.discard();
            p.setGameMode(GameType.SURVIVAL); p.setHealth(20); p.getFoodData().setFoodLevel(20); p.setInvulnerable(true);
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 120, 0, false, false, false));
            p.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true); p.level().setDayTime(18000); p.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 18; z++) {
                p.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 80; y < 86; y++) p.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            p.connection.teleport(.5, 80, .5, 0, 0);
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) p.setItemSlot(slot, ItemStack.EMPTY);
            equip(p, "rapier");
            first = mob(p, EntityType.ZOMBIE, .5, 6.5); second = mob(p, EntityType.ZOMBIE, .5, 12.5); passive = mob(p, EntityType.COW, .5, 2.5);
            serverReady = true;
        });
    }

    private LivingEntity mob(ServerPlayer p, EntityType<?> type, double x, double z) {
        var entity = (LivingEntity) type.create(p.level(), EntitySpawnReason.COMMAND);
        if (entity == null) throw new AssertionError("Cannot create " + type);
        entity.setPos(x, 80, z); entity.setNoGravity(true);
        if (entity instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
        p.level().addFreshEntity(entity); return entity;
    }

    private void equip(ServerPlayer p, String style) {
        var actor = RpgSmoke.actor(p); actor.forceSheathed();
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(style.equals("spear") ? Items.DIAMOND_SPEAR : Items.DIAMOND_SWORD));
        p.setItemSlot(EquipmentSlot.OFFHAND, style.equals("dual_blade") ? new ItemStack(Items.IRON_SWORD) : ItemStack.EMPTY);
        RpgServer.instance().action(p, "weaponStyle", style); actor.setResources(100, 100);
        if (!actor.drawn()) RpgServer.instance().action(p, "draw", "");
        RpgServer.instance().action(p, "status", "");
    }

    private void lock(LivingEntity expected, String description) {
        LivingEntity actual = CombatCamera.lockedTarget();
        check(expected == null ? actual == null : actual != null && actual.getId() == expected.getId(), description);
        var row = new JsonObject(); row.addProperty("tick", tick); row.addProperty("expected", expected == null ? -1 : expected.getId());
        row.addProperty("actual", actual == null ? -1 : actual.getId()); row.addProperty("description", description);
        row.add("snapshot", ClientState.object(ClientState.snapshot(), "targetLock").deepCopy());
        if (actual != null) {
            var player = Minecraft.getInstance().player;
            double desired = Math.toDegrees(Math.atan2(-(actual.getX() - player.getX()), actual.getZ() - player.getZ()));
            double error = Math.abs(Mth.wrapDegrees(player.getYRot() - (float) desired));
            maximumFacingError = Math.max(maximumFacingError, error);
            check(error < 1.0, description + " faces target on actual local actor");
            row.addProperty("distanceMeters", player.distanceTo(actual)); row.addProperty("yawErrorDegrees", error);
        }
        lockChecks.add(row);
    }

    private void key(int code, boolean down, boolean click) {
        var key = InputConstants.Type.KEYSYM.getOrCreate(code); KeyMapping.set(key, down); if (click) KeyMapping.click(key);
    }

    private boolean orbit(double degrees) {
        var options = Minecraft.getInstance().options;
        double gain = Math.pow(options.sensitivity().get() * .6 + .2, 3) * 8;
        return CombatCamera.mouse(degrees / (.15 * gain) * (options.invertMouseX().get() ? -1 : 1), 0);
    }

    private void start(String name) throws Exception {
        movies.start(name, "actual DISPLAY5 framebuffer; real local player and authoritative integrated server", this::observation);
        captureActive = true;
    }

    private void stop() { if (captureActive) { movies.stop(); captureActive = false; } }

    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (done || game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) { if (warmup++ >= 60) initialize(game); return; }
            if (failure != null) throw new IllegalStateException("Async fixture/capture failure", failure);
            movies.assertHealthy(); game.getFramerateLimitTracker().onInputReceived();
            checkMonitor();
            if (System.nanoTime() - started > 140_000_000_000L) throw new AssertionError("Rapier lock review timeout");
            if (!serverReady) return;
            if (finishing) { if (screenshotsPending == 0 && movies.finish()) finish(null); return; }
            int t = ++tick;
            if (t == 22) lock(first, "Hostile at six blocks takes priority over passive at two blocks");
            if (t == 25) { actorYaw = game.player.getYRot(); orbitYaw = CombatCamera.yaw(); start("rapier-orbit-lock"); }
            if (t >= 26 && t <= 40) check(orbit(6), "Rapier consumes independent orbit mouse input " + t);
            if (t == 45) {
                lock(first, "Orbit camera preserves hostile lock");
                check(Math.abs(Mth.wrapDegrees(CombatCamera.yaw() - orbitYaw)) > 85, "Mouse input visibly rotates camera by a quarter turn");
                check(Math.abs(Mth.wrapDegrees(game.player.getYRot() - actorYaw)) < 1, "Camera orbit leaves actor target facing unchanged");
                shots.add("rapier-orbit-independent"); stop();
            }
            if (t == 50) server(p -> first.setPos(.5, 80, 11.51));
            if (t == 70) lock(passive, "Out-of-range hostile releases and passive fallback is acquired");
            if (t == 75) server(p -> first.setPos(.5, 80, 10.49));
            if (t == 95) lock(first, "Hostile at 9.99 blocks replaces passive fallback");
            if (t == 100) server(p -> first.setPos(.5, 80, 10.51));
            if (t == 120) lock(passive, "Hostile at 10.01 blocks is excluded");
            if (t == 125) server(p -> { first.setPos(.5, 80, 3.5); second.setPos(.5, 80, 7.5); });
            if (t == 145) lock(first, "Nearest hostile is acquired within range");
            if (t == 150) server(p -> first.hurtServer(p.level(), p.damageSources().playerAttack(p), 1000));
            if (t == 170) lock(second, "Dead target switches to remaining hostile");
            if (t == 175) server(p -> { second.setPos(.5, 80, 12.5); passive.setPos(.5, 80, -12.5); });
            if (t == 195) lock(null, "All targets outside ten blocks clears lock");
            if (t == 196) {
                freeAttackYaw = CombatCamera.yaw();
                freeAttackCheck.addProperty("cameraYawBeforeInput", freeAttackYaw);
                freeAttackCheck.addProperty("localActorYawBeforeInput", game.player.getYRot());
                check(Math.abs(Mth.wrapDegrees(freeAttackYaw - game.player.getYRot())) > 85, "Unlocked immediate attack starts with camera and actor a quarter turn apart");
                CombatCamera.attack("left");
            }
            if (t == 198) server(p -> {
                var aim = RpgSmoke.actor(p).attackAim();
                check(aim != null, "Unlocked immediate click starts an authoritative attack");
                double error = Math.abs(Mth.wrapDegrees((float) aim.sample(0).yaw() - freeAttackYaw));
                check(error < .1, "Unlocked immediate click commits current camera yaw before server attack handling");
                freeAttackCheck.addProperty("authoritativeAttackYaw", aim.sample(0).yaw()); freeAttackCheck.addProperty("yawErrorDegrees", error);
            });
            if (t == 200) server(p -> {
                first = mob(p, EntityType.ZOMBIE, .5, 2.9); passive.setPos(.5, 80, -1.9);
                targetHealth = first.getHealth(); passiveHealth = passive.getHealth(); RpgSmoke.actor(p).setResources(100, 100);
            });
            if (t == 222) lock(first, "Attack fixture locks front hostile while camera is off-axis");
            if (t == 225) { start("rapier-locked-attack"); CombatCamera.attack("left"); }
            if (t == 247) server(p -> {
                check(first.getHealth() < targetHealth, "Server-authoritative left attack hits locked hostile despite camera orbit");
                check(passive.getHealth() == passiveHealth, "Locked attack does not damage opposite passive target");
            });
            if (t == 252) { shots.add("rapier-locked-attack"); stop(); }
            if (t >= 260 && t < 275) key(GLFW.GLFW_KEY_C, true, false);
            if (t == 266) {
                check(ClientState.text(ClientState.snapshot(), "phase", "").equals("GUARDING"), "Held C enters authoritative guard while drawn");
                server(p -> check(RpgSmoke.actor(p).phase() == CombatPhase.GUARDING && !RpgSmoke.actor(p).heavyGuard(), "C alone uses server light guard")); shots.add("rapier-c-guard");
            }
            if (t == 267) game.options.keyShift.setDown(true);
            if (t == 272) server(p -> check(RpgSmoke.actor(p).heavyGuard(), "Shift plus C uses server heavy guard"));
            if (t == 275) { key(GLFW.GLFW_KEY_C, false, false); game.options.keyShift.setDown(false); }
            if (t == 280) key(GLFW.GLFW_KEY_E, true, true);
            if (t == 290) {
                check(game.screen instanceof InventoryScreen, "E opens vanilla inventory while rapier is drawn");
                key(GLFW.GLFW_KEY_E, false, false); game.setScreen(null);
            }
            if (t == 295) server(p -> {
                p.connection.teleport(.5, 80, .5, 0, 0); first.setPos(.5, 80, 8.5); second.setPos(.5, 80, 12.5); passive.setPos(.5, 80, -12.5);
                RpgSmoke.actor(p).setResources(100, 100);
            });
            if (t == 307) {
                // Return the independent camera to target-forward through its public mouse path.
                orbit(-Mth.wrapDegrees(CombatCamera.yaw()));
                start("rapier-original-upper-moving-lower");
            }
            if (t >= 310 && t < 410) {
                int lane = (t - 310) / 25; movementSegment = List.of("forward", "backward", "left", "right").get(lane);
                game.options.keyUp.setDown(lane == 0); game.options.keyDown.setDown(lane == 1);
                game.options.keyLeft.setDown(lane == 2); game.options.keyRight.setDown(lane == 3);
                if ((t - 310) % 25 == 18) shots.add("rapier-moving-" + movementSegment);
            }
            if (t == 410) { KeyMapping.releaseAll(); movementSegment = "stop"; }
            if (t == 420) server(p -> {
                p.connection.teleport(.5, 80, .5, 0, 0); p.setDeltaMovement(Vec3.ZERO); RpgSmoke.actor(p).setResources(100, 100);
            });
            if (t == 430) { lock(first, "Orbit dodge fixture retains hostile lock"); orbit(90); movementSegment = "orbit-walk"; }
            if (t >= 430 && t <= 440) game.options.keyUp.setDown(true);
            if (t == 438) {
                var moved = new Vec3(game.player.getX() - game.player.xo, 0, game.player.getZ() - game.player.zo);
                check(moved.lengthSqr() > .0025, "Actual W input produces motion with orbit camera at ninety degrees");
                walkDirection = moved.normalize();
                var cameraDirection = Vec3.directionFromRotation(0, CombatCamera.yaw());
                check(walkDirection.dot(cameraDirection) > .99, "Locked W movement follows independent camera forward");
                dodgeCheck.add("actualWalkDirection", RpgConfig.JSON.toJsonTree(new double[]{walkDirection.x, walkDirection.y, walkDirection.z}));
                dodgeCheck.addProperty("cameraYaw", CombatCamera.yaw()); dodgeCheck.addProperty("actorYaw", game.player.getYRot());
            }
            if (t == 439) server(p -> dodgeStart = p.position());
            if (t == 440) key(GLFW.GLFW_KEY_LEFT_ALT, true, true);
            if (t == 441) { game.options.keyUp.setDown(false); key(GLFW.GLFW_KEY_LEFT_ALT, false, false); movementSegment = "orbit-dodge"; }
            if (t == 443) server(p -> {
                try {
                    @SuppressWarnings("unchecked") var directions = (Map<UUID, Vec3>) field(RpgServer.class, RpgServer.instance(), "dodgeDirections");
                    var direction = directions.get(p.getUUID());
                    check(RpgSmoke.actor(p).phase() == CombatPhase.DODGING && direction != null, "Real W plus Alt dispatch enters authoritative dodge");
                    double dot = direction.dot(walkDirection);
                    check(dot > .99, "Locked dodge direction matches actual independent-camera walking direction");
                    dodgeCheck.add("serverDodgeDirection", RpgConfig.JSON.toJsonTree(new double[]{direction.x, direction.y, direction.z})); dodgeCheck.addProperty("walkDodgeDirectionDot", dot);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect authoritative dodge direction", error); }
            });
            if (t == 450) server(p -> {
                var moved = p.position().subtract(dodgeStart).multiply(1, 0, 1); double dot = moved.normalize().dot(walkDirection);
                check(moved.length() > 1 && dot > .95, "Actual server dodge displacement continues the walking direction");
                dodgeCheck.addProperty("actualServerTravelMeters", moved.length()); dodgeCheck.addProperty("actualServerTravelDirectionDot", dot);
            });
            if (t == 452) shots.add("rapier-orbit-w-dodge");
            if (t == 455) {
                movementSegment = "stop";
                server(p -> { p.connection.teleport(.5, 80, .5, 0, 0); p.setDeltaMovement(Vec3.ZERO); });
            }
            if (t == 465) orbit(-Mth.wrapDegrees(CombatCamera.yaw()));
            if (t == 485) { stop(); verifyMovement(); shots.add("rapier-original-upper-settled"); }
            if (t == 495) server(p -> { equip(p, "spear"); first.setPos(.5, 80, 12.5); });
            if (t == 517) { check(CombatCamera.style().equals("spear"), "Original spear style still activates"); check(CombatCamera.lockedTarget() == null, "Rapier target lock is absent in spear style"); start("spear-regression"); }
            if (t >= 520 && t < 552) game.options.keyRight.setDown(true);
            if (t == 530) CombatCamera.attack("left");
            if (t == 555) { game.options.keyRight.setDown(false); shots.add("spear-regression"); stop(); }
            if (t == 565) server(p -> equip(p, "dual_blade"));
            if (t == 587) { check(CombatCamera.style().equals("dual_blade"), "Original dual blade style still activates"); check(CombatCamera.lockedTarget() == null, "Rapier target lock is absent in dual blade style"); offhandBefore = OfficialHeldItems.offhandAttachedSubmissions(); start("dual-regression"); }
            if (t >= 590 && t < 622) game.options.keyLeft.setDown(true);
            if (t == 600) CombatCamera.attack("left");
            if (t == 625) {
                game.options.keyLeft.setDown(false); shots.add("dual-regression"); stop();
                check(OfficialHeldItems.offhandAttachedSubmissions() > offhandBefore, "Dual blade retains official offhand ItemStack attachment");
                finishing = true;
            }
        } catch (Throwable error) { stop(); finish(error); }
    }

    private void checkMonitor() {
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        if (!"\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString())) throw new AssertionError("Window escaped DISPLAY5: " + environment);
    }

    private JsonObject observation() {
        var game = Minecraft.getInstance();
        JsonObject sample = RenderObservations.submittedSampler(game.player).get();
        sample.addProperty("reviewTick", tick); sample.addProperty("movementSegment", movementSegment);
        sample.addProperty("cameraYaw", CombatCamera.yaw()); sample.addProperty("actorYaw", game.player.getYRot());
        sample.addProperty("lockYaw", CombatCamera.lockYaw());
        var target = CombatCamera.lockedTarget(); sample.addProperty("targetId", target == null ? -1 : target.getId());
        sample.add("authoritativeTargetLock", ClientState.object(ClientState.snapshot(), "targetLock").deepCopy());
        if (tick >= 312 && tick < 410) {
            verifySource(sample); movementFrames.add(sample.deepCopy());
        }
        recordedFrames++; return sample;
    }

    private void verifySource(JsonObject sample) {
        if (!sample.has("renderAnimation")) throw new AssertionError("No real rapier animation binding");
        var animation = sample.getAsJsonObject("renderAnimation");
        if (!animation.get("phase").getAsString().equals("READY")) return;
        try {
            @SuppressWarnings("unchecked") var bindings = (Map<UUID, Object>) field(PlayerAnimations.class, null, "bindings");
            var actor = Minecraft.getInstance().player; Object binding = bindings.get(actor.getUUID());
            var frame = (PlayerAnimations.Frame) field(binding.getClass(), binding, "sampled");
            double transition = ((Number) field(binding.getClass(), binding, "transitionStarted")).doubleValue();
            double duration = ((Number) field(binding.getClass(), binding, "transitionTicks")).doubleValue();
            if (frame.sampledTick() < transition + duration + .01) return;
            var payload = (RpgAnimationPayload) field(binding.getClass(), binding, "state");
            var motion = (PresentationMotion) field(binding.getClass(), binding, "motion");
            var breathConfig = (ModConfigSpec.DoubleValue) field(Class.forName("org.fsrpg.client.ClientOptions"), null, "BREATH_SPEED");
            double seconds = ((Number) field(binding.getClass(), binding, "sampledClipSeconds")).doubleValue();
            var source = SwordMotion.sampleUpper(frame.model(), frame.model().rig().clips().get(payload.action()), seconds,
                    motion.breathingSeconds(frame.sampledTick(), breathConfig.get()), actor.getXRot(), sample.get("aimDeltaYaw").getAsFloat());
            var sourceWorld = frame.model().worldMatrices(source); double maximum = 0;
            for (String bone : UPPER) {
                if (!sourceWorld.containsKey(bone) || !frame.world().containsKey(bone)) throw new AssertionError("Missing source/rendered upper bone " + bone);
                Quaternionf expected = sourceWorld.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize();
                Quaternionf actual = frame.world().get(bone).getUnnormalizedRotation(new Quaternionf()).normalize();
                double difference = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(expected.dot(actual)), 0, 1)));
                maximum = Math.max(maximum, difference);
                if (difference > .15) throw new AssertionError("Moving upper deviates from original source: " + bone + " = " + difference + " degrees");
            }
            maximumSourceDegrees = Math.max(maximumSourceDegrees, maximum); sourceComparisons++;
            sample.addProperty("originalUpperMaximumRotationErrorDegrees", maximum);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect original upper source", error); }
    }

    private Object field(Class<?> owner, Object instance, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }

    private void verifyMovement() {
        for (String segment : List.of("forward", "backward", "left", "right")) {
            var frames = movementFrames.stream().filter(row -> row.get("movementSegment").getAsString().equals(segment)).toList();
            check(frames.size() >= 35, "Real rendered frames captured for movement " + segment);
            double travelled = 0, gaitTravel = 0, previousGait = Double.NaN, minKnee = Double.POSITIVE_INFINITY, maxKnee = Double.NEGATIVE_INFINITY;
            int previousTick = -1, contacts = 0; double maxSpeed = 0, minDirectionDot = 1;
            for (var row : frames) {
                int currentTick = row.get("actorTickCount").getAsInt();
                var delta = row.getAsJsonArray("movementXZ"); double dx = delta.get(0).getAsDouble(), dz = delta.get(1).getAsDouble();
                if (currentTick != previousTick) { travelled += Math.hypot(dx, dz); previousTick = currentTick; }
                maxSpeed = Math.max(maxSpeed, row.get("movingWeight").getAsDouble());
                var lower = row.getAsJsonObject("finalLowerBody");
                if (lower == null) throw new AssertionError("No supported lower body in real movement " + segment);
                if (!RenderObservations.stableLowerSupport(row)) throw new AssertionError("Rendered soles penetrate ground or pelvis/hip support disconnects " + segment);
                double phase = lower.get("phaseRadians").getAsDouble();
                if (Double.isFinite(previousGait)) gaitTravel += Math.abs(Math.atan2(Math.sin(phase - previousGait), Math.cos(phase - previousGait))); previousGait = phase;
                for (String side : List.of("left", "right")) {
                    double knee = row.get(side + "Knee").getAsDouble(); minKnee = Math.min(minKnee, knee); maxKnee = Math.max(maxKnee, knee);
                    if (lower.getAsJsonObject(side).get("support").getAsDouble() > .5) contacts++;
                }
                if ((row.get("reviewTick").getAsInt() - 310) % 25 >= 12 && Math.hypot(dx, dz) > .02 && row.get("movingWeight").getAsDouble() > .5) {
                    double yaw = Math.toRadians(row.get("bodyYaw").getAsDouble());
                    double side = dx * Math.cos(yaw) + dz * Math.sin(yaw), forward = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
                    var direction = lower.getAsJsonArray("directionXZ");
                    double lx = direction.get(0).getAsDouble(), lz = direction.get(1).getAsDouble();
                    double divisor = Math.hypot(side, forward) * Math.hypot(lx, lz);
                    if (divisor > .001) minDirectionDot = Math.min(minDirectionDot, (side * lx + forward * lz) / divisor);
                }
            }
            check(travelled > .8 && maxSpeed > .3, "Real WASD moves player and drives lower body " + segment);
            check(gaitTravel > .6 && maxKnee - minKnee > .02 && contacts > frames.size() / 2, "Gait, knee and supporting foot remain live " + segment);
            check(minDirectionDot > .85, "Supported lower-body direction follows actual movement " + segment);
            var summary = new JsonObject(); summary.addProperty("segment", segment); summary.addProperty("frames", frames.size()); summary.addProperty("travelMeters", travelled);
            summary.addProperty("gaitTravelRadians", gaitTravel); summary.addProperty("kneeRangeRadians", maxKnee - minKnee); summary.addProperty("minimumDirectionDot", minDirectionDot); movementChecks.add(summary);
        }
        check(sourceComparisons > 150 && maximumSourceDegrees <= .15, "Moving upper rotations retain original source in every stable rendered frame");
        check(OfficialHeldItems.submissions() > 0, "Rapier remains actual official ItemStack at authored wrist");
    }

    private void render(RenderFrameEvent.Post event) {
        if (!initialized || done) return;
        try {
            movies.frame(event);
            while (!shots.isEmpty()) {
                String file = shots.removeFirst(); screenshotsPending++;
                Screenshot.grab(root.toFile(), file + ".png", Minecraft.getInstance().getMainRenderTarget(), 1, text -> screenshotsPending--);
            }
        } catch (Throwable error) { failure = error; }
    }

    private void finish(Throwable error) {
        if (done) return; done = true; stop();
        try {
            if (error == null) {
                var clips = movies.summary().getAsJsonArray("clips"); check(clips.size() == 5, "All five real framebuffer review segments completed");
                for (var value : clips) {
                    var clip = value.getAsJsonObject(); String id = clip.get("id").getAsString();
                    check(clip.get("sourceFps").getAsDouble() >= 30, "Actual source capture is at least 30 FPS: " + id);
                    check(clip.get("distinctSamplesWithinGameTick").getAsInt() > 0, "Capture samples actual render partial ticks: " + id);
                    if (!id.equals("rapier-orbit-lock")) check(clip.get("changedRigPairs").getAsInt() > 0, "Animation matrices advance in captured render frames: " + id);
                }
            }
        } catch (Throwable validation) { error = validation; }
        try {
            var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("error", error == null ? null : error.toString());
            report.add("checks", RpgConfig.JSON.toJsonTree(passed)); report.add("targetSelectionChecks", lockChecks); report.add("movementChecks", movementChecks);
            report.add("unlockedImmediateAttack", freeAttackCheck); report.add("orbitCameraWalkAndDodge", dodgeCheck);
            report.addProperty("originalUpperComparisons", sourceComparisons); report.addProperty("maximumOriginalUpperRotationErrorDegrees", maximumSourceDegrees);
            report.addProperty("maximumFacingErrorDegrees", maximumFacingError); report.addProperty("recordedFrames", recordedFrames); report.addProperty("completedTick", tick);
            report.addProperty("copiedWorld", System.getProperty("fsrpg.rapierLockWorld")); RpgSmoke.windowEnvironment(report);
            var source = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            report.addProperty("modJar", source.toString()); report.addProperty("modJarSha256", Files.isRegularFile(source) ? HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))) : "loose-classes");
            RpgConfig.atomicWrite(root.resolve("rapier-lock-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("RAPIER LOCK REVIEW COMPLETE passed=" + (error == null) + " " + root); if (error != null) error.printStackTrace();
        } catch (Throwable reportError) { reportError.printStackTrace(); }
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) {
            game.options.framerateLimit().set(previousFps); game.options.renderDistance().set(previousDistance); game.options.guiScale().set(previousScale); game.options.fov().set(previousFov);
            game.options.pauseOnLostFocus = previousPause; game.options.enableVsync().set(previousVsync); game.options.hideGui = previousHidden; game.options.setCameraType(previousCamera); game.setWindowActive(previousActive);
        }
        game.stop();
    }
}

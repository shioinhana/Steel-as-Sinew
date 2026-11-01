package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.HudLayout;
import org.fsrpg.client.RpgHud;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** Development-only HUD rendering review. All fixture values originate on the integrated server. */
final class HudReview {
    private record Scene(String name, GameType mode, int scale, double maxHealth, double health,
                         int xpLevel, double xpFraction, double staminaFraction, double spiritFraction,
                         boolean offhand, boolean armorAndAir, boolean hideGui, boolean expectedCompact) {}
    private static final List<Scene> SCENES = List.of(
            new Scene("00-survival-full-wide", GameType.SURVIVAL, 1, 20, 20, 0, 0, 1, 1, false, false, false, false),
            new Scene("01-survival-hurt-experience", GameType.SURVIVAL, 2, 101, 84, 4, .37, 1, 1, false, false, false, false),
            new Scene("02-resources-partial", GameType.SURVIVAL, 2, 101, 84, 4, .37, .64, .64, false, false, false, false),
            new Scene("03-resources-empty", GameType.SURVIVAL, 2, 101, 84, 4, .37, 0, 0, false, false, false, false),
            new Scene("04-narrow-scale3-offhand-armor-air", GameType.SURVIVAL, 3, 20, 13, 12, .8, .27, .46, true, true, false, true),
            new Scene("05-narrow-scale4-offhand", GameType.SURVIVAL, 4, 20, 13, 12, .8, .27, .46, true, true, false, true),
            new Scene("06-creative", GameType.CREATIVE, 2, 20, 20, 7, .5, .71, .38, false, false, false, false),
            new Scene("07-hidden-gui", GameType.SURVIVAL, 2, 20, 13, 7, .5, .71, .38, false, false, true, false),
            new Scene("08-spectator", GameType.SPECTATOR, 2, 20, 20, 7, .5, .71, .38, false, false, false, false));
    private static final String REQUIRED_MONITOR = "\\\\.\\DISPLAY5";
    private static final String SAFE_WORLD_PREFIX = "FSRPG HUD Review ";
    private static final Path ROOT = Path.of(System.getProperty("fsrpg.smokeEvidence", "../../verification/hud-20261011/game")).toAbsolutePath().normalize();
    private final Path directory = ROOT.resolve("run-" + Instant.now().toString().replace(':', '-'));
    private final JsonArray images = new JsonArray();
    private final List<String> checks = new ArrayList<>();
    private final Map<String, Integer> layerPosts = new TreeMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile Scene heldScene;
    private volatile JsonObject serverValues;
    private volatile Throwable failure;
    private volatile boolean serverReady;
    private JsonObject image;
    private HudLayout lastLayout;
    private UUID playerId;
    private Path worldPath;
    private long startedNanos;
    private int scene = -1, sceneTicks, warmupTicks, resourceLayerFrames;
    private boolean captureRequested, captured, initialized, done;
    private int previousScale, previousFps, previousDistance;
    private boolean previousHidden, previousPause, previousVsync;
    private CameraType previousCamera;
    private AttackIndicatorStatus previousAttackIndicator;

    HudReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::layer);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeCameraAngles event) -> {
            if (!done && initialized) { event.setYaw(0); event.setPitch(9); event.setRoll(0); }
        });
    }

    private void initialize(Minecraft game) throws Exception {
        startedNanos = System.nanoTime();
        Files.createDirectories(directory.resolve("screenshots"));
        monitorCheck();
        String configuredWorld = System.getProperty("fsrpg.hudWorld", "");
        worldPath = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(configuredWorld.startsWith(SAFE_WORLD_PREFIX) && worldPath.getFileName().toString().equals(configuredWorld),
                "HUD fixture only opens a specifically named copied test world");
        check(Files.isRegularFile(worldPath.resolve(".fsrpg-hud-test-world")), "Copied HUD test world carries its test-only marker");
        previousScale = game.options.guiScale().get(); previousHidden = game.options.hideGui;
        previousPause = game.options.pauseOnLostFocus; previousCamera = game.options.getCameraType();
        previousAttackIndicator = game.options.attackIndicator().get();
        previousFps = game.options.framerateLimit().get(); previousVsync = game.options.enableVsync().get();
        previousDistance = game.options.renderDistance().get();
        game.options.pauseOnLostFocus = false; game.options.hideGui = false;
        game.options.setCameraType(CameraType.FIRST_PERSON); game.options.framerateLimit().set(60);
        game.options.enableVsync().set(false); game.options.renderDistance().set(4);
        game.getTutorial().setStep(TutorialSteps.NONE); KeyMapping.releaseAll();
        GLFW.glfwSetWindowTitle(game.getWindow().handle(), "FSRPG 四条 HUD 实机验证 · DISPLAY5 · 完成后自动关闭");
        playerId = game.player.getUUID(); initialized = true;
        server(player -> {
            player.setGameMode(GameType.CREATIVE);
            player.level().setDayTime(6000); player.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++)
                player.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = 80; y <= 84; y++)
                player.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            player.connection.teleport(.5, 80, .5, 0, 9);
            player.getInventory().clearContent();
            List<net.minecraft.world.item.Item> items = List.of(Items.TORCH, Items.DIAMOND, Items.IRON_PICKAXE,
                    Items.STONE_AXE, Items.STONE_SHOVEL, Items.ARROW, Items.COBBLESTONE, Items.STONE_HOE, Items.BREAD);
            for (int i = 0; i < items.size(); i++) player.getInventory().setItem(i, new ItemStack(items.get(i), i == 6 ? 2 : i == 8 ? 11 : 1));
            player.getInventory().setSelectedSlot(2);
            player.connection.send(new ClientboundSetHeldSlotPacket(2));
            serverReady = true;
        });
    }

    private void server(Consumer<ServerPlayer> task) {
        var game = Minecraft.getInstance(); var integrated = game.getSingleplayerServer();
        integrated.execute(() -> {
            try {
                var player = integrated.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("Test player left the integrated server");
                task.accept(player);
            } catch (Throwable error) { failure = error; }
        });
    }

    private void configure(Minecraft game, int index) {
        scene = index; sceneTicks = 0; captured = false; resourceLayerFrames = 0; lastLayout = null; layerPosts.clear();
        var planned = SCENES.get(index); heldScene = null; serverValues = null;
        viewport(game, planned.scale == 3 ? 1200 : 1280, planned.scale == 4 ? 960 : 720);
        game.options.guiScale().set(planned.scale); game.options.hideGui = planned.hideGui;
        game.options.attackIndicator().set(planned.offhand ? AttackIndicatorStatus.HOTBAR : AttackIndicatorStatus.CROSSHAIR);
        game.options.setCameraType(CameraType.FIRST_PERSON); game.resizeDisplay();
        image = new JsonObject(); image.addProperty("name", planned.name);
        image.addProperty("file", "screenshots/" + planned.name + ".png");
        image.add("requestedFixture", RpgConfig.JSON.toJsonTree(planned));
        server(player -> {
            player.setGameMode(planned.mode);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(planned.maxHealth);
            player.setHealth((float) planned.health);
            player.setExperienceLevels(planned.xpLevel);
            player.setExperiencePoints((int) Math.round(player.getXpNeededForNextLevel() * planned.xpFraction));
            player.getFoodData().setFoodLevel(planned.armorAndAir ? 12 : 8);
            player.getFoodData().setSaturation(0);
            player.setItemSlot(EquipmentSlot.OFFHAND, planned.offhand ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.CHEST, planned.armorAndAir ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY); player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            player.setAirSupply(planned.armorAndAir ? 80 : player.getMaxAirSupply());
            heldScene = planned; maintain(player, planned);
        });
    }

    private void viewport(Minecraft game, int width, int height) {
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        var bounds = environment.getAsJsonObject("monitorBounds");
        if (!REQUIRED_MONITOR.equals(environment.get("monitorAdapter").getAsString())
                || bounds.get("width").getAsInt() < width + 40 || bounds.get("height").getAsInt() < height + 60)
            throw new AssertionError("DISPLAY5 cannot contain the HUD fixture viewport: " + environment);
        long window = game.getWindow().handle();
        GLFW.glfwSetWindowSize(window, width, height);
        GLFW.glfwSetWindowPos(window, bounds.get("x").getAsInt() + (bounds.get("width").getAsInt() - width) / 2,
                bounds.get("y").getAsInt() + (bounds.get("height").getAsInt() - height) / 2);
        // Keep the entire resized development window on the already verified DISPLAY5.
        monitorCheck();
    }

    /** Freeze only the server fixture values while the rendering review takes each screenshot. */
    private void maintain(ServerPlayer player, Scene planned) {
        var actor = RpgSmoke.actor(player); var before = actor.snapshot();
        actor.setResources(before.maxStamina() * planned.staminaFraction, before.maxSpirit() * planned.spiritFraction);
        if (planned.armorAndAir) player.setAirSupply(80);
        var authoritative = actor.snapshot(); var values = new JsonObject();
        values.addProperty("health", player.getHealth()); values.addProperty("maxHealth", player.getMaxHealth());
        values.addProperty("experienceLevel", player.experienceLevel); values.addProperty("experienceProgress", player.experienceProgress);
        values.addProperty("stamina", authoritative.stamina()); values.addProperty("maxStamina", authoritative.maxStamina());
        values.addProperty("spirit", authoritative.spirit()); values.addProperty("maxSpirit", authoritative.maxSpirit());
        values.addProperty("serverTick", authoritative.serverTick()); values.addProperty("drawn", authoritative.drawn());
        values.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());
        serverValues = values;
        // This is the normal server packet path, never a replacement of ClientState presentation values.
        RpgServer.instance().action(player, "status", "");
    }

    private void serverTick(ServerTickEvent.Post event) {
        Scene planned = heldScene;
        if (done || planned == null || playerId == null) return;
        try {
            var player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player != null) maintain(player, planned);
        } catch (Throwable error) { failure = error; }
    }

    private void layer(RenderGuiLayerEvent.Post event) {
        if (done || scene < 0) return;
        layerPosts.merge(event.getName().toString(), 1, Integer::sum);
        if (event.getName().toString().equals("fsrpg:resource_bars")) {
            resourceLayerFrames++; lastLayout = RpgHud.visible() ? RpgHud.lastLayout() : null;
        }
    }

    private boolean valuesArrived(Minecraft game) {
        var values = serverValues;
        if (values == null || heldScene != SCENES.get(scene)) return false;
        if (Math.abs(game.player.getHealth() - values.get("health").getAsDouble()) > .01
                || Math.abs(game.player.getMaxHealth() - values.get("maxHealth").getAsDouble()) > .01
                || game.player.experienceLevel != values.get("experienceLevel").getAsInt()) return false;
        for (String key : List.of("stamina", "maxStamina", "spirit", "maxSpirit"))
            if (Math.abs(ClientState.number(ClientState.snapshot(), key, -1) - values.get(key).getAsDouble()) > .01) return false;
        return game.gameMode.getPlayerMode() == SCENES.get(scene).mode;
    }

    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) initialize(game);
            if (failure != null) throw new RuntimeException("HUD smoke fixture or capture failed", failure);
            if ((System.nanoTime() - startedNanos) / 1e9 > 120) throw new AssertionError("HUD review exceeded 120 seconds after world readiness");
            if (game.isPaused()) game.setScreen(null);
            game.getFramerateLimitTracker().onInputReceived(); KeyMapping.releaseAll();
            if (!serverReady || warmupTicks++ < 80) return;
            if (scene < 0) { configure(game, 0); return; }
            if (captured) {
                if (pending.get() != 0) return;
                if (scene + 1 == SCENES.size()) { finish(null); return; }
                configure(game, scene + 1); return;
            }
            sceneTicks++;
            if (sceneTicks < 20 || !valuesArrived(game) || resourceLayerFrames < 2) return;
            if (!captureRequested) {
                verifyScene(game); captureRequested = true;
            }
        } catch (Throwable error) { finish(error); }
    }

    private void verifyScene(Minecraft game) {
        var planned = SCENES.get(scene); boolean expectedVisible = !planned.hideGui && planned.mode != GameType.SPECTATOR;
        check(RpgHud.visible() == expectedVisible, planned.name + ": actual HUD visibility follows mode/F1");
        check(valuesArrived(game), planned.name + ": server values reached actual player and RPG network snapshot");
        check(game.getWindow().getGuiScale() == planned.scale, planned.name + ": requested GUI scale reached actual framebuffer layout");
        if (expectedVisible) {
            check(lastLayout != null && lastLayout.compact() == planned.expectedCompact, planned.name + ": wide/compact layout selected from actual GUI dimensions");
            check(layerPosts.getOrDefault("minecraft:hotbar", 0) > 0, planned.name + ": vanilla hotbar rendering retained");
            check(layerPosts.getOrDefault("minecraft:player_health", 0) == 0, planned.name + ": vanilla heart layer replaced");
            check(layerPosts.getOrDefault("minecraft:experience_level", 0) == 0, planned.name + ": vanilla XP text replaced");
        }
        monitorCheck();
    }

    private void render(RenderFrameEvent.Post event) {
        if (done || !captureRequested) return;
        try {
            captureRequested = false; captured = true; var game = Minecraft.getInstance(); var record = image;
            record.addProperty("capturedAtUtc", Instant.now().toString()); record.addProperty("guiWidth", game.getWindow().getGuiScaledWidth());
            record.addProperty("guiHeight", game.getWindow().getGuiScaledHeight()); record.addProperty("guiScale", game.getWindow().getGuiScale());
            record.addProperty("hudVisible", RpgHud.visible()); record.addProperty("resourceLayerPostEvents", resourceLayerFrames);
            record.addProperty("attackIndicator", game.options.attackIndicator().get().name());
            record.add("layout", RpgConfig.JSON.toJsonTree(lastLayout)); record.add("actualLayerPostEvents", RpgConfig.JSON.toJsonTree(layerPosts));
            record.add("authoritativeServerValues", serverValues.deepCopy());
            var received = new JsonObject();
            for (String key : List.of("stamina", "maxStamina", "spirit", "maxSpirit", "serverTick"))
                received.addProperty(key, ClientState.number(ClientState.snapshot(), key, -1));
            received.addProperty("health", game.player.getHealth()); received.addProperty("maxHealth", game.player.getMaxHealth());
            received.addProperty("experienceLevel", game.player.experienceLevel); received.addProperty("experienceProgress", game.player.experienceProgress);
            received.addProperty("drawn", ClientState.drawn()); record.add("receivedClientValues", received);
            var window = new JsonObject(); RpgSmoke.windowEnvironment(window); record.add("window", window); images.add(record);
            pending.incrementAndGet();
            Screenshot.grab(directory.toFile(), record.get("name").getAsString() + ".png", game.getMainRenderTarget(), 1, message -> {
                try {
                    Path file = directory.resolve(record.get("file").getAsString());
                    if (!Files.isRegularFile(file) || Files.size(file) < 1024) throw new IllegalStateException("HUD screenshot failed: " + message.getString());
                    record.addProperty("sha256", sha(file));
                } catch (Throwable error) { failure = error; }
                finally { pending.decrementAndGet(); }
            });
        } catch (Throwable error) { finish(error); }
    }

    private void monitorCheck() {
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        if (!REQUIRED_MONITOR.equals(environment.get("monitorAdapter").getAsString()))
            throw new AssertionError("HUD review requires window center on DISPLAY5: " + environment);
    }
    private void check(boolean value, String text) {
        if (!value) throw new AssertionError(text); checks.add(text); System.out.println("HUD PASS " + text);
    }
    private static String sha(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private void finish(Throwable error) {
        if (done) return; done = true; heldScene = null;
        try {
            var report = new JsonObject(); report.addProperty("schemaVersion", 1);
            report.addProperty("capturePassed", error == null && images.size() == SCENES.size() && pending.get() == 0);
            report.addProperty("scope", "Actual Minecraft HUD rendering and server-to-client values; motion work is excluded");
            report.addProperty("fixtureSource", "Integrated-server player attributes/XP/inventory and CombatActor resources; normal RPG snapshot packets");
            report.addProperty("unavailableSnapshotCoverage", "HUD placeholder helper is verified by separate logic tests; client snapshots are never cleared or fabricated here");
            report.addProperty("error", error == null ? null : error.toString()); report.addProperty("expectedImages", SCENES.size());
            report.addProperty("world", String.valueOf(worldPath)); report.addProperty("directory", directory.toString());
            report.addProperty("secondsAfterWorldReady", (System.nanoTime() - startedNanos) / 1e9);
            report.add("checksPassed", RpgConfig.JSON.toJsonTree(checks)); report.add("views", images); RpgSmoke.windowEnvironment(report);
            Path source = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            report.addProperty("jarSha256", Files.isRegularFile(source) ? sha(source) : "development-classes");
            RpgConfig.atomicWrite(directory.resolve("hud-review.json"), RpgConfig.JSON.toJson(report));
            RpgConfig.atomicWrite(ROOT.resolve("latest.json"), RpgConfig.JSON.toJson(report));
            System.out.println("HUD REVIEW COMPLETE " + directory + " capturePassed=" + report.get("capturePassed"));
        } catch (Throwable reportError) { reportError.printStackTrace(); }
        if (error != null) error.printStackTrace();
        var game = Minecraft.getInstance();
        if (initialized) {
            game.options.guiScale().set(previousScale); game.options.hideGui = previousHidden;
            game.options.pauseOnLostFocus = previousPause; game.options.setCameraType(previousCamera);
            game.options.attackIndicator().set(previousAttackIndicator);
            game.options.framerateLimit().set(previousFps); game.options.enableVsync().set(previousVsync);
            game.options.renderDistance().set(previousDistance); game.resizeDisplay();
        }
        game.stop();
    }
}

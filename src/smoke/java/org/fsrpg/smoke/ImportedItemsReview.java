package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.ImportedItems;
import org.fsrpg.RpgServer;
import org.fsrpg.art.ArtCatalog;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** Real client acceptance for imported static items; never included in the released mod. */
final class ImportedItemsReview {
    private static final List<String> EXPECTED = List.of("money_pouch", "gold_coin", "silver_coin", "crystal_coin", "nether_coin",
            "gold_adventurer_badge", "cobalt_adventurer_badge", "copper_adventurer_badge", "silver_adventurer_badge", "white_adventurer_badge");
    private static final ItemDisplayContext[] CONTEXTS = {
            ItemDisplayContext.GUI, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, ItemDisplayContext.GROUND,
            ItemDisplayContext.FIXED, ItemDisplayContext.HEAD, ItemDisplayContext.ON_SHELF};
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray checks = new JsonArray(), models = new JsonArray(), screenshots = new JsonArray();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final List<Integer> frameIds = new ArrayList<>(), groundIds = new ArrayList<>();
    private volatile boolean serverReady;
    private volatile Throwable failure;
    private boolean initialized, done, oldPause, oldVsync, oldHideGui, oldActive;
    private int warmup, ticks, nativeFrames, oldScale, oldFps, oldDistance, oldFov;
    private CameraType oldCamera;
    private ImportedAssetsScreen grid;
    private String capture;
    private long started;

    ImportedItemsReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks.add(message);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static ItemStack item(int index) { return ImportedItems.ITEMS.get(index).toStack(); }

    private void assertMonitor() {
        var environment = new JsonObject();
        RpgSmoke.windowEnvironment(environment);
        if (!"\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()))
            throw new AssertionError("Imported-items window outside DISPLAY5: " + environment);
    }

    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots"));
        assertMonitor();
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var named = System.getProperty("fsrpg.importedItemsWorld", "");
        check(named.startsWith("FSRPG Imported Items Review ") && named.equals(world.getFileName().toString())
                        && Files.isRegularFile(world.resolve(".fsrpg-imported-items-test-world")),
                "Only the explicitly marked copied imported-items world is modified");
        check(ImportedItems.IDS.equals(EXPECTED) && ImportedItems.ITEMS.size() == 10, "All nine existing item IDs and the new white badge are registered exactly once");
        check(ArtCatalog.read("/assets/fsrpg/models/item/silver_adventurer_badge.json").getAsJsonArray("elements").size() == 169,
                "Existing silver badge retains its original 169 source cuboids");
        check(ArtCatalog.read("/assets/fsrpg/models/item/white_adventurer_badge.json").getAsJsonArray("elements").size() == 544,
                "New white badge retains all 544 source cuboids");
        for (int i = 0; i < EXPECTED.size(); i++) {
            var stack = item(i);
            check(id(stack).equals("fsrpg:" + EXPECTED.get(i)), "Stable registered item ID " + EXPECTED.get(i));
            check(stack.getMaxStackSize() == (EXPECTED.get(i).equals("money_pouch") || EXPECTED.get(i).endsWith("badge") ? 1 : 64),
                    "Expected stack limit " + EXPECTED.get(i));
            for (var context : CONTEXTS) {
                var state = new ItemStackRenderState();
                game.getItemModelResolver().updateForTopItem(state, stack, context, game.level, game.player, i);
                check(!state.isEmpty(), "Native model present " + EXPECTED.get(i) + "/" + context);
                var particle = state.pickParticleIcon(RandomSource.create(1));
                check(particle != null && !particle.contents().name().toString().contains("missingno"),
                        "Native texture present " + EXPECTED.get(i) + "/" + context);
                var bounds = state.getModelBoundingBox();
                double width = bounds.maxX - bounds.minX, height = bounds.maxY - bounds.minY, depth = bounds.maxZ - bounds.minZ;
                check(Double.isFinite(width) && Double.isFinite(height) && Double.isFinite(depth) && width > 0 && height > 0 && depth > 0,
                        "Finite visible native bounds " + EXPECTED.get(i) + "/" + context);
                var row = new JsonObject();
                row.addProperty("id", id(stack)); row.addProperty("context", context.getSerializedName());
                row.addProperty("texture", particle.contents().name().toString());
                row.addProperty("widthMeters", width); row.addProperty("heightMeters", height); row.addProperty("depthMeters", depth);
                models.add(row);
            }
        }
        oldScale = game.options.guiScale().get(); oldFps = game.options.framerateLimit().get();
        oldDistance = game.options.renderDistance().get(); oldFov = game.options.fov().get();
        oldPause = game.options.pauseOnLostFocus; oldVsync = game.options.enableVsync().get();
        oldHideGui = game.options.hideGui; oldCamera = game.options.getCameraType(); oldActive = game.isWindowActive();
        game.options.guiScale().set(2); game.options.framerateLimit().set(60); game.options.renderDistance().set(3);
        game.options.pauseOnLostFocus = false; game.options.enableVsync().set(false); game.options.hideGui = false;
        game.setWindowActive(true); game.getTutorial().setStep(TutorialSteps.NONE);
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        var bounds = environment.getAsJsonObject("monitorBounds"); long handle = game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle, 1200, 780);
        GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 30, bounds.get("y").getAsInt() + 40);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 资源物品栏与十件模型 · DISPLAY5");
        game.resizeDisplay(); initialized = true; started = System.nanoTime();
        var uuid = game.player.getUUID();
        game.getSingleplayerServer().execute(() -> { try {
            var player = game.getSingleplayerServer().getPlayerList().getPlayer(uuid);
            player.setGameMode(GameType.CREATIVE); player.setInvulnerable(true); player.getInventory().clearContent();
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) player.setItemSlot(slot, ItemStack.EMPTY);
            RpgSmoke.actor(player).forceSheathed(); RpgServer.instance().action(player, "status", "");
            player.level().setDayTime(6000); player.level().setWeatherParameters(6000, 0, false, false);
            // A rerun may reuse this marked fixture copy; remove only its old display entities.
            var fixtureBounds = new net.minecraft.world.phys.AABB(-10, 79, -12, 11, 87, 9);
            for (var frame : player.level().getEntitiesOfClass(ItemFrame.class, fixtureBounds)) frame.discard();
            for (var dropped : player.level().getEntitiesOfClass(ItemEntity.class, fixtureBounds)) dropped.discard();
            for (int x = -10; x <= 10; x++) for (int z = -12; z <= 8; z++) {
                player.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 80; y < 87; y++) player.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            for (int x = -6; x <= 6; x++) for (int y = 80; y <= 85; y++)
                player.level().setBlock(new BlockPos(x, y, 6), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
            for (int i = 0; i < EXPECTED.size(); i++) {
                int x = -4 + 2 * (i % 5), y = i < 5 ? 83 : 81;
                var frame = new ItemFrame(player.level(), new BlockPos(x, y, 5), Direction.NORTH);
                frame.setItem(item(i), false); frame.setInvulnerable(true);
                player.level().addFreshEntity(frame); frameIds.add(frame.getId());
                var dropped = new ItemEntity(player.level(), x + .5, 80.15, -1 + 2 * (i / 5), item(i));
                dropped.setDeltaMovement(0, 0, 0); dropped.setNoGravity(true); dropped.setInvulnerable(true);
                dropped.setNeverPickUp(); dropped.setUnlimitedLifetime();
                player.level().addFreshEntity(dropped); groundIds.add(dropped.getId());
            }
            player.connection.teleport(.5, 80, -6, 180, 6); serverReady = true;
        } catch (Throwable error) { failure = error; } });
    }

    private void openCreative(Minecraft game) throws Exception {
        CreativeModeTabs.tryRebuildTabContents(game.level.enabledFeatures(), true, game.level.registryAccess());
        var tab = ImportedItems.RESOURCE_TAB.get();
        check(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).equals(Identifier.parse("fsrpg:resources")), "Dedicated resource creative tab has the stable fsrpg:resources key");
        var expected = EXPECTED.stream().map(name -> "fsrpg:" + name).toList();
        check(tab.getDisplayItems().stream().map(ImportedItemsReview::id).toList().equals(expected), "Dedicated creative tab contains exactly all ten imported items in order");
        check(tab.getSearchTabDisplayItems().stream().map(ImportedItemsReview::id).toList().equals(expected), "All ten resource items remain available to creative search");
        check(CreativeModeTabs.allTabs().stream().filter(value -> value == tab).count() == 1, "Resource tab is registered once");
        check(id(tab.getIconItem()).equals("fsrpg:white_adventurer_badge"), "Resource tab uses the newly added white badge icon");
        // Set the normal native tab before init, which also chooses its NeoForge tab page.
        var selected = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab"); selected.setAccessible(true); selected.set(null, tab);
        var screen = new CreativeModeInventoryScreen(game.player, game.level.enabledFeatures(), true);
        game.setScreen(screen);
        check(screen.getCurrentPage().getVisibleTabs().contains(tab), "Native creative page visibly includes the selected resource tab");
        check(screen.getMenu().items.stream().map(ImportedItemsReview::id).toList().equals(expected), "Native creative slots show exactly the ten registered resources");
    }

    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (done || game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) { if (++warmup >= 60) initialize(game); return; }
            if (failure != null) throw new IllegalStateException("Item fixture or capture failed", failure);
            if (System.nanoTime() - started > 120_000_000_000L) throw new AssertionError("Imported-items review timeout");
            game.getFramerateLimitTracker().onInputReceived(); KeyMapping.releaseAll();
            if (!serverReady) return;
            ticks++;
            if (ticks == 20) openCreative(game);
            if (ticks == 40) request("00-resource-creative-tab");
            if (ticks == 55) { grid = new ImportedAssetsScreen(); game.setScreen(grid); }
            if (ticks == 75) {
                check(grid.drawnFrames() > 0 && grid.observations.size() == 10, "All ten GUI models render in the enlarged native grid");
                request("01-all-ten-native-gui-models");
            }
            int phaseTick = ticks - 90;
            if (phaseTick >= 0 && phaseTick < EXPECTED.size() * 70) {
                int index = phaseTick / 70, local = phaseTick % 70;
                if (local == 0) {
                    game.setScreen(null); game.options.hideGui = false;
                    game.options.setCameraType(CameraType.FIRST_PERSON); game.options.fov().set(65);
                    server(player -> {
                        player.setItemSlot(EquipmentSlot.MAINHAND, item(index));
                        player.setItemSlot(EquipmentSlot.OFFHAND, item(index));
                    });
                }
                if (local == 20) {
                    check(id(game.player.getMainHandItem()).equals("fsrpg:" + EXPECTED.get(index)), "Real local player holds " + EXPECTED.get(index));
                    check(id(game.player.getOffhandItem()).equals("fsrpg:" + EXPECTED.get(index)), "Real local player offhand holds " + EXPECTED.get(index));
                    request("held-" + index + "-" + EXPECTED.get(index) + "-first-person");
                }
                if (local == 30) { game.options.setCameraType(CameraType.THIRD_PERSON_FRONT); game.options.fov().set(40); }
                if (local == 55) request("held-" + index + "-" + EXPECTED.get(index) + "-third-person");
            }
            if (ticks == 800) {
                game.setScreen(null); game.options.hideGui = true; game.options.setCameraType(CameraType.FIRST_PERSON); game.options.fov().set(65);
                server(player -> {
                    player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                    player.connection.teleport(.5, 81.5, -3, 0, 0);
                });
            }
            if (ticks == 835) {
                for (int i = 0; i < EXPECTED.size(); i++) {
                    var entity = game.level.getEntity(frameIds.get(i));
                    check(entity instanceof ItemFrame frame && id(frame.getItem()).equals("fsrpg:" + EXPECTED.get(i)), "Real item-frame stack synchronized " + EXPECTED.get(i));
                }
                request("02-all-ten-native-fixed-item-frames");
            }
            if (ticks == 850) server(player -> player.connection.teleport(.5, 80, -8, 0, 15));
            if (ticks == 885) {
                for (int i = 0; i < EXPECTED.size(); i++) {
                    var entity = game.level.getEntity(groundIds.get(i));
                    check(entity instanceof ItemEntity dropped && id(dropped.getItem()).equals("fsrpg:" + EXPECTED.get(i)), "Real dropped-item stack synchronized " + EXPECTED.get(i));
                }
                request("03-all-ten-native-ground-items");
            }
            if (ticks >= 900 && capture == null && pendingWrites.get() == 0) {
                check(nativeFrames > 200, "Real native render frames captured throughout the static review");
                check(screenshots.size() == 24, "Creative tab, enlarged grid, twenty held views and two world galleries captured");
                for (var value : screenshots) check(Files.size(root.resolve(value.getAsJsonObject().get("file").getAsString())) > 1000,
                        "Screenshot written " + value.getAsJsonObject().get("file").getAsString());
                finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }

    private void request(String name) {
        if (capture != null) throw new IllegalStateException("Screenshot request overlapped");
        capture = name;
    }

    private void server(java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> work) {
        var game = Minecraft.getInstance(); var uuid = game.player.getUUID();
        game.getSingleplayerServer().execute(() -> { try {
            work.accept(game.getSingleplayerServer().getPlayerList().getPlayer(uuid));
        } catch (Throwable error) { failure = error; } });
    }

    private void render(RenderFrameEvent.Post event) {
        if (done || !initialized) return;
        nativeFrames++;
        if (capture == null) return;
        try {
            assertMonitor(); String name = capture; capture = null;
            pendingWrites.incrementAndGet();
            Screenshot.grab(root.toFile(), name + ".png", Minecraft.getInstance().getMainRenderTarget(), 1, message -> pendingWrites.decrementAndGet());
            var row = new JsonObject(); row.addProperty("file", "screenshots/" + name + ".png");
            row.addProperty("nativeFrame", nativeFrames); row.addProperty("gameTick", ticks); screenshots.add(row);
        } catch (Throwable error) { failure = error; }
    }

    private void finish(Throwable error) {
        if (done) return;
        if (error == null) try { assertMonitor(); } catch (Throwable monitorError) { error = monitorError; }
        done = true;
        try {
            var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("world", System.getProperty("fsrpg.importedItemsWorld"));
            report.addProperty("staticItemsOnly", true); report.addProperty("creativeTab", "fsrpg:resources");
            report.add("expectedItemIds", RpgConfig.JSON.toJsonTree(EXPECTED)); report.addProperty("checks", checks.size()); report.add("checksPassed", checks);
            report.addProperty("nativeRenderFrames", nativeFrames); report.add("nativeItemModels", models); report.add("screenshots", screenshots);
            RpgSmoke.windowEnvironment(report);
            var jar = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            report.addProperty("modJar", jar.toString()); report.addProperty("modJarSha256", Files.isRegularFile(jar) ? sha(Files.readAllBytes(jar)) : "loose-classes");
            RpgConfig.atomicWrite(root.resolve("imported-items-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("IMPORTED ITEMS REVIEW COMPLETE passed=" + (error == null) + " " + root);
            if (error != null) error.printStackTrace();
        } catch (Throwable reporting) { reporting.printStackTrace(); }
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) {
            game.options.guiScale().set(oldScale); game.options.framerateLimit().set(oldFps); game.options.renderDistance().set(oldDistance); game.options.fov().set(oldFov);
            game.options.pauseOnLostFocus = oldPause; game.options.enableVsync().set(oldVsync); game.options.hideGui = oldHideGui;
            game.options.setCameraType(oldCamera); game.setWindowActive(oldActive);
        }
        game.stop();
    }
}

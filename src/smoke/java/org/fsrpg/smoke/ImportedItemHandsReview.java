package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.ImportedItems;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ImportedItemPresentation;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.LegacyFirstPersonAnimations;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** Native first-person hand/item acceptance and bounded real-frame equip recordings. */
final class ImportedItemHandsReview {
    private static final java.lang.reflect.Method LOCAL_INTENT = localIntentMethod();
    private static final String[] HANDS = {"mainHand", "offHand"};
    private static final List<String> MOVING_ITEMS = List.of("money_pouch", "gold_coin", "white_adventurer_badge");
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie = new RenderFrameCapture(root);
    private final JsonArray checks = new JsonArray(), scenes = new JsonArray(), screenshots = new JsonArray();
    private final AtomicInteger pendingShots = new AtomicInteger();
    private volatile boolean serverReady;
    private volatile Throwable failure;
    private boolean initialized, done, recording, oldPause, oldVsync, oldHidden, oldActive, oldRightSleeve, oldLeftSleeve, oldCombatShoulder;
    private boolean checkLegacyFrame, staleSwitchPending, pouchPeakShot;
    private net.neoforged.neoforge.common.ModConfigSpec.BooleanValue combatShoulder;
    private int warmup, ticks, nativeFrames, oldScale, oldFps, oldDistance, oldFov;
    private int vanillaEvents, vanillaCanceled, outgoingEvents, outgoingFallback, staleIntentFrames;
    private long outgoingNativeCount, legacyBeforeFrame, legacyBeforeCase;
    private long started;
    private String capture;
    private JsonObject beforeStatic, settledStatic, contextBaseline, vanillaBaseline, movingSettled, invisibleBaseline;
    private JsonArray originalGuiModels;
    private CameraType oldCamera;
    private HumanoidArm oldArm;
    private ImportedAssetsScreen grid;

    ImportedItemHandsReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::preRender);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks.add(message);
        System.out.println("IMPORTED HANDS PASS " + message);
    }

    private static double number(JsonObject object, String key) { return object.get(key).getAsDouble(); }
    private static long count(JsonObject object, String key) { return object.get(key).getAsLong(); }
    private static String text(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static JsonObject hand(JsonObject object, String key) { return object.getAsJsonObject(key); }
    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static ItemStack item(String name) { return ImportedItems.ITEMS.get(ImportedItems.IDS.indexOf(name)).toStack(); }
    private static JsonObject diagnostic() { return ImportedItemPresentation.diagnostics().deepCopy(); }
    private static java.lang.reflect.Method localIntentMethod() {
        try {
            var method = PlayerAnimations.class.getDeclaredMethod("localIntent"); method.setAccessible(true); return method;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect native animation intent", error); }
    }
    private static org.fsrpg.network.RpgAnimationPayload localIntent() {
        try { return (org.fsrpg.network.RpgAnimationPayload) LOCAL_INTENT.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect native animation intent", error); }
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private void assertMonitor() {
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        if (!"\\\\.\\DISPLAY5".equals(text(environment, "monitorAdapter")))
            throw new AssertionError("Imported-hand review window outside DISPLAY5: " + environment);
    }

    private void server(Consumer<ServerPlayer> work) {
        var game = Minecraft.getInstance(); var uuid = game.player.getUUID();
        game.getSingleplayerServer().execute(() -> { try {
            work.accept(game.getSingleplayerServer().getPlayerList().getPlayer(uuid));
        } catch (Throwable error) { failure = error; } });
    }

    private void equip(String name, int amount) {
        server(player -> {
            var stack = name == null ? ItemStack.EMPTY : item(name);
            if (!stack.isEmpty()) stack.setCount(amount);
            player.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            player.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
        });
    }

    private void dominant(HumanoidArm arm) {
        var game = Minecraft.getInstance();
        game.options.mainHand().set(arm); game.player.setMainArm(arm);
        server(player -> player.setMainArm(arm));
    }

    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots")); assertMonitor();
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String named = System.getProperty("fsrpg.importedItemHandsWorld", "");
        check(named.startsWith("FSRPG Imported Item Hands Review ") && named.equals(world.getFileName().toString())
                        && Files.isRegularFile(world.resolve(".fsrpg-imported-item-hands-test-world")),
                "Only the explicitly marked copied imported-hand test world is modified");
        check(ImportedItems.IDS.size() == 10, "Ten imported resource items are available to the hand review");
        int supported = 0, excluded = 0; var otherNamespaces = new java.util.TreeSet<String>();
        for (var registered : BuiltInRegistries.ITEM) {
            var key = BuiltInRegistries.ITEM.getKey(registered);
            boolean expected = key.getNamespace().equals("fsrpg") && ImportedItems.IDS.contains(key.getPath());
            if (ImportedItemPresentation.supports(registered.getDefaultInstance()) != expected)
                throw new AssertionError("Imported renderer scope leaks into another item: " + key);
            if (expected) supported++; else excluded++;
            if (!key.getNamespace().equals("minecraft") && !key.getNamespace().equals("fsrpg")) otherNamespaces.add(key.getNamespace());
        }
        check(supported == 10 && excluded > 100, "The real registry scope accepts exactly ten resources and excludes every other installed item");
        var registryScope = new JsonObject(); registryScope.addProperty("id", "registry-render-scope"); registryScope.addProperty("supportedItems", supported);
        registryScope.addProperty("excludedItems", excluded); registryScope.add("otherInstalledItemNamespaces", RpgConfig.JSON.toJsonTree(otherNamespaces)); scenes.add(registryScope);
        oldScale = game.options.guiScale().get(); oldFps = game.options.framerateLimit().get();
        oldDistance = game.options.renderDistance().get(); oldFov = game.options.fov().get(); oldArm = game.options.mainHand().get();
        oldRightSleeve = game.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE); oldLeftSleeve = game.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
        var shoulderField = Class.forName("org.fsrpg.client.ClientOptions").getDeclaredField("THIRD_PERSON"); shoulderField.setAccessible(true);
        combatShoulder = (net.neoforged.neoforge.common.ModConfigSpec.BooleanValue) shoulderField.get(null); oldCombatShoulder = combatShoulder.get(); combatShoulder.set(false);
        oldPause = game.options.pauseOnLostFocus; oldVsync = game.options.enableVsync().get(); oldHidden = game.options.hideGui;
        oldActive = game.isWindowActive(); oldCamera = game.options.getCameraType();
        game.options.guiScale().set(2); game.options.framerateLimit().set(40); game.options.renderDistance().set(3); game.options.fov().set(65);
        game.options.pauseOnLostFocus = false; game.options.enableVsync().set(false); game.options.hideGui = false;
        game.options.setCameraType(CameraType.FIRST_PERSON); game.setWindowActive(true); game.getTutorial().setStep(TutorialSteps.NONE);
        game.options.setModelPart(PlayerModelPart.RIGHT_SLEEVE, true); game.options.setModelPart(PlayerModelPart.LEFT_SLEEVE, true); game.options.broadcastOptions();
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        var bounds = environment.getAsJsonObject("monitorBounds"); long handle = game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle, Math.min(1200, bounds.get("width").getAsInt() - 60), Math.min(780, bounds.get("height").getAsInt() - 100));
        GLFW.glfwSetWindowPos(handle, bounds.get("x").getAsInt() + 30, bounds.get("y").getAsInt() + 40);
        GLFW.glfwSetWindowTitle(handle, "FSRPG 原版手臂持物与一次性取出动作 · DISPLAY5"); game.resizeDisplay();
        // Register the observation after client bootstrap so LOWEST runs after the production hand listener.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::observeHand);
        initialized = true; started = System.nanoTime(); dominant(HumanoidArm.RIGHT);
        server(player -> {
            player.setGameMode(GameType.CREATIVE); player.setInvulnerable(true); player.getInventory().clearContent();
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) player.setItemSlot(slot, ItemStack.EMPTY);
            RpgSmoke.actor(player).forceSheathed(); RpgServer.instance().action(player, "status", "");
            player.level().setDayTime(6000); player.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -8; x <= 8; x++) for (int z = -12; z <= 5; z++) {
                player.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 80; y < 86; y++) player.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            player.connection.teleport(.5, 80, -6, 180, 6); serverReady = true;
        });
    }

    private void verifySettled(String name, JsonObject values, HumanoidArm mainArm) {
        var game = Minecraft.getInstance();
        check(id(game.player.getMainHandItem()).equals("fsrpg:" + name) && id(game.player.getOffhandItem()).equals("fsrpg:" + name),
                "Both real ItemStacks synchronized " + name);
        for (var side : HANDS) {
            var value = hand(values, side);
            check(text(value, "itemId").equals("fsrpg:" + name), "Native official item submission " + name + "/" + side);
            check(value.get("settled").getAsBoolean(), "One-shot equip has settled " + name + "/" + side);
            check(!value.get("pending").getAsBoolean(), "One-shot equip has actually started and completed " + name + "/" + side);
            check(Math.abs(number(value, "itemLift")) < .0001 && Math.abs(number(value, "armLift")) < .0001,
                    "Resting item and hand stop after the one-shot motion " + name + "/" + side);
            check(Math.abs(number(value, "armTiltDegrees")) < .0001, "Whole-hand tilt settles back to the resting grip " + name + "/" + side);
            String expectedArm = (side.equals("mainHand") ? mainArm : mainArm.getOpposite()).name();
            check(text(value, "arm").equals(expectedArm), "Real dominant-hand mapping " + name + "/" + side + "=" + expectedArm);
        }
        check(text(values, "nativeArmSkin").equals(game.player.getSkin().body().texturePath().toString()), "Native arms use the player's actual skin " + name);
        check(text(values, "nativeArmModel").equals(game.player.getSkin().model().name()), "Native arms use the player's actual WIDE/SLIM model " + name);
        var sleeve = text(values, "lastArm").equals("LEFT") ? PlayerModelPart.LEFT_SLEEVE : PlayerModelPart.RIGHT_SLEEVE;
        check(values.get("nativeArmSleeveVisible").getAsBoolean() == game.player.isModelPartShown(sleeve), "Native arm respects the actual sleeve layer " + name);
        if (name.equals("money_pouch")) {
            check(number(values, "lastItemScale") >= .95, "Pouch uses its enlarged native presentation scale");
            check(number(values, "lastArmRaise") >= .10, "Pouch grip is raised into a readable lower-screen position");
        }
    }

    private void sameSerial(JsonObject first, JsonObject second, String note) {
        for (var side : HANDS) check(count(hand(first, side), "equipSerial") == count(hand(second, side), "equipSerial"), note + "/" + side);
    }

    private void sameSubmissions(JsonObject first, JsonObject second, String note) {
        for (var key : List.of("armSubmissions", "itemSubmissions", "mainHandSubmissions", "offHandSubmissions"))
            check(count(first, key) == count(second, key), note + "/" + key);
    }

    private void scene(String name, JsonObject values) {
        var row = new JsonObject(); row.addProperty("id", name); row.addProperty("gameTick", ticks); row.add("diagnostics", values); scenes.add(row);
    }

    private void staticItems(Minecraft game, int elapsed) {
        int index = elapsed / 50, local = elapsed % 50; String name = ImportedItems.IDS.get(index);
        if (local == 0) { beforeStatic = diagnostic(); equip(name, 1); }
        if (local == 35) {
            var values = diagnostic(); verifySettled(name, values, HumanoidArm.RIGHT);
            for (var key : List.of("armSubmissions", "itemSubmissions", "mainHandSubmissions", "offHandSubmissions"))
                check(count(values, key) > count(beforeStatic, key) + 10, "Repeated actual native arm/item submissions " + name + "/" + key);
            settledStatic = values; scene("static-" + name, values); request("static-" + index + "-" + name + "-both-hands");
        }
        if (local == 40) equip(name, item(name).getMaxStackSize() > 1 ? 2 : 1);
        if (local == 49) {
            var values = diagnostic();
            sameSerial(settledStatic, values, "Idle or a fresh count-only stack does not replay equip " + name);
            check(game.player.getMainHandItem().getCount() == (item(name).getMaxStackSize() > 1 ? 2 : 1), "Count-only replacement reaches the real client " + name);
        }
    }

    private JsonObject captureObservation() {
        var values = diagnostic();
        var observation = new JsonObject(); observation.add("nativeHands", values);
        var rig = new JsonObject();
        for (String side : HANDS) {
            var state = hand(values, side); var pose = new JsonObject();
            if (state != null) for (String key : List.of("itemId", "arm", "kind", "itemLift", "armLift", "armTiltDegrees", "itemSpinDegrees", "drawProgress"))
                if (state.has(key)) pose.add(key, state.get(key).deepCopy());
            rig.add(side, pose);
        }
        observation.add("rig", rig); observation.addProperty("stageTick", ticks); return observation;
    }

    private void movingItems(int elapsed) throws Exception {
        int index = elapsed / 60, local = elapsed % 60; String name = MOVING_ITEMS.get(index);
        if (local == 0) { equip(null, 1); movingSettled = null; if (name.equals("money_pouch")) pouchPeakShot = false; }
        if (local == 4) {
            movie.start("equip-" + name.replace('_', '-'), "actual first-person both-hand equip: " + name, this::captureObservation);
            recording = true;
        }
        if (local == 8) equip(name, 1);
        if (local == 21 && !name.equals("money_pouch")) request("motion-" + name + "-active");
        if (local == 44) {
            movingSettled = diagnostic(); verifySettled(name, movingSettled, HumanoidArm.RIGHT);
            scene("equip-settled-" + name, movingSettled); request("motion-" + name + "-settled");
        }
        if (local == 47) equip(name, item(name).getMaxStackSize() > 1 ? 3 : 1);
        if (local == 55) sameSerial(movingSettled, diagnostic(), "Recorded settled equip stays still after stack replacement " + name);
        if (local == 59) { movie.stop(); recording = false; }
    }

    private void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (done || game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) { if (++warmup >= 60) initialize(game); return; }
            if (failure != null) throw new IllegalStateException("Native hand fixture failed", failure);
            if (System.nanoTime() - started > 180_000_000_000L) throw new AssertionError("Native imported-hand review timeout");
            movie.assertHealthy(); game.getFramerateLimitTracker().onInputReceived(); KeyMapping.releaseAll();
            if (!serverReady) return; ticks++;
            if (ticks == 20) { grid = new ImportedAssetsScreen(); game.setScreen(grid); }
            if (ticks == 40) { check(grid.drawnFrames() > 10 && grid.observations.size() == 10, "All ten original GUI models remain loaded and visible"); originalGuiModels = grid.observations.deepCopy(); request("00-native-gui-models-unchanged"); }
            if (ticks == 50) game.setScreen(null);
            if (ticks >= 60 && ticks < 560) staticItems(game, ticks - 60);
            if (ticks == 570) { dominant(HumanoidArm.LEFT); equip("white_adventurer_badge", 1); }
            if (ticks == 610) { var values = diagnostic(); verifySettled("white_adventurer_badge", values, HumanoidArm.LEFT); scene("left-dominant-both-hands", values); request("11-left-dominant-native-arms"); }
            if (ticks == 625) { contextBaseline = diagnostic(); game.options.setCameraType(CameraType.THIRD_PERSON_FRONT); }
            if (ticks == 645) { sameSubmissions(contextBaseline, diagnostic(), "Third person never invokes the custom first-person presentation"); request("12-third-person-unchanged"); }
            if (ticks == 650) { grid = new ImportedAssetsScreen(); game.setScreen(grid); contextBaseline = diagnostic(); }
            if (ticks == 670) {
                check(grid.drawnFrames() > 10, "GUI continues rendering after equip animations");
                check(grid.observations.equals(originalGuiModels), "GUI model IDs, textures and native bounds stay identical after first-person presentation");
            }
            if (ticks == 675) {
                game.setScreen(null); game.options.setCameraType(CameraType.FIRST_PERSON); dominant(HumanoidArm.RIGHT);
                server(player -> { player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE)); player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_SWORD)); });
            }
            if (ticks == 700) vanillaBaseline = diagnostic();
            if (ticks == 725) {
                sameSubmissions(vanillaBaseline, diagnostic(), "Vanilla items use their original rendering without imported-arm submissions");
                check(vanillaEvents > 30 && vanillaCanceled == 0, "Actual vanilla RenderHandEvents remain uncanceled after switching away");
                request("13-vanilla-fallback"); scene("vanilla-fallback", diagnostic());
            }
            if (ticks >= 750 && ticks < 930) movingItems(ticks - 750);
            if (ticks >= 940 && ticks < 1090) {
                int index = (ticks - 940) / 50, local = (ticks - 940) % 50; String name = MOVING_ITEMS.get(index);
                if (local == 0) server(player -> { player.setItemSlot(EquipmentSlot.MAINHAND, item(name)); player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY); });
                if (local == 40) {
                    var values = diagnostic();
                    check(id(game.player.getMainHandItem()).equals("fsrpg:" + name) && game.player.getOffhandItem().isEmpty(), "Actual single-main-hand view " + name);
                    check(text(hand(values, "mainHand"), "itemId").equals("fsrpg:" + name) && hand(values, "mainHand").get("settled").getAsBoolean(), "Single-main-hand object settles on its native hand " + name);
                    scene("single-main-hand-" + name, values); request("single-main-hand-" + name);
                }
            }
            if (ticks == 1090) { game.options.setModelPart(PlayerModelPart.RIGHT_SLEEVE, false); game.options.broadcastOptions(); }
            if (ticks == 1110) {
                check(!game.player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE), "Sleeve visibility change reaches the real player");
                check(!diagnostic().get("nativeArmSleeveVisible").getAsBoolean(), "Native renderer honors the hidden sleeve layer");
                scene("native-sleeve-hidden", diagnostic()); request("native-sleeve-hidden");
            }
            if (ticks == 1115) { game.options.setModelPart(PlayerModelPart.RIGHT_SLEEVE, true); game.options.broadcastOptions(); }
            if (ticks == 1130) server(player -> player.setInvisible(true));
            if (ticks == 1145) { check(game.player.isInvisible(), "Invisible state is synchronized from the server"); invisibleBaseline = diagnostic(); }
            if (ticks == 1165) {
                var values = diagnostic();
                check(count(values, "armSubmissions") == count(invisibleBaseline, "armSubmissions"), "Invisible player never submits a native skin arm");
                check(count(values, "itemSubmissions") > count(invisibleBaseline, "itemSubmissions") + 10, "Invisible player's official held item continues rendering");
                scene("invisible-item-without-arm", values); request("invisible-item-without-arm");
            }
            if (ticks == 1170) server(player -> player.setInvisible(false));
            if (ticks == 1200) {
                check(!game.player.isInvisible() && count(diagnostic(), "armSubmissions") > count(invisibleBaseline, "armSubmissions") + 10, "Native arms resume after invisibility ends");
                CombatCamera.reset(); game.options.setCameraType(CameraType.FIRST_PERSON); game.player.getInventory().setSelectedSlot(0);
                server(this::equipLegacyWeapon);
            }
            if (ticks == 1230) {
                var intent = localIntent();
                check(ClientState.drawn() && intent != null && intent.model().startsWith("legacy_"), "Actual server-drawn legacy weapon reaches first-person presentation");
                check(game.options.getCameraType().isFirstPerson(), "Legacy compatibility check remains in first person with automatic shoulder camera disabled");
                legacyBeforeCase = LegacyFirstPersonAnimations.samples(); contextBaseline = diagnostic();
            }
            if (ticks == 1250) {
                check(LegacyFirstPersonAnimations.samples() > legacyBeforeCase + 10, "Legacy weapon's original authored arm pair continues rendering");
                sameSubmissions(contextBaseline, diagnostic(), "Legacy weapon plus resource offhand does not add a second native arm pair");
                request("legacy-weapon-before-resource-switch"); scene("legacy-weapon-with-resource-offhand", diagnostic());
            }
            if (ticks == 1255) staleSwitchPending = true;
            if (ticks == 1260) server(player -> player.getInventory().setSelectedSlot(1));
            if (ticks == 1300) {
                verifySettled("white_adventurer_badge", diagnostic(), HumanoidArm.RIGHT);
                check(staleIntentFrames > 0, "Real predicted hotbar switch exercised frames with an outgoing legacy intent");
                request("resource-after-live-legacy-switch"); scene("resource-owns-stale-legacy-intent", diagnostic());
            }
            if (ticks == 1310) { game.player.getInventory().setSelectedSlot(0); server(this::equipLegacyWeapon); }
            if (ticks == 1340) {
                check(ClientState.drawn() && game.options.getCameraType().isFirstPerson(), "Switching back restores the drawn weapon in first person");
                legacyBeforeCase = LegacyFirstPersonAnimations.samples(); contextBaseline = diagnostic();
            }
            if (ticks == 1360) {
                check(LegacyFirstPersonAnimations.samples() > legacyBeforeCase + 10, "Returning from a resource resumes the original legacy animation renderer");
                sameSubmissions(contextBaseline, diagnostic(), "Returning weapon does not duplicate the imported native arms");
                request("legacy-weapon-after-resource-switch"); scene("legacy-weapon-restored", diagnostic());
            }
            if (ticks >= 1380 && capture == null && pendingShots.get() == 0 && movie.finish()) {
                validateCaptures(); finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }

    private void observeHand(RenderHandEvent event) {
        if (!initialized || done) return;
        var game = Minecraft.getInstance();
        if (ticks >= 60 && ticks <= 695 && ImportedItemPresentation.supports(event.getItemStack())
                && !ItemStack.isSameItemSameComponents(event.getItemStack(), game.player.getItemInHand(event.getHand()))) {
            outgoingEvents++; var values = diagnostic(); long submitted = count(values, "outgoingItemSubmissions");
            if (!event.isCanceled() || !values.get("lastOutgoing").getAsBoolean() || submitted <= outgoingNativeCount) outgoingFallback++;
            outgoingNativeCount = submitted;
        }
        if (ticks >= 700 && ticks <= 725 && (event.getItemStack().is(Items.APPLE) || event.getItemStack().is(Items.DIAMOND_SWORD))) {
            vanillaEvents++; if (event.isCanceled()) vanillaCanceled++;
        }
    }

    private void equipLegacyWeapon(ServerPlayer player) {
        var rpg = RpgServer.instance();
        rpg.progression().learnFree(player.getUUID(), "rapier");
        player.getInventory().setSelectedSlot(0); player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setItem(1, item("white_adventurer_badge")); player.setItemSlot(EquipmentSlot.OFFHAND, item("white_adventurer_badge"));
        var actor = RpgSmoke.actor(player); actor.forceSheathed(); actor.setStyle(rpg.config().current().styles().get("rapier"));
        rpg.action(player, "status", ""); actor.requestDrawn(); rpg.action(player, "status", "");
    }

    private void preRender(RenderFrameEvent.Pre event) {
        if (!initialized || done) return; var game = Minecraft.getInstance();
        if (game.player == null) return;
        if (staleSwitchPending) {
            // This is the same local prediction as choosing a real hotbar slot; normal client ticks synchronize it.
            game.player.getInventory().setSelectedSlot(1); staleSwitchPending = false;
        }
        checkLegacyFrame = ticks >= 1200 && ImportedItemPresentation.supports(game.player.getMainHandItem());
        if (checkLegacyFrame) {
            legacyBeforeFrame = LegacyFirstPersonAnimations.samples();
            var intent = localIntent();
            if (ClientState.drawn() && intent != null && intent.model().startsWith("legacy_")) staleIntentFrames++;
        }
    }

    private void validateCaptures() throws Exception {
        var captureReport = movie.summary(); var clips = captureReport.getAsJsonArray("clips");
        check(clips.size() == 3, "Exactly three short real-frame equip clips were recorded");
        double totalSeconds = 0;
        for (var value : clips) {
            var clip = value.getAsJsonObject(); String clipId = text(clip, "id");
            check(number(clip, "sourceFps") >= 30, "Actual rendered capture is at least 30 Hz " + clipId);
            check(count(clip, "capturedFrames") == count(clip, "writtenFrames") && count(clip, "capturedFrames") >= 60, "Every actual frame is written once " + clipId);
            check(count(clip, "distinctSamplesWithinGameTick") > 5 && count(clip, "changedRigPairs") > 5, "Motion advances across real sub-tick rendered frames " + clipId);
            totalSeconds += number(clip, "captureDurationSeconds");
            double previous = -1, maximumLift = 0, minimumLift = 0, maximumArmLift = 0, minimumArmLift = 0, maximumSpin = 0;
            double maximumArmTilt = 0, minimumArmTilt = 0, pouchDipAt = -1, pouchHeftAt = -1, pouchReturnAt = -1;
            boolean sawSettled = false;
            long serial = -1; String expected = clipId.substring("equip-".length()).replace('-', '_');
            int activeSamples = 0;
            for (var frameValue : clip.getAsJsonArray("frames")) {
                var frame = frameValue.getAsJsonObject(); double current = number(frame, "capturedAtSeconds");
                if (!(current > previous)) throw new AssertionError("Non-increasing source timestamp " + clipId);
                previous = current;
                if (frame.get("paused").getAsBoolean() || !frame.get("levelRunningNormally").getAsBoolean()) throw new AssertionError("Paused recording " + clipId);
                var nativeHands = frame.getAsJsonObject("observation").getAsJsonObject("nativeHands");
                var main = hand(nativeHands, "mainHand");
                if (main != null && main.has("itemId") && text(main, "itemId").equals("fsrpg:" + expected)) {
                    activeSamples++; long nextSerial = count(main, "equipSerial");
                    if (serial >= 0 && serial != nextSerial) throw new AssertionError("Equip replayed during one recorded selection " + clipId);
                    serial = nextSerial;
                    maximumLift = Math.max(maximumLift, number(main, "itemLift")); minimumLift = Math.min(minimumLift, number(main, "itemLift"));
                    maximumArmLift = Math.max(maximumArmLift, number(main, "armLift")); minimumArmLift = Math.min(minimumArmLift, number(main, "armLift"));
                    maximumArmTilt = Math.max(maximumArmTilt, number(main, "armTiltDegrees")); minimumArmTilt = Math.min(minimumArmTilt, number(main, "armTiltDegrees"));
                    if (expected.equals("money_pouch")) {
                        double armLift = number(main, "armLift");
                        if (pouchDipAt < 0 && pouchHeftAt < 0 && armLift < -.025) pouchDipAt = current;
                        if (pouchHeftAt < 0 && armLift > .075) pouchHeftAt = current;
                        if (pouchHeftAt >= 0 && pouchReturnAt < 0 && armLift < -.012) pouchReturnAt = current;
                    }
                    maximumSpin = Math.max(maximumSpin, Math.abs(number(main, "itemSpinDegrees")));
                    sawSettled |= main.get("settled").getAsBoolean();
                }
            }
            check(activeSamples > 30 && sawSettled, "Actual equip is visible and returns to settled rest " + clipId);
            if (expected.endsWith("coin")) check(maximumLift > .05 && maximumSpin > 90, "Coin visibly rises and spins before landing " + clipId);
            else if (expected.equals("money_pouch")) {
                check(maximumArmLift > .075 && minimumArmLift < -.025, "Pouch and native hand visibly load downward then heft upward");
                check(pouchDipAt >= 0 && pouchDipAt < pouchHeftAt && pouchHeftAt < pouchReturnAt,
                        "Actual pouch frames show anticipation, upward heft and downward return in order");
                check(maximumArmTilt > 5 && minimumArmTilt < -4, "Pouch and attached native arm tilt with the weighted heft");
                check(pouchPeakShot, "Pouch active screenshot is captured from an actual raised-hand frame");
            }
            var note = new JsonObject(); note.addProperty("id", clipId); note.addProperty("maximumItemLift", maximumLift); note.addProperty("minimumItemLift", minimumLift); note.addProperty("maximumArmLift", maximumArmLift); note.addProperty("minimumArmLift", minimumArmLift); note.addProperty("maximumArmTiltDegrees", maximumArmTilt); note.addProperty("minimumArmTiltDegrees", minimumArmTilt); note.addProperty("pouchDipAtSeconds", pouchDipAt); note.addProperty("pouchHeftAtSeconds", pouchHeftAt); note.addProperty("pouchReturnAtSeconds", pouchReturnAt); note.addProperty("maximumSpinDegrees", maximumSpin); note.addProperty("observedEquipSerial", serial); scenes.add(note);
        }
        check(totalSeconds <= 10, "Dynamic recording is bounded to ten source seconds total");
        check(outgoingEvents > 0 && outgoingFallback == 0, "Every real outgoing cached resource keeps its native arm/item presentation until vanilla replacement");
        check(screenshots.size() == 28, "GUI, ten both-hand items, handedness, third-person, fallback, six motion keyframes, three single-hand views, sleeve, invisible and three legacy-switch views captured");
        for (var screenshot : screenshots) check(Files.size(root.resolve(text(screenshot.getAsJsonObject(), "file"))) > 1000,
                "Native screenshot written " + text(screenshot.getAsJsonObject(), "file"));
    }

    private void request(String name) {
        if (capture != null) throw new IllegalStateException("Screenshot request overlapped"); capture = name;
    }

    private void render(RenderFrameEvent.Post event) {
        if (done || !initialized) return; nativeFrames++;
        try {
            if (checkLegacyFrame && LegacyFirstPersonAnimations.samples() != legacyBeforeFrame)
                throw new AssertionError("Legacy arm pair rendered over an actual imported main-hand item during hotbar switching");
            if (recording) {
                assertMonitor(); movie.frame(event);
                var main = hand(diagnostic(), "mainHand");
                if (!pouchPeakShot && main != null && text(main, "itemId").equals("fsrpg:money_pouch") && number(main, "armLift") > .075) {
                    request("motion-money_pouch-active"); pouchPeakShot = true;
                }
            }
            if (capture == null) return;
            assertMonitor(); String name = capture; capture = null; pendingShots.incrementAndGet();
            Screenshot.grab(root.toFile(), name + ".png", Minecraft.getInstance().getMainRenderTarget(), 1, message -> pendingShots.decrementAndGet());
            var row = new JsonObject(); row.addProperty("file", "screenshots/" + name + ".png"); row.addProperty("nativeFrame", nativeFrames); row.addProperty("gameTick", ticks); screenshots.add(row);
        } catch (Throwable error) { failure = error; }
    }

    private void finish(Throwable error) {
        if (done) return;
        if (error == null) try { assertMonitor(); } catch (Throwable monitorFailure) { error = monitorFailure; }
        done = true; movie.stop();
        try {
            var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("world", System.getProperty("fsrpg.importedItemHandsWorld")); report.addProperty("checks", checks.size()); report.add("checksPassed", checks);
            report.addProperty("nativeRenderFrames", nativeFrames); report.addProperty("vanillaRenderHandEvents", vanillaEvents); report.addProperty("vanillaCanceledEvents", vanillaCanceled);
            report.addProperty("outgoingCachedItemEvents", outgoingEvents); report.addProperty("outgoingFallbackEvents", outgoingFallback); report.addProperty("staleLegacyIntentFrames", staleIntentFrames);
            report.add("nativeHands", diagnostic()); report.add("scenes", scenes); report.add("screenshots", screenshots); report.add("renderCapture", movie.summary());
            RpgSmoke.windowEnvironment(report);
            var jar = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            report.addProperty("modJar", jar.toString()); report.addProperty("modJarSha256", Files.isRegularFile(jar) ? sha(Files.readAllBytes(jar)) : "loose-classes");
            RpgConfig.atomicWrite(root.resolve("imported-item-hands-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("IMPORTED ITEM HANDS REVIEW COMPLETE passed=" + (error == null) + " " + root);
            if (error != null) error.printStackTrace();
        } catch (Throwable reporting) { reporting.printStackTrace(); }
        var game = Minecraft.getInstance(); KeyMapping.releaseAll();
        if (initialized) {
            game.options.guiScale().set(oldScale); game.options.framerateLimit().set(oldFps); game.options.renderDistance().set(oldDistance); game.options.fov().set(oldFov);
            game.options.pauseOnLostFocus = oldPause; game.options.enableVsync().set(oldVsync); game.options.hideGui = oldHidden; game.options.mainHand().set(oldArm);
            game.options.setModelPart(PlayerModelPart.RIGHT_SLEEVE, oldRightSleeve); game.options.setModelPart(PlayerModelPart.LEFT_SLEEVE, oldLeftSleeve); game.options.broadcastOptions();
            combatShoulder.set(oldCombatShoulder); CombatCamera.reset();
            game.options.setCameraType(oldCamera); game.setWindowActive(oldActive);
        }
        game.stop();
    }
}

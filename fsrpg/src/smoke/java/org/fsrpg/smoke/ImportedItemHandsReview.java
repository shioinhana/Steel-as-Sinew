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
    // Preserve the existing regression sequence while extending only the three pouch cases.
    private static final int STATIC_POUCH_EXTRA = 30, MOTION_POUCH_EXTRA = 22, SINGLE_POUCH_EXTRA = 30;
    private static final int MOTION_START = 750 + STATIC_POUCH_EXTRA;
    private static final int SINGLE_START = 940 + STATIC_POUCH_EXTRA + MOTION_POUCH_EXTRA;
    private static final int FINAL_STAGE_OFFSET = STATIC_POUCH_EXTRA + MOTION_POUCH_EXTRA + SINGLE_POUCH_EXTRA;
    private static final double POUCH_TOSS_SECONDS = .70, POUCH_HEFT_SECONDS = 1.0;
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final RenderFrameCapture movie = new RenderFrameCapture(root);
    private final JsonArray checks = new JsonArray(), scenes = new JsonArray(), screenshots = new JsonArray();
    private final AtomicInteger pendingShots = new AtomicInteger();
    private volatile boolean serverReady;
    private volatile Throwable failure;
    private boolean initialized, done, recording, oldPause, oldVsync, oldHidden, oldActive, oldRightSleeve, oldLeftSleeve, oldCombatShoulder;
    private boolean checkLegacyFrame, staleSwitchPending;
    private int pouchKeyframeMask, coinKeyframeMask;
    private net.neoforged.neoforge.common.ModConfigSpec.BooleanValue combatShoulder;
    private int warmup, ticks, nativeFrames, oldScale, oldFps, oldDistance, oldFov;
    private int vanillaEvents, vanillaCanceled, outgoingEvents, outgoingFallback, staleIntentFrames;
    private long outgoingNativeCount, legacyBeforeFrame, legacyBeforeCase;
    private final java.util.Set<String> pouchSupportArms = new java.util.HashSet<>();
    private int pouchSupportSamples, pouchContactSamples, pouchFlightSamples;
    private double maximumPouchContactDistance, maximumPouchSideOffset, minimumPouchCenterAbovePalm = Double.POSITIVE_INFINITY,
            minimumPouchUpY = Double.POSITIVE_INFINITY, maximumPouchFlightGap, maximumPouchFlightResidual;
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
        check((named.startsWith("FSRPG Imported Item Hands Review ") || named.startsWith("FSRPG Pouch Sequence Review ")) && named.equals(world.getFileName().toString())
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
            verifyPouchSupport(values);
            verifyNaturalPouchArm(values);
            check(true, "Actual submitted pouch bottom rests above the naturally slanted native hand after toss and two hefts");
        }
        if (name.endsWith("_coin")) {
            var coin = coinSample(values);
            check(coin.settled() && coin.contactDistance() < .00002,
                    "Settled official coin bottom returns to its actual native hand " + name);
        }
    }

    private static double[] point(JsonObject values, String name) {
        var value = values.getAsJsonObject(name);
        if (value == null) throw new AssertionError("Missing actual pouch support point: " + name);
        double[] result = {number(value, "x"), number(value, "y"), number(value, "z")};
        for (double coordinate : result) if (!Double.isFinite(coordinate)) throw new AssertionError("Non-finite actual pouch point: " + name);
        return result;
    }

    /** Independently compare actual submitted model points against the actual native palm point. */
    private void verifyPouchSupport(JsonObject values) {
        if (!text(values, "lastItemId").equals("fsrpg:money_pouch")) throw new AssertionError("Pouch support diagnostics do not describe the last rendered item");
        var palm = point(values, "lastPouchPalmSupport"); var base = point(values, "lastPouchBase");
        var center = point(values, "lastPouchCenter"); var up = point(values, "lastPouchUp");
        double dx = base[0] - palm[0], dy = base[1] - palm[1], dz = base[2] - palm[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz), lateral = Math.abs(dx);
        double upLength = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        double bodyHeight = (center[0] - base[0]) * up[0] + (center[1] - base[1]) * up[1] + (center[2] - base[2]) * up[2];
        double lift = number(values, "lastPouchFlightLift"), gap = dx * up[0] + dy * up[1] + dz * up[2];
        double rx = dx - up[0] * lift, ry = dy - up[1] * lift, rz = dz - up[2] * lift;
        double residual = Math.sqrt(rx * rx + ry * ry + rz * rz);
        boolean flying = lift > .00001;
        if (!Double.isFinite(lift) || lift < 0 || residual > .00002 || Math.abs(gap - lift) > .00002 || Math.abs(upLength - 1) > .001)
            throw new AssertionError("Actual pouch separation must equal its intentional flight above the native hand: gap=" + gap + ", lift=" + lift + ", residual=" + residual);
        if (values.get("lastPouchInFlight").getAsBoolean() != flying || Math.abs(number(values, "lastPouchFlightGap") - gap) > .00002)
            throw new AssertionError("Pouch flight diagnostics disagree with actual submitted bottom/support positions");
        if (!flying && distance > .00003) throw new AssertionError("Pouch did not return to its actual supporting hand: " + distance);
        if (!(bodyHeight > .025 && center[1] > palm[1] && up[1] > .8))
            throw new AssertionError("Pouch body must stand above its palm support, not hang from its side or top: height=" + bodyHeight + ", upY=" + up[1]);
        if (Math.abs(center[0] - base[0]) > (center[1] - base[1]) * .5 + .005)
            throw new AssertionError("Pouch body is displaced sideways from the supporting palm");
        if (Math.abs(number(values, "lastPouchContactDistance") - distance) > .00002
                || Math.abs(Math.abs(number(values, "lastPouchSideOffset")) - lateral) > .00002)
            throw new AssertionError("Pouch contact diagnostic does not match independently recomputed submitted points");
        if (!text(values, "lastPouchSupportMode").equals("base_on_native_edge")) throw new AssertionError("Pouch must use the actual naturally slanted native hand support");
        String arm = text(values, "lastPouchArm");
        if (!arm.equals("LEFT") && !arm.equals("RIGHT")) throw new AssertionError("Unknown pouch support arm " + arm);
        pouchSupportArms.add(arm); pouchSupportSamples++;
        if (flying) pouchFlightSamples++;
        else {
            pouchContactSamples++; maximumPouchContactDistance = Math.max(maximumPouchContactDistance, distance);
            maximumPouchSideOffset = Math.max(maximumPouchSideOffset, lateral);
        }
        maximumPouchFlightGap = Math.max(maximumPouchFlightGap, gap); maximumPouchFlightResidual = Math.max(maximumPouchFlightResidual, residual);
        minimumPouchCenterAbovePalm = Math.min(minimumPouchCenterAbovePalm, bodyHeight); minimumPouchUpY = Math.min(minimumPouchUpY, up[1]);
    }

    private void verifyNaturalPouchArm(JsonObject values) {
        var normal = point(values, "lastPouchSupportNormal"); var direction = point(values, "lastPouchArmDirection");
        double alignment = normal[0] * direction[0] + normal[1] * direction[1] + normal[2] * direction[2];
        check(normal[1] > .25 && normal[1] < .8 && Math.abs(normal[0]) > .15 && Math.abs(normal[2]) > .35
                        && alignment > .999 && alignment < 1.001,
                "Actual resting skin arm retains its native diagonal angle instead of a horizontalized supporting fist");
        check(!values.get("lastPouchInFlight").getAsBoolean() && number(values, "lastPouchContactDistance") < .00002,
                "After the complete sequence the pouch bottom is back on the native hand");
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
        int extra = elapsed < 50 + STATIC_POUCH_EXTRA ? STATIC_POUCH_EXTRA : 0;
        int adjusted = elapsed - (extra == 0 ? STATIC_POUCH_EXTRA : 0);
        int index = extra == 0 ? adjusted / 50 : 0, local = extra == 0 ? adjusted % 50 : elapsed;
        String name = ImportedItems.IDS.get(index);
        if (local == 0) { beforeStatic = diagnostic(); equip(name, 1); }
        if (local == 35 + extra) {
            var values = diagnostic(); verifySettled(name, values, HumanoidArm.RIGHT);
            for (var key : List.of("armSubmissions", "itemSubmissions", "mainHandSubmissions", "offHandSubmissions"))
                check(count(values, key) > count(beforeStatic, key) + 10, "Repeated actual native arm/item submissions " + name + "/" + key);
            settledStatic = values; scene("static-" + name, values); request("static-" + index + "-" + name + "-both-hands");
        }
        if (local == 40 + extra) equip(name, item(name).getMaxStackSize() > 1 ? 2 : 1);
        if (local == 49 + extra) {
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
        boolean pouch = elapsed < 60 + MOTION_POUCH_EXTRA;
        int adjusted = elapsed - (pouch ? 0 : MOTION_POUCH_EXTRA);
        int index = pouch ? 0 : adjusted / 60, local = pouch ? elapsed : adjusted % 60;
        String name = MOVING_ITEMS.get(index);
        if (local == 0) {
            equip(null, 1); movingSettled = null;
            if (pouch) pouchKeyframeMask = 0;
            if (name.equals("gold_coin")) coinKeyframeMask = 0;
        }
        if (local == 4) {
            movie.start("equip-" + name.replace('_', '-'), "actual first-person both-hand equip: " + name, this::captureObservation);
            recording = true;
        }
        if (local == 8) equip(name, 1);
        if (local == 21 && name.equals("white_adventurer_badge")) request("motion-" + name + "-active");
        if (local == (pouch ? 70 : 44)) {
            movingSettled = diagnostic(); verifySettled(name, movingSettled, HumanoidArm.RIGHT);
            scene("equip-settled-" + name, movingSettled);
            if (!name.equals("gold_coin")) request("motion-" + name + "-settled");
        }
        if (local == (pouch ? 73 : 47)) equip(name, item(name).getMaxStackSize() > 1 ? 3 : 1);
        if (local == (pouch ? 79 : 55)) sameSerial(movingSettled, diagnostic(), "Recorded settled equip stays still after stack replacement " + name);
        if (local == 59 + (pouch ? MOTION_POUCH_EXTRA : 0)) { movie.stop(); recording = false; }
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
            if (ticks >= 60 && ticks < 590) staticItems(game, ticks - 60);
            if (ticks == 600) { dominant(HumanoidArm.LEFT); equip("white_adventurer_badge", 1); }
            if (ticks == 640) { var values = diagnostic(); verifySettled("white_adventurer_badge", values, HumanoidArm.LEFT); scene("left-dominant-both-hands", values); request("11-left-dominant-native-arms"); }
            if (ticks == 655) { contextBaseline = diagnostic(); game.options.setCameraType(CameraType.THIRD_PERSON_FRONT); }
            if (ticks == 675) { sameSubmissions(contextBaseline, diagnostic(), "Third person never invokes the custom first-person presentation"); request("12-third-person-unchanged"); }
            if (ticks == 680) { grid = new ImportedAssetsScreen(); game.setScreen(grid); contextBaseline = diagnostic(); }
            if (ticks == 700) {
                check(grid.drawnFrames() > 10, "GUI continues rendering after equip animations");
                check(grid.observations.equals(originalGuiModels), "GUI model IDs, textures and native bounds stay identical after first-person presentation");
            }
            if (ticks == 705) {
                game.setScreen(null); game.options.setCameraType(CameraType.FIRST_PERSON); dominant(HumanoidArm.RIGHT);
                server(player -> { player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE)); player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_SWORD)); });
            }
            if (ticks == 730) vanillaBaseline = diagnostic();
            if (ticks == 755) {
                sameSubmissions(vanillaBaseline, diagnostic(), "Vanilla items use their original rendering without imported-arm submissions");
                check(vanillaEvents > 30 && vanillaCanceled == 0, "Actual vanilla RenderHandEvents remain uncanceled after switching away");
                request("13-vanilla-fallback"); scene("vanilla-fallback", diagnostic());
            }
            if (ticks >= 780 && ticks < 982) movingItems(ticks - MOTION_START);
            if (ticks >= 992 && ticks < 1172) {
                int elapsed = ticks - SINGLE_START;
                boolean pouch = elapsed < 50 + SINGLE_POUCH_EXTRA;
                int adjusted = elapsed - (pouch ? 0 : SINGLE_POUCH_EXTRA);
                int index = pouch ? 0 : adjusted / 50, local = pouch ? elapsed : adjusted % 50;
                String name = MOVING_ITEMS.get(index);
                if (local == 0) server(player -> { player.setItemSlot(EquipmentSlot.MAINHAND, item(name)); player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY); });
                if (local == 40 + (pouch ? SINGLE_POUCH_EXTRA : 0)) {
                    var values = diagnostic();
                    check(id(game.player.getMainHandItem()).equals("fsrpg:" + name) && game.player.getOffhandItem().isEmpty(), "Actual single-main-hand view " + name);
                    check(text(hand(values, "mainHand"), "itemId").equals("fsrpg:" + name) && hand(values, "mainHand").get("settled").getAsBoolean(), "Single-main-hand object settles on its native hand " + name);
                    if (name.equals("money_pouch")) {
                        verifyPouchSupport(values); verifyNaturalPouchArm(values);
                        check(text(values, "lastPouchArm").equals("RIGHT"), "Single-main-hand pouch rests on the actual right palm");
                    }
                    scene("single-main-hand-" + name, values); request("single-main-hand-" + name);
                }
            }
            if (ticks == 1172) { game.options.setModelPart(PlayerModelPart.RIGHT_SLEEVE, false); game.options.broadcastOptions(); }
            if (ticks == 1192) {
                check(!game.player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE), "Sleeve visibility change reaches the real player");
                check(!diagnostic().get("nativeArmSleeveVisible").getAsBoolean(), "Native renderer honors the hidden sleeve layer");
                scene("native-sleeve-hidden", diagnostic()); request("native-sleeve-hidden");
            }
            if (ticks == 1197) { game.options.setModelPart(PlayerModelPart.RIGHT_SLEEVE, true); game.options.broadcastOptions(); }
            if (ticks == 1212) server(player -> player.setInvisible(true));
            if (ticks == 1227) { check(game.player.isInvisible(), "Invisible state is synchronized from the server"); invisibleBaseline = diagnostic(); }
            if (ticks == 1247) {
                var values = diagnostic();
                check(count(values, "armSubmissions") == count(invisibleBaseline, "armSubmissions"), "Invisible player never submits a native skin arm");
                check(count(values, "itemSubmissions") > count(invisibleBaseline, "itemSubmissions") + 10, "Invisible player's official held item continues rendering");
                scene("invisible-item-without-arm", values); request("invisible-item-without-arm");
            }
            if (ticks == 1252) server(player -> player.setInvisible(false));
            if (ticks == 1282) {
                check(!game.player.isInvisible() && count(diagnostic(), "armSubmissions") > count(invisibleBaseline, "armSubmissions") + 10, "Native arms resume after invisibility ends");
                CombatCamera.reset(); game.options.setCameraType(CameraType.FIRST_PERSON); game.player.getInventory().setSelectedSlot(0);
                server(this::equipLegacyWeapon);
            }
            if (ticks == 1312) {
                var intent = localIntent();
                check(ClientState.drawn() && intent != null && intent.model().startsWith("legacy_"), "Actual server-drawn legacy weapon reaches first-person presentation");
                check(game.options.getCameraType().isFirstPerson(), "Legacy compatibility check remains in first person with automatic shoulder camera disabled");
                legacyBeforeCase = LegacyFirstPersonAnimations.samples(); contextBaseline = diagnostic();
            }
            if (ticks == 1332) {
                check(LegacyFirstPersonAnimations.samples() > legacyBeforeCase + 10, "Legacy weapon's original authored arm pair continues rendering");
                sameSubmissions(contextBaseline, diagnostic(), "Legacy weapon plus resource offhand does not add a second native arm pair");
                request("legacy-weapon-before-resource-switch"); scene("legacy-weapon-with-resource-offhand", diagnostic());
            }
            if (ticks == 1337) staleSwitchPending = true;
            if (ticks == 1342) server(player -> player.getInventory().setSelectedSlot(1));
            if (ticks == 1382) {
                verifySettled("white_adventurer_badge", diagnostic(), HumanoidArm.RIGHT);
                check(staleIntentFrames > 0, "Real predicted hotbar switch exercised frames with an outgoing legacy intent");
                request("resource-after-live-legacy-switch"); scene("resource-owns-stale-legacy-intent", diagnostic());
            }
            if (ticks == 1392) { game.player.getInventory().setSelectedSlot(0); server(this::equipLegacyWeapon); }
            if (ticks == 1422) {
                check(ClientState.drawn() && game.options.getCameraType().isFirstPerson(), "Switching back restores the drawn weapon in first person");
                legacyBeforeCase = LegacyFirstPersonAnimations.samples(); contextBaseline = diagnostic();
            }
            if (ticks == 1442) {
                check(LegacyFirstPersonAnimations.samples() > legacyBeforeCase + 10, "Returning from a resource resumes the original legacy animation renderer");
                sameSubmissions(contextBaseline, diagnostic(), "Returning weapon does not duplicate the imported native arms");
                request("legacy-weapon-after-resource-switch"); scene("legacy-weapon-restored", diagnostic());
            }
            if (ticks >= 1462 && capture == null && pendingShots.get() == 0 && movie.finish()) {
                validateCaptures(); finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }

    private void observeHand(RenderHandEvent event) {
        if (!initialized || done) return;
        var game = Minecraft.getInstance();
        if (ticks >= 60 && ticks <= 725 && ImportedItemPresentation.supports(event.getItemStack())
                && !ItemStack.isSameItemSameComponents(event.getItemStack(), game.player.getItemInHand(event.getHand()))) {
            outgoingEvents++; var values = diagnostic(); long submitted = count(values, "outgoingItemSubmissions");
            if (!event.isCanceled() || !values.get("lastOutgoing").getAsBoolean() || submitted <= outgoingNativeCount) outgoingFallback++;
            outgoingNativeCount = submitted;
        }
        if (ticks >= 730 && ticks <= 755 && (event.getItemStack().is(Items.APPLE) || event.getItemStack().is(Items.DIAMOND_SWORD))) {
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
        checkLegacyFrame = ticks >= 1282 && ImportedItemPresentation.supports(game.player.getMainHandItem());
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
            double supportMinY = Double.POSITIVE_INFINITY, supportMaxY = Double.NEGATIVE_INFINITY,
                    actualDipY = Double.POSITIVE_INFINITY, actualApexY = Double.NEGATIVE_INFINITY, actualCatchY = Double.POSITIVE_INFINITY;
            double actualDipAt = -1, actualApexAt = -1, actualCatchAt = -1;
            boolean sawSettled = false;
            long serial = -1; String expected = clipId.substring("equip-".length()).replace('-', '_');
            int activeSamples = 0, pouchContactFrames = 0, pouchSupportMotionFrames = 0;
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
                        if (text(nativeHands, "lastItemId").equals("fsrpg:money_pouch")) {
                            verifyPouchSupport(nativeHands); pouchContactFrames++;
                            String supportArm = text(nativeHands, "lastPouchArm");
                            var supportState = text(main, "arm").equals(supportArm) ? main : hand(nativeHands, "offHand");
                            double elapsed = number(supportState, "equipSeconds") - POUCH_TOSS_SECONDS;
                            if (elapsed >= .15 && elapsed <= 1.0) {
                                // Use the submitted camera-space point, after every wrist/pivot transform.
                                // The first .15 s is excluded because vanilla is still raising the incoming hand.
                                double y = point(nativeHands, "lastPouchPalmSupport")[1];
                                pouchSupportMotionFrames++; supportMinY = Math.min(supportMinY, y); supportMaxY = Math.max(supportMaxY, y);
                                if (elapsed <= .32 && y < actualDipY) { actualDipY = y; actualDipAt = current; }
                                if (elapsed >= .38 && elapsed <= .65 && y > actualApexY) { actualApexY = y; actualApexAt = current; }
                                if (elapsed >= .70 && elapsed <= .95 && y < actualCatchY) { actualCatchY = y; actualCatchAt = current; }
                            }
                        }
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
            if (expected.endsWith("coin")) {
                check(maximumLift > .05 && maximumSpin > 90, "Coin visibly rises and spins before landing " + clipId);
                verifyCoinSequence(clip);
            }
            else if (expected.equals("money_pouch")) {
                check(maximumArmLift > .075 && minimumArmLift < -.025, "Pouch and native hand visibly load downward then heft upward");
                check(pouchDipAt >= 0 && pouchDipAt < pouchHeftAt && pouchHeftAt < pouchReturnAt,
                        "Actual pouch frames show anticipation, upward heft and downward return in order");
                check(maximumArmTilt > 5 && minimumArmTilt < -4, "Pouch and attached native arm tilt with the weighted heft");
                check(pouchKeyframeMask == 15, "Pouch toss apex, landing and both heft apex screenshots are actual rendered frames");
                verifyPouchSequence(clip);
                check(pouchContactFrames >= 30, "Actual pouch bottom remains on the native palm throughout at least thirty real load/heft/return frames");
                check(pouchSupportMotionFrames >= 20 && supportMaxY - supportMinY > .10,
                        "Actual submitted palm and bag bottom travel more than ten centimetres after the incoming-hand raise");
                check(actualDipAt >= 0 && actualDipAt < actualApexAt && actualApexAt < actualCatchAt
                                && actualApexY - actualDipY > .10 && actualApexY - actualCatchY > .08,
                        "Actual camera-space bag-bottom motion visibly loads down, hefts up and cushions the return without wrist-pivot cancellation");
            }
            var note = new JsonObject(); note.addProperty("id", clipId); note.addProperty("maximumItemLift", maximumLift); note.addProperty("minimumItemLift", minimumLift); note.addProperty("maximumArmLift", maximumArmLift); note.addProperty("minimumArmLift", minimumArmLift); note.addProperty("maximumArmTiltDegrees", maximumArmTilt); note.addProperty("minimumArmTiltDegrees", minimumArmTilt); note.addProperty("pouchDipAtSeconds", pouchDipAt); note.addProperty("pouchHeftAtSeconds", pouchHeftAt); note.addProperty("pouchReturnAtSeconds", pouchReturnAt); note.addProperty("maximumSpinDegrees", maximumSpin); note.addProperty("observedEquipSerial", serial);
            if (expected.equals("money_pouch")) {
                note.addProperty("actualSupportMotionFrames", pouchSupportMotionFrames); note.addProperty("actualSupportMinimumY", supportMinY);
                note.addProperty("actualSupportMaximumY", supportMaxY); note.addProperty("actualSupportVerticalRangeMeters", supportMaxY - supportMinY);
                note.addProperty("actualDipY", actualDipY); note.addProperty("actualApexY", actualApexY); note.addProperty("actualCatchY", actualCatchY);
                note.addProperty("actualDipAtSeconds", actualDipAt); note.addProperty("actualApexAtSeconds", actualApexAt); note.addProperty("actualCatchAtSeconds", actualCatchAt);
            }
            scenes.add(note);
        }
        check(totalSeconds <= 10, "Dynamic recording is bounded to ten source seconds total");
        check(pouchSupportArms.containsAll(java.util.Set.of("LEFT", "RIGHT")), "Actual submitted bag-bottom support is verified on both native palms");
        check(outgoingEvents > 0 && outgoingFallback == 0, "Every real outgoing cached resource keeps its native arm/item presentation until vanilla replacement");
        check(screenshots.size() == 33, "GUI, ten both-hand items, handedness, third-person, fallback, eleven motion keyframes, three single-hand views, sleeve, invisible and three legacy-switch views captured");
        for (var screenshot : screenshots) check(Files.size(root.resolve(text(screenshot.getAsJsonObject(), "file"))) > 1000,
                "Native screenshot written " + text(screenshot.getAsJsonObject(), "file"));
    }

    /** Read actual submitted hand and bag positions, not only the authored curve. */
    private void verifyPouchSequence(JsonObject clip) {
        double flightAt = -1, landingAt = -1, maximumGap = 0;
        int flightFrames = 0, contactFrames = 0;
        double[] dip = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}, peak = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY},
                cushion = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}, dipAt = {-1, -1}, peakAt = {-1, -1}, cushionAt = {-1, -1};
        for (var frameValue : clip.getAsJsonArray("frames")) {
            var frame = frameValue.getAsJsonObject(); double time = number(frame, "capturedAtSeconds");
            var values = frame.getAsJsonObject("observation").getAsJsonObject("nativeHands");
            var main = hand(values, "mainHand");
            if (!text(values, "lastItemId").equals("fsrpg:money_pouch") || !text(main, "itemId").equals("fsrpg:money_pouch")) continue;
            var state = text(main, "arm").equals(text(values, "lastPouchArm")) ? main : hand(values, "offHand");
            double elapsed = number(state, "equipSeconds"), gap = number(values, "lastPouchFlightGap");
            maximumGap = Math.max(maximumGap, gap);
            if (gap > .05) { flightFrames++; if (flightAt < 0) flightAt = time; }
            if (gap < .00002 && elapsed > .80 * POUCH_TOSS_SECONDS) {
                contactFrames++; if (landingAt < 0) landingAt = time;
            }
            if (elapsed >= POUCH_TOSS_SECONDS && elapsed < POUCH_TOSS_SECONDS + 2 * POUCH_HEFT_SECONDS) {
                double afterLanding = elapsed - POUCH_TOSS_SECONDS;
                int heft = Math.min(1, (int) (afterLanding / POUCH_HEFT_SECONDS));
                double phase = afterLanding / POUCH_HEFT_SECONDS - heft;
                if (gap > .00002) throw new AssertionError("Pouch leaves hand during a post-landing heft");
                double y = point(values, "lastPouchPalmSupport")[1];
                if (phase >= .12 && phase <= .34 && y < dip[heft]) { dip[heft] = y; dipAt[heft] = time; }
                if (phase >= .38 && phase <= .65 && y > peak[heft]) { peak[heft] = y; peakAt[heft] = time; }
                if (phase >= .70 && phase <= .95 && y < cushion[heft]) { cushion[heft] = y; cushionAt[heft] = time; }
            }
        }
        check(maximumGap > .20 && flightFrames >= 8 && flightAt >= 0 && landingAt > flightAt,
                "Actual pouch first flies over twenty centimetres above the hand, then lands");
        check(contactFrames >= 60, "Actual pouch stays supported during the two hefts and settled rest");
        var sequence = new JsonObject(); sequence.addProperty("id", "pouch-toss-then-two-hefts");
        sequence.addProperty("maximumFlightGapMeters", maximumGap); sequence.addProperty("flightAtSeconds", flightAt);
        sequence.addProperty("landingAtSeconds", landingAt); sequence.addProperty("flightFrames", flightFrames);
        sequence.addProperty("contactFrames", contactFrames); var hefts = new JsonArray();
        for (int i = 0; i < 2; i++) {
            check(dipAt[i] > landingAt && dipAt[i] < peakAt[i] && peakAt[i] < cushionAt[i]
                            && peak[i] - dip[i] > .10 && peak[i] - cushion[i] > .08,
                    "Actual hand and pouch bottom perform down/up/cushion heft " + (i + 1));
            var h = new JsonObject(); h.addProperty("number", i + 1); h.addProperty("dipY", dip[i]); h.addProperty("peakY", peak[i]);
            h.addProperty("catchY", cushion[i]); h.addProperty("dipAtSeconds", dipAt[i]); h.addProperty("peakAtSeconds", peakAt[i]);
            h.addProperty("catchAtSeconds", cushionAt[i]); h.addProperty("verticalRangeMeters", peak[i] - dip[i]); hefts.add(h);
        }
        check(cushionAt[0] < dipAt[1], "Second heft follows first heft completion");
        sequence.add("hefts", hefts); scenes.add(sequence);
    }

    /** Coin spin is about its center, so its landing anchor uses the unspun flight axis. */
    private record CoinSample(double elapsed, double gap, double palmY, double contactDistance,
                              double flightResidual, double armTilt, double armLift, double spin,
                              boolean settled, boolean pending, boolean independentFlight) {}

    private static CoinSample coinSample(JsonObject values) {
        if (!text(values, "lastItemId").endsWith("_coin") || values.get("lastOutgoing").getAsBoolean())
            throw new AssertionError("Coin evidence must describe the currently submitted incoming coin");
        var main = hand(values, "mainHand");
        var state = text(main, "arm").equals(text(values, "lastCoinArm")) ? main : hand(values, "offHand");
        var center = point(values, "lastCoinCenter"); var landing = point(values, "lastCoinLandingPoint");
        var palm = point(values, "lastCoinPalmSupport"); var up = point(values, "lastCoinUp");
        var frameOffset = point(values, "lastCoinFrameOffset");
        boolean independent = values.get("lastCoinIndependentFlight").getAsBoolean();
        double half = number(values, "lastCoinHalfHeight"), lift = number(values, "lastCoinFlightLift"),
                offset = number(values, "lastCoinFlightOffset"), elapsed = number(values, "lastCoinElapsedSeconds");
        double dx = center[0] - up[0] * half - landing[0] - frameOffset[0], dy = center[1] - up[1] * half - landing[1] - frameOffset[1],
                dz = center[2] - up[2] * half - landing[2] - frameOffset[2];
        double gap = dx * up[0] + dy * up[1] + dz * up[2];
        double rx = dx - up[0] * (lift + offset), ry = dy - up[1] * (lift + offset), rz = dz - up[2] * (lift + offset);
        double residual = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double px = center[0] - up[0] * half - palm[0], py = center[1] - up[1] * half - palm[1],
                pz = center[2] - up[2] * half - palm[2];
        double length = up[0] * up[0] + up[1] * up[1] + up[2] * up[2];
        for (int axis = 0; axis < 3; axis++) {
            double expected = independent ? 0 : palm[axis] - landing[axis];
            if (Math.abs(frameOffset[axis] - expected) > .00002)
                throw new AssertionError("Actual coin frame must be independent during flight and attached to the moving native palm after catch");
        }
        if (!Double.isFinite(half) || half <= 0 || Math.abs(length - 1) > .001 || residual > .00002
                || Math.abs(elapsed - number(state, "equipSeconds")) > .000001
                || Math.abs(number(values, "lastCoinArmTilt") - number(state, "armTiltDegrees")) > .000001
                || Math.abs(number(values, "lastCoinArmLift") - number(state, "armLift")) > .000001)
            throw new AssertionError("Actual coin center, native hand and unspun flight diagnostics disagree: residual=" + residual);
        return new CoinSample(elapsed, gap, palm[1], Math.sqrt(px * px + py * py + pz * pz), residual,
                number(values, "lastCoinArmTilt"), number(values, "lastCoinArmLift"), number(state, "itemSpinDegrees"),
                state.get("settled").getAsBoolean(), state.get("pending").getAsBoolean(), independent);
    }

    private void verifyCoinSequence(JsonObject clip) {
        double incomingAt = -1, flightAt = -1, flightElapsed = -1, caughtAt = -1, settledAt = -1, settledElapsed = -1;
        double maximumGap = 0, maximumResidual = 0, maximumLaunchTilt = Double.NEGATIVE_INFINITY,
                minimumLaunchTilt = Double.POSITIVE_INFINITY, lowestFlightPalmY = Double.POSITIVE_INFINITY,
                highestCatchPalmY = Double.NEGATIVE_INFINITY, lowestFlightAt = -1, highestCatchAt = -1,
                maximumFlightContactDistance = 0, maximumRestContactDistance = 0;
        int actualFrames = 0, earlyFlightFrames = 0, settledFrames = 0, catchCushionFrames = 0;
        for (var frameValue : clip.getAsJsonArray("frames")) {
            var frame = frameValue.getAsJsonObject(); double time = number(frame, "capturedAtSeconds");
            var values = frame.getAsJsonObject("observation").getAsJsonObject("nativeHands");
            if (!text(values, "lastItemId").equals("fsrpg:gold_coin") || values.get("lastOutgoing").getAsBoolean()) continue;
            var sample = coinSample(values); actualFrames++;
            if (sample.pending()) throw new AssertionError("Incoming coin waits on a raised resting hand before starting its throw");
            if (incomingAt < 0) incomingAt = time;
            maximumGap = Math.max(maximumGap, sample.gap()); maximumResidual = Math.max(maximumResidual, sample.flightResidual());
            if (sample.gap() > .02 && flightAt < 0) { flightAt = time; flightElapsed = sample.elapsed(); }
            if (sample.elapsed() < .10 && sample.gap() > .02) earlyFlightFrames++;
            if (sample.elapsed() < .13) {
                maximumLaunchTilt = Math.max(maximumLaunchTilt, sample.armTilt());
                minimumLaunchTilt = Math.min(minimumLaunchTilt, sample.armTilt());
            }
            if (sample.elapsed() >= .17 && sample.elapsed() <= .24) {
                if (!sample.independentFlight()) throw new AssertionError("Coin must fly independently while its hand recoils beneath it");
                if (sample.palmY() < lowestFlightPalmY) { lowestFlightPalmY = sample.palmY(); lowestFlightAt = time; }
                maximumFlightContactDistance = Math.max(maximumFlightContactDistance, sample.contactDistance());
            }
            if (!sample.independentFlight() && sample.contactDistance() > .00002)
                throw new AssertionError("Caught coin separates from the actual native palm during its cushion or rest");
            if (!sample.independentFlight() && !sample.settled() && sample.armLift() < -.005) catchCushionFrames++;
            if (sample.elapsed() >= .44 && sample.elapsed() <= .56 && !sample.independentFlight() && sample.contactDistance() < .00002) {
                if (caughtAt < 0) caughtAt = time;
                if (sample.palmY() > highestCatchPalmY) { highestCatchPalmY = sample.palmY(); highestCatchAt = time; }
            }
            if (sample.settled()) {
                if (settledAt < 0) { settledAt = time; settledElapsed = sample.elapsed(); }
                settledFrames++; maximumRestContactDistance = Math.max(maximumRestContactDistance, sample.contactDistance());
            }
        }
        check(actualFrames > 30 && earlyFlightFrames > 0 && flightElapsed >= 0 && flightElapsed < .10
                        && flightAt - incomingAt < .10,
                "Actual incoming coin is already airborne within one tenth of a second, with no held-still pre-throw wait");
        check(maximumLaunchTilt > 10 && minimumLaunchTilt < -10 && maximumLaunchTilt - minimumLaunchTilt > 25,
                "Actual native wrist gives a clear backward-to-forward launch impulse");
        check(maximumGap > .16 && maximumFlightContactDistance > .15 && maximumResidual < .00002,
                "Coin center follows an independent actual flight above the catch target while the hand drops beneath it");
        check(lowestFlightAt > flightAt && highestCatchAt > lowestFlightAt && highestCatchPalmY - lowestFlightPalmY > .06,
                "Actual native hand rises from its flight low point to meet the descending coin");
        check(caughtAt > flightAt && settledAt > caughtAt && settledElapsed < .70 && settledAt - incomingAt < .70,
                "Fast toss, catch and complete settling finish within seven tenths of a source second");
        check(settledFrames >= 20 && maximumRestContactDistance < .00002,
                "After the quick catch the actual official coin bottom remains on its native hand");
        check(catchCushionFrames >= 2, "Caught coin stays attached during the native hand's visible downward cushion");
        check(coinKeyframeMask == 15, "Coin launch, independent flight, catch and settled screenshots are actual rendered frames");
        var result = new JsonObject(); result.addProperty("id", "coin-fast-launch-then-native-hand-catch");
        result.addProperty("firstActualIncomingAtSeconds", incomingAt); result.addProperty("firstAirborneAtSeconds", flightAt);
        result.addProperty("firstAirborneEquipSeconds", flightElapsed); result.addProperty("catchAtSeconds", caughtAt);
        result.addProperty("settledAtSeconds", settledAt); result.addProperty("settledEquipSeconds", settledElapsed);
        result.addProperty("actualCompleteDurationSeconds", settledAt - incomingAt); result.addProperty("maximumFlightGapMeters", maximumGap);
        result.addProperty("maximumFlightResidualMeters", maximumResidual); result.addProperty("maximumRestContactDistanceMeters", maximumRestContactDistance);
        result.addProperty("minimumLaunchWristDegrees", minimumLaunchTilt); result.addProperty("maximumLaunchWristDegrees", maximumLaunchTilt);
        result.addProperty("lowestFlightPalmY", lowestFlightPalmY); result.addProperty("highestCatchPalmY", highestCatchPalmY);
        result.addProperty("actualHandReturnHeightMeters", highestCatchPalmY - lowestFlightPalmY);
        result.addProperty("lowestFlightPalmAtSeconds", lowestFlightAt); result.addProperty("highestCatchPalmAtSeconds", highestCatchAt);
        result.addProperty("actualCoinFrames", actualFrames); result.addProperty("earlyAirborneFrames", earlyFlightFrames);
        result.addProperty("settledFrames", settledFrames); result.addProperty("catchCushionFrames", catchCushionFrames); scenes.add(result);
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
                var values = diagnostic(); var main = hand(values, "mainHand");
                if (capture == null && main != null && text(main, "itemId").equals("fsrpg:money_pouch")) {
                    double elapsed = number(main, "equipSeconds"), lift = number(main, "itemLift"), armLift = number(main, "armLift");
                    if ((pouchKeyframeMask & 1) == 0 && lift > .21) { request("motion-money_pouch-toss-apex"); pouchKeyframeMask |= 1; }
                    else if ((pouchKeyframeMask & 2) == 0 && elapsed > .58 && elapsed < .70 && lift < .00001) { request("motion-money_pouch-landing"); pouchKeyframeMask |= 2; }
                    else if ((pouchKeyframeMask & 4) == 0 && elapsed > .70 && elapsed < 1.70 && armLift > .095) { request("motion-money_pouch-heft-1"); pouchKeyframeMask |= 4; }
                    else if ((pouchKeyframeMask & 8) == 0 && elapsed > 1.70 && armLift > .095) { request("motion-money_pouch-heft-2"); pouchKeyframeMask |= 8; }
                }
                if (capture == null && text(values, "lastItemId").equals("fsrpg:gold_coin") && !values.get("lastOutgoing").getAsBoolean()) {
                    var sample = coinSample(values); String keyframe = null;
                    if ((coinKeyframeMask & 1) == 0 && sample.elapsed() >= .06 && sample.elapsed() <= .11 && sample.armTilt() > 5 && sample.gap() > .02) {
                        keyframe = "launch"; coinKeyframeMask |= 1;
                    } else if ((coinKeyframeMask & 2) == 0 && sample.elapsed() >= .17 && sample.elapsed() <= .24 && sample.gap() > .14) {
                        keyframe = "flying"; coinKeyframeMask |= 2;
                    } else if ((coinKeyframeMask & 4) == 0 && sample.elapsed() >= .44 && sample.elapsed() <= .51 && !sample.independentFlight() && sample.contactDistance() < .00002 && sample.spin() >= 359.9) {
                        keyframe = "catch"; coinKeyframeMask |= 4;
                    } else if ((coinKeyframeMask & 8) == 0 && sample.elapsed() >= .55 && sample.elapsed() < .70 && sample.settled() && sample.contactDistance() < .00002) {
                        keyframe = "settled"; coinKeyframeMask |= 8;
                    }
                    if (keyframe != null) { request("motion-gold_coin-" + keyframe); scene("coin-keyframe-" + keyframe, values); }
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
            var support = new JsonObject(); support.addProperty("verifiedActualSubmissionSamples", pouchSupportSamples);
            support.addProperty("supportedSamples", pouchContactSamples); support.addProperty("flightSamples", pouchFlightSamples);
            support.addProperty("maximumFlightGapMeters", maximumPouchFlightGap); support.addProperty("maximumFlightResidualMeters", maximumPouchFlightResidual);
            support.addProperty("maximumContactDistanceMeters", maximumPouchContactDistance); support.addProperty("maximumSideOffsetMeters", maximumPouchSideOffset);
            if (pouchSupportSamples > 0) { support.addProperty("minimumBodyCenterAbovePalmMeters", minimumPouchCenterAbovePalm); support.addProperty("minimumCameraUpComponent", minimumPouchUpY); }
            support.add("nativeArms", RpgConfig.JSON.toJsonTree(pouchSupportArms)); report.add("pouchBottomSupport", support);
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

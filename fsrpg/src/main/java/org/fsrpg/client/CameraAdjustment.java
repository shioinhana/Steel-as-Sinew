package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.fml.config.ModConfigs;
import org.lwjgl.glfw.GLFW;

/** Local, persisted camera composition. No movement or interaction packet is sent here. */
public final class CameraAdjustment {
    private static KeyMapping modifier, up, down, left, right;
    private static final CameraAdjustmentIntent intent = new CameraAdjustmentIntent();
    private static final Set<InputConstants.Key> consumedBindings = new HashSet<>();
    private static final CameraAdjustmentIntent.Decision NONE = new CameraAdjustmentIntent.Decision(false, 0, 0);
    private static CameraAdjustmentIntent.Decision decision = NONE;
    private static Object player, level;
    private static Entity vehicle;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("FSRPG camera");
    private static final AsyncConfigSave saves = new AsyncConfigSave(Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "FSRPG camera config writer"); thread.setDaemon(true); return thread;
    }), System::nanoTime, TimeUnit.SECONDS.toNanos(5), error ->
            LOG.warn("Unable to save camera composition; keeping the in-memory settings and retrying", error));
    private record Desired(CameraProfilePosition ground, CameraProfilePosition mounted, boolean initialized) {}
    private static Desired desired;
    private static long adjustedTicks, hintUntil;
    private static String hintProfile = "ground";
    private static CameraProfilePosition hintPosition;
    private CameraAdjustment() {}

    static void registerKeys(RegisterKeyMappingsEvent event, KeyMapping.Category category) {
        modifier = key("modifier", GLFW.GLFW_KEY_LEFT_CONTROL, category);
        up = key("up", GLFW.GLFW_KEY_I, category); down = key("down", GLFW.GLFW_KEY_K, category);
        left = key("left", GLFW.GLFW_KEY_J, category); right = key("right", GLFW.GLFW_KEY_L, category);
        event.register(modifier); event.register(up); event.register(down); event.register(left); event.register(right);
    }
    private static KeyMapping key(String direction, int key, KeyMapping.Category category) {
        return new KeyMapping("key.fsrpg.camera_" + direction, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, category);
    }
    public static boolean mounted() {
        var p = Minecraft.getInstance().player;
        if (p == null || !p.isPassenger()) return false;
        Entity root = p.getRootVehicle();
        return root instanceof AbstractHorse || root instanceof AbstractBoat;
    }
    private static boolean eligible() {
        var game = Minecraft.getInstance();
        return game.player != null && game.level != null && game.player.isAlive() && !game.player.isSpectator()
                && game.getCameraEntity() == game.player && game.screen == null && game.isWindowActive()
                && CombatCamera.active();
    }
    private static void sync() {
        pollSaves();
        var game = Minecraft.getInstance();
        Entity nextVehicle = mounted() ? game.player.getRootVehicle() : null;
        if (player != game.player || level != game.level || vehicle != nextVehicle) {
            flush(); decision = NONE; consumedBindings.clear(); hintUntil = 0;
            player = game.player; level = game.level; vehicle = nextVehicle;
        }
    }
    private static CameraProfilePosition ground() {
        if (desired != null) return desired.ground();
        return new CameraProfilePosition(ClientOptions.SHOULDER.get(), ClientOptions.CAMERA_HEIGHT.get(), ClientOptions.CAMERA_DISTANCE.get());
    }
    private static CameraProfilePosition mountedPosition() {
        if (desired != null) return desired.initialized() ? desired.mounted() : desired.ground().forMounted(ClientOptions.MOUNTED_CAMERA_EXTRA.get());
        return initialized()
                ? new CameraProfilePosition(ClientOptions.MOUNTED_SHOULDER.get(), ClientOptions.MOUNTED_CAMERA_HEIGHT.get(), ClientOptions.MOUNTED_CAMERA_DISTANCE.get())
                : ground().forMounted(ClientOptions.MOUNTED_CAMERA_EXTRA.get());
    }
    /** Pure reads only: rendering must never perform configuration IO. */
    public static CameraProfilePosition position() { return mounted() ? mountedPosition() : ground(); }
    private static void initializeMounted() {
        if (!mounted() || initialized()) return;
        var initial = ground().forMounted(ClientOptions.MOUNTED_CAMERA_EXTRA.get());
        changed(new Desired(ground(), initial, true));
    }
    private static boolean pressed(KeyMapping key) {
        if (key == null) return false;
        boolean clicked = false; while (key.consumeClick()) clicked = true;
        return key.isDown() || clicked;
    }
    /** Vanilla consumes the advancements binding before the player's movement input event. */
    public static void beforeKeybinds(ClientTickEvent.Pre event) {
        if (!eligible() || modifier == null || !modifier.isDown()) return;
        var advancements = Minecraft.getInstance().options.keyAdvancements;
        var binding = advancements.getKey();
        if (binding.equals(up.getKey()) || binding.equals(down.getKey())
                || binding.equals(left.getKey()) || binding.equals(right.getKey())) {
            // Leave the camera mappings' own clicks intact, including a press/release between ticks.
            while (advancements.consumeClick()) { /* this Ctrl chord belongs to camera composition */ }
        }
    }
    /** Runs before native player movement, riding input and input packet creation. */
    public static void movement(MovementInputUpdateEvent event) {
        var game = Minecraft.getInstance(); if (event.getEntity() != game.player || game.player == null) return;
        sync(); consumedBindings.clear();
        boolean u = pressed(up), d = pressed(down), l = pressed(left), r = pressed(right);
        boolean allowed = eligible();
        if (allowed) initializeMounted();
        if (modifier != null) while (modifier.consumeClick()) { /* modifier is held, never a delayed click */ }
        decision = intent.sample(allowed, modifier != null && modifier.isDown(), u, d, l, r);
        if (decision.adjusting()) {
            if (u) consumedBindings.add(up.getKey()); if (d) consumedBindings.add(down.getKey());
            if (l) consumedBindings.add(left.getKey()); if (r) consumedBindings.add(right.getKey());
            var before = position(); var after = before.adjust(decision.horizontal(), decision.vertical(), ClientOptions.CAMERA_ADJUST_STEP.get(), mounted());
            if (!before.equals(after)) {
                var profiles = currentProfiles();
                changed(mounted() ? new Desired(profiles.ground(), after, profiles.initialized())
                        : new Desired(after, profiles.mounted(), profiles.initialized()));
                adjustedTicks++;
            }
            hintPosition = after; hintProfile = mounted() ? "mounted" : "ground"; hintUntil = System.nanoTime() + 1_500_000_000L;
            var keys = event.getInput().keyPresses;
            // Ctrl is also the default vanilla sprint key. A camera chord must not start sprinting;
            // independent Ctrl, Shift, jump and all native vehicle inputs keep their normal meaning.
            event.getInput().keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false);
            game.player.setSprinting(false);
        }
    }
    /** Save once at the end of a gesture (or when leaving its context), never per frame. */
    static void tick() {
        sync();
        if (!eligible()) { decision = NONE; consumedBindings.clear(); hintUntil = 0; }
        if (!decision.adjusting()) flush();
    }
    public static boolean ownsModifier() { return decision.adjusting(); }
    static boolean consumes(KeyMapping binding) { return consumedBindings.contains(binding.getKey()); }
    static void reset() { drain(); decision = NONE; consumedBindings.clear(); hintUntil = 0; player = level = null; vehicle = null; }
    public static void shutdown() { drain(); saves.shutdown(); }
    private static boolean initialized() { return desired != null ? desired.initialized() : ClientOptions.MOUNTED_CAMERA_INITIALIZED.get(); }
    private static Desired currentProfiles() {
        return desired != null ? desired : new Desired(
                new CameraProfilePosition(ClientOptions.SHOULDER.get(), ClientOptions.CAMERA_HEIGHT.get(), ClientOptions.CAMERA_DISTANCE.get()),
                new CameraProfilePosition(ClientOptions.MOUNTED_SHOULDER.get(), ClientOptions.MOUNTED_CAMERA_HEIGHT.get(), ClientOptions.MOUNTED_CAMERA_DISTANCE.get()),
                ClientOptions.MOUNTED_CAMERA_INITIALIZED.get());
    }
    private static void changed(Desired next) { desired = next; applyDesired(); saves.changed(); }
    /** A watcher may replace the loaded graph while an older generation is being written. */
    private static void applyDesired() {
        if (desired == null || !ClientOptions.SPEC.isLoaded()) return;
        ClientOptions.SHOULDER.set(desired.ground().side()); ClientOptions.CAMERA_HEIGHT.set(desired.ground().height());
        ClientOptions.CAMERA_DISTANCE.set(desired.ground().distance());
        ClientOptions.MOUNTED_SHOULDER.set(desired.mounted().side()); ClientOptions.MOUNTED_CAMERA_HEIGHT.set(desired.mounted().height());
        ClientOptions.MOUNTED_CAMERA_DISTANCE.set(desired.mounted().distance()); ClientOptions.MOUNTED_CAMERA_INITIALIZED.set(desired.initialized());
    }
    private static void pollSaves() {
        saves.poll();
        // LoadedConfig.save returns after taking the same lock used for watcher reads/reloads.
        // Earlier reloads have finished before latest-generation completion is acknowledged here.
        applyDesired();
        if (!saves.pending()) desired = null;
    }
    private static Runnable prepareSave() {
        var config = ModConfigs.getModConfigs("fsrpg").stream()
                .filter(value -> value.getSpec() == ClientOptions.SPEC).findFirst().orElseThrow();
        // Keep the complete native config, its custom values/comments and native Reloading event.
        // Both these seven updates and save must use the captured graph: a watcher can replace
        // SPEC.loadedConfig between a ConfigValue.set call and a later getLoadedConfig call.
        return AsyncConfigSave.capture(config::getLoadedConfig, CameraAdjustment::currentProfiles, (loaded, snapshot) -> {
            var values = loaded.config();
            values.set(ClientOptions.SHOULDER.getPath(), snapshot.ground().side());
            values.set(ClientOptions.CAMERA_HEIGHT.getPath(), snapshot.ground().height());
            values.set(ClientOptions.CAMERA_DISTANCE.getPath(), snapshot.ground().distance());
            values.set(ClientOptions.MOUNTED_SHOULDER.getPath(), snapshot.mounted().side());
            values.set(ClientOptions.MOUNTED_CAMERA_HEIGHT.getPath(), snapshot.mounted().height());
            values.set(ClientOptions.MOUNTED_CAMERA_DISTANCE.getPath(), snapshot.mounted().distance());
            values.set(ClientOptions.MOUNTED_CAMERA_INITIALIZED.getPath(), snapshot.initialized());
        }, net.neoforged.fml.config.IConfigSpec.ILoadedConfig::save);
    }
    private static void flush() {
        pollSaves();
        if (ClientOptions.SPEC.isLoaded()) saves.flush(CameraAdjustment::prepareSave);
    }
    private static void drain() {
        if (ClientOptions.SPEC.isLoaded() && !saves.drain(CameraAdjustment::prepareSave, 3))
            LOG.warn("Camera composition remains unsaved after the bounded exit retries");
        pollSaves();
    }
    public static void hud(GuiGraphics gui) {
        var game = Minecraft.getInstance();
        if (!eligible() || hintPosition == null || System.nanoTime() >= hintUntil) return;
        Component text = Component.translatable("message.fsrpg.camera_adjustment",
                Component.translatable("message.fsrpg.camera_profile." + hintProfile),
                String.format(Locale.ROOT, "%.2f", hintPosition.side()), String.format(Locale.ROOT, "%.2f", hintPosition.height()));
        int x = (gui.guiWidth() - game.font.width(text)) / 2, y = gui.guiHeight() - 82;
        gui.fill(x - 4, y - 3, x + game.font.width(text) + 4, y + 11, 0x90000000);
        gui.drawString(game.font, text, x, y, 0xffd9eaff, true);
    }
    public static JsonObject diagnostics() {
        var out = new JsonObject(); var current = position();
        out.addProperty("profile", mounted() ? "mounted" : "ground"); out.addProperty("supportedVehicle", mounted());
        out.addProperty("side", current.side()); out.addProperty("height", current.height()); out.addProperty("distance", current.distance());
        out.add("ground", json(ground())); out.add("mounted", json(mountedPosition()));
        out.addProperty("mountedInitialized", initialized());
        out.addProperty("adjusting", decision.adjusting()); out.addProperty("ownsModifier", decision.adjusting());
        out.addProperty("adjustedTicks", adjustedTicks); out.addProperty("saveCount", saves.saveCount()); out.addProperty("pendingSave", saves.pending());
        out.addProperty("saveInFlight", saves.writing()); out.addProperty("saveGeneration", saves.requestedGeneration()); out.addProperty("savedGeneration", saves.savedGeneration());
        out.addProperty("lastSaveMillis", saves.lastWriteNanos() / 1_000_000.0); out.addProperty("longestSaveMillis", saves.longestWriteNanos() / 1_000_000.0);
        return out;
    }
    private static JsonObject json(CameraProfilePosition value) {
        var out = new JsonObject(); out.addProperty("side", value.side()); out.addProperty("height", value.height()); out.addProperty("distance", value.distance()); return out;
    }
}

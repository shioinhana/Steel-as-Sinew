package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.config.RpgConfig;

/** DISPLAY5 visual acceptance for the spear guard pose (horizontal shaft across the body). */
final class SpearGuardReview {
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray checks = new JsonArray();
    private final ConcurrentLinkedQueue<String> screenshots = new ConcurrentLinkedQueue<>();
    private int ticks, checkCount;
    private boolean done, initialised;
    private String worldName;

    SpearGuardReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::renderFrame);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks.add(message);
        checkCount++;
        System.out.println("SPEAR GUARD PASS " + message);
    }

    private void renderFrame(RenderFrameEvent.Post event) {
        if (done) return;
        String name;
        while ((name = screenshots.poll()) != null) {
            Screenshot.grab(root.toFile(), name + ".png", Minecraft.getInstance().getMainRenderTarget(), 1,
                    message -> System.out.println("SPEAR GUARD SCREENSHOT " + message.getString()));
        }
    }

    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        try {
            Minecraft game = Minecraft.getInstance();
            if (game.player == null || game.level == null || game.getSingleplayerServer() == null) return;
            if (!initialised) {
                if (RpgServer.instance() == null) return;
                initialise(game);
            }
            int t = ticks++;
            if (t == 2) RpgSmoke.server(player -> {
                player.level().setDayTime(6000);
                player.level().setWeatherParameters(6000, 0, false, false);
                player.connection.teleport(0.5, 80, 0.5, 180, 0);
                for (var entity : player.level().getEntities(player, player.getBoundingBox().inflate(24),
                        e -> !(e instanceof net.minecraft.world.entity.player.Player))) entity.discard();
            });
            if (t == 5) RpgSmoke.server(player -> {
                player.setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:iron_spear"))));
                RpgServer.instance().action(player, "draw", "");
            });
            if (t == 20) {
                RpgSmoke.server(player -> RpgServer.instance().action(player, "guard", "true"));
            } else if (t == 22) {
                RpgSmoke.server(player -> RpgServer.instance().action(player, "guard", "held"));
            } else if (t == 30) {
                screenshots.add("spear-guard-front");
            } else if (t == 40) {
                float reach = org.fsrpg.client.PlayerAnimations.guardReachErrorCm();
                float height = org.fsrpg.client.PlayerAnimations.guardWristHeightCm();
                check(Float.isFinite(reach) && reach < 1.0f,
                        "Both guard hands reach the authored chest shaft targets (error=" + reach + " cm)");
                check(Float.isFinite(height) && height > 115 && height < 155,
                        "Guard hands stay at chest height (" + height + " cm)");
            } else if (t == 55) {
                RpgSmoke.server(player -> RpgServer.instance().action(player, "guard", "false"));
            } else if (t == 70) {
                screenshots.add("spear-idle-front");
            } else if (t >= 90) {
                finish(null);
            }
        } catch (Throwable error) {
            finish(error);
        }
    }

    private void initialise(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots"));
        JsonObject env = new JsonObject();
        RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env, "monitorAdapter", "")),
                "Spear guard review runs on DISPLAY5");
        worldName = System.getProperty("fsrpg.spearGuardWorld", "");
        Path world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(worldName.startsWith("FSRPG Camera Perf Review") && worldName.equals(world.getFileName().toString())
                        && Files.isRegularFile(world.resolve(".fsrpg-camera-perf-test-world")),
                "Only the marked copied spear review world is modified");
        game.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        game.options.enableVsync().set(false);
        game.options.framerateLimit().set(60);
        game.options.fov().set(30);
        game.options.gamma().set(1.0);
        game.options.renderDistance().set(3);
        game.options.guiScale().set(2);
        game.options.hideGui = true;
        game.options.pauseOnLostFocus = false;
        game.getTutorial().setStep(TutorialSteps.NONE);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(game.getWindow().handle(), 1600, 900);
        game.resizeDisplay();
        KeyMapping.releaseAll();
        game.mouseHandler.releaseMouse();
        RpgSmoke.server(player -> {
            player.setGameMode(GameType.CREATIVE);
            player.level().setDayTime(6000);
            player.level().setWeatherParameters(6000, 0, false, false);
        });
        initialised = true;
    }

    private void finish(Throwable error) {
        if (done) return;
        done = true;
        try {
            Minecraft game = Minecraft.getInstance();
            KeyMapping.releaseAll();
            JsonObject report = new JsonObject();
            report.addProperty("passed", error == null);
            report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("world", worldName);
            report.addProperty("checks", checkCount);
            report.add("checkNames", checks);
            report.addProperty("jar", RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toString());
            Path file = root.resolve("spear-guard-review.json");
            Files.createDirectories(file.getParent());
            RpgConfig.atomicWrite(file, RpgConfig.JSON.toJson(report));
            System.out.println("SPEAR GUARD REVIEW COMPLETE passed=" + (error == null));
            if (error != null) error.printStackTrace();
            game.stop();
        } catch (Throwable reporting) {
            reporting.printStackTrace();
            Minecraft.getInstance().stop();
        }
    }
}

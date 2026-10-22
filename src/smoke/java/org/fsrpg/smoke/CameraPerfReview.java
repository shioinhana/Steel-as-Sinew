package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
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
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.config.RpgConfig;

/**
 * DISPLAY5 movement/camera pacing review: walks and continuously turns the real shoulder camera for
 * two six-second phases (rapier, spear) and records each native render-frame interval plus the real
 * camera clip/solve costs. This measures the same path a player uses, not an idle load screen.
 */
final class CameraPerfReview {
    private static final int WARMUP_TICKS = 40, MEASURE_TICKS = 200;
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray phases = new JsonArray(), checks = new JsonArray();
    private final List<Double> intervals = new ArrayList<>();
    private final List<Long> frameStamps = new ArrayList<>();
    private final List<Double> solveMillis = new ArrayList<>();
    private int ticks, checkCount, phaseNumber;
    private long phaseStartNanos;
    private boolean done, initialised, measuring;
    private String worldName;

    CameraPerfReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::renderFrame);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks.add(message);
        checkCount++;
        System.out.println("CAMERA PERF PASS " + message);
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
            game.options.keyUp.setDown(true);
            game.options.keySprint.setDown(phaseNumber == 1);
            if (t == 10) {
                prepare(game, "rapier", "minecraft:iron_sword");
            } else if (t == 10 + WARMUP_TICKS) {
                beginMeasure("rapier");
            } else if (t == 10 + WARMUP_TICKS + MEASURE_TICKS) {
                endMeasure("rapier");
                prepare(game, "spear", "minecraft:iron_spear");
            } else if (t == 10 + 2 * WARMUP_TICKS + MEASURE_TICKS) {
                beginMeasure("spear");
            } else if (t == 10 + 2 * WARMUP_TICKS + 2 * MEASURE_TICKS) {
                endMeasure("spear");
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
                "Camera pacing runs on DISPLAY5");
        worldName = System.getProperty("fsrpg.cameraPerfWorld", "");
        Path world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(worldName.startsWith("FSRPG Camera Perf Review") && worldName.equals(world.getFileName().toString())
                        && Files.isRegularFile(world.resolve(".fsrpg-camera-perf-test-world")),
                "Only the marked copied camera pacing world is modified");
        game.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        game.options.enableVsync().set(false);
        game.options.framerateLimit().set(120);
        game.options.renderDistance().set(3);
        game.options.guiScale().set(2);
        game.options.hideGui = false;
        game.options.pauseOnLostFocus = false;
        game.getTutorial().setStep(TutorialSteps.NONE);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(game.getWindow().handle(), 1280, 720);
        game.resizeDisplay();
        KeyMapping.releaseAll();
        game.mouseHandler.releaseMouse();
        RpgSmoke.server(player -> {
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent();
            // The pacing path needs every weapon style available; this is a copied creative review world.
            var progression = RpgServer.instance().progression();
            for (String id : List.of("rapier", "dual_sword", "archery")) progression.learnFree(player.getUUID(), id);
        });
        initialised = true;
    }

    private void prepare(Minecraft game, String phase, String itemId) {
        phaseNumber = phase.equals("rapier") ? 1 : 2;
        intervals.clear();
        solveMillis.clear();
        RpgSmoke.server(player -> {
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            RpgServer.instance().action(player, "draw", "");
        });
    }

    private void beginMeasure(String phase) {
        CombatCamera.resetPerfCounters();
        ClientState.resetPerfCounters();
        phaseStartNanos = System.nanoTime();
        intervals.clear();
        frameStamps.clear();
        solveMillis.clear();
        lastFrameNanos = 0;
        measuring = true;
        check(ClientState.drawn(), phase + " phase is drawn before measurement");
        check(CombatCamera.active(), phase + " phase uses the real shoulder camera");
    }

    private long lastFrameNanos;

    private void renderFrame(RenderFrameEvent.Post event) {
        if (!measuring || done) return;
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            intervals.add((now - lastFrameNanos) / 1_000_000.0);
            frameStamps.add(now);
        }
        lastFrameNanos = now;
        long solve = PlayerAnimations.lastSpearSolveNanos();
        if (phaseNumber == 2 && solve > 0) solveMillis.add(solve / 1_000_000.0);
        // Continuous real camera input while walking, once per genuine rendered frame.
        CombatCamera.mouse(0.45, 0.08);
    }

    private void endMeasure(String phase) {
        measuring = false;
        if (intervals.size() < 20) throw new AssertionError(phase + " produced too few native frames");
        List<Double> sorted = new ArrayList<>(intervals);
        Collections.sort(sorted);
        double median = percentile(sorted, .50), p95 = percentile(sorted, .95), p99 = percentile(sorted, .99);
        double max = sorted.getLast();
        long[] camera = CombatCamera.perfSnapshot();
        long[] snapshots = ClientState.perfSnapshot();
        double fps = 1000.0 / Math.max(.001, median);
        JsonObject row = new JsonObject();
        row.addProperty("phase", phase);
        row.addProperty("frames", sorted.size());
        row.addProperty("medianMs", median);
        row.addProperty("p95Ms", p95);
        row.addProperty("p99Ms", p99);
        row.addProperty("maxMs", max);
        row.addProperty("fps", fps);
        row.addProperty("cameraCalls", camera[0]);
        row.addProperty("cameraRayCasts", camera[1]);
        row.addProperty("cameraNanos", camera[2]);
        row.addProperty("cameraMaxNanos", camera[3]);
        row.addProperty("snapshotCalls", snapshots[0]);
        row.addProperty("snapshotNanos", snapshots[1]);
        row.addProperty("snapshotMaxNanos", snapshots[2]);
        if (!solveMillis.isEmpty()) {
            List<Double> solved = new ArrayList<>(solveMillis);
            Collections.sort(solved);
            row.addProperty("solveSamples", solved.size());
            row.addProperty("solveMedianMs", percentile(solved, .50));
            row.addProperty("solveP99Ms", percentile(solved, .99));
            row.addProperty("solveMaxMs", solved.getLast());
        }
        double slowThreshold = Math.max(6, median * 3);
        JsonArray slow = new JsonArray();
        for (int i = 0; i < intervals.size() && slow.size() < 20; i++) {
            if (intervals.get(i) < slowThreshold) continue;
            JsonObject frame = new JsonObject();
            frame.addProperty("ms", intervals.get(i));
            frame.addProperty("secondsIntoPhase", (frameStamps.get(i) - phaseStartNanos) / 1e9);
            frame.addProperty("revision", ClientState.revision());
            slow.add(frame);
        }
        row.addProperty("slowThresholdMs", slowThreshold);
        row.add("slowFrames", slow);
        phases.add(row);
        System.out.println("CAMERA PERF " + phase + " fps=" + Math.round(fps) + " median=" + fmt(median)
                + " p95=" + fmt(p95) + " p99=" + fmt(p99) + " max=" + fmt(max)
                + " rays=" + camera[1] + " snapshots=" + snapshots[0] + " slow=" + slow.size()
                + " snapMax=" + fmt(snapshots[2] / 1e6));
        check(fps >= 80, phase + " keeps a smooth median camera frame rate: " + Math.round(fps) + " fps");
        check(p99 <= Math.max(12, median * 2.2), phase + " has no sustained long-frame tail: p99=" + fmt(p99) + " ms");
        check(max <= 50, phase + " has no multi-frame stall: max=" + fmt(max) + " ms");
        check(camera[0] > 0, phase + " really swept the shoulder camera each frame");
    }

    private static double percentile(List<Double> sorted, double fraction) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor((sorted.size() - 1) * fraction)));
    }

    private static String fmt(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }

    private void finish(Throwable error) {
        if (done) return;
        done = true;
        measuring = false;
        try {
            Minecraft game = Minecraft.getInstance();
            KeyMapping.releaseAll();
            JsonObject report = new JsonObject();
            report.addProperty("passed", error == null);
            report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("world", worldName);
            report.addProperty("checks", checkCount);
            report.add("checkNames", checks);
            report.add("phases", phases);
            report.addProperty("jar", RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toString());
            Path file = root.resolve("camera-perf-review.json");
            Files.createDirectories(file.getParent());
            RpgConfig.atomicWrite(file, RpgConfig.JSON.toJson(report));
            System.out.println("CAMERA PERF REVIEW COMPLETE passed=" + (error == null));
            if (error != null) error.printStackTrace();
            game.stop();
        } catch (Throwable reporting) {
            reporting.printStackTrace();
            Minecraft.getInstance().stop();
        }
    }
}

package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.SkillTreeLayout;
import org.fsrpg.client.SkillTreeScreen;
import org.fsrpg.config.RpgConfig;

/**
 * DISPLAY5 acceptance for the single-page skill tree and its creative-mode tools. The report is
 * written only after native screenshots have been requested; server state is always read back.
 */
final class SkillTreeReview {
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray checks = new JsonArray(), scenes = new JsonArray();
    private final ConcurrentLinkedQueue<String> screenshots = new ConcurrentLinkedQueue<>();
    private int ticks, checkCount;
    private boolean done, initialised, opened, creativeSynced;
    private String worldName;
    private Throwable failure;

    SkillTreeReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::renderFrame);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }

    private void renderFrame(RenderFrameEvent.Post event) {
        if (done) return;
        String name;
        while ((name = screenshots.poll()) != null) {
            Screenshot.grab(root.toFile(), name + ".png",
                    Minecraft.getInstance().getMainRenderTarget(), 1,
                    message -> System.out.println("SKILL TREE SCREENSHOT " + message.getString()));
        }
    }

    private RpgServer rpg() { return RpgServer.instance(); }
    private Minecraft game() { return Minecraft.getInstance(); }

    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks.add(message);
        checkCount++;
        System.out.println("SKILL TREE PASS " + message);
    }

    private void scene(String id, String note) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("note", note);
        row.addProperty("gameTick", game().level == null ? -1 : game().level.getGameTime());
        scenes.add(row);
        screenshots.add(id.replace('/', '-'));
    }

    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        try {
            Minecraft game = game();
            if (game.player == null || game.level == null || game.getSingleplayerServer() == null) return;
            if (!initialised) {
                if (rpg() == null || ClientState.revision() == 0) return;
                initialise(game);
            }
            if (!creativeSynced) {
                if (!ClientState.bool(ClientState.snapshot(), "creative", false)) return;
                creativeSynced = true;
                check(true, "Creative mode is synchronized from the server to the client snapshot");
            }
            step(game, ticks++);
        } catch (Throwable error) {
            finish(error);
        }
    }

    private void initialise(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots"));
        JsonObject env = new JsonObject();
        RpgSmoke.windowEnvironment(env);
        check("\\\\.\\DISPLAY5".equals(ClientState.text(env, "monitorAdapter", "")),
                "Skill-tree review runs on DISPLAY5");
        worldName = System.getProperty("fsrpg.skillTreeWorld", "");
        Path world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(worldName.startsWith("FSRPG Skill Tree Review") && worldName.equals(world.getFileName().toString())
                        && Files.isRegularFile(world.resolve(".fsrpg-skill-tree-test-world")),
                "Only the marked copied skill-tree world is modified");
        org.lwjgl.glfw.GLFW.glfwSetWindowTitle(game.getWindow().handle(),
                "FSRPG 技能树与创造模式修行验收 · DISPLAY5");
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(game.getWindow().handle(), 1600, 900);
        game.resizeDisplay();
        game.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        game.options.hideGui = false;
        game.options.pauseOnLostFocus = false;
        game.getTutorial().setStep(TutorialSteps.NONE);
        KeyMapping.releaseAll();
        game.mouseHandler.releaseMouse();
        RpgSmoke.server(player -> {
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent();
        });
        initialised = true;
    }

    private void step(Minecraft game, int t) throws Exception {
        if (t == 10) {
            game.setScreen(new SkillTreeScreen());
            opened = true;
        } else if (t == 20) {
            check(opened && game.screen instanceof SkillTreeScreen, "P page is replaced by the single-page skill tree");
            SkillTreeScreen screen = (SkillTreeScreen) game.screen;
            check(children(screen).stream().filter(widget -> widget.getMessage().getString().contains("西洋剑")).count() == 1,
                    "Category rail contains the rapier category once");
            check(groups(screen).size() == 4, "Rapier page groups入门/刺击/蓄力/处决 into four sections");
            check(cards(screen).size() == 4, "Every rapier node is visible without pagination");
            scene("skill-tree/rapier", "single-page rapier tree");
        } else if (t == 25) {
            press(game, "双刀");
        } else if (t == 30) {
            SkillTreeScreen screen = (SkillTreeScreen) game.screen;
            check(groups(screen).size() == 2 && cards(screen).size() == 5,
                    "Dual-blade page fits all five nodes on one page");
            scene("skill-tree/dual-blade", "single-page dual-blade tree");
        } else if (t == 35) {
            press(game, "弓");
        } else if (t == 40) {
            SkillTreeScreen screen = (SkillTreeScreen) game.screen;
            check(groups(screen).size() == 5 && cards(screen).size() == 8,
                    "Bow page shows all eight nodes with 150/250 exclusive pairs grouped");
            scene("skill-tree/bow", "single-page bow tree with branch groups");
        } else if (t == 45) {
            press(game, "步法");
        } else if (t == 50) {
            SkillTreeScreen screen = (SkillTreeScreen) game.screen;
            check(groups(screen).size() == 3 && cards(screen).size() == 8,
                    "Footwork page groups styles/cloud step/light body on one page");
            scene("skill-tree/footwork", "single-page footwork tree");
        } else if (t == 55) {
            press(game, "补满进度");
        } else if (t == 80) {
            var view = rpg().progression().view(game.player.getUUID());
            check(view.mind().total() == 91 && view.body().total() == 102 && view.footwork().total() == 600,
                    "Creative fill reaches configured cultivation maxima");
            check(view.rapierKills() == 301 && view.dualHits() == 400 && view.archeryScore() == 300,
                    "Creative fill reaches configured weapon-growth maxima");
            check(view.trialPassed() && view.dragonKilled(), "Creative fill marks trial and dragon conditions");
            scene("creative/filled", "filled cultivation and growth");
        } else if (t == 85) {
            press(game, "弓");
        } else if (t == 90) {
            select(game, "archery_ru_suo");
        } else if (t == 95) {
            check(chosenIs(game, "archery_ru_suo"),
                    "A normally condition-locked bow branch can be selected in the detail panel");
            scene("creative/locked-node-detail", "condition-locked bow branch detail");
            pressAction(game, "学习");
        } else if (t == 120) {
            var view = rpg().progression().view(game.player.getUUID());
            check(view.learnedNodes().contains("archery_ru_suo"),
                    "Creative learning grants a condition/prerequisite-locked node");
            check(rpg().progression().nodes(game.player.getUUID()).stream()
                            .anyMatch(node -> node.id().equals("archery_ru_suo") && node.creative()),
                    "Creative grant is exposed to the client node view");
            check(rpg().progression().archeryView(game.player.getUUID()).branch150().equals("RU_SUO"),
                    "Creative-granted bow branch takes effect immediately");
            scene("creative/granted-node", "creative-granted bow branch detail");
        } else if (t == 125) {
            select(game, "archery_ru_suo");
            pressAction(game, "移除");
        } else if (t == 145) {
            check(!rpg().progression().view(game.player.getUUID()).learnedNodes().contains("archery_ru_suo"),
                    "Creative removal takes the granted node away");
            scene("creative/removed-node", "node removed in creative mode");
        } else if (t == 150) {
            press(game, "清空重选");
        } else if (t == 155) {
            press(game, "再次点击确认");
        } else if (t == 180) {
            var view = rpg().progression().view(game.player.getUUID());
            check(view.learnedNodes().isEmpty(), "Confirmed reset clears learned skills for reselection");
            check(view.mind().total() == 0 && view.body().total() == 0 && view.footwork().total() == 0,
                    "Confirmed reset clears cultivation totals");
            check(view.mindUnlocked() && view.bodyUnlocked() && view.footworkUnlocked(),
                    "Confirmed reset keeps the training doors open for immediate reselection");
            check(view.trialPassed() && view.dragonKilled(), "Confirmed reset preserves world achievement marks");
            scene("creative/reset", "empty tree ready for reselection");
        } else if (t >= 190) {
            finish(null);
        }
    }

    private void select(Minecraft game, String id) throws Exception {
        if (!(game.screen instanceof SkillTreeScreen screen)) throw new AssertionError("Skill tree screen is not open");
        var field = SkillTreeScreen.class.getDeclaredField("selected");
        field.setAccessible(true);
        field.set(screen, id);
        var rebuild = SkillTreeScreen.class.getDeclaredMethod("rebuild");
        rebuild.setAccessible(true);
        rebuild.invoke(screen);
    }

    private boolean chosenIs(Minecraft game, String id) throws Exception {
        var field = SkillTreeScreen.class.getDeclaredField("selected");
        field.setAccessible(true);
        return id.equals(field.get(game.screen));
    }

    @SuppressWarnings("unchecked")
    private List<SkillTreeLayout.Group> groups(SkillTreeScreen screen) throws Exception {
        var field = SkillTreeScreen.class.getDeclaredField("groups");
        field.setAccessible(true);
        return (List<SkillTreeLayout.Group>) field.get(screen);
    }

    @SuppressWarnings("unchecked")
    private List<?> cards(SkillTreeScreen screen) throws Exception {
        var field = SkillTreeScreen.class.getDeclaredField("cards");
        field.setAccessible(true);
        return (List<?>) field.get(screen);
    }

    private List<Button> children(SkillTreeScreen screen) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
    }

    private void press(Minecraft game, String label) throws Exception {
        if (!(game.screen instanceof SkillTreeScreen screen)) throw new AssertionError("Skill tree screen is not open");
        for (Button button : children(screen)) {
            if (button.active && button.getMessage().getString().contains(label)) {
                button.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
                return;
            }
        }
        throw new AssertionError("No active button containing " + label);
    }

    private void pressAction(Minecraft game, String label) throws Exception {
        if (!(game.screen instanceof SkillTreeScreen screen)) throw new AssertionError("Skill tree screen is not open");
        for (Button button : children(screen)) {
            if (button.active && button.getMessage().getString().contains(label)) {
                button.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
                return;
            }
        }
        throw new AssertionError("No active action button containing " + label);
    }

    private void finish(Throwable error) {
        if (done) return;
        done = true;
        failure = error;
        try {
            Minecraft game = game();
            if (game.screen instanceof SkillTreeScreen) game.setScreen(null);
            KeyMapping.releaseAll();
            JsonObject report = new JsonObject();
            report.addProperty("passed", error == null);
            report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("world", worldName);
            report.addProperty("checks", checkCount);
            report.add("checkNames", checks);
            report.add("scenes", scenes);
            report.addProperty("jar", RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toString());
            Path file = root.resolve("skill-tree-review.json");
            Files.createDirectories(file.getParent());
            RpgConfig.atomicWrite(file, RpgConfig.JSON.toJson(report));
            System.out.println("SKILL TREE REVIEW COMPLETE passed=" + (error == null));
            if (error != null) error.printStackTrace();
            game.stop();
        } catch (Throwable reporting) {
            reporting.printStackTrace();
            game().stop();
        }
    }
}

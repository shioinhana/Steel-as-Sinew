package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.GuildQuestScreen;
import org.fsrpg.client.QuestJournalModel;
import org.fsrpg.client.RpgHud;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.quest.QuestDefinition;
import org.fsrpg.quest.QuestService;
import org.fsrpg.quest.QuestStore;
import org.lwjgl.glfw.GLFW;

/** Same-JAR integrated-server acceptance. Only a specifically marked disposable world is changed. */
final class QuestSystemReview {
    private static final String MONITOR = "\\\\.\\DISPLAY5", PREFIX = "FSRPG Quest System Review ";
    private static final String SUPPLY = "fsrpg:guild_supplies", ZOMBIES = "fsrpg:guild_zombies", ELITE = "fsrpg:guild_elite", CHAIN = "fsrpg:guild_chain", EXAM = "fsrpg:guild_exam_c";
    private record Scene(String name, int scale, int width, int height, QuestJournalModel.Tab tab, String id) {}
    private static final List<Scene> SCENES = List.of(
            new Scene("00-board-material-progress", 2, 1280, 720, QuestJournalModel.Tab.BOARD, SUPPLY),
            new Scene("01-materials-ready-wide", 2, 1280, 720, QuestJournalModel.Tab.ACTIVE, SUPPLY),
            new Scene("02-materials-ready-narrow", 4, 1280, 960, QuestJournalModel.Tab.ACTIVE, SUPPLY),
            new Scene("03-three-tracked-hud", 2, 1280, 720, null, ""),
            new Scene("04-rewards-cooldown-completed", 2, 1280, 720, QuestJournalModel.Tab.COMPLETED, SUPPLY),
            new Scene("05-abandoned-journal-narrow", 4, 1280, 960, QuestJournalModel.Tab.FAILED, ZOMBIES),
            new Scene("06-stage-reward-before-claim", 2, 1280, 720, QuestJournalModel.Tab.ACTIVE, CHAIN),
            new Scene("07-final-choice-narrow", 4, 1280, 960, QuestJournalModel.Tab.ACTIVE, CHAIN),
            new Scene("08-promotion-npc-eligible", 2, 1280, 720, QuestJournalModel.Tab.RANK, EXAM),
            new Scene("09-npc-exam-active", 2, 1280, 720, QuestJournalModel.Tab.ACTIVE, EXAM),
            new Scene("10-exam-complete-title-c", 2, 1280, 720, QuestJournalModel.Tab.COMPLETED, EXAM),
            new Scene("11-daily-board", 2, 1280, 720, QuestJournalModel.Tab.DAILY, ""));
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray checks = new JsonArray(), screenshots = new JsonArray();
    private final AtomicInteger writes = new AtomicInteger();
    private volatile Throwable failure;
    private volatile boolean serverReady, languageReady;
    private volatile List<QuestService.QuestProgress> expected = List.of();
    private QuestService quests;
    private UUID playerId;
    private Path world;
    private boolean initialized, done, requested, captured, shown;
    private int scene = -1, localTicks, warmup, oldScale, oldFps, oldDistance;
    private boolean oldHide, oldPause, oldVsync;
    private String oldLanguage;
    private CameraType oldCamera;
    private long started;
    private int nativeMouseReleases, grabbedPointerRenderFrames;
    private java.lang.reflect.Field mouseX, mouseY;
    private double oldMouseX, oldMouseY;

    QuestSystemReview() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> isolateNativePointer());
        NeoForge.EVENT_BUS.addListener(this::tick); NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }
    private void initialize(Minecraft game) throws Exception {
        started = System.nanoTime(); Files.createDirectories(root.resolve("screenshots")); monitor();
        world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = System.getProperty("fsrpg.questSystemWorld", "");
        check(name.startsWith(PREFIX) && world.getFileName().toString().equals(name) && Files.isRegularFile(world.resolve(".fsrpg-quest-system-test-world")), "Only the specifically named marked quest-system world is modified");
        playerId = game.player.getUUID();
        oldScale = game.options.guiScale().get(); oldFps = game.options.framerateLimit().get(); oldDistance = game.options.renderDistance().get();
        oldHide = game.options.hideGui; oldPause = game.options.pauseOnLostFocus; oldVsync = game.options.enableVsync().get(); oldCamera = game.options.getCameraType(); oldLanguage = game.options.languageCode;
        mouseX = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos"); mouseY = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
        mouseX.setAccessible(true); mouseY.setAccessible(true); oldMouseX = mouseX.getDouble(game.mouseHandler); oldMouseY = mouseY.getDouble(game.mouseHandler);
        game.options.pauseOnLostFocus = false; game.options.hideGui = false; game.options.framerateLimit().set(40); game.options.renderDistance().set(3);
        game.options.enableVsync().set(false); game.options.setCameraType(CameraType.FIRST_PERSON); game.getTutorial().setStep(TutorialSteps.NONE);
        if (game.getLanguageManager().getSelected().equals("zh_cn")) languageReady = true;
        else { game.getLanguageManager().setSelected("zh_cn"); game.options.languageCode = "zh_cn"; game.reloadResourcePacks().whenComplete((v, error) -> { failure = error; languageReady = error == null; }); }
        initialized = true; isolateNativePointer(); GLFW.glfwSetWindowTitle(game.getWindow().handle(), "FSRPG 公会任务系统验收 · DISPLAY5 · 完成后自动关闭");
        server(player -> {
            quests = service();
            var old = quests.snapshot(); var players = new LinkedHashMap<>(old.players()); players.remove(playerId);
            var ranks = new LinkedHashMap<>(old.ranks()); ranks.remove(playerId); var reputations = new LinkedHashMap<>(old.reputations()); reputations.remove(playerId);
            quests.restore(new QuestService.Snapshot(QuestService.SCHEMA_VERSION, players, ranks, reputations));
            player.setGameMode(GameType.SURVIVAL); player.setHealth(20); player.getFoodData().setFoodLevel(20); player.getInventory().clearContent();
            player.level().setDayTime(18000); player.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                player.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                for (int y = 80; y <= 83; y++) player.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            player.connection.teleport(.5, 80, .5, 0, 8);
            action(player, "acceptQuest", "fsrpg:guild_bounty_high"); check(quests.progress(playerId, "fsrpg:guild_bounty_high").status() == QuestService.Status.LOCKED, "D title cannot accept the B-gated high bounty through a forged action");
            for (String id : List.of(SUPPLY, ZOMBIES, ELITE, CHAIN, "fsrpg:guild_lottery")) action(player, "acceptQuest", id);
            check(quests.progress(playerId, SUPPLY).status() == QuestService.Status.ACTIVE, "Accept intent creates the actual player's active multi-material task");
            player.addItem(new ItemStack(Items.OAK_LOG, 5)); inventory(player);
            check(quests.progress(playerId, SUPPLY).objectiveCounts().get("wood") == 5 && quests.progress(playerId, SUPPLY).objectiveCounts().get("iron") == 0, "Actual inventory scan counts each material independently");
            for (String id : List.of(SUPPLY, ZOMBIES, ELITE, CHAIN)) action(player, "trackQuest", id);
            check(quests.progress(playerId).stream().filter(QuestService.QuestProgress::tracked).count() == 3 && !quests.progress(playerId, CHAIN).tracked(), "Fourth tracking intention is rejected by the server's configured three-task limit");
            kill(player, EntityType.ZOMBIE, 0, false); check(quests.progress(playerId, ZOMBIES).objectiveCounts().get("zombie") == 0, "Actual level-zero Zombie death does not count toward minimum-level-one hunting");
            kill(player, EntityType.ZOMBIE, 1, false); check(quests.progress(playerId, ZOMBIES).objectiveCounts().get("zombie") == 1, "Actual eligible Zombie death increments the correct hunting task");
            check(quests.progress(playerId, ELITE).objectiveCounts().get("elite") == 0, "Ordinary Zombies do not count toward the elite target");
            kill(player, EntityType.ZOMBIE, 5, true); check(quests.progress(playerId, ELITE).status() == QuestService.Status.READY, "Real level-five elite-tagged Zombie death completes the elite task");
            sync(player); serverReady = true;
        });
    }
    private static QuestService service() {
        try { var field = RpgServer.class.getDeclaredField("quests"); field.setAccessible(true); return (QuestService) field.get(RpgServer.instance()); }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private void server(Consumer<ServerPlayer> work) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> { try { var player = server.getPlayerList().getPlayer(playerId); if (player == null) throw new AssertionError("Quest fixture player left"); work.accept(player); } catch (Throwable error) { failure = error; } });
    }
    private static void action(ServerPlayer player, String action, String argument) { RpgServer.instance().action(player, action, argument); }
    private static void inventory(ServerPlayer player) {
        try { var method = RpgServer.class.getDeclaredMethod("refreshQuestInventory", ServerPlayer.class); method.setAccessible(true); method.invoke(RpgServer.instance(), player); }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private void sync(ServerPlayer player) { inventory(player); expected = quests.progress(playerId); action(player, "status", ""); }
    private static void kill(ServerPlayer player, EntityType<? extends Mob> type, int level, boolean elite) {
        Mob victim = type.create(player.level(), EntitySpawnReason.COMMAND); if (victim == null) throw new AssertionError("Cannot create target " + type);
        victim.setNoAi(true); victim.setPos(12.5, 80, .5); victim.getPersistentData().putInt("fsrpg_level", level);
        if (elite) victim.addTag("fsrpg_elite"); player.level().addFreshEntity(victim);
        victim.hurtServer(player.level(), player.damageSources().playerAttack(player), 10000);
        if (victim.isAlive()) throw new AssertionError("Fixture target survived actual player damage");
    }
    private static Map<String, Integer> owned(ServerPlayer player) {
        var result = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i); if (!stack.isEmpty()) result.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return result;
    }
    private void fixture(ServerPlayer player, int scene) {
        if (scene == 1) {
            player.addItem(new ItemStack(Items.OAK_LOG, 15)); player.addItem(new ItemStack(Items.RAW_IRON, 9)); inventory(player);
            check(quests.progress(playerId, SUPPLY).status() == QuestService.Status.ACTIVE, "One missing iron keeps a multi-material task active");
            player.addItem(new ItemStack(Items.RAW_IRON)); inventory(player);
            check(quests.progress(playerId, SUPPLY).status() == QuestService.Status.READY, "Both actual inventory requirements make the task ready");
            for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.OAK_LOG)) { player.getInventory().getItem(i).shrink(1); break; }
            inventory(player); check(quests.progress(playerId, SUPPLY).status() == QuestService.Status.ACTIVE, "Removing required materials revokes readiness before a claim");
            player.addItem(new ItemStack(Items.OAK_LOG)); inventory(player);
        }
        if (scene == 4) {
            int silver = owned(player).getOrDefault("fsrpg:silver_coin", 0), gold = owned(player).getOrDefault("fsrpg:gold_coin", 0), reputation = quests.reputation(playerId);
            action(player, "claimQuest", SUPPLY); action(player, "claimQuest", ELITE);
            check(owned(player).getOrDefault("fsrpg:silver_coin", 0) == silver + 5, "Actual completion pays exactly five configured silver coin items");
            check(owned(player).getOrDefault("fsrpg:gold_coin", 0) == gold + 2 && owned(player).getOrDefault("minecraft:diamond", 0) == 1, "Elite claim pays exactly two gold coin items and one diamond");
            check(quests.reputation(playerId) == reputation + 20, "Completed tasks grant the configured server-side guild reputation");
            var before = owned(player); reputation = quests.reputation(playerId); action(player, "claimQuest", SUPPLY); action(player, "claimQuest", ELITE);
            check(owned(player).equals(before) && quests.reputation(playerId) == reputation, "Duplicate completion claims give neither items nor reputation");
            action(player, "acceptQuest", SUPPLY); check(quests.progress(playerId, SUPPLY).status() == QuestService.Status.COOLDOWN, "Reaccepting during the independent supply cooldown is rejected");
        }
        if (scene == 5) {
            var before = owned(player); action(player, "abandonQuest", ZOMBIES);
            int count = quests.progress(playerId, ZOMBIES).objectiveCounts().get("zombie"); kill(player, EntityType.ZOMBIE, 1, false);
            check(quests.progress(playerId, ZOMBIES).status() == QuestService.Status.ABANDONED && quests.progress(playerId, ZOMBIES).objectiveCounts().get("zombie") == count, "Abandonment stops subsequent actual kill events");
            check(owned(player).equals(before) && !quests.progress(playerId, ZOMBIES).tracked(), "Abandonment issues no reward and removes only its tracking");
            check(!quests.progress(playerId, ZOMBIES).history().isEmpty(), "Abandoned attempt remains in the persistent journal history");
        }
        if (scene == 6) {
            player.addItem(new ItemStack(Items.BREAD, 4)); inventory(player);
            kill(player, EntityType.SKELETON, 1, false);
            check(quests.progress(playerId, CHAIN).objectiveCounts().get("food") == 4 && quests.progress(playerId, CHAIN).objectiveCounts().get("skeleton") == 0, "Future-stage real kills are ignored until the supply stage reward is claimed");
            check(quests.progress(playerId, CHAIN).stage() == 0, "Completed first stage is awaiting its own reward");
        }
        if (scene == 7) {
            int silver = owned(player).getOrDefault("fsrpg:silver_coin", 0);
            String supply = "{\"id\":\"" + CHAIN + "\",\"stage\":\"supply\"}";
            action(player, "claimQuestStage", supply); check(quests.progress(playerId, CHAIN).stage() == 1 && owned(player).getOrDefault("fsrpg:silver_coin", 0) == silver + 3, "First stage reward pays exactly three silver and unlocks stage two");
            var before = owned(player); action(player, "claimQuestStage", supply); check(owned(player).equals(before), "Repeated stage reward intent cannot pay twice");
            for (int n = 0; n < 3; n++) kill(player, EntityType.SKELETON, 1, false);
            check(quests.progress(playerId, CHAIN).status() == QuestService.Status.READY, "Three actual Skeleton deaths complete the final stage");
            action(player, "claimQuest", "{\"id\":\"" + CHAIN + "\",\"choice\":1}");
            check(quests.progress(playerId, CHAIN).completions() == 0, "Final reward cannot bypass the unclaimed final-stage reward");
            int bread = owned(player).getOrDefault("minecraft:bread", 0);
            action(player, "claimQuestStage", "{\"id\":\"" + CHAIN + "\",\"stage\":\"hunt\"}");
            check(owned(player).getOrDefault("minecraft:bread", 0) == bread + 3, "Final-stage reward pays its independent three bread items");
            action(player, "claimQuest", CHAIN); check(quests.progress(playerId, CHAIN).status() == QuestService.Status.READY, "Missing required self-selected reward is rejected without consuming the completion");
        }
        if (scene == 8) {
            int arrows = owned(player).getOrDefault("minecraft:arrow", 0), gold = owned(player).getOrDefault("fsrpg:gold_coin", 0);
            action(player, "claimQuest", "{\"id\":\"" + CHAIN + "\",\"choice\":1}");
            check(owned(player).getOrDefault("minecraft:arrow", 0) == arrows + 24 && owned(player).getOrDefault("fsrpg:gold_coin", 0) == gold + 1, "Final choice index one pays twenty-four arrows and one gold item exactly");
            var before = owned(player); action(player, "claimQuest", "{\"id\":\"" + CHAIN + "\",\"choice\":0}");
            check(owned(player).equals(before), "Changing choice on a duplicate claim cannot produce the other reward");
            check(quests.promotionProgress(playerId).eligible(), "Three genuine completions including supply make the configured C exam eligible");
            action(player, "applyExam", EXAM); check(quests.progress(playerId, EXAM).status() != QuestService.Status.ACTIVE, "Remote exam application fails without a nearby guild NPC");
            ArmorStand npc = new ArmorStand(player.level(), 2.5, 80, .5); npc.addTag("fsrpg_guild"); npc.setCustomName(Component.literal("公会接待员")); npc.setNoGravity(true); npc.setInvulnerable(true); player.level().addFreshEntity(npc);
        }
        if (scene == 9) {
            action(player, "acceptQuest", EXAM); check(quests.progress(playerId, EXAM).status() != QuestService.Status.ACTIVE, "Ordinary accept action cannot bypass the guild exam workflow");
            action(player, "applyExam", EXAM); check(quests.progress(playerId, EXAM).status() == QuestService.Status.ACTIVE, "Eligible application next to the tagged NPC creates the actual exam task");
            action(player, "claimQuest", EXAM); check(quests.rank(playerId) == QuestDefinition.Rank.D, "Unfinished exam cannot grant a new adventurer title");
        }
        if (scene == 10) {
            for (int n = 0; n < 3; n++) kill(player, EntityType.ZOMBIE, 1, false);
            check(quests.progress(playerId, EXAM).status() == QuestService.Status.READY, "Three actual exam target deaths produce a completed exam log entry");
            action(player, "claimQuest", EXAM); check(quests.rank(playerId) == QuestDefinition.Rank.C && quests.progress(playerId, EXAM).status() == QuestService.Status.CLAIMED, "Only the completed exam claim near the NPC promotes D to C");
            try {
                var restored = new QuestService(quests.catalog(), quests.settings()); restored.restore(new QuestStore(world.resolve("data/fsrpg/quests.json"), RpgConfig.JSON).load());
                check(restored.rank(playerId) == QuestDefinition.Rank.C && restored.progress(playerId, CHAIN).completions() == 1 && restored.progress(playerId, CHAIN).claimedStages().size() == 2, "Actual saved quest ledger reloads title, completion and both one-time stage markers");
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        }
        if (scene == 11) {
            int iron = owned(player).getOrDefault("minecraft:iron_ingot", 0), diamonds = owned(player).getOrDefault("minecraft:diamond", 0), silver = owned(player).getOrDefault("fsrpg:silver_coin", 0);
            for (int n = 0; n < 3; n++) kill(player, EntityType.SPIDER, 1, false);
            action(player, "claimQuest", "fsrpg:guild_lottery");
            int ironGain = owned(player).getOrDefault("minecraft:iron_ingot", 0) - iron, diamondGain = owned(player).getOrDefault("minecraft:diamond", 0) - diamonds;
            check((ironGain == 3 && diamondGain == 0 || ironGain == 0 && diamondGain == 1) && owned(player).getOrDefault("fsrpg:silver_coin", 0) == silver + 3, "Random task pays exactly one configured pool outcome plus its fixed currency");
            var before = owned(player); action(player, "claimQuest", "fsrpg:guild_lottery"); check(owned(player).equals(before), "Repeated random-reward claim cannot reroll or pay another pool item");
            long available = quests.catalog().definitions().stream().filter(q -> q.rules().daily() && quests.dailyAvailable(playerId, q.id())).count();
            check(available == quests.settings().dailyCount(), "Actual daily board exposes the configured number of eligible tasks");
            var first = quests.catalog().definitions().stream().filter(q -> q.rules().daily() && quests.dailyAvailable(playerId, q.id())).map(QuestDefinition::id).toList();
            action(player, "status", "");
            check(first.equals(quests.catalog().definitions().stream().filter(q -> q.rules().daily() && quests.dailyAvailable(playerId, q.id())).map(QuestDefinition::id).toList()), "Reopening the board cannot reroll today's selection");
        }
    }
    private void configure(Minecraft game, int index) {
        scene = index; localTicks = 0; requested = captured = shown = false; serverReady = false;
        Scene next = SCENES.get(index); viewport(game, next.width(), next.height()); game.options.guiScale().set(next.scale()); game.resizeDisplay();
        game.setScreen(null); isolateNativePointer();
        server(player -> { fixture(player, index); sync(player); serverReady = true; });
    }
    private boolean arrived() {
        for (var progress : expected) {
            JsonObject view = ClientState.quests().stream().filter(q -> ClientState.text(q, "id", "").equals(progress.questId())).findFirst().orElse(null);
            if (view == null || !ClientState.text(view, "status", "").equals(progress.status().name()) || ClientState.bool(view, "tracked", false) != progress.tracked()) return false;
            for (JsonObject objective : ClientState.objects(view, "objectives")) if ((int) ClientState.number(objective, "current", 0) != progress.objectiveCounts().getOrDefault(ClientState.text(objective, "id", ""), 0)) return false;
        }
        return !expected.isEmpty();
    }
    private void show(Minecraft game) {
        Scene next = SCENES.get(scene); if (next.tab() == null) { game.setScreen(null); isolateNativePointer(); return; }
        var screen = new GuildQuestScreen(); game.setScreen(screen); screen.showTab(next.tab()); if (!next.id().isBlank()) screen.selectQuest(next.id());
        if (scene == 7) {
            Button button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b -> b.active && b.getMessage().getString().equals("领奖")).findFirst().orElseThrow(() -> new AssertionError("No final reward choice button"));
            button.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
            check(game.screen.getClass().getSimpleName().equals("GuildRewardChoiceScreen"), "Actual final reward widget opens the persistent-choice screen");
        }
    }
    private void tick(ClientTickEvent.Post event) {
        if (done) return; var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) initialize(game); if (failure != null) throw new RuntimeException("Quest fixture failed", failure);
            if ((System.nanoTime() - started) / 1e9 > 120) throw new AssertionError("Quest review exceeded 120 seconds");
            game.getFramerateLimitTracker().onInputReceived(); KeyMapping.releaseAll(); game.getToastManager().clear(); mouseX.setDouble(game.mouseHandler, -10); mouseY.setDouble(game.mouseHandler, -10);
            if (!languageReady || !serverReady || warmup++ < 40) return;
            if (scene < 0) { configure(game, 0); return; }
            if (captured) { if (writes.get() > 0) return; if (scene + 1 == SCENES.size()) finish(null); else configure(game, scene + 1); return; }
            if (!arrived()) return;
            if (!shown) { show(game); shown = true; localTicks = 0; return; }
            if (++localTicks < 16) return;
            check(game.getWindow().getGuiScale() == SCENES.get(scene).scale(), SCENES.get(scene).name() + ": actual GUI scale is correct");
            check(arrived(), SCENES.get(scene).name() + ": server progress arrives through the production snapshot");
            if (game.screen != null) {
                for (var child : game.screen.children()) if (child instanceof Button button) {
                    if (button.getX() < 0 || button.getY() < 0 || button.getX() + button.getWidth() > game.getWindow().getGuiScaledWidth() || button.getY() + button.getHeight() > game.getWindow().getGuiScaledHeight()) throw new AssertionError("Quest widget outside actual GUI bounds: " + button.getMessage().getString());
                }
                check(true, SCENES.get(scene).name() + ": every actual widget fits the scaled viewport");
            }
            if (scene == 3) check(QuestJournalModel.tracked(ClientState.quests()).size() == 3 && RpgHud.visible(), "Three independently selected tasks reach the actual gameplay HUD");
            if (scene == 8) check(ClientState.bool(ClientState.object(ClientState.snapshot(), "guild"), "nearGuildNpc", false), "The real nearby tagged guild NPC is reflected in client exam access");
            monitor(); requested = true;
        } catch (Throwable error) { finish(error); }
        finally { isolateNativePointer(); }
    }
    private void render(RenderFrameEvent.Post event) {
        if (done || !initialized) return;
        try {
            if (Minecraft.getInstance().mouseHandler.isMouseGrabbed()) grabbedPointerRenderFrames++;
            if (grabbedPointerRenderFrames > 0) throw new AssertionError("The quest test must never capture the OS pointer during any rendered gameplay or UI frame");
            if (!requested) return;
            requested = false; captured = true; var game = Minecraft.getInstance(); var next = SCENES.get(scene); var record = new JsonObject();
            record.addProperty("name", next.name()); record.addProperty("file", "screenshots/" + next.name() + ".png");
            record.addProperty("guiWidth", game.getWindow().getGuiScaledWidth()); record.addProperty("guiHeight", game.getWindow().getGuiScaledHeight()); record.addProperty("guiScale", game.getWindow().getGuiScale());
            record.addProperty("screen", game.screen == null ? "game" : game.screen.getClass().getSimpleName()); record.add("receivedSnapshot", ClientState.snapshot().deepCopy()); RpgSmoke.windowEnvironment(record); screenshots.add(record);
            writes.incrementAndGet(); Screenshot.grab(root.toFile(), next.name() + ".png", game.getMainRenderTarget(), 1, message -> {
                try { Path file = root.resolve(record.get("file").getAsString()); if (!Files.isRegularFile(file) || Files.size(file) < 1024) throw new AssertionError("Missing screenshot " + message.getString()); record.addProperty("sha256", sha(file)); }
                catch (Throwable error) { failure = error; } finally { writes.decrementAndGet(); }
            });
        } catch (Throwable error) { finish(error); }
    }
    private void isolateNativePointer() {
        if (done || !initialized) return;
        var mouse = Minecraft.getInstance().mouseHandler;
        if (mouse.isMouseGrabbed()) { mouse.releaseMouse(); nativeMouseReleases++; }
        mouse.setIgnoreFirstMove();
    }
    private void viewport(Minecraft game, int width, int height) {
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment); var bounds = environment.getAsJsonObject("monitorBounds");
        if (!MONITOR.equals(environment.get("monitorAdapter").getAsString()) || bounds.get("width").getAsInt() < width + 30 || bounds.get("height").getAsInt() < height + 60) throw new AssertionError("DISPLAY5 cannot contain quest viewport " + environment);
        long window = game.getWindow().handle(); GLFW.glfwSetWindowSize(window, width, height); GLFW.glfwSetWindowPos(window, bounds.get("x").getAsInt() + (bounds.get("width").getAsInt() - width) / 2, bounds.get("y").getAsInt() + (bounds.get("height").getAsInt() - height) / 2); monitor();
    }
    private void monitor() { var report = new JsonObject(); RpgSmoke.windowEnvironment(report); if (!MONITOR.equals(report.get("monitorAdapter").getAsString())) throw new AssertionError("Quest review must stay on DISPLAY5 " + report); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); synchronized (checks) { checks.add(message); } System.out.println("QUEST PASS " + message); }
    private static String sha(Path file) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private void finish(Throwable error) {
        if (done) return; done = true;
        try {
            var report = new JsonObject(); boolean passed = error == null && writes.get() == 0 && screenshots.size() == SCENES.size();
            report.addProperty("schemaVersion", 1); report.addProperty("passed", passed); report.addProperty("capturePassed", passed); report.addProperty("error", error == null ? null : error.toString());
            report.addProperty("scope", "Packaged JAR integrated-server quest intentions, real inventory and death events, currency, staged choices, NPC exams, persisted journal and actual wide/narrow UI");
            report.addProperty("world", String.valueOf(world)); report.addProperty("secondsAfterWorldReady", (System.nanoTime() - started) / 1e9);
            Path jar = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); report.addProperty("modJar", jar.toString()); report.addProperty("modJarSha256", Files.isRegularFile(jar) ? sha(jar) : "development-classes");
            report.addProperty("osPointerCaptured", grabbedPointerRenderFrames > 0); report.addProperty("grabbedPointerRenderFrames", grabbedPointerRenderFrames); report.addProperty("nativeMouseReleaseCount", nativeMouseReleases);
            report.addProperty("pointerIsolation", "MouseHandler.releaseMouse at client Pre/Post and immediately after screen changes; no OS focus or cursor-position API calls");
            synchronized (checks) { report.add("checksPassed", checks); } report.add("views", screenshots); RpgSmoke.windowEnvironment(report);
            RpgConfig.atomicWrite(root.resolve("quest-system-review.json"), RpgConfig.JSON.toJson(report));
            System.out.println("QUEST SYSTEM REVIEW COMPLETE " + root + " passed=" + passed);
        } catch (Throwable reportError) { reportError.printStackTrace(); }
        if (error != null) error.printStackTrace(); var game = Minecraft.getInstance();
        if (initialized) {
            game.mouseHandler.releaseMouse();
            game.options.guiScale().set(oldScale); game.options.framerateLimit().set(oldFps); game.options.renderDistance().set(oldDistance); game.options.hideGui = oldHide;
            game.options.pauseOnLostFocus = oldPause; game.options.enableVsync().set(oldVsync); game.options.setCameraType(oldCamera); game.options.languageCode = oldLanguage; game.getLanguageManager().setSelected(oldLanguage);
            try { mouseX.setDouble(game.mouseHandler, oldMouseX); mouseY.setDouble(game.mouseHandler, oldMouseY); } catch (ReflectiveOperationException ignored) { }
            game.resizeDisplay();
        }
        game.stop();
    }
}

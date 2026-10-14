package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.CropRewardScreen;
import org.fsrpg.client.QuestJournalScreen;
import org.fsrpg.client.RpgHud;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.quest.CollectionCatalog;
import org.fsrpg.quest.CollectionService;
import org.fsrpg.quest.CollectionStore;
import org.fsrpg.quest.CollectionInventory;
import org.fsrpg.combat.CombatPhase;
import org.lwjgl.glfw.GLFW;

/** Real integrated-server transactions and actual screens; exclusively a marked copied world. */
final class CollectionReview {
    private static final String MONITOR = "\\\\.\\DISPLAY5", WORLD_PREFIX = "FSRPG Collection Review ";
    private static final Path ROOT = Path.of(System.getProperty("fsrpg.smokeEvidence", "../../verification/collection-20261011/game")).toAbsolutePath().normalize();
    private record Scene(String name, int scale, int width, int height, int screen) {}
    private static final List<Scene> SCENES = List.of(
            new Scene("00-unarmed-hud-resident", 2, 1280, 720, 0),
            new Scene("01-introduction-wide", 2, 1280, 720, 1),
            new Scene("02-zombie-requirements-ready", 2, 1280, 720, 2),
            new Scene("03-zombie-submitted-narrow", 4, 1280, 960, 2),
            new Scene("04-potato-needs-eleven", 3, 1200, 720, 3),
            new Scene("05-potato-pending-choice", 2, 1280, 720, 3),
            new Scene("06-seed-choice-wide", 2, 1280, 720, 4),
            new Scene("07-seed-choice-narrow", 4, 1280, 960, 4),
            new Scene("08-inventory-reward-wide", 2, 1280, 720, 5),
            new Scene("09-milestones-completed", 2, 1280, 720, 1),
            new Scene("10-all-seeds-inventory-narrow", 4, 1280, 960, 5),
            new Scene("11-single-stance-auto-drawn", 2, 1280, 720, 0),
            new Scene("12-ordinary-item-sheath", 2, 1280, 720, 0),
            new Scene("13-multiple-stances-first-r", 2, 1280, 720, 0),
            new Scene("14-multiple-stances-second-r", 2, 1280, 720, 0),
            new Scene("15-multiple-stances-return-r", 2, 1280, 720, 0));
    private final Path directory = ROOT.resolve("run-" + Instant.now().toString().replace(':', '-'));
    private final List<String> checks = new ArrayList<>();
    private final JsonArray images = new JsonArray();
    private final AtomicInteger writes = new AtomicInteger();
    private volatile Throwable failure;
    private volatile boolean serverReady;
    private volatile int expectedMobs, expectedCrops;
    private volatile boolean expectedFirstHunt;
    private UUID playerId;
    private Path worldPath;
    private boolean initialized, done, requested, captured;
    private int scene = -1, sceneTicks, warmup, resourceLayers, previousScale, previousFps, previousDistance;
    private boolean previousHidden, previousPause, previousVsync;
    private CameraType previousCamera;
    private long startNanos;
    private CollectionService collections;
    private RpgConfig liveConfig;
    private RpgConfig.Definitions originalDefinitions;
    private volatile boolean weaponVerified;
    private boolean weaponVerificationQueued;
    private boolean contextPrepared;
    private String previousLanguage;
    private volatile boolean languageReady;
    private java.lang.reflect.Field mouseX, mouseY;
    private double previousMouseX, previousMouseY;

    CollectionReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((RenderGuiLayerEvent.Post event) -> { if (!done && event.getName().equals(RpgHud.LAYER)) resourceLayers++; });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }
    private void initialize(Minecraft game) throws Exception {
        startNanos = System.nanoTime(); Files.createDirectories(directory.resolve("screenshots")); monitorCheck();
        String configured = System.getProperty("fsrpg.collectionWorld", "");
        worldPath = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        check(configured.startsWith(WORLD_PREFIX) && worldPath.getFileName().toString().equals(configured), "Only the specifically named collection copy is modified");
        check(Files.isRegularFile(worldPath.resolve(".fsrpg-collection-test-world")), "Copied collection world has its test-only marker");
        playerId = game.player.getUUID(); previousScale = game.options.guiScale().get(); previousHidden = game.options.hideGui;
        previousPause = game.options.pauseOnLostFocus; previousFps = game.options.framerateLimit().get(); previousVsync = game.options.enableVsync().get();
        previousDistance = game.options.renderDistance().get(); previousCamera = game.options.getCameraType();
        previousLanguage = game.options.languageCode;
        if (game.getLanguageManager().getSelected().equals("zh_cn")) languageReady = true;
        else {
            game.getLanguageManager().setSelected("zh_cn"); game.options.languageCode = "zh_cn";
            game.reloadResourcePacks().whenComplete((unused, error) -> { if (error != null) failure = error; languageReady = error == null; });
        }
        mouseX = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos"); mouseY = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
        mouseX.setAccessible(true); mouseY.setAccessible(true); previousMouseX = mouseX.getDouble(game.mouseHandler); previousMouseY = mouseY.getDouble(game.mouseHandler);
        game.options.pauseOnLostFocus = false; game.options.hideGui = false; game.options.framerateLimit().set(60);
        game.options.enableVsync().set(false); game.options.renderDistance().set(4); game.options.setCameraType(CameraType.FIRST_PERSON);
        game.getTutorial().setStep(TutorialSteps.NONE); KeyMapping.releaseAll(); initialized = true;
        GLFW.glfwSetWindowTitle(game.getWindow().handle(), "FSRPG 新任务与常驻 HUD 验证 · DISPLAY5 · 完成后自动关闭");
        server(player -> {
            collections = service();
            var old = collections.snapshot(); var players = new LinkedHashMap<>(old.players()); players.remove(playerId);
            var pending = new LinkedHashMap<>(old.pending()); pending.remove(playerId);
            collections.restore(new CollectionService.Snapshot(CollectionService.SCHEMA_VERSION, players, pending));
            player.setGameMode(GameType.SURVIVAL); player.setHealth(20); player.getFoodData().setFoodLevel(20);
            player.level().setDayTime(18000); player.level().setWeatherParameters(6000, 0, false, false);
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) player.level().setBlock(new BlockPos(x, 79, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = 80; y <= 83; y++) player.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            player.connection.teleport(.5, 80, .5, 0, 9); player.getInventory().clearContent();
            fill(player, collections.catalog().mobRequirements("minecraft:zombie"));
            Map<String, Integer> before = owned(player); action(player, "collectionSubmitMob", "minecraft:zombie");
            check(collections.progress(playerId).mobCount() == 0 && owned(player).equals(before), "Biological submissions are gated by first hunt without consuming anything");
            Zombie zombie = new Zombie(player.level()); zombie.setNoAi(true); zombie.setPos(8.5, 80, .5); player.level().addFreshEntity(zombie);
            zombie.hurtServer(player.level(), player.damageSources().playerAttack(player), 1000);
            check(collections.progress(playerId).firstHuntComplete() && collections.progress(playerId).firstHuntEntity().equals("minecraft:zombie"), "Actual player damage and Zombie death event complete the first hunt");
            player.getInventory().clearContent(); fill(player, collections.catalog().mobRequirements("minecraft:zombie"));
            check(collections.catalog().mobRequirements("minecraft:zombie").size() == 4, "Zombie submission includes rotten flesh, iron, carrot and potato, excluding equipment");
            synchronize(player); serverReady = true;
        });
    }
    private CollectionService service() {
        try { for (var field : RpgServer.class.getDeclaredFields()) if (field.getType().equals(CollectionService.class)) { field.setAccessible(true); return (CollectionService) field.get(RpgServer.instance()); } }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        throw new IllegalStateException("CollectionService field is unavailable");
    }
    private void server(Consumer<ServerPlayer> work) {
        var game = Minecraft.getInstance(); var integrated = game.getSingleplayerServer();
        integrated.execute(() -> { try { var player = integrated.getPlayerList().getPlayer(playerId); if (player == null) throw new IllegalStateException("Collection test player left"); work.accept(player); } catch (Throwable error) { failure = error; } });
    }
    private static void action(ServerPlayer player, String intent, String argument) { RpgServer.instance().action(player, intent, argument); }
    private void synchronize(ServerPlayer player) {
        var state = collections.progress(playerId); expectedMobs = state.mobCount(); expectedCrops = state.cropCount(); expectedFirstHunt = state.firstHuntComplete();
        action(player, "status", "");
    }
    private static ItemStack stack(CollectionCatalog.Item item) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item.id())), item.count());
        if (!item.potion().isEmpty()) BuiltInRegistries.POTION.get(Identifier.parse(item.potion())).ifPresent(holder -> stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder)));
        return stack;
    }
    private static void fill(ServerPlayer player, List<CollectionCatalog.Item> ingredients) {
        for (var item : ingredients) if (!player.getInventory().add(stack(item))) throw new AssertionError("Fixture ingredients did not fit: " + item);
    }
    private static Map<String, Integer> owned(ServerPlayer player) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot); if (item.isEmpty()) continue;
            String key = BuiltInRegistries.ITEM.getKey(item.getItem()).toString(); var potion = item.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.potion().isPresent()) key += "@" + BuiltInRegistries.POTION.getKey(potion.potion().get().value());
            values.merge(key, item.getCount(), Integer::sum);
        }
        return values;
    }
    private void configure(Minecraft game, int index) {
        scene = index; sceneTicks = 0; requested = false; captured = false; resourceLayers = 0; serverReady = false;
        weaponVerified = false; weaponVerificationQueued = false; contextPrepared = false;
        Scene planned = SCENES.get(index); viewport(game, planned.width(), planned.height());
        game.options.guiScale().set(planned.scale()); game.options.hideGui = false; game.resizeDisplay();
        server(player -> { fixture(player, index); synchronize(player); serverReady = true; });
        showScreen(game, planned.screen());
    }
    private void showScreen(Minecraft game, int kind) {
        QuestJournalScreen journal = new QuestJournalScreen();
        if (kind == 0) game.setScreen(null);
        else if (kind == 5) game.setScreen(new InventoryScreen(game.player));
        else {
            game.setScreen(journal);
            if (kind == 2) { journal.showTab(QuestJournalScreen.Tab.MOBS); journal.selectEntry("minecraft:zombie"); }
            if (kind == 3 || kind == 4) { journal.showTab(QuestJournalScreen.Tab.CROPS); journal.selectEntry("minecraft:potato"); }
            if (kind == 4) game.setScreen(new CropRewardScreen(journal, "minecraft:potato"));
        }
    }
    private void fixture(ServerPlayer player, int index) {
        var catalog = collections.catalog();
        if (index == 3) {
            action(player, "collectionSubmitMob", "minecraft:zombie");
            check(collections.progress(playerId).submittedMobs().contains("minecraft:zombie"), "Zombie completion is persisted by the actual server submission intent");
            for (var reward : catalog.mobRewards("minecraft:zombie")) check(owned(player).getOrDefault(reward.key(), 0) == reward.count(), "Zombie reward grants two of " + reward.key());
            var before = owned(player); action(player, "collectionSubmitMob", "minecraft:zombie");
            check(owned(player).equals(before) && collections.progress(playerId).mobCount() == 1, "Repeated zombie submission does not consume or duplicate rewards");
            player.getInventory().clearContent(); before = owned(player); action(player, "collectionSubmitMob", "minecraft:blaze");
            check(owned(player).equals(before) && collections.progress(playerId).mobCount() == 1, "Insufficient ingredients leave inventory and collection count intact");
        }
        if (index == 4) {
            player.getInventory().clearContent(); player.getInventory().add(new ItemStack(Items.POTATO, 10));
            action(player, "collectionSubmitCrop", "minecraft:potato");
            check(owned(player).getOrDefault("minecraft:potato", 0) == 10 && collections.progress(playerId).cropCount() == 0, "Ten potatoes cannot double-count their seed; rejected crop consumes zero");
        }
        if (index == 5) {
            player.getInventory().clearContent(); fill(player, catalog.cropRequirements("minecraft:potato"));
            action(player, "collectionSubmitCrop", "minecraft:potato");
            check(owned(player).getOrDefault("minecraft:potato", 0) == 0 && collections.progress(playerId).pendingSeedChoices().contains("minecraft:potato"), "Eleven potatoes are deducted and create a durable seed reward choice");
            var before = owned(player); action(player, "collectionClaimSeeds", "minecraft:potato|minecraft:potato");
            check(owned(player).equals(before) && collections.progress(playerId).pendingSeedChoices().contains("minecraft:potato"), "Same-crop seed choice is rejected while preserving its reward");
            try {
                CollectionService restored = new CollectionService(catalog);
                restored.restore(new CollectionStore(worldPath.resolve("data/fsrpg/collections.json"), RpgConfig.JSON).load());
                check(restored.progress(playerId).pendingSeedChoices().contains("minecraft:potato"), "Pending seed choice survives loading the actual saved collections.json into a fresh service");
            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        }
        if (index == 8) {
            player.getInventory().clearContent(); for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            var before = owned(player); action(player, "collectionClaimSeeds", "minecraft:potato|minecraft:wheat");
            check(owned(player).equals(before) && collections.progress(playerId).pendingSeedChoices().contains("minecraft:potato"), "Full inventory refuses seed rewards without discarding or marking them claimed");
            player.getInventory().clearContent(); action(player, "collectionClaimSeeds", "minecraft:potato|minecraft:wheat");
            check(owned(player).getOrDefault("minecraft:wheat_seeds", 0) == 5 && owned(player).getOrDefault("minecraft:bone", 0) == 3, "Other-crop reward gives five wheat seeds and three bones");
            before = owned(player); action(player, "collectionClaimSeeds", "minecraft:potato|minecraft:wheat");
            check(owned(player).equals(before) && !collections.progress(playerId).pendingSeedChoices().contains("minecraft:potato"), "Repeated seed claim gives no additional items");
        }
        if (index == 9) {
            int remaining = 9;
            for (var mob : catalog.mobs()) {
                if (collections.progress(playerId).submittedMobs().contains(mob.id())) continue;
                player.getInventory().clearContent(); fill(player, catalog.mobRequirements(mob.id())); action(player, "collectionSubmitMob", mob.id());
                int count = collections.progress(playerId).mobCount();
                if (count == 5 || count == 10) for (var milestone : catalog.mobMilestones()) if (milestone.target() == count) {
                    check(collections.progress(playerId).claimedMobMilestones().contains(count), "Biological milestone " + count + " is automatically claimed");
                    for (var reward : milestone.rewards()) check(owned(player).getOrDefault(reward.key(), 0) >= reward.count(), "Biological milestone grants " + reward.key());
                }
                if (--remaining == 0) break;
            }
            check(collections.progress(playerId).mobCount() == 10, "Ten distinct biological submissions complete both iron milestones");
            remaining = 9;
            for (var crop : catalog.crops()) {
                if (collections.progress(playerId).submittedCrops().contains(crop.id())) continue;
                player.getInventory().clearContent(); fill(player, catalog.cropRequirements(crop.id())); action(player, "collectionSubmitCrop", crop.id());
                if (--remaining == 0) break;
            }
            check(collections.progress(playerId).cropCount() == 10 && collections.progress(playerId).cropMilestoneClaimed(), "Ten distinct crop submissions automatically complete the crop milestone");
            for (var seed : catalog.cropMilestoneRewards()) check(owned(player).getOrDefault(seed.key(), 0) >= seed.count(), "Crop milestone grants every configured plantable seed: " + seed.key());
            var before = owned(player); String cropId = catalog.crops().getFirst().id(); action(player, "collectionSubmitCrop", cropId);
            check(owned(player).equals(before), "Duplicate crop submission cannot reissue the all-seeds milestone");
        }
        if (index == 11) {
            adapterChecks(player);
            player.getInventory().clearContent(); player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD)); action(player, "status", "");
            var actor = RpgSmoke.actor(player);
            check(actor.phase() == CombatPhase.DRAWING || actor.phase() == CombatPhase.READY, "Equipping a single-stance sword automatically starts drawing on the server");
            action(player, "draw", "");
            check(actor.phase() != CombatPhase.SHEATHING && actor.phase() != CombatPhase.SHEATHED, "R never sheaths a single-stance weapon while drawing");
        }
        if (index == 12) {
            var actor = RpgSmoke.actor(player); check(actor.drawn() && actor.phase() == CombatPhase.READY, "Automatic single-stance draw naturally completed through server ticks");
            action(player, "draw", ""); check(actor.drawn() && actor.phase() == CombatPhase.READY, "R on a ready single-stance sword preserves its drawn state");
            player.getInventory().setItem(0, new ItemStack(Items.POTATO)); action(player, "status", "");
            check(!actor.drawn() && actor.phase() == CombatPhase.SHEATHED, "Changing from a sword to an ordinary potato immediately forces sheath");
        }
        if (index == 13) {
            installSecondStance();
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD)); action(player, "status", "");
            var actor = RpgSmoke.actor(player);
            check(!actor.drawn() && actor.phase() == CombatPhase.SHEATHED, "A weapon with multiple configured stances waits for R rather than automatically drawing");
            action(player, "draw", "");
            check(actor.style().id().equals("rapier") && (actor.phase() == CombatPhase.DRAWING || actor.phase() == CombatPhase.READY), "First R draws the current stance of the multi-stance weapon");
        }
        if (index == 14) {
            var actor = RpgSmoke.actor(player); check(actor.phase() == CombatPhase.READY, "First multi-stance draw completes through actual server ticks");
            action(player, "draw", "");
            check(actor.drawn() && actor.style().id().equals("rapier_review_second"), "Next R selects the second configured drawn stance");
            check(actor.style().presentationStyleId().equals("rapier"), "Custom stance preserves official rapier presentation routing");
        }
        if (index == 15) {
            var actor = RpgSmoke.actor(player); action(player, "draw", "");
            check(actor.drawn() && actor.style().id().equals("rapier"), "Next R wraps from the second stance back to the first");
            action(player, "attack", "left");
            check(actor.phase() == CombatPhase.WINDUP || actor.phase() == CombatPhase.ACTIVE, "Actual server attack enters a busy combat phase");
            String beforeStyle = actor.style().id(); action(player, "draw", "");
            check(actor.style().id().equals(beforeStyle) && actor.phase() != CombatPhase.SHEATHED, "R cannot change or sheath a multi-stance weapon during busy combat");
        }
    }

    private void adapterChecks(ServerPlayer player) {
        var catalog = collections.catalog(); player.getInventory().clearContent(); fill(player, catalog.mobRequirements("minecraft:stray"));
        int arrowSlot = -1;
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(Items.TIPPED_ARROW)) { arrowSlot = slot; break; }
        check(arrowSlot >= 0, "Stray fixture includes its potion-specific arrow ingredient");
        player.getInventory().setItem(arrowSlot, stack(new CollectionCatalog.Item("minecraft:tipped_arrow", 1, "minecraft:long_slowness")));
        check(!CollectionService.hasRequirements(catalog.mobRequirements("minecraft:stray"), CollectionInventory.owned(player)), "Long-slowness arrows cannot substitute for the required ordinary slowness arrows");
        action(player, "collectionSubmitMob", "minecraft:stray"); check(!collections.progress(playerId).submittedMobs().contains("minecraft:stray"), "Server rejects a different slowness potion without completing stray");
        ItemStack special = stack(new CollectionCatalog.Item("minecraft:tipped_arrow", 1, "minecraft:slowness"));
        PotionContents content = special.get(DataComponents.POTION_CONTENTS);
        special.set(DataComponents.POTION_CONTENTS, new PotionContents(content.potion(), java.util.Optional.empty(), List.of(new MobEffectInstance(MobEffects.POISON, 100)), java.util.Optional.empty()));
        player.getInventory().setItem(arrowSlot, special);
        check(!CollectionService.hasRequirements(catalog.mobRequirements("minecraft:stray"), CollectionInventory.owned(player)), "Additional custom effects cannot count as the exact required slowness arrow");
        player.getInventory().setItem(arrowSlot, stack(new CollectionCatalog.Item("minecraft:tipped_arrow", 1, "minecraft:slowness")));
        action(player, "collectionSubmitMob", "minecraft:stray");
        check(collections.progress(playerId).submittedMobs().contains("minecraft:stray"), "Ordinary slowness arrow is accepted by the actual server inventory exchange");
        player.getInventory().clearContent(); for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().setItem(0, new ItemStack(Items.BONE, 61)); player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.CARROT));
        action(player, "collectionClaimSeeds", "minecraft:wheat|minecraft:carrot");
        check(player.getOffhandItem().is(Items.CARROT) && player.getOffhandItem().getCount() == 6 && player.getInventory().getItem(0).getCount() == 64,
                "A full main inventory can merge five rewards into an existing offhand carrot stack and three into its bone stack");
        check(!collections.progress(playerId).pendingSeedChoices().contains("minecraft:wheat"), "Successful occupied-offhand reward merge persists its claimed choice");
    }
    private void installSecondStance() {
        try {
            var configField = RpgServer.class.getDeclaredField("config"); configField.setAccessible(true); liveConfig = (RpgConfig) configField.get(RpgServer.instance());
            originalDefinitions = liveConfig.current();
            JsonArray styles = RpgConfig.JSON.toJsonTree(originalDefinitions.styles().values()).getAsJsonArray();
            JsonObject second = RpgConfig.JSON.toJsonTree(originalDefinitions.styles().get("rapier")).getAsJsonObject();
            second.addProperty("id", "rapier_review_second"); second.addProperty("label", "测试第二拔刀姿态"); styles.add(second);
            var candidate = RpgConfig.parse(RpgConfig.JSON.toJsonTree(originalDefinitions.combat()), RpgConfig.JSON.toJsonTree(originalDefinitions.skills().values()),
                    RpgConfig.JSON.toJsonTree(originalDefinitions.quests().definitions()), RpgConfig.JSON.toJsonTree(originalDefinitions.weapons()), styles,
                    RpgConfig.JSON.toJsonTree(originalDefinitions.collections().definition()));
            applyLiveDefinitions(candidate); check(liveConfig.current().styles().containsKey("rapier_review_second"), "Test-only second stance passed six-document parsing and live server apply without writing configuration files");
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private void applyLiveDefinitions(RpgConfig.Definitions definitions) throws ReflectiveOperationException {
        var current = RpgConfig.class.getDeclaredField("current"); current.setAccessible(true); current.set(liveConfig, definitions);
        var apply = RpgServer.class.getDeclaredMethod("applyConfig", RpgConfig.Definitions.class); apply.setAccessible(true); apply.invoke(RpgServer.instance(), definitions);
        collections = service();
    }
    private void verifyWeapon(ServerPlayer player, int index) {
        var actor = RpgSmoke.actor(player);
        if (index == 12) check(actor.phase() == CombatPhase.SHEATHED && !actor.drawn(), "Ordinary-item state stays sheathed after subsequent server ticks");
        else check(actor.phase() == CombatPhase.READY && actor.drawn(), "Weapon scene " + index + " reaches a ready drawn state through server ticks");
        synchronize(player); weaponVerified = true;
    }
    private void viewport(Minecraft game, int width, int height) {
        var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment); var bounds = environment.getAsJsonObject("monitorBounds");
        if (!MONITOR.equals(environment.get("monitorAdapter").getAsString()) || bounds.get("width").getAsInt() < width + 40 || bounds.get("height").getAsInt() < height + 60)
            throw new AssertionError("DISPLAY5 cannot contain the requested collection viewport: " + environment);
        long window = game.getWindow().handle(); GLFW.glfwSetWindowSize(window, width, height);
        GLFW.glfwSetWindowPos(window, bounds.get("x").getAsInt() + (bounds.get("width").getAsInt() - width) / 2, bounds.get("y").getAsInt() + (bounds.get("height").getAsInt() - height) / 2);
        monitorCheck();
    }
    private void tick(ClientTickEvent.Post event) {
        if (done) return; var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || game.getSingleplayerServer() == null) return;
        try {
            if (!initialized) initialize(game);
            if (failure != null) throw new RuntimeException("Collection fixture failed", failure);
            if ((System.nanoTime() - startNanos) / 1e9 > 300) throw new AssertionError("Collection review exceeded 300 seconds");
            game.getFramerateLimitTracker().onInputReceived(); KeyMapping.releaseAll();
            game.getToastManager().clear();
            // Move only the client's logical hover coordinates; the user's Windows cursor is untouched.
            mouseX.setDouble(game.mouseHandler, -10); mouseY.setDouble(game.mouseHandler, -10);
            if (!languageReady || !serverReady || warmup++ < 70) return;
            if (scene < 0) { configure(game, 0); return; }
            if (captured) { if (writes.get() != 0) return; if (scene + 1 == SCENES.size()) finish(null); else configure(game, scene + 1); return; }
            sceneTicks++;
            JsonObject collection = ClientState.collection();
            boolean arrived = (int) ClientState.number(collection, "mobCount", -1) == expectedMobs && (int) ClientState.number(collection, "cropCount", -1) == expectedCrops
                    && ClientState.bool(ClientState.object(collection, "firstHunt"), "complete", false) == expectedFirstHunt;
            if (arrived && !contextPrepared) { showScreen(game, SCENES.get(scene).screen()); contextPrepared = true; sceneTicks = 0; return; }
            if (sceneTicks < 24 || !arrived || resourceLayers < 2) return;
            if (scene >= 11 && !weaponVerified) { if (!weaponVerificationQueued) { weaponVerificationQueued = true; server(player -> verifyWeapon(player, scene)); } return; }
            if (scene >= 13) {
                check((int) ClientState.number(ClientState.snapshot(), "drawStateCount", 0) == 2, SCENES.get(scene).name() + ": two server-defined matching drawn states reach the client");
                check(ClientState.text(ClientState.snapshot(), "weaponMode", "").equals("rapier"), SCENES.get(scene).name() + ": custom stance uses rapier presentation");
            }
            check(RpgHud.visible(), SCENES.get(scene).name() + ": HUD remains visible with the current screen and equipment");
            check(game.getWindow().getGuiScale() == SCENES.get(scene).scale(), SCENES.get(scene).name() + ": requested actual GUI scale");
            check(arrived, SCENES.get(scene).name() + ": actual server progress reached the normal client snapshot");
            if (SCENES.get(scene).screen() == 5) check(game.screen instanceof InventoryScreen, SCENES.get(scene).name() + ": actual player inventory is open");
            monitorCheck(); requested = true;
        } catch (Throwable error) { finish(error); }
    }
    private void render(RenderFrameEvent.Post event) {
        if (done || !requested) return;
        try {
            requested = false; captured = true; var game = Minecraft.getInstance(); var planned = SCENES.get(scene); var record = new JsonObject();
            record.addProperty("name", planned.name()); record.addProperty("file", "screenshots/" + planned.name() + ".png");
            record.addProperty("guiWidth", game.getWindow().getGuiScaledWidth()); record.addProperty("guiHeight", game.getWindow().getGuiScaledHeight());
            record.addProperty("guiScale", game.getWindow().getGuiScale()); record.addProperty("hudVisible", RpgHud.visible()); record.addProperty("resourceLayerFrames", resourceLayers);
            record.addProperty("screen", game.screen == null ? "game" : game.screen.getClass().getSimpleName());
            record.add("receivedCollection", ClientState.collection().deepCopy()); RpgSmoke.windowEnvironment(record); images.add(record); writes.incrementAndGet();
            Screenshot.grab(directory.toFile(), planned.name() + ".png", game.getMainRenderTarget(), 1, message -> {
                try { Path file = directory.resolve(record.get("file").getAsString()); if (!Files.isRegularFile(file) || Files.size(file) < 1024) throw new AssertionError("Collection screenshot missing: " + message.getString()); record.addProperty("sha256", sha(file)); }
                catch (Throwable error) { failure = error; } finally { writes.decrementAndGet(); }
            });
        } catch (Throwable error) { finish(error); }
    }
    private void monitorCheck() { var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment); if (!MONITOR.equals(environment.get("monitorAdapter").getAsString())) throw new AssertionError("Collection review window must stay on DISPLAY5: " + environment); }
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); synchronized (checks) { checks.add(text); } System.out.println("COLLECTION PASS " + text); }
    private static String sha(Path file) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private void finish(Throwable error) {
        if (done) return; done = true;
        if (originalDefinitions != null && Minecraft.getInstance().getSingleplayerServer() != null) {
            try {
                Minecraft.getInstance().getSingleplayerServer().executeBlocking(() -> {
                    try { applyLiveDefinitions(originalDefinitions); check(liveConfig.current() == originalDefinitions, "Original live definitions restored after the multi-stance review"); originalDefinitions = null; }
                    catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
                });
            } catch (Throwable restoreFailure) { if (error == null) error = restoreFailure; else error.addSuppressed(restoreFailure); }
        }
        try {
            var report = new JsonObject(); report.addProperty("schemaVersion", 1); report.addProperty("capturePassed", error == null && images.size() == SCENES.size() && writes.get() == 0);
            report.addProperty("scope", "Real server inventory exchanges, first-death event, persistent reward choices and actual task/inventory/HUD rendering");
            report.addProperty("error", error == null ? null : error.toString()); report.addProperty("expectedImages", SCENES.size()); report.addProperty("directory", directory.toString());
            report.addProperty("world", String.valueOf(worldPath)); report.addProperty("secondsAfterWorldReady", (System.nanoTime() - startNanos) / 1e9);
            synchronized (checks) { report.add("checksPassed", RpgConfig.JSON.toJsonTree(checks)); } report.add("views", images); RpgSmoke.windowEnvironment(report);
            Path source = Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); report.addProperty("jarSha256", Files.isRegularFile(source) ? sha(source) : "development-classes");
            RpgConfig.atomicWrite(directory.resolve("collection-review.json"), RpgConfig.JSON.toJson(report)); RpgConfig.atomicWrite(ROOT.resolve("latest.json"), RpgConfig.JSON.toJson(report));
            System.out.println("COLLECTION REVIEW COMPLETE " + directory + " capturePassed=" + report.get("capturePassed"));
        } catch (Throwable reportFailure) { reportFailure.printStackTrace(); }
        if (error != null) error.printStackTrace(); var game = Minecraft.getInstance();
        if (initialized) {
            game.options.guiScale().set(previousScale); game.options.hideGui = previousHidden; game.options.pauseOnLostFocus = previousPause;
            game.options.framerateLimit().set(previousFps); game.options.enableVsync().set(previousVsync); game.options.renderDistance().set(previousDistance); game.options.setCameraType(previousCamera);
            game.options.languageCode = previousLanguage; game.getLanguageManager().setSelected(previousLanguage);
            try { mouseX.setDouble(game.mouseHandler, previousMouseX); mouseY.setDouble(game.mouseHandler, previousMouseY); } catch (ReflectiveOperationException ignored) { }
            game.resizeDisplay();
        }
        game.stop();
    }
}

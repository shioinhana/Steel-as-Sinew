package org.fsrpg.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executed by Gradle questTest without Minecraft client or a game world. */
public final class QuestTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static int checks;

    public static void main(String[] args) throws Exception {
        progressionAndClaims();
        repeatableAndIsolatedPlayers();
        stableIdReloadAndAtomicStore();
        invalidCatalogsAndSnapshots();
        defaultCatalog();
        System.out.println("QuestTest passed: " + checks + " checks");
    }

    private static void progressionAndClaims() {
        QuestDefinition hunt = quest("hunt", List.of(), List.of(objective("kill", QuestDefinition.ObjectiveType.KILL, "minecraft:zombie", 2)), false, true);
        QuestDefinition followup = quest("followup", List.of("hunt"), List.of(objective("slash", QuestDefinition.ObjectiveType.USE_SKILL, "fsrpg:slash", 1)), false, true);
        QuestService service = new QuestService(QuestCatalog.of(List.of(hunt, followup)));
        UUID player = UUID.randomUUID();
        equal(QuestService.Status.ACTIVE, service.progress(player, "hunt").status(), "auto accept");
        equal(QuestService.Status.LOCKED, service.progress(player, "followup").status(), "prerequisite lock");
        rejects(() -> service.accept(player, "followup"), "cannot accept locked quest");
        service.kill(player, "minecraft:skeleton", 99);
        service.kill(player, "minecraft:zombie", -1);
        equal(0, service.progress(player, "hunt").objectiveCounts().get("kill"), "wrong or negative events ignored");
        equal(List.of(), service.kill(player, "minecraft:zombie", 1), "not ready after first kill");
        equal(List.of("hunt"), service.kill(player, "minecraft:zombie", Integer.MAX_VALUE), "large event clamps without overflow");
        equal(2, service.progress(player, "hunt").objectiveCounts().get("kill"), "kill count capped");
        equal(QuestService.Status.LOCKED, service.progress(player, "followup").status(), "ready is not claimed");
        equal(List.of(), service.kill(player, "minecraft:zombie", 1), "ready notification only once");
        equal(hunt.rewards(), service.claim(player, "hunt"), "rewards returned");
        equal(QuestService.Status.CLAIMED, service.progress(player, "hunt").status(), "claim persisted in state");
        equal(QuestService.Status.ACTIVE, service.progress(player, "followup").status(), "claim auto accepts followup");
        rejects(() -> service.claim(player, "hunt"), "duplicate reward claim rejected");
        rejects(() -> service.accept(player, "hunt"), "one shot cannot restart");
        equal(List.of("followup"), service.useSkill(player, "fsrpg:slash"), "skill completes followup");
    }

    private static void repeatableAndIsolatedPlayers() {
        QuestDefinition field = quest("field", List.of(), List.of(
                objective("flesh", QuestDefinition.ObjectiveType.COLLECT, "minecraft:rotten_flesh", 2),
                new QuestDefinition.Objective("visit", QuestDefinition.ObjectiveType.VISIT, null, 1,
                        new QuestDefinition.Location("minecraft:overworld", 0, 64, 0, 3))), true, false);
        QuestService service = new QuestService(QuestCatalog.of(List.of(field)));
        UUID player = UUID.randomUUID(), another = UUID.randomUUID();
        service.collect(player, "minecraft:rotten_flesh", 999);
        service.accept(player, "field");
        equal(0, service.progress(player, "field").objectiveCounts().get("flesh"), "events before acceptance ignored");
        service.visit(player, "minecraft:the_nether", 0, 64, 0);
        service.visit(player, "minecraft:overworld", Double.NaN, 64, 0);
        service.visit(player, "minecraft:overworld", 3.01, 64, 0);
        equal(0, service.progress(player, "field").objectiveCounts().get("visit"), "dimension, finite coordinate and radius checks");
        service.visit(player, "minecraft:overworld", 3, 64, 0);
        service.visit(player, "minecraft:overworld", 0, 64, 0);
        equal(1, service.progress(player, "field").objectiveCounts().get("visit"), "visit idempotent and boundary inclusive");
        service.collect(another, "minecraft:rotten_flesh", 999);
        equal(0, service.progress(player, "field").objectiveCounts().get("flesh"), "per-player progress isolation");
        equal(List.of("field"), service.collect(player, "minecraft:rotten_flesh", 2), "all objectives needed");
        service.claim(player, "field");
        equal(QuestService.Status.CLAIMED, service.progress(player, "field").status(), "repeat stays claimed until accepted");
        service.accept(player, "field");
        equal(1, service.progress(player, "field").completions(), "repeat preserves completion history");
        equal(0, service.progress(player, "field").objectiveCounts().get("visit"), "repeat resets objective progress");
        rejects(() -> service.claim(player, "field"), "repeat cannot immediately claim again");
        service.collect(player, "minecraft:rotten_flesh", 2);
        service.visit(player, "minecraft:overworld", 0, 64, 0);
        service.claim(player, "field");
        equal(2, service.progress(player, "field").completions(), "second completion recorded");
    }

    private static void stableIdReloadAndAtomicStore() throws Exception {
        QuestDefinition original = quest("hunt", List.of(), List.of(objective("kill", QuestDefinition.ObjectiveType.KILL, "minecraft:zombie", 2)), false, false);
        QuestService service = new QuestService(QuestCatalog.of(List.of(original)));
        UUID player = UUID.randomUUID();
        service.accept(player, "hunt");
        service.kill(player, "minecraft:zombie", 2);
        Path directory = Files.createTempDirectory("fsrpg-quests-test-");
        Path file = directory.resolve("任务进度.json");
        QuestStore store = new QuestStore(file, GSON);
        try {
            equal(0, store.load().players().size(), "missing file loads empty state");
            store.save(service.snapshot());
            QuestService restored = new QuestService(QuestCatalog.of(List.of(original)));
            restored.restore(store.load());
            equal(QuestService.Status.READY, restored.progress(player, "hunt").status(), "UUID and record JSON roundtrip");
            restored.claim(player, "hunt");
            store.save(restored.snapshot());
            service.restore(store.load());
            rejects(() -> service.claim(player, "hunt"), "claim survives process reload");
            equal(QuestService.Status.CLAIMED, service.progress(player, "hunt").status(), "atomic replacement preserves claim");

            QuestService changed = new QuestService(QuestCatalog.of(List.of(quest("hunt", List.of(), List.of(
                    objective("kill", QuestDefinition.ObjectiveType.KILL, "minecraft:zombie", 3),
                    objective("skill", QuestDefinition.ObjectiveType.USE_SKILL, "fsrpg:slash", 1)), false, false))));
            QuestService notClaimed = new QuestService(QuestCatalog.of(List.of(original)));
            notClaimed.accept(player, "hunt");
            notClaimed.kill(player, "minecraft:zombie", 2);
            changed.restore(notClaimed.snapshot());
            equal(2, changed.progress(player, "hunt").objectiveCounts().get("kill"), "stable objective id keeps progress");
            equal(0, changed.progress(player, "hunt").objectiveCounts().get("skill"), "new objective starts at zero");
            equal(QuestService.Status.ACTIVE, changed.progress(player, "hunt").status(), "changed requirements recompute ready");

            QuestService lowerTarget = new QuestService(QuestCatalog.of(List.of(quest("hunt", List.of(), List.of(
                    objective("kill", QuestDefinition.ObjectiveType.KILL, "minecraft:zombie", 1)), false, false))));
            lowerTarget.restore(notClaimed.snapshot());
            equal(1, lowerTarget.progress(player, "hunt").objectiveCounts().get("kill"), "lower target clamps progress");
            rejects(() -> service.snapshot().players().clear(), "snapshot outer map immutable");
            rejects(() -> service.snapshot().players().get(player).clear(), "snapshot inner map immutable");
            try (var files = Files.list(directory)) { equal(1L, files.count(), "temporary save files cleaned"); }
            Files.writeString(file, "{broken", StandardCharsets.UTF_8);
            rejectsChecked(store::load, "corrupt JSON fails without silently resetting progress");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void invalidCatalogsAndSnapshots() {
        QuestDefinition valid = quest("q", List.of(), List.of(objective("kill", QuestDefinition.ObjectiveType.KILL, "minecraft:zombie", 1)), false, false);
        rejects(() -> QuestCatalog.of(List.of(valid, valid)), "duplicate quest ids");
        rejects(() -> QuestCatalog.of(List.of(quest("q", List.of("missing"), valid.objectives(), false, false))), "unknown prerequisite");
        rejects(() -> QuestCatalog.of(List.of(
                quest("a", List.of("b"), valid.objectives(), false, false),
                quest("b", List.of("a"), valid.objectives(), false, false))), "prerequisite cycle");
        rejects(() -> QuestCatalog.of(List.of(quest("q", List.of(), List.of(valid.objectives().getFirst(), valid.objectives().getFirst()), false, false))), "duplicate objective ids");
        rejects(() -> QuestCatalog.of(List.of(quest("q", List.of(), List.of(objective("kill", null, "minecraft:zombie", 1)), false, false))), "unknown objective type");
        rejects(() -> QuestCatalog.of(List.of(quest("q", List.of(), List.of(objective("kill", QuestDefinition.ObjectiveType.KILL, "minecraft:zombie", 0)), false, false))), "zero target");
        rejects(() -> QuestCatalog.of(List.of(quest("q", List.of(), List.of(new QuestDefinition.Objective("visit", QuestDefinition.ObjectiveType.VISIT, null, 1,
                new QuestDefinition.Location("minecraft:overworld", Double.POSITIVE_INFINITY, 0, 0, 1))), false, false))), "nonfinite location");
        rejects(() -> QuestCatalog.of(List.of(quest("q", List.of(), List.of(objective("kill", QuestDefinition.ObjectiveType.KILL, "zombie", 1)), false, false))), "entity identifiers require namespace");
        QuestService service = new QuestService(QuestCatalog.of(List.of(valid)));
        UUID player = UUID.randomUUID();
        service.accept(player, "q");
        QuestService.Snapshot before = service.snapshot();
        rejects(() -> service.restore(new QuestService.Snapshot(999, Map.of())), "unknown save schema");
        rejects(() -> service.restore(new QuestService.Snapshot(1, Map.of(player, Map.of("q", new QuestService.StoredProgress(QuestService.Status.CLAIMED, Map.of("kill", 1), 0))))), "invalid claimed snapshot");
        equal(before, service.snapshot(), "failed restore does not damage live state");
    }

    private static void defaultCatalog() throws Exception {
        try (var input = QuestTest.class.getResourceAsStream("/defaults/quests.json")) {
            if (input == null) throw new AssertionError("default quests resource missing");
            QuestDefinition[] definitions = GSON.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), QuestDefinition[].class);
            QuestCatalog catalog = QuestCatalog.of(List.of(definitions));
            equal(3, catalog.definitions().size(), "three connected default tutorial quests");
            equal(List.of("fsrpg:first_hunt"), catalog.get("fsrpg:skill_training").prerequisites(), "default tutorial prerequisites");
            equal("初次狩猎", catalog.get("fsrpg:first_hunt").label(), "UTF-8 defaults");
        }
    }

    private static QuestDefinition quest(String id, List<String> prerequisites, List<QuestDefinition.Objective> objectives, boolean repeatable, boolean autoAccept) {
        return new QuestDefinition(id, id, "test quest", prerequisites, objectives,
                new QuestDefinition.Rewards(List.of(new QuestDefinition.RewardItem("minecraft:bread", 1)), 10), repeatable, autoAccept);
    }
    private static QuestDefinition.Objective objective(String id, QuestDefinition.ObjectiveType type, String target, int count) {
        return new QuestDefinition.Objective(id, type, target, count, null);
    }
    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message + ": action unexpectedly succeeded");
    }
    @FunctionalInterface private interface Checked { Object run() throws Exception; }
    private static void rejectsChecked(Checked action, String message) {
        checks++;
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError(message + ": action unexpectedly succeeded");
    }
}

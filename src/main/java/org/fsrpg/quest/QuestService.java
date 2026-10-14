package org.fsrpg.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative progression; callers feed only successful, trusted game events. */
public final class QuestService {
    public static final int SCHEMA_VERSION = 1;
    public enum Status { LOCKED, AVAILABLE, ACTIVE, READY, CLAIMED }
    public record QuestProgress(String questId, Status status, Map<String, Integer> objectiveCounts, int completions) {
        public QuestProgress { objectiveCounts = Map.copyOf(objectiveCounts); }
    }
    public record StoredProgress(Status status, Map<String, Integer> objectiveCounts, int completions) {
        public StoredProgress { objectiveCounts = objectiveCounts == null ? Map.of() : Map.copyOf(objectiveCounts); }
    }
    public record Snapshot(int schemaVersion, Map<UUID, Map<String, StoredProgress>> players) {}

    private final QuestCatalog catalog;
    private final Map<UUID, Map<String, StoredProgress>> players = new LinkedHashMap<>();
    private long revision;

    public QuestService(QuestCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }
    public QuestCatalog catalog() { return catalog; }
    public synchronized long revision() { return revision; }

    /** Initial auto-accept and prerequisite unlocks never restart a completed repeatable quest. */
    public synchronized void ensurePlayer(UUID player) {
        Map<String, StoredProgress> state = playerState(player);
        for (QuestDefinition quest : catalog.definitions()) {
            if (quest.autoAccept() && !state.containsKey(quest.id()) && prerequisitesMet(quest, state)) {
                state.put(quest.id(), fresh(quest, 0));
                revision++;
            }
        }
    }

    public synchronized QuestProgress accept(UUID player, String questId) {
        QuestDefinition quest = catalog.get(questId);
        Map<String, StoredProgress> state = playerState(player);
        if (!prerequisitesMet(quest, state)) throw new IllegalStateException("quest prerequisites are not complete: " + questId);
        StoredProgress previous = state.get(questId);
        if (previous != null && (previous.status() != Status.CLAIMED || !quest.repeatable())) {
            throw new IllegalStateException("quest is already accepted or completed: " + questId);
        }
        StoredProgress started = fresh(quest, previous == null ? 0 : previous.completions());
        state.put(questId, started);
        revision++;
        return view(quest, started, state);
    }

    public synchronized List<String> kill(UUID player, String entityType, int amount) {
        return advance(player, QuestDefinition.ObjectiveType.KILL, entityType, amount, null, 0, 0, 0);
    }
    /** COLLECT counts items acquired after acceptance; claiming does not consume those items. */
    public synchronized List<String> collect(UUID player, String itemId, int amount) {
        return advance(player, QuestDefinition.ObjectiveType.COLLECT, itemId, amount, null, 0, 0, 0);
    }
    public synchronized List<String> useSkill(UUID player, String skillId) {
        return advance(player, QuestDefinition.ObjectiveType.USE_SKILL, skillId, 1, null, 0, 0, 0);
    }
    public synchronized List<String> visit(UUID player, String dimension, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return List.of();
        return advance(player, QuestDefinition.ObjectiveType.VISIT, null, 1, dimension, x, y, z);
    }

    public synchronized List<QuestProgress> progress(UUID player) {
        ensurePlayer(player);
        Map<String, StoredProgress> state = playerState(player);
        return catalog.definitions().stream().map(q -> view(q, state.get(q.id()), state)).toList();
    }

    public synchronized QuestProgress progress(UUID player, String questId) {
        ensurePlayer(player);
        QuestDefinition quest = catalog.get(questId);
        Map<String, StoredProgress> state = playerState(player);
        return view(quest, state.get(questId), state);
    }

    /** Marks the claim before returning rewards. Persist the snapshot before issuing external rewards. */
    public synchronized QuestDefinition.Rewards claim(UUID player, String questId) {
        QuestDefinition quest = catalog.get(questId);
        Map<String, StoredProgress> state = playerState(player);
        StoredProgress progress = state.get(questId);
        if (progress == null || progress.status() != Status.READY) {
            throw new IllegalStateException("quest is not ready to claim: " + questId);
        }
        if (progress.completions() == Integer.MAX_VALUE) throw new IllegalStateException("quest completion limit reached: " + questId);
        state.put(questId, new StoredProgress(Status.CLAIMED, progress.objectiveCounts(), progress.completions() + 1));
        revision++;
        ensurePlayer(player);
        return quest.rewards();
    }

    public synchronized Snapshot snapshot() {
        Map<UUID, Map<String, StoredProgress>> snapshot = new LinkedHashMap<>();
        players.forEach((id, progress) -> snapshot.put(id, Map.copyOf(progress)));
        return new Snapshot(SCHEMA_VERSION, Map.copyOf(snapshot));
    }

    /** Stable objective IDs survive config edits. Counts are clamped to each current objective target. */
    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != SCHEMA_VERSION || snapshot.players() == null) {
            throw new IllegalArgumentException("unsupported or missing quest snapshot schema");
        }
        Map<UUID, Map<String, StoredProgress>> restored = new LinkedHashMap<>();
        for (var entry : snapshot.players().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) throw new IllegalArgumentException("invalid player snapshot");
            Map<String, StoredProgress> quests = new LinkedHashMap<>();
            for (var questEntry : entry.getValue().entrySet()) {
                String id = questEntry.getKey();
                StoredProgress saved = questEntry.getValue();
                if (id == null || saved == null || saved.completions() < 0
                        || saved.status() == null || saved.status() == Status.LOCKED || saved.status() == Status.AVAILABLE) {
                    throw new IllegalArgumentException("invalid saved progress: " + id);
                }
                for (var count : saved.objectiveCounts().entrySet()) {
                    if (count.getKey() == null || count.getValue() == null || count.getValue() < 0) {
                        throw new IllegalArgumentException("invalid saved objective count: " + id);
                    }
                }
                if (saved.status() == Status.CLAIMED && saved.completions() == 0) {
                    throw new IllegalArgumentException("claimed quest is missing completion count: " + id);
                }
                if (!catalog.contains(id)) {
                    quests.put(id, saved); // Keep removed config entries for a future edit that restores them.
                    continue;
                }
                QuestDefinition quest = catalog.get(id);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (QuestDefinition.Objective objective : quest.objectives()) {
                    counts.put(objective.id(), Math.min(objective.count(), saved.objectiveCounts().getOrDefault(objective.id(), 0)));
                }
                Status status = saved.status() == Status.CLAIMED ? Status.CLAIMED : complete(quest, counts) ? Status.READY : Status.ACTIVE;
                quests.put(id, new StoredProgress(status, counts, saved.completions()));
            }
            restored.put(entry.getKey(), quests);
        }
        players.clear();
        players.putAll(restored);
        revision++;
    }

    private List<String> advance(UUID player, QuestDefinition.ObjectiveType type, String target, int amount,
                                 String dimension, double x, double y, double z) {
        if (amount < 1) return List.of();
        ensurePlayer(player);
        Map<String, StoredProgress> state = playerState(player);
        List<String> ready = new ArrayList<>();
        for (QuestDefinition quest : catalog.definitions()) {
            StoredProgress previous = state.get(quest.id());
            if (previous == null || previous.status() != Status.ACTIVE) continue;
            Map<String, Integer> counts = new LinkedHashMap<>(previous.objectiveCounts());
            boolean changed = false;
            for (QuestDefinition.Objective objective : quest.objectives()) {
                if (objective.type() != type) continue;
                boolean matches = type == QuestDefinition.ObjectiveType.VISIT
                        ? objective.location().contains(dimension, x, y, z) : Objects.equals(objective.target(), target);
                int count = counts.getOrDefault(objective.id(), 0);
                if (matches && count < objective.count()) {
                    counts.put(objective.id(), (int) Math.min((long) objective.count(), (long) count + amount));
                    changed = true;
                }
            }
            if (changed) {
                Status status = complete(quest, counts) ? Status.READY : Status.ACTIVE;
                state.put(quest.id(), new StoredProgress(status, counts, previous.completions()));
                revision++;
                if (status == Status.READY) ready.add(quest.id());
            }
        }
        return List.copyOf(ready);
    }

    private Map<String, StoredProgress> playerState(UUID player) {
        Objects.requireNonNull(player, "player");
        return players.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
    }
    private static StoredProgress fresh(QuestDefinition quest, int completions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        quest.objectives().forEach(objective -> counts.put(objective.id(), 0));
        return new StoredProgress(Status.ACTIVE, counts, completions);
    }
    private static boolean prerequisitesMet(QuestDefinition quest, Map<String, StoredProgress> state) {
        return quest.prerequisites().stream().allMatch(id -> state.containsKey(id) && state.get(id).completions() > 0);
    }
    private static boolean complete(QuestDefinition quest, Map<String, Integer> counts) {
        return quest.objectives().stream().allMatch(o -> counts.getOrDefault(o.id(), 0) >= o.count());
    }
    private static QuestProgress view(QuestDefinition quest, StoredProgress stored, Map<String, StoredProgress> state) {
        return stored == null
                ? new QuestProgress(quest.id(), prerequisitesMet(quest, state) ? Status.AVAILABLE : Status.LOCKED, Map.of(), 0)
                : new QuestProgress(quest.id(), stored.status(), stored.objectiveCounts(), stored.completions());
    }
}

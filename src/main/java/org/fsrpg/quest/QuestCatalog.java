package org.fsrpg.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates an entire catalog before it can become the server's active config. */
public final class QuestCatalog {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:/-]{1,128}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final Map<String, QuestDefinition> byId;
    private final List<QuestDefinition> definitions;

    private QuestCatalog(Map<String, QuestDefinition> byId) {
        this.byId = Map.copyOf(byId);
        this.definitions = List.copyOf(byId.values());
    }

    public static QuestCatalog of(Collection<QuestDefinition> definitions) {
        if (definitions == null || definitions.size() > 1024) fail("quest count must be at most 1024");
        Map<String, QuestDefinition> indexed = new LinkedHashMap<>();
        for (QuestDefinition quest : definitions) {
            validate(quest);
            if (indexed.putIfAbsent(quest.id(), quest) != null) fail("duplicate quest id: " + quest.id());
        }
        for (QuestDefinition quest : indexed.values()) {
            for (String required : quest.prerequisites()) {
                if (!indexed.containsKey(required)) fail(quest.id() + ": unknown prerequisite " + required);
            }
        }
        Map<String, Integer> visits = new HashMap<>();
        for (String id : indexed.keySet()) visit(id, indexed, visits, new ArrayList<>());
        return new QuestCatalog(indexed);
    }

    public List<QuestDefinition> definitions() { return definitions; }

    public QuestDefinition get(String id) {
        QuestDefinition definition = byId.get(id);
        if (definition == null) throw new IllegalArgumentException("unknown quest: " + id);
        return definition;
    }

    public boolean contains(String id) { return byId.containsKey(id); }

    private static void validate(QuestDefinition quest) {
        if (quest == null) fail("quest must not be null");
        validId(quest.id(), "quest id");
        if (quest.label() == null || quest.label().isBlank() || quest.label().length() > 256) {
            fail(quest.id() + ": label must contain 1..256 characters");
        }
        if (quest.description().length() > 4096) fail(quest.id() + ": description exceeds 4096 characters");
        Set<String> prerequisiteIds = new HashSet<>();
        for (String required : quest.prerequisites()) {
            validId(required, quest.id() + ": prerequisite");
            if (!prerequisiteIds.add(required)) fail(quest.id() + ": duplicate prerequisite " + required);
        }
        if (quest.objectives().isEmpty() || quest.objectives().size() > 64) {
            fail(quest.id() + ": objectives must contain 1..64 entries");
        }
        Set<String> objectiveIds = new HashSet<>();
        for (QuestDefinition.Objective objective : quest.objectives()) {
            if (objective == null) fail(quest.id() + ": objective must not be null");
            validId(objective.id(), quest.id() + ": objective id");
            if (!objectiveIds.add(objective.id())) fail(quest.id() + ": duplicate objective id " + objective.id());
            if (objective.type() == null) fail(quest.id() + "/" + objective.id() + ": unknown objective type");
            if (objective.count() < 1 || objective.count() > 1_000_000) {
                fail(quest.id() + "/" + objective.id() + ": count must be 1..1000000");
            }
            if (objective.type() == QuestDefinition.ObjectiveType.VISIT) {
                if (objective.count() != 1) fail(quest.id() + ": VISIT count must be 1");
                QuestDefinition.Location location = objective.location();
                if (location == null) fail(quest.id() + ": VISIT requires location");
                validResourceId(location.dimension(), quest.id() + ": visit dimension");
                if (!coordinate(location.x()) || !coordinate(location.y()) || !coordinate(location.z())
                        || !Double.isFinite(location.radius()) || location.radius() <= 0 || location.radius() > 10_000) {
                    fail(quest.id() + ": visit coordinates must be finite within world bounds; radius must be (0,10000]");
                }
            } else if (objective.type() == QuestDefinition.ObjectiveType.USE_SKILL) {
                validId(objective.target(), quest.id() + ": skill target");
            } else {
                validResourceId(objective.target(), quest.id() + ": objective target");
            }
        }
        if (quest.rewards().xp() < 0 || quest.rewards().xp() > 1_000_000) fail(quest.id() + ": reward xp must be 0..1000000");
        if (quest.rewards().items().size() > 64) fail(quest.id() + ": at most 64 reward items are allowed");
        for (QuestDefinition.RewardItem item : quest.rewards().items()) {
            if (item == null) fail(quest.id() + ": reward item must not be null");
            validResourceId(item.id(), quest.id() + ": reward item id");
            if (item.count() < 1 || item.count() > 4096) fail(quest.id() + ": reward item count must be 1..4096");
        }
    }

    private static void visit(String id, Map<String, QuestDefinition> indexed,
                              Map<String, Integer> visits, List<String> stack) {
        if (visits.getOrDefault(id, 0) == 2) return;
        if (visits.getOrDefault(id, 0) == 1) fail("cyclic quest prerequisites: " + stack + " -> " + id);
        visits.put(id, 1);
        stack.add(id);
        for (String dependency : indexed.get(id).prerequisites()) visit(dependency, indexed, visits, stack);
        stack.removeLast();
        visits.put(id, 2);
    }

    private static boolean coordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 30_000_000; }
    private static void validId(String value, String description) {
        if (value == null || !ID.matcher(value).matches()) fail(description + " is invalid: " + value);
    }
    private static void validResourceId(String value, String description) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) fail(description + " must be namespace:path: " + value);
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}

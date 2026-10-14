package org.fsrpg.quest;

import java.util.List;

/** Text-editable quest data. All Minecraft identifiers remain plain strings. */
public record QuestDefinition(
        String id,
        String label,
        String description,
        List<String> prerequisites,
        List<Objective> objectives,
        Rewards rewards,
        boolean repeatable,
        boolean autoAccept) {

    public QuestDefinition {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        rewards = rewards == null ? new Rewards(List.of(), 0) : rewards;
        description = description == null ? "" : description;
    }

    public enum ObjectiveType { KILL, COLLECT, VISIT, USE_SKILL }

    public record Objective(String id, ObjectiveType type, String target, int count, Location location) {}

    public record Location(String dimension, double x, double y, double z, double radius) {
        public boolean contains(String currentDimension, double currentX, double currentY, double currentZ) {
            if (!dimension.equals(currentDimension)) return false;
            double dx = currentX - x, dy = currentY - y, dz = currentZ - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    public record Rewards(List<RewardItem> items, int xp) {
        public Rewards {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record RewardItem(String id, int count) {}
}

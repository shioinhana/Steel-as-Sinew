package org.fsrpg.config;
import java.util.*;

/** Item lists and material damage multipliers are data, allowing other mods' weapons. */
public record WeaponConfig(Map<String, Group> groups, Map<String, Double> itemDamageMultipliers) {
    public record Group(List<String> mainItems, List<String> offhandItems) {
        public Group { mainItems = List.copyOf(mainItems); offhandItems = List.copyOf(offhandItems); }
    }
    public WeaponConfig {
        if (groups == null || groups.isEmpty() || groups.size() > 128) throw new IllegalArgumentException("weapons.groups must contain 1..128 groups");
        groups = Map.copyOf(groups);
        itemDamageMultipliers = itemDamageMultipliers == null ? Map.of() : Map.copyOf(itemDamageMultipliers);
        for (var entry : groups.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid weapon group " + entry.getKey());
            if (entry.getValue().mainItems().isEmpty()) throw new IllegalArgumentException("Empty weapon group " + entry.getKey());
            for (String id : entry.getValue().mainItems()) validId(id);
            for (String id : entry.getValue().offhandItems()) validId(id);
        }
        for (var entry : itemDamageMultipliers.entrySet()) {
            validId(entry.getKey());
            if (!Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > 100) throw new IllegalArgumentException("Invalid weapon multiplier " + entry.getKey());
        }
    }
    private static void validId(String id) { if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid item id " + id); }
    public boolean matches(String group, String main, String offhand) {
        if (group.equals("any")) return true;
        Group definition = groups.get(group);
        return definition != null && definition.mainItems().contains(main) && (definition.offhandItems().isEmpty() || definition.offhandItems().contains(offhand));
    }
    public double damageMultiplier(String item) { return itemDamageMultipliers.getOrDefault(item, 1.0); }
}

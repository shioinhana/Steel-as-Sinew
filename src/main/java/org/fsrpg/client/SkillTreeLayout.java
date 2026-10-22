package org.fsrpg.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure grouping for the skill-tree page: every node of one tree is placed in exactly one visible
 * branch section, so the whole tree fits on one page instead of being paged row by row.
 */
public final class SkillTreeLayout {
    public record Group(String caption, List<JsonObject> nodes) {}

    private record Section(String caption, Set<String> effects, List<String> order) {}

    private static final Map<String, List<Section>> SECTIONS = Map.of(
            "rapier", List.of(
                    new Section("入门", Set.of("ROOT_RAPIER"), List.of("ROOT_RAPIER")),
                    new Section("刺击", Set.of("RAPIER_THRUST"), List.of("RAPIER_THRUST")),
                    new Section("蓄力拨挡刺", Set.of("RAPIER_PARRY"), List.of("RAPIER_PARRY")),
                    new Section("缺血处决", Set.of("RAPIER_EXECUTION"), List.of("RAPIER_EXECUTION"))),
            "dual_blade", List.of(
                    new Section("入门", Set.of("ROOT_DUAL"), List.of("ROOT_DUAL")),
                    new Section("连击层数上限", Set.of("DUAL_COMBO_CAP"),
                            List.of("DUAL_COMBO_CAP"))),
            "bow", List.of(
                    new Section("入门", Set.of("ROOT_BOW"), List.of("ROOT_BOW")),
                    new Section("速射强化", Set.of("BOW_AUTO_CHARGE", "BOW_TRAJECTORY"),
                            List.of("BOW_AUTO_CHARGE", "BOW_TRAJECTORY")),
                    new Section("狙击镜", Set.of("BOW_SCOPE"), List.of("BOW_SCOPE")),
                    new Section("150 分支（二选一）", Set.of("RU_SUO", "NIE_SHI"), List.of("RU_SUO", "NIE_SHI")),
                    new Section("250 分支（二选一）", Set.of("YAN_XIA_DIAN", "CHUAN_TANG_FENG"),
                            List.of("YAN_XIA_DIAN", "CHUAN_TANG_FENG"))),
            "footwork", List.of(
                    new Section("流派（二选一）", Set.of("RABBIT", "FLYING_SWAN"), List.of("RABBIT", "FLYING_SWAN")),
                    new Section("登云", Set.of("CLOUD_STEP", "CLOUD_STEP_II", "CLOUD_STEP_III"),
                            List.of("CLOUD_STEP", "CLOUD_STEP_II", "CLOUD_STEP_III")),
                    new Section("若轻", Set.of("LIGHT_BODY", "LIGHT_BODY_II", "LIGHT_BODY_III"),
                            List.of("LIGHT_BODY", "LIGHT_BODY_II", "LIGHT_BODY_III"))));
    private SkillTreeLayout() {}

    /** One section per known branch family; unknown future effects land in "其他" instead of vanishing. */
    public static List<Group> groups(String tree, List<JsonObject> nodes) {
        if (tree == null || nodes == null) throw new IllegalArgumentException("tree and nodes are required");
        List<JsonObject> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparing(n -> Json.text(n, "id", "")));
        List<Group> result = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();
        for (Section section : SECTIONS.getOrDefault(tree, List.of())) {
            List<JsonObject> selected = new ArrayList<>();
            for (JsonObject node : ordered)
                if (section.effects().contains(Json.text(node, "effect", ""))) selected.add(node);
            selected.sort(Comparator.comparingInt(node -> section.order().indexOf(Json.text(node, "effect", ""))));
            if (section.effects().contains("DUAL_COMBO_CAP"))
                selected.sort(Comparator.comparingInt(SkillTreeLayout::comboRank));
            if (!selected.isEmpty()) {
                result.add(new Group(section.caption(), List.copyOf(selected)));
                selected.forEach(node -> used.add(Json.text(node, "id", "")));
            }
        }
        List<JsonObject> rest = ordered.stream().filter(node -> !used.contains(Json.text(node, "id", ""))).toList();
        if (!rest.isEmpty()) result.add(new Group("其他", rest));
        return List.copyOf(result);
    }

    /** Node ids in display order across all sections; used by tests and keyboard navigation. */
    public static List<String> flatten(List<Group> groups) {
        List<String> ids = new ArrayList<>();
        for (Group group : groups) for (JsonObject node : group.nodes()) ids.add(Json.text(node, "id", ""));
        return List.copyOf(ids);
    }

    /** Combo-cap nodes have no serialized value; their growth requirement orders 3/4/5 and the trial last. */
    private static int comboRank(JsonObject node) {
        JsonElement requirements = node.get("requirements");
        int score = 0;
        boolean trial = false;
        if (requirements != null && requirements.isJsonObject()) {
            JsonElement raw = requirements.getAsJsonObject().get("score");
            if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()) score = raw.getAsInt();
            JsonElement trialElement = requirements.getAsJsonObject().get("trial");
            trial = trialElement != null && trialElement.isJsonPrimitive() && trialElement.getAsJsonPrimitive().isBoolean()
                    && trialElement.getAsBoolean();
        }
        return trial && score == 0 ? Integer.MAX_VALUE : score;
    }

    private static final class Json {
        private Json() {}
        static String text(JsonObject object, String key, String fallback) {
            JsonElement value = object == null ? null : object.get(key);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    ? value.getAsString() : fallback;
        }
    }
}

package org.fsrpg.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every configured node must appear exactly once on one clean page, in its branch section. */
public final class SkillTreeLayoutTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        realConfiguration();
        fallbackSection();
        emptyTree();
        System.out.println("SkillTreeLayoutTest passed: " + checks + " checks");
    }

    private static void realConfiguration() throws Exception {
        Path file = Path.of("src/main/resources/defaults/skill_trees.json");
        if (!Files.isRegularFile(file)) file = Path.of("config/fsrpg/skill_trees.json");
        JsonArray all = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("nodes");
        check(all.size() == 25, "configured skill tree keeps 25 nodes");
        for (String tree : List.of("rapier", "dual_blade", "bow", "footwork")) {
            List<JsonObject> nodes = new ArrayList<>();
            for (JsonElement element : all)
                if (element.getAsJsonObject().get("tree").getAsString().equals(tree)) nodes.add(element.getAsJsonObject());
            List<SkillTreeLayout.Group> groups = SkillTreeLayout.groups(tree, nodes);
            List<String> flat = SkillTreeLayout.flatten(groups);
            equal(nodes.size(), flat.size(), tree + " shows every node once");
            equal(nodes.size(), new HashSet<>(flat).size(), tree + " has no duplicate cards");
            Set<String> expected = new HashSet<>();
            for (JsonObject node : nodes) expected.add(node.get("id").getAsString());
            equal(expected, new HashSet<>(flat), tree + " keeps the exact node set");
            equal(expectedCaptions(tree), groups.stream().map(SkillTreeLayout.Group::caption).toList(),
                    tree + " groups nodes by branch family");
            for (SkillTreeLayout.Group group : groups) check(!group.nodes().isEmpty(), tree + " has no empty section");
        }
        List<JsonObject> dual = nodesOf(all, "dual_blade");
        equal(List.of("dual_sword", "dual_combo", "dual_tier250", "dual_tier400", "dual_trial"),
                SkillTreeLayout.flatten(SkillTreeLayout.groups("dual_blade", dual)),
                "combo caps stay in tier order and the trial last");
        List<JsonObject> footwork = nodesOf(all, "footwork");
        List<SkillTreeLayout.Group> footworkGroups = SkillTreeLayout.groups("footwork", footwork);
        equal(2, footworkGroups.get(0).nodes().size(), "two exclusive styles stay together");
        equal(3, footworkGroups.get(1).nodes().size(), "cloud step tiers stay together");
        equal(3, footworkGroups.get(2).nodes().size(), "light body tiers stay together");
    }

    private static List<JsonObject> nodesOf(JsonArray all, String tree) {
        List<JsonObject> nodes = new ArrayList<>();
        for (JsonElement element : all)
            if (element.getAsJsonObject().get("tree").getAsString().equals(tree)) nodes.add(element.getAsJsonObject());
        return nodes;
    }

    private static List<String> expectedCaptions(String tree) {
        return switch (tree) {
            case "rapier" -> List.of("入门", "刺击", "蓄力拨挡刺", "缺血处决");
            case "dual_blade" -> List.of("入门", "连击层数上限");
            case "bow" -> List.of("入门", "速射强化", "狙击镜", "150 分支（二选一）", "250 分支（二选一）");
            default -> List.of("流派（二选一）", "登云", "若轻");
        };
    }

    private static void fallbackSection() {
        JsonObject known = node("known", "rapier", "ROOT_RAPIER");
        JsonObject unknown = node("future_branch", "rapier", "FUTURE_EFFECT");
        List<SkillTreeLayout.Group> groups = SkillTreeLayout.groups("rapier", List.of(known, unknown));
        equal(List.of("入门", "其他"), groups.stream().map(SkillTreeLayout.Group::caption).toList(),
                "unknown future effect is still shown in a fallback section");
        equal(List.of("known", "future_branch"), SkillTreeLayout.flatten(groups),
                "fallback keeps stable display order");
    }

    private static void emptyTree() {
        equal(List.of(), SkillTreeLayout.groups("bow", List.of()), "empty tree draws no sections");
    }

    private static JsonObject node(String id, String tree, String effect) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("tree", tree);
        node.addProperty("effect", effect);
        node.addProperty("label", id);
        return node;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": " + expected + " != " + actual);
    }
}

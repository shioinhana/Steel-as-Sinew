package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** An existing 0.8.x skill tree gains exactly the eight old footwork nodes and their rules; custom data survives. */
public final class FootworkSkillMigrationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        fresh();
        old();
        customValuesAndNodes();
        invalidCandidateWritesNothing();
        System.out.println("FootworkSkillMigrationTest passed: " + checks + " checks");
    }

    /** A brand-new install already contains the branch, so the migration only records completion. */
    private static void fresh() throws Exception {
        Path dir = fixture();
        try {
            check(Files.exists(dir.resolve(FootworkSkillMigration.MARKER)), "fresh config receives completion marker");
            check(!Files.exists(dir.resolve(FootworkSkillMigration.BACKUP)), "fresh config invents no prior backup");
            var trees = new RpgConfig(dir).reload().skillTrees();
            for (String id : FootworkSkillMigration.NODE_IDS)
                check(trees.node(id) != null, "fresh seed contains footwork node " + id);
            check(trees.rules().footwork().rabbitDistance() > 0, "fresh seed contains footwork rules");
        } finally {
            cleanup(dir);
        }
    }

    /** A released 0.8.x file has no footwork rules or nodes; only those are added. */
    private static void old() throws Exception {
        Path dir = fixture();
        try {
            Files.delete(dir.resolve(FootworkSkillMigration.MARKER));
            JsonObject before = read(dir);
            before.getAsJsonObject("rules").remove("footwork");
            removeFootworkNodes(before);
            String entry = " \n" + before + "\n";
            Files.writeString(dir.resolve("skill_trees.json"), entry);
            Map<String, byte[]> inventory = bytes(dir);
            var config = new RpgConfig(dir);
            var parsed = config.reload();
            for (String id : FootworkSkillMigration.NODE_IDS)
                check(parsed.skillTrees().node(id) != null, "missing footwork node added " + id);
            check(parsed.skillTrees().rules().footwork().selectionPoints() > 0, "missing footwork rules added");
            equal(entry, Files.readString(dir.resolve(FootworkSkillMigration.BACKUP).resolve("skill_trees.json")),
                    "exact pre-upgrade bytes backed up");
            var after = bytes(dir);
            var added = new TreeSet<>(after.keySet());
            added.removeAll(inventory.keySet());
            equal(Set.of(FootworkSkillMigration.MARKER, FootworkSkillMigration.BACKUP + "/skill_trees.json"),
                    added, "exactly one marker and one exact backup");
            for (var file : inventory.entrySet())
                if (!file.getKey().equals("skill_trees.json"))
                    check(Arrays.equals(file.getValue(), after.get(file.getKey())), "unrelated file preserved " + file.getKey());
            config.reload();
            sameBytes(after, bytes(dir), "reload byte-idempotent");
        } finally {
            cleanup(dir);
        }
    }

    /** Explicit custom footwork values and same-ID custom nodes must win over bundled defaults. */
    private static void customValuesAndNodes() throws Exception {
        Path dir = fixture();
        try {
            Files.delete(dir.resolve(FootworkSkillMigration.MARKER));
            JsonObject before = read(dir);
            before.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("rabbitDistance", 4.25);
            removeFootworkNodes(before);
            JsonObject custom = new JsonObject();
            custom.addProperty("id", "rabbit");
            custom.addProperty("tree", "footwork");
            custom.addProperty("label", "自定义脱兔");
            custom.addProperty("description", "custom");
            custom.addProperty("automatic", false);
            custom.add("cost", JsonParser.parseString("{\"mind\":0,\"body\":0,\"footwork\":0}"));
            custom.add("requirements", JsonParser.parseString(
                    "{\"mind\":0,\"body\":0,\"footwork\":0,\"score\":0,\"trial\":false,\"dragon\":false}"));
            custom.add("prerequisites", new JsonArray());
            custom.addProperty("exclusiveGroup", "footwork_style");
            custom.addProperty("effect", "RABBIT");
            custom.addProperty("value", 0);
            custom.add("skills", new JsonArray());
            before.getAsJsonArray("nodes").add(custom);
            Files.writeString(dir.resolve("skill_trees.json"), before.toString());
            var parsed = new RpgConfig(dir).reload();
            equal(4.25, parsed.skillTrees().rules().footwork().rabbitDistance(), "custom footwork field preserved");
            equal("自定义脱兔", parsed.skillTrees().node("rabbit").label(), "custom node with stable ID preserved");
            for (String id : FootworkSkillMigration.NODE_IDS)
                check(parsed.skillTrees().node(id) != null, "remaining footwork node still added " + id);
        } finally {
            cleanup(dir);
        }
    }

    /** A candidate that makes the complete catalog invalid must leave every byte untouched. */
    private static void invalidCandidateWritesNothing() throws Exception {
        Path dir = fixture();
        try {
            Files.delete(dir.resolve(FootworkSkillMigration.MARKER));
            JsonObject before = read(dir);
            before.getAsJsonObject("rules").remove("footwork");
            removeFootworkNodes(before);
            before.addProperty("unexpected", true);
            Files.writeString(dir.resolve("skill_trees.json"), before.toString());
            Map<String, byte[]> beforeBytes = bytes(dir);
            boolean rejected = false;
            try {
                new RpgConfig(dir).reload();
            } catch (RuntimeException expected) {
                rejected = true;
            }
            check(rejected, "invalid candidate rejected");
            sameBytes(beforeBytes, bytes(dir), "invalid migration writes nothing");
        } finally {
            cleanup(dir);
        }
    }

    private static Path fixture() throws Exception {
        Path path = Files.createTempDirectory("fsrpg-footwork-migration-");
        new RpgConfig(path).reload();
        return path;
    }

    private static JsonObject read(Path dir) throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve("skill_trees.json"))).getAsJsonObject();
    }

    private static void removeFootworkNodes(JsonObject trees) {
        JsonArray nodes = trees.getAsJsonArray("nodes");
        for (int i = nodes.size() - 1; i >= 0; i--) {
            JsonObject node = nodes.get(i).getAsJsonObject();
            if (node.has("tree") && node.get("tree").getAsString().equals("footwork")) nodes.remove(i);
        }
    }

    private static Map<String, byte[]> bytes(Path dir) throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                result.put(dir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return result;
    }

    private static void sameBytes(Map<String, byte[]> a, Map<String, byte[]> b, String message) {
        equal(a.keySet(), b.keySet(), message + " inventory");
        for (String key : a.keySet()) check(Arrays.equals(a.get(key), b.get(key)), message + " " + key);
    }

    private static void cleanup(Path dir) throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.toAbsolutePath().normalize().startsWith(root))
                    throw new IllegalStateException("fixture escape");
                Files.deleteIfExists(file);
            }
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": " + expected + " != " + actual);
    }
}

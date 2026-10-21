package org.fsrpg.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-time stock rapier technique enablement, custom preservation, exact backup and retry boundaries. */
public final class RapierTechniquesMigrationTest {
    private static int checks;
    private static final List<String> FILES = List.of("combat.json", "skills.json", "quests.json", "weapons.json", "styles.json",
            "collections.json", "skill_trees.json", "archery.json", "guard_cues.json");

    public static void main(String[] args) throws Exception {
        freshSeed(); stockEmptyMigration(); customPairPreserved(); legacyMissingTechniquesNotMigrated();
        invalidCandidateNotWritten(); bundledDefaults();
        System.out.println("RapierTechniquesMigrationTest passed: " + checks + " checks");
    }

    private static void freshSeed() throws Exception {
        Path dir = Files.createTempDirectory("fsrpg-rapier-techniques-fresh-");
        try {
            new RpgConfig(dir).reload();
            JsonObject techniques = rapierTechniques(read(dir, "styles.json"));
            equal(List.of(RapierTechniquesMigration.RIPOSTE_SKILL, RapierTechniquesMigration.RIPOSTE_WINDOW_TICKS,
                    RapierTechniquesMigration.REPRISE_SKILL, RapierTechniquesMigration.REPRISE_WINDOW_TICKS),
                    List.of(techniques.get("riposteSkill").getAsString(), techniques.get("riposteWindowTicks").getAsInt(),
                            techniques.get("repriseSkill").getAsString(), techniques.get("repriseWindowTicks").getAsInt()),
                    "fresh seed already carries the released conditional techniques");
            JsonObject marker = marker(dir);
            check(marker.get("changed").getAsBoolean() == false && marker.get("reason").getAsString().equals("already_released_defaults"),
                    "fresh seed needs no rewrite but records a completed check");
            var before = bytes(dir);
            new RpgConfig(dir).reload();
            sameBytes(before, bytes(dir), "second reload is byte exact");
            var definitions = new RpgConfig(dir).reload();
            equal("rapier_riposte", definitions.styles().get("rapier").techniques().riposteSkill(), "server sees the riposte technique");
            equal(20, definitions.styles().get("rapier").techniques().riposteWindowTicks(), "released riposte window");
            equal("rapier_reprise", definitions.styles().get("rapier").techniques().repriseSkill(), "server sees the reprise technique");
            equal(12, definitions.styles().get("rapier").techniques().repriseWindowTicks(), "released reprise window");
        } finally { cleanup(dir); }
    }

    private static void stockEmptyMigration() throws Exception {
        Path dir = fixture();
        try {
            writeRapierTechniques(dir, "", 0, "", 0);
            Map<String, byte[]> before = bytes(dir);
            byte[] stylesBefore = before.get("styles.json");
            JsonArray stylesBeforeJson = read(dir, "styles.json").getAsJsonArray();
            RapierTechniquesMigration.migrate(dir);
            JsonObject techniques = rapierTechniques(read(dir, "styles.json"));
            equal(RapierTechniquesMigration.RIPOSTE_SKILL, techniques.get("riposteSkill").getAsString(), "stock empty rapier gains riposte");
            equal(20, techniques.get("riposteWindowTicks").getAsInt(), "released riposte window is written");
            equal(RapierTechniquesMigration.REPRISE_SKILL, techniques.get("repriseSkill").getAsString(), "stock empty rapier gains reprise");
            equal(12, techniques.get("repriseWindowTicks").getAsInt(), "released reprise window is written");
            JsonArray stylesAfterJson = read(dir, "styles.json").getAsJsonArray();
            for (int i = 0; i < stylesBeforeJson.size(); i++) {
                String id = stylesAfterJson.get(i).getAsJsonObject().get("id").getAsString();
                if (id.equals("rapier")) continue;
                check(stylesBeforeJson.get(i).equals(stylesAfterJson.get(i)), "unrelated style field data stays identical: " + id);
            }
            JsonObject bundled = bundledRapierTechniques();
            check(bundled.equals(techniques), "migrated defaults match the bundled styles.json exactly");
            check(Arrays.equals(stylesBefore, Files.readAllBytes(dir.resolve(RapierTechniquesMigration.BACKUP).resolve("styles.json"))),
                    "the exact pre-migration styles bytes are backed up");
            for (String name : FILES) if (!name.equals("styles.json"))
                check(Arrays.equals(before.get(name), Files.readAllBytes(dir.resolve(name))), "unrelated file bytes retained: " + name);
            JsonObject marker = marker(dir);
            check(marker.get("changed").getAsBoolean() && marker.get("reason").getAsString().equals("enabled_released_defaults"),
                    "marker records the one-time enablement");
            check(marker.getAsJsonObject("previousValue").entrySet().stream()
                    .allMatch(entry -> entry.getKey().endsWith("Skill") ? entry.getValue().getAsString().isEmpty() : entry.getValue().getAsInt() == 0),
                    "marker stores the observed empty previous value");
            check(marker.getAsJsonObject("newValue").get("riposteSkill").getAsString().equals(RapierTechniquesMigration.RIPOSTE_SKILL),
                    "marker stores the new pair");
            check(marker.get("backup").getAsString().equals(RapierTechniquesMigration.BACKUP + "/styles.json"),
                    "marker points at the exact backup");
            check(marker.get("customTechniquesPreserved").getAsBoolean() && marker.get("unrelatedStylesPreserved").getAsBoolean(),
                    "marker declares field preservation");
            equal(RapierTechniquesMigration.RIPOSTE_SKILL,
                    new RpgConfig(dir).reload().styles().get("rapier").techniques().riposteSkill(),
                    "a complete reload passes with the enabled pair");
            // A completed marker makes repeat migration byte-idempotent even if the file were edited back.
            Map<String, byte[]> after = bytes(dir);
            RapierTechniquesMigration.migrate(dir);
            sameBytes(after, bytes(dir), "the marker prevents repeated migration");
            writeRapierTechniques(dir, "", 0, "", 0);
            byte[] reverted = Files.readAllBytes(dir.resolve("styles.json"));
            RapierTechniquesMigration.migrate(dir);
            check(Arrays.equals(reverted, Files.readAllBytes(dir.resolve("styles.json"))),
                    "a completed marker never re-applies the defaults after a later edit");
            // Without a marker the already-enabled stock pair is recognised and not rewritten again.
            writeRapierTechniques(dir, RapierTechniquesMigration.RIPOSTE_SKILL, RapierTechniquesMigration.RIPOSTE_WINDOW_TICKS,
                    RapierTechniquesMigration.REPRISE_SKILL, RapierTechniquesMigration.REPRISE_WINDOW_TICKS);
            Files.delete(dir.resolve(RapierTechniquesMigration.MARKER));
            byte[] enabled = Files.readAllBytes(dir.resolve("styles.json"));
            RapierTechniquesMigration.migrate(dir);
            check(Arrays.equals(enabled, Files.readAllBytes(dir.resolve("styles.json"))), "already-enabled defaults are not rewritten");
            check(marker(dir).get("reason").getAsString().equals("already_released_defaults"), "already-enabled defaults record a completed check");
        } finally { cleanup(dir); }
    }

    private static void customPairPreserved() throws Exception {
        Path dir = fixture();
        try {
            writeRapierTechniques(dir, RapierTechniquesMigration.RIPOSTE_SKILL, 10, "", 0);
            byte[] custom = Files.readAllBytes(dir.resolve("styles.json"));
            RapierTechniquesMigration.migrate(dir);
            check(Arrays.equals(custom, Files.readAllBytes(dir.resolve("styles.json"))),
                    "a custom non-empty technique pair is never rewritten");
            check(!Files.exists(dir.resolve(RapierTechniquesMigration.BACKUP)),
                    "custom preservation creates no fictitious backup");
            JsonObject marker = marker(dir);
            check(!marker.get("changed").getAsBoolean() && marker.get("reason").getAsString().equals("custom_techniques")
                    && marker.get("customTechniquesDetected").getAsBoolean() && marker.get("customTechniquesPreserved").getAsBoolean(),
                    "marker records that custom techniques were preserved");
            equal(10, new RpgConfig(dir).reload().styles().get("rapier").techniques().riposteWindowTicks(),
                    "the custom window still drives the server definition");
        } finally { cleanup(dir); }
    }

    private static void invalidCandidateNotWritten() throws Exception {
        Path dir = fixture();
        try {
            writeRapierTechniques(dir, "", 0, "", 0);
            byte[] stylesBefore = Files.readAllBytes(dir.resolve("styles.json"));
            byte[] skillsBefore = Files.readAllBytes(dir.resolve("skills.json"));
            byte[] treesBefore = Files.readAllBytes(dir.resolve("skill_trees.json"));
            // A partial custom catalog that has no conditional skill cannot receive the default pair.
            removeSkill(dir, RapierTechniquesMigration.RIPOSTE_SKILL);
            RapierTechniquesMigration.migrate(dir);
            check(Arrays.equals(stylesBefore, Files.readAllBytes(dir.resolve("styles.json"))),
                    "an invalid complete candidate leaves the style file untouched");
            check(!Files.exists(dir.resolve(RapierTechniquesMigration.BACKUP)) && !Files.exists(dir.resolve(RapierTechniquesMigration.MARKER)),
                    "an invalid candidate creates no backup or completed marker");
            // Repairing the catalog lets the same migration complete on retry.
            Files.write(dir.resolve("skills.json"), skillsBefore);
            Files.write(dir.resolve("skill_trees.json"), treesBefore);
            RapierTechniquesMigration.migrate(dir);
            equal(RapierTechniquesMigration.REPRISE_SKILL, rapierTechniques(read(dir, "styles.json")).get("repriseSkill").getAsString(),
                    "a repaired catalog retries the migration normally");
            check(Files.exists(dir.resolve(RapierTechniquesMigration.BACKUP).resolve("styles.json")) && Files.exists(dir.resolve(RapierTechniquesMigration.MARKER)),
                    "the repaired retry captures its backup and marker");
        } finally { cleanup(dir); }
    }

    private static void legacyMissingTechniquesNotMigrated() throws Exception {
        Path dir = fixture();
        try {
            JsonArray styles = read(dir, "styles.json").getAsJsonArray();
            find(styles, "rapier").remove("techniques");
            Files.writeString(dir.resolve("styles.json"), " \n" + RpgConfig.JSON.toJson(styles) + "\n");
            byte[] legacy = Files.readAllBytes(dir.resolve("styles.json"));
            RapierTechniquesMigration.migrate(dir);
            check(Arrays.equals(legacy, Files.readAllBytes(dir.resolve("styles.json"))),
                    "a pre-refinement style object without techniques is never given an invented pair");
            check(!Files.exists(dir.resolve(RapierTechniquesMigration.BACKUP)), "a missing techniques object creates no backup");
            JsonObject marker = marker(dir);
            check(!marker.get("changed").getAsBoolean() && marker.get("reason").getAsString().equals("missing_techniques"),
                    "marker records that the legacy minimal style was left untouched");
            check(new RpgConfig(dir).reload().styles().get("rapier").techniques()
                    .equals(org.fsrpg.combat.WeaponStyleDefinition.Techniques.none()), "a minimal legacy style still parses as techniques disabled");
        } finally { cleanup(dir); }
    }

    private static void bundledDefaults() throws Exception {
        JsonObject techniques = bundledRapierTechniques();
        equal(RapierTechniquesMigration.RIPOSTE_SKILL, techniques.get("riposteSkill").getAsString(), "bundled default enables riposte");
        equal(20, techniques.get("riposteWindowTicks").getAsInt(), "bundled default riposte window");
        equal(RapierTechniquesMigration.REPRISE_SKILL, techniques.get("repriseSkill").getAsString(), "bundled default enables reprise");
        equal(12, techniques.get("repriseWindowTicks").getAsInt(), "bundled default reprise window");
    }

    private static JsonObject bundledRapierTechniques() throws Exception {
        try (var input = RapierTechniquesMigrationTest.class.getResourceAsStream("/defaults/styles.json")) {
            check(input != null, "bundled default styles exist");
            var styles = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            return find(styles, "rapier").getAsJsonObject("techniques");
        }
    }

    private static Path fixture() throws Exception {
        Path dir = Files.createTempDirectory("fsrpg-rapier-techniques-old-");
        new RpgConfig(dir).reload();
        Files.deleteIfExists(dir.resolve(RapierTechniquesMigration.MARKER));
        Path backup = dir.resolve(RapierTechniquesMigration.BACKUP);
        if (Files.exists(backup)) {
            try (var files = Files.walk(backup)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        return dir;
    }

    private static void writeRapierTechniques(Path dir, String riposte, int riposteWindow, String reprise, int repriseWindow) throws Exception {
        JsonArray styles = read(dir, "styles.json").getAsJsonArray();
        JsonObject techniques = new JsonObject();
        techniques.addProperty("riposteSkill", riposte);
        techniques.addProperty("riposteWindowTicks", riposteWindow);
        techniques.addProperty("repriseSkill", reprise);
        techniques.addProperty("repriseWindowTicks", repriseWindow);
        find(styles, "rapier").add("techniques", techniques);
        Files.writeString(dir.resolve("styles.json"), " \n" + RpgConfig.JSON.toJson(styles) + "\n");
    }

    private static void removeSkill(Path dir, String skillId) throws Exception {
        JsonArray skills = read(dir, "skills.json").getAsJsonArray();
        for (int i = skills.size() - 1; i >= 0; i--) if (skills.get(i).getAsJsonObject().get("id").getAsString().equals(skillId)) skills.remove(i);
        Files.writeString(dir.resolve("skills.json"), " \n" + RpgConfig.JSON.toJson(skills) + "\n");
        JsonObject trees = read(dir, "skill_trees.json").getAsJsonObject();
        for (JsonElement node : trees.getAsJsonArray("nodes")) {
            JsonObject value = node.getAsJsonObject();
            if (!value.has("skills")) continue;
            JsonArray owned = value.getAsJsonArray("skills");
            for (int i = owned.size() - 1; i >= 0; i--) if (owned.get(i).getAsString().equals(skillId)) owned.remove(i);
        }
        Files.writeString(dir.resolve("skill_trees.json"), " \n" + RpgConfig.JSON.toJson(trees) + "\n");
    }

    private static JsonObject rapierTechniques(JsonElement styles) {
        return find(styles.getAsJsonArray(), "rapier").getAsJsonObject("techniques");
    }
    private static JsonObject find(JsonArray values, String id) {
        for (JsonElement value : values) if (value.getAsJsonObject().get("id").getAsString().equals(id)) return value.getAsJsonObject();
        throw new AssertionError("Missing style " + id);
    }
    private static JsonObject marker(Path dir) throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve(RapierTechniquesMigration.MARKER))).getAsJsonObject();
    }
    private static JsonElement read(Path dir, String name) throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
    }
    private static Map<String, byte[]> bytes(Path dir) throws Exception {
        var result = new LinkedHashMap<String, byte[]>();
        for (String name : FILES) result.put(name, Files.readAllBytes(dir.resolve(name)));
        return result;
    }
    private static void sameBytes(Map<String, byte[]> expected, Map<String, byte[]> actual, String message) {
        equal(expected.keySet(), actual.keySet(), message + " inventory");
        for (String name : expected.keySet()) check(Arrays.equals(expected.get(name), actual.get(name)), message + " " + name);
    }
    private static void cleanup(Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException("Cleanup outside fixture");
                Files.deleteIfExists(file);
            }
        }
    }
    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": " + expected + " != " + actual);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

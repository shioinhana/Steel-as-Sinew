package org.fsrpg.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fsrpg.combat.CombatRules;
import org.fsrpg.creature.GuardCueSettings;

/** Guard-only migration, exact original backups, disabled preservation and retry/validation boundaries. */
public final class GuardCueMigrationTest {
    private static int checks;
    private static final List<String> FILES = List.of("combat.json", "skills.json", "styles.json", "weapons.json", "quests.json",
            "collections.json", "skill_trees.json", "archery.json", "guard_cues.json");

    public static void main(String[] args) throws Exception {
        fresh(); existingAndRetry(); disabled(); invalidCandidate();
        System.out.println("GuardCueMigrationTest passed: " + checks + " checks");
    }

    private static void fresh() throws Exception {
        Path dir = Files.createTempDirectory("fsrpg-guard-cue-fresh-");
        try {
            var config = new RpgConfig(dir); var definitions = config.reload();
            equal(7, definitions.combat().perfectGuardTicks(), "fresh seven-tick project window after the 0.8.4 extension");
            equal(CombatRules.defaults(), definitions.combat(), "fallback and bundled combat agree");
            equal(GuardCueSettings.defaults(), GuardCueSettings.read(dir.resolve("guard_cues.json")), "fresh cue settings are exact defaults");
            check(Files.exists(dir.resolve(GuardCueMigration.MARKER)), "fresh upgrade marked complete");
            Map<String, byte[]> before = bytes(dir); config.reload(); sameBytes(before, bytes(dir), "second reload byte exact");
        } finally { cleanup(dir); }
    }

    private static void existingAndRetry() throws Exception {
        Path dir = fixture();
        try {
            JsonObject combat = read(dir, "combat.json").getAsJsonObject();
            combat.addProperty("maxSpirit", 173); combat.addProperty("guardStaminaCost", 7.25);
            combat.getAsJsonObject("perfectGuardCounter").addProperty("windowTicks", 7);
            JsonArray styles = read(dir, "styles.json").getAsJsonArray();
            styles.get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("damageMultiplier", .37);
            write(dir, "combat.json", combat); write(dir, "styles.json", styles);
            var before = bytes(dir); var expectedCombat = combat.deepCopy(); var expectedStyles = styles.deepCopy();
            calibrate(expectedCombat); for (var value : expectedStyles) for (String field : List.of("guard", "heavyGuard")) {
                JsonObject style = value.getAsJsonObject(); if (style.has(field) && style.get(field).isJsonObject()) calibrate(style.getAsJsonObject(field));
            }
            GuardCueMigration.migrate(dir);
            equal(expectedCombat, read(dir, "combat.json"), "global only enabled window changes");
            equal(expectedStyles, read(dir, "styles.json"), "style only enabled windows change");
            for (String name : List.of("combat.json", "styles.json"))
                check(Arrays.equals(before.get(name), Files.readAllBytes(dir.resolve(GuardCueMigration.BACKUP).resolve(name))), "byte exact original " + name);
            for (String name : FILES) if (!List.of("combat.json", "styles.json").contains(name))
                check(Arrays.equals(before.get(name), Files.readAllBytes(dir.resolve(name))), "unrelated bytes retained " + name);
            var once = bytes(dir); GuardCueMigration.migrate(dir); sameBytes(once, bytes(dir), "marker makes migration idempotent");
            Files.delete(dir.resolve(GuardCueMigration.MARKER));
            combat = read(dir, "combat.json").getAsJsonObject(); combat.addProperty("maxSpirit", 197); write(dir, "combat.json", combat);
            GuardCueMigration.migrate(dir);
            equal(197, read(dir, "combat.json").getAsJsonObject().get("maxSpirit").getAsInt(), "retry retains unrelated later edit");
            for (String name : List.of("combat.json", "styles.json"))
                check(Arrays.equals(before.get(name), Files.readAllBytes(dir.resolve(GuardCueMigration.BACKUP).resolve(name))), "retry retains true original " + name);
            combat.addProperty("perfectGuardTicks", 9); write(dir, "combat.json", combat); GuardCueMigration.migrate(dir);
            equal(combat, read(dir, "combat.json"), "completed marker preserves explicit later window tuning");
        } finally { cleanup(dir); }
    }

    private static void disabled() throws Exception {
        Path dir = fixture();
        try {
            JsonObject combat = read(dir, "combat.json").getAsJsonObject(); combat.addProperty("perfectGuardTicks", 0); write(dir, "combat.json", combat);
            JsonArray styles = read(dir, "styles.json").getAsJsonArray();
            for (var value : styles) for (String field : List.of("guard", "heavyGuard")) {
                JsonObject style = value.getAsJsonObject(); if (style.has(field) && style.get(field).isJsonObject()) style.getAsJsonObject(field).addProperty("perfectGuardTicks", 0);
            }
            write(dir, "styles.json", styles); var before = bytes(dir); GuardCueMigration.migrate(dir);
            equal(0, read(dir, "combat.json").getAsJsonObject().get("perfectGuardTicks").getAsInt(), "disabled global window preserved");
            sameBytes(before, bytes(dir), "all disabled configuration stays byte exact");
            check(!Files.exists(dir.resolve(GuardCueMigration.BACKUP)), "unchanged migration creates no fictitious originals");
        } finally { cleanup(dir); }
    }

    private static void invalidCandidate() throws Exception {
        Path dir = fixture();
        try {
            JsonObject combat = read(dir, "combat.json").getAsJsonObject(); combat.addProperty("maxSpirit", -1); write(dir, "combat.json", combat);
            var before = bytes(dir); boolean rejected = false;
            try { GuardCueMigration.migrate(dir); } catch (RuntimeException expected) { rejected = true; }
            check(rejected, "invalid complete candidate rejected"); sameBytes(before, bytes(dir), "failed validation modifies no configuration");
            check(!Files.exists(dir.resolve(GuardCueMigration.MARKER)), "invalid migration cannot complete marker");
            check(!Files.exists(dir.resolve(GuardCueMigration.BACKUP)), "invalid migration creates no backup");
            combat.addProperty("maxSpirit", 100); write(dir, "combat.json", combat); GuardCueMigration.migrate(dir);
            equal(6, read(dir, "combat.json").getAsJsonObject().get("perfectGuardTicks").getAsInt(), "repaired candidate retries normally");
        } finally { cleanup(dir); }
    }

    private static Path fixture() throws Exception {
        Path dir = Files.createTempDirectory("fsrpg-guard-cue-existing-"); new RpgConfig(dir).reload();
        Files.deleteIfExists(dir.resolve(GuardCueMigration.MARKER));
        Path backup = dir.resolve(GuardCueMigration.BACKUP).toAbsolutePath().normalize();
        if (Files.exists(backup)) {
            if (!backup.startsWith(dir.toAbsolutePath().normalize())) throw new IllegalStateException("Backup outside fixture");
            try (var files = Files.walk(backup)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        JsonObject combat = read(dir, "combat.json").getAsJsonObject(); combat.addProperty("perfectGuardTicks", 2); write(dir, "combat.json", combat);
        JsonArray styles = read(dir, "styles.json").getAsJsonArray();
        for (var value : styles) for (String field : List.of("guard", "heavyGuard")) {
            JsonObject style = value.getAsJsonObject(); if (style.has(field) && style.get(field).isJsonObject()) {
                var guard = style.getAsJsonObject(field); if (guard.get("perfectGuardTicks").getAsInt() > 0) guard.addProperty("perfectGuardTicks", 2);
            }
        }
        write(dir, "styles.json", styles); return dir;
    }
    private static void calibrate(JsonObject guard) { if (guard.get("perfectGuardTicks").getAsInt() > 0) guard.addProperty("perfectGuardTicks", 6); }
    private static JsonElement read(Path dir, String name) throws Exception { return JsonParser.parseString(Files.readString(dir.resolve(name))); }
    private static void write(Path dir, String name, JsonElement value) throws Exception { Files.writeString(dir.resolve(name), " \n" + RpgConfig.JSON.toJson(value) + "\n"); }
    private static Map<String, byte[]> bytes(Path dir) throws Exception { var result = new LinkedHashMap<String, byte[]>(); for (String name : FILES) result.put(name, Files.readAllBytes(dir.resolve(name))); return result; }
    private static void sameBytes(Map<String, byte[]> expected, Map<String, byte[]> actual, String message) { equal(expected.keySet(), actual.keySet(), message + " inventory"); for (String name : expected.keySet()) check(Arrays.equals(expected.get(name), actual.get(name)), message + " " + name); }
    private static void cleanup(Path directory) throws Exception { Path root = directory.toAbsolutePath().normalize(); try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) { if (!file.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException("Cleanup outside fixture"); Files.deleteIfExists(file); } } }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message + ": " + expected + " != " + actual); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

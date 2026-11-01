package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * 0.9.0 migration: an existing skill-tree file gains the old ActionCombat footwork branch
 * ({@code rules.footwork} plus the eight stable footwork nodes). Every custom field, node and
 * unrelated file is preserved; the pre-upgrade bytes are backed up once and the migration is
 * byte-idempotent.
 */
public final class FootworkSkillMigration {
    public static final String MARKER = "footwork-skill-migration-v1.json";
    public static final String BACKUP = "footwork-skill-backup-v1";
    /** Stable IDs only. Node contents come from the bundled defaults so tuning has one source. */
    public static final Set<String> NODE_IDS = Set.of(
            "rabbit", "flying_swan",
            "cloud_step", "cloud_step_ii", "cloud_step_iii",
            "light_body", "light_body_ii", "light_body_iii");
    private final Path directory;
    private final byte[] original;

    private FootworkSkillMigration(Path directory, byte[] original) {
        this.directory = directory;
        this.original = original;
    }

    public static void migrate(Path directory) throws IOException {
        FootworkSkillMigration migration = capture(directory);
        if (migration != null) migration.apply();
    }

    public static FootworkSkillMigration capture(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return null;
        Path backup = directory.resolve(BACKUP).resolve("skill_trees.json");
        Path source = directory.resolve("skill_trees.json");
        byte[] original = Files.exists(backup) ? bytes(backup) : Files.exists(source) ? bytes(source) : null;
        if (original != null) JsonParser.parseString(new String(original, StandardCharsets.UTF_8)).getAsJsonObject();
        return new FootworkSkillMigration(directory, original);
    }

    public void apply() throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return;
        JsonObject before = readTree().getAsJsonObject();
        JsonObject candidate = before.deepCopy();
        JsonObject defaults = bundledDefaults();
        boolean rulesAdded = addFootworkRules(candidate, defaults);
        List<String> added = addFootworkNodes(candidate, defaults);
        // Validate the complete candidate (including every existing custom node/style reference)
        // before creating the backup or writing any file.
        RpgConfig.parse(read("combat.json"), read("skills.json"), read("quests.json"), read("weapons.json"),
                read("styles.json"), read("collections.json"), candidate, read("archery.json"));
        boolean changed = rulesAdded || !added.isEmpty();
        if (changed && original != null) {
            Path backup = directory.resolve(BACKUP);
            Files.createDirectories(backup);
            if (!Files.exists(backup.resolve("skill_trees.json")))
                Files.write(backup.resolve("skill_trees.json"), original, StandardOpenOption.CREATE_NEW);
        }
        if (changed) RpgConfig.atomicWrite(directory.resolve("skill_trees.json"), RpgConfig.JSON.toJson(candidate));
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1);
        marker.addProperty("releaseVersion", "0.9.0");
        marker.addProperty("footworkRulesAdded", rulesAdded);
        JsonArray nodes = new JsonArray();
        for (String id : added) nodes.add(id);
        marker.add("footworkNodesAdded", nodes);
        marker.addProperty("existingFootworkFieldsPreserved", true);
        marker.addProperty("customNodesPreserved", true);
        marker.addProperty("existingSkillTreeFile", original != null);
        if (original != null) try {
            marker.addProperty("originalSkillTreesSha256",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    /** Missing individual fields are filled from defaults; explicit custom values always win. */
    private static boolean addFootworkRules(JsonObject candidate, JsonObject defaults) {
        JsonObject defaultRules = defaults.getAsJsonObject("rules").getAsJsonObject("footwork");
        JsonObject rules = candidate.getAsJsonObject("rules");
        if (!rules.has("footwork")) {
            rules.add("footwork", defaultRules.deepCopy());
            return true;
        }
        JsonObject footwork = rules.getAsJsonObject("footwork");
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : defaultRules.entrySet())
            if (!footwork.has(entry.getKey())) {
                footwork.add(entry.getKey(), entry.getValue().deepCopy());
                changed = true;
            }
        return changed;
    }

    private static List<String> addFootworkNodes(JsonObject candidate, JsonObject defaults) {
        JsonArray nodes = candidate.getAsJsonArray("nodes");
        Set<String> existing = new HashSet<>();
        for (JsonElement node : nodes) existing.add(node.getAsJsonObject().get("id").getAsString());
        List<String> added = new ArrayList<>();
        for (JsonElement node : defaults.getAsJsonArray("nodes")) {
            JsonObject object = node.getAsJsonObject();
            String id = object.get("id").getAsString();
            if (!NODE_IDS.contains(id) || existing.contains(id)) continue;
            nodes.add(object.deepCopy());
            added.add(id);
        }
        return added;
    }

    private JsonElement readTree() throws IOException {
        return read("skill_trees.json");
    }

    private JsonElement read(String name) throws IOException {
        return JsonParser.parseString(new String(bytes(directory.resolve(name)), StandardCharsets.UTF_8));
    }

    private static JsonObject bundledDefaults() throws IOException {
        try (InputStream in = RpgConfig.class.getResourceAsStream("/defaults/skill_trees.json")) {
            if (in == null) throw new IOException("Missing bundled default skill_trees.json");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static byte[] bytes(Path path) throws IOException {
        if (Files.size(path) > 512 * 1024) throw new IOException(path.getFileName() + " exceeds 512 KiB");
        return Files.readAllBytes(path);
    }
}

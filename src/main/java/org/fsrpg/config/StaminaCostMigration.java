package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Scale existing action costs once; snapshots make interrupted upgrades safe to retry. */
final class StaminaCostMigration {
    static final String MARKER = "stamina-cost-migration-v1.json";
    static final String BACKUP = "stamina-cost-backup-v1";
    private static final List<String> COST_FILES = List.of("combat.json", "skills.json", "styles.json", "archery.json");
    private static final BigDecimal SCALE = new BigDecimal("0.2");
    private final Path directory;
    private final Map<String, JsonElement> originals;

    private StaminaCostMigration(Path directory, Map<String, JsonElement> originals) {
        this.directory = directory;
        this.originals = originals;
    }

    /** Must run before seeding or legacy migration, so new defaults are never reduced twice. */
    static StaminaCostMigration capture(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return null;
        Path backup = directory.resolve(BACKUP), manifest = backup.resolve("source-files.json");
        Files.createDirectories(backup);
        JsonArray names;
        if (Files.exists(manifest)) {
            names = read(manifest).getAsJsonArray();
        } else {
            names = new JsonArray();
            for (String name : COST_FILES) {
                Path source = directory.resolve(name), snapshot = backup.resolve(name);
                if (Files.exists(snapshot) || Files.exists(source)) {
                    if (!Files.exists(snapshot)) {
                        // Bad costs must be repairable in the live file, without a bad snapshot locking the upgrade.
                        validateOriginalCosts(name, read(source));
                        Files.copy(source, snapshot);
                    }
                    names.add(name);
                }
            }
            RpgConfig.atomicWrite(manifest, RpgConfig.JSON.toJson(names));
        }
        Map<String, JsonElement> originals = new LinkedHashMap<>();
        for (JsonElement value : names) {
            String name = value.getAsString();
            if (!COST_FILES.contains(name) || originals.containsKey(name)) throw new IOException("Invalid stamina migration snapshot " + name);
            originals.put(name, read(backup.resolve(name)));
        }
        return new StaminaCostMigration(directory, originals);
    }

    void apply() throws IOException {
        Map<String, JsonElement> candidate = new LinkedHashMap<>();
        for (String name : COST_FILES) candidate.put(name, read(directory.resolve(name)));
        for (var original : originals.entrySet()) {
            JsonElement next = candidate.get(original.getKey());
            switch (original.getKey()) {
                case "combat.json" -> scaleFields(next.getAsJsonObject(), original.getValue().getAsJsonObject(),
                        "guardStaminaCost", "perfectGuardStaminaCost", "dodgeStaminaCost");
                case "archery.json" -> scaleFields(next.getAsJsonObject(), original.getValue().getAsJsonObject(),
                        "rapidStaminaCost", "snipeStaminaCost", "bashStaminaCost");
                case "skills.json", "styles.json" -> scaleCatalog(next.getAsJsonArray(), original.getValue().getAsJsonArray(), original.getKey().equals("styles.json"));
                default -> throw new IllegalStateException("Unexpected cost file");
            }
        }
        // The same definitions used by the server must accept the entire upgrade before any cost writes.
        RpgConfig.parse(candidate.get("combat.json"), candidate.get("skills.json"), read(directory.resolve("quests.json")),
                read(directory.resolve("weapons.json")), candidate.get("styles.json"), read(directory.resolve("collections.json")),
                read(directory.resolve("skill_trees.json")), candidate.get("archery.json"));
        JsonArray changed = new JsonArray();
        for (var entry : candidate.entrySet()) {
            Path target = directory.resolve(entry.getKey());
            if (!entry.getValue().equals(read(target))) {
                RpgConfig.atomicWrite(target, RpgConfig.JSON.toJson(entry.getValue()));
                changed.add(entry.getKey());
            }
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1);
        marker.addProperty("releaseVersion", "0.5.3");
        marker.addProperty("staminaCostMultiplier", SCALE);
        marker.addProperty("customFieldsPreserved", true);
        marker.add("changedFiles", changed);
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    private static void scaleCatalog(JsonArray candidate, JsonArray original, boolean styles) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement value : original) {
            JsonObject entry = value.getAsJsonObject();
            String id = entry.get("id").getAsString();
            if (byId.putIfAbsent(id, entry) != null) throw new IllegalArgumentException("Duplicate stamina migration ID " + id);
        }
        for (JsonElement value : candidate) {
            JsonObject entry = value.getAsJsonObject(), before = byId.get(entry.get("id").getAsString());
            if (before == null) continue;
            if (!styles) scaleFields(entry, before, "staminaCost");
            else for (String guard : List.of("guard", "heavyGuard")) {
                if (before.has(guard) && !before.get(guard).isJsonNull() && entry.has(guard) && !entry.get(guard).isJsonNull())
                    scaleFields(entry.getAsJsonObject(guard), before.getAsJsonObject(guard), "staminaCost", "perfectGuardStaminaCost");
            }
        }
    }

    private static void validateOriginalCosts(String name, JsonElement original) {
        JsonElement probe = original.deepCopy();
        switch (name) {
            case "combat.json" -> scaleFields(probe.getAsJsonObject(), original.getAsJsonObject(),
                    "guardStaminaCost", "perfectGuardStaminaCost", "dodgeStaminaCost");
            case "archery.json" -> scaleFields(probe.getAsJsonObject(), original.getAsJsonObject(),
                    "rapidStaminaCost", "snipeStaminaCost", "bashStaminaCost");
            case "skills.json", "styles.json" -> scaleCatalog(probe.getAsJsonArray(), original.getAsJsonArray(), name.equals("styles.json"));
            default -> throw new IllegalArgumentException("Unknown stamina cost file " + name);
        }
    }

    private static void scaleFields(JsonObject candidate, JsonObject original, String... fields) {
        for (String field : fields) if (original.has(field)) {
            JsonElement value = original.get(field);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field + " must be numeric");
            double amount = value.getAsDouble();
            if (!Double.isFinite(amount) || amount < 0 || amount > 1_000_000) throw new IllegalArgumentException(field + " outside 0..1000000");
            candidate.addProperty(field, value.getAsBigDecimal().multiply(SCALE));
        }
    }

    private static JsonElement read(Path path) throws IOException {
        if (Files.size(path) > 512 * 1024) throw new IOException(path.getFileName() + " exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
    }
}

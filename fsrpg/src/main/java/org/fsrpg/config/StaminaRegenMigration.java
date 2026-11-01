package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Increase existing regeneration once, using the original snapshot when retrying an interrupted upgrade. */
final class StaminaRegenMigration {
    static final String MARKER = "stamina-regen-migration-v1.json";
    static final String BACKUP = "stamina-regen-backup-v1";
    private static final String FIELD = "staminaRegenPerSecond";
    private static final BigDecimal SCALE = new BigDecimal("3");
    private final Path directory;
    private final BigDecimal originalRate;

    private StaminaRegenMigration(Path directory, BigDecimal originalRate) {
        this.directory = directory;
        this.originalRate = originalRate;
    }

    /** Capture before defaults are seeded, so a fresh rate of 3 is never mistaken for an old rate. */
    static StaminaRegenMigration capture(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return null;
        Path backup = directory.resolve(BACKUP), manifest = backup.resolve("source-files.json");
        Files.createDirectories(backup);
        JsonArray names;
        if (Files.exists(manifest)) names = read(manifest).getAsJsonArray();
        else {
            names = new JsonArray();
            Path source = directory.resolve("combat.json"), snapshot = backup.resolve("combat.json");
            if (Files.exists(snapshot) || Files.exists(source)) {
                if (!Files.exists(snapshot)) {
                    scaledRate(read(source).getAsJsonObject());
                    Files.copy(source, snapshot);
                }
                names.add("combat.json");
            }
            RpgConfig.atomicWrite(manifest, RpgConfig.JSON.toJson(names));
        }
        if (names.size() > 1 || (names.size() == 1 && !"combat.json".equals(names.get(0).getAsString())))
            throw new IOException("Invalid stamina regeneration migration snapshot");
        BigDecimal original = names.isEmpty() ? null : rate(read(backup.resolve("combat.json")).getAsJsonObject());
        if (original != null) validateRate(original.multiply(SCALE));
        return new StaminaRegenMigration(directory, original);
    }

    void apply() throws IOException {
        Path combat = directory.resolve("combat.json");
        JsonObject before = read(combat).getAsJsonObject(), candidate = before.deepCopy();
        if (originalRate != null) candidate.addProperty(FIELD, originalRate.multiply(SCALE));
        // Validate the same complete definitions the server loads before changing any user value.
        RpgConfig.parse(candidate, read("skills.json"), read("quests.json"), read("weapons.json"),
                read("styles.json"), read("collections.json"), read("skill_trees.json"), read("archery.json"));
        if (!before.equals(candidate)) RpgConfig.atomicWrite(combat, RpgConfig.JSON.toJson(candidate));
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1);
        marker.addProperty("releaseVersion", "0.7.2");
        marker.addProperty("staminaRegenMultiplier", SCALE);
        marker.addProperty("existingCombatFile", originalRate != null);
        if (originalRate != null) marker.addProperty("originalStaminaRegenPerSecond", originalRate);
        marker.addProperty("staminaRegenPerSecond", candidate.get(FIELD).getAsBigDecimal());
        marker.addProperty("customFieldsPreserved", true);
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    private static BigDecimal scaledRate(JsonObject value) {
        BigDecimal result = rate(value).multiply(SCALE);
        validateRate(result);
        return result;
    }
    private static BigDecimal rate(JsonObject value) {
        JsonElement field = value.get(FIELD);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(FIELD + " must be numeric");
        BigDecimal result = field.getAsBigDecimal();
        validateRate(result);
        return result;
    }
    private static void validateRate(BigDecimal value) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(1_000_000)) > 0)
            throw new IllegalArgumentException(FIELD + " outside 0..1000000 after regeneration upgrade");
    }
    private JsonElement read(String name) throws IOException { return read(directory.resolve(name)); }
    private static JsonElement read(Path path) throws IOException {
        if (Files.size(path) > 512 * 1024) throw new IOException(path.getFileName() + " exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
    }
}

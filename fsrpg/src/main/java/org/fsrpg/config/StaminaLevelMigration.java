package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.fsrpg.combat.StaminaLevelScaling;

/** Install level-based stamina once, preserving the exact combat file present before historical upgrades. */
public final class StaminaLevelMigration {
    public static final String MARKER = "stamina-level-migration-v1.json";
    public static final String BACKUP = "stamina-level-backup-v1";
    private final Path directory;
    private final byte[] original;

    private StaminaLevelMigration(Path directory, byte[] original) { this.directory = directory; this.original = original; }

    /** Capture before any older migration runs; do not freeze invalid input into a backup before validation. */
    public static StaminaLevelMigration capture(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return null;
        Path snapshot = directory.resolve(BACKUP).resolve("combat.json"), source = directory.resolve("combat.json");
        byte[] bytes = Files.exists(snapshot) ? bytes(snapshot) : Files.exists(source) ? bytes(source) : null;
        if (bytes != null) JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        return new StaminaLevelMigration(directory, bytes);
    }

    public void apply() throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return;
        Path combat = directory.resolve("combat.json");
        JsonObject before = read("combat.json").getAsJsonObject(), candidate = before.deepCopy();
        candidate.addProperty("maxStamina", 50);
        candidate.addProperty("staminaRegenPerSecond", 5);
        JsonObject defaults = RpgConfig.JSON.toJsonTree(StaminaLevelScaling.defaults()).getAsJsonObject();
        if (!candidate.has("staminaLevelScaling")) candidate.add("staminaLevelScaling", defaults);
        else if (candidate.get("staminaLevelScaling").isJsonObject()) {
            JsonObject scaling = candidate.getAsJsonObject("staminaLevelScaling");
            for (var field : defaults.entrySet()) if (!scaling.has(field.getKey())) scaling.add(field.getKey(), field.getValue());
        }
        RpgConfig.parse(candidate, read("skills.json"), read("quests.json"), read("weapons.json"),
                read("styles.json"), read("collections.json"), read("skill_trees.json"), read("archery.json"));
        if (original != null) {
            Path backup = directory.resolve(BACKUP); Files.createDirectories(backup);
            Path snapshot = backup.resolve("combat.json");
            if (!Files.exists(snapshot)) Files.write(snapshot, original, StandardOpenOption.CREATE_NEW);
        }
        if (!candidate.equals(before)) RpgConfig.atomicWrite(combat, RpgConfig.JSON.toJson(candidate));
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1); marker.addProperty("releaseVersion", "0.7.4");
        marker.addProperty("baseMaxStamina", 50); marker.addProperty("baseStaminaRegenPerSecond", 5);
        marker.addProperty("levelSource", "vanilla experienceLevel"); marker.addProperty("regenerationFloorOnly", true);
        marker.addProperty("existingCombatFile", original != null); marker.addProperty("otherFieldsPreserved", true);
        if (original != null) marker.addProperty("originalCombatSha256", sha256(original));
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    private JsonElement read(String name) throws IOException { return JsonParser.parseString(new String(bytes(directory.resolve(name)), StandardCharsets.UTF_8)); }
    private static byte[] bytes(Path file) throws IOException {
        if (Files.size(file) > 512 * 1024) throw new IOException(file.getFileName() + " exceeds 512 KiB");
        return Files.readAllBytes(file);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

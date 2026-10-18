package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.fsrpg.combat.StaminaCombatRules;

/** Add only absent stamina combat fields once, retaining the exact file that existed before all upgrades. */
public final class StaminaCombatMigration {
    public static final String MARKER = "stamina-combat-migration-v1.json";
    public static final String BACKUP = "stamina-combat-backup-v1";
    private final Path directory;
    private final byte[] original;
    private StaminaCombatMigration(Path directory, byte[] original) { this.directory = directory; this.original = original; }

    public static StaminaCombatMigration capture(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return null;
        Path snapshot = directory.resolve(BACKUP).resolve("combat.json"), source = directory.resolve("combat.json");
        byte[] original = Files.exists(snapshot) ? bytes(snapshot) : Files.exists(source) ? bytes(source) : null;
        if (original != null) JsonParser.parseString(new String(original, StandardCharsets.UTF_8)).getAsJsonObject();
        return new StaminaCombatMigration(directory, original);
    }

    public void apply() throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return;
        JsonObject before = read("combat.json").getAsJsonObject(), candidate = before.deepCopy();
        JsonObject defaults = RpgConfig.JSON.toJsonTree(StaminaCombatRules.defaults()).getAsJsonObject();
        if (!candidate.has("staminaCombatRules")) candidate.add("staminaCombatRules", defaults);
        else if (candidate.get("staminaCombatRules").isJsonObject()) {
            JsonObject rules = candidate.getAsJsonObject("staminaCombatRules");
            for (var field : defaults.entrySet()) if (!rules.has(field.getKey())) rules.add(field.getKey(), field.getValue());
        }
        RpgConfig.parse(candidate, read("skills.json"), read("quests.json"), read("weapons.json"),
                read("styles.json"), read("collections.json"));
        boolean changed = !before.equals(candidate);
        if (changed && original != null) {
            Path backup = directory.resolve(BACKUP); Files.createDirectories(backup);
            Path snapshot = backup.resolve("combat.json");
            if (!Files.exists(snapshot)) Files.write(snapshot, original, StandardOpenOption.CREATE_NEW);
        }
        if (changed) RpgConfig.atomicWrite(directory.resolve("combat.json"), RpgConfig.JSON.toJson(candidate));
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1); marker.addProperty("releaseVersion", "0.7.7");
        marker.addProperty("onlyMissingFieldsAdded", true); marker.addProperty("allOtherFieldsPreserved", true);
        marker.addProperty("existingCombatFile", original != null);
        if (original != null) marker.addProperty("originalCombatSha256", sha256(original));
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    private JsonElement read(String name) throws IOException {
        return JsonParser.parseString(new String(bytes(directory.resolve(name)), StandardCharsets.UTF_8));
    }
    private static byte[] bytes(Path file) throws IOException {
        if (Files.size(file) > 512 * 1024) throw new IOException(file.getFileName() + " exceeds 512 KiB");
        return Files.readAllBytes(file);
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

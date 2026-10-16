package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Apply the requested 200 ms point thrust once, preserving every other skill field. */
final class SpearThrustTimingMigration {
    static final String MARKER = "spear-thrust-timing-migration-v1.json";
    static final String BACKUP = "spear-thrust-timing-backup-v1";
    static final String SKILL = "spear_thrust";

    private SpearThrustTimingMigration() {}

    static void migrate(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return;
        Path skills = directory.resolve("skills.json");
        JsonArray before = read(skills).getAsJsonArray(), candidate = before.deepCopy();
        JsonObject thrust = null;
        for (JsonElement value : candidate) {
            JsonObject skill = value.getAsJsonObject();
            if (SKILL.equals(skill.get("id").getAsString())) {
                if (thrust != null) throw new IllegalArgumentException("Duplicate skill id: " + SKILL);
                thrust = skill;
            }
        }
        if (thrust != null) {
            thrust.addProperty("windupTicks", 1);
            thrust.addProperty("activeTicks", 1);
            thrust.addProperty("recoveryTicks", 2);
        }
        // A custom catalog may intentionally omit this stable skill. Never insert an unwanted skill.
        RpgConfig.parse(read(directory.resolve("combat.json")), candidate,
                read(directory.resolve("quests.json")), read(directory.resolve("weapons.json")),
                read(directory.resolve("styles.json")), read(directory.resolve("collections.json")),
                read(directory.resolve("skill_trees.json")), read(directory.resolve("archery.json")));
        boolean changed = !before.equals(candidate);
        if (changed) {
            Path backup = directory.resolve(BACKUP);
            Files.createDirectories(backup);
            Path snapshot = backup.resolve("skills.json");
            if (!Files.exists(snapshot)) Files.copy(skills, snapshot);
            RpgConfig.atomicWrite(skills, RpgConfig.JSON.toJson(candidate));
        }
        // Fixed values make a retry after the skills write safe without scaling or rewriting backups.
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1);
        marker.addProperty("releaseVersion", "0.7.3");
        marker.addProperty("skillId", SKILL);
        marker.addProperty("skillPresent", thrust != null);
        marker.addProperty("windupTicks", 1);
        marker.addProperty("activeTicks", 1);
        marker.addProperty("recoveryTicks", 2);
        marker.addProperty("durationSeconds", .2);
        marker.addProperty("otherFieldsPreserved", true);
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    private static JsonElement read(Path file) throws IOException {
        if (Files.size(file) > 512 * 1024) throw new IOException(file.getFileName() + " exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }
}

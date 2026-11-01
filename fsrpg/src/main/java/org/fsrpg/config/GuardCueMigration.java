package org.fsrpg.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** One-time project calibration of enabled perfect-guard windows; all other tuning remains byte/field preserved. */
public final class GuardCueMigration {
    public static final String MARKER = "guard-cue-migration-v1.json";
    public static final String BACKUP = "guard-cue-backup-v1";
    public static final int WINDOW_TICKS = 6;
    private GuardCueMigration() {}

    public static void migrate(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return;
        JsonObject combat = read(directory.resolve("combat.json")).getAsJsonObject();
        JsonArray styles = read(directory.resolve("styles.json")).getAsJsonArray();
        JsonObject nextCombat = combat.deepCopy(); JsonArray nextStyles = styles.deepCopy();
        calibrate(nextCombat);
        for (var value : nextStyles) {
            JsonObject style = value.getAsJsonObject();
            for (String field : List.of("guard", "heavyGuard"))
                if (style.has(field) && style.get(field).isJsonObject()) calibrate(style.getAsJsonObject(field));
        }
        // Validate the complete immutable candidate before creating backups or writing any configuration.
        RpgConfig.parse(nextCombat, read(directory.resolve("skills.json")), read(directory.resolve("quests.json")),
                read(directory.resolve("weapons.json")), nextStyles, read(directory.resolve("collections.json")),
                read(directory.resolve("skill_trees.json")), read(directory.resolve("archery.json")));
        if (!combat.equals(nextCombat) || !styles.equals(nextStyles)) {
            Path backup = directory.resolve(BACKUP); Files.createDirectories(backup);
            // Capture the pair before either write. Interrupted retries retain the true original bytes.
            for (String name : List.of("combat.json", "styles.json"))
                if (!Files.exists(backup.resolve(name))) Files.copy(directory.resolve(name), backup.resolve(name));
            if (!combat.equals(nextCombat)) RpgConfig.atomicWrite(directory.resolve("combat.json"), RpgConfig.JSON.toJson(nextCombat));
            if (!styles.equals(nextStyles)) RpgConfig.atomicWrite(directory.resolve("styles.json"), RpgConfig.JSON.toJson(nextStyles));
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1); marker.addProperty("releaseVersion", "0.7.5");
        marker.addProperty("perfectGuardTicks", WINDOW_TICKS);
        marker.addProperty("timingSource", "project calibration; no unpublished game frame values claimed");
        marker.addProperty("disabledGuardWindowsPreserved", true); marker.addProperty("allOtherFieldsPreserved", true);
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }

    private static void calibrate(JsonObject guard) {
        if (guard.get("perfectGuardTicks").getAsInt() > 0) guard.addProperty("perfectGuardTicks", WINDOW_TICKS);
    }
    private static JsonElement read(Path file) throws IOException {
        if (Files.size(file) > 512 * 1024) throw new IOException(file.getFileName() + " exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }
}

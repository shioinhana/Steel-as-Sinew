package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.CombatRules;

/** One-time upgrade of guard timing and the independent left-click counter opportunity. */
public final class PerfectGuardMigration {
    public static final String MARKER = "perfect-guard-migration-v1.json";
    public static final String BACKUP = "perfect-guard-backup-v1";
    private PerfectGuardMigration() {}

    public static void migrate(Path directory) throws IOException {
        if (Files.exists(directory.resolve(MARKER))) return;
        JsonObject combat = read(directory.resolve("combat.json")).getAsJsonObject();
        JsonArray styles = read(directory.resolve("styles.json")).getAsJsonArray();
        JsonObject nextCombat = combat.deepCopy(); JsonArray nextStyles = styles.deepCopy();
        nextCombat.addProperty("perfectGuardTicks", 2);
        nextCombat.addProperty("parryStunTicks", 4);
        JsonObject defaults = RpgConfig.JSON.toJsonTree(CombatRules.PerfectGuardCounter.defaults()).getAsJsonObject();
        if (!nextCombat.has("perfectGuardCounter")) nextCombat.add("perfectGuardCounter", defaults);
        else if (nextCombat.get("perfectGuardCounter").isJsonObject())
            for (var field : defaults.entrySet()) if (!nextCombat.getAsJsonObject("perfectGuardCounter").has(field.getKey()))
                nextCombat.getAsJsonObject("perfectGuardCounter").add(field.getKey(), field.getValue());
        for (var value : nextStyles) {
            JsonObject style = value.getAsJsonObject();
            for (String field : List.of("guard", "heavyGuard")) {
                if (!style.has(field) || !style.get(field).isJsonObject()) continue;
                JsonObject guard = style.getAsJsonObject(field);
                // Existing zero windows (heavy guard / bows) remain disabled.
                if (guard.get("perfectGuardTicks").getAsInt() > 0) guard.addProperty("perfectGuardTicks", 2);
                guard.addProperty("parryStunTicks", 4);
            }
        }
        RpgConfig.parse(nextCombat, read(directory.resolve("skills.json")), read(directory.resolve("quests.json")),
                read(directory.resolve("weapons.json")), nextStyles, read(directory.resolve("collections.json")),
                read(directory.resolve("skill_trees.json")), read(directory.resolve("archery.json")));
        for (String name : List.of("combat.json", "styles.json")) {
            JsonElement before = name.equals("combat.json") ? combat : styles;
            JsonElement candidate = name.equals("combat.json") ? nextCombat : nextStyles;
            if (before.equals(candidate)) continue;
            Path backup = directory.resolve(BACKUP); Files.createDirectories(backup);
            Path target = directory.resolve(name), snapshot = backup.resolve(name);
            if (!Files.exists(snapshot)) Files.copy(target, snapshot);
            RpgConfig.atomicWrite(target, RpgConfig.JSON.toJson(candidate));
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1); marker.addProperty("releaseVersion", "0.7.3");
        marker.addProperty("perfectGuardTicks", 2); marker.addProperty("parryStunTicks", 4);
        marker.addProperty("counterWindowTicks", 4); marker.addProperty("counterDamageMultiplier", 1.5);
        marker.addProperty("disabledGuardWindowsPreserved", true); marker.addProperty("otherFieldsPreserved", true);
        RpgConfig.atomicWrite(directory.resolve(MARKER), RpgConfig.JSON.toJson(marker));
    }
    private static JsonElement read(Path file) throws IOException {
        if (Files.size(file) > 512 * 1024) throw new IOException(file.getFileName() + " exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }
}

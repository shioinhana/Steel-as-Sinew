package org.fsrpg.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fsrpg.combat.RapierTargeting;

/** Adds only absent targeting fields, preserving all established combat tuning. */
final class RapierTargetLockMigration {
    private RapierTargetLockMigration() {}
    static void migrate(Path directory) throws IOException {
        Path combat = directory.resolve("combat.json");
        JsonObject old = JsonParser.parseString(Files.readString(combat)).getAsJsonObject();
        JsonObject next = old.deepCopy();
        JsonObject defaults = RpgConfig.JSON.toJsonTree(RapierTargeting.Rules.defaults()).getAsJsonObject();
        if (!next.has("rapierTargetLock")) next.add("rapierTargetLock", defaults);
        else if (next.get("rapierTargetLock").isJsonObject()) {
            JsonObject existing = next.getAsJsonObject("rapierTargetLock");
            for (var entry : defaults.entrySet()) if (!existing.has(entry.getKey())) existing.add(entry.getKey(), entry.getValue());
        }
        if (old.equals(next)) return;
        // Refuse a malformed merge before creating a backup or rewriting user text.
        RpgConfig.parse(next, read(directory,"skills.json"), read(directory,"quests.json"),
                read(directory,"weapons.json"), read(directory,"styles.json"), read(directory,"collections.json"));
        Path backup = directory.resolve("rapier-target-lock-backup-v1");
        Files.createDirectories(backup);
        if (!Files.exists(backup.resolve("combat.json"))) Files.copy(combat, backup.resolve("combat.json"));
        RpgConfig.atomicWrite(combat, RpgConfig.JSON.toJson(next));
    }
    private static JsonElement read(Path directory, String name) throws IOException {
        return JsonParser.parseString(Files.readString(directory.resolve(name)));
    }
}

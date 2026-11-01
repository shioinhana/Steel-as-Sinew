package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** Append absent guild seeds once; never replace a user's task or change a stable ID. */
public final class QuestSystemMigration {
    private QuestSystemMigration() {}
    public static void migrate(Path directory) throws IOException {
        Path marker = directory.resolve("quest-system-migration-v1.json");
        if (Files.exists(marker)) return;
        Path quests = directory.resolve("quests.json");
        JsonArray current = JsonParser.parseString(Files.readString(quests)).getAsJsonArray();
        JsonArray additions;
        try (var in = QuestSystemMigration.class.getResourceAsStream("/defaults/guild_quests.json")) {
            if (in == null) throw new IOException("Missing guild task seeds");
            additions = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        }
        Set<String> ids = new HashSet<>();
        current.forEach(q -> ids.add(q.getAsJsonObject().get("id").getAsString()));
        JsonArray added = new JsonArray();
        for (JsonElement entry : additions) {
            String id = entry.getAsJsonObject().get("id").getAsString();
            if (ids.add(id)) { current.add(entry); added.add(id); }
        }
        org.fsrpg.quest.QuestCatalog.of(java.util.Arrays.asList(RpgConfig.JSON.fromJson(current, org.fsrpg.quest.QuestDefinition[].class)));
        Path backup = directory.resolve("quest-system-backup-v1");
        Files.createDirectories(backup);
        if (!Files.exists(backup.resolve("quests.json"))) Files.copy(quests, backup.resolve("quests.json"), StandardCopyOption.COPY_ATTRIBUTES);
        if (!added.isEmpty()) RpgConfig.atomicWrite(quests, RpgConfig.JSON.toJson(current));
        JsonObject report = new JsonObject(); report.addProperty("version", "0.13.0");
        report.add("addedQuestIds", added); report.addProperty("existingDefinitionsPreserved", true);
        RpgConfig.atomicWrite(marker, RpgConfig.JSON.toJson(report));
    }
}

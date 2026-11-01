package org.fsrpg.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.*;
import java.util.Set;
import org.fsrpg.quest.QuestSettings;

public final class QuestSettingsConfig {
    private QuestSettingsConfig() {}
    public static QuestSettings load(Path directory) throws IOException {
        Path file = directory.resolve("guild.json");
        if (!Files.exists(file)) {
            Files.createDirectories(directory);
            try (var in = QuestSettingsConfig.class.getResourceAsStream("/defaults/guild.json")) {
                if (in == null) throw new IOException("Missing guild.json seed");
                Files.copy(in, file);
            }
        }
        if (Files.size(file) > 65536) throw new IOException("guild.json exceeds 64 KiB");
        try {
            JsonObject source=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            validate(source);
            QuestSettings settings = RpgConfig.JSON.fromJson(source, QuestSettings.class);
            if (settings == null) throw new IllegalArgumentException("Empty guild settings");
            return settings;
        } catch (RuntimeException error) { throw new IOException("guild.json: " + error.getMessage(), error); }
    }
    private static void validate(JsonObject source) {
        fields(source,Set.of("maxTracked","dailyCount","timezone","promotions"));
        integer(source,"maxTracked");integer(source,"dailyCount");text(source,"timezone");
        if(source.has("promotions")) {
            if(!source.get("promotions").isJsonArray())throw new IllegalArgumentException("promotions must be an array");
            for(JsonElement entry:source.getAsJsonArray("promotions")) {
                if(!entry.isJsonObject())throw new IllegalArgumentException("promotion must be an object");
                JsonObject p=entry.getAsJsonObject();fields(p,Set.of("rank","totalCompletions","requiredQuests","examQuest"));
                text(p,"rank");text(p,"examQuest");integer(p,"totalCompletions");
                if(!p.has("rank"))throw new IllegalArgumentException("promotion rank required");
                org.fsrpg.quest.QuestDefinition.Rank.valueOf(p.get("rank").getAsString());
                if(p.has("requiredQuests")) {
                    if(!p.get("requiredQuests").isJsonObject())throw new IllegalArgumentException("requiredQuests must be an object");
                    JsonObject required=p.getAsJsonObject("requiredQuests");for(String id:required.keySet())integer(required,id);
                }
            }
        }
    }
    private static void fields(JsonObject source,Set<String> allowed) {
        for(String key:source.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown guild setting "+key);
    }
    private static void integer(JsonObject source,String name) {
        if(!source.has(name))return;
        JsonElement value=source.get(name);
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(name+" must be an integer");
        try {value.getAsBigDecimal().intValueExact();}
        catch(ArithmeticException|NumberFormatException error){throw new IllegalArgumentException(name+" must be a 32-bit integer",error);}
    }
    private static void text(JsonObject source,String name) {
        if(!source.has(name))return;
        JsonElement value=source.get(name);
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException(name+" must be text");
    }
}

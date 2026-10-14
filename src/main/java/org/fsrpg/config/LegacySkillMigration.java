package org.fsrpg.config;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Upgrade default fields only; preserve user tuning, unknown IDs, worlds and custom models. */
final class LegacySkillMigration {
    private LegacySkillMigration() {}
    static void migrate(Path directory) throws IOException {
        Path marker=directory.resolve("legacy-skill-migration-v1.json");
        if(Files.exists(marker))return;
        Map<String,JsonElement> merged=new LinkedHashMap<>();
        for(String name:List.of("skills.json","styles.json","weapons.json")) {
            JsonElement current=JsonParser.parseString(Files.readString(directory.resolve(name)));
            JsonElement previous=resource("/defaults/legacy-0.4/"+name),next=resource("/defaults/"+name);
            merged.put(name,name.equals("weapons.json")?fields(current,previous,next):catalog(current.getAsJsonArray(),previous.getAsJsonArray(),next.getAsJsonArray()));
        }
        // Validate all proposed catalogs together before changing any files.
        RpgConfig.parse(JsonParser.parseString(Files.readString(directory.resolve("combat.json"))),merged.get("skills.json"),
                JsonParser.parseString(Files.readString(directory.resolve("quests.json"))),merged.get("weapons.json"),merged.get("styles.json"),
                JsonParser.parseString(Files.readString(directory.resolve("collections.json"))));
        Path backup=directory.resolve("legacy-skill-backup-v1");Files.createDirectories(backup);
        for(var entry:merged.entrySet()) {
            Path target=directory.resolve(entry.getKey()),copy=backup.resolve(entry.getKey());
            if(!Files.exists(copy))Files.copy(target,copy);
            RpgConfig.atomicWrite(target,RpgConfig.JSON.toJson(entry.getValue()));
        }
        RpgConfig.atomicWrite(marker,"{\"schemaVersion\":1,\"sourceVersion\":\"actioncombat-0.1.1\",\"customFieldsPreserved\":true}");
    }
    private static JsonElement resource(String name)throws IOException {
        try(InputStream in=LegacySkillMigration.class.getResourceAsStream(name)) {
            if(in==null)throw new IOException("Missing migration baseline "+name);
            return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static JsonArray catalog(JsonArray current,JsonArray previous,JsonArray next) {
        Map<String,JsonObject> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
        for(var value:previous)before.put(value.getAsJsonObject().get("id").getAsString(),value.getAsJsonObject());
        for(var value:next)after.put(value.getAsJsonObject().get("id").getAsString(),value.getAsJsonObject());
        JsonArray result=new JsonArray();Set<String> seen=new HashSet<>();
        for(var value:current) {
            String id=value.getAsJsonObject().get("id").getAsString();seen.add(id);
            result.add(before.containsKey(id)&&after.containsKey(id)?fields(value,before.get(id),after.get(id)):value.deepCopy());
        }
        for(var entry:after.entrySet())if(!seen.contains(entry.getKey()))result.add(entry.getValue().deepCopy());
        return result;
    }
    private static JsonElement fields(JsonElement current,JsonElement before,JsonElement after) {
        if(Objects.equals(current,before))return after.deepCopy();
        if(current!=null&&before!=null&&after!=null&&current.isJsonObject()&&before.isJsonObject()&&after.isJsonObject()) {
            JsonObject result=current.deepCopy().getAsJsonObject(),old=before.getAsJsonObject(),next=after.getAsJsonObject();
            boolean customModel=result.has("model")&&(!Objects.equals(result.get("model"),old.get("model"))||!Objects.equals(result.get("action"),old.get("action")));
            for(var entry:next.entrySet()) {
                String key=entry.getKey();
                if(customModel&&(key.equals("model")||key.equals("action")))continue;
                if(!result.has(key))result.add(key,entry.getValue().deepCopy());
                else if(old.has(key))result.add(key,fields(result.get(key),old.get(key),entry.getValue()));
            }
            return result;
        }
        return current.deepCopy();
    }
}

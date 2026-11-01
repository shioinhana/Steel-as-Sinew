package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.fsrpg.combat.SwordCombatRules;

/** Durable, field-wise 0.12 migration; custom models and explicit opt-out keep their old behavior. */
public final class SwordCombatMigration {
    public static final String MARKER="sword-combat-migration-v1.json",BACKUP="sword-combat-backup-v1";
    private static final List<String> FILES=List.of("combat.json","skills.json","skill_trees.json");
    private SwordCombatMigration(){}
    public static void migrate(Path directory)throws IOException {migrate(directory,true);}
    public static void migrate(Path directory,boolean explicitExistingRules)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        Path backup=directory.resolve(BACKUP),planFile=backup.resolve("plan.json");JsonObject plan;
        if(Files.exists(planFile))plan=read(planFile).getAsJsonObject();
        else {
            JsonObject before=new JsonObject(),candidates=new JsonObject();
            for(String file:FILES){before.addProperty(file,hash(Files.readAllBytes(directory.resolve(file))));candidates.add(file,read(directory.resolve(file)));}
            JsonObject baseline=resource("sword-combat-0.11.0.json").getAsJsonObject();
            JsonArray skills=candidates.getAsJsonArray("skills.json"),defaults=resource("skills.json").getAsJsonArray();
            boolean compatible=true;
            for(JsonElement row:baseline.getAsJsonArray("skills")) {
                JsonObject old=row.getAsJsonObject(),actual=find(skills,old.get("id").getAsString());
                if(actual==null){compatible=false;continue;}
                if(!actual.get("action").equals(old.get("action"))||!Set.of(old.get("model").getAsString(),"rapier_fullbody.fsvoxel").contains(actual.get("model").getAsString()))compatible=false;
            }
            for(String name:List.of("legacy_rapier.fsvoxel","legacy_rapier_fp.fsvoxel")) {
                Path external=directory.resolve("models").resolve(name);
                if(Files.exists(external)&&!hash(Files.readAllBytes(external)).equals(hash(bytes("/assets/fsrpg/fsvoxel/models/"+name))))compatible=false;
            }
            JsonObject combat=candidates.getAsJsonObject("combat.json"),fresh=RpgConfig.JSON.toJsonTree(SwordCombatRules.defaults()).getAsJsonObject();
            if(!combat.has("swordCombat")){fresh.addProperty("enabled",compatible);combat.add("swordCombat",fresh);}
            else if(combat.get("swordCombat").isJsonObject())mergeMissing(combat.getAsJsonObject("swordCombat"),fresh);
            if(!compatible&&!explicitExistingRules)combat.getAsJsonObject("swordCombat").addProperty("enabled",false);
            boolean enabled=combat.getAsJsonObject("swordCombat").get("enabled").getAsBoolean();
            if(compatible&&enabled)for(JsonElement e:skills){JsonObject row=e.getAsJsonObject();if(row.get("weapon").getAsString().equals("sword")&&row.get("model").getAsString().equals("legacy_rapier.fsvoxel"))row.addProperty("model","rapier_fullbody.fsvoxel");}
            if(find(skills,"sword_parry_return")==null)skills.add(Objects.requireNonNull(find(defaults,"sword_parry_return")).deepCopy());
            JsonArray nodes=candidates.getAsJsonObject("skill_trees.json").getAsJsonArray("nodes"),freshNodes=resource("skill_trees.json").getAsJsonObject().getAsJsonArray("nodes");
            if(compatible&&enabled)for(JsonElement e:baseline.getAsJsonArray("nodes")){JsonObject old=e.getAsJsonObject(),actual=find(nodes,old.get("id").getAsString()),next=find(freshNodes,old.get("id").getAsString());if(actual!=null&&next!=null&&Objects.equals(actual.get("description"),old.get("description")))actual.add("description",next.get("description").deepCopy());}
            validate(directory,candidates);plan=new JsonObject();plan.addProperty("releaseVersion","0.12.0");plan.addProperty("customModelsPreserved",true);plan.addProperty("stockModelsCompatible",compatible);plan.add("before",before);plan.add("candidates",candidates);
            Files.createDirectories(backup);
            for(String file:FILES){Path source=directory.resolve(file),copy=backup.resolve(file);if(!hash(Files.readAllBytes(source)).equals(before.get(file).getAsString()))throw new IOException("Concurrent sword migration edit: "+file);if(!Files.exists(copy))Files.copy(source,copy);if(!hash(Files.readAllBytes(copy)).equals(before.get(file).getAsString()))throw new IOException("Sword migration backup mismatch: "+file);}
            RpgConfig.atomicWrite(planFile,RpgConfig.JSON.toJson(plan));
        }
        if(!"0.12.0".equals(plan.get("releaseVersion").getAsString()))throw new IOException("Unknown sword migration plan");
        JsonObject candidates=plan.getAsJsonObject("candidates");validate(directory,candidates);
        for(String file:FILES)if(!read(directory.resolve(file)).equals(candidates.get(file))&&!hash(Files.readAllBytes(directory.resolve(file))).equals(plan.getAsJsonObject("before").get(file).getAsString()))throw new IOException("Concurrent edit during sword migration: "+file);
        for(String file:FILES)if(!read(directory.resolve(file)).equals(candidates.get(file)))RpgConfig.atomicWrite(directory.resolve(file),RpgConfig.JSON.toJson(candidates.get(file)));
        JsonObject marker=new JsonObject();marker.addProperty("releaseVersion","0.12.0");marker.addProperty("customFieldsPreserved",true);marker.addProperty("stockModelsCompatible",plan.get("stockModelsCompatible").getAsBoolean());RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }
    private static void mergeMissing(JsonObject existing,JsonObject defaults){for(var e:defaults.entrySet())if(!existing.has(e.getKey()))existing.add(e.getKey(),e.getValue().deepCopy());}
    private static JsonObject find(JsonArray rows,String id){for(JsonElement e:rows){var r=e.getAsJsonObject();if(r.get("id").getAsString().equals(id))return r;}return null;}
    private static void validate(Path d,JsonObject c)throws IOException{
        var candidate=RpgConfig.parse(c.get("combat.json"),c.get("skills.json"),read(d.resolve("quests.json")),read(d.resolve("weapons.json")),read(d.resolve("styles.json")),read(d.resolve("collections.json")),c.get("skill_trees.json"),read(d.resolve("archery.json")));
        RpgConfig.validateSwordReferences(candidate);
    }
    private static JsonElement read(Path p)throws IOException{if(Files.size(p)>512*1024)throw new IOException("Oversized config "+p);return JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8));}
    private static JsonElement resource(String name)throws IOException{return JsonParser.parseString(new String(bytes("/defaults/"+name),StandardCharsets.UTF_8));}
    private static byte[] bytes(String path)throws IOException{try(var in=SwordCombatMigration.class.getResourceAsStream(path)){if(in==null)throw new IOException("Missing resource "+path);return in.readAllBytes();}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
}

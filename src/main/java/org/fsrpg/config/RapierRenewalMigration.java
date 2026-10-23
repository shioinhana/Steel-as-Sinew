package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 0.10.0 stock animation replacement with field-wise configuration preservation and durable retry. */
public final class RapierRenewalMigration {
    public static final String MARKER="rapier-renewal-migration-v1.json", BACKUP="rapier-renewal-backup-v1";
    private static final List<String> FILES=List.of("skills.json","skill_trees.json");
    private static final List<String> MODELS=List.of("models/legacy_rapier.fsvoxel","models/legacy_rapier_fp.fsvoxel");
    private static final List<String> IDS=List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust",
            "rapier_lunge_finish","sword_thrust","rapier_riposte","rapier_reprise");
    private static final List<String> FIELDS=List.of("label","windupTicks","activeTicks","recoveryTicks","model","action");
    private RapierRenewalMigration() {}

    public static void migrate(Path directory)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        Map<String,byte[]> models=new LinkedHashMap<>();
        for(String name:MODELS)models.put(name,bytes("/assets/fsrpg/fsvoxel/"+name));
        migrate(directory,resource("rapier-renewal-0.9.5.json").getAsJsonObject(),models);
    }

    static void migrate(Path directory,JsonObject baseline,Map<String,byte[]> models)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        Path backup=directory.resolve(BACKUP),planPath=backup.resolve("plan.json");
        JsonObject plan;
        if(Files.exists(planPath))plan=read(planPath).getAsJsonObject();
        else {
            plan=plan(directory,baseline,models);
            validate(directory,plan.getAsJsonObject("candidates"));
            Files.createDirectories(backup);
            for(var entry:plan.getAsJsonObject("beforeSha256").entrySet()) {
                Path source=directory.resolve(entry.getKey()),copy=backup.resolve(entry.getKey());
                String expected=entry.getValue().getAsString();
                if(!hash(source).equals(expected))throw new IOException("Concurrent edit before rapier renewal backup: "+entry.getKey());
                Files.createDirectories(copy.getParent());
                if(!Files.exists(copy))Files.copy(source,copy);
                if(!hash(copy).equals(expected))throw new IOException("Rapier renewal backup differs from original: "+entry.getKey());
            }
            RpgConfig.atomicWrite(planPath,RpgConfig.JSON.toJson(plan));
        }
        if(!"0.10.0".equals(plan.get("releaseVersion").getAsString()))throw new IOException("Unknown rapier renewal plan");
        JsonObject candidates=plan.getAsJsonObject("candidates"),before=plan.getAsJsonObject("beforeSha256");
        validate(directory,candidates);
        if(plan.has("techniqueSourceSha256")&&!hash(directory.resolve("models/rapier.fsvoxel")).equals(plan.get("techniqueSourceSha256").getAsString()))
            throw new IOException("Concurrent custom model edit during rapier renewal: models/rapier.fsvoxel");
        // Preflight every target before the first replacement, including a retry after interrupted writes.
        for(String name:FILES)if(!read(directory.resolve(name)).equals(candidates.get(name))&&!hash(directory.resolve(name)).equals(before.get(name).getAsString()))
            throw new IOException("Concurrent custom edit during rapier renewal: "+name);
        for(var value:plan.getAsJsonArray("modelUpgrade")) {
            var row=value.getAsJsonObject();if(!row.get("applied").getAsBoolean())continue;
            String name=row.get("relativePath").getAsString(),next=row.get("newSha256").getAsString();
            if(!RapierFencingMigration.digest(Objects.requireNonNull(models.get(name))).equals(next))throw new IOException("Bundled rapier model changed after durable plan: "+name);
            String current=hash(directory.resolve(name));
            if(!current.equals(next)&&!current.equals(row.get("oldSha256").getAsString()))throw new IOException("Concurrent custom model edit during rapier renewal: "+name);
        }
        for(String name:FILES)if(!read(directory.resolve(name)).equals(candidates.get(name)))RpgConfig.atomicWrite(directory.resolve(name),RpgConfig.JSON.toJson(candidates.get(name)));
        for(var value:plan.getAsJsonArray("modelUpgrade")) {
            var row=value.getAsJsonObject();if(!row.get("applied").getAsBoolean())continue;
            String name=row.get("relativePath").getAsString();
            if(!hash(directory.resolve(name)).equals(row.get("newSha256").getAsString()))writeBytes(directory.resolve(name),models.get(name));
        }
        JsonObject marker=new JsonObject();marker.addProperty("releaseVersion","0.10.0");marker.addProperty("schemaVersion",1);
        marker.addProperty("customFieldsPreserved",true);marker.addProperty("customModelsPreserved",true);
        marker.add("skillUpgrade",plan.get("skillUpgrade").deepCopy());marker.add("modelUpgrade",plan.get("modelUpgrade").deepCopy());
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }

    private static JsonObject plan(Path directory,JsonObject baseline,Map<String,byte[]> models)throws IOException {
        if(!"fsrpg.rapier-renewal.baseline.v1".equals(baseline.get("format").getAsString()))throw new IOException("Unknown rapier renewal baseline");
        JsonObject candidates=new JsonObject(),before=new JsonObject();
        for(String name:FILES){candidates.add(name,read(directory.resolve(name)));before.addProperty(name,hash(directory.resolve(name)));}
        JsonArray modelUpgrade=new JsonArray();boolean compatible=true;
        for(String name:MODELS) {
            String old=Files.isRegularFile(directory.resolve(name))?hash(directory.resolve(name)):null;
            String stock=baseline.getAsJsonObject("models").get(name).getAsString(),next=RapierFencingMigration.digest(models.get(name));
            boolean original=stock.equals(old),current=next.equals(old),apply=original&&!current;
            compatible&=original||current;
            JsonObject row=new JsonObject();row.addProperty("relativePath",name);nullable(row,"oldSha256",old);
            nullable(row,"newSha256",apply?next:old);row.addProperty("applied",apply);row.addProperty("customPreserved",!original&&!current);modelUpgrade.add(row);
            if(apply)before.addProperty(name,old);
        }
        String techniqueModel="models/rapier.fsvoxel";
        boolean stockTechnique=Files.isRegularFile(directory.resolve(techniqueModel))&&hash(directory.resolve(techniqueModel)).equals(baseline.getAsJsonObject("models").get(techniqueModel).getAsString());
        JsonObject updates=new JsonObject();JsonArray actual=candidates.getAsJsonArray("skills.json");
        JsonArray oldSkills=baseline.getAsJsonArray("skills"),freshSkills=resource("skills.json").getAsJsonArray();
        if(compatible)for(String id:IDS) {
            JsonObject old=find(oldSkills,id),next=find(freshSkills,id),row=find(actual,id);if(row==null||old==null||next==null)continue;
            if(!Objects.equals(row.get("model"),old.get("model"))||!Objects.equals(row.get("action"),old.get("action")))continue;
            if((id.equals("rapier_riposte")||id.equals("rapier_reprise"))&&!stockTechnique)continue;
            JsonArray fields=new JsonArray();
            for(String field:FIELDS)if(Objects.equals(row.get(field),old.get(field))&&!Objects.equals(row.get(field),next.get(field))) {
                row.add(field,next.get(field).deepCopy());fields.add(field);
            }
            if(!fields.isEmpty())updates.add(id,fields);
        }
        JsonArray descriptions=new JsonArray();
        if(compatible) {
            JsonArray oldNodes=baseline.getAsJsonArray("nodes"),
                    newNodes=resource("skill_trees.json").getAsJsonObject().getAsJsonArray("nodes"),
                    actualNodes=candidates.getAsJsonObject("skill_trees.json").getAsJsonArray("nodes");
            for(String id:List.of("rapier","rapier_thrust","rapier_parry_thrust")) {
                JsonObject old=find(oldNodes,id),next=find(newNodes,id),row=find(actualNodes,id);
                if(row!=null&&Objects.equals(row.get("description"),old.get("description"))&&!Objects.equals(row.get("description"),next.get("description"))) {
                    row.add("description",next.get("description").deepCopy());descriptions.add(id);
                }
            }
        }
        JsonObject upgrade=new JsonObject();upgrade.addProperty("modelsCompatible",compatible);upgrade.addProperty("legacyTechniqueModelStock",stockTechnique);
        upgrade.add("updatedFields",updates);upgrade.add("updatedDescriptions",descriptions);
        JsonObject result=new JsonObject();result.addProperty("releaseVersion","0.10.0");result.addProperty("schemaVersion",1);
        result.add("beforeSha256",before);result.add("candidates",candidates);result.add("modelUpgrade",modelUpgrade);result.add("skillUpgrade",upgrade);
        if(updates.has("rapier_riposte")||updates.has("rapier_reprise"))result.addProperty("techniqueSourceSha256",hash(directory.resolve(techniqueModel)));
        return result;
    }
    private static void validate(Path directory,JsonObject candidates)throws IOException {
        RpgConfig.parse(read(directory.resolve("combat.json")),candidates.get("skills.json"),read(directory.resolve("quests.json")),read(directory.resolve("weapons.json")),
                read(directory.resolve("styles.json")),read(directory.resolve("collections.json")),candidates.get("skill_trees.json"),read(directory.resolve("archery.json")));
    }
    private static JsonObject find(JsonArray values,String id) {for(var value:values){var row=value.getAsJsonObject();if(id.equals(row.get("id").getAsString()))return row;}return null;}
    private static void nullable(JsonObject row,String name,String value){row.add(name,value==null?JsonNull.INSTANCE:new JsonPrimitive(value));}
    private static JsonElement resource(String name)throws IOException{return JsonParser.parseString(new String(bytes("/defaults/"+name),StandardCharsets.UTF_8));}
    private static byte[] bytes(String name)throws IOException{try(var in=RapierRenewalMigration.class.getResourceAsStream(name)){if(in==null)throw new IOException("Missing rapier renewal resource: "+name);return in.readAllBytes();}}
    private static JsonElement read(Path file)throws IOException{if(Files.size(file)>512*1024)throw new IOException(file.getFileName()+" exceeds 512 KiB");return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8));}
    private static String hash(Path file)throws IOException{return RapierFencingMigration.digest(Files.readAllBytes(file));}
    private static void writeBytes(Path file,byte[] bytes)throws IOException {
        Path temp=Files.createTempFile(file.getParent(),".fsrpg-rapier-renewal-",".tmp");
        try{Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
}

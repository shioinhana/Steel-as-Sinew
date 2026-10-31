package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Upgrade the stock four-stage rapier only when both authoritative animation models can follow it. */
public final class RapierFencingMigration {
    public static final String MARKER="rapier-fencing-migration-v1.json", BACKUP="rapier-fencing-backup-v1";
    private static final String FIFTH="rapier_lunge_finish";
    private static final List<String> JSON_FILES=List.of("skills.json","styles.json","skill_trees.json");
    private static final List<String> MODELS=List.of("models/legacy_rapier.fsvoxel","models/legacy_rapier_fp.fsvoxel");
    private static final List<String> SKILL_FIELDS=List.of("label","windupTicks","activeTicks","recoveryTicks");
    private RapierFencingMigration() {}

    public static void migrate(Path directory)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        JsonObject baseline=resourceJson("/defaults/rapier-fencing-0.8.4.json").getAsJsonObject();
        Map<String,byte[]> models=new LinkedHashMap<>();
        for(String name:MODELS)models.put(name,resourceBytes("/assets/fsrpg/fsvoxel/"+name));
        migrate(directory,baseline,models);
    }

    /** An absent tree file must remain valid while historical migrations still use a four-stage catalog. */
    static JsonElement compatibleInitialTrees(JsonElement skills,JsonElement trees)throws IOException {
        if(find(skills.getAsJsonArray(),FIFTH)!=null)return trees;
        JsonObject adapted=trees.deepCopy().getAsJsonObject();
        JsonObject root=find(adapted.getAsJsonArray("nodes"),"rapier");
        if(root!=null) {
            JsonArray allowed=new JsonArray();for(var id:root.getAsJsonArray("skills"))if(!FIFTH.equals(id.getAsString()))allowed.add(id.deepCopy());
            root.add("skills",allowed);
            JsonObject baseline=resourceJson("/defaults/rapier-fencing-0.8.4.json").getAsJsonObject();
            root.add("description",baseline.get("rootDescription").deepCopy());
        }
        return adapted;
    }

    /** The byte-source seam keeps the real migration testable without registries or a renderer. */
    static void migrate(Path directory,JsonObject baseline,Map<String,byte[]> bundledModels)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        Path backup=directory.resolve(BACKUP),planFile=backup.resolve("plan.json");
        JsonObject plan;
        if(Files.exists(planFile))plan=read(planFile).getAsJsonObject();
        else {
            plan=plan(directory,baseline,bundledModels);
            validate(directory,plan.getAsJsonObject("candidates"));
            Files.createDirectories(backup);
            for(var entry:plan.getAsJsonObject("beforeSha256").entrySet()) {
                Path source=directory.resolve(entry.getKey()),copy=backup.resolve(entry.getKey());
                String expected=entry.getValue().getAsString();
                if(!digest(Files.readAllBytes(source)).equals(expected))throw new IOException("Concurrent edit before fencing backup: "+entry.getKey());
                Files.createDirectories(copy.getParent());
                if(!Files.exists(copy))Files.copy(source,copy);
                if(!digest(Files.readAllBytes(copy)).equals(expected))throw new IOException("Fencing backup does not match its original bytes: "+entry.getKey());
            }
            // Reuse the complete proposal on interrupted retries instead of reinterpreting an
            // already-updated skill, model, or chain as a custom edit.
            RpgConfig.atomicWrite(planFile,RpgConfig.JSON.toJson(plan));
        }
        if(!"0.8.5".equals(plan.get("releaseVersion").getAsString()))throw new IOException("Unknown fencing migration plan");
        JsonObject candidates=plan.getAsJsonObject("candidates"),before=plan.getAsJsonObject("beforeSha256");
        validate(directory,candidates);
        for(String name:JSON_FILES) {
            JsonElement current=read(directory.resolve(name)),next=candidates.get(name);
            if(!current.equals(next)) {
                if(!digest(Files.readAllBytes(directory.resolve(name))).equals(before.get(name).getAsString()))
                    throw new IOException("Concurrent custom edit during fencing migration: "+name);
                RpgConfig.atomicWrite(directory.resolve(name),RpgConfig.JSON.toJson(next));
            }
        }
        for(var value:plan.getAsJsonArray("modelUpgrade")) {
            JsonObject row=value.getAsJsonObject();if(!row.get("applied").getAsBoolean())continue;
            String name=row.get("relativePath").getAsString(),next=row.get("newSha256").getAsString();
            byte[] bytes=Objects.requireNonNull(bundledModels.get(name));
            if(!digest(bytes).equals(next))throw new IOException("Bundled fencing model changed after its durable plan: "+name);
            Path target=directory.resolve(name);String current=digest(Files.readAllBytes(target));
            if(!current.equals(next)) {
                if(!current.equals(row.get("oldSha256").getAsString()))throw new IOException("Concurrent custom model edit during fencing migration: "+name);
                writeBytes(target,bytes);
            }
        }
        JsonObject marker=new JsonObject();marker.addProperty("releaseVersion","0.8.5");marker.addProperty("schemaVersion",1);
        marker.addProperty("customFieldsPreserved",true);marker.addProperty("customModelsPreserved",true);
        marker.add("skillUpgrade",plan.get("skillUpgrade").deepCopy());marker.add("modelUpgrade",plan.get("modelUpgrade").deepCopy());
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }

    private static JsonObject plan(Path directory,JsonObject baseline,Map<String,byte[]> bundledModels)throws IOException {
        if(!"fsrpg.rapier-fencing.baseline.v1".equals(baseline.get("format").getAsString()))throw new IOException("Unknown fencing baseline");
        JsonObject candidates=new JsonObject(),before=new JsonObject();
        for(String name:JSON_FILES){Path file=directory.resolve(name);candidates.add(name,read(file));before.addProperty(name,digest(Files.readAllBytes(file)));}
        JsonArray modelUpgrade=new JsonArray();boolean modelsReady=true;
        for(String name:MODELS) {
            String stock=baseline.getAsJsonObject("models").get(name).getAsString();
            String next=digest(Objects.requireNonNull(bundledModels.get(name))),old=Files.isRegularFile(directory.resolve(name))?digest(Files.readAllBytes(directory.resolve(name))):null;
            boolean original=stock.equals(old),current=next.equals(old),apply=original&&!current;
            modelsReady&=(original||current)&&!stock.equals(next);
            JsonObject row=new JsonObject();row.addProperty("relativePath",name);nullable(row,"oldSha256",old);
            nullable(row,"newSha256",apply?next:old);row.addProperty("applied",apply);row.addProperty("customPreserved",!original&&!current);modelUpgrade.add(row);
            if(apply)before.addProperty(name,old);
        }
        JsonArray skills=candidates.getAsJsonArray("skills.json"),styles=candidates.getAsJsonArray("styles.json");
        // Later sword modes decide their own models/descriptions after this historical chain upgrade.
        JsonObject historicalTarget=resourceJson("/defaults/sword-combat-0.11.0.json").getAsJsonObject();
        JsonObject rapier=find(styles,"rapier"),nextSkill=find(historicalTarget.getAsJsonArray("skills"),FIFTH);
        JsonObject fifth=find(skills,FIFTH);boolean plainSkills=true;
        for(var value:baseline.getAsJsonArray("skills")) {
            JsonObject old=value.getAsJsonObject(),actual=find(skills,old.get("id").getAsString());
            for(String field:List.of("weapon","input","model","action"))
                if(actual==null||!Objects.equals(actual.get(field),old.get(field)))plainSkills=false;
        }
        boolean stockChain=rapier!=null&&Objects.equals(rapier.get("leftCombo"),baseline.get("leftCombo"));
        boolean compatibleFifth=fifth==null||fifth.equals(nextSkill);
        boolean catalogHasRoom=fifth!=null||skills.size()<128;
        boolean enabled=modelsReady&&plainSkills&&stockChain&&compatibleFifth&&catalogHasRoom;
        JsonObject updates=new JsonObject();boolean fifthAdded=false,chainExtended=false,rootExtended=false,descriptionUpdated=false;
        if(enabled) {
            JsonArray fresh=historicalTarget.getAsJsonArray("skills");
            for(var value:baseline.getAsJsonArray("skills")) {
                JsonObject old=value.getAsJsonObject();String id=old.get("id").getAsString();
                JsonObject actual=find(skills,id),next=find(fresh,id);JsonArray fields=new JsonArray();
                for(String field:SKILL_FIELDS)if(Objects.equals(actual.get(field),old.get(field))&&!Objects.equals(actual.get(field),next.get(field))) {
                    actual.add(field,next.get(field).deepCopy());fields.add(field);
                }
                if(!fields.isEmpty())updates.add(id,fields);
            }
            if(fifth==null){skills.add(nextSkill.deepCopy());fifthAdded=true;}
            JsonObject nextStyle=find(resourceJson("/defaults/styles.json").getAsJsonArray(),"rapier");
            rapier.add("leftCombo",nextStyle.get("leftCombo").deepCopy());chainExtended=true;
            JsonObject root=find(candidates.getAsJsonObject("skill_trees.json").getAsJsonArray("nodes"),"rapier");
            JsonObject freshRoot=find(historicalTarget.getAsJsonArray("nodes"),"rapier");
            if(root!=null&&Objects.equals(root.get("skills"),baseline.get("rootSkills"))){root.add("skills",freshRoot.get("skills").deepCopy());rootExtended=true;}
            if(root!=null&&Objects.equals(root.get("description"),baseline.get("rootDescription"))){root.add("description",freshRoot.get("description").deepCopy());descriptionUpdated=true;}
        }
        String reason=enabled?"stock_models_and_chain":!modelsReady?"custom_missing_or_unavailable_models":!plainSkills?"custom_or_missing_animation_skill":!stockChain?"custom_or_current_chain":!compatibleFifth?"custom_fifth_skill_id":"catalog_full";
        JsonObject skillUpgrade=new JsonObject();skillUpgrade.addProperty("enabled",enabled);skillUpgrade.addProperty("reason",reason);
        skillUpgrade.add("updatedFields",updates);skillUpgrade.addProperty("fifthAdded",fifthAdded);skillUpgrade.addProperty("chainExtended",chainExtended);
        skillUpgrade.addProperty("rootSkillsExtended",rootExtended);skillUpgrade.addProperty("rootDescriptionUpdated",descriptionUpdated);
        JsonObject result=new JsonObject();result.addProperty("releaseVersion","0.8.5");result.addProperty("schemaVersion",1);
        result.add("beforeSha256",before);result.add("candidates",candidates);result.add("modelUpgrade",modelUpgrade);result.add("skillUpgrade",skillUpgrade);
        return result;
    }

    private static void validate(Path directory,JsonObject candidate)throws IOException {
        RpgConfig.parse(read(directory.resolve("combat.json")),candidate.get("skills.json"),read(directory.resolve("quests.json")),read(directory.resolve("weapons.json")),
                candidate.get("styles.json"),read(directory.resolve("collections.json")),candidate.get("skill_trees.json"),read(directory.resolve("archery.json")));
    }
    private static JsonObject find(JsonArray values,String id) {
        JsonObject found=null;
        for(var value:values){JsonObject row=value.getAsJsonObject();if(id.equals(row.get("id").getAsString())){if(found!=null)throw new IllegalArgumentException("Duplicate id: "+id);found=row;}}
        return found;
    }
    private static void nullable(JsonObject row,String name,String value){row.add(name,value==null?JsonNull.INSTANCE:new JsonPrimitive(value));}
    private static JsonElement resourceJson(String name)throws IOException{return JsonParser.parseString(new String(resourceBytes(name),StandardCharsets.UTF_8));}
    private static byte[] resourceBytes(String name)throws IOException{try(var in=RapierFencingMigration.class.getResourceAsStream(name)){if(in==null)throw new IOException("Missing fencing resource: "+name);return in.readAllBytes();}}
    private static JsonElement read(Path file)throws IOException{if(Files.size(file)>512*1024)throw new IOException(file.getFileName()+" exceeds 512 KiB");return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8));}
    static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void writeBytes(Path file,byte[] bytes)throws IOException {
        Path temp=Files.createTempFile(file.getParent(),".fsrpg-fencing-",".tmp");
        try{Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temp);}
    }
}

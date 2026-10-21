package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.fsrpg.creature.GuardCueSettings;

/** Add one tick once, preserving disabled guards and all unrelated custom tuning. */
public final class DefenseFlowMigration {
    public static final String MARKER="defense-flow-migration-v1.json", BACKUP="defense-flow-backup-v1";
    private static final List<String> FILES=List.of("combat.json","styles.json","guard_cues.json");
    private final Path directory;
    private final boolean existingCombat,existingCue,existingStyles;
    private DefenseFlowMigration(Path directory,boolean combat,boolean cue,boolean styles){this.directory=directory;existingCombat=combat;existingCue=cue;existingStyles=styles;}
    public static DefenseFlowMigration capture(Path directory)throws IOException{
        if(Files.exists(directory.resolve(MARKER)))return null;
        Path plan=directory.resolve(BACKUP).resolve("plan.json");
        if(Files.exists(plan)){
            JsonObject p=read(plan).getAsJsonObject();
            return new DefenseFlowMigration(directory,p.get("existingCombat").getAsBoolean(),p.get("existingCue").getAsBoolean(),p.get("existingStyles").getAsBoolean());
        }
        return new DefenseFlowMigration(directory,Files.exists(directory.resolve("combat.json")),Files.exists(directory.resolve("guard_cues.json")),Files.exists(directory.resolve("styles.json")));
    }
    public void apply()throws IOException{
        Path backup=directory.resolve(BACKUP),plan=backup.resolve("plan.json");
        JsonObject candidates;
        if(Files.exists(plan)) candidates=read(plan).getAsJsonObject().getAsJsonObject("candidates");
        else {
            JsonObject combat=read(directory.resolve("combat.json")).getAsJsonObject();
            JsonArray styles=read(directory.resolve("styles.json")).getAsJsonArray();
            JsonObject cue=read(directory.resolve("guard_cues.json")).getAsJsonObject();
            extendGuard(combat,existingCombat);
            for(var value:styles)for(String field:List.of("guard","heavyGuard")){
                JsonObject style=value.getAsJsonObject();if(style.has(field)&&style.get(field).isJsonObject())extendGuard(style.getAsJsonObject(field),existingStyles);
            }
            // Older JSON omitted this extension and used the 0.8.3 two-tick fallback.
            if(existingCombat){
                JsonObject clash=combat.has("weaponClash")?combat.getAsJsonObject("weaponClash"):new JsonObject();
                int previous=clash.has("contactWindowTicks")?clash.get("contactWindowTicks").getAsInt():2;
                clash.addProperty("contactWindowTicks",Math.min(10,Math.addExact(previous,1)));
                if(!clash.has("damageTolerance"))clash.addProperty("damageTolerance",3);
                combat.add("weaponClash",clash);
            }
            if(existingCue&&cue.get("enabled").getAsBoolean()){
                int next=Math.min(60,Math.addExact(cue.get("inputWindowTicks").getAsInt(),1));
                cue.addProperty("inputWindowTicks",next);
                cue.addProperty("flashLeadTicks",Math.max(cue.get("flashLeadTicks").getAsInt(),next));
            }
            candidates=new JsonObject();candidates.add("combat.json",combat);candidates.add("styles.json",styles);candidates.add("guard_cues.json",cue);
            validate(candidates);
            Files.createDirectories(backup);
            for(String name:FILES)if(!Files.exists(backup.resolve(name)))Files.copy(directory.resolve(name),backup.resolve(name));
            JsonObject p=new JsonObject();p.addProperty("existingCombat",existingCombat);p.addProperty("existingCue",existingCue);p.addProperty("existingStyles",existingStyles);p.add("candidates",candidates);
            // Write the complete validated plan before the first mutation. Interrupted retries
            // reuse this exact candidate instead of incrementing an already-written field again.
            RpgConfig.atomicWrite(plan,RpgConfig.JSON.toJson(p));
        }
        validate(candidates);
        for(String name:FILES){
            JsonElement current=read(directory.resolve(name)),next=candidates.get(name);
            if(!current.equals(next)){
                if(!current.equals(read(backup.resolve(name))))throw new IOException("Concurrent custom edit during defense migration: "+name);
                RpgConfig.atomicWrite(directory.resolve(name),RpgConfig.JSON.toJson(next));
            }
        }
        JsonObject marker=new JsonObject();marker.addProperty("releaseVersion","0.8.4");marker.addProperty("windowDeltaTicks",1);
        marker.addProperty("disabledWindowsPreserved",true);marker.addProperty("unrelatedFieldsPreserved",true);marker.addProperty("continuousPerfectGuardLimit",3);
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }
    private static void extendGuard(JsonObject guard,boolean existing){int ticks=guard.get("perfectGuardTicks").getAsInt();if(ticks>0)guard.addProperty("perfectGuardTicks",existing?Math.min(72000,Math.addExact(ticks,1)):7);}
    private void validate(JsonObject candidates)throws IOException{
        RpgConfig.parse(candidates.get("combat.json"),read(directory.resolve("skills.json")),read(directory.resolve("quests.json")),read(directory.resolve("weapons.json")),
                candidates.get("styles.json"),read(directory.resolve("collections.json")),read(directory.resolve("skill_trees.json")),read(directory.resolve("archery.json")));
        GuardCueSettings.parse(RpgConfig.JSON.toJson(candidates.get("guard_cues.json")));
    }
    private static JsonElement read(Path file)throws IOException{
        if(Files.size(file)>512*1024)throw new IOException(file.getFileName()+" exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8));
    }
}

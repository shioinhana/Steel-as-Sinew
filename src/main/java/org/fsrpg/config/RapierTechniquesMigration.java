package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * 0.9.0 migration: the released rapier conditional techniques were described by the cultivation
 * node and {@code docs/西洋剑.md} but the stock styles shipped an empty pair, so the legacy
 * "拨挡还击 / 闪步回刺" branches stayed unreachable. Enable the documented default pair once for a
 * stock/empty rapier techniques object. A non-empty custom pair is kept verbatim, the complete
 * candidate is validated before any write, the exact pre-migration bytes are backed up, and the
 * marker makes the step idempotent.
 */
public final class RapierTechniquesMigration {
    public static final String MARKER="rapier-techniques-migration-v1.json", BACKUP="rapier-techniques-backup-v1";
    public static final String RIPOSTE_SKILL="rapier_riposte", REPRISE_SKILL="rapier_reprise";
    public static final int RIPOSTE_WINDOW_TICKS=20, REPRISE_WINDOW_TICKS=12;
    private static final String REASON_DEFAULTS="enabled_released_defaults", REASON_ALREADY="already_released_defaults",
            REASON_CUSTOM="custom_techniques", REASON_ABSENT="no_rapier_style", REASON_MISSING="missing_techniques";
    private RapierTechniquesMigration(){}

    public static void migrate(Path directory)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        Path stylesFile=directory.resolve("styles.json");
        if(!Files.exists(stylesFile))return;
        JsonElement parsed=read(stylesFile);
        if(!parsed.isJsonArray())throw new IOException("styles.json must be an array");
        JsonArray styles=parsed.getAsJsonArray(),next=styles.deepCopy();
        JsonObject rapier=find(next,"rapier");
        boolean changed=false,custom=false;String reason;JsonObject previous=null;
        if(rapier==null){reason=REASON_ABSENT;}
        else{
            JsonElement existing=rapier.get("techniques");
            if(existing==null||!existing.isJsonObject()){
                // A pre-refinement style object (old required fields only) is a real custom legacy file.
                // Never invent a technique pair there; Techniques.none() already keeps it valid.
                reason=REASON_MISSING;
            } else if(released(existing.getAsJsonObject())){reason=REASON_ALREADY;}
            else if(!empty(existing.getAsJsonObject())){reason=REASON_CUSTOM;custom=true;}
            else{
                previous=existing.getAsJsonObject().deepCopy();
                JsonObject techniques=new JsonObject();
                techniques.addProperty("riposteSkill",RIPOSTE_SKILL);
                techniques.addProperty("riposteWindowTicks",RIPOSTE_WINDOW_TICKS);
                techniques.addProperty("repriseSkill",REPRISE_SKILL);
                techniques.addProperty("repriseWindowTicks",REPRISE_WINDOW_TICKS);
                rapier.add("techniques",techniques);
                changed=true;reason=REASON_DEFAULTS;
            }
        }
        if(changed){
            try{validate(directory,next);}
            catch(RuntimeException invalid){
                // A custom/partial catalog may not contain the conditional skill ids yet. Keep the file
                // untouched and leave no marker so a repaired catalog can still receive the defaults.
                return;
            }
            Path backup=directory.resolve(BACKUP).resolve("styles.json");
            Files.createDirectories(backup.getParent());
            // Capture the true original bytes before writing. Interrupted retries retain them.
            if(!Files.exists(backup))Files.copy(stylesFile,backup);
            RpgConfig.atomicWrite(stylesFile,RpgConfig.JSON.toJson(next));
        }
        JsonObject marker=new JsonObject();
        marker.addProperty("schemaVersion",1);marker.addProperty("releaseVersion","0.9.0");
        marker.addProperty("field","rapier.techniques");marker.addProperty("changed",changed);
        marker.addProperty("reason",reason);
        if(previous!=null)marker.add("previousValue",previous);
        if(changed)marker.add("newValue",rapier.getAsJsonObject("techniques").deepCopy());
        if(changed)marker.addProperty("backup",BACKUP+"/styles.json");
        marker.addProperty("customTechniquesPreserved",true);
        marker.addProperty("customTechniquesDetected",custom);
        marker.addProperty("unrelatedStylesPreserved",true);
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }

    /** The exact released pair already enabled; re-writing it would only churn the user's file. */
    private static boolean released(JsonObject techniques){
        return RIPOSTE_SKILL.equals(string(techniques,"riposteSkill"))&&number(techniques,"riposteWindowTicks")==RIPOSTE_WINDOW_TICKS
                &&REPRISE_SKILL.equals(string(techniques,"repriseSkill"))&&number(techniques,"repriseWindowTicks")==REPRISE_WINDOW_TICKS;
    }
    private static String string(JsonObject json,String field){
        JsonElement value=json.get(field);
        return value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()?"":value.getAsString();
    }
    private static double number(JsonObject json,String field){
        JsonElement value=json.get(field);
        return value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()?Double.NaN:value.getAsDouble();
    }
    /** Missing fields are equivalent to the old disabled pair; any enabled value is real custom tuning. */
    private static boolean empty(JsonObject techniques){
        return stringEmpty(techniques,"riposteSkill")&&stringEmpty(techniques,"repriseSkill")
                &&zero(techniques,"riposteWindowTicks")&&zero(techniques,"repriseWindowTicks");
    }
    private static boolean stringEmpty(JsonObject json,String field){
        JsonElement value=json.get(field);
        return value==null||value.isJsonNull()||value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&value.getAsString().isEmpty();
    }
    private static boolean zero(JsonObject json,String field){
        JsonElement value=json.get(field);
        return value==null||value.isJsonNull()||value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&value.getAsDouble()==0;
    }
    private static void validate(Path directory,JsonArray styles)throws IOException{
        RpgConfig.parse(read(directory.resolve("combat.json")),read(directory.resolve("skills.json")),
                read(directory.resolve("quests.json")),read(directory.resolve("weapons.json")),styles,
                read(directory.resolve("collections.json")),read(directory.resolve("skill_trees.json")),
                read(directory.resolve("archery.json")));
    }
    private static JsonObject find(JsonArray values,String id){
        JsonObject found=null;
        for(JsonElement value:values){JsonObject row=value.getAsJsonObject();
            if(id.equals(row.get("id").getAsString())){if(found!=null)throw new IllegalArgumentException("Duplicate style id: "+id);found=row;}}
        return found;
    }
    private static JsonElement read(Path file)throws IOException{
        if(Files.size(file)>512*1024)throw new IOException(file.getFileName()+" exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8));
    }
}

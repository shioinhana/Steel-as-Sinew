package org.fsrpg.creature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** Project-calibrated timing, not a claim of unpublished Zenless Zone Zero frame values. */
public record GuardCueSettings(boolean enabled, int flashLeadTicks, int inputWindowTicks,
                               int flashDurationMillis, boolean guardCancelsActive,
                               String timingMode,double flashAtFraction,double windowOpenFraction,double windowCloseFraction) {
    /** Explicit old Java fixtures/configurations can still request fixed tick timing. */
    public GuardCueSettings(boolean enabled,int flashLeadTicks,int inputWindowTicks,int flashDurationMillis,boolean guardCancelsActive){
        this(enabled,flashLeadTicks,inputWindowTicks,flashDurationMillis,guardCancelsActive,"fixed_ticks",.2,.6,1);
    }
    /** Existing explicit Java fixtures retain their former contact-inclusive boundary. */
    public GuardCueSettings(boolean enabled,int flashLeadTicks,int inputWindowTicks,int flashDurationMillis,boolean guardCancelsActive,
                            String timingMode,double flashAtFraction,double windowOpenFraction){
        this(enabled,flashLeadTicks,inputWindowTicks,flashDurationMillis,guardCancelsActive,timingMode,flashAtFraction,windowOpenFraction,1);
    }
    public GuardCueSettings {
        if(flashLeadTicks<1 || flashLeadTicks>60 || inputWindowTicks<1 || inputWindowTicks>flashLeadTicks
                || flashDurationMillis<16 || flashDurationMillis>500)
            throw new IllegalArgumentException("Invalid guard cue timing: 1 <= inputWindowTicks <= flashLeadTicks <= 60, flash 16..500 ms");
        if(timingMode==null||!Set.of("windup_fraction","fixed_ticks").contains(timingMode)||!Double.isFinite(flashAtFraction)||!Double.isFinite(windowOpenFraction)
                ||!Double.isFinite(windowCloseFraction)||flashAtFraction<0||flashAtFraction>=1||windowOpenFraction<flashAtFraction||windowOpenFraction>1
                ||windowCloseFraction<windowOpenFraction||windowCloseFraction>1)
            throw new IllegalArgumentException("Expected finite 0 <= flashAtFraction <= windowOpenFraction <= windowCloseFraction <= 1 and flashAtFraction < 1");
    }
    public static GuardCueSettings defaults(){return new GuardCueSettings(true,7,7,120,true,"windup_fraction",.2,.6,.8);}
    public int windupTicks(int authored){return !enabled?authored:timingMode.equals("windup_fraction")?Math.max(1,authored):Math.max(authored,flashLeadTicks);}
    public GuardCueWindow window(long started,int windup){
        long contact=started+windupTicks(windup);
        if(timingMode.equals("windup_fraction")){
            long duration=contact-started;
            long flash=java.math.BigDecimal.valueOf(flashAtFraction).multiply(java.math.BigDecimal.valueOf(duration)).setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
            long open=java.math.BigDecimal.valueOf(windowOpenFraction).multiply(java.math.BigDecimal.valueOf(duration)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
            long close=java.math.BigDecimal.valueOf(windowCloseFraction).multiply(java.math.BigDecimal.valueOf(duration)).setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
            return new GuardCueWindow(started,started+flash,started+open,started+close,contact);
        }
        return new GuardCueWindow(started,contact-flashLeadTicks,contact-inputWindowTicks,contact,contact);
    }
    /** One-time translation of the pre-0.9 explicit contact-inclusive close fraction. */
    public static final String CLOSE_MIGRATION_MARKER="guard-cue-close-migration-v1.json";
    public static final String CLOSE_MIGRATION_BACKUP="guard-cue-close-backup-v1";
    public static GuardCueSettings read(Path file)throws IOException {
        if(!Files.exists(file)){
            try(var input=GuardCueSettings.class.getResourceAsStream("/defaults/guard_cues.json")){
                if(input==null)throw new IOException("Missing guard cue defaults");
                Files.createDirectories(file.toAbsolutePath().getParent());
                try{Files.copy(input,file);}catch(java.nio.file.FileAlreadyExistsException concurrent){/* Preserve the existing file. */}
            }
        }
        if(Files.size(file)>64*1024)throw new IOException("guard_cues.json exceeds 64 KiB");
        String text;
        try{text=migrateLegacyClose(file,Files.readString(file,StandardCharsets.UTF_8));}
        catch(RuntimeException invalid){throw new IOException("Invalid guard_cues.json: "+invalid.getMessage(),invalid);}
        try{return parse(text);}
        catch(RuntimeException invalid){throw new IOException("Invalid guard_cues.json: "+invalid.getMessage(),invalid);}
    }
    /**
     * A pre-0.9 file could explicitly repeat the old contact-inclusive default close fraction
     * {@code 1.0}. Translate it once to the four-fifths closing point ({@code 0.8}), keep every
     * custom value, save the exact original bytes and never migrate the same directory twice.
     * Old five-field files that omit the field need no rewrite because {@link #parse} already
     * applies {@code 0.8}.
     */
    private static String migrateLegacyClose(Path file,String text)throws IOException{
        Path directory=file.toAbsolutePath().getParent();
        if(directory==null||Files.exists(directory.resolve(CLOSE_MIGRATION_MARKER)))return text;
        JsonObject json;
        try{
            var parsed=JsonParser.parseString(text);
            if(!parsed.isJsonObject())return text;
            json=parsed.getAsJsonObject();
        }catch(RuntimeException invalid){return text;} // The normal parser reports the real configuration error.
        var close=json.get("windowCloseFraction");
        if(close==null||!close.isJsonPrimitive()||!close.getAsJsonPrimitive().isNumber()||close.getAsDouble()!=1.0)return text;
        double open=json.has("windowOpenFraction")&&json.get("windowOpenFraction").isJsonPrimitive()
                &&json.get("windowOpenFraction").getAsJsonPrimitive().isNumber()?json.get("windowOpenFraction").getAsDouble():.6;
        double migrated=Math.max(.8,open);
        if(!Double.isFinite(migrated)||migrated<open||migrated>1||migrated==1.0)return text;
        JsonObject next=json.deepCopy();
        next.addProperty("windowCloseFraction",migrated);
        String candidate=WRITE.toJson(next);
        parse(candidate); // Validate the complete immutable candidate before any file or backup is touched.
        Path backup=directory.resolve(CLOSE_MIGRATION_BACKUP).resolve("guard_cues.json");
        Files.createDirectories(backup.getParent());
        if(!Files.exists(backup))Files.copy(file,backup);
        Path temporary=Files.createTempFile(directory,"guard-cues-close-",".tmp");
        try{
            Files.writeString(temporary,candidate,StandardCharsets.UTF_8);
            try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException fallback){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
        JsonObject marker=new JsonObject();
        marker.addProperty("schemaVersion",1);marker.addProperty("releaseVersion","0.9.0");
        marker.addProperty("field","windowCloseFraction");marker.addProperty("previousValue",1.0);
        marker.addProperty("windowCloseFraction",migrated);
        marker.addProperty("backup",CLOSE_MIGRATION_BACKUP+"/guard_cues.json");
        marker.addProperty("customValuesPreserved",true);marker.addProperty("unrelatedFieldsPreserved",true);
        AtomicWrite.write(directory.resolve(CLOSE_MIGRATION_MARKER),WRITE.toJson(marker));
        return candidate;
    }
    private static final com.google.gson.Gson WRITE=new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Small local atomic write so the creature package keeps no dependency on the config package. */
    private static final class AtomicWrite{
        private AtomicWrite(){}
        static void write(Path target,String contents)throws IOException{
            Path directory=target.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            Path temporary=Files.createTempFile(directory,target.getFileName().toString(),".tmp");
            try{
                Files.writeString(temporary,contents,StandardCharsets.UTF_8);
                try{Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(java.nio.file.AtomicMoveNotSupportedException fallback){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(temporary);}
        }
    }
    public static GuardCueSettings parse(String text){
        JsonObject json=JsonParser.parseString(text).getAsJsonObject();
        var keys=Set.of("enabled","flashLeadTicks","inputWindowTicks","flashDurationMillis","guardCancelsActive");
        var allowed=Set.of("enabled","flashLeadTicks","inputWindowTicks","flashDurationMillis","guardCancelsActive","timingMode","flashAtFraction","windowOpenFraction","windowCloseFraction");
        if(!json.keySet().containsAll(keys)||!allowed.containsAll(json.keySet()))throw new IllegalArgumentException("Invalid guard cue fields");
        return new GuardCueSettings(bool(json,"enabled"),integer(json,"flashLeadTicks"),integer(json,"inputWindowTicks"),
                integer(json,"flashDurationMillis"),bool(json,"guardCancelsActive"),json.has("timingMode")?text(json,"timingMode"):"windup_fraction",
                json.has("flashAtFraction")?number(json,"flashAtFraction"):.2,json.has("windowOpenFraction")?number(json,"windowOpenFraction"):.6,
                json.has("windowCloseFraction")?number(json,"windowCloseFraction"):Math.max(.8,json.has("windowOpenFraction")?number(json,"windowOpenFraction"):.6));
    }
    private static String text(JsonObject json,String field){var value=json.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected string "+field);return value.getAsString();}
    private static double number(JsonObject json,String field){var value=json.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected number "+field);return value.getAsDouble();}
    private static int integer(JsonObject json,String field){
        var value=json.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected numeric "+field);
        return value.getAsBigDecimal().intValueExact();
    }
    private static boolean bool(JsonObject json,String field){
        var value=json.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Expected boolean "+field);
        return value.getAsBoolean();
    }
}

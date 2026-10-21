package org.fsrpg.creature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Project-calibrated timing, not a claim of unpublished Zenless Zone Zero frame values. */
public record GuardCueSettings(boolean enabled, int flashLeadTicks, int inputWindowTicks,
                               int flashDurationMillis, boolean guardCancelsActive,
                               String timingMode,double flashAtFraction,double windowOpenFraction) {
    /** Explicit old Java fixtures/configurations can still request fixed tick timing. */
    public GuardCueSettings(boolean enabled,int flashLeadTicks,int inputWindowTicks,int flashDurationMillis,boolean guardCancelsActive){
        this(enabled,flashLeadTicks,inputWindowTicks,flashDurationMillis,guardCancelsActive,"fixed_ticks",.2,.6);
    }
    public GuardCueSettings {
        if(flashLeadTicks<1 || flashLeadTicks>60 || inputWindowTicks<1 || inputWindowTicks>flashLeadTicks
                || flashDurationMillis<16 || flashDurationMillis>500)
            throw new IllegalArgumentException("Invalid guard cue timing: 1 <= inputWindowTicks <= flashLeadTicks <= 60, flash 16..500 ms");
        if(timingMode==null||!Set.of("windup_fraction","fixed_ticks").contains(timingMode)||!Double.isFinite(flashAtFraction)||!Double.isFinite(windowOpenFraction)
                ||flashAtFraction<0||flashAtFraction>=1||windowOpenFraction<flashAtFraction||windowOpenFraction>1)
            throw new IllegalArgumentException("Expected finite 0 <= flashAtFraction <= windowOpenFraction <= 1 and flashAtFraction < 1");
    }
    public static GuardCueSettings defaults(){return new GuardCueSettings(true,7,7,120,true,"windup_fraction",.2,.6);}
    public int windupTicks(int authored){return !enabled?authored:timingMode.equals("windup_fraction")?Math.max(1,authored):Math.max(authored,flashLeadTicks);}
    public GuardCueWindow window(long started,int windup){
        long contact=started+windupTicks(windup);
        if(timingMode.equals("windup_fraction")){
            long duration=contact-started;
            long flash=java.math.BigDecimal.valueOf(flashAtFraction).multiply(java.math.BigDecimal.valueOf(duration)).setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
            long open=java.math.BigDecimal.valueOf(windowOpenFraction).multiply(java.math.BigDecimal.valueOf(duration)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
            return new GuardCueWindow(started,started+flash,started+open,contact,contact);
        }
        return new GuardCueWindow(started,contact-flashLeadTicks,contact-inputWindowTicks,contact,contact);
    }
    public static GuardCueSettings read(Path file)throws IOException {
        if(!Files.exists(file)){
            try(var input=GuardCueSettings.class.getResourceAsStream("/defaults/guard_cues.json")){
                if(input==null)throw new IOException("Missing guard cue defaults");
                Files.createDirectories(file.toAbsolutePath().getParent());
                try{Files.copy(input,file);}catch(java.nio.file.FileAlreadyExistsException concurrent){/* Preserve the existing file. */}
            }
        }
        if(Files.size(file)>64*1024)throw new IOException("guard_cues.json exceeds 64 KiB");
        try{return parse(Files.readString(file,StandardCharsets.UTF_8));}
        catch(RuntimeException invalid){throw new IOException("Invalid guard_cues.json: "+invalid.getMessage(),invalid);}
    }
    public static GuardCueSettings parse(String text){
        JsonObject json=JsonParser.parseString(text).getAsJsonObject();
        var keys=Set.of("enabled","flashLeadTicks","inputWindowTicks","flashDurationMillis","guardCancelsActive");
        var allowed=Set.of("enabled","flashLeadTicks","inputWindowTicks","flashDurationMillis","guardCancelsActive","timingMode","flashAtFraction","windowOpenFraction");
        if(!json.keySet().containsAll(keys)||!allowed.containsAll(json.keySet()))throw new IllegalArgumentException("Invalid guard cue fields");
        return new GuardCueSettings(bool(json,"enabled"),integer(json,"flashLeadTicks"),integer(json,"inputWindowTicks"),
                integer(json,"flashDurationMillis"),bool(json,"guardCancelsActive"),json.has("timingMode")?text(json,"timingMode"):"windup_fraction",
                json.has("flashAtFraction")?number(json,"flashAtFraction"):.2,json.has("windowOpenFraction")?number(json,"windowOpenFraction"):.6);
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

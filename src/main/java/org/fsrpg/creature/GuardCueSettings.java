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
                               int flashDurationMillis, boolean guardCancelsActive) {
    public GuardCueSettings {
        if(flashLeadTicks<1 || flashLeadTicks>60 || inputWindowTicks<1 || inputWindowTicks>flashLeadTicks
                || flashDurationMillis<16 || flashDurationMillis>500)
            throw new IllegalArgumentException("Invalid guard cue timing: 1 <= inputWindowTicks <= flashLeadTicks <= 60, flash 16..500 ms");
    }
    public static GuardCueSettings defaults(){return new GuardCueSettings(true,6,6,120,true);}
    public int windupTicks(int authored){return enabled?Math.max(authored,flashLeadTicks):authored;}
    public GuardCueWindow window(long started,int windup){
        long contact=started+windupTicks(windup);
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
        if(!json.keySet().equals(keys))throw new IllegalArgumentException("Guard cue fields must be "+keys);
        return new GuardCueSettings(bool(json,"enabled"),integer(json,"flashLeadTicks"),integer(json,"inputWindowTicks"),
                integer(json,"flashDurationMillis"),bool(json,"guardCancelsActive"));
    }
    private static int integer(JsonObject json,String field){
        var value=json.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected numeric "+field);
        return value.getAsBigDecimal().intValueExact();
    }
    private static boolean bool(JsonObject json,String field){
        var value=json.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Expected boolean "+field);
        return value.getAsBoolean();
    }
}

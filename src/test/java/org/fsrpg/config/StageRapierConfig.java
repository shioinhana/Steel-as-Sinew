package org.fsrpg.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Runs the real configuration migration on a copy before deploying any user file. */
public final class StageRapierConfig {
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void retained(JsonElement old,JsonElement next,String path){
        if(old.isJsonObject()){
            if(!next.isJsonObject())throw new AssertionError("Changed object "+path);
            for(var entry:old.getAsJsonObject().entrySet()){
                if(!next.getAsJsonObject().has(entry.getKey()))throw new AssertionError("Removed "+path+"."+entry.getKey());
                retained(entry.getValue(),next.getAsJsonObject().get(entry.getKey()),path+"."+entry.getKey());
            }
        }else if(!old.equals(next))throw new AssertionError("Changed established value "+path);
    }
    public static void main(String[] args)throws Exception{
        Path directory=Path.of(args[0]),report=Path.of(args[1]);var before=new LinkedHashMap<String,String>();
        try(var files=Files.walk(directory)){for(var file:files.filter(Files::isRegularFile).toList())before.put(directory.relativize(file).toString(),sha(Files.readAllBytes(file)));}
        Path combat=directory.resolve("combat.json");byte[] original=Files.readAllBytes(combat);
        var config=new RpgConfig(directory);config.reload();byte[] updated=Files.readAllBytes(combat);
        retained(JsonParser.parseString(new String(original,java.nio.charset.StandardCharsets.UTF_8)),JsonParser.parseString(new String(updated,java.nio.charset.StandardCharsets.UTF_8)),"combat");
        for(var entry:before.entrySet())if(!entry.getKey().equals("combat.json")&&!entry.getValue().equals(sha(Files.readAllBytes(directory.resolve(entry.getKey())))))throw new AssertionError("Unexpected migration change "+entry.getKey());
        var result=new com.google.gson.JsonObject();result.addProperty("passed",true);result.addProperty("directory",directory.toAbsolutePath().toString());result.addProperty("oldCombatSha256",sha(original));result.addProperty("newCombatSha256",sha(updated));result.addProperty("existingFieldsPreserved",true);result.addProperty("range",config.current().combat().rapierTargetLock().range());result.addProperty("combatChanged",!java.util.Arrays.equals(original,updated));
        RpgConfig.atomicWrite(report,RpgConfig.JSON.toJson(result));System.out.println(RpgConfig.JSON.toJson(result));
    }
}

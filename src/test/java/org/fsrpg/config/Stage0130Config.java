package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Run the tested JAR's production seeding on a copy, preserving all preexisting task definitions. */
public final class Stage0130Config {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]).toAbsolutePath().normalize(), report=Path.of(args[1]).toAbsolutePath().normalize();
        if (!directory.toString().contains("verification") || !directory.getFileName().toString().equals("fsrpg")) throw new IllegalArgumentException("isolated verification/.../fsrpg required");
        Map<String,String> before=hashes(directory);
        JsonArray original=JsonParser.parseString(Files.readString(directory.resolve("quests.json"))).getAsJsonArray();
        RpgConfig config=new RpgConfig(directory); var definitions=config.reload();
        QuestSettingsConfig.load(directory).validate(definitions.quests());
        JsonArray afterQuests=JsonParser.parseString(Files.readString(directory.resolve("quests.json"))).getAsJsonArray();
        for (JsonElement prior:original) {
            String id=prior.getAsJsonObject().get("id").getAsString(); JsonElement preserved=null;
            for(JsonElement current:afterQuests)if(current.getAsJsonObject().get("id").getAsString().equals(id))preserved=current;
            if(!prior.equals(preserved))throw new AssertionError("existing task changed: "+id);
        }
        Map<String,String> after=hashes(directory); config.reload();
        if(!after.equals(hashes(directory)))throw new AssertionError("second seeding changed files");
        for(var prior:before.entrySet())if(!prior.getKey().equals("quests.json") && !Objects.equals(prior.getValue(),after.get(prior.getKey())))
            throw new AssertionError("unrelated original configuration changed: "+prior.getKey());
        Path jar=Path.of(RpgConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar"))throw new AssertionError("must execute against packaged JAR");
        JsonObject output=new JsonObject(); output.addProperty("passed",true);output.addProperty("version","0.13.0");
        output.addProperty("directory",directory.toString());output.addProperty("modJarSha256",sha(jar));
        output.addProperty("originalTasksPreserved",true);output.addProperty("secondReloadIdempotent",true);
        output.addProperty("taskCount",definitions.quests().definitions().size());
        output.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));output.add("afterSha256",RpgConfig.JSON.toJsonTree(after));
        RpgConfig.atomicWrite(report,RpgConfig.JSON.toJson(output));System.out.println("Stage0130Config passed: "+before.size()+" -> "+after.size()+" files");
    }
    private static Map<String,String> hashes(Path directory)throws Exception {
        Map<String,String> values=new TreeMap<>();try(var files=Files.walk(directory)){for(Path file:files.filter(Files::isRegularFile).toList()){
            if(Files.isSymbolicLink(file))throw new IllegalArgumentException("symlink rejected");
            values.put(directory.relativize(file).toString().replace('\\','/'),sha(file));
        }}return values;
    }
    private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}

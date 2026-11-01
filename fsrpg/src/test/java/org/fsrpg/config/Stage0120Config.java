package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs the actual packaged production migration on an isolated copy. Never points at a live config. */
public final class Stage0120Config {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Expected isolated verification/fsrpg directory and report path");
        Path directory=Path.of(args[0]).toAbsolutePath().normalize(),report=Path.of(args[1]).toAbsolutePath().normalize();
        if(!directory.getFileName().toString().equals("fsrpg")||!hasComponent(directory,"verification")||!hasComponent(report,"verification"))throw new IllegalArgumentException("Use an isolated project verification/fsrpg candidate");
        rejectLinks(directory);Map<String,String> before=hashes(directory);
        Path jar=Path.of(RpgConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isRegularFile(jar)||!jar.getFileName().toString().equals("fsrpg-neoforge-0.12.0.jar"))throw new IllegalArgumentException("Production RpgConfig must load from the exact tested 0.12.0 JAR, placed before loose classes on the classpath");
        String jarSha=sha(Files.readAllBytes(jar));
        var definitions=new RpgConfig(directory).reload();
        JsonObject marker=JsonParser.parseString(Files.readString(directory.resolve(SwordCombatMigration.MARKER))).getAsJsonObject();
        if(!marker.get("customFieldsPreserved").getAsBoolean())throw new IllegalStateException("Migration did not preserve custom fields");
        JsonObject combat=JsonParser.parseString(Files.readString(directory.resolve("combat.json"))).getAsJsonObject();
        boolean enabled=combat.getAsJsonObject("swordCombat").get("enabled").getAsBoolean();
        // Seed only brand-new model names. Existing external files are owned by the user.
        for(String model:List.of("rapier_fullbody.fsvoxel","rapier_fullbody_fp.fsvoxel")){
            Path destination=directory.resolve("models").resolve(model);
            if(!Files.exists(destination)){Files.createDirectories(destination.getParent());try(var source=RpgConfig.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/"+model)){if(source==null)throw new IllegalStateException("Missing packaged model "+model);Files.copy(source,destination);}}
        }
        Map<String,String> after=hashes(directory);new RpgConfig(directory).reload();
        if(!after.equals(hashes(directory)))throw new AssertionError("Second production reload changed the staged result");
        for(String path:before.keySet())if(!after.containsKey(path))throw new AssertionError("Migration deleted a preexisting file: "+path);
        if(enabled&&!definitions.skills().get("sword_left").model().equals("rapier_fullbody.fsvoxel"))throw new AssertionError("Enabled stock full-body mode did not select its new animation model");
        JsonObject output=new JsonObject();output.addProperty("passed",true);output.addProperty("version","0.12.0");output.addProperty("directory",directory.toString());
        output.addProperty("modJar",jar.toString());output.addProperty("modJarSha256",jarSha);output.addProperty("fullBodyEnabled",enabled);output.addProperty("secondReloadIdempotent",true);output.addProperty("preexistingFilesRetained",true);
        output.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));output.add("afterSha256",RpgConfig.JSON.toJsonTree(after));output.add("migration",marker);
        output.add("rapierCombo",RpgConfig.JSON.toJsonTree(definitions.styles().get("rapier").leftCombo().stream().map(s->s.skillId()).toList()));
        Files.createDirectories(report.getParent());RpgConfig.atomicWrite(report,RpgConfig.JSON.toJson(output));
        System.out.println("Stage0120Config passed: "+before.size()+" -> "+after.size()+" files; fullBodyEnabled="+enabled+"; same-JAR SHA-256 "+jarSha);
    }
    private static boolean hasComponent(Path path,String name){for(Path part:path)if(part.toString().equals(name))return true;return false;}
    private static void rejectLinks(Path root)throws Exception {for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("Symbolic path is not allowed: "+p);try(var entries=Files.walk(root)){for(Path p:entries.toList())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("Symbolic candidate entry: "+p);}}
    private static Map<String,String> hashes(Path directory)throws Exception{Map<String,String> result=new TreeMap<>();try(var files=Files.walk(directory)){for(Path p:files.filter(Files::isRegularFile).toList())result.put(directory.relativize(p).toString().replace('\\','/'),sha(Files.readAllBytes(p)));}return result;}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

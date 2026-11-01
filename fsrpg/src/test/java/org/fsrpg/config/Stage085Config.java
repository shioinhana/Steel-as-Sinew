package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Prepare an isolated full configuration copy; deployment independently verifies its exact allowlist. */
public final class Stage085Config {
    public static void main(String[] args)throws Exception {
        Path dir=Path.of(args[0]).toAbsolutePath().normalize(),report=Path.of(args[1]).toAbsolutePath().normalize();
        if(!dir.toString().contains("verification")||!dir.getFileName().toString().equals("fsrpg"))throw new IllegalArgumentException("Expected an isolated verification/fsrpg candidate");
        Map<String,String> before=hashes(dir);JsonObject previous=new JsonObject();
        for(String name:List.of("skills.json","styles.json","skill_trees.json"))previous.add(name,read(dir.resolve(name)));
        var definitions=new RpgConfig(dir).reload();JsonObject marker=read(dir.resolve(RapierFencingMigration.MARKER)).getAsJsonObject();
        Path manifest=Path.of("docs/rapier-fencing-0.8.5-manifest.json").toAbsolutePath().normalize();
        JsonObject assets=read(manifest).getAsJsonObject();
        if(!"0.8.5".equals(assets.get("version").getAsString())||assets.getAsJsonArray("assets").size()!=2)throw new IllegalStateException("Expected both reviewed 0.8.5 rapier models");
        for(var value:assets.getAsJsonArray("assets")) {
            var asset=value.getAsJsonObject();byte[] bytes;
            try(var in=Stage085Config.class.getResourceAsStream("/"+asset.get("entry").getAsString())){bytes=Objects.requireNonNull(in).readAllBytes();}
            if(!RapierFencingMigration.digest(bytes).equals(asset.get("afterSha256").getAsString()))throw new IllegalStateException("Bundled model differs from reviewed 0.8.5 manifest");
        }
        JsonObject current=new JsonObject();for(var entry:previous.entrySet())current.add(entry.getKey(),read(dir.resolve(entry.getKey())));
        JsonObject output=new JsonObject();output.addProperty("passed",true);output.addProperty("version","0.8.5");output.addProperty("directory",dir.toString());
        output.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));output.add("afterSha256",RpgConfig.JSON.toJsonTree(hashes(dir)));output.add("previous",previous);output.add("current",current);
        output.add("skillUpgrade",marker.get("skillUpgrade").deepCopy());output.add("modelUpgrade",marker.get("modelUpgrade").deepCopy());
        output.addProperty("migrationPlanSha256",digest(dir.resolve(RapierFencingMigration.BACKUP).resolve("plan.json")));
        output.addProperty("migrationMarkerSha256",digest(dir.resolve(RapierFencingMigration.MARKER)));
        output.addProperty("assetManifestSha256",digest(manifest));
        output.add("rapierCombo",RpgConfig.JSON.toJsonTree(definitions.styles().get("rapier").leftCombo().stream().map(s->s.skillId()).toList()));
        Files.createDirectories(report.getParent());RpgConfig.atomicWrite(report,RpgConfig.JSON.toJson(output));
        System.out.println("Staged 0.8.5: "+definitions.styles().get("rapier").leftCombo().size()+" rapier stages; "+before.size()+" -> "+hashes(dir).size()+" files; "+dir);
    }
    private static JsonElement read(Path file)throws Exception{return JsonParser.parseString(Files.readString(file));}
    private static String digest(Path file)throws Exception{return RapierFencingMigration.digest(Files.readAllBytes(file));}
    private static Map<String,String> hashes(Path dir)throws Exception{Map<String,String> result=new TreeMap<>();try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).toList())result.put(dir.relativize(file).toString().replace('\\','/'),digest(file));}return result;}
}

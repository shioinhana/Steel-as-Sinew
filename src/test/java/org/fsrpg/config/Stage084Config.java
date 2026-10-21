package org.fsrpg.config;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import org.fsrpg.creature.GuardCueSettings;

/** Prepare an isolated copy; deployment independently checks every changed field before copying it. */
public final class Stage084Config {
    public static void main(String[] args)throws Exception{
        Path dir=Path.of(args[0]).toAbsolutePath().normalize(),report=Path.of(args[1]).toAbsolutePath().normalize();
        if(!dir.toString().contains("verification")||!dir.getFileName().toString().equals("fsrpg"))throw new IllegalArgumentException("Expected an isolated verification/fsrpg candidate");
        Map<String,String> before=hashes(dir);Map<String,JsonElement> old=new LinkedHashMap<>();
        for(String name:List.of("combat.json","styles.json","guard_cues.json"))old.put(name,JsonParser.parseString(Files.readString(dir.resolve(name))));
        var d=new RpgConfig(dir).reload();var cue=GuardCueSettings.read(dir.resolve("guard_cues.json"));
        JsonArray modelUpgrade=upgradeStockModels(dir);
        JsonObject r=new JsonObject();r.addProperty("passed",true);r.addProperty("version","0.8.4");r.addProperty("directory",dir.toString());
        r.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));r.add("afterSha256",RpgConfig.JSON.toJsonTree(hashes(dir)));r.add("previous",RpgConfig.JSON.toJsonTree(old));
        JsonObject current=new JsonObject();for(String name:old.keySet())current.add(name,JsonParser.parseString(Files.readString(dir.resolve(name))));r.add("current",current);
        r.addProperty("perfectGuardTicks",d.combat().perfectGuardTicks());r.addProperty("contactWindowTicks",d.combat().weaponClash().contactWindowTicks());r.add("guardCue",RpgConfig.JSON.toJsonTree(cue));
        r.add("modelUpgrade",modelUpgrade);
        Files.createDirectories(report.getParent());RpgConfig.atomicWrite(report,RpgConfig.JSON.toJson(r));
        System.out.println("Staged 0.8.4: perfect guard "+d.combat().perfectGuardTicks()+" ticks; clash "+d.combat().weaponClash().contactWindowTicks()+" ticks; "+dir);
    }
    private static JsonArray upgradeStockModels(Path dir)throws Exception{
        JsonArray upgrades=new JsonArray();
        Path manifest=Path.of("docs/rapier-motion-0.8.4-manifest.json").toAbsolutePath().normalize();
        var assets=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject().getAsJsonArray("assets");
        if(assets==null||assets.size()!=2)throw new IllegalStateException("Expected exactly two reviewed rapier source assets");
        for(var value:assets){
            var asset=value.getAsJsonObject();String name=asset.get("relativePath").getAsString();
            if(!Set.of("models/legacy_rapier.fsvoxel","models/legacy_rapier_fp.fsvoxel").contains(name))throw new IllegalStateException("Unexpected model upgrade path");
            Path file=dir.resolve(name);String old=Files.isRegularFile(file)?digest(Files.readAllBytes(file)):null;
            byte[] next;try(var in=Stage084Config.class.getResourceAsStream("/"+asset.get("entry").getAsString())){next=Objects.requireNonNull(in).readAllBytes();}
            String expected=asset.get("afterSha256").getAsString();if(!digest(next).equals(expected))throw new IllegalStateException("Bundled rapier asset differs from reviewed source");
            boolean stock=Objects.equals(old,asset.get("beforeSha256").getAsString()),current=Objects.equals(old,expected);
            if(stock&&!current)Files.write(file,next);
            JsonObject row=new JsonObject();row.addProperty("relativePath",name);row.add("oldSha256",old==null?JsonNull.INSTANCE:new JsonPrimitive(old));
            row.add("newSha256",old==null?JsonNull.INSTANCE:new JsonPrimitive(stock?expected:old));row.addProperty("applied",stock&&!current);row.addProperty("customPreserved",!stock&&!current);upgrades.add(row);
        }
        return upgrades;
    }
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,String> hashes(Path dir)throws Exception{
        Map<String,String> out=new TreeMap<>();try(var files=Files.walk(dir)){for(Path p:files.filter(Files::isRegularFile).toList())out.put(dir.relativize(p).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}return out;
    }
}

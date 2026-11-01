package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Isolated candidate for the ordinary field-preserving runtime migration, never a live instance edit. */
public final class Stage0100Config {
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]).toAbsolutePath().normalize(),report=Path.of(args[1]).toAbsolutePath().normalize();
        if(!directory.toString().contains("verification")||!directory.getFileName().toString().equals("fsrpg"))throw new IllegalArgumentException("Expected isolated verification/fsrpg candidate");
        Map<String,String> before=hashes(directory);var definitions=new RpgConfig(directory).reload();
        JsonObject output=new JsonObject();output.addProperty("passed",true);output.addProperty("version","0.10.0");output.addProperty("directory",directory.toString());
        output.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));output.add("afterSha256",RpgConfig.JSON.toJsonTree(hashes(directory)));
        output.add("migration",JsonParser.parseString(Files.readString(directory.resolve(RapierRenewalMigration.MARKER))));
        output.add("rapierCombo",RpgConfig.JSON.toJsonTree(definitions.styles().get("rapier").leftCombo().stream().map(s->s.skillId()).toList()));
        Files.createDirectories(report.getParent());RpgConfig.atomicWrite(report,RpgConfig.JSON.toJson(output));
        System.out.println("Staged 0.10.0 rapier renewal: "+before.size()+" -> "+hashes(directory).size()+" files; "+directory);
    }
    private static Map<String,String> hashes(Path directory)throws Exception {
        Map<String,String> result=new TreeMap<>();try(var files=Files.walk(directory)) {for(Path file:files.filter(Files::isRegularFile).toList())result.put(directory.relativize(file).toString().replace('\\','/'),RapierFencingMigration.digest(Files.readAllBytes(file)));}return result;
    }
}

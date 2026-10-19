package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.fsrpg.combat.BurstDashRules;

/** Introduce absent Shift movement and spear swipe multiplier fields, preserving the exact entry file. */
public final class BurstDashMigration {
    public static final String MARKER="burst-dash-migration-v1.json",BACKUP="burst-dash-backup-v1";
    private final Path directory;private final byte[] original;
    private BurstDashMigration(Path directory,byte[] original){this.directory=directory;this.original=original;}
    public static BurstDashMigration capture(Path directory)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return null;
        Path backup=directory.resolve(BACKUP).resolve("combat.json"),source=directory.resolve("combat.json");
        byte[] original=Files.exists(backup)?bytes(backup):Files.exists(source)?bytes(source):null;
        if(original!=null)JsonParser.parseString(new String(original,StandardCharsets.UTF_8)).getAsJsonObject();
        return new BurstDashMigration(directory,original);
    }
    public void apply()throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        JsonObject before=read("combat.json").getAsJsonObject(),candidate=before.deepCopy();
        if(!candidate.has("spearSwipeKnockbackMultiplier"))candidate.addProperty("spearSwipeKnockbackMultiplier",.5);
        JsonObject defaults=RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()).getAsJsonObject();
        if(!candidate.has("burstDash"))candidate.add("burstDash",defaults);
        else if(candidate.get("burstDash").isJsonObject()) {
            var existing=candidate.getAsJsonObject("burstDash");
            for(var field:defaults.entrySet())if(!existing.has(field.getKey()))existing.add(field.getKey(),field.getValue());
        }
        RpgConfig.parse(candidate,read("skills.json"),read("quests.json"),read("weapons.json"),read("styles.json"),read("collections.json"));
        boolean changed=!before.equals(candidate);
        if(changed&&original!=null) {
            Path backup=directory.resolve(BACKUP);Files.createDirectories(backup);
            if(!Files.exists(backup.resolve("combat.json")))Files.write(backup.resolve("combat.json"),original,StandardOpenOption.CREATE_NEW);
        }
        if(changed)RpgConfig.atomicWrite(directory.resolve("combat.json"),RpgConfig.JSON.toJson(candidate));
        JsonObject marker=new JsonObject();marker.addProperty("schemaVersion",1);marker.addProperty("releaseVersion","0.7.8");
        marker.addProperty("onlyMissingFieldsAdded",true);marker.addProperty("allOtherFieldsPreserved",true);
        marker.addProperty("altDodgeTuningPreserved",true);marker.addProperty("existingCombatFile",original!=null);
        if(original!=null)try{marker.addProperty("originalCombatSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }
    private JsonElement read(String name)throws IOException{return JsonParser.parseString(new String(bytes(directory.resolve(name)),StandardCharsets.UTF_8));}
    private static byte[] bytes(Path file)throws IOException {
        if(Files.size(file)>512*1024)throw new IOException(file.getFileName()+" exceeds 512 KiB");
        return Files.readAllBytes(file);
    }
}

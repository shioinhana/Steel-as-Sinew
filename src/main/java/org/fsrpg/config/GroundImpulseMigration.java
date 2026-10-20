package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.fsrpg.combat.BurstDashRules;

/** Add only initialSpeed; retain every custom legacy distance/duration/run-speed field and the exact entry bytes. */
public final class GroundImpulseMigration {
    public static final String MARKER="ground-impulse-migration-v1.json",BACKUP="ground-impulse-backup-v1";
    private final Path directory;private final byte[] original;
    private GroundImpulseMigration(Path directory,byte[] original){this.directory=directory;this.original=original;}
    public static GroundImpulseMigration capture(Path directory)throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return null;
        Path backup=directory.resolve(BACKUP).resolve("combat.json"),source=directory.resolve("combat.json");
        byte[] original=Files.exists(backup)?bytes(backup):Files.exists(source)?bytes(source):null;
        if(original!=null)JsonParser.parseString(new String(original,StandardCharsets.UTF_8)).getAsJsonObject();
        return new GroundImpulseMigration(directory,original);
    }
    public void apply()throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        JsonObject before=read("combat.json").getAsJsonObject(),candidate=before.deepCopy();
        if(!candidate.has("burstDash"))candidate.add("burstDash",RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()));
        else if(candidate.get("burstDash").isJsonObject()) {
            var burst=candidate.getAsJsonObject("burstDash");
            if(!burst.has("initialSpeed")) {
                double distance=burst.has("distance")&&burst.get("distance").isJsonPrimitive()&&burst.get("distance").getAsJsonPrimitive().isNumber()
                        ?burst.get("distance").getAsDouble():BurstDashRules.defaults().distance();
                burst.addProperty("initialSpeed",BurstDashRules.legacyInitialSpeed(distance));
            }
        }
        RpgConfig.parse(candidate,read("skills.json"),read("quests.json"),read("weapons.json"),read("styles.json"),read("collections.json"));
        boolean changed=!before.equals(candidate);
        if(changed&&original!=null){Path backup=directory.resolve(BACKUP);Files.createDirectories(backup);
            if(!Files.exists(backup.resolve("combat.json")))Files.write(backup.resolve("combat.json"),original,StandardOpenOption.CREATE_NEW);}
        if(changed)RpgConfig.atomicWrite(directory.resolve("combat.json"),RpgConfig.JSON.toJson(candidate));
        JsonObject marker=new JsonObject();marker.addProperty("schemaVersion",1);marker.addProperty("releaseVersion","0.7.9");
        marker.addProperty("onlyMissingInitialSpeedAdded",true);marker.addProperty("legacyFieldsPreserved",true);
        marker.addProperty("groundedFreshPressOnly",true);marker.addProperty("altDodgeTuningPreserved",true);marker.addProperty("existingCombatFile",original!=null);
        if(original!=null)try{marker.addProperty("originalCombatSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }
    private JsonElement read(String name)throws IOException{return JsonParser.parseString(new String(bytes(directory.resolve(name)),StandardCharsets.UTF_8));}
    private static byte[] bytes(Path path)throws IOException{if(Files.size(path)>512*1024)throw new IOException(path.getFileName()+" exceeds 512 KiB");return Files.readAllBytes(path);}
}

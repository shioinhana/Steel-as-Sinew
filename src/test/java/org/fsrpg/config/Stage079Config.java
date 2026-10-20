package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.fsrpg.combat.BurstDashRules;
import org.fsrpg.client.WeaponInspections;
import org.fsrpg.creature.CreatureCombatCatalog;
import org.fsrpg.creature.GuardCueSettings;

/** Exact official-instance field migration and cosmetic catalogue seed. */
public final class Stage079Config {
    private static final Path OFFICIAL=Path.of("C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/config/fsrpg");
    private static final Set<String> NEW=Set.of(GroundImpulseMigration.MARKER,GroundImpulseMigration.BACKUP+"/combat.json",WeaponInspections.FILE);
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Expected staged fsrpg directory and output report");
        Path source=OFFICIAL.toAbsolutePath().normalize(),dir=Path.of(args[0]).toAbsolutePath().normalize();
        Path evidence=Path.of("verification/inspection-impulse-20261020").toAbsolutePath().normalize(),output=Path.of(args[1]).toAbsolutePath().normalize();
        if(!dir.startsWith(evidence)||!output.startsWith(evidence)||dir.equals(source)||dir.startsWith(source))
            throw new IllegalArgumentException("Stage/report must remain inside dedicated release evidence");
        Map<String,String> before=hashes(source);
        if(before.size()!=51||before.keySet().stream().anyMatch(NEW::contains))throw new IllegalStateException("Expected installed pre-0.7.9 51-file configuration");
        if(!Files.exists(dir)||hashes(dir).isEmpty())copyTree(source,dir);
        if(!before.equals(hashes(dir))||!before.equals(hashes(source)))throw new AssertionError("Stage is not an exact stable copy of official config");
        byte[] original=Files.readAllBytes(dir.resolve("combat.json"));
        JsonObject expected=read(dir.resolve("combat.json")).getAsJsonObject().deepCopy();
        if(!expected.has("burstDash"))expected.add("burstDash",RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()));
        else if(expected.get("burstDash").isJsonObject()&&!expected.getAsJsonObject("burstDash").has("initialSpeed")){
            var burst=expected.getAsJsonObject("burstDash");burst.addProperty("initialSpeed",BurstDashRules.legacyInitialSpeed(burst.has("distance")?burst.get("distance").getAsDouble():3));
        }
        var config=new RpgConfig(dir);var definitions=config.reload();
        WeaponInspections.reload(dir);GuardCueSettings.read(dir.resolve("guard_cues.json"));CreatureCombatCatalog.read(dir.resolve("creature_combat.json"));
        if(!expected.equals(read(dir.resolve("combat.json"))))throw new AssertionError("Migration changed fields outside absent initialSpeed field");
        Map<String,String> after=hashes(dir);List<String> changed=new ArrayList<>(),added=new ArrayList<>();
        for(var file:before.entrySet()){
            if(!after.containsKey(file.getKey()))throw new AssertionError("Removed existing config: "+file.getKey());
            if(!file.getValue().equals(after.get(file.getKey()))){
                if(!file.getKey().equals("combat.json"))throw new AssertionError("Changed unrelated config: "+file.getKey());
                changed.add(file.getKey());
            }
        }
        for(String name:after.keySet())if(!before.containsKey(name)){
            if(!NEW.contains(name))throw new AssertionError("Unexpected new config: "+name);added.add(name);
        }
        if(after.size()!=54||!Set.copyOf(added).equals(NEW))throw new AssertionError("Expected exactly inspection seed, one marker and exact combat backup: 51 to 54 files");
        if(!Arrays.equals(original,Files.readAllBytes(dir.resolve(GroundImpulseMigration.BACKUP).resolve("combat.json"))))throw new AssertionError("Original combat backup differs byte for byte");
        config.reload();WeaponInspections.reload(dir);if(!after.equals(hashes(dir)))throw new AssertionError("Second reload changed staged config");
        if(!before.equals(hashes(source)))throw new AssertionError("Official source changed while staging");

        JsonObject report=new JsonObject();report.addProperty("passed",true);report.addProperty("version","0.7.9");
        report.addProperty("sourceDirectory",source.toString());report.addProperty("stageDirectory",dir.toString());
        report.addProperty("sourceCopiedByteExactly",true);report.addProperty("officialSourceUntouched",true);
        report.addProperty("onlyMissingInitialSpeedAndInspectionSeedAdded",true);report.addProperty("allOtherExistingFieldsAndFilesPreserved",true);
        report.addProperty("exactOriginalBackups",true);report.addProperty("secondReloadIdempotent",true);report.addProperty("altDodgeTuningPreserved",true);
        report.add("burstDash",RpgConfig.JSON.toJsonTree(definitions.combat().burstDash()));
        report.addProperty("spearSwipeKnockbackMultiplier",definitions.combat().spearSwipeKnockbackMultiplier());
        report.addProperty("inspectionVariants",java.util.stream.Stream.of("rapier","dual_blade","spear","bow_rapid").mapToInt(style->WeaponInspections.actions(style).size()).sum());
        report.add("staminaCombatRules",RpgConfig.JSON.toJsonTree(definitions.combat().staminaCombatRules()));
        report.addProperty("baseMaxStamina",definitions.combat().maxStamina());report.addProperty("baseStaminaRegenPerSecond",definitions.combat().staminaRegenPerSecond());
        report.add("staminaLevelScaling",RpgConfig.JSON.toJsonTree(definitions.combat().staminaLevelScaling()));
        report.addProperty("existingFileCount",before.size());report.addProperty("stagedFileCount",after.size());
        report.add("changedExistingFiles",RpgConfig.JSON.toJsonTree(changed));report.add("newFiles",RpgConfig.JSON.toJsonTree(added));
        report.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));report.add("afterSha256",RpgConfig.JSON.toJsonTree(after));
        Files.createDirectories(output.getParent());RpgConfig.atomicWrite(output,RpgConfig.JSON.toJson(report));
        System.out.println("Stage079Config passed: 51 original files; 54 staged files; only absent initialSpeed + inspection seed; exact original; idempotent reload");
    }
    private static JsonElement read(Path file)throws Exception{return JsonParser.parseString(Files.readString(file));}
    private static Map<String,String> hashes(Path directory)throws Exception{
        var result=new TreeMap<String,String>();try(var paths=Files.walk(directory)){
            for(Path file:paths.filter(Files::isRegularFile).toList())result.put(directory.relativize(file).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }return result;
    }
    private static void copyTree(Path source,Path dest)throws Exception{
        Files.createDirectories(dest);try(var paths=Files.walk(source)){for(Path file:paths.toList()){
            if(Files.isSymbolicLink(file))throw new IllegalStateException("Cannot stage symbolic config entry");
            Path target=dest.resolve(source.relativize(file)).normalize();if(!target.startsWith(dest))throw new IllegalStateException("Stage escaped destination");
            if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target,StandardCopyOption.COPY_ATTRIBUTES);
        }}
    }
}

package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.fsrpg.combat.StaminaLevelScaling;
import org.fsrpg.creature.CreatureCombatCatalog;

/** Stage a read-only official-instance copy, then prove this release's exact field and file allowlist. */
public final class Stage074Config {
    private static final Path OFFICIAL=Path.of("C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/config/fsrpg");
    private static final Set<String> ALLOWED_NEW=Set.of(StaminaLevelMigration.MARKER,
            StaminaLevelMigration.BACKUP+"/combat.json","creature_combat.json");

    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Expected staged fsrpg directory and output report");
        Path source=OFFICIAL.toAbsolutePath().normalize(),dir=Path.of(args[0]).toAbsolutePath().normalize();
        Path verification=Path.of("verification/level-creature-combat-20261016").toAbsolutePath().normalize();
        Path output=Path.of(args[1]).toAbsolutePath().normalize();
        if(!dir.startsWith(verification)||!output.startsWith(verification)||dir.equals(source)||dir.startsWith(source))
            throw new IllegalArgumentException("Stage and report must stay inside the dedicated release evidence directory");
        if(!Files.isDirectory(source))throw new IllegalStateException("Official config directory does not exist");
        Map<String,String> before=hashes(source);
        if(before.containsKey(StaminaLevelMigration.MARKER)||before.containsKey(StaminaLevelMigration.BACKUP+"/combat.json"))
            throw new IllegalStateException("This stage expects the installed pre-0.7.4 configuration");
        if(!Files.exists(dir)||hashes(dir).isEmpty())copyTree(source,dir);
        if(!before.equals(hashes(dir))||!before.equals(hashes(source)))throw new AssertionError("Stage is not an exact stable copy of official config");
        byte[] original=Files.readAllBytes(dir.resolve("combat.json"));
        JsonObject expected=read(dir.resolve("combat.json")).getAsJsonObject().deepCopy();
        expected.addProperty("maxStamina",50);expected.addProperty("staminaRegenPerSecond",5);
        JsonObject defaults=RpgConfig.JSON.toJsonTree(StaminaLevelScaling.defaults()).getAsJsonObject();
        if(!expected.has("staminaLevelScaling"))expected.add("staminaLevelScaling",defaults);
        else for(var field:defaults.entrySet())if(!expected.getAsJsonObject("staminaLevelScaling").has(field.getKey()))
            expected.getAsJsonObject("staminaLevelScaling").add(field.getKey(),field.getValue());

        var config=new RpgConfig(dir);var definitions=config.reload();
        var creatures=CreatureCombatCatalog.read(dir.resolve("creature_combat.json"));
        if(creatures.definitions().isEmpty())throw new AssertionError("Creature melee catalog has no real supplied attack definitions");
        if(!expected.equals(read(dir.resolve("combat.json"))))throw new AssertionError("Unexpected combat field change");
        Map<String,String> after=hashes(dir);List<String> changed=new ArrayList<>(),added=new ArrayList<>();
        for(var field:before.entrySet()) {
            if(!after.containsKey(field.getKey()))throw new AssertionError("Removed existing config: "+field.getKey());
            if(!field.getValue().equals(after.get(field.getKey()))) {
                if(!field.getKey().equals("combat.json"))throw new AssertionError("Changed unrelated file: "+field.getKey());
                changed.add(field.getKey());
            }
        }
        for(String name:after.keySet())if(!before.containsKey(name)) {
            if(!ALLOWED_NEW.contains(name))throw new AssertionError("Unexpected new config: "+name);
            added.add(name);
        }
        if(!added.contains(StaminaLevelMigration.MARKER)||!added.contains(StaminaLevelMigration.BACKUP+"/combat.json"))
            throw new AssertionError("Stamina migration marker and exact original backup are required");
        if(!Arrays.equals(original,Files.readAllBytes(dir.resolve(StaminaLevelMigration.BACKUP).resolve("combat.json"))))
            throw new AssertionError("Original combat backup is not byte exact");
        if(!before.containsKey("creature_combat.json")) {
            try(var in=Stage074Config.class.getResourceAsStream("/defaults/creature_combat.json")) {
                if(in==null||!Arrays.equals(in.readAllBytes(),Files.readAllBytes(dir.resolve("creature_combat.json"))))
                    throw new AssertionError("New creature config is not the exact tested bundled seed");
            }
        }
        config.reload();CreatureCombatCatalog.read(dir.resolve("creature_combat.json"));
        if(!after.equals(hashes(dir)))throw new AssertionError("Second reload changed staged config");
        if(!before.equals(hashes(source)))throw new AssertionError("Official source changed while staging");
        if(definitions.combat().maxStamina()!=50||definitions.combat().staminaRegenPerSecond()!=5)
            throw new AssertionError("Requested base50/base5 did not load");

        JsonObject report=new JsonObject();report.addProperty("passed",true);report.addProperty("version","0.7.4");
        report.addProperty("sourceDirectory",source.toString());report.addProperty("stageDirectory",dir.toString());
        report.addProperty("sourceCopiedByteExactly",true);report.addProperty("officialSourceUntouched",true);
        report.addProperty("allOtherExistingFieldsAndFilesPreserved",true);report.addProperty("exactOriginalBackups",true);report.addProperty("secondReloadIdempotent",true);
        report.addProperty("baseMaxStamina",50);report.addProperty("baseStaminaRegenPerSecond",5);report.addProperty("levelSource","vanilla experienceLevel");
        report.add("staminaLevelScaling",RpgConfig.JSON.toJsonTree(definitions.combat().staminaLevelScaling()));
        report.addProperty("creatureCatalogDefinitionCount",creatures.definitions().size());report.addProperty("creatureCatalogValidatedAgainstSuppliedArt",true);
        report.addProperty("existingFileCount",before.size());report.addProperty("stagedFileCount",after.size());
        report.add("changedExistingFiles",RpgConfig.JSON.toJsonTree(changed));report.add("newFiles",RpgConfig.JSON.toJsonTree(added));
        report.add("beforeSha256",RpgConfig.JSON.toJsonTree(before));report.add("afterSha256",RpgConfig.JSON.toJsonTree(after));
        Files.createDirectories(output.getParent());RpgConfig.atomicWrite(output,RpgConfig.JSON.toJson(report));
        System.out.println("Stage074Config passed: "+before.size()+" original files; only combat base/scaling changed; "+added.size()+" allowed new files; exact backup; second reload idempotent");
    }
    private static void copyTree(Path source,Path dest)throws Exception {
        Files.createDirectories(dest);
        try(var paths=Files.walk(source)) {for(Path p:paths.toList()) {
            if(Files.isSymbolicLink(p))throw new IllegalStateException("Cannot stage a symbolic config entry: "+p);
            Path target=dest.resolve(source.relativize(p)).normalize();if(!target.startsWith(dest))throw new IllegalStateException("Stage escaped destination");
            if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target,StandardCopyOption.COPY_ATTRIBUTES);
        }}
    }
    private static JsonElement read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p));}
    private static Map<String,String> hashes(Path dir)throws Exception {
        Map<String,String> result=new TreeMap<>();try(var files=Files.walk(dir)){for(var p:files.filter(Files::isRegularFile).toList())
            result.put(dir.relativize(p).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}return result;
    }
}

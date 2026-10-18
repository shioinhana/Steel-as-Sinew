package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.fsrpg.combat.StaminaCombatRules;
import org.fsrpg.creature.CreatureCombatCatalog;
import org.fsrpg.creature.GuardCueSettings;

/** Read-only official staging: add absent stamina combat rules, retaining every other byte/field. */
public final class Stage077Config {
    private static final Path OFFICIAL = Path.of("C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/config/fsrpg");
    private static final Set<String> ALLOWED_NEW = Set.of(StaminaCombatMigration.MARKER, StaminaCombatMigration.BACKUP + "/combat.json");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged fsrpg directory and output report");
        Path source = OFFICIAL.toAbsolutePath().normalize(), dir = Path.of(args[0]).toAbsolutePath().normalize();
        Path evidence = Path.of("verification/combat-revision-20261018").toAbsolutePath().normalize(), output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!dir.startsWith(evidence) || !output.startsWith(evidence) || dir.equals(source) || dir.startsWith(source))
            throw new IllegalArgumentException("Stage/report must remain inside dedicated release evidence directory");
        Map<String,String> before = hashes(source);
        if (before.size() != 47 || before.keySet().stream().anyMatch(ALLOWED_NEW::contains))
            throw new IllegalStateException("Expected the installed pre-0.7.7 47-file configuration");
        if (!Files.exists(dir) || hashes(dir).isEmpty()) copyTree(source, dir);
        if (!before.equals(hashes(dir)) || !before.equals(hashes(source))) throw new AssertionError("Stage is not a stable exact copy of official config");
        byte[] original = Files.readAllBytes(dir.resolve("combat.json"));
        JsonObject expected = read(dir.resolve("combat.json")).getAsJsonObject().deepCopy();
        JsonObject defaults = RpgConfig.JSON.toJsonTree(StaminaCombatRules.defaults()).getAsJsonObject();
        if (!expected.has("staminaCombatRules")) expected.add("staminaCombatRules", defaults);
        else if (expected.get("staminaCombatRules").isJsonObject()) {
            var existing = expected.getAsJsonObject("staminaCombatRules");
            for (var field : defaults.entrySet()) if (!existing.has(field.getKey())) existing.add(field.getKey(), field.getValue());
        }
        var config = new RpgConfig(dir); var definitions = config.reload();
        GuardCueSettings.read(dir.resolve("guard_cues.json")); CreatureCombatCatalog.read(dir.resolve("creature_combat.json"));
        if (!expected.equals(read(dir.resolve("combat.json")))) throw new AssertionError("Migration changed fields outside absent staminaCombatRules entries");
        Map<String,String> after = hashes(dir); List<String> changed = new ArrayList<>(), added = new ArrayList<>();
        for (var file : before.entrySet()) {
            if (!after.containsKey(file.getKey())) throw new AssertionError("Removed existing config: " + file.getKey());
            if (!file.getValue().equals(after.get(file.getKey()))) {
                if (!file.getKey().equals("combat.json")) throw new AssertionError("Changed unrelated config: " + file.getKey());
                changed.add(file.getKey());
            }
        }
        for (String name : after.keySet()) if (!before.containsKey(name)) {
            if (!ALLOWED_NEW.contains(name)) throw new AssertionError("Unexpected new config: " + name); added.add(name);
        }
        if (after.size() != 49 || !Set.copyOf(added).equals(ALLOWED_NEW)) throw new AssertionError("Expected only migration marker and exact combat original: 47 to 49 files");
        if (!Arrays.equals(original, Files.readAllBytes(dir.resolve(StaminaCombatMigration.BACKUP).resolve("combat.json"))))
            throw new AssertionError("Combat original backup differs byte-for-byte");
        config.reload();
        if (!after.equals(hashes(dir))) throw new AssertionError("Second reload changed staged config");
        if (!before.equals(hashes(source))) throw new AssertionError("Official source changed while staging");

        JsonObject report = new JsonObject(); report.addProperty("passed",true); report.addProperty("version","0.7.7");
        report.addProperty("sourceDirectory",source.toString()); report.addProperty("stageDirectory",dir.toString());
        report.addProperty("sourceCopiedByteExactly",true); report.addProperty("officialSourceUntouched",true);
        report.addProperty("onlyMissingStaminaCombatFieldsAdded",true); report.addProperty("allOtherExistingFieldsAndFilesPreserved",true);
        report.addProperty("exactOriginalBackups",true); report.addProperty("secondReloadIdempotent",true);
        report.add("staminaCombatRules",RpgConfig.JSON.toJsonTree(definitions.combat().staminaCombatRules()));
        report.addProperty("baseMaxStamina",definitions.combat().maxStamina()); report.addProperty("baseStaminaRegenPerSecond",definitions.combat().staminaRegenPerSecond());
        report.add("staminaLevelScaling",RpgConfig.JSON.toJsonTree(definitions.combat().staminaLevelScaling()));
        report.addProperty("existingFileCount",before.size()); report.addProperty("stagedFileCount",after.size());
        report.add("changedExistingFiles",RpgConfig.JSON.toJsonTree(changed)); report.add("newFiles",RpgConfig.JSON.toJsonTree(added));
        report.add("beforeSha256",RpgConfig.JSON.toJsonTree(before)); report.add("afterSha256",RpgConfig.JSON.toJsonTree(after));
        Files.createDirectories(output.getParent()); RpgConfig.atomicWrite(output,RpgConfig.JSON.toJson(report));
        System.out.println("Stage077Config passed: 47 original files; 49 staged files; only absent stamina combat fields added; exact original; reload idempotent");
    }

    private static JsonElement read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)); }
    private static Map<String,String> hashes(Path directory) throws Exception {
        var result = new TreeMap<String,String>(); try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(directory.relativize(path).toString().replace('\\','/'),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        } return result;
    }
    private static void copyTree(Path source,Path dest) throws Exception {
        Files.createDirectories(dest); try (var paths = Files.walk(source)) { for (Path path : paths.toList()) {
            if (Files.isSymbolicLink(path)) throw new IllegalStateException("Cannot stage symbolic config entry");
            Path target = dest.resolve(source.relativize(path)).normalize(); if (!target.startsWith(dest)) throw new IllegalStateException("Stage escaped destination");
            if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path,target,StandardCopyOption.COPY_ATTRIBUTES);
        } }
    }
}

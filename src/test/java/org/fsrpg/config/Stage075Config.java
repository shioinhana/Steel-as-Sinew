package org.fsrpg.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.fsrpg.creature.CreatureCombatCatalog;
import org.fsrpg.creature.GuardCueSettings;

/** Read-only official-instance staging with an exact guard-only field/file allowlist. */
public final class Stage075Config {
    private static final Path OFFICIAL = Path.of("C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/config/fsrpg");
    private static final Set<String> EDITED = Set.of("combat.json", "styles.json");
    private static final Set<String> ALLOWED_NEW = Set.of(GuardCueMigration.MARKER,
            GuardCueMigration.BACKUP + "/combat.json", GuardCueMigration.BACKUP + "/styles.json", "guard_cues.json");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged fsrpg directory and output report");
        Path source = OFFICIAL.toAbsolutePath().normalize(), dir = Path.of(args[0]).toAbsolutePath().normalize();
        Path verification = Path.of("verification/guard-combo-20261018").toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!dir.startsWith(verification) || !output.startsWith(verification) || dir.equals(source) || dir.startsWith(source))
            throw new IllegalArgumentException("Stage/report must stay inside the dedicated release evidence directory");
        Map<String, String> before = hashes(source);
        if (before.containsKey(GuardCueMigration.MARKER) || before.containsKey(GuardCueMigration.BACKUP + "/combat.json")
                || before.containsKey(GuardCueMigration.BACKUP + "/styles.json"))
            throw new IllegalStateException("This stage expects the installed pre-0.7.5 configuration");
        if (!Files.exists(dir) || hashes(dir).isEmpty()) copyTree(source, dir);
        if (!before.equals(hashes(dir)) || !before.equals(hashes(source))) throw new AssertionError("Stage is not an exact stable copy of official config");
        Map<String, byte[]> originals = new TreeMap<>();
        for (String name : EDITED) originals.put(name, Files.readAllBytes(dir.resolve(name)));
        var expectedCombat = read(dir.resolve("combat.json")).getAsJsonObject().deepCopy(); calibrate(expectedCombat);
        var expectedStyles = read(dir.resolve("styles.json")).getAsJsonArray().deepCopy();
        for (var value : expectedStyles) for (String field : List.of("guard", "heavyGuard")) {
            var style = value.getAsJsonObject(); if (style.has(field) && style.get(field).isJsonObject()) calibrate(style.getAsJsonObject(field));
        }

        var config = new RpgConfig(dir); var definitions = config.reload();
        var settings = GuardCueSettings.read(dir.resolve("guard_cues.json"));
        var creatures = CreatureCombatCatalog.read(dir.resolve("creature_combat.json"));
        if (!expectedCombat.equals(read(dir.resolve("combat.json"))) || !expectedStyles.equals(read(dir.resolve("styles.json"))))
            throw new AssertionError("Migration changed fields outside enabled perfectGuardTicks");
        if (!settings.equals(GuardCueSettings.defaults())) throw new AssertionError("Staged cue seed differs from tested project defaults");
        Map<String, String> after = hashes(dir); List<String> changed = new ArrayList<>(), added = new ArrayList<>();
        for (var entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) throw new AssertionError("Removed existing config: " + entry.getKey());
            if (!entry.getValue().equals(after.get(entry.getKey()))) {
                if (!EDITED.contains(entry.getKey())) throw new AssertionError("Changed unrelated config: " + entry.getKey());
                changed.add(entry.getKey());
            }
        }
        for (String name : after.keySet()) if (!before.containsKey(name)) {
            if (!ALLOWED_NEW.contains(name)) throw new AssertionError("Unexpected new config: " + name); added.add(name);
        }
        if (!Set.copyOf(added).equals(ALLOWED_NEW)) throw new AssertionError("Expected exactly cue seed, migration marker and two exact backups");
        for (String name : EDITED) if (!Arrays.equals(originals.get(name), Files.readAllBytes(dir.resolve(GuardCueMigration.BACKUP).resolve(name))))
            throw new AssertionError("Original backup is not byte exact: " + name);
        try (var in = Stage075Config.class.getResourceAsStream("/defaults/guard_cues.json")) {
            if (in == null || !Arrays.equals(in.readAllBytes(), Files.readAllBytes(dir.resolve("guard_cues.json"))))
                throw new AssertionError("New guard cue config is not the exact bundled seed");
        }
        config.reload(); GuardCueSettings.read(dir.resolve("guard_cues.json"));
        if (!after.equals(hashes(dir))) throw new AssertionError("Second reload changed staged config");
        if (!before.equals(hashes(source))) throw new AssertionError("Official configuration changed while staging");
        if (definitions.combat().maxStamina() != 50 || definitions.combat().staminaRegenPerSecond() != 5)
            throw new AssertionError("Existing stamina level base tuning changed");

        JsonObject report = new JsonObject(); report.addProperty("passed", true); report.addProperty("version", "0.7.5");
        report.addProperty("sourceDirectory", source.toString()); report.addProperty("stageDirectory", dir.toString());
        report.addProperty("sourceCopiedByteExactly", true); report.addProperty("officialSourceUntouched", true);
        report.addProperty("allOtherExistingFieldsAndFilesPreserved", true); report.addProperty("disabledGuardWindowsPreserved", true);
        report.addProperty("exactOriginalBackups", true); report.addProperty("secondReloadIdempotent", true);
        report.addProperty("perfectGuardTicks", GuardCueMigration.WINDOW_TICKS);
        report.add("guardCueSettings", RpgConfig.JSON.toJsonTree(settings));
        report.addProperty("timingSource", "project calibration; no unpublished game frame values claimed");
        report.addProperty("baseMaxStamina", definitions.combat().maxStamina()); report.addProperty("baseStaminaRegenPerSecond", definitions.combat().staminaRegenPerSecond());
        report.add("staminaLevelScaling", RpgConfig.JSON.toJsonTree(definitions.combat().staminaLevelScaling()));
        report.addProperty("counterWindowTicks", definitions.combat().perfectGuardCounter().windowTicks());
        report.addProperty("counterDamageMultiplier", definitions.combat().perfectGuardCounter().damageMultiplier());
        report.addProperty("parryStunTicks", definitions.combat().parryStunTicks());
        report.addProperty("creatureCatalogDefinitionCount", creatures.definitions().size());
        report.addProperty("existingFileCount", before.size()); report.addProperty("stagedFileCount", after.size());
        report.add("changedExistingFiles", RpgConfig.JSON.toJsonTree(changed)); report.add("newFiles", RpgConfig.JSON.toJsonTree(added));
        report.add("beforeSha256", RpgConfig.JSON.toJsonTree(before)); report.add("afterSha256", RpgConfig.JSON.toJsonTree(after));
        Files.createDirectories(output.getParent()); RpgConfig.atomicWrite(output, RpgConfig.JSON.toJson(report));
        System.out.println("Stage075Config passed: " + before.size() + " original files; " + after.size()
                + " staged files; only enabled guard windows changed; exact originals; second reload idempotent");
    }
    private static void calibrate(JsonObject guard) { if (guard.get("perfectGuardTicks").getAsInt() > 0) guard.addProperty("perfectGuardTicks", 6); }
    private static JsonElement read(Path file) throws Exception { return JsonParser.parseString(Files.readString(file)); }
    private static void copyTree(Path source, Path dest) throws Exception {
        Files.createDirectories(dest);
        try (var paths = Files.walk(source)) { for (Path path : paths.toList()) {
            if (Files.isSymbolicLink(path)) throw new IllegalStateException("Cannot stage symbolic config entry");
            Path target = dest.resolve(source.relativize(path)).normalize(); if (!target.startsWith(dest)) throw new IllegalStateException("Stage escaped destination");
            if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
        } }
    }
    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> result = new TreeMap<>(); try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) result.put(directory.relativize(path).toString().replace('\\', '/'),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        } return result;
    }
}

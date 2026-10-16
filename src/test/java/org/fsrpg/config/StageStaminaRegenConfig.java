package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Validate the production migration on a staged copy before deploying any user configuration. */
public final class StageStaminaRegenConfig {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged config directory and output report");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        Path combat = directory.resolve("combat.json");
        byte[] originalBytes = Files.readAllBytes(combat);
        JsonObject original = JsonParser.parseString(Files.readString(combat)).getAsJsonObject();
        double beforeRate = original.get("staminaRegenPerSecond").getAsDouble();
        boolean alreadyMigrated = Files.exists(directory.resolve(StaminaRegenMigration.MARKER));
        Map<String, String> before = hashes(directory);
        CombatResult result = migrate(directory);
        JsonObject next = JsonParser.parseString(Files.readString(combat)).getAsJsonObject();
        JsonObject expected = original.deepCopy();
        expected.addProperty("staminaRegenPerSecond", beforeRate * (alreadyMigrated ? 1 : 3));
        if (!expected.equals(next)) throw new AssertionError("Staged upgrade changed fields besides staminaRegenPerSecond");
        Map<String, String> after = hashes(directory);
        for (var entry : before.entrySet()) if (!entry.getKey().equals("combat.json") && !Objects.equals(entry.getValue(), after.get(entry.getKey())))
            throw new AssertionError("Staged upgrade changed unrelated existing file " + entry.getKey());
        if (!alreadyMigrated) {
            Path snapshot = directory.resolve(StaminaRegenMigration.BACKUP).resolve("combat.json");
            if (!Arrays.equals(originalBytes, Files.readAllBytes(snapshot))) throw new AssertionError("Migration did not preserve exact original combat backup");
        }
        new RpgConfig(directory).reload();
        if (!after.equals(hashes(directory))) throw new AssertionError("Staged regeneration migration is not idempotent");
        JsonObject report = new JsonObject();
        report.addProperty("passed", true);
        report.addProperty("stageDirectory", directory.toString());
        report.addProperty("alreadyMigrated", alreadyMigrated);
        report.addProperty("beforeStaminaRegenPerSecond", beforeRate);
        report.addProperty("afterStaminaRegenPerSecond", result.rate());
        report.addProperty("spiritRegenPerSecond", result.spiritRate());
        report.addProperty("resourceRegenDelayTicks", result.delay());
        report.addProperty("allOtherExistingFieldsAndFilesPreserved", true);
        report.addProperty("exactOriginalCombatBackup", !alreadyMigrated);
        report.addProperty("secondReloadIdempotent", true);
        report.add("migration", JsonParser.parseString(Files.readString(directory.resolve(StaminaRegenMigration.MARKER))));
        report.add("beforeSha256", RpgConfig.JSON.toJsonTree(before));
        report.add("afterSha256", RpgConfig.JSON.toJsonTree(after));
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        RpgConfig.atomicWrite(output, RpgConfig.JSON.toJson(report));
        System.out.println("Staged stamina regeneration validated: " + beforeRate + " -> " + result.rate() + "; all other existing fields and files retained; second reload unchanged");
    }
    private record CombatResult(double rate, double spiritRate, int delay) {}
    private static CombatResult migrate(Path directory) throws Exception {
        var combat = new RpgConfig(directory).reload().combat();
        return new CombatResult(combat.staminaRegenPerSecond(), combat.spiritRegenPerSecond(), combat.resourceRegenDelayTicks());
    }
    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList())
                result.put(directory.relativize(path).toString().replace('\\', '/'), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
}

package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs the production migration on a staged copy; never writes the official instance itself. */
public final class StageStaminaConfig {
    private static final List<String> FILES = List.of("combat.json", "skills.json", "styles.json", "archery.json", "quests.json", "weapons.json", "collections.json", "skill_trees.json");
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged config directory and output report");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String, String> before = hashes(directory);
        Map<String, JsonElement> beforeValues = new LinkedHashMap<>();
        for (String name : before.keySet()) beforeValues.put(name, JsonParser.parseString(Files.readString(directory.resolve(name))));
        boolean alreadyMigrated = Files.exists(directory.resolve(StaminaCostMigration.MARKER));
        var definitions = new RpgConfig(directory).reload();
        Map<String, String> after = hashes(directory);
        var report = new JsonObject();
        report.addProperty("passed", true);
        report.addProperty("stageDirectory", directory.toString());
        report.addProperty("skills", definitions.skills().size());
        report.addProperty("styles", definitions.styles().size());
        report.addProperty("staminaCostMultiplier", .2);
        report.add("beforeSha256", RpgConfig.JSON.toJsonTree(before));
        report.add("afterSha256", RpgConfig.JSON.toJsonTree(after));
        report.add("migration", JsonParser.parseString(Files.readString(directory.resolve(StaminaCostMigration.MARKER))));
        int costs = 0;
        for (var entry : beforeValues.entrySet()) costs += verify(entry.getValue(), JsonParser.parseString(Files.readString(directory.resolve(entry.getKey()))),
                entry.getKey(), alreadyMigrated ? 1 : .2);
        report.addProperty("staminaFieldsValidated", costs);
        report.addProperty("allExistingStaminaCostsScaledExactlyOnce", true);
        report.addProperty("unrelatedConfigValuesPreserved", true);
        JsonArray changed = new JsonArray();
        for (String name : FILES) if (!Objects.equals(before.get(name), after.get(name))) changed.add(name);
        report.add("changedFiles", changed);
        Map<String, String> first = hashes(directory); new RpgConfig(directory).reload();
        if (!first.equals(hashes(directory))) throw new IllegalStateException("Staged migration is not idempotent");
        report.addProperty("secondReloadIdempotent", true);
        RpgConfig.atomicWrite(Path.of(args[1]), RpgConfig.JSON.toJson(report));
        System.out.println("Staged stamina migration validated: " + changed.size() + " changed config files; second reload unchanged");
    }
    private static int verify(JsonElement before, JsonElement after, String path, double multiplier) {
        int costs = 0;
        if (before.isJsonObject()) {
            if (!after.isJsonObject() || !before.getAsJsonObject().keySet().equals(after.getAsJsonObject().keySet()))
                throw new IllegalStateException("Unexpected changed config fields: " + path);
            for (var field : before.getAsJsonObject().entrySet()) {
                JsonElement next = after.getAsJsonObject().get(field.getKey());
                if (field.getKey().equals("staminaCost") || field.getKey().endsWith("StaminaCost")) {
                    if (Math.abs(field.getValue().getAsDouble() * multiplier - next.getAsDouble()) > 1e-8)
                        throw new IllegalStateException("Incorrect migrated stamina value: " + path + "." + field.getKey());
                    costs++;
                } else costs += verify(field.getValue(), next, path + "." + field.getKey(), multiplier);
            }
        } else if (before.isJsonArray()) {
            if (!after.isJsonArray() || before.getAsJsonArray().size() != after.getAsJsonArray().size())
                throw new IllegalStateException("Unexpected changed config entries: " + path);
            for (int i = 0; i < before.getAsJsonArray().size(); i++) costs += verify(before.getAsJsonArray().get(i), after.getAsJsonArray().get(i), path + "[" + i + "]", multiplier);
        } else if (!before.equals(after)) throw new IllegalStateException("Unrelated config value changed: " + path);
        return costs;
    }
    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : FILES) {
            Path path = directory.resolve(name);
            if (Files.exists(path)) result.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
}

package org.fsrpg.creature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;

/** Pure source-reference/configuration checks, without client model or renderer classes. */
public final class CreatureCombatCatalogTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var catalog = CreatureCombatCatalog.bundled();
        check(catalog.definitions().size() == 19, "nineteen supplied melee clips");
        check(catalog.definitions().stream().map(CreatureCombatCatalog.Definition::entityType).distinct().count() == 18,
                "eighteen supplied melee entity profiles");
        var pairs = new HashSet<String>();
        for (var definition : catalog.definitions()) {
            check(pairs.add(definition.entityType() + "/" + definition.variant()), "unique mapping");
            near(0, definition.clipSeconds(0), "source start");
            near(definition.contactSeconds(), definition.clipSeconds(definition.windupTicks()), "20 TPS contact maps exactly to source key");
            near(definition.durationSeconds(), definition.clipSeconds(definition.totalTicks()), "source end");
            check(definition.clipSeconds(definition.windupTicks() + .25) > definition.contactSeconds(), "native subtick advances recovery");
            check(definition.evidence().startsWith("Curve inference, not an author hit marker:"), "honest source interpretation");
            try (var stream = CreatureCombatCatalogTest.class.getResourceAsStream("/assets/fsrpg/art/creatures/" + definition.model() + ".json")) {
                var source = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                var clip = source.getAsJsonArray("clips").asList().stream().map(value -> value.getAsJsonObject())
                        .filter(value -> value.get("id").getAsString().equals(definition.clip())).findFirst().orElseThrow();
                check(clip.getAsJsonArray("tracks").asList().stream().flatMap(value -> value.getAsJsonObject().getAsJsonArray("frames").asList().stream())
                        .anyMatch(frame -> Math.abs(frame.getAsJsonObject().get("time").getAsDouble() - definition.contactSeconds()) < .00001),
                        "default contact is an actual supplied curve key, not a guessed fraction");
            }
        }
        check(catalog.find("minecraft:piglin", "spear").orElseThrow().clip().equals("attack_spear"), "explicit spear variant");
        check(catalog.find("minecraft:piglin", "sword").orElseThrow().clip().equals("attack_sword"), "default sword variant");
        for (String variant : new String[]{"bow", "crossbow", "ranged"})
            check(catalog.find("minecraft:piglin", variant).isEmpty(), "ranged variant preserves vanilla behavior");
        for (String excluded : new String[]{"skeleton", "bogged", "stray", "parched", "pillager", "evoker", "witch", "wither", "ender_dragon", "creeper"})
            check(catalog.find("minecraft:" + excluded, "default").isEmpty(), "no invented melee clip for " + excluded);

        String seed;
        try (var source = CreatureCombatCatalogTest.class.getResourceAsStream("/defaults/creature_combat.json")) {
            seed = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        var directory = Files.createTempDirectory("fsrpg-creature-catalog-");
        var file = directory.resolve("nested/creature_combat.json");
        check(CreatureCombatCatalog.read(file).definitions().size() == 19, "missing file seeded");
        byte[] original = Files.readAllBytes(file);
        CreatureCombatCatalog.read(file);
        check(java.util.Arrays.equals(original, Files.readAllBytes(file)), "reload leaves original bytes unchanged");
        var custom = JsonParser.parseString(seed).getAsJsonObject();
        custom.getAsJsonArray("attacks").remove(0);
        String customText = custom.toString() + "\n";
        Files.writeString(file, customText);
        check(CreatureCombatCatalog.read(file).definitions().size() == 18, "custom profile removal preserved");
        check(Files.readString(file).equals(customText), "custom file never rewritten");
        Files.writeString(file, " ".repeat(512 * 1024 + 1));
        try { CreatureCombatCatalog.read(file); throw new AssertionError("oversized file accepted"); }
        catch (java.io.IOException expected) { checks++; }

        reject(seed, row -> row.addProperty("windupTicks", 0), "zero windup");
        reject(seed, row -> row.addProperty("recoveryTicks", -1), "negative recovery");
        reject(seed, row -> row.addProperty("windupTicks", 1.5), "fractional tick");
        reject(seed, row -> row.addProperty("windupTicks", "6"), "string tick");
        reject(seed, row -> row.addProperty("contactSeconds", 0), "zero contact");
        reject(seed, row -> row.addProperty("contactSeconds", 100), "contact after end");
        reject(seed, row -> row.addProperty("durationSeconds", 2), "source duration mismatch");
        reject(seed, row -> row.addProperty("model", "dragon"), "model from another entity");
        reject(seed, row -> row.addProperty("clip", "idle"), "nonattack clip");
        reject(seed, row -> row.addProperty("clip", "attack_missing"), "missing attack clip");
        reject(seed, row -> row.addProperty("entityType", "minecraft:ender_dragon"), "missing source entity model");
        reject(seed, row -> row.addProperty("evidence", ""), "empty evidence");
        reject(seed, row -> row.addProperty("unknown", 1), "unknown field");
        var duplicate = JsonParser.parseString(seed).getAsJsonObject();
        duplicate.getAsJsonArray("attacks").add(duplicate.getAsJsonArray("attacks").get(0).deepCopy());
        invalid(duplicate.toString(), "duplicate entity variant");
        invalid(seed.replace("\"contactSeconds\": 0.29167", "\"contactSeconds\": 1e999"), "nonfinite contact");
        Files.delete(file); Files.delete(file.getParent()); Files.delete(directory);
        System.out.println("CreatureCombatCatalogTest passed: " + checks + " checks");
    }

    private static void reject(String seed, java.util.function.Consumer<JsonObject> edit, String message) {
        var modified = JsonParser.parseString(seed).getAsJsonObject();
        edit.accept(modified.getAsJsonArray("attacks").get(0).getAsJsonObject());
        invalid(modified.toString(), message);
    }

    private static void invalid(String json, String message) {
        try { CreatureCombatCatalog.parse(json); throw new AssertionError("Accepted " + message); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }

    private static void near(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-8, message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

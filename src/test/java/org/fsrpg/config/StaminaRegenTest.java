package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.*;

/** Upgrade and server-tick coverage for the requested threefold regeneration. */
public final class StaminaRegenTest {
    private static final List<String> FILES = List.of("combat.json", "skills.json", "styles.json", "archery.json", "quests.json", "weapons.json", "collections.json", "skill_trees.json");
    private static int checks;

    public static void main(String[] args) throws Exception {
        freshDefaultsAndServerTicks();
        customUpgradeAndInterruptedRetry();
        freshInterruptedSeeding();
        invalidUpgradeAndRepair();
        System.out.println("StaminaRegenTest passed: " + checks + " checks");
    }

    private static void freshDefaultsAndServerTicks() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-regen-fresh-");
        try {
            RpgConfig config = new RpgConfig(directory);
            CombatRules rules = config.reload().combat();
            equal(CombatRules.defaults(), rules, "fallback matches seeded rules");
            near(5, rules.staminaRegenPerSecond(), "current fresh seed starts at five");
            near(1, rules.spiritRegenPerSecond(), "spirit regeneration unchanged");
            equal(20, rules.resourceRegenDelayTicks(), "historical spirit regeneration delay unchanged");
            equal(resource("combat.json"), read(directory), "fresh combat bytes represent exact bundled defaults");
            check(!Files.exists(directory.resolve(StaminaRegenMigration.BACKUP).resolve("combat.json")), "fresh seed has no invented original combat backup");
            CombatActor actor = new CombatActor(UUID.randomUUID(), rules);
            actor.setResources(40, 40);
            for (int tick = 0; tick < 20; tick++) actor.tick();
            near(45, actor.stamina(), "authoritative 20 Hz ticks recover current five stamina per second");
            near(41, actor.spirit(), "authoritative spirit rate remains one");
            actor.consumeStamina(5);
            double drained = actor.stamina();
            for (int tick = 1; tick < rules.resourceRegenDelayTicks(); tick++) actor.tick();
            near(drained + 19 * .125, actor.stamina(), "combat stamina recovers at half rate throughout the old delay period");
            actor.tick();
            near(drained + 2.5, actor.stamina(), "twenty combat ticks restore half the five-point rate");
            actor.setResources(49.95, 100);
            actor.tick();
            near(50, actor.stamina(), "faster recovery still respects current maximum");
            Map<String, String> before = texts(directory);
            config.reload();
            equal(before, texts(directory), "fresh reload preserves every config byte");
            near(5, config.current().combat().staminaRegenPerSecond(), "fresh reload never triples seed again");
        } finally { cleanup(directory); }
    }

    private static void customUpgradeAndInterruptedRetry() throws Exception {
        Path directory = oldDirectory();
        try {
            JsonObject original = read(directory);
            original.addProperty("staminaRegenPerSecond", .875);
            original.addProperty("maxStamina", 157);
            original.addProperty("spiritRegenPerSecond", 2.75);
            original.addProperty("resourceRegenDelayTicks", 37);
            original.addProperty("dodgeStaminaCost", 8.125);
            original.getAsJsonObject("rapierTargetLock").addProperty("range", 7.5);
            String source = " \n" + RpgConfig.JSON.toJson(original) + "\n";
            Files.writeString(directory.resolve("combat.json"), source);
            Map<String, String> before = texts(directory);
            RpgConfig config = new RpgConfig(directory);
            var definitions = config.reload();
            near(2.625, definitions.combat().staminaRegenPerSecond(), "custom base rate triples exactly");
            JsonObject next = read(directory);
            next.add("staminaRegenPerSecond", original.get("staminaRegenPerSecond"));
            equal(original, next, "only the requested existing combat field changes");
            for (String file : FILES) if (!file.equals("combat.json"))
                equal(before.get(file), Files.readString(directory.resolve(file)), "unrelated config bytes retained: " + file);
            equal(source, Files.readString(directory.resolve(StaminaRegenMigration.BACKUP).resolve("combat.json")), "backup retains exact original bytes");
            Map<String, String> once = texts(directory);
            String marker = Files.readString(directory.resolve(StaminaRegenMigration.MARKER));
            config.reload();
            equal(once, texts(directory), "upgrade is byte-idempotent on reload");
            equal(marker, Files.readString(directory.resolve(StaminaRegenMigration.MARKER)), "completion marker is stable");
            // Crash after the rate write but before marker: retry reads the old rate from its snapshot.
            Files.delete(directory.resolve(StaminaRegenMigration.MARKER));
            JsonObject editedDuringRetry = read(directory);
            editedDuringRetry.addProperty("maxStamina", 193);
            Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(editedDuringRetry));
            near(2.625, config.reload().combat().staminaRegenPerSecond(), "interrupted retry does not multiply an already upgraded value");
            near(193, config.current().combat().maxStamina(), "retry preserves later edits to other fields");
            equal(source, Files.readString(directory.resolve(StaminaRegenMigration.BACKUP).resolve("combat.json")), "retry never replaces original backup");
            JsonObject explicit = read(directory);
            explicit.addProperty("staminaRegenPerSecond", 4.5);
            Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(explicit));
            near(4.5, config.reload().combat().staminaRegenPerSecond(), "later explicit regeneration tuning remains untouched");
        } finally { cleanup(directory); }
    }

    private static void freshInterruptedSeeding() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-regen-fresh-retry-");
        try {
            StaminaRegenMigration.capture(directory);
            copyDefaults(directory);
            near(5, new RpgConfig(directory).reload().combat().staminaRegenPerSecond(), "fresh seed interrupted before marker resumes at five without tripling");
        } finally { cleanup(directory); }
    }

    private static void invalidUpgradeAndRepair() throws Exception {
        for (JsonElement invalid : List.of(new JsonPrimitive(-1), new JsonPrimitive("1"), new JsonPrimitive(400_000), JsonNull.INSTANCE)) {
            Path directory = oldDirectory();
            try {
                JsonObject combat = read(directory);
                combat.add("staminaRegenPerSecond", invalid);
                String original = RpgConfig.JSON.toJson(combat);
                Files.writeString(directory.resolve("combat.json"), original);
                rejects(() -> new RpgConfig(directory).reload(), "invalid original or scaled rate rejected: " + invalid);
                equal(original, Files.readString(directory.resolve("combat.json")), "invalid rate does not rewrite combat");
                check(!Files.exists(directory.resolve(StaminaRegenMigration.MARKER)), "invalid rate cannot complete upgrade");
                check(!Files.exists(directory.resolve(StaminaRegenMigration.BACKUP).resolve("combat.json")), "invalid rate does not freeze a bad snapshot");
                combat.addProperty("staminaRegenPerSecond", 1);
                Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(combat));
                near(3, new RpgConfig(directory).reload().combat().staminaRegenPerSecond(), "rate can be repaired before retry");
            } finally { cleanup(directory); }
        }
        Path directory = oldDirectory();
        try {
            JsonObject combat = read(directory);
            combat.addProperty("staminaRegenPerSecond", 1);
            combat.addProperty("maxSpirit", -1);
            String invalid = RpgConfig.JSON.toJson(combat);
            Files.writeString(directory.resolve("combat.json"), invalid);
            rejects(() -> new RpgConfig(directory).reload(), "whole candidate validation rejects unrelated invalid rule");
            equal(invalid, Files.readString(directory.resolve("combat.json")), "invalid complete rules do not write faster rate");
            combat.addProperty("maxSpirit", 100);
            Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(combat));
            near(3, new RpgConfig(directory).reload().combat().staminaRegenPerSecond(), "repaired complete rules resume migration");
        } finally { cleanup(directory); }
    }

    private static Path oldDirectory() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-regen-old-");
        copyDefaults(directory);
        Files.writeString(directory.resolve(StaminaCostMigration.MARKER), "{}");
        Files.writeString(directory.resolve("legacy-skill-migration-v1.json"), "{}");
        // Isolate the historical x3 upgrade; StaminaLevelMigrationTest separately
        // verifies the subsequent 0.7.4 base replacement and complete upgrade chain.
        Files.writeString(directory.resolve(StaminaLevelMigration.MARKER), "{}");
        // Guard releases have their own exact-field suites. Keep this historical x3 fixture guard-neutral.
        Files.writeString(directory.resolve(PerfectGuardMigration.MARKER), "{}");
        Files.writeString(directory.resolve(GuardCueMigration.MARKER), "{}");
        Files.writeString(directory.resolve(DefenseFlowMigration.MARKER), "{}");
        return directory;
    }
    private static void copyDefaults(Path directory) throws Exception {
        for (String name : FILES) Files.writeString(directory.resolve(name), RpgConfig.JSON.toJson(resource(name)));
    }
    private static JsonElement resource(String name) throws Exception {
        try (var in = StaminaRegenTest.class.getResourceAsStream("/defaults/" + name)) {
            if (in == null) throw new IllegalStateException(name);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private static JsonObject read(Path directory) throws Exception { return JsonParser.parseString(Files.readString(directory.resolve("combat.json"))).getAsJsonObject(); }
    private static Map<String, String> texts(Path directory) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : FILES) result.put(name, Files.readString(directory.resolve(name)));
        return result;
    }
    private static void cleanup(Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException("Cleanup escaped temp directory");
                Files.deleteIfExists(path);
            }
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) { checks++; try { action.run(); throw new AssertionError(message); } catch (RuntimeException | java.io.IOException expected) { } catch (Exception failure) { throw new AssertionError(message, failure); } }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-8, message + ": " + expected + " != " + actual); }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message + ": " + expected + " != " + actual); }
    private static void check(boolean success, String message) { checks++; if (!success) throw new AssertionError(message); }
}

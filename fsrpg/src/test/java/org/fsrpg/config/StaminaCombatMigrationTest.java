package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.*;

/** Missing-field-only upgrades, exact initial backups and strict optional-record compatibility. */
public final class StaminaCombatMigrationTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        freshAndLegacyParse(); missingAndPartial(); initialCaptureAndRetry(); invalidAndStrict();
        System.out.println("StaminaCombatMigrationTest passed: " + checks + " checks");
    }

    private static void freshAndLegacyParse() throws Exception {
        Path dir = Files.createTempDirectory("fsrpg-stamina-combat-fresh-");
        try {
            var config = new RpgConfig(dir); var data = config.reload();
            equal(StaminaCombatRules.defaults(), data.combat().staminaCombatRules(), "fresh combat defaults");
            equal(CombatRules.defaults(), CombatConfigExpectations.legacyMode(data.combat()), "bundled historical combat fields and fallback remain identical");
            check(Files.exists(dir.resolve(StaminaCombatMigration.MARKER)), "fresh seeding records migration");
            check(!Files.exists(dir.resolve(StaminaCombatMigration.BACKUP)), "fresh seed has no fictitious original");
            JsonObject old = read(dir, "combat.json").getAsJsonObject(); old.remove("staminaCombatRules");
            equal(StaminaCombatRules.defaults(), parse(dir, old).combat().staminaCombatRules(), "old JSON without extension has defaults");
            var before = bytes(dir); config.reload(); sameBytes(before, bytes(dir), "second reload byte exact");
        } finally { cleanup(dir); }
    }

    private static void missingAndPartial() throws Exception {
        for (boolean partial : List.of(false, true)) {
            Path dir = fixture();
            try {
                JsonObject combat = read(dir, "combat.json").getAsJsonObject();
                combat.addProperty("maxSpirit", 177); combat.addProperty("staminaRegenPerSecond", 7.25);
                combat.getAsJsonObject("staminaLevelScaling").addProperty("regenSqrtCoefficient", 2.75);
                combat.remove("staminaCombatRules");
                if (partial) {
                    JsonObject rules = new JsonObject(); rules.addProperty("regenMultiplier", .37);
                    combat.add("staminaCombatRules", rules);
                }
                write(dir, "combat.json", combat); var before = bytes(dir);
                StaminaCombatMigration.capture(dir).apply();
                var expected = combat.deepCopy(); JsonObject nested = partial ? expected.getAsJsonObject("staminaCombatRules") : new JsonObject();
                nested.addProperty("combatTicks", 60); nested.addProperty("missCostMultiplier", .5);
                if (!partial) nested.addProperty("regenMultiplier", .5);
                expected.add("staminaCombatRules", nested);
                equal(expected, read(dir, "combat.json"), "only absent fields added, partial=" + partial);
                check(Arrays.equals(before.get("combat.json"), Files.readAllBytes(dir.resolve(StaminaCombatMigration.BACKUP).resolve("combat.json"))), "exact original bytes preserved");
                for (var file : before.entrySet()) if (!file.getKey().equals("combat.json"))
                    check(Arrays.equals(file.getValue(), bytes(dir).get(file.getKey())), "unrelated existing file retained: " + file.getKey());
                var once = bytes(dir); check(StaminaCombatMigration.capture(dir) == null, "completed migration is not recaptured");
                new RpgConfig(dir).reload(); sameBytes(once, bytes(dir), "reload respects custom values and marker");
            } finally { cleanup(dir); }
        }
        Path dir = fixture();
        try {
            JsonObject combat = read(dir, "combat.json").getAsJsonObject();
            combat.add("staminaCombatRules", RpgConfig.JSON.toJsonTree(new StaminaCombatRules(91, .2, .73))); write(dir, "combat.json", combat);
            var before = bytes(dir); StaminaCombatMigration.capture(dir).apply();
            check(Arrays.equals(before.get("combat.json"), Files.readAllBytes(dir.resolve("combat.json"))), "complete custom record stays byte exact");
            check(!Files.exists(dir.resolve(StaminaCombatMigration.BACKUP)), "no config change creates no backup");
        } finally { cleanup(dir); }
    }

    private static void initialCaptureAndRetry() throws Exception {
        Path dir = fixture();
        try {
            JsonObject combat = read(dir, "combat.json").getAsJsonObject(); combat.remove("staminaCombatRules"); write(dir, "combat.json", combat);
            byte[] original = Files.readAllBytes(dir.resolve("combat.json"));
            var migration = StaminaCombatMigration.capture(dir);
            combat.addProperty("maxSpirit", 191); write(dir, "combat.json", combat); migration.apply();
            check(Arrays.equals(original, Files.readAllBytes(dir.resolve(StaminaCombatMigration.BACKUP).resolve("combat.json"))), "capture precedes any other migration changes");
            equal(191, read(dir, "combat.json").getAsJsonObject().get("maxSpirit").getAsInt(), "apply retains other migrations' valid edits");
            Files.delete(dir.resolve(StaminaCombatMigration.MARKER));
            combat = read(dir, "combat.json").getAsJsonObject(); combat.getAsJsonObject("staminaCombatRules").remove("combatTicks");
            combat.getAsJsonObject("staminaCombatRules").addProperty("missCostMultiplier", .17); write(dir, "combat.json", combat);
            StaminaCombatMigration.capture(dir).apply();
            check(Arrays.equals(original, Files.readAllBytes(dir.resolve(StaminaCombatMigration.BACKUP).resolve("combat.json"))), "interrupted retry retains true initial backup");
            near(.17, read(dir, "combat.json").getAsJsonObject().getAsJsonObject("staminaCombatRules").get("missCostMultiplier").getAsDouble(), "retry retains configured discount");
            combat = read(dir, "combat.json").getAsJsonObject(); combat.getAsJsonObject("staminaCombatRules").addProperty("combatTicks", 83); write(dir, "combat.json", combat);
            new RpgConfig(dir).reload(); equal(combat, read(dir, "combat.json"), "finished migration preserves later tuning");
        } finally { cleanup(dir); }
    }

    private static void invalidAndStrict() throws Exception {
        Path dir = fixture();
        try {
            JsonObject combat = read(dir, "combat.json").getAsJsonObject();
            for (String value : List.of("null", "[]", "{\"combatTicks\":1.5,\"regenMultiplier\":0.5,\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":\"0.5\",\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":null,\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":{},\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":true,\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":NaN,\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":Infinity,\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":-0.1,\"missCostMultiplier\":0.5}",
                    "{\"combatTicks\":60,\"regenMultiplier\":0.5,\"missCostMultiplier\":2}",
                    "{\"combatTicks\":60,\"regenMultiplier\":0.5,\"missCostMultiplier\":0.5,\"unknown\":1}")) {
                combat.add("staminaCombatRules", JsonParser.parseString(value)); write(dir, "combat.json", combat);
                var before = bytes(dir); boolean rejected = false;
                try { StaminaCombatMigration.capture(dir).apply(); } catch (RuntimeException expected) { rejected = true; }
                check(rejected, "invalid field rejected: " + value); sameBytes(before, bytes(dir), "invalid input leaves no writes");
                check(!Files.exists(dir.resolve(StaminaCombatMigration.BACKUP)), "validation precedes original backup creation");
                check(!Files.exists(dir.resolve(StaminaCombatMigration.MARKER)), "validation precedes completion marker");
            }
            combat.add("staminaCombatRules", new JsonObject()); boolean rejected = false;
            try { parse(dir, combat); } catch (RuntimeException expected) { rejected = true; }
            check(rejected, "direct parse requires present objects to be complete");
            write(dir, "combat.json", combat);
            equal(StaminaCombatRules.defaults(), new RpgConfig(dir).reload().combat().staminaCombatRules(),
                    "seed fills absent nested values before historical validators");
        } finally { cleanup(dir); }
    }

    private static Path fixture() throws Exception {
        Path dir = Files.createTempDirectory("fsrpg-stamina-combat-existing-"); new RpgConfig(dir).reload();
        Files.delete(dir.resolve(StaminaCombatMigration.MARKER)); return dir;
    }
    private static RpgConfig.Definitions parse(Path dir, JsonObject combat) throws Exception {
        return RpgConfig.parse(combat,read(dir,"skills.json"),read(dir,"quests.json"),read(dir,"weapons.json"),read(dir,"styles.json"),
                read(dir,"collections.json"),read(dir,"skill_trees.json"),read(dir,"archery.json"));
    }
    private static JsonElement read(Path dir,String name) throws Exception { return JsonParser.parseString(Files.readString(dir.resolve(name))); }
    // JsonElement.toString retains explicit nulls; the production pretty writer deliberately omits them.
    private static void write(Path dir,String name,JsonElement value) throws Exception { Files.writeString(dir.resolve(name), " \n"+value+"\n"); }
    private static Map<String,byte[]> bytes(Path dir) throws Exception {
        var result = new TreeMap<String,byte[]>(); try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) result.put(dir.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));
        } return result;
    }
    private static void sameBytes(Map<String,byte[]> expected,Map<String,byte[]> actual,String message) {
        equal(expected.keySet(),actual.keySet(),message+" inventory");
        for (String name : expected.keySet()) check(Arrays.equals(expected.get(name),actual.get(name)),message+" "+name);
    }
    private static void cleanup(Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize(); try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException("Cleanup outside fixture"); Files.deleteIfExists(file);
            }
        }
    }
    private static void near(double expected,double actual,String message) { check(Math.abs(expected-actual)<1e-8,message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+": "+expected+" != "+actual); }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}

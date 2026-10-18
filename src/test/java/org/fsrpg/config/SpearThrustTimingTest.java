package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.*;

/** Real server state transitions and one-time, field-scoped upgrade coverage for the 200 ms thrust. */
public final class SpearThrustTimingTest {
    private static final List<String> FILES = List.of("combat.json", "skills.json", "styles.json", "archery.json", "quests.json", "weapons.json", "collections.json", "skill_trees.json");
    private static int checks;

    public static void main(String[] args) throws Exception {
        freshDefaultsAndServerTicks();
        customUpgradeAndInterruptedRetry();
        invalidCandidateAndRepair();
        System.out.println("SpearThrustTimingTest passed: " + checks + " checks");
    }

    private static void freshDefaultsAndServerTicks() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-thrust-fresh-");
        try {
            RpgConfig config = new RpgConfig(directory);
            var definitions = config.reload();
            var thrust = definitions.skills().get("spear_thrust");
            equal(1, thrust.windupTicks(), "50 ms preparation");
            equal(1, thrust.activeTicks(), "50 ms contact window");
            equal(2, thrust.recoveryTicks(), "100 ms recovery");
            equal(4, thrust.durationTicks(), "one complete point thrust lasts exactly 200 ms at 20 Hz");
            near(7, thrust.damage(), "thrust damage unchanged");
            near(10, thrust.staminaCost(), "current requested thrust stamina cost is ten");
            near(0, thrust.spiritCost(), "thrust spirit cost unchanged");
            near(4.5, thrust.reach(), "thrust reach unchanged");
            equal(0, thrust.cooldownTicks(), "existing cooldown remains zero");
            near(5, definitions.combat().staminaRegenPerSecond(), "current default stamina base is five");
            var sweep = definitions.skills().get("spear_sweep");
            equal(28, sweep.durationTicks(), "trajectory sweep cadence unchanged");
            CombatActor actor = new CombatActor(UUID.randomUUID(), definitions.combat());
            equal(ActionResult.ACCEPTED, actor.setStyle(definitions.styles().get("spear")), "server selects real spear style");
            actor.requestDrawn();
            for (int tick = 0; tick < definitions.combat().drawTicks(); tick++) actor.tick();
            actor.setResources(40, 40);
            var aim = AttackAim.at(12, -8);
            equal(ActionResult.ACCEPTED, actor.aimedSkill(thrust, aim), "server accepts point thrust");
            equal(CombatPhase.WINDUP, actor.phase(), "attack starts with preparation");
            equal(aim, actor.attackAim(), "validated aim retained through complete attack");
            near(30, actor.stamina(), "one acceptance charges the current ten stamina");
            UUID victim = UUID.randomUUID();
            check(!actor.claimVictim(victim), "windup cannot claim a hit");
            actor.tick();
            equal(CombatPhase.ACTIVE, actor.phase(), "first server tick enters contact");
            check(actor.activeSkill() == thrust, "contact uses configured authority skill without another timing override");
            check(actor.claimVictim(victim), "contact can claim a victim");
            check(!actor.claimVictim(victim), "same thrust cannot hit a victim twice");
            check(actor.markAttackContact(actor.snapshot().swingSequence()), "actual landed thrust retains its full cost");
            actor.tick();
            equal(CombatPhase.RECOVERY, actor.phase(), "second tick ends contact");
            equal(2, actor.snapshot().phaseTicksRemaining(), "two recovery ticks remain");
            check(!actor.claimVictim(UUID.randomUUID()), "recovery cannot deal damage");
            actor.tick();
            equal(CombatPhase.RECOVERY, actor.phase(), "third tick retains final recovery tick");
            actor.tick();
            equal(CombatPhase.READY, actor.phase(), "fourth tick completes one point thrust");
            check(actor.currentSkill() == null && actor.attackAim() == null, "finished attack clears skill and aim");
            near(30.625, actor.stamina(), "landed thrust keeps its ten-point cost and recovers fully before contact, then at half rate");
            equal(1, actor.drainStartedSkills().size(), "one click emits one authoritative start");
            for (int tick = 0; tick < 4; tick++) actor.tick();
            equal(1L, actor.snapshot().swingSequence(), "waiting never repeats a point thrust automatically");
            Map<String, String> before = texts(directory);
            config.reload();
            equal(before, texts(directory), "fresh config is byte-idempotent after its first load");
            check(!Files.exists(directory.resolve(SpearThrustTimingMigration.BACKUP).resolve("skills.json")), "fresh correct defaults need no artificial old backup");
        } finally { cleanup(directory); }
    }

    private static void customUpgradeAndInterruptedRetry() throws Exception {
        Path directory = existingDirectory();
        try {
            JsonArray skills = read(directory, "skills.json").getAsJsonArray();
            JsonObject thrust = skill(skills);
            thrust.addProperty("windupTicks", 8); thrust.addProperty("activeTicks", 3); thrust.addProperty("recoveryTicks", 11);
            thrust.addProperty("damage", 12.75); thrust.addProperty("staminaCost", 8.25);
            thrust.addProperty("reach", 5.5); thrust.addProperty("label", "我的点刺");
            thrust.addProperty("model", "custom_spear.fsvoxel"); thrust.addProperty("action", "custom_thrust");
            JsonObject other = skills.get(0).getAsJsonObject(); other.addProperty("damage", 19.5);
            String original = " \n" + RpgConfig.JSON.toJson(skills) + "\n";
            Files.writeString(directory.resolve("skills.json"), original);
            Map<String, String> before = texts(directory);
            var config = new RpgConfig(directory);
            var definitions = config.reload();
            JsonArray expected = skills.deepCopy(); timing(skill(expected), 1, 1, 2);
            equal(expected, read(directory, "skills.json"), "only three timing fields on the stable spear skill change");
            equal(original, Files.readString(directory.resolve(SpearThrustTimingMigration.BACKUP).resolve("skills.json")), "backup preserves exact original bytes");
            for (String name : FILES) if (!name.equals("skills.json"))
                equal(before.get(name), Files.readString(directory.resolve(name)), "unrelated config bytes retained: " + name);
            near(3, definitions.combat().staminaRegenPerSecond(), "already completed stamina migration is not multiplied again");
            Map<String, String> once = texts(directory);
            config.reload(); equal(once, texts(directory), "second load makes no config writes");
            // Simulate interruption after the skill write and before its marker.
            Files.delete(directory.resolve(SpearThrustTimingMigration.MARKER));
            JsonArray duringRetry = read(directory, "skills.json").getAsJsonArray();
            skill(duringRetry).addProperty("damage", 17.25);
            Files.writeString(directory.resolve("skills.json"), RpgConfig.JSON.toJson(duringRetry));
            near(17.25, config.reload().skills().get("spear_thrust").damage(), "interrupted retry retains later non-timing edits");
            equal(4, config.current().skills().get("spear_thrust").durationTicks(), "interrupted retry remains exactly four ticks");
            equal(original, Files.readString(directory.resolve(SpearThrustTimingMigration.BACKUP).resolve("skills.json")), "retry never overwrites original backup");
            JsonArray later = read(directory, "skills.json").getAsJsonArray(); timing(skill(later), 2, 2, 3);
            Files.writeString(directory.resolve("skills.json"), RpgConfig.JSON.toJson(later));
            equal(7, config.reload().skills().get("spear_thrust").durationTicks(), "later explicit tuning survives reload after completed migration");
        } finally { cleanup(directory); }
    }

    private static void invalidCandidateAndRepair() throws Exception {
        Path directory = existingDirectory();
        try {
            JsonArray skills = read(directory, "skills.json").getAsJsonArray(); timing(skill(skills), 5, 4, 9);
            Files.writeString(directory.resolve("skills.json"), RpgConfig.JSON.toJson(skills));
            JsonObject combat = read(directory, "combat.json").getAsJsonObject(); combat.addProperty("maxSpirit", -1);
            Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(combat));
            Map<String, String> before = texts(directory);
            rejects(() -> new RpgConfig(directory).reload(), "invalid complete candidate rejects requested timing upgrade");
            equal(before, texts(directory), "validation failure leaves config bytes intact");
            check(!Files.exists(directory.resolve(SpearThrustTimingMigration.MARKER)), "failed validation cannot mark migration complete");
            check(!Files.exists(directory.resolve(SpearThrustTimingMigration.BACKUP).resolve("skills.json")), "failed validation does not freeze a bad backup");
            combat.addProperty("maxSpirit", 100); Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(combat));
            equal(4, new RpgConfig(directory).reload().skills().get("spear_thrust").durationTicks(), "repair can finish pending upgrade");
        } finally { cleanup(directory); }
    }

    private static Path existingDirectory() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-thrust-existing-");
        // Isolate timing retries from the release's independently tested guard and cost upgrades.
        new RpgConfig(directory).reload();
        // A completed level migration may be explicitly tuned later. Keep a
        // historical rate of three here and prove the timing-only retry preserves it.
        JsonObject combat = read(directory, "combat.json").getAsJsonObject();
        combat.addProperty("staminaRegenPerSecond", 3);
        Files.writeString(directory.resolve("combat.json"), RpgConfig.JSON.toJson(combat));
        Files.delete(directory.resolve(SpearThrustTimingMigration.MARKER));
        return directory;
    }
    private static JsonObject skill(JsonArray array) { for (var value : array) if ("spear_thrust".equals(value.getAsJsonObject().get("id").getAsString())) return value.getAsJsonObject(); throw new AssertionError("Missing spear_thrust"); }
    private static void timing(JsonObject value, int windup, int active, int recovery) { value.addProperty("windupTicks", windup); value.addProperty("activeTicks", active); value.addProperty("recoveryTicks", recovery); }
    private static JsonElement read(Path directory, String name) throws Exception { return JsonParser.parseString(Files.readString(directory.resolve(name))); }
    private static Map<String, String> texts(Path directory) throws Exception { Map<String, String> result = new LinkedHashMap<>(); for (String name : FILES) result.put(name, Files.readString(directory.resolve(name))); return result; }
    private static void cleanup(Path directory) throws Exception { Path root = directory.toAbsolutePath().normalize(); try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) { if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException("Cleanup escaped temp directory"); Files.deleteIfExists(path); } } }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) { checks++; try { action.run(); throw new AssertionError(message); } catch (RuntimeException | java.io.IOException expected) {} catch (Exception failure) { throw new AssertionError(message, failure); } }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-8, message + ": " + expected + " != " + actual); }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message + ": " + expected + " != " + actual); }
    private static void check(boolean success, String message) { checks++; if (!success) throw new AssertionError(message); }
}

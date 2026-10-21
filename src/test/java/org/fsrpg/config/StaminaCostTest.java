package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.*;

/** Focused coverage of upgrade costs and the authoritative combat debit/gate paths. */
public final class StaminaCostTest {
    private static final List<String> FILES = List.of("combat.json", "skills.json", "styles.json", "archery.json", "quests.json", "weapons.json", "collections.json", "skill_trees.json");
    private static int checks;

    public static void main(String[] args) throws Exception {
        templateCosts();
        freshAndAuthoritativeDebits();
        customUpgradeAndRetry();
        oldLegacyUpgrade();
        invalidUpgradeAndRepair();
        System.out.println("StaminaCostTest passed: " + checks + " checks");
    }

    private static void templateCosts() throws Exception {
        for (String name : FILES.subList(0, 4)) {
            JsonElement before = resource("/stamina-before-0.5.3/" + name);
            if (name.equals("skills.json")) currentSpearTiming(before);
            currentTemplateRules(before,name);
            compareCosts(before, resource("/defaults/" + name), name);
        }
        CombatRules bundled = RpgConfig.JSON.fromJson(resource("/defaults/combat.json"), CombatRules.class);
        equal(bundled, CombatRules.defaults(), "pure server fallback matches reduced default config");
    }

    private static void freshAndAuthoritativeDebits() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-stamina-fresh-");
        try {
            RpgConfig config = new RpgConfig(directory);
            var definitions = config.reload();
            for (String name : FILES.subList(0, 4)) equal(resource("/defaults/" + name), read(directory, name), "fresh seed reduced once: " + name);
            for (SkillDefinition skill : definitions.skills().values()) {
                CombatActor actor = ready(definitions.combat());
                actor.setResources(skill.staminaCost(), skill.spiritCost());
                equal(ActionResult.ACCEPTED, actor.requestAttack(skill), skill.id() + " accepted at exact reduced cost");
                near(0, actor.stamina(), skill.id() + " charges reduced cost once");
                near(0, actor.spirit(), skill.id() + " spirit debit unchanged");
                if (skill.staminaCost() > 0) {
                    actor = ready(definitions.combat());
                    actor.setResources(Math.max(0, skill.staminaCost() - 0.001), skill.spiritCost());
                    double before = actor.stamina();
                    equal(ActionResult.INSUFFICIENT_STAMINA, actor.requestAttack(skill), skill.id() + " checks reduced gate");
                    near(before, actor.stamina(), skill.id() + " rejects without partial cost");
                }
            }
            CombatActor dodge = ready(definitions.combat());
            dodge.setResources(6, 100);
            equal(ActionResult.ACCEPTED, dodge.requestDodge(true), "dodge accepted at six stamina");
            near(0, dodge.stamina(), "dodge charges six once");
            checkGuard(definitions, null, false, false, 10);
            checkGuard(definitions, null, false, true, 10);
            for (var style : definitions.styles().values()) if (style.enabled() && style.guard().arcDegrees() > 0) {
                checkGuard(definitions, style, false, false, style.guard().staminaCost());
                checkGuard(definitions, style, false, true, style.guard().perfectGuardStaminaCost());
                if (style.heavyGuard() != null) checkGuard(definitions, style, true, false, style.heavyGuard().staminaCost());
            }
            CombatActor draw = new CombatActor(UUID.randomUUID(), definitions.combat());
            draw.setResources(0, 0);
            equal(ActionResult.ACCEPTED, draw.toggleDrawn(), "draw remains free");
            for (int tick = 0; tick < definitions.combat().drawTicks(); tick++) draw.tick();
            equal(ActionResult.ACCEPTED, draw.toggleDrawn(), "sheath remains free");
            var textBefore = Files.readString(directory.resolve("skills.json"));
            config.reload();
            equal(textBefore, Files.readString(directory.resolve("skills.json")), "fresh reload never rescales costs");
        } finally { cleanup(directory); }
    }

    private static void checkGuard(RpgConfig.Definitions definitions, WeaponStyleDefinition style, boolean heavy, boolean perfect, double cost) {
        CombatActor actor = ready(definitions.combat());
        if (style != null) equal(ActionResult.ACCEPTED, actor.setStyle(style), "guard style accepted");
        equal(ActionResult.ACCEPTED, actor.setGuarding(true, heavy), "guard intent accepted");
        actor.setResources(cost, 100);
        var result = CombatResolver.receiveIncoming(null, actor, 0, 4, 0, true, perfect, perfect);
        equal(perfect ? HitOutcome.PARRIED : HitOutcome.BLOCKED, result.outcome(), "guard reduced-cost gate and outcome");
        near(0, actor.stamina(), "guard charges reduced cost once");
    }

    private static void customUpgradeAndRetry() throws Exception {
        Path directory = oldDirectory();
        try {
            JsonArray skills = read(directory, "skills.json").getAsJsonArray();
            JsonObject custom = skills.get(0).getAsJsonObject().deepCopy();
            custom.addProperty("id", "custom_stamina_action"); custom.addProperty("staminaCost", 37.25);
            custom.addProperty("spiritCost", 13.5); custom.addProperty("damage", 7.75);
            custom.addProperty("model", "custom_model.fsvoxel"); custom.addProperty("action", "custom_action"); skills.add(custom);
            write(directory, "skills.json", skills);
            JsonArray styles = read(directory, "styles.json").getAsJsonArray();
            styles.get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("staminaCost", 41);
            styles.get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("perfectGuardStaminaCost", 21.7);
            styles.get(0).getAsJsonObject().getAsJsonObject("heavyGuard").addProperty("staminaCost", 99);
            write(directory, "styles.json", styles);
            JsonObject archery = read(directory, "archery.json").getAsJsonObject();
            archery.addProperty("rapidStaminaCost", 9.5); archery.addProperty("snipeStaminaCost", 17.5); archery.addProperty("bashStaminaCost", 23);
            write(directory, "archery.json", archery);
            JsonObject combat = read(directory, "combat.json").getAsJsonObject();
            combat.addProperty("guardStaminaCost", 71.5); combat.addProperty("perfectGuardStaminaCost", 81.5); combat.addProperty("dodgeStaminaCost", 43.75);
            write(directory, "combat.json", combat);
            Map<String, String> before = texts(directory);
            Path model = directory.resolve("models/custom_model.fsvoxel"); Files.createDirectories(model.getParent()); Files.writeString(model, "custom model bytes");
            RpgConfig config = new RpgConfig(directory); var migrated = config.reload();
            for (String name : FILES.subList(0, 4)) {
                compareCosts(JsonParser.parseString(before.get(name)), read(directory, name), "custom upgrade " + name);
                equal(before.get(name), Files.readString(directory.resolve(StaminaCostMigration.BACKUP).resolve(name)), "exact original backup " + name);
            }
            for (String name : FILES.subList(4, 8)) equal(before.get(name), Files.readString(directory.resolve(name)), "unrelated config bytes preserved " + name);
            equal("custom model bytes", Files.readString(model), "custom model bytes preserved");
            near(7.45, migrated.skills().get("custom_stamina_action").staminaCost(), "custom skill ratio retained");
            near(8.2, migrated.styles().get("rapier").guard().staminaCost(), "custom guard ratio retained");
            near(1.9, migrated.archery().rapidStaminaCost(), "custom rapid shot cost reduced");
            near(3.5, migrated.archery().snipeStaminaCost(), "custom snipe shot cost reduced");
            near(4.6, migrated.archery().bashStaminaCost(), "custom bash cost reduced");
            Map<String, String> migratedText = texts(directory); config.reload();
            equal(migratedText, texts(directory), "reload idempotent for all config bytes");
            // Simulate an interruption after some writes but before the completion marker.
            Files.delete(directory.resolve(StaminaCostMigration.MARKER));
            Files.writeString(directory.resolve("skills.json"), before.get("skills.json"));
            config.reload();
            equal(migratedText, texts(directory), "partial upgrade converges without repeated scaling");
            JsonObject patch = new JsonObject(); patch.addProperty("staminaCost", 3.25);
            config.editSkill("custom_stamina_action", patch); config.reload();
            near(3.25, config.current().skills().get("custom_stamina_action").staminaCost(), "later explicit tuning is not rescaled");
        } finally { cleanup(directory); }
    }

    private static void oldLegacyUpgrade() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-stamina-old-legacy-");
        try {
            skipLaterBalanceUpgrades(directory);
            for (String name : List.of("skills.json", "styles.json", "weapons.json")) write(directory, name, resource("/defaults/legacy-0.4/" + name));
            JsonArray before = read(directory, "skills.json").getAsJsonArray();
            var result = new RpgConfig(directory).reload();
            for (JsonElement value : before) {
                JsonObject old = value.getAsJsonObject();
                near(old.get("staminaCost").getAsDouble() / 5, result.skills().get(old.get("id").getAsString()).staminaCost(), "legacy field scales original cost once");
            }
            var defaults = RpgConfig.JSON.fromJson(resource("/defaults/skills.json"), SkillDefinition[].class);
            for (var skill : defaults) if (!containsId(before, skill.id())) near(skill.staminaCost(), result.skills().get(skill.id()).staminaCost(), "new legacy entry keeps reduced template value");
            near(6, result.combat().dodgeStaminaCost(), "absent old combat file seeds reduced cost once");
        } finally { cleanup(directory); }
    }

    private static void invalidUpgradeAndRepair() throws Exception {
        Path directory = oldDirectory();
        try {
            JsonObject broken = read(directory, "combat.json").getAsJsonObject();
            broken.addProperty("dodgeStaminaCost", -1); write(directory, "combat.json", broken);
            Map<String, String> invalid = texts(directory);
            rejects(() -> new RpgConfig(directory).reload(), "negative original cost refuses migration");
            equal(invalid, texts(directory), "invalid upgrade does not alter cost/config files");
            check(!Files.exists(directory.resolve(StaminaCostMigration.MARKER)), "invalid upgrade leaves completion marker absent");
            broken.addProperty("dodgeStaminaCost", 30); write(directory, "combat.json", broken);
            broken.addProperty("maxSpirit", -1); write(directory, "combat.json", broken);
            invalid = texts(directory);
            rejects(() -> new RpgConfig(directory).reload(), "whole candidate validation refuses invalid unrelated field");
            equal(invalid, texts(directory), "whole candidate failure does not write reduced costs");
            broken.addProperty("maxSpirit", 100); write(directory, "combat.json", broken);
            near(6, new RpgConfig(directory).reload().combat().dodgeStaminaCost(), "repair can complete pending migration");
        } finally { cleanup(directory); }
    }

    private static Path oldDirectory() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-stamina-upgrade-");
        for (String name : FILES) {
            JsonElement value = resource((FILES.indexOf(name) < 4 ? "/stamina-before-0.5.3/" : "/defaults/") + name);
            if (name.equals("skills.json")) currentSpearTiming(value);
            write(directory, name, value);
        }
        Files.writeString(directory.resolve("legacy-skill-migration-v1.json"), "{}");
        skipLaterBalanceUpgrades(directory);
        return directory;
    }
    // Keep the cost retry fixture on this release's cadence. Restoring an old file after an unrelated
    // timing marker completed is an explicit later edit; that marker must not retune it on reload.
    // SpearThrustTimingTest separately proves the timing upgrade, backup and interrupted retry.
    private static void currentSpearTiming(JsonElement skills) {
        for (var value : skills.getAsJsonArray()) {
            JsonObject skill = value.getAsJsonObject();
            if (!"spear_thrust".equals(skill.get("id").getAsString())) continue;
            skill.addProperty("windupTicks", 1); skill.addProperty("activeTicks", 1); skill.addProperty("recoveryTicks", 2);
        }
    }
    // This class isolates the historical 0.5.3 divide-by-five upgrade. Current
    // fixed costs and guard rules have independent server/debit/migration suites.
    private static void skipLaterBalanceUpgrades(Path directory)throws Exception {
        Files.writeString(directory.resolve(WeaponStaminaBalanceMigration.MARKER),"{}");
        Files.writeString(directory.resolve(PerfectGuardMigration.MARKER),"{}");
        Files.writeString(directory.resolve(GuardCueMigration.MARKER),"{}");
        Files.writeString(directory.resolve(DefenseFlowMigration.MARKER),"{}");
        Files.writeString(directory.resolve(StaminaLevelMigration.MARKER),"{}");
    }
    private static void currentTemplateRules(JsonElement before,String name) {
        if(name.equals("combat.json")) {
            var o=before.getAsJsonObject();o.addProperty("perfectGuardTicks",7);o.addProperty("parryStunTicks",4);
            o.add("perfectGuardCounter",RpgConfig.JSON.toJsonTree(CombatRules.PerfectGuardCounter.defaults()));
            o.addProperty("maxStamina",50);o.add("staminaLevelScaling",RpgConfig.JSON.toJsonTree(StaminaLevelScaling.defaults()));
        } else if(name.equals("styles.json")) {
            for(var entry:before.getAsJsonArray())for(String field:List.of("guard","heavyGuard")) {
                var o=entry.getAsJsonObject();if(!o.has(field)||!o.get(field).isJsonObject())continue;
                var guard=o.getAsJsonObject(field);if(guard.get("perfectGuardTicks").getAsInt()>0)guard.addProperty("perfectGuardTicks",7);
                guard.addProperty("parryStunTicks",4);
            }
        } else if(name.equals("archery.json")) {
            var o=before.getAsJsonObject();o.addProperty("rapidStaminaCost",25);o.addProperty("snipeStaminaCost",75);o.addProperty("partialShotStaminaMultiplier",.7);
        } else if(name.equals("skills.json")) {
            for(var entry:before.getAsJsonArray()) {
                var o=entry.getAsJsonObject();String weapon=o.get("weapon").getAsString(),id=o.get("id").getAsString();
                int cost=weapon.equals("sword")||weapon.equals("rapier")?5:weapon.equals("dual_blade")?8:id.equals("spear_thrust")?10:id.equals("bow_rapid_release")?5:id.equals("bow_snipe_release")?15:-1;
                if(cost>=0)o.addProperty("staminaCost",cost*5);
            }
        }
    }
    private static boolean containsId(JsonArray entries, String id) { for (var value : entries) if (id.equals(value.getAsJsonObject().get("id").getAsString())) return true; return false; }
    private static CombatActor ready(CombatRules rules) { CombatActor actor = new CombatActor(UUID.randomUUID(), rules); actor.toggleDrawn(); for (int i = 0; i < rules.drawTicks(); i++) actor.tick(); return actor; }
    private static void compareCosts(JsonElement before, JsonElement after, String path) {
        if (before.isJsonObject()) {
            Set<String> afterFields = new HashSet<>(after.getAsJsonObject().keySet());
            if (path.endsWith("combat.json") && !before.getAsJsonObject().has("rapierTargetLock")) {
                equal(RpgConfig.JSON.toJsonTree(RapierTargeting.Rules.defaults()),
                        after.getAsJsonObject().get("rapierTargetLock"), path + " adds exact default targeting rules");
                afterFields.remove("rapierTargetLock");
            }
            if (path.endsWith("combat.json") && !before.getAsJsonObject().has("staminaCombatRules")) {
                equal(RpgConfig.JSON.toJsonTree(StaminaCombatRules.defaults()),
                        after.getAsJsonObject().get("staminaCombatRules"), path + " adds exact current stamina combat rules");
                afterFields.remove("staminaCombatRules");
            }
            if(path.endsWith("combat.json")&&!before.getAsJsonObject().has("burstDash")) {
                equal(RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()),after.getAsJsonObject().get("burstDash"),path+" adds exact default Shift burst rules");
                afterFields.remove("burstDash");
            }
            if(path.endsWith("combat.json")&&!before.getAsJsonObject().has("spearSwipeKnockbackMultiplier")) {
                equal(new JsonPrimitive(.5),after.getAsJsonObject().get("spearSwipeKnockbackMultiplier"),path+" adds exact default spear swipe knockback multiplier");
                afterFields.remove("spearSwipeKnockbackMultiplier");
            }
            if(path.endsWith("combat.json")&&!before.getAsJsonObject().has("weaponClash")&&after.getAsJsonObject().has("weaponClash")) {
                equal(RpgConfig.JSON.toJsonTree(WeaponClashRules.defaults()),after.getAsJsonObject().get("weaponClash"),path+" seeds exact clash defaults without rewriting existing files");
                afterFields.remove("weaponClash");
            }
            equal(before.getAsJsonObject().keySet(), afterFields, path + " preserves all original fields");
            for (var field : before.getAsJsonObject().entrySet()) {
                if (field.getKey().equals("staminaCost") || field.getKey().endsWith("StaminaCost")) near(field.getValue().getAsDouble() / 5, after.getAsJsonObject().get(field.getKey()).getAsDouble(), path + "." + field.getKey());
                else if (field.getKey().equals("staminaRegenPerSecond")) near(path.equals("combat.json") ? 5 : field.getValue().getAsDouble() * 3, after.getAsJsonObject().get(field.getKey()).getAsDouble(), path + " current template base or isolated historical x3 upgrade");
                else compareCosts(field.getValue(), after.getAsJsonObject().get(field.getKey()), path + "." + field.getKey());
            }
        } else if (before.isJsonArray()) {
            equal(before.getAsJsonArray().size(), after.getAsJsonArray().size(), path + " preserves entries");
            for (int i = 0; i < before.getAsJsonArray().size(); i++) compareCosts(before.getAsJsonArray().get(i), after.getAsJsonArray().get(i), path + "[" + i + "]");
        } else equal(before, after, path + " unrelated value preserved");
    }
    private static Map<String, String> texts(Path directory) throws Exception { Map<String, String> result = new LinkedHashMap<>(); for (String name : FILES) result.put(name, Files.readString(directory.resolve(name))); return result; }
    private static JsonElement resource(String path) throws Exception { try (var in = StaminaCostTest.class.getResourceAsStream(path)) { if (in == null) throw new IllegalStateException(path); return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)); } }
    private static JsonElement read(Path directory, String name) throws Exception { return JsonParser.parseString(Files.readString(directory.resolve(name))); }
    private static void write(Path directory, String name, JsonElement value) throws Exception { Files.writeString(directory.resolve(name), " \n" + RpgConfig.JSON.toJson(value) + "\n"); }
    private static void cleanup(Path directory) throws Exception { Path root = directory.toAbsolutePath().normalize(); try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) { if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException("Cleanup escaped temp directory"); Files.deleteIfExists(path); } } }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) { checks++; try { action.run(); throw new AssertionError(message); } catch (RuntimeException | java.io.IOException expected) { } catch (Exception failure) { throw new AssertionError(message, failure); } }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-8, message + ": " + expected + " != " + actual); }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message + ": " + expected + " != " + actual); }
    private static void check(boolean success, String message) { checks++; if (!success) throw new AssertionError(message); }
}

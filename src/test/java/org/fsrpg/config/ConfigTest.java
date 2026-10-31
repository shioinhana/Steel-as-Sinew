package org.fsrpg.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.fsrpg.combat.CombatRules;
import org.fsrpg.combat.WeaponClashRules;
import org.fsrpg.progression.FootworkRules;

/** Config edits are tested independently of registries, network packets, or Minecraft. */
public final class ConfigTest {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        defaultAndTransaction();
        parseValidation();
        styleValidationAndLegacySeeding();
        collectionsValidationAndLegacySeeding();
        skillTreeAndArcheryMilestoneValidation();
        legacyDefaultFieldMigration();
        rapierTargetLockValidationAndMigration();
        weaponClashValidation();
        if (!failures.isEmpty()) throw new AssertionError("ConfigTest failed " + failures.size() + "/" + checks + " checks: " + String.join("; ", failures));
        System.out.println("ConfigTest passed: " + checks + " checks");
    }

    private static void weaponClashValidation() throws Exception {
        JsonObject combat=resource("combat.json").getAsJsonObject();
        var skills=resource("skills.json");var quests=resource("quests.json");var weapons=resource("weapons.json");
        equal(new WeaponClashRules(3,3),RpgConfig.parse(combat,skills,quests,weapons).combat().weaponClash(),"bundled weapon clash defaults use three ticks and three damage points");
        JsonObject old=combat.deepCopy();old.remove("weaponClash");String oldText=RpgConfig.JSON.toJson(old);
        equal(WeaponClashRules.defaults(),RpgConfig.parse(old,skills,quests,weapons).combat().weaponClash(),"old combat JSON can omit the entire clash extension");
        equal(oldText,RpgConfig.JSON.toJson(old),"parsing old combat rules does not insert or rewrite source fields");
        var base=CombatRules.defaults();
        var compatible=new CombatRules(base.maxStamina(),base.maxSpirit(),base.staminaRegenPerSecond(),base.spiritRegenPerSecond(),
                base.resourceRegenDelayTicks(),base.drawTicks(),base.sheathTicks(),base.guardArcDegrees(),base.guardDamageMultiplier(),
                base.guardStaminaCost(),base.perfectGuardTicks(),base.perfectGuardStaminaCost(),base.parryStunTicks(),base.guardBreakStunTicks(),
                base.hitStunTicks(),base.clashFacingArcDegrees(),base.dodgeDurationTicks(),base.dodgeInvulnerabilityStartTicks(),
                base.dodgeInvulnerabilityTicks(),base.dodgeCooldownTicks(),base.dodgeStaminaCost(),base.dodgeDistance(),base.dodgeCancelsRecovery(),
                base.allowAirDodge(),base.rapierTargetLock(),base.perfectGuardCounter(),base.staminaLevelScaling(),base.staminaCombatRules(),base.burstDash(),.25);
        equal(WeaponClashRules.defaults(),compatible.weaponClash(),"previous full CombatRules constructor retains default clash rules");
        equal(.25,compatible.spearSwipeKnockbackMultiplier(),"previous full constructor retains customized spear swipe knockback");
        JsonObject nullable=combat.deepCopy();nullable.add("weaponClash",com.google.gson.JsonNull.INSTANCE);
        equal(WeaponClashRules.defaults(),RpgConfig.JSON.fromJson(nullable,CombatRules.class).weaponClash(),"programmatic null clash record receives compact-constructor defaults");
        rejects(()->RpgConfig.parse(nullable,skills,quests,weapons),"strict config loader rejects explicit null clash rules");
        for(int window:List.of(0,2,10)) for(double tolerance:List.of(0.0,3.0,1_000_000.0)) {
            JsonObject valid=combat.deepCopy();var clash=valid.getAsJsonObject("weaponClash");
            clash.addProperty("contactWindowTicks",window);clash.addProperty("damageTolerance",tolerance);
            equal(new WeaponClashRules(window,tolerance),RpgConfig.parse(valid,skills,quests,weapons).combat().weaponClash(),"legal clash boundaries retain exact values");
        }
        for(String field:List.of("contactWindowTicks","damageTolerance")) {
            JsonObject missing=combat.deepCopy();missing.getAsJsonObject("weaponClash").remove(field);
            rejects(()->RpgConfig.parse(missing,skills,quests,weapons),"provided clash object requires "+field);
            JsonObject nullField=combat.deepCopy();nullField.getAsJsonObject("weaponClash").add(field,com.google.gson.JsonNull.INSTANCE);
            rejects(()->RpgConfig.parse(nullField,skills,quests,weapons),"provided clash object rejects null "+field);
        }
        for(JsonElement value:List.of(new com.google.gson.JsonPrimitive(-1),new com.google.gson.JsonPrimitive(11),
                new com.google.gson.JsonPrimitive(1.5),new com.google.gson.JsonPrimitive("2"),new com.google.gson.JsonPrimitive(true),
                new com.google.gson.JsonPrimitive(Double.NaN),new com.google.gson.JsonPrimitive(Double.POSITIVE_INFINITY),
                new com.google.gson.JsonPrimitive(1e100))) {
            JsonObject invalid=combat.deepCopy();invalid.getAsJsonObject("weaponClash").add("contactWindowTicks",value);
            rejects(()->RpgConfig.parse(invalid,skills,quests,weapons),"invalid clash contact window rejected: "+value);
        }
        for(JsonElement value:List.of(new com.google.gson.JsonPrimitive(-.01),new com.google.gson.JsonPrimitive(1_000_000.01),
                new com.google.gson.JsonPrimitive("3"),new com.google.gson.JsonPrimitive(false),new com.google.gson.JsonPrimitive(Double.NaN),
                new com.google.gson.JsonPrimitive(Double.POSITIVE_INFINITY),new com.google.gson.JsonPrimitive(Double.NEGATIVE_INFINITY))) {
            JsonObject invalid=combat.deepCopy();invalid.getAsJsonObject("weaponClash").add("damageTolerance",value);
            rejects(()->RpgConfig.parse(invalid,skills,quests,weapons),"invalid clash damage tolerance rejected: "+value);
        }
        for(JsonElement value:List.of(new JsonArray(),new com.google.gson.JsonPrimitive("clash"),new com.google.gson.JsonPrimitive(2),new com.google.gson.JsonPrimitive(true))) {
            JsonObject invalid=combat.deepCopy();invalid.add("weaponClash",value);
            rejects(()->RpgConfig.parse(invalid,skills,quests,weapons),"clash extension must be an object: "+value);
        }
        JsonObject typo=combat.deepCopy();typo.getAsJsonObject("weaponClash").addProperty("damageTolerence",3);
        rejects(()->RpgConfig.parse(typo,skills,quests,weapons),"unknown clash property rejected");
        JsonObject rootTypo=combat.deepCopy();rootTypo.addProperty("weaponClashes",2);
        rejects(()->RpgConfig.parse(rootTypo,skills,quests,weapons),"unknown root clash property rejected");
        rejects(()->new WeaponClashRules(-1,3),"direct clash record rejects negative window");
        rejects(()->new WeaponClashRules(11,3),"direct clash record rejects oversized window");
        for(double tolerance:List.of(-1.0,1_000_001.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            rejects(()->new WeaponClashRules(2,tolerance),"direct clash record rejects invalid or nonfinite tolerance");
        Path directory=Files.createTempDirectory("fsrpg-weapon-clash-config-");
        try {
            var config=new RpgConfig(directory);config.reload();Path combatFile=directory.resolve("combat.json");
            JsonObject customized=JsonParser.parseString(Files.readString(combatFile)).getAsJsonObject();customized.remove("weaponClash");customized.addProperty("maxSpirit",137);
            String original=" \n"+RpgConfig.JSON.toJson(customized)+"\n";Files.writeString(combatFile,original);
            var reloaded=config.reload();equal(137.0,reloaded.combat().maxSpirit(),"old customized combat field survives clash defaults");
            equal(WeaponClashRules.defaults(),reloaded.combat().weaponClash(),"reload supplies absent clash rules in memory");
            equal(original,Files.readString(combatFile),"reload preserves exact old user combat JSON without clash migration");
        } finally {
            try(var paths=Files.walk(directory)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if(!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()))throw new IllegalStateException("test cleanup escaped temp");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void rapierTargetLockValidationAndMigration() throws Exception {
        JsonObject combat = resource("combat.json").getAsJsonObject();
        var skills=resource("skills.json");var quests=resource("quests.json");var weapons=resource("weapons.json");
        var defaults=RpgConfig.parse(combat,skills,quests,weapons).combat().rapierTargetLock();
        equal(10.0,defaults.range(),"rapier lock default is 10 blocks");
        check(defaults.enabled()&&defaults.allowPassive()&&defaults.requireLineOfSight(),"default locking filters enabled");
        JsonObject old=combat.deepCopy();old.remove("rapierTargetLock");
        equal(10.0,RpgConfig.parse(old,skills,quests,weapons).combat().rapierTargetLock().range(),"pre-lock combat JSON parses with defaults");
        for(String field:List.of("enabled","range","allowPassive","requireLineOfSight")) {
            JsonObject invalid=combat.deepCopy();invalid.getAsJsonObject("rapierTargetLock").remove(field);
            rejects(()->RpgConfig.parse(invalid,skills,quests,weapons),"partial lock rules rejected: "+field);
        }
        JsonObject typo=combat.deepCopy();typo.getAsJsonObject("rapierTargetLock").addProperty("rang",10);
        rejects(()->RpgConfig.parse(typo,skills,quests,weapons),"unknown lock field rejected");
        JsonObject scalar=combat.deepCopy();scalar.getAsJsonObject("rapierTargetLock").addProperty("range","10");
        rejects(()->RpgConfig.parse(scalar,skills,quests,weapons),"lock range numeric string rejected");
        JsonObject invalid=combat.deepCopy();invalid.getAsJsonObject("rapierTargetLock").addProperty("range",-1);
        rejects(()->RpgConfig.parse(invalid,skills,quests,weapons),"invalid lock range rejected");
        JsonObject nullable=combat.deepCopy();nullable.add("rapierTargetLock",com.google.gson.JsonNull.INSTANCE);
        rejects(()->RpgConfig.parse(nullable,skills,quests,weapons),"explicit null lock rules rejected");
        Path directory=Files.createTempDirectory("fsrpg-rapier-lock-config-");
        try {
            var config=new RpgConfig(directory);config.reload();
            Path combatFile=directory.resolve("combat.json");
            JsonObject custom=JsonParser.parseString(Files.readString(combatFile)).getAsJsonObject();
            custom.remove("rapierTargetLock");custom.addProperty("maxStamina",157);custom.addProperty("dodgeDistance",2.25);
            String original=RpgConfig.JSON.toJson(custom);Files.writeString(combatFile,original);
            var updated=config.reload();
            equal(157.0,updated.combat().maxStamina(),"target migration preserves custom stamina");
            equal(2.25,updated.combat().dodgeDistance(),"target migration preserves custom dodge");
            equal(10.0,updated.combat().rapierTargetLock().range(),"target migration adds default range");
            equal(original,Files.readString(directory.resolve("rapier-target-lock-backup-v1/combat.json")),"target migration backs up exact prior text");
            String first=Files.readString(combatFile);config.seed();
            equal(first,Files.readString(combatFile),"target migration idempotent");
            custom=JsonParser.parseString(first).getAsJsonObject();
            custom.getAsJsonObject("rapierTargetLock").addProperty("range",7.5);
            custom.getAsJsonObject("rapierTargetLock").addProperty("allowPassive",false);
            custom.getAsJsonObject("rapierTargetLock").remove("requireLineOfSight");
            Files.writeString(combatFile,RpgConfig.JSON.toJson(custom));
            updated=config.reload();
            equal(7.5,updated.combat().rapierTargetLock().range(),"field migration preserves custom range");
            check(!updated.combat().rapierTargetLock().allowPassive(),"field migration preserves false");
            check(updated.combat().rapierTargetLock().requireLineOfSight(),"field migration adds only absent option");
        } finally {
            try(var paths=Files.walk(directory)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if(!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()))throw new IllegalStateException("test cleanup escaped temp");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void defaultAndTransaction() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-config-test-");
        try {
            RpgConfig config = new RpgConfig(directory);
            RpgConfig.Definitions initial = config.reload();
            equal(23, initial.skills().size(), "default skills include five-stage fencing, conditional sword return, combined slash and bow actions");
            equal(3, initial.quests().definitions().size(), "default tutorial count");
            for (String name : List.of("combat.json", "skills.json", "quests.json", "weapons.json", "styles.json", "collections.json", "skill_trees.json", "archery.json")) {
                check(Files.isRegularFile(directory.resolve(name)), "seed creates " + name);
            }
            String originalSkills = Files.readString(directory.resolve("skills.json"), StandardCharsets.UTF_8);
            config.seed();
            equal(originalSkills, Files.readString(directory.resolve("skills.json")), "seed preserves user config");

            JsonObject invalid = patch("damage", -1);
            rejects(() -> config.editSkill("sword_left", invalid), "negative editor damage rejected");
            same(initial, config.current(), "failed edit keeps live definition identity");
            equal(originalSkills, Files.readString(directory.resolve("skills.json")), "failed edit leaves original file");
            check(!Files.exists(directory.resolve("skills.json.bak")), "invalid edit does not create backup");
            rejects(() -> config.editSkill("sword_left", patch("windupTicks", 1.5)), "fractional editor tick rejected");
            rejects(() -> config.editSkill("sword_left", patch("windupTypo", 10)), "unknown editor field rejected");
            rejects(() -> config.editSkill("does_not_exist", patch("damage", 10)), "unknown skill editor rejected");
            JsonObject stringDamage = new JsonObject(); stringDamage.addProperty("damage", "10");
            rejects(() -> config.editSkill("sword_left", stringDamage), "numeric string editor rejected");

            config.setValidator(candidate -> { if (candidate.skills().get("sword_left").damage() == 13) throw new IllegalArgumentException("external registry validation failure"); });
            rejects(() -> config.editSkill("sword_left", patch("damage", 13)), "external validator runs before save");
            same(initial, config.current(), "external validator failure keeps live state");
            equal(originalSkills, Files.readString(directory.resolve("skills.json")), "external validator failure keeps file");

            int validWindup = 20;
            JsonObject valid = patch("damage", 10); valid.addProperty("windupTicks", validWindup);
            RpgConfig.Definitions edited = config.editSkill("sword_left", valid);
            equal(10.0, edited.skills().get("sword_left").damage(), "successful edit changes damage");
            equal(validWindup, edited.skills().get("sword_left").windupTicks(), "successful edit changes windup");
            equal(initial.skills().get("sword_left").label(), edited.skills().get("sword_left").label(), "successful edit preserves UTF-8 label");
            equal(originalSkills, Files.readString(directory.resolve("skills.json.bak")), "backup captures pre-edit file");
            JsonArray persisted = JsonParser.parseString(Files.readString(directory.resolve("skills.json"))).getAsJsonArray();
            equal(validWindup, persisted.get(0).getAsJsonObject().get("windupTicks").getAsInt(), "successful edit persisted");
            same(edited, config.current(), "successful edit replaces live definition");
            rejects(() -> edited.skills().clear(), "skill definitions immutable");
            rejects(() -> edited.weapons().groups().clear(), "weapon groups immutable");

            String savedSkills = Files.readString(directory.resolve("skills.json"));
            JsonArray malformed = JsonParser.parseString(savedSkills).getAsJsonArray();
            malformed.get(0).getAsJsonObject().addProperty("damage", -10);
            Files.writeString(directory.resolve("skills.json"), RpgConfig.JSON.toJson(malformed));
            rejects(config::reload, "bad external numeric value refuses reload");
            same(edited, config.current(), "failed reload keeps prior live config");
            check(Files.readString(directory.resolve("skills.json")).contains("-10"), "failed reload preserves source for repair");

            Files.writeString(directory.resolve("skills.json"), "{broken", StandardCharsets.UTF_8);
            rejects(config::reload, "malformed file refuses reload");
            same(edited, config.current(), "malformed reload keeps prior live config");
            equal("{broken", Files.readString(directory.resolve("skills.json")), "malformed file is preserved");

            Files.writeString(directory.resolve("skills.json"), " ".repeat(512 * 1024 + 1));
            rejects(config::reload, "oversized config rejected before parse");
            same(edited, config.current(), "oversized reload keeps prior live config");
            Files.writeString(directory.resolve("skills.json"), savedSkills);
            RpgConfig.Definitions reloaded = config.reload();
            equal(10.0, reloaded.skills().get("sword_left").damage(), "repaired file loads successfully");

            String previousBackup = Files.readString(directory.resolve("skills.json.bak"));
            JsonArray externallyEdited = JsonParser.parseString(savedSkills).getAsJsonArray();
            externallyEdited.get(0).getAsJsonObject().addProperty("windupTicks", validWindup - 1);
            String externalText = RpgConfig.JSON.toJson(externallyEdited);
            Files.writeString(directory.resolve("skills.json"), externalText);
            rejects(() -> config.editSkill("sword_left", patch("damage", 12)), "GUI edit refuses unseen external text change");
            equal(externalText, Files.readString(directory.resolve("skills.json")), "conflict preserves external editor text exactly");
            equal(previousBackup, Files.readString(directory.resolve("skills.json.bak")), "conflict preserves prior backup");
            same(reloaded, config.current(), "conflict preserves live definition identity");
            equal(validWindup, config.current().skills().get("sword_left").windupTicks(), "external edits remain unapplied before reload");
            config.reload();
            equal(validWindup - 1, config.current().skills().get("sword_left").windupTicks(), "reload imports external windup change");
            RpgConfig.Definitions afterReloadEdit = config.editSkill("sword_left", patch("damage", 12));
            equal(12.0, afterReloadEdit.skills().get("sword_left").damage(), "GUI editing resumes after conflict reload");
            equal(validWindup - 1, afterReloadEdit.skills().get("sword_left").windupTicks(), "GUI edit keeps external windup value");
            equal(externalText, Files.readString(directory.resolve("skills.json.bak")), "post-reload backup includes external changes");

            String whitespaceOnly = " \n\t" + Files.readString(directory.resolve("skills.json")) + "\n \t";
            Files.writeString(directory.resolve("skills.json"), whitespaceOnly);
            RpgConfig.Definitions afterWhitespaceEdit = config.editSkill("sword_left", patch("damage", 14));
            equal(14.0, afterWhitespaceEdit.skills().get("sword_left").damage(), "whitespace-only external edit permits GUI save");
            equal(validWindup - 1, afterWhitespaceEdit.skills().get("sword_left").windupTicks(), "whitespace-only save preserves other values");
            equal(whitespaceOnly, Files.readString(directory.resolve("skills.json.bak")), "whitespace-only original captured in backup");
            try (var files = Files.list(directory)) { check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "atomic editor temp files cleaned"); }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) throw new IllegalStateException("test cleanup escaped temporary directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void styleValidationAndLegacySeeding() throws Exception {
        JsonElement combat = resource("combat.json"), skills = resource("skills.json"), quests = resource("quests.json"), weapons = resource("weapons.json"), styles = resource("styles.json");
        RpgConfig.Definitions definitions = RpgConfig.parse(combat, skills, quests, weapons, styles);
        equal(6, definitions.styles().size(), "melee, reserved unarmed and two bow style slots");
        equal(5, definitions.styles().get("rapier").leftCombo().size(), "five fencing rapier combo stages");
        equal("西洋剑", definitions.styles().get("rapier").label(), "user terminology preserved");
        check(definitions.styles().get("dual_blade").enabled() && definitions.styles().get("spear").enabled() && !definitions.styles().get("unarmed").enabled(), "dual and spear enabled; unarmed remains reserved");
        check(definitions.weapons().matches("unarmed", "minecraft:air", "minecraft:air"), "unarmed group matches an empty main hand");
        equal("sword_thrust", definitions.styles().get("rapier").rightSkill(), "right click selects charged thrust rather than legacy right swing");
        equal(14.0, definitions.styles().get("rapier").heavyGuard().staminaCost(), "reduced heavy guard configuration loaded");
        equal("rapier_riposte", definitions.styles().get("rapier").techniques().riposteSkill(), "default rapier enables the released riposte window beside the charged thrust");
        equal(20, definitions.styles().get("rapier").techniques().riposteWindowTicks(), "released riposte window ticks");
        equal("rapier_reprise", definitions.styles().get("rapier").techniques().repriseSkill(), "default rapier enables the released reprise window");
        equal(12, definitions.styles().get("rapier").techniques().repriseWindowTicks(), "released reprise window ticks");
        rejects(() -> definitions.styles().clear(), "style definitions immutable");
        JsonElement changed = styles.deepCopy(); changed.getAsJsonArray().add(changed.getAsJsonArray().get(0).deepCopy());
        invalidStyleParse(combat, skills, quests, weapons, changed, "duplicate style id rejected");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("heavyGuard").remove("staminaCost");
        invalidStyleParse(combat, skills, quests, weapons, changed, "partial heavy guard cannot silently default its cost");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("techniques").addProperty("riposteWindowTicks", 1.5);
        invalidStyleParse(combat, skills, quests, weapons, changed, "technique window rejects fractional ticks");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("techniques").addProperty("riposteSkill", "missing");
        invalidStyleParse(combat, skills, quests, weapons, changed, "conditional skill must exist");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("techniques").addProperty("riposteSkill", "sword_left");
        invalidStyleParse(combat, skills, quests, weapons, changed, "conditional skill must use RIGHT");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("techniques").addProperty("riposteSkill", "sword_thrust");
        invalidStyleParse(combat, skills, quests, weapons, changed, "conditional skill cannot alias normal right skill");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("techniques").addProperty("typo", 5);
        invalidStyleParse(combat, skills, quests, weapons, changed, "unknown technique field is rejected");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().remove("heavyGuard"); changed.getAsJsonArray().get(0).getAsJsonObject().remove("techniques");
        var oldStyle = RpgConfig.parse(combat, skills, quests, weapons, changed).styles().get("rapier");
        check(oldStyle.heavyGuard() == null && oldStyle.techniques().riposteSkill().isEmpty(), "pre-refinement style files retain old behavior without forced rewrite");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("leftCombo").get(0).getAsJsonObject().addProperty("skillId", "missing_skill");
        invalidStyleParse(combat, skills, quests, weapons, changed, "unknown combo skill rejected");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("rightSkill", "sword_left");
        invalidStyleParse(combat, skills, quests, weapons, changed, "right button cannot reference left-only skill");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("leftCombo").get(0).getAsJsonObject().addProperty("chainTick", 6);
        check(RpgConfig.parse(combat,skills,quests,weapons,changed).styles().get("rapier") != null,"legacy timing fields are optional and do not constrain chaining");
        changed = styles.deepCopy(); lastComboStage(changed).addProperty("chainTick", 5);
        check(RpgConfig.parse(combat,skills,quests,weapons,changed).styles().get("rapier") != null,"legacy timing fields are optional and do not constrain chaining");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("leftCombo").get(0).getAsJsonObject().addProperty("bufferTicks", 6);
        check(RpgConfig.parse(combat,skills,quests,weapons,changed).styles().get("rapier") != null,"legacy timing fields are optional and do not constrain chaining");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("maxBlockedHits", 0);
        invalidStyleParse(combat, skills, quests, weapons, changed, "guard contact limit positive");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("repeatHitWindowTicks", 1.5);
        invalidStyleParse(combat, skills, quests, weapons, changed, "style timing is an integer");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("heavyBreaksGuard", "true");
        invalidStyleParse(combat, skills, quests, weapons, changed, "style boolean cannot be text");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("guard").remove("heavyBreaksGuard");
        invalidStyleParse(combat, skills, quests, weapons, changed, "missing heavy guard rule cannot silently default to false");
        changed = styles.deepCopy(); lastComboStage(changed).remove("chainTick");
        check(RpgConfig.parse(combat,skills,quests,weapons,changed).styles().get("rapier") != null,"legacy timing fields are optional and do not constrain chaining");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("weaponGroup", "missing_group");
        invalidStyleParse(combat, skills, quests, weapons, changed, "enabled style references an existing weapon group");
        changed = styles.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("typo", true);
        invalidStyleParse(combat, skills, quests, weapons, changed, "unknown style field rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("windupTicks", 20);
        check(RpgConfig.parse(combat,changed,quests,weapons,styles).skills().get("sword_left").windupTicks()==20,"timing editor no longer depends on an independent chain tick");

        Path directory = Files.createTempDirectory("fsrpg-legacy-style-test-");
        try {
            JsonArray legacySkills = new JsonArray();
            for (JsonElement skill : skills.getAsJsonArray()) {
                JsonObject value = skill.getAsJsonObject();
                if (List.of("sword_left", "sword_right", "sword_thrust").contains(value.get("id").getAsString())) legacySkills.add(value.deepCopy());
            }
            // Simulate custom old numeric values and a legacy SPECIAL right skill, not just a byte-for-byte stock file.
            legacySkills.get(0).getAsJsonObject().addProperty("damage", 7.75);
            legacySkills.get(2).getAsJsonObject().addProperty("input", "SPECIAL");
            JsonObject legacyWeapons = weapons.deepCopy().getAsJsonObject();
            legacyWeapons.getAsJsonObject("groups").remove("rapier"); legacyWeapons.getAsJsonObject("groups").remove("unarmed");
            String skillsText = " \n" + RpgConfig.JSON.toJson(legacySkills) + "\n";
            String weaponsText = RpgConfig.JSON.toJson(legacyWeapons);
            Files.writeString(directory.resolve("skills.json"), skillsText);
            Files.writeString(directory.resolve("weapons.json"), weaponsText);
            RpgConfig config = new RpgConfig(directory);
            RpgConfig.Definitions legacy = config.reload();
            equal(skillsText, Files.readString(directory.resolve("legacy-skill-backup-v1/skills.json")), "legacy migration backs up exact original custom skills text");
            equal(weaponsText, Files.readString(directory.resolve("legacy-skill-backup-v1/weapons.json")), "legacy migration backs up exact original weapons text");
            equal(7.75, legacy.skills().get("sword_left").damage(), "legacy numeric edits remain effective");
            equal(1, legacy.styles().get("rapier").leftCombo().size(), "missing new stages create a one-stage compatibility style");
            equal(0, legacy.styles().get("rapier").leftCombo().getFirst().chainTick(), "compatibility final stage gate cleared");
            equal("sword_thrust", legacy.styles().get("rapier").rightSkill(), "legacy SPECIAL input retained as right action");
            equal("rapier_riposte", legacy.styles().get("rapier").techniques().riposteSkill(), "legacy catalog gains the conditional skill ids and then the released riposte window");
            equal(20, legacy.styles().get("rapier").techniques().riposteWindowTicks(), "legacy seed uses the released riposte window");
            equal("rapier_reprise", legacy.styles().get("rapier").techniques().repriseSkill(), "legacy seed enables the released reprise pair");
            equal(12, legacy.styles().get("rapier").techniques().repriseWindowTicks(), "legacy seed uses the released reprise window");
            String firstStyleText = Files.readString(directory.resolve("styles.json"));
            config.seed();
            equal(firstStyleText, Files.readString(directory.resolve("styles.json")), "existing style configuration never reseeded");
            String beforeEdit = Files.readString(directory.resolve("skills.json"));
            rejects(() -> config.editSkill("sword_left", patch("windupTicks", -1)), "invalid edit leaves compatible styles intact");
            equal(beforeEdit, Files.readString(directory.resolve("skills.json")), "invalid compatible edit preserves source");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) throw new IllegalStateException("test cleanup escaped temporary directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void parseValidation() throws Exception {
        JsonElement combat = resource("combat.json"), skills = resource("skills.json"), quests = resource("quests.json"), weapons = resource("weapons.json");
        RpgConfig.parse(combat, skills, quests, weapons);
        checks++;
        JsonElement changed;

        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().remove("windupTicks");
        invalidParse(combat, changed, quests, weapons, "missing skill number rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().add("damage", com.google.gson.JsonNull.INSTANCE);
        invalidParse(combat, changed, quests, weapons, "null skill number rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("windupTypo", 1);
        invalidParse(combat, changed, quests, weapons, "unknown skill field rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("windupTicks", 1.5);
        invalidParse(combat, changed, quests, weapons, "fractional external integer rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("damage", "6");
        invalidParse(combat, changed, quests, weapons, "numeric string external value rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("damage", "NaN");
        invalidParse(combat, changed, quests, weapons, "nonfinite skill damage rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("input", "UNKNOWN");
        invalidParse(combat, changed, quests, weapons, "unknown skill input rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().add(changed.getAsJsonArray().get(0).deepCopy());
        invalidParse(combat, changed, quests, weapons, "duplicate skill id rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("weapon", "missing_weapon_group");
        invalidParse(combat, changed, quests, weapons, "unknown weapon group reference rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("model", "../outside.fsvoxel");
        invalidParse(combat, changed, quests, weapons, "asset path traversal rejected");
        changed = skills.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("label", "a".repeat(180_001));
        invalidParse(combat, changed, quests, weapons, "oversized network definition rejected");

        changed = combat.deepCopy(); changed.getAsJsonObject().remove("drawTicks");
        invalidParse(changed, skills, quests, weapons, "missing combat number rejected");
        changed = combat.deepCopy(); changed.getAsJsonObject().addProperty("drawTicks", 2.75);
        invalidParse(changed, skills, quests, weapons, "fractional external combat integer rejected");
        changed = combat.deepCopy(); changed.getAsJsonObject().addProperty("extra", 1);
        invalidParse(changed, skills, quests, weapons, "unknown combat field rejected");
        changed = combat.deepCopy(); changed.getAsJsonObject().addProperty("dodgeInvulnerabilityTicks", 999);
        invalidParse(changed, skills, quests, weapons, "invulnerability must fit dodge window");

        changed = quests.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("prerequisites").add("fsrpg:field_research");
        invalidParse(combat, skills, changed, weapons, "quest cycle rejected");
        changed = quests.deepCopy(); changed.getAsJsonArray().get(1).getAsJsonObject().getAsJsonArray("objectives").get(0).getAsJsonObject().addProperty("target", "unknown_skill");
        invalidParse(combat, skills, changed, weapons, "quest references known skill");
        changed = quests.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("objectives").get(0).getAsJsonObject().addProperty("type", "UNKNOWN");
        invalidParse(combat, skills, changed, weapons, "quest unknown objective type rejected");
        changed = quests.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().addProperty("labelTypo", "ignored?");
        invalidParse(combat, skills, changed, weapons, "unknown quest field rejected");
        changed = quests.deepCopy(); changed.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("objectives").get(0).getAsJsonObject().addProperty("count", 1.75);
        invalidParse(combat, skills, changed, weapons, "fractional quest count rejected");

        changed = weapons.deepCopy(); changed.getAsJsonObject().getAsJsonObject("itemDamageMultipliers").addProperty("minecraft:iron_sword", -1);
        invalidParse(combat, skills, quests, changed, "negative material multiplier rejected");
        changed = weapons.deepCopy(); changed.getAsJsonObject().getAsJsonObject("groups").getAsJsonObject("sword").getAsJsonArray("mainItems").add("bad_item_id");
        invalidParse(combat, skills, quests, changed, "invalid weapon item syntax rejected");
        changed = weapons.deepCopy(); changed.getAsJsonObject().addProperty("extra", 1);
        invalidParse(combat, skills, quests, changed, "unknown weapon field rejected");
    }

    private static void collectionsValidationAndLegacySeeding() throws Exception {
        JsonElement combat = resource("combat.json"), skills = resource("skills.json"), quests = resource("quests.json"), weapons = resource("weapons.json"), styles = resource("styles.json");
        JsonObject collections = resource("collections.json").getAsJsonObject();
        equal(63, RpgConfig.parse(combat, skills, quests, weapons).collections().mobs().size(), "legacy four-file parser gains bundled creature collections");
        equal(22, RpgConfig.parse(combat, skills, quests, weapons, styles).collections().crops().size(), "legacy five-file parser gains bundled crop collections");
        JsonObject custom = collections.deepCopy(); custom.addProperty("firstHuntRequired", false); custom.addProperty("seedChoiceCount", 8); custom.addProperty("cropMatureRequired", 12);
        var parsed = RpgConfig.parse(combat, skills, quests, weapons, styles, custom);
        equal(8, parsed.collections().definition().seedChoiceCount(), "six-file parser uses customized collections");
        check(!parsed.collections().firstHuntRequired(), "collection hunt gate is configurable");
        equal(13, parsed.collections().cropRequirements("minecraft:potato").getFirst().count(), "custom seed and mature counts merge correctly");
        JsonObject unknown = collections.deepCopy(); unknown.addProperty("unrecognized", true);
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, unknown), "collections unknown root field rejects complete config candidate");
        JsonObject missing = collections.deepCopy(); missing.remove("seedChoiceCount");
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, missing), "collections missing required field rejects complete config candidate");
        JsonObject fractional = collections.deepCopy(); fractional.addProperty("cropMatureRequired", 1.25);
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, fractional), "collections fractional count rejected through config parser");
        JsonObject huge = collections.deepCopy(); huge.addProperty("seedChoiceCount", 4097);
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, huge), "collections bounded seed reward");
        JsonObject duplicateSeed = collections.deepCopy(); duplicateSeed.getAsJsonArray("crops").get(1).getAsJsonObject().addProperty("seed", "minecraft:wheat_seeds");
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, duplicateSeed), "collection crop seed must refer to unique cultivated kind");
        JsonObject nestedUnknown = collections.deepCopy(); nestedUnknown.getAsJsonArray("mobs").get(0).getAsJsonObject().getAsJsonArray("drops").get(0).getAsJsonObject().addProperty("equipment", false);
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, nestedUnknown), "collections unknown nested item field rejected");

        Path directory = Files.createTempDirectory("fsrpg-collection-config-test-");
        try {
            String existingSkills = " \n" + RpgConfig.JSON.toJson(skills) + "\n";
            Files.writeString(directory.resolve("skills.json"), existingSkills);
            // This fixture already contains this release's reduced costs; the cost upgrade is complete.
            Files.writeString(directory.resolve("stamina-cost-migration-v1.json"), "{\"schemaVersion\":1,\"staminaCostMultiplier\":0.2}");
            RpgConfig config = new RpgConfig(directory);
            RpgConfig.Definitions seeded = config.reload();
            check(Files.isRegularFile(directory.resolve("collections.json")), "upgrading existing config adds missing collection file");
            equal(skills, JsonParser.parseString(Files.readString(directory.resolve("skills.json"))), "collection seeding preserves existing skill values through field migration");
            equal(existingSkills, Files.readString(directory.resolve("legacy-skill-backup-v1/skills.json")), "collection upgrade backs up original skill formatting");
            equal(63, seeded.collections().mobs().size(), "seeded collections include official death-drop catalog");
            String customText = " \n" + RpgConfig.JSON.toJson(custom) + "\n\t";
            Files.writeString(directory.resolve("collections.json"), customText);
            RpgConfig.Definitions customized = config.reload();
            config.seed();
            equal(customText, Files.readString(directory.resolve("collections.json")), "existing custom collection file is never reseeded");
            RpgConfig.Definitions edited = config.editSkill("sword_left", patch("damage", 12));
            equal(customized.collections().definition(), edited.collections().definition(), "skill editor preserves live customized collections");
            equal(customText, Files.readString(directory.resolve("collections.json")), "skill editor preserves external collection file bytes");
            Files.writeString(directory.resolve("collections.json"), RpgConfig.JSON.toJson(unknown));
            rejects(config::reload, "invalid collection file refuses atomic whole-config reload");
            same(edited, config.current(), "invalid collection reload retains last valid complete definition");

            config.setValidator(candidate -> candidate.collections().validateReferences(id -> !id.startsWith("fsrpg:missing"), id -> !id.startsWith("fsrpg:missing"), id -> !id.startsWith("fsrpg:missing")));
            JsonObject badItem = custom.deepCopy(); badItem.getAsJsonArray("crops").get(0).getAsJsonObject().addProperty("mature", "fsrpg:missing_item");
            Files.writeString(directory.resolve("collections.json"), RpgConfig.JSON.toJson(badItem));
            rejects(config::reload, "unregistered mature crop item rejected before replacing live config");
            same(edited, config.current(), "invalid item reference preserves current configuration");
            JsonObject badEntity = custom.deepCopy(); badEntity.getAsJsonArray("mobs").get(0).getAsJsonObject().addProperty("entity", "fsrpg:missing_entity");
            Files.writeString(directory.resolve("collections.json"), RpgConfig.JSON.toJson(badEntity));
            rejects(config::reload, "unregistered collection entity rejected before replacing live config");
            same(edited, config.current(), "invalid entity reference preserves current configuration");
            JsonObject badPotion = custom.deepCopy(); badPotion.getAsJsonArray("mobs").get(1).getAsJsonObject().getAsJsonArray("drops").get(2).getAsJsonObject().addProperty("potion", "fsrpg:missing_potion");
            Files.writeString(directory.resolve("collections.json"), RpgConfig.JSON.toJson(badPotion));
            rejects(config::reload, "unregistered potion effect rejected before replacing live config");
            same(edited, config.current(), "invalid potion reference preserves current configuration");
            Files.writeString(directory.resolve("collections.json"), customText);
            RpgConfig.Definitions repaired = config.reload();
            equal(8, repaired.collections().definition().seedChoiceCount(), "repaired custom collection file reloads successfully");
            equal(12.0, repaired.skills().get("sword_left").damage(), "collection repair preserves unrelated edited skill");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) throw new IllegalStateException("test cleanup escaped temporary directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static JsonElement resource(String name) throws Exception {
        try (var input = ConfigTest.class.getResourceAsStream("/defaults/" + name)) {
            if (input == null) throw new IllegalStateException("Missing default " + name);
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private static JsonObject lastComboStage(JsonElement styles) {
        JsonArray combo = styles.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("leftCombo");
        return combo.get(combo.size() - 1).getAsJsonObject();
    }
    private static void skillTreeAndArcheryMilestoneValidation() throws Exception {
        JsonElement combat = resource("combat.json"), skills = resource("skills.json"), quests = resource("quests.json"), weapons = resource("weapons.json"), styles = resource("styles.json"), collections = resource("collections.json");
        JsonObject trees = resource("skill_trees.json").getAsJsonObject(), archery = resource("archery.json").getAsJsonObject();
        var defaults = RpgConfig.parse(combat, skills, quests, weapons, styles, collections, trees, archery);
        equal(25, defaults.skillTrees().nodes().size(), "eight-file parser loads all migrated weapon and footwork nodes");
        equal(FootworkRules.defaults(), defaults.skillTrees().rules().footwork(), "eight-file parser carries the legacy footwork rules");
        equal(8L, defaults.skillTrees().nodes().stream().filter(n -> n.tree().equals("footwork")).count(), "eight-file parser loads eight footwork nodes");
        JsonObject customFootwork = trees.deepCopy();
        customFootwork.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("jumpIntervalTicks", 5);
        equal(5, RpgConfig.parse(combat, skills, quests, weapons, styles, collections, customFootwork, archery)
                .skillTrees().rules().footwork().jumpIntervalTicks(), "custom footwork interval accepted and preserved");
        JsonObject invalidFootwork = trees.deepCopy();
        invalidFootwork.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("rabbitDistance", 0);
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, collections, invalidFootwork, archery),
                "invalid footwork distance rejected");
        JsonObject oldFootwork = trees.deepCopy();
        oldFootwork.getAsJsonObject("rules").remove("footwork");
        removeFootworkNodes(oldFootwork);
        var oldParser = RpgConfig.parse(combat, skills, quests, weapons, styles, collections, oldFootwork, archery);
        equal(FootworkRules.defaults(), oldParser.skillTrees().rules().footwork(),
                "pre-0.9 skill-tree file without footwork keeps loading with the legacy defaults");
        equal(17, oldParser.skillTrees().nodes().size(), "pre-0.9 skill-tree file keeps its seventeen nodes");
        String[][] milestones = {{"archery_auto_charge", "autoChargeScore"}, {"archery_trajectory", "trajectoryScore"}, {"archery_scope", "scopeScore"}};
        for (String[] milestone : milestones) {
            String nodeId = milestone[0], rule = milestone[1];
            int original = archery.get(rule).getAsInt();
            JsonObject differentTree = trees.deepCopy();
            treeNode(differentTree, nodeId).getAsJsonObject("requirements").addProperty("score", original + 1);
            rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, collections, differentTree, archery), nodeId + " refuses tree-only milestone change");
            JsonObject differentArchery = archery.deepCopy(); differentArchery.addProperty(rule, original + 1);
            rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles, collections, trees, differentArchery), nodeId + " refuses archery-only milestone change");
            var synchronizedConfig = RpgConfig.parse(combat, skills, quests, weapons, styles, collections, differentTree, differentArchery);
            equal(original + 1, synchronizedConfig.skillTrees().node(nodeId).requirements().score(), nodeId + " accepts matching customized thresholds");
            int configured = switch (rule) {
                case "autoChargeScore" -> synchronizedConfig.archery().autoChargeScore();
                case "trajectoryScore" -> synchronizedConfig.archery().trajectoryScore();
                default -> synchronizedConfig.archery().scopeScore();
            };
            equal(original + 1, configured, nodeId + " keeps combat and unlock threshold synchronized");
        }
    }
    private static void removeFootworkNodes(JsonObject trees) {
        JsonArray nodes = trees.getAsJsonArray("nodes");
        for (int index = nodes.size() - 1; index >= 0; index--)
            if (nodes.get(index).getAsJsonObject().get("tree").getAsString().equals("footwork")) nodes.remove(index);
    }
    private static JsonObject treeNode(JsonObject trees, String id) {
        for (JsonElement value : trees.getAsJsonArray("nodes")) {
            JsonObject node = value.getAsJsonObject();
            if (id.equals(node.get("id").getAsString())) return node;
        }
        throw new IllegalArgumentException("Missing test node " + id);
    }
    private static void legacyDefaultFieldMigration() throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-legacy-default-migration-test-");
        try {
            JsonArray oldSkills = resource("legacy-0.4/skills.json").getAsJsonArray();
            JsonArray oldStyles = resource("legacy-0.4/styles.json").getAsJsonArray();
            JsonObject oldWeapons = resource("legacy-0.4/weapons.json").getAsJsonObject();
            equal(15, oldSkills.size(), "published 0.4 baseline retains its skill catalog");
            equal(5, oldStyles.get(0).getAsJsonObject().getAsJsonArray("leftCombo").size(), "published 0.4 baseline retains its five-stage combo");
            oldSkills.get(0).getAsJsonObject().addProperty("damage", 7.75);
            oldSkills.get(0).getAsJsonObject().addProperty("model", "custom_rapier.fsvoxel");
            oldSkills.get(0).getAsJsonObject().addProperty("action", "custom_slash");
            JsonObject customSkill = oldSkills.get(0).deepCopy().getAsJsonObject(); customSkill.addProperty("id", "custom_slash"); oldSkills.add(customSkill);
            oldStyles.get(0).getAsJsonObject().getAsJsonObject("guard").addProperty("staminaCost", 41);
            String beforeSkills = " \n" + RpgConfig.JSON.toJson(oldSkills) + "\n";
            String beforeStyles = RpgConfig.JSON.toJson(oldStyles);
            Files.writeString(directory.resolve("skills.json"), beforeSkills);
            Files.writeString(directory.resolve("styles.json"), beforeStyles);
            Files.writeString(directory.resolve("weapons.json"), RpgConfig.JSON.toJson(oldWeapons));
            RpgConfig config = new RpgConfig(directory); var migrated = config.reload();
            equal(4, migrated.styles().get("rapier").leftCombo().size(), "untouched old combo migrates to four legacy attacks");
            equal(7.75, migrated.skills().get("sword_left").damage(), "migration preserves tuned damage");
            equal("custom_rapier.fsvoxel", migrated.skills().get("sword_left").model(), "migration preserves custom model");
            equal("custom_slash", migrated.skills().get("sword_left").action(), "migration preserves custom model action pair");
            check(migrated.skills().containsKey("custom_slash"), "migration keeps unknown stable skill ID");
            equal(8.2, migrated.styles().get("rapier").guard().staminaCost(), "recursive migration preserves tuned guard ratio while reducing its cost");
            check(migrated.skills().containsKey("dual_combined") && migrated.styles().containsKey("bow_snipe"), "migration adds combined slash and bow definitions");
            equal(beforeSkills, Files.readString(directory.resolve("legacy-skill-backup-v1/skills.json")), "migration backup keeps exact original skill file");
            equal(beforeStyles, Files.readString(directory.resolve("legacy-skill-backup-v1/styles.json")), "migration backup keeps exact original style file");
            check(Files.isRegularFile(directory.resolve("legacy-skill-migration-v1.json")), "migration writes completion marker");
            String migratedText = Files.readString(directory.resolve("skills.json")); config.reload();
            equal(migratedText, Files.readString(directory.resolve("skills.json")), "completed migration leaves subsequent reload source intact");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) throw new IllegalStateException("test cleanup escaped temporary directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
    private static JsonObject patch(String field, Number value) { JsonObject patch = new JsonObject(); patch.addProperty(field, value); return patch; }
    private static void invalidParse(JsonElement combat, JsonElement skills, JsonElement quests, JsonElement weapons, String message) {
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons), message);
    }
    private static void invalidStyleParse(JsonElement combat, JsonElement skills, JsonElement quests, JsonElement weapons, JsonElement styles, String message) {
        rejects(() -> RpgConfig.parse(combat, skills, quests, weapons, styles), message);
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void rejects(CheckedAction action, String message) {
        checks++;
        try { action.run(); failures.add(message + " (unexpected success)"); } catch (Exception expected) { }
    }
    private static void check(boolean valid, String message) { checks++; if (!valid) failures.add(message); }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message + " (expected " + expected + ", got " + actual + ")"); }
    private static void same(Object expected, Object actual, String message) { check(expected == actual, message); }
}

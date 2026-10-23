package org.fsrpg.config;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.*;
import org.fsrpg.quest.*;
import org.fsrpg.progression.SkillTreeCatalog;
import org.fsrpg.archery.ArcheryRules;

/** Parse and validate every file before replacing the current immutable definitions. */
public final class RpgConfig {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public record Definitions(CombatRules combat, Map<String, SkillDefinition> skills, QuestCatalog quests, WeaponConfig weapons,
                              Map<String, WeaponStyleDefinition> styles, CollectionCatalog collections,
                              SkillTreeCatalog skillTrees, ArcheryRules archery) {
        public Definitions(CombatRules combat, Map<String,SkillDefinition> skills, QuestCatalog quests, WeaponConfig weapons,
                           Map<String,WeaponStyleDefinition> styles, CollectionCatalog collections) {
            this(combat,skills,quests,weapons,styles,collections,SkillTreeCatalog.bundled(JSON),ArcheryRules.defaults());
        }
    }
    private final Path directory;
    private Definitions current;
    private java.util.function.Consumer<Definitions> validator = ignored -> {};
    public RpgConfig(Path directory) { this.directory = directory; }
    public Path directory() { return directory; }
    public void setValidator(java.util.function.Consumer<Definitions> validator) { this.validator = validator; }
    public Definitions current() { if (current == null) throw new IllegalStateException("Configuration has not loaded"); return current; }

    public Definitions reload() throws IOException {
        seed();
        Definitions candidate = parse(read("combat.json"), read("skills.json"), read("quests.json"), read("weapons.json"), read("styles.json"), read("collections.json"),read("skill_trees.json"),read("archery.json"));
        validator.accept(candidate);
        current = candidate;
        return candidate;
    }
    public void seed() throws IOException {
        Files.createDirectories(directory);
        DefenseFlowMigration defenseFlowMigration=DefenseFlowMigration.capture(directory);
        GroundImpulseMigration groundImpulseMigration=GroundImpulseMigration.capture(directory);
        BurstDashMigration burstDashMigration=BurstDashMigration.capture(directory);
        StaminaCombatMigration staminaCombatMigration = StaminaCombatMigration.capture(directory);
        StaminaLevelMigration staminaLevelMigration = StaminaLevelMigration.capture(directory);
        StaminaCostMigration staminaMigration = StaminaCostMigration.capture(directory);
        StaminaRegenMigration staminaRegenMigration = StaminaRegenMigration.capture(directory);
        for (String name : List.of("combat.json", "skills.json", "quests.json", "weapons.json", "collections.json", "skill_trees.json", "archery.json", "guard_cues.json")) {
            Path target = directory.resolve(name);
            if (!Files.exists(target)) {
                try (InputStream in = RpgConfig.class.getResourceAsStream("/defaults/" + name)) {
                    if (in == null) throw new IOException("Missing bundled default " + name);
                    if(name.equals("skill_trees.json")) {
                        JsonElement trees=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
                        atomicWrite(target,JSON.toJson(RapierFencingMigration.compatibleInitialTrees(read("skills.json"),trees)));
                    }else Files.copy(in, target);
                }
            }
        }
        // A new optional file adapts to old/custom skill catalogs without editing those catalogs.
        Path styles = directory.resolve("styles.json");
        if (!Files.exists(styles)) atomicWrite(styles, JSON.toJson(compatibleStyles(read("skills.json"), read("weapons.json"))));
        if(burstDashMigration!=null)burstDashMigration.apply();
        if(groundImpulseMigration!=null)groundImpulseMigration.apply();
        if (staminaCombatMigration != null) staminaCombatMigration.apply();
        LegacySkillMigration.migrate(directory);
        if (staminaMigration != null) staminaMigration.apply();
        RapierTargetLockMigration.migrate(directory);
        if (staminaRegenMigration != null) staminaRegenMigration.apply();
        SpearThrustTimingMigration.migrate(directory);
        WeaponStaminaBalanceMigration.migrate(directory);
        PerfectGuardMigration.migrate(directory);
        GuardCueMigration.migrate(directory);
        if(defenseFlowMigration!=null)defenseFlowMigration.apply();
        if (staminaLevelMigration != null) staminaLevelMigration.apply();
        RapierFencingMigration.migrate(directory);
        RapierTechniquesMigration.migrate(directory);
        FootworkSkillMigration.migrate(directory);
        RapierRenewalMigration.migrate(directory);
    }
    private JsonElement read(String name) throws IOException {
        Path path = directory.resolve(name);
        if (Files.size(path) > 512 * 1024) throw new IOException(name + " exceeds 512 KiB");
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in);
        } catch (RuntimeException failure) { throw new IOException(name + ": " + failure.getMessage(), failure); }
    }
    public static Definitions parse(JsonElement rulesJson, JsonElement skillsJson, JsonElement questsJson, JsonElement weaponsJson) {
        return parse(rulesJson, skillsJson, questsJson, weaponsJson, compatibleStyles(skillsJson, weaponsJson));
    }
    public static Definitions parse(JsonElement rulesJson, JsonElement skillsJson, JsonElement questsJson, JsonElement weaponsJson, JsonElement stylesJson) {
        return parse(rulesJson, skillsJson, questsJson, weaponsJson, stylesJson, JSON.toJsonTree(CollectionCatalog.bundled(JSON).definition()));
    }
    public static Definitions parse(JsonElement rulesJson, JsonElement skillsJson, JsonElement questsJson, JsonElement weaponsJson, JsonElement stylesJson, JsonElement collectionsJson) {
        requireFields(rulesJson.getAsJsonObject(), CombatRules.class);
        CombatRules rules = JSON.fromJson(rulesJson, CombatRules.class);
        JsonArray skillsArray = skillsJson.getAsJsonArray();
        if (skillsArray.isEmpty() || skillsArray.size() > 128) throw new IllegalArgumentException("skills must contain 1..128 entries");
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();
        for (JsonElement element : skillsArray) {
            requireFields(element.getAsJsonObject(), SkillDefinition.class);
            SkillDefinition skill = JSON.fromJson(element, SkillDefinition.class);
            if (skill.id().length() > 128 || skill.label().length() > 256 || skill.model().length() > 256 || skill.action().length() > 128)
                throw new IllegalArgumentException("Skill text exceeds maximum length: " + skill.id());
            if (skills.putIfAbsent(skill.id(), skill) != null) throw new IllegalArgumentException("Duplicate skill id: " + skill.id());
        }
        JsonArray questsArray = questsJson.getAsJsonArray();
        if (questsArray.size() > 512) throw new IllegalArgumentException("quests exceeds 512 definitions");
        for (JsonElement quest : questsArray) recordFields(quest.getAsJsonObject(), QuestDefinition.class, false);
        QuestCatalog catalog = QuestCatalog.of(Arrays.asList(JSON.fromJson(questsArray, QuestDefinition[].class)));
        recordFields(weaponsJson.getAsJsonObject(), WeaponConfig.class, true);
        WeaponConfig weapons = JSON.fromJson(weaponsJson, WeaponConfig.class);
        for (SkillDefinition skill : skills.values()) if (!skill.weapon().equals("any") && !weapons.groups().containsKey(skill.weapon()))
            throw new IllegalArgumentException("Skill " + skill.id() + " references unknown weapon group " + skill.weapon());
        for (QuestDefinition quest : catalog.definitions()) for (QuestDefinition.Objective objective : quest.objectives()) {
            if (objective.type() == QuestDefinition.ObjectiveType.USE_SKILL && !skills.containsKey(objective.target()))
                throw new IllegalArgumentException("Quest " + quest.id() + " references unknown skill " + objective.target());
        }
        JsonArray stylesArray = stylesJson.getAsJsonArray();
        if (stylesArray.isEmpty() || stylesArray.size() > 128) throw new IllegalArgumentException("styles must contain 1..128 entries");
        Map<String, WeaponStyleDefinition> styles = new LinkedHashMap<>();
        for (JsonElement element : stylesArray) {
            requireFields(element.getAsJsonObject(), WeaponStyleDefinition.class);
            WeaponStyleDefinition style = JSON.fromJson(element, WeaponStyleDefinition.class);
            if (style.id().length() > 128 || style.label().length() > 256 || style.weaponGroup().length() > 128)
                throw new IllegalArgumentException("Style text exceeds maximum length: " + style.id());
            if (styles.putIfAbsent(style.id(), style) != null) throw new IllegalArgumentException("Duplicate style id: " + style.id());
            if (style.enabled() && !style.weaponGroup().equals("any") && !weapons.groups().containsKey(style.weaponGroup()))
                throw new IllegalArgumentException("Style " + style.id() + " references unknown weapon group " + style.weaponGroup());
            style.validateSkills(skills);
            for (var stage : style.leftCombo()) validateStyleWeapon(style, skills.get(stage.skillId()));
            if (!style.rightSkill().isEmpty()) validateStyleWeapon(style, skills.get(style.rightSkill()));
        }
        if (styles.values().stream().noneMatch(WeaponStyleDefinition::enabled)) throw new IllegalArgumentException("At least one weapon style must be enabled");
        if (JSON.toJson(skillsArray).length() + JSON.toJson(questsArray).length() + JSON.toJson(stylesArray).length() > 180_000)
            throw new IllegalArgumentException("Definitions exceed the network snapshot size limit");
        CollectionCatalog collections = CollectionCatalog.parse(collectionsJson, JSON);
        if (JSON.toJson(collectionsJson).length() > 60_000) throw new IllegalArgumentException("Collections exceed the network snapshot size limit");
        return new Definitions(rules, Collections.unmodifiableMap(skills), catalog, weapons, Collections.unmodifiableMap(styles), collections);
    }
    private static void validateStyleWeapon(WeaponStyleDefinition style, SkillDefinition skill) {
        if (!skill.weapon().equals("any") && !skill.weapon().equals(style.weaponGroup()))
            throw new IllegalArgumentException("Style " + style.id() + " and skill " + skill.id() + " use different weapon groups");
    }
    public static Definitions parse(JsonElement rules,JsonElement skills,JsonElement quests,JsonElement weapons,
                                    JsonElement styles,JsonElement collections,JsonElement trees,JsonElement archery) {
        Definitions base=parse(rules,skills,quests,weapons,styles,collections);
        SkillTreeCatalog catalog=SkillTreeCatalog.parse(trees,JSON);
        catalog.validateReferences(base.skills()::containsKey,base.styles()::containsKey);
        requireFields(archery.getAsJsonObject(),ArcheryRules.class);
        ArcheryRules bowRules=JSON.fromJson(archery,ArcheryRules.class);
        for(var node:catalog.nodes()) {
            int configured=switch(node.effect()) {
                case BOW_AUTO_CHARGE->bowRules.autoChargeScore();
                case BOW_TRAJECTORY->bowRules.trajectoryScore();
                case BOW_SCOPE->bowRules.scopeScore();
                default->-1;
            };
            if(configured>=0&&node.requirements().score()!=configured)
                throw new IllegalArgumentException("archery milestone score differs from skill tree node "+node.id());
        }
        if(JSON.toJson(trees).length()>40000)throw new IllegalArgumentException("Skill trees exceed snapshot budget");
        return new Definitions(base.combat(),base.skills(),base.quests(),base.weapons(),base.styles(),base.collections(),catalog,bowRules);
    }
    /** Seed only the absent style file; keep legacy and externally edited source files intact. */
    private static JsonArray compatibleStyles(JsonElement skillsJson, JsonElement weaponsJson) {
        JsonArray defaults;
        try (InputStream in = RpgConfig.class.getResourceAsStream("/defaults/styles.json")) {
            if (in == null) throw new IllegalArgumentException("Missing bundled styles.json");
            defaults = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot read default styles", failure); }
        Map<String, JsonObject> skills = new LinkedHashMap<>();
        for (JsonElement element : skillsJson.getAsJsonArray()) {
            JsonObject skill = element.getAsJsonObject();
            skills.put(skill.get("id").getAsString(), skill);
        }
        for (JsonElement element : defaults) {
            JsonObject style = element.getAsJsonObject();
            JsonArray adapted = new JsonArray();
            for (JsonElement stage : style.getAsJsonArray("leftCombo")) {
                JsonObject skill = skills.get(stage.getAsJsonObject().get("skillId").getAsString());
                if (skill != null && "LEFT".equals(skill.get("input").getAsString())) adapted.add(stage.deepCopy());
            }
            if (style.get("enabled").getAsBoolean() && adapted.isEmpty()) {
                for (JsonObject skill : skills.values()) {
                    if ("LEFT".equals(skill.get("input").getAsString()) && skill.get("weapon").equals(style.get("weaponGroup"))) {
                        JsonObject stage = new JsonObject(); stage.addProperty("skillId", skill.get("id").getAsString());
                        for (String field : List.of("gateStartTick", "gateEndTick", "chainTick", "bufferTicks")) stage.addProperty(field, 0);
                        adapted.add(stage); break;
                    }
                }
            }
            style.add("leftCombo", adapted);
            if(style.has("controls")) {
                boolean missing=false;
                for(var field:style.getAsJsonObject("controls").entrySet())
                    if(field.getKey().endsWith("Skill")||field.getKey().endsWith("Left")||field.getKey().endsWith("Right")) {
                        String id=field.getValue().getAsString();if(!id.isEmpty()&&!skills.containsKey(id))missing=true;
                    }
                if(missing) {style.remove("controls");style.addProperty("enabled",false);}
            }
            if (!skills.containsKey(style.get("rightSkill").getAsString())) style.addProperty("rightSkill", "");
            if (style.has("techniques")) {
                var techniques = style.getAsJsonObject("techniques");
                for (String name : List.of("riposte", "reprise")) {
                    if (!skills.containsKey(techniques.get(name + "Skill").getAsString())) {
                        techniques.addProperty(name + "Skill", ""); techniques.addProperty(name + "WindowTicks", 0);
                    }
                }
            }
            if (style.get("enabled").getAsBoolean() && (adapted.isEmpty()
                    || !weaponsJson.getAsJsonObject().getAsJsonObject("groups").has(style.get("weaponGroup").getAsString()))) {
                style.addProperty("enabled", false);
            }
        }
        return defaults;
    }
    private static void requireFields(JsonObject obj, Class<?> record) {
        recordFields(obj, record, true);
    }
    private static void recordFields(JsonObject obj, Class<?> record, boolean required) {
        Set<String> expected = new HashSet<>();
        for (var component : record.getRecordComponents()) {
            expected.add(component.getName());
            boolean optionalStyleExtension = record == WeaponStyleDefinition.class
                    && Set.of("heavyGuard", "techniques", "cancels", "controls").contains(component.getName());
            boolean optionalLegacyGate = record == WeaponStyleDefinition.ComboStage.class && !component.getName().equals("skillId");
            boolean optionalTargetLock = record == CombatRules.class && Set.of("rapierTargetLock","perfectGuardCounter","staminaLevelScaling","staminaCombatRules","burstDash","spearSwipeKnockbackMultiplier","weaponClash").contains(component.getName()) && !obj.has(component.getName());
            boolean optionalPartialShot = record == ArcheryRules.class && component.getName().equals("partialShotStaminaMultiplier") && !obj.has(component.getName());
            boolean optionalComboReset = record == org.fsrpg.combat.WeaponControls.class && component.getName().equals("comboResetTicks") && !obj.has(component.getName());
            boolean optionalImpulseSpeed=record==BurstDashRules.class&&component.getName().equals("initialSpeed")&&!obj.has(component.getName());
            if (required && !optionalStyleExtension && !optionalLegacyGate && !optionalTargetLock && !optionalPartialShot && !optionalComboReset && !optionalImpulseSpeed && (!obj.has(component.getName()) || obj.get(component.getName()).isJsonNull()))
                throw new IllegalArgumentException(record.getSimpleName() + " missing field " + component.getName());
            if (obj.has(component.getName()) && !obj.get(component.getName()).isJsonNull())
                checkType(obj.get(component.getName()), component.getGenericType(), record.getSimpleName() + "." + component.getName());
        }
        for (String key : obj.keySet()) if (!expected.contains(key)) throw new IllegalArgumentException("Unknown " + record.getSimpleName() + " field " + key);
    }
    private static void checkType(JsonElement value, java.lang.reflect.Type type, String path) {
        if (type instanceof java.lang.reflect.ParameterizedType generic) {
            if (generic.getRawType() == List.class) {
                if (!value.isJsonArray()) throw new IllegalArgumentException(path + " must be an array");
                for (JsonElement item : value.getAsJsonArray()) checkType(item, generic.getActualTypeArguments()[0], path + "[]");
            } else if (generic.getRawType() == Map.class) {
                if (!value.isJsonObject()) throw new IllegalArgumentException(path + " must be an object");
                for (var item : value.getAsJsonObject().entrySet()) checkType(item.getValue(), generic.getActualTypeArguments()[1], path + "." + item.getKey());
            }
            return;
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz.isRecord()) {
            if (!value.isJsonObject()) throw new IllegalArgumentException(path + " must be an object");
            boolean completeStyleRules = clazz == WeaponStyleDefinition.ComboStage.class || clazz == WeaponStyleDefinition.GuardRules.class
                    || clazz == WeaponStyleDefinition.Techniques.class || clazz == WeaponStyleDefinition.CancelRules.class || clazz == org.fsrpg.combat.WeaponControls.class
                    || clazz == org.fsrpg.combat.RapierTargeting.Rules.class || clazz == CombatRules.PerfectGuardCounter.class
                    || clazz == StaminaLevelScaling.class || clazz == StaminaCombatRules.class || clazz == BurstDashRules.class
                    || clazz == WeaponClashRules.class;
            recordFields(value.getAsJsonObject(), clazz, completeStyleRules); return;
        }
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(path + " must be a scalar");
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if ((clazz == String.class || clazz.isEnum()) && !primitive.isString()) throw new IllegalArgumentException(path + " must be text");
        if ((clazz == boolean.class || clazz == Boolean.class) && !primitive.isBoolean()) throw new IllegalArgumentException(path + " must be boolean");
        if (clazz == int.class || clazz == Integer.class || clazz == double.class || clazz == Double.class) {
            if (!primitive.isNumber() || !Double.isFinite(primitive.getAsDouble())) throw new IllegalArgumentException(path + " must be a finite number");
            if (clazz == int.class || clazz == Integer.class) {
                try { primitive.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException error) { throw new IllegalArgumentException(path + " must be a 32-bit integer"); }
            }
        }
    }
    /** Server permission must be checked before calling. A failed patch never changes live config. */
    public Definitions editSkill(String id, JsonObject changes) throws IOException {
        if (!current().skills().containsKey(id)) throw new IllegalArgumentException("Unknown skill: " + id);
        if (!read("skills.json").equals(JSON.toJsonTree(current().skills().values())))
            throw new IOException("skills.json 已被外部修改，请先 /fsrpg reload，再重新编辑");
        Set<String> editable = Set.of("windupTicks", "activeTicks", "recoveryTicks", "cooldownTicks", "damage", "reach", "arcDegrees", "knockback", "staminaCost", "spiritCost");
        JsonArray patched = JSON.toJsonTree(current().skills().values()).getAsJsonArray();
        for (var change : changes.entrySet()) {
            if (!editable.contains(change.getKey())) throw new IllegalArgumentException("Field is not editable in this window: " + change.getKey());
            if (!change.getValue().isJsonPrimitive() || !change.getValue().getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Expected number for " + change.getKey());
            double value = change.getValue().getAsDouble();
            if (!Double.isFinite(value) || (change.getKey().endsWith("Ticks") && value != Math.rint(value)))
                throw new IllegalArgumentException("Expected finite " + (change.getKey().endsWith("Ticks") ? "integer " : "") + "for " + change.getKey());
            for (JsonElement skill : patched) if (skill.getAsJsonObject().get("id").getAsString().equals(id)) skill.getAsJsonObject().add(change.getKey(), change.getValue());
        }
        Definitions parsed = parse(JSON.toJsonTree(current().combat()), patched, JSON.toJsonTree(current().quests().definitions()), JSON.toJsonTree(current().weapons()), JSON.toJsonTree(current().styles().values()), JSON.toJsonTree(current().collections().definition()));
        current().skillTrees().validateReferences(parsed.skills()::containsKey,parsed.styles()::containsKey);
        Definitions candidate=new Definitions(parsed.combat(),parsed.skills(),parsed.quests(),parsed.weapons(),parsed.styles(),parsed.collections(),current().skillTrees(),current().archery());
        validator.accept(candidate);
        Path target = directory.resolve("skills.json");
        Files.copy(target, directory.resolve("skills.json.bak"), StandardCopyOption.REPLACE_EXISTING);
        atomicWrite(target, JSON.toJson(patched));
        current = candidate;
        return candidate;
    }
    public static void atomicWrite(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, text + "\n", StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}

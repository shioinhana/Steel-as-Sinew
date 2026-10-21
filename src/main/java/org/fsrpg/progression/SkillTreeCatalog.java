package org.fsrpg.progression;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Server-owned, strict configuration for the migrated legacy cultivation trees. */
public final class SkillTreeCatalog {
    public static final int SCHEMA_VERSION = 1;
    public enum Effect {
        ROOT_RAPIER, ROOT_DUAL, ROOT_BOW, RAPIER_THRUST, RAPIER_PARRY, RAPIER_EXECUTION,
        DUAL_COMBO_CAP, BOW_AUTO_CHARGE, BOW_TRAJECTORY, BOW_SCOPE,
        RU_SUO, NIE_SHI, YAN_XIA_DIAN, CHUAN_TANG_FENG,
        RABBIT, FLYING_SWAN, CLOUD_STEP, CLOUD_STEP_II, CLOUD_STEP_III,
        LIGHT_BODY, LIGHT_BODY_II, LIGHT_BODY_III
    }
    public record Cost(int mind, int body, int footwork) {
        public Cost { points(mind); points(body); points(footwork); }
    }
    public record Requirements(int mind, int body, int footwork, int score, boolean trial, boolean dragon) {
        public Requirements { points(mind); points(body); points(footwork); points(score); }
    }
    public record Node(String id, String tree, String label, String description, boolean automatic,
                       Cost cost, Requirements requirements, List<String> prerequisites,
                       String exclusiveGroup, Effect effect, int value, List<String> skills) {
        public Node {
            validId(id); validTree(tree); label = visible(label, 80); description = visible(description, 1200);
            Objects.requireNonNull(cost); Objects.requireNonNull(requirements); Objects.requireNonNull(effect);
            prerequisites = List.copyOf(prerequisites); skills = List.copyOf(skills);
            if (prerequisites.size() > 16 || skills.size() > 64) throw bad("node reference list exceeds limit");
            prerequisites.forEach(SkillTreeCatalog::validId); skills.forEach(SkillTreeCatalog::validId);
            if (!exclusiveGroup.isEmpty()) validId(exclusiveGroup);
            if (effect == Effect.DUAL_COMBO_CAP) integer(value, 1, 12, "combo cap");
            else if (value != 0) throw bad("only combo-cap effects accept a value");
            if (automatic && (cost.mind() != 0 || cost.body() != 0 || cost.footwork() != 0 || !exclusiveGroup.isEmpty()))
                throw bad("automatic nodes cannot cost points or be exclusive choices");
            String branch = switch (effect) {
                case RU_SUO, NIE_SHI -> "archery_150";
                case YAN_XIA_DIAN, CHUAN_TANG_FENG -> "archery_250";
                case RABBIT, FLYING_SWAN -> "footwork_style";
                default -> "";
            };
            if (!exclusiveGroup.equals(branch)) throw bad("incorrect exclusive group for effect: " + effect);
            boolean manual = !branch.isEmpty() || isFootwork(effect) || Set.of(Effect.ROOT_RAPIER, Effect.ROOT_DUAL, Effect.ROOT_BOW).contains(effect);
            if (automatic == manual) throw bad("effect has incorrect manual/automatic learning mode: " + effect);
            String expected = switch (effect) {
                case ROOT_RAPIER, RAPIER_THRUST, RAPIER_PARRY, RAPIER_EXECUTION -> "rapier";
                case ROOT_DUAL, DUAL_COMBO_CAP -> "dual_blade";
                case RABBIT, FLYING_SWAN, CLOUD_STEP, CLOUD_STEP_II, CLOUD_STEP_III,
                     LIGHT_BODY, LIGHT_BODY_II, LIGHT_BODY_III -> "footwork";
                default -> "bow";
            };
            if (!tree.equals(expected)) throw bad("effect does not belong to node tree: " + id);
        }
    }
    public record TrainingRules(long mindActiveTicksPerPoint, int mindHungerPerPoint, double bodyUnlockHp,
            double bodyMetersPerPoint, int bodyMinedPerPoint, int footworkUnlockBodyTotal,
            double footworkMetersPerPoint, int footworkCap, List<Integer> mindHealthThresholds,
            double mindHealthPerLevel, int regenDoubleMind, int regenLowFoodMind, int regenLowFoodLevel,
            List<Integer> bodyDamageThresholds, double bodyDamagePerLevel,
            List<Integer> bodyAttackSpeedThresholds, double bodyAttackSpeedPerLevelSeconds,
            double minAttackIntervalSeconds, double bodyBowFactorPerLevel,
            List<Integer> bodyMiningThresholds, double bodyMiningFactorPerLevel,
            double footworkMaxSpeedBonus) {
        public TrainingRules {
            if (mindActiveTicksPerPoint < 1 || mindActiveTicksPerPoint > 1_000_000_000L) throw bad("invalid active tick interval");
            integer(mindHungerPerPoint, 1, 1000, "hunger interval"); range(bodyUnlockHp, .01, 100_000, "body unlock HP");
            range(bodyMetersPerPoint, .01, 1_000_000, "body metres interval"); integer(bodyMinedPerPoint, 1, 1_000_000, "mining interval");
            points(footworkUnlockBodyTotal); range(footworkMetersPerPoint, .01, 1_000_000, "footwork metres interval");
            integer(footworkCap, 1, 1_000_000, "footwork cap");
            mindHealthThresholds = thresholds(mindHealthThresholds); bodyDamageThresholds = thresholds(bodyDamageThresholds);
            bodyAttackSpeedThresholds = thresholds(bodyAttackSpeedThresholds); bodyMiningThresholds = thresholds(bodyMiningThresholds);
            range(mindHealthPerLevel, 0, 100, "health bonus"); points(regenDoubleMind); points(regenLowFoodMind);
            integer(regenLowFoodLevel, 1, 20, "regen food"); range(bodyDamagePerLevel, 0, 100, "damage bonus");
            range(bodyAttackSpeedPerLevelSeconds, 0, 10, "attack interval reduction"); range(minAttackIntervalSeconds, .05, 10, "minimum attack interval");
            range(bodyBowFactorPerLevel, .01, 1, "bow draw factor"); range(bodyMiningFactorPerLevel, .01, 1, "mining time factor");
            range(footworkMaxSpeedBonus, 0, 10, "movement bonus");
        }
    }
    public record RapierRules(int windupTicks, int activeTicks, int recoveryTicks, int cooldownTicks,
            int fullChargeTicks, int guardBreakCooldownTicks, double lungeSpeedMps,
            double baseSlashDamage, double thrustMultiplier, double parriedMultiplier,
            double executionMissingHealthShare, double range) {
        public RapierRules {
            ticks(windupTicks); ticks(activeTicks); ticks(recoveryTicks); ticks(cooldownTicks);
            ticks(fullChargeTicks); ticks(guardBreakCooldownTicks); SkillTreeCatalog.range(lungeSpeedMps, 0, 100, "lunge speed");
            SkillTreeCatalog.range(baseSlashDamage, 0, 1000, "rapier slash damage"); SkillTreeCatalog.range(thrustMultiplier, 0, 100, "thrust multiplier");
            SkillTreeCatalog.range(parriedMultiplier, 0, 100, "parry multiplier"); SkillTreeCatalog.range(executionMissingHealthShare, 0, 1, "execution share");
            SkillTreeCatalog.range(range, .1, 64, "rapier range");
        }
        public double thrustDamage() { return baseSlashDamage * thrustMultiplier; }
        public double parriedDamage() { return baseSlashDamage * parriedMultiplier; }
    }
    public record DualRules(double holdMoveBonus, double swingMovePenalty, double layerSwingBonus,
                            int decayTicks, int linkedSlashCooldownTicks) {
        public DualRules {
            range(holdMoveBonus, 0, 10, "dual hold bonus"); range(swingMovePenalty, 0, .99, "dual swing penalty");
            range(layerSwingBonus, 0, 10, "combo layer bonus"); ticks(decayTicks); ticks(linkedSlashCooldownTicks);
        }
    }
    public record LongTermRules(boolean enabled, double baseDailyDecay, double masteryDecay,
            int masteryDecayDays, int initialAptitudeMin, int initialAptitudeMax,
            int bossInsightBonus, int bossInsightBonusCap, int efficiencyPercentCap) {
        public LongTermRules {
            range(baseDailyDecay, 0, 1, "daily decay"); range(masteryDecay, 0, 1, "mastery decay");
            integer(masteryDecayDays, 1, 3650, "mastery decay days"); integer(initialAptitudeMin, -99, 100, "minimum aptitude");
            integer(initialAptitudeMax, initialAptitudeMin, 100, "maximum aptitude"); integer(bossInsightBonus, 0, 200, "boss insight bonus");
            integer(bossInsightBonusCap, 0, 1000, "boss insight cap"); integer(efficiencyPercentCap, 0, 1000, "efficiency cap");
        }
    }
    public record Rules(TrainingRules training, RapierRules rapier, DualRules dual, LongTermRules longTerm, FootworkRules footwork) {
        public Rules { Objects.requireNonNull(training); Objects.requireNonNull(rapier); Objects.requireNonNull(dual); Objects.requireNonNull(longTerm); Objects.requireNonNull(footwork); }
        public Rules(TrainingRules training, RapierRules rapier, DualRules dual, LongTermRules longTerm) {
            this(training, rapier, dual, longTerm, FootworkRules.defaults());
        }
    }
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:-]{1,100}");
    private final Rules rules;
    private final List<Node> nodes;
    private final Map<String, Node> byId;
    public SkillTreeCatalog(Rules rules, List<Node> nodes) {
        this.rules = Objects.requireNonNull(rules); this.nodes = List.copyOf(nodes);
        if (nodes.isEmpty() || nodes.size() > 256) throw bad("skill tree needs 1..256 nodes");
        Map<String, Node> map = new LinkedHashMap<>(); Set<Effect> unique = EnumSet.noneOf(Effect.class);
        for (Node node : nodes) {
            if (map.put(node.id(), node) != null) throw bad("duplicate skill-tree node: " + node.id());
            if (node.effect() != Effect.DUAL_COMBO_CAP && !unique.add(node.effect())) throw bad("duplicate node effect: " + node.effect());
        }
        for (Node node : nodes) for (String dependency : node.prerequisites()) {
            if (!map.containsKey(dependency)) throw bad("unknown node prerequisite: " + dependency);
            if (!map.get(dependency).tree().equals(node.tree())) throw bad("cross-tree prerequisite: " + dependency);
        }
        for (Effect root : List.of(Effect.ROOT_RAPIER, Effect.ROOT_DUAL, Effect.ROOT_BOW)) {
            if (!unique.contains(root)) throw bad("missing root effect: " + root);
            Node node = nodes.stream().filter(n -> n.effect() == root).findFirst().orElseThrow();
            if (node.automatic() || !node.prerequisites().isEmpty() || !node.exclusiveGroup().isEmpty()) throw bad("weapon roots must be manual and unconditional");
        }
        byId = Collections.unmodifiableMap(map);
        Set<String> visited = new HashSet<>(); for (Node node : nodes) visit(node.id(), new HashSet<>(), visited);
        for (Node node : nodes) if (!isFootwork(node.effect()) && !Set.of(Effect.ROOT_RAPIER, Effect.ROOT_DUAL, Effect.ROOT_BOW).contains(node.effect()) && !reachesRoot(node))
            throw bad("node must depend on its learned weapon root: " + node.id());
    }
    public Rules rules() { return rules; }
    public List<Node> nodes() { return nodes; }
    public Node node(String id) { Node n = byId.get(id); if (n == null) throw bad("unknown skill-tree node: " + id); return n; }
    public Optional<Node> find(String id) { return Optional.ofNullable(byId.get(id)); }
    public void validateReferences(Predicate<String> skillExists, Predicate<String> styleExists) {
        for (Node node : nodes) {
            if (node.tree().equals("bow")) {
                if (!styleExists.test("bow_rapid") || !styleExists.test("bow_snipe")) throw bad("bow tree needs bow_rapid and bow_snipe styles");
            } else if (!node.tree().equals("footwork") && !styleExists.test(node.tree())) throw bad("unknown node style: " + node.tree());
            for (String skill : node.skills()) if (!skillExists.test(skill)) throw bad("unknown node skill: " + skill);
        }
    }
    public static SkillTreeCatalog bundled(Gson gson) {
        try (InputStream stream = SkillTreeCatalog.class.getResourceAsStream("/defaults/skill_trees.json")) {
            if (stream == null) throw bad("missing bundled skill_trees.json");
            return parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)), gson);
        } catch (IOException error) { throw new IllegalStateException("cannot read bundled skill trees", error); }
    }
    public static SkillTreeCatalog parse(JsonElement json, Gson gson) {
        Objects.requireNonNull(gson); JsonObject root = object(json, "skill trees");
        fields(root, Set.of("schemaVersion", "rules", "nodes"));
        if (integer(root.get("schemaVersion"), "schemaVersion") != SCHEMA_VERSION) throw bad("unsupported skill tree schema");
        JsonObject r = object(root.get("rules"), "rules");
        Set<String> ruleFields = new HashSet<>(Set.of("training", "rapier", "dual", "longTerm"));
        if (r.has("footwork")) ruleFields.add("footwork"); fields(r, ruleFields);
        Rules rules = new Rules(record(r.get("training"), TrainingRules.class, gson), record(r.get("rapier"), RapierRules.class, gson),
                record(r.get("dual"), DualRules.class, gson), record(r.get("longTerm"), LongTermRules.class, gson),
                r.has("footwork") ? record(r.get("footwork"), FootworkRules.class, gson) : FootworkRules.defaults());
        List<Node> nodes = new ArrayList<>(); JsonArray array = array(root.get("nodes"), "nodes");
        if (array.size() > 256) throw bad("too many nodes");
        for (JsonElement element : array) {
            JsonObject n = object(element, "node"); fields(n, Set.of("id", "tree", "label", "description", "automatic", "cost", "requirements", "prerequisites", "exclusiveGroup", "effect", "value", "skills"));
            Effect effect; try { effect = Effect.valueOf(string(n.get("effect"), "effect")); } catch (IllegalArgumentException invalid) { throw bad("unknown node effect"); }
            nodes.add(new Node(string(n.get("id"), "id"), string(n.get("tree"), "tree"), string(n.get("label"), "label"),
                    string(n.get("description"), "description"), bool(n.get("automatic"), "automatic"), record(n.get("cost"), Cost.class, gson),
                    record(n.get("requirements"), Requirements.class, gson), strings(n.get("prerequisites")), string(n.get("exclusiveGroup"), "exclusiveGroup"),
                    effect, integer(n.get("value"), "value"), strings(n.get("skills"))));
        }
        return new SkillTreeCatalog(rules, nodes);
    }
    private void visit(String id, Set<String> path, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!path.add(id)) throw bad("cyclic skill tree prerequisite: " + id);
        for (String prerequisite : node(id).prerequisites()) visit(prerequisite, path, visited);
        path.remove(id); visited.add(id);
    }
    private boolean reachesRoot(Node node) {
        if (Set.of(Effect.ROOT_RAPIER, Effect.ROOT_DUAL, Effect.ROOT_BOW).contains(node.effect())) return true;
        return node.prerequisites().stream().anyMatch(id -> reachesRoot(byId.get(id)));
    }
    private static <T> T record(JsonElement element, Class<T> type, Gson gson) {
        JsonObject object = object(element, type.getSimpleName()); Set<String> keys = new LinkedHashSet<>();
        for (var component : type.getRecordComponents()) {
            keys.add(component.getName()); JsonElement value = object.get(component.getName());
            Class<?> fieldType = component.getType();
            if (fieldType == boolean.class) bool(value, component.getName());
            else if (fieldType == int.class || fieldType == long.class) integral(value, component.getName());
            else if (fieldType == double.class) number(value, component.getName());
            else if (fieldType == List.class) { for (JsonElement threshold : array(value, component.getName())) integer(threshold, "threshold"); }
        }
        fields(object, keys);
        try { return gson.fromJson(object, type); } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid " + type.getSimpleName(), invalid); }
    }
    private static JsonObject object(JsonElement e, String name) { if (e == null || !e.isJsonObject()) throw bad(name + " must be an object"); return e.getAsJsonObject(); }
    private static JsonArray array(JsonElement e, String name) { if (e == null || !e.isJsonArray()) throw bad(name + " must be an array"); return e.getAsJsonArray(); }
    private static void fields(JsonObject o, Set<String> keys) { if (!o.keySet().equals(keys)) throw bad("unknown or missing fields: " + o.keySet() + ", expected " + keys); }
    private static String string(JsonElement e, String name) { if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw bad(name + " must be a string"); return e.getAsString(); }
    private static boolean bool(JsonElement e, String name) { if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw bad(name + " must be boolean"); return e.getAsBoolean(); }
    private static double number(JsonElement e, String name) { if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw bad(name + " must be numeric"); double value = e.getAsDouble(); if (!Double.isFinite(value)) throw bad(name + " must be finite"); return value; }
    private static long integral(JsonElement e, String name) { number(e, name); try { return e.getAsBigDecimal().longValueExact(); } catch (ArithmeticException invalid) { throw bad(name + " must be an integer"); } }
    private static int integer(JsonElement e, String name) { long value = integral(e, name); if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw bad(name + " overflows integer"); return (int) value; }
    private static List<String> strings(JsonElement e) { List<String> result = new ArrayList<>(); for (JsonElement entry : array(e, "reference list")) result.add(string(entry, "reference")); if (new HashSet<>(result).size() != result.size()) throw bad("duplicate references"); return result; }
    private static List<Integer> thresholds(List<Integer> values) { values = List.copyOf(values); if (values.isEmpty() || values.size() > 32) throw bad("invalid threshold count"); int previous = -1; for (int value : values) { points(value); if (value <= previous) throw bad("thresholds must increase"); previous = value; } return values; }
    static void validId(String id) { if (id == null || !ID.matcher(id).matches()) throw bad("invalid ID: " + id); }
    public static boolean isFootwork(Effect effect) {
        return switch (effect) {
            case RABBIT, FLYING_SWAN, CLOUD_STEP, CLOUD_STEP_II, CLOUD_STEP_III,
                 LIGHT_BODY, LIGHT_BODY_II, LIGHT_BODY_III -> true;
            default -> false;
        };
    }
    public static boolean isFootworkTechnique(Effect effect) {
        return isFootwork(effect) && effect != Effect.RABBIT && effect != Effect.FLYING_SWAN;
    }
    private static void validTree(String id) { if (!Set.of("rapier", "dual_blade", "bow", "footwork").contains(id)) throw bad("invalid tree: " + id); }
    private static String visible(String value, int length) { if (value == null || value.isBlank() || value.length() > length) throw bad("invalid visible text"); return value; }
    private static void points(int n) { integer(n, 0, 1_000_000_000, "points"); }
    private static void ticks(int n) { integer(n, 1, 72_000, "ticks"); }
    private static void integer(int n, int min, int max, String name) { if (n < min || n > max) throw bad("invalid " + name); }
    private static void range(double n, double min, double max, String name) { if (!Double.isFinite(n) || n < min || n > max) throw bad("invalid " + name); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

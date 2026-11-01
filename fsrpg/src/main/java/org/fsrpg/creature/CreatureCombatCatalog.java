package org.fsrpg.creature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Source-backed melee animation timing. This common class never loads client geometry. */
public final class CreatureCombatCatalog {
    private static final String DEFAULT_RESOURCE = "/defaults/creature_combat.json";
    private static final Set<String> ENTRY_KEYS = Set.of("entityType", "variant", "model", "clip", "contactSeconds",
            "durationSeconds", "windupTicks", "recoveryTicks", "evidence");

    public record Definition(String entityType, String variant, String model, String clip, double contactSeconds,
                             double durationSeconds, int windupTicks, int recoveryTicks, String evidence) {
        public Definition(String entityType, String variant, String model, String clip, double contactSeconds,
                          double durationSeconds, int windupTicks, int recoveryTicks) {
            this(entityType, variant, model, clip, contactSeconds, durationSeconds, windupTicks, recoveryTicks,
                    "Programmatic timing; catalog loading separately validates the supplied source clip");
        }
        public Definition {
            if (entityType == null || !entityType.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                    || variant == null || !variant.matches("default|sword|axe|spear")
                    || model == null || !model.matches("[a-z0-9_]+")
                    || clip == null || !clip.matches("attack(?:_[a-z0-9_]+)?"))
                throw new IllegalArgumentException("Invalid creature melee entity/variant/model/clip");
            if (!Double.isFinite(contactSeconds) || !Double.isFinite(durationSeconds)
                    || contactSeconds <= 0 || durationSeconds <= contactSeconds || durationSeconds > 30
                    || windupTicks < 1 || windupTicks > 600 || recoveryTicks < 1 || recoveryTicks > 600)
                throw new IllegalArgumentException("Invalid creature melee timing");
            if (evidence == null || evidence.isBlank() || evidence.length() > 4096)
                throw new IllegalArgumentException("Creature melee timing needs source-curve evidence");
        }

        public int totalTicks() { return windupTicks + recoveryTicks; }

        /** Contact is exactly the windup boundary despite 20 TPS quantization. */
        public double clipSeconds(double elapsedTicks) {
            if (!Double.isFinite(elapsedTicks)) throw new IllegalArgumentException("Nonfinite animation clock");
            double time = Math.clamp(elapsedTicks, 0, totalTicks());
            return time <= windupTicks ? contactSeconds * time / windupTicks
                    : contactSeconds + (durationSeconds - contactSeconds) * (time - windupTicks) / recoveryTicks;
        }
    }

    private final Map<String, Definition> entries;

    private CreatureCombatCatalog(Map<String, Definition> entries) { this.entries = Map.copyOf(entries); }

    public List<Definition> definitions() { return List.copyOf(entries.values()); }

    public Optional<Definition> find(String entityType, String variant) {
        if (entityType == null) return Optional.empty();
        if (variant == null || variant.isBlank()) variant = "default";
        if (Set.of("bow", "crossbow", "ranged").contains(variant)) return Optional.empty();
        Definition selected = entries.get(entityType + "/" + variant);
        return Optional.ofNullable(selected == null ? entries.get(entityType + "/default") : selected);
    }

    /** Seed only a missing file. Existing custom files are read and validated without rewriting. */
    public static CreatureCombatCatalog read(Path file) throws IOException {
        if (!Files.exists(file)) {
            byte[] seed = resource(DEFAULT_RESOURCE);
            parse(new String(seed, StandardCharsets.UTF_8));
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (var input = new java.io.ByteArrayInputStream(seed)) {
                try { Files.copy(input, file); }
                catch (java.nio.file.FileAlreadyExistsException concurrentSeed) { /* Preserve the existing winner. */ }
            }
        }
        if (Files.size(file) > 512 * 1024) throw new IOException("Creature combat configuration exceeds 512 KiB: " + file);
        try { return parse(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw new IOException("Invalid creature combat configuration: " + file, invalid); }
    }

    public static CreatureCombatCatalog bundled() {
        try { return parse(new String(resource(DEFAULT_RESOURCE), StandardCharsets.UTF_8)); }
        catch (IOException missing) { throw new IllegalStateException("Missing creature combat seed", missing); }
    }

    public static CreatureCombatCatalog parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        keys(root, Set.of("schemaVersion", "attacks"));
        if (integer(root, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported creature combat schema");
        JsonArray attacks = root.getAsJsonArray("attacks");
        if (attacks == null || attacks.size() > 128) throw new IllegalArgumentException("Invalid creature attack inventory");
        var result = new LinkedHashMap<String, Definition>();
        var models = new LinkedHashMap<String, JsonObject>();
        JsonObject catalog = source("/assets/fsrpg/art/catalog.json");
        for (var raw : attacks) {
            var row = raw.getAsJsonObject(); keys(row, ENTRY_KEYS);
            var definition = new Definition(string(row, "entityType"), string(row, "variant"), string(row, "model"),
                    string(row, "clip"), number(row, "contactSeconds"), number(row, "durationSeconds"),
                    integer(row, "windupTicks"), integer(row, "recoveryTicks"), string(row, "evidence"));
            validateSource(definition, catalog, models);
            if (result.putIfAbsent(definition.entityType() + "/" + definition.variant(), definition) != null)
                throw new IllegalArgumentException("Duplicate creature melee mapping: " + definition.entityType() + "/" + definition.variant());
        }
        return new CreatureCombatCatalog(result);
    }

    private static void validateSource(Definition definition, JsonObject catalog, Map<String, JsonObject> models) {
        boolean entityModel = false;
        for (var raw : catalog.getAsJsonArray("profiles")) {
            var profile = raw.getAsJsonObject();
            if (!profile.get("entity").getAsString().equals(definition.entityType())) continue;
            for (var model : profile.getAsJsonArray("models")) if (model.getAsString().equals(definition.model())) entityModel = true;
        }
        if (!entityModel) throw new IllegalArgumentException("Model is not supplied for creature: " + definition.entityType());
        var model = models.computeIfAbsent(definition.model(), name -> source("/assets/fsrpg/art/creatures/" + name + ".json"));
        JsonObject selected = null;
        for (var raw : model.getAsJsonArray("clips")) if (raw.getAsJsonObject().get("id").getAsString().equals(definition.clip())) selected = raw.getAsJsonObject();
        if (selected == null || selected.getAsJsonArray("tracks").isEmpty())
            throw new IllegalArgumentException("No supplied attack animation: " + definition.model() + "/" + definition.clip());
        if (Math.abs(selected.get("length").getAsDouble() - definition.durationSeconds()) > .0001)
            throw new IllegalArgumentException("Configured source duration differs from supplied clip: " + definition.model() + "/" + definition.clip());
    }

    private static JsonObject source(String name) {
        try { return JsonParser.parseString(new String(resource(name), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (IOException missing) { throw new IllegalArgumentException("Missing supplied creature resource: " + name, missing); }
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = CreatureCombatCatalog.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing resource " + name);
            return input.readAllBytes();
        }
    }

    private static void keys(JsonObject value, Set<String> allowed) {
        for (String key : value.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown creature combat field: " + key);
    }

    private static String string(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string " + key);
        return field.getAsString();
    }

    private static double number(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number " + key);
        double result = field.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite " + key);
        return result;
    }

    private static int integer(JsonObject value, String key) {
        number(value, key);
        try { return value.get(key).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Expected integer " + key, invalid); }
    }
}

package org.fsrpg.quest;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Immutable server definitions for one-time species and cultivated-crop submissions. */
public final class CollectionCatalog {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:/-]{1,128}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Item(String id, int count, String potion) {
        public Item { potion = potion == null ? "" : potion; }
        public Item(String id, int count) { this(id, count, ""); }
        /** Registry id with an optional exact potion component used by the inventory adapter. */
        public String key() { return potion.isEmpty() ? id : id + "@" + potion; }
    }
    public record Mob(String id, String entity, String label, List<Item> drops) {
        public Mob { drops = List.copyOf(drops); }
    }
    public record Crop(String id, String label, String seed, String mature) {}
    public record Milestone(int target, List<Item> rewards) {
        public Milestone { rewards = List.copyOf(rewards); }
    }
    public record CropMilestone(int target, int seedCount) {}
    public record Definition(int schemaVersion, boolean firstHuntRequired, int mobRewardMultiplier,
                             int cropSeedRequired, int cropMatureRequired, int seedChoiceCount, int seedChoiceBones,
                             List<Mob> mobs, List<Crop> crops, List<Milestone> mobMilestones, CropMilestone cropMilestone) {}

    private final Definition definition;
    private final Map<String, Mob> mobs;
    private final Map<String, Crop> crops;
    private CollectionCatalog(Definition definition, Map<String, Mob> mobs, Map<String, Crop> crops) {
        this.definition = definition;
        this.mobs = Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
        this.crops = Collections.unmodifiableMap(new LinkedHashMap<>(crops));
    }
    public Definition definition() { return definition; }
    public List<Mob> mobs() { return definition.mobs(); }
    public List<Crop> crops() { return definition.crops(); }
    public List<Milestone> mobMilestones() { return definition.mobMilestones(); }
    public CropMilestone cropMilestone() { return definition.cropMilestone(); }
    public boolean firstHuntRequired() { return definition.firstHuntRequired(); }
    public Mob mob(String id) {
        Mob value = mobs.get(id);
        if (value == null) throw new IllegalArgumentException("unknown creature collection: " + id);
        return value;
    }
    public Crop crop(String id) {
        Crop value = crops.get(id);
        if (value == null) throw new IllegalArgumentException("unknown crop collection: " + id);
        return value;
    }
    public boolean containsMob(String id) { return mobs.containsKey(id); }
    public boolean containsCrop(String id) { return crops.containsKey(id); }
    public List<Item> mobRequirements(String id) { return mob(id).drops(); }
    public List<Item> mobRewards(String id) {
        return mob(id).drops().stream().map(i -> new Item(i.id(), Math.multiplyExact(i.count(), definition.mobRewardMultiplier()), i.potion())).toList();
    }
    /** A self-seeded crop consumes the sum, normally 11; never reuses the same stack twice. */
    public List<Item> cropRequirements(String id) {
        Crop crop = crop(id);
        return merge(List.of(new Item(crop.seed(), definition.cropSeedRequired()), new Item(crop.mature(), definition.cropMatureRequired())));
    }
    public List<Item> seedChoiceRewards(String cropId) {
        return List.of(new Item(crop(cropId).seed(), definition.seedChoiceCount()), new Item("minecraft:bone", definition.seedChoiceBones()));
    }
    public List<Item> cropMilestoneRewards() {
        return merge(crops().stream().map(c -> new Item(c.seed(), cropMilestone().seedCount())).toList());
    }
    public static List<Item> merge(Collection<Item> items) {
        Map<String, Item> result = new LinkedHashMap<>();
        for (Item item : items) {
            Item old = result.get(item.key());
            result.put(item.key(), new Item(item.id(), old == null ? item.count() : Math.addExact(old.count(), item.count()), item.potion()));
        }
        return List.copyOf(result.values());
    }

    public static CollectionCatalog bundled(Gson gson) {
        try (InputStream in = CollectionCatalog.class.getResourceAsStream("/defaults/collections.json")) {
            if (in == null) throw new IllegalArgumentException("Missing bundled collections.json");
            return parse(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)), gson);
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot read bundled collections.json", failure); }
    }
    /** Reject unknown fields and wrong JSON primitive types before Gson can coerce them. */
    public static CollectionCatalog parse(JsonElement json, Gson gson) {
        Objects.requireNonNull(gson, "gson");
        JsonObject obj = object(json, "collections");
        fields(obj, Set.of("schemaVersion", "firstHuntRequired", "mobRewardMultiplier", "cropSeedRequired", "cropMatureRequired",
                "seedChoiceCount", "seedChoiceBones", "mobs", "crops", "mobMilestones", "cropMilestone"), Set.of());
        for (String key : List.of("schemaVersion", "mobRewardMultiplier", "cropSeedRequired", "cropMatureRequired", "seedChoiceCount", "seedChoiceBones")) integer(obj.get(key), key);
        if (!obj.get("firstHuntRequired").isJsonPrimitive() || !obj.get("firstHuntRequired").getAsJsonPrimitive().isBoolean()) fail("firstHuntRequired must be boolean");
        for (JsonElement entry : array(obj.get("mobs"), "mobs", 1, 256)) {
            JsonObject mob = object(entry, "mob");
            fields(mob, Set.of("id", "entity", "label", "drops"), Set.of());
            textFields(mob, "id", "entity", "label");
            for (JsonElement item : array(mob.get("drops"), "drops", 1, 64)) itemFields(item);
        }
        for (JsonElement entry : array(obj.get("crops"), "crops", 2, 256)) {
            JsonObject crop = object(entry, "crop");
            fields(crop, Set.of("id", "label", "seed", "mature"), Set.of());
            textFields(crop, "id", "label", "seed", "mature");
        }
        for (JsonElement entry : array(obj.get("mobMilestones"), "mobMilestones", 0, 64)) {
            JsonObject milestone = object(entry, "mob milestone");
            fields(milestone, Set.of("target", "rewards"), Set.of()); integer(milestone.get("target"), "target");
            for (JsonElement item : array(milestone.get("rewards"), "milestone rewards", 1, 64)) itemFields(item);
        }
        JsonObject cropMilestone = object(obj.get("cropMilestone"), "crop milestone");
        fields(cropMilestone, Set.of("target", "seedCount"), Set.of());
        integer(cropMilestone.get("target"), "target"); integer(cropMilestone.get("seedCount"), "seedCount");
        return of(gson.fromJson(obj, Definition.class));
    }
    public static CollectionCatalog fromJson(JsonElement json) { return parse(json, new Gson()); }
    public static CollectionCatalog of(Definition d) {
        if (d == null || d.schemaVersion() != 1) fail("collections schemaVersion must be 1");
        range(d.mobRewardMultiplier(), 1, 64, "mobRewardMultiplier");
        range(d.cropSeedRequired(), 1, 64, "cropSeedRequired"); range(d.cropMatureRequired(), 1, 4096, "cropMatureRequired");
        range(d.seedChoiceCount(), 1, 4096, "seedChoiceCount"); range(d.seedChoiceBones(), 1, 4096, "seedChoiceBones");
        if (d.mobs() == null || d.mobs().isEmpty() || d.mobs().size() > 256) fail("mobs must contain 1..256 entries");
        if (d.crops() == null || d.crops().size() < 2 || d.crops().size() > 256) fail("crops must contain 2..256 entries");
        Map<String, Mob> mobs = new LinkedHashMap<>(); Set<String> entities = new HashSet<>();
        for (Mob mob : d.mobs()) {
            if (mob == null) fail("null mob"); validId(mob.id()); resourceId(mob.entity()); label(mob.label());
            if (!entities.add(mob.entity())) fail("duplicate creature entity: " + mob.entity());
            if (mobs.putIfAbsent(mob.id(), mob) != null) fail("duplicate mob id: " + mob.id());
            items(mob.drops(), 1, 64, "mob drops");
            for (Item item : mob.drops()) if ((long)item.count() * d.mobRewardMultiplier() > 4096) fail("mob reward exceeds 4096: " + mob.id());
        }
        Map<String, Crop> crops = new LinkedHashMap<>(); Set<String> seeds = new HashSet<>();
        for (Crop crop : d.crops()) {
            if (crop == null) fail("null crop"); validId(crop.id()); label(crop.label()); resourceId(crop.seed()); resourceId(crop.mature());
            noAir(crop.seed()); noAir(crop.mature());
            if (crops.putIfAbsent(crop.id(), crop) != null) fail("duplicate crop id: " + crop.id());
            if (!seeds.add(crop.seed())) fail("duplicate crop seed: " + crop.seed());
        }
        if (d.mobMilestones() == null || d.mobMilestones().size() > 64) fail("invalid mob milestones");
        Set<Integer> targets = new HashSet<>();
        for (Milestone milestone : d.mobMilestones()) {
            if (milestone == null) fail("null mob milestone");
            range(milestone.target(), 1, mobs.size(), "mob milestone target");
            if (!targets.add(milestone.target())) fail("duplicate mob milestone target: " + milestone.target());
            items(milestone.rewards(), 1, 64, "milestone rewards");
        }
        if (d.cropMilestone() == null) fail("cropMilestone is required");
        range(d.cropMilestone().target(), 1, crops.size(), "crop milestone target"); range(d.cropMilestone().seedCount(), 1, 4096, "crop milestone seedCount");
        Definition immutable = new Definition(1, d.firstHuntRequired(), d.mobRewardMultiplier(), d.cropSeedRequired(), d.cropMatureRequired(),
                d.seedChoiceCount(), d.seedChoiceBones(), List.copyOf(mobs.values()), List.copyOf(crops.values()), List.copyOf(d.mobMilestones()), d.cropMilestone());
        return new CollectionCatalog(immutable, mobs, crops);
    }
    /** The Minecraft adapter supplies official registries; this core loads no game classes. */
    public void validateReferences(Predicate<String> itemExists, Predicate<String> entityExists, Predicate<String> potionExists) {
        Objects.requireNonNull(itemExists); Objects.requireNonNull(entityExists); Objects.requireNonNull(potionExists);
        for (Mob mob : mobs()) {
            if (!entityExists.test(mob.entity())) fail("unknown collection entity: " + mob.entity());
            for (Item item : mob.drops()) references(item, itemExists, potionExists);
        }
        for (Crop crop : crops()) {
            if (!itemExists.test(crop.seed())) fail("unknown crop seed: " + crop.seed());
            if (!itemExists.test(crop.mature())) fail("unknown mature crop: " + crop.mature());
        }
        if (!itemExists.test("minecraft:bone")) fail("unknown seed-choice bone item");
        for (Milestone milestone : mobMilestones()) for (Item item : milestone.rewards()) references(item, itemExists, potionExists);
    }
    private static void references(Item item, Predicate<String> items, Predicate<String> potions) {
        if (!items.test(item.id())) fail("unknown collection item: " + item.id());
        if (!item.potion().isEmpty() && !potions.test(item.potion())) fail("unknown collection potion: " + item.potion());
    }
    private static void items(List<Item> list, int min, int max, String description) {
        if (list == null || list.size() < min || list.size() > max) fail(description + " count out of bounds");
        Set<String> keys = new HashSet<>();
        for (Item item : list) {
            if (item == null) fail("null collection item"); resourceId(item.id()); noAir(item.id()); range(item.count(), 1, 4096, "item count");
            if (!item.potion().isEmpty()) { resourceId(item.potion()); if (!item.id().equals("minecraft:tipped_arrow")) fail("potion component only supported for tipped arrows"); }
            if (!keys.add(item.key())) fail("duplicate item requirement/reward: " + item.key());
        }
    }
    private static void itemFields(JsonElement value) {
        JsonObject item = object(value, "item"); fields(item, Set.of("id", "count"), Set.of("potion"));
        textFields(item, "id"); integer(item.get("count"), "item count"); if (item.has("potion")) textFields(item, "potion");
    }
    private static JsonObject object(JsonElement e, String name) { if (e == null || !e.isJsonObject()) fail(name + " must be object"); return e.getAsJsonObject(); }
    private static JsonArray array(JsonElement e, String name, int min, int max) {
        if (e == null || !e.isJsonArray()) fail(name + " must be array");
        JsonArray a = e.getAsJsonArray(); if (a.size() < min || a.size() > max) fail(name + " count out of bounds"); return a;
    }
    private static void fields(JsonObject obj, Set<String> required, Set<String> optional) {
        for (String key : obj.keySet()) if (!required.contains(key) && !optional.contains(key)) fail("unknown collections field: " + key);
        for (String key : required) if (!obj.has(key) || obj.get(key).isJsonNull()) fail("missing collections field: " + key);
    }
    private static void textFields(JsonObject obj, String... keys) {
        for (String key : keys) if (!obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) fail(key + " must be string");
    }
    private static void integer(JsonElement e, String name) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) fail(name + " must be integer");
        try { e.getAsBigDecimal().intValueExact(); } catch (ArithmeticException | NumberFormatException invalid) { fail(name + " must be finite integer within 32-bit range"); }
    }
    private static void range(int i, int min, int max, String name) { if (i < min || i > max) fail(name + " must be " + min + ".." + max); }
    private static void validId(String id) { if (id == null || !ID.matcher(id).matches()) fail("invalid collection id: " + id); }
    private static void resourceId(String id) { if (id == null || id.length() > 128 || !RESOURCE_ID.matcher(id).matches()) fail("invalid collection registry id: " + id); }
    private static void noAir(String id) { if ("minecraft:air".equals(id)) fail("air is not a collection item"); }
    private static void label(String label) { if (label == null || label.isBlank() || label.length() > 256) fail("collection label must contain 1..256 characters"); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}

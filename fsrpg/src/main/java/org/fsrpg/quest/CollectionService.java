package org.fsrpg.quest;

import java.util.*;
import org.fsrpg.quest.CollectionCatalog.Item;

/**
 * One-time collection progress and a durable inventory-exchange outbox, independent of Minecraft.
 *
 * Integration order: plan -> begin -> save collections -> applyOnce and save player inventory plus
 * transaction receipt -> finish -> save collections. Recover any saved pending exchange before new
 * input. applyOnce must treat a receipt as success without consuming/granting twice. If applying an
 * unreceipted exchange fails before inventory changes, cancel and save. Never cancel an applied one.
 * Both consumed ingredients and reward items belong to that one player-side atomic save.
 */
public final class CollectionService {
    public static final int SCHEMA_VERSION = 1;
    public enum Kind { MOB, CROP, SEED_CHOICE }
    public record State(long revision, String firstHuntEntity, Set<String> submittedMobs, Set<String> submittedCrops,
                        Set<String> pendingSeedChoices, Set<String> claimedSeedChoices, Set<Integer> claimedMobMilestones,
                        boolean cropMilestoneClaimed) {
        public State {
            firstHuntEntity = Objects.requireNonNull(firstHuntEntity, "firstHuntEntity");
            submittedMobs = immutable(submittedMobs); submittedCrops = immutable(submittedCrops);
            pendingSeedChoices = immutable(pendingSeedChoices); claimedSeedChoices = immutable(claimedSeedChoices);
            claimedMobMilestones = immutable(claimedMobMilestones);
        }
        public boolean firstHuntComplete() { return !firstHuntEntity.isEmpty(); }
        public int mobCount() { return submittedMobs.size(); }
        public int cropCount() { return submittedCrops.size(); }
    }
    public record Pending(UUID transactionId, UUID player, Kind kind, String sourceId, String choiceId,
                          List<Item> requirements, List<Item> rewards, int xp, State before, State after) {
        public Pending { requirements = List.copyOf(requirements); rewards = List.copyOf(rewards); }
    }
    public record Snapshot(int schemaVersion, Map<UUID, State> players, Map<UUID, Pending> pending) {}

    /** Created only by this service; client requests must never deserialize a plan. */
    public static final class Plan {
        private final Pending pending;
        private Plan(Pending pending) { this.pending = pending; }
        public UUID transactionId() { return pending.transactionId(); }
        public UUID player() { return pending.player(); }
        public Kind kind() { return pending.kind(); }
        public String sourceId() { return pending.sourceId(); }
        public String choiceId() { return pending.choiceId(); }
        public List<Item> requirements() { return pending.requirements(); }
        public List<Item> rewards() { return pending.rewards(); }
        public int xp() { return pending.xp(); }
    }
    private final CollectionCatalog catalog;
    private final Map<UUID, State> players = new LinkedHashMap<>();
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Plan> issued = new HashMap<>();
    private long revision;
    public CollectionService(CollectionCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }
    public CollectionCatalog catalog() { return catalog; }
    public synchronized long revision() { return revision; }
    public synchronized State progress(UUID player) { return players.getOrDefault(Objects.requireNonNull(player), empty()); }
    /** Trusted server event only. No reward is invented for the first kill. */
    public synchronized boolean firstKill(UUID player, String entityId) {
        validResourceId(entityId);
        State s = progress(player);
        if (s.firstHuntComplete()) return false;
        idle(player);
        players.put(player, new State(next(s.revision()), entityId, s.submittedMobs(), s.submittedCrops(), s.pendingSeedChoices(),
                s.claimedSeedChoices(), s.claimedMobMilestones(), s.cropMilestoneClaimed()));
        issued.remove(player); revision++;
        return true;
    }
    public synchronized Plan planMob(UUID player, String mobId, Map<String, Integer> owned) {
        CollectionCatalog.Mob mob = catalog.mob(mobId); idle(player); State s = progress(player);
        if (catalog.firstHuntRequired() && !s.firstHuntComplete()) throw new IllegalStateException("hunt your first monster before creature submissions");
        if (s.submittedMobs().contains(mobId)) throw new IllegalStateException("creature already submitted: " + mobId);
        List<Item> requirements = mob.drops(); requireOwned(requirements, owned);
        Set<String> submitted = add(s.submittedMobs(), mobId); Set<Integer> milestones = new LinkedHashSet<>(s.claimedMobMilestones());
        List<Item> rewards = new ArrayList<>(catalog.mobRewards(mobId));
        for (CollectionCatalog.Milestone milestone : catalog.mobMilestones()) if (submitted.size() >= milestone.target() && milestones.add(milestone.target())) rewards.addAll(milestone.rewards());
        State after = new State(next(s.revision()), s.firstHuntEntity(), submitted, s.submittedCrops(), s.pendingSeedChoices(),
                s.claimedSeedChoices(), milestones, s.cropMilestoneClaimed());
        return plan(player, Kind.MOB, mobId, "", requirements, rewards, s, after);
    }
    public synchronized Plan planCrop(UUID player, String cropId, Map<String, Integer> owned) {
        catalog.crop(cropId); idle(player); State s = progress(player);
        if (s.submittedCrops().contains(cropId)) throw new IllegalStateException("crop already submitted: " + cropId);
        List<Item> requirements = catalog.cropRequirements(cropId); requireOwned(requirements, owned);
        Set<String> submitted = add(s.submittedCrops(), cropId);
        boolean milestone = s.cropMilestoneClaimed() || submitted.size() >= catalog.cropMilestone().target();
        List<Item> rewards = !s.cropMilestoneClaimed() && milestone ? catalog.cropMilestoneRewards() : List.of();
        State after = new State(next(s.revision()), s.firstHuntEntity(), s.submittedMobs(), submitted, add(s.pendingSeedChoices(), cropId),
                s.claimedSeedChoices(), s.claimedMobMilestones(), milestone);
        return plan(player, Kind.CROP, cropId, "", requirements, rewards, s, after);
    }
    public synchronized Plan planSeedChoice(UUID player, String sourceCropId, String choiceCropId) {
        catalog.crop(sourceCropId); catalog.crop(choiceCropId); idle(player); State s = progress(player);
        if (sourceCropId.equals(choiceCropId)) throw new IllegalArgumentException("choose a different crop's seeds");
        if (!s.pendingSeedChoices().contains(sourceCropId) || s.claimedSeedChoices().contains(sourceCropId)) throw new IllegalStateException("no unclaimed seed choice for crop: " + sourceCropId);
        Set<String> choices = new LinkedHashSet<>(s.pendingSeedChoices()); choices.remove(sourceCropId);
        State after = new State(next(s.revision()), s.firstHuntEntity(), s.submittedMobs(), s.submittedCrops(), choices,
                add(s.claimedSeedChoices(), sourceCropId), s.claimedMobMilestones(), s.cropMilestoneClaimed());
        return plan(player, Kind.SEED_CHOICE, sourceCropId, choiceCropId, List.of(), catalog.seedChoiceRewards(choiceCropId), s, after);
    }
    private Plan plan(UUID player, Kind kind, String sourceId, String choiceId, List<Item> requirements, List<Item> rewards, State before, State after) {
        List<Item> mergedRewards = CollectionCatalog.merge(rewards);
        validateItems(requirements); validateItems(mergedRewards);
        Plan plan = new Plan(new Pending(UUID.randomUUID(), player, kind, sourceId, choiceId, List.copyOf(requirements), mergedRewards, 0, before, after));
        issued.put(player, plan);
        return plan;
    }
    /** Writes an outbox entry in memory. Persist it before making any inventory changes. */
    public synchronized Pending begin(Plan plan) {
        Objects.requireNonNull(plan); idle(plan.player());
        if (issued.get(plan.player()) != plan || !progress(plan.player()).equals(plan.pending.before())) throw new IllegalStateException("stale or foreign collection plan");
        pending.put(plan.player(), plan.pending); issued.remove(plan.player()); revision++;
        return plan.pending;
    }
    /** Complete only after the player inventory transaction receipt was persisted. Duplicate finish is harmless. */
    public synchronized boolean finish(UUID player, UUID transactionId) {
        Pending exchange = pending.get(player);
        if (exchange == null) return false;
        if (!exchange.transactionId().equals(transactionId)) throw new IllegalArgumentException("wrong collection transaction");
        if (!progress(player).equals(exchange.before())) throw new IllegalStateException("collection exchange base progress changed");
        players.put(player, exchange.after()); pending.remove(player); issued.remove(player); revision++;
        return true;
    }
    /** Only for an exchange known not to have changed inventory. Keep applied exchanges until finish. */
    public synchronized boolean cancel(UUID player, UUID transactionId) {
        Pending exchange = pending.get(player);
        if (exchange == null) return false;
        if (!exchange.transactionId().equals(transactionId)) throw new IllegalArgumentException("wrong collection transaction");
        pending.remove(player); issued.remove(player); revision++; return true;
    }
    public synchronized Optional<Pending> pending(UUID player) { return Optional.ofNullable(pending.get(Objects.requireNonNull(player))); }
    public synchronized List<Pending> pending() { return List.copyOf(pending.values()); }
    public synchronized Snapshot snapshot() { return new Snapshot(SCHEMA_VERSION, Map.copyOf(players), Map.copyOf(pending)); }
    /** Restoring into a new service supports atomic config reload and retains removed stable ids. */
    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != SCHEMA_VERSION || snapshot.players() == null || snapshot.pending() == null) throw new IllegalArgumentException("unsupported or missing collection snapshot schema");
        if (snapshot.players().size() > 1_000_000 || snapshot.pending().size() > 1_000_000) throw new IllegalArgumentException("collection snapshot player count exceeds limit");
        Map<UUID, State> restored = new LinkedHashMap<>(); Map<UUID, Pending> restoredPending = new LinkedHashMap<>(); Set<UUID> transactions = new HashSet<>();
        for (var entry : snapshot.players().entrySet()) {
            Objects.requireNonNull(entry.getKey(), "null saved player"); validate(entry.getValue()); restored.put(entry.getKey(), entry.getValue());
        }
        for (var entry : snapshot.pending().entrySet()) {
            Pending p = entry.getValue();
            if (p == null || p.transactionId() == null || p.player() == null || !p.player().equals(entry.getKey()) || p.kind() == null || !transactions.add(p.transactionId())) throw new IllegalArgumentException("invalid saved collection transaction");
            validStableId(p.sourceId()); if (p.choiceId() == null) throw new IllegalArgumentException("null saved seed choice");
            if (p.kind() == Kind.SEED_CHOICE) { validStableId(p.choiceId()); if (p.sourceId().equals(p.choiceId())) throw new IllegalArgumentException("same-species saved seed choice"); }
            else if (!p.choiceId().isEmpty()) throw new IllegalArgumentException("unexpected saved choice");
            if (p.xp() != 0) throw new IllegalArgumentException("unexpected collection exchange xp");
            validateItems(p.requirements()); validateItems(p.rewards()); validate(p.before()); validate(p.after());
            if (!restored.getOrDefault(entry.getKey(), empty()).equals(p.before()) || p.after().revision() != next(p.before().revision())) throw new IllegalArgumentException("invalid collection transaction revision");
            validateTransition(p);
            restoredPending.put(entry.getKey(), p);
        }
        players.clear(); players.putAll(restored); pending.clear(); pending.putAll(restoredPending); issued.clear(); revision++;
    }
    private static void validateTransition(Pending p) {
        State before = p.before(), after = p.after();
        if (!before.firstHuntEntity().equals(after.firstHuntEntity())) throw new IllegalArgumentException("exchange cannot change first hunt");
        boolean valid = switch (p.kind()) {
            case MOB -> !before.submittedMobs().contains(p.sourceId()) && after.submittedMobs().equals(add(before.submittedMobs(), p.sourceId()))
                    && after.submittedCrops().equals(before.submittedCrops()) && after.pendingSeedChoices().equals(before.pendingSeedChoices())
                    && after.claimedSeedChoices().equals(before.claimedSeedChoices()) && after.claimedMobMilestones().containsAll(before.claimedMobMilestones())
                    && after.cropMilestoneClaimed() == before.cropMilestoneClaimed() && !p.requirements().isEmpty();
            case CROP -> !before.submittedCrops().contains(p.sourceId()) && after.submittedCrops().equals(add(before.submittedCrops(), p.sourceId()))
                    && after.submittedMobs().equals(before.submittedMobs()) && after.pendingSeedChoices().equals(add(before.pendingSeedChoices(), p.sourceId()))
                    && after.claimedSeedChoices().equals(before.claimedSeedChoices()) && after.claimedMobMilestones().equals(before.claimedMobMilestones())
                    && (!before.cropMilestoneClaimed() || after.cropMilestoneClaimed()) && !p.requirements().isEmpty();
            case SEED_CHOICE -> before.pendingSeedChoices().contains(p.sourceId()) && !before.claimedSeedChoices().contains(p.sourceId())
                    && after.pendingSeedChoices().equals(remove(before.pendingSeedChoices(), p.sourceId())) && after.claimedSeedChoices().equals(add(before.claimedSeedChoices(), p.sourceId()))
                    && after.submittedMobs().equals(before.submittedMobs()) && after.submittedCrops().equals(before.submittedCrops())
                    && after.claimedMobMilestones().equals(before.claimedMobMilestones()) && after.cropMilestoneClaimed() == before.cropMilestoneClaimed()
                    && p.requirements().isEmpty() && !p.rewards().isEmpty();
        };
        if (!valid) throw new IllegalArgumentException("invalid saved collection exchange transition");
    }
    private void idle(UUID player) { Objects.requireNonNull(player); if (pending.containsKey(player)) throw new IllegalStateException("a collection inventory exchange is awaiting recovery"); }
    public static boolean hasRequirements(List<Item> requirements, Map<String, Integer> owned) {
        if (owned == null) return false;
        for (Item item : requirements) { Integer count = owned.get(item.key()); if (count == null || count < item.count()) return false; }
        return true;
    }
    private static void requireOwned(List<Item> requirements, Map<String, Integer> owned) {
        if (!hasRequirements(requirements, owned)) throw new IllegalStateException("not enough collection ingredients");
    }
    private static State empty() { return new State(0, "", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false); }
    private static long next(long revision) { if (revision < 0 || revision == Long.MAX_VALUE) throw new IllegalStateException("collection revision limit reached"); return revision + 1; }
    private static <T> Set<T> immutable(Set<T> values) { return Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(values, "saved collection set"))); }
    private static <T> Set<T> add(Set<T> values, T value) { Set<T> result = new LinkedHashSet<>(values); result.add(value); return result; }
    private static <T> Set<T> remove(Set<T> values, T value) { Set<T> result = new LinkedHashSet<>(values); result.remove(value); return result; }
    private static void validate(State state) {
        if (state == null || state.revision() < 0 || state.revision() == Long.MAX_VALUE) throw new IllegalArgumentException("invalid saved collection state");
        if (!state.firstHuntEntity().isEmpty()) validResourceId(state.firstHuntEntity());
        for (Set<String> values : List.of(state.submittedMobs(), state.submittedCrops(), state.pendingSeedChoices(), state.claimedSeedChoices())) {
            if (values.size() > 4096) throw new IllegalArgumentException("saved collection set too large");
            values.forEach(CollectionService::validStableId);
        }
        if (!state.submittedCrops().containsAll(state.pendingSeedChoices()) || !state.submittedCrops().containsAll(state.claimedSeedChoices())
                || !Collections.disjoint(state.pendingSeedChoices(), state.claimedSeedChoices())) throw new IllegalArgumentException("invalid saved seed choice state");
        if (state.claimedMobMilestones().size() > 256) throw new IllegalArgumentException("too many saved milestones");
        for (Integer target : state.claimedMobMilestones()) if (target == null || target < 1 || target > 256 || target > state.mobCount()) throw new IllegalArgumentException("invalid saved mob milestone");
    }
    private static void validateItems(List<Item> items) {
        if (items.size() > 512) throw new IllegalArgumentException("too many saved exchange items");
        Set<String> keys = new HashSet<>();
        for (Item item : items) {
            if (item == null || item.count() < 1 || item.count() > 16384) throw new IllegalArgumentException("invalid saved exchange count");
            validResourceId(item.id()); if (item.id().equals("minecraft:air")) throw new IllegalArgumentException("saved air ingredient");
            if (!item.potion().isEmpty()) { validResourceId(item.potion()); if (!item.id().equals("minecraft:tipped_arrow")) throw new IllegalArgumentException("invalid saved potion item"); }
            if (!keys.add(item.key())) throw new IllegalArgumentException("duplicate saved exchange item");
        }
    }
    private static void validStableId(String id) { if (id == null || !id.matches("[a-z0-9_.:/-]{1,128}")) throw new IllegalArgumentException("invalid saved collection id: " + id); }
    private static void validResourceId(String id) { if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("invalid collection entity/item: " + id); }
}

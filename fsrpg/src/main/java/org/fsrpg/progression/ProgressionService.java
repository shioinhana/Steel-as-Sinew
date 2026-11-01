package org.fsrpg.progression;

import java.util.*;
import org.fsrpg.progression.SkillTreeCatalog.*;

/** Pure, player-isolated cultivation. Call growth methods only for verified server events. */
public final class ProgressionService {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX = 1_000_000_000;
    private static final long UNSET = Long.MIN_VALUE;
    private static final Set<String> GAIN_KEYS = Set.of("mind", "body", "footwork", "rapier", "dual_blade", "bow");
    private static final Set<String> SCORE_KEYS = Set.of("rapier", "dual_blade", "bow");
    public record Points(int total, int available) {
        public Points { bound(total); if (available < 0 || available > total) throw bad("available points exceed total"); }
    }
    public record View(boolean mindUnlocked, boolean bodyUnlocked, boolean footworkUnlocked,
                       Points mind, Points body, Points footwork, int rapierKills, int dualHits,
                       int archeryScore, int dualStacks, int dualCap, boolean trialPassed,
                       boolean dragonKilled, int physiquePercent, int insightPercent, boolean migrated,
                       Set<String> learnedNodes, String branch150, String branch250) {}
    public record NodeView(String id, String tree, String effect, String label, String description, boolean automatic,
                           boolean learned, boolean available, List<String> blockers, Cost cost,
                           Requirements requirements, List<String> prerequisites,
                           String exclusiveGroup, List<String> skills, boolean creative) {}
    public record LearnResult(boolean success, String reason) {}
    public record BowProgress(boolean unlocked, int score, String branch150, String branch250) {}
    public record FootworkView(boolean enabled, FootworkStyle style, int cloudTier, int lightTier,
                               int selectionsEarned, int selectionsUsed, int selectionsAvailable) {}
    public record Effects(boolean rapier, boolean dual, boolean bow, boolean rapierThrust,
                          boolean rapierParry, boolean rapierExecution, int dualComboCap, int dualStacks,
                          double healthBonus, double damageBonus, double attackIntervalReductionSeconds,
                          double bowDrawFactor, double miningTimeFactor, double movementSpeedBonus,
                          double dualHoldingMoveBonus, double dualAttackingMoveBonus,
                          int regenerationFoodLevel, double regenerationMultiplier,
                          double minAttackIntervalSeconds) {
        public int attackIntervalTicks(int baseTicks) {
            if (baseTicks < 1 || baseTicks > 72_000) throw bad("invalid base attack interval");
            return Math.max((int) Math.ceil(minAttackIntervalSeconds * 20),
                    Math.round((float) (Math.max(minAttackIntervalSeconds, baseTicks / 20.0 - attackIntervalReductionSeconds) * 20)));
        }
    }
    /** Durable data contains earned totals, unspent points, actual-event remainders and learned IDs. */
    public record SavedProgress(long revision, boolean mindUnlocked, boolean bodyUnlocked, boolean footworkUnlocked,
            Points mind, Points body, Points footwork, int rapierKills, int dualHits, int archeryScore,
            int dualStacks, boolean trialPassed, boolean dragonKilled, boolean migrated,
            Set<String> learnedNodes, Set<String> eatenFoods, long activeTicks, int hungerRemainder,
            int minedRemainder, double bodyMetres, double footworkMetres, double hostileHpLoss,
            boolean aptitudesInitialized, int physiquePercent, int insightBasePercent, int bossInsightBonus,
            boolean physiqueRerolled, Map<String, Double> gainRemainders, Map<String, Long> growthDays,
            long lastDecayDay, long dualLastHitTick, Set<String> creativeNodes) {}
    public record Snapshot(int schemaVersion, Map<UUID, SavedProgress> players) {}

    private SkillTreeCatalog catalog;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private long revision;
    public ProgressionService(SkillTreeCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }
    public synchronized SkillTreeCatalog catalog() { return catalog; }
    public synchronized long revision() { return revision; }
    public synchronized boolean hasPlayer(UUID player) { return players.containsKey(Objects.requireNonNull(player)); }
    public synchronized void reconfigure(SkillTreeCatalog replacement) {
        catalog = Objects.requireNonNull(replacement);
        for (Player p : players.values()) { capFootwork(p); revokeInvalidBranches(p); p.dualStacks = Math.min(p.dualStacks, dualCap(p)); }
        revision++;
    }
    public synchronized View view(UUID player) {
        Player p = read(player); int cap = dualCap(p);
        return new View(p.mindUnlocked, p.bodyUnlocked, p.footworkUnlocked, p.mind, p.body, p.footwork,
                p.rapierKills, p.dualHits, p.archeryScore, Math.min(p.dualStacks, cap), cap, p.trialPassed,
                p.dragonKilled, p.physiquePercent, insight(p), p.migrated, immutable(p.learned), branch(p, "archery_150"), branch(p, "archery_250"));
    }
    public synchronized List<NodeView> nodes(UUID player) {
        Player p = read(player); List<NodeView> result = new ArrayList<>();
        for (Node node : catalog.nodes()) {
            boolean unlocked = unlocked(p, node); List<String> blockers = blockers(p, node, true);
            result.add(new NodeView(node.id(), node.tree(), node.effect().name(), node.label(), node.description(), node.automatic(),
                    unlocked, !node.automatic() && !unlocked && blockers.isEmpty(), List.copyOf(blockers),
                    node.cost(), node.requirements(), node.prerequisites(), node.exclusiveGroup(), node.skills(),
                    p.creative.contains(node.id())));
        }
        return List.copyOf(result);
    }
    public synchronized LearnResult learn(UUID player, String nodeId) {
        Optional<Node> found = catalog.find(nodeId);
        if (found.isEmpty()) return new LearnResult(false, "未知技能节点");
        Node node = found.get(); Player p = read(player);
        if (node.automatic()) return new LearnResult(false, "该节点达到条件后自动解锁");
        if (unlocked(p, node)) return new LearnResult(false, "已学习");
        List<String> blockers = blockers(p, node, true);
        if (!blockers.isEmpty()) return new LearnResult(false, String.join("；", blockers));
        p = edit(player); p.learned.add(node.id()); Cost cost = node.cost();
        p.mind = spend(p.mind, cost.mind()); p.body = spend(p.body, cost.body()); p.footwork = spend(p.footwork, cost.footwork());
        changed(p); return new LearnResult(true, "已学习 " + node.label());
    }
    /** Creative-only: learn one manual node for free, replacing any exclusive sibling. */
    public synchronized LearnResult learnFree(UUID player, String nodeId) {
        Optional<Node> found = catalog.find(nodeId);
        if (found.isEmpty()) return new LearnResult(false, "未知技能节点");
        Node node = found.get();
        if (node.automatic()) return new LearnResult(false, "该节点达到条件后自动解锁");
        Player current = read(player);
        if (current.learned.contains(node.id())) return new LearnResult(false, "已学习");
        Player p = edit(player);
        // Creative learning is a real choice, so the other branch of the same group is replaced.
        if (!node.exclusiveGroup().isEmpty()) for (Node other : catalog.nodes())
            if (!other.id().equals(node.id()) && other.exclusiveGroup().equals(node.exclusiveGroup())) {
                p.learned.remove(other.id()); p.creative.remove(other.id());
            }
        p.learned.add(node.id()); p.creative.add(node.id());
        changed(p); return new LearnResult(true, "创造模式：已学习 " + node.label());
    }
    /** Creative-only: remove one manual node from learned and creative selections; effects stop immediately. */
    public synchronized LearnResult unlearn(UUID player, String nodeId) {
        Optional<Node> found = catalog.find(nodeId);
        if (found.isEmpty()) return new LearnResult(false, "未知技能节点");
        Node node = found.get();
        if (node.automatic()) return new LearnResult(false, "自动解锁节点不能移除");
        Player p = read(player);
        if (!p.learned.contains(node.id())) return new LearnResult(false, "尚未学习该技能");
        p = edit(player); p.learned.remove(node.id()); p.creative.remove(node.id());
        changed(p); return new LearnResult(true, "已移除 " + node.label());
    }
    /**
     * Creative-only one-shot fill: totals are the highest requirement of each stat plus every manual cost,
     * so all automatic gates (including exclusive branches) become available without rewriting learned IDs.
     */
    public synchronized void creativeFill(UUID player) {
        Player p = edit(player); TrainingRules training = catalog.rules().training();
        long mindRequirement = 0, bodyRequirement = 0, mindCosts = 0, bodyCosts = 0;
        int rapierScore = 0, dualScore = 0, bowScore = 0;
        for (Node node : catalog.nodes()) {
            Requirements requirements = node.requirements();
            mindRequirement = Math.max(mindRequirement, requirements.mind());
            bodyRequirement = Math.max(bodyRequirement, requirements.body());
            switch (node.tree()) {
                case "rapier" -> rapierScore = Math.max(rapierScore, requirements.score());
                case "dual_blade" -> dualScore = Math.max(dualScore, requirements.score());
                case "bow" -> bowScore = Math.max(bowScore, requirements.score());
                default -> { }
            }
            if (!node.automatic()) { mindCosts += node.cost().mind(); bodyCosts += node.cost().body(); }
        }
        int mind = (int) Math.min(MAX, mindRequirement + mindCosts), body = (int) Math.min(MAX, bodyRequirement + bodyCosts);
        p.mindUnlocked = true; p.bodyUnlocked = true; p.footworkUnlocked = true;
        p.mind = new Points(mind, mind); p.body = new Points(body, body);
        p.footwork = new Points(training.footworkCap(), training.footworkCap());
        p.rapierKills = rapierScore; p.dualHits = dualScore; p.archeryScore = bowScore;
        p.dualStacks = 0; p.trialPassed = true; p.dragonKilled = true;
        p.aptitudesInitialized = true; p.physiquePercent = 0; p.insightBasePercent = 100;
        p.activeTicks = 0; p.hungerRemainder = 0; p.minedRemainder = 0;
        p.bodyMetres = 0; p.footworkMetres = 0; p.hostileHpLoss = 0;
        p.remainders.clear(); p.growthDays.clear(); p.incoming.clear();
        p.incomingTick = UNSET; p.dualLastHitTick = UNSET;
        changed(p);
    }
    /** Creative-only one-shot reset: drop every learned node and earned point, keeping unlocks and world flags. */
    public synchronized void creativeReset(UUID player) {
        Player p = edit(player);
        p.learned.clear(); p.creative.clear();
        p.mind = new Points(0, 0); p.body = new Points(0, 0); p.footwork = new Points(0, 0);
        p.rapierKills = 0; p.dualHits = 0; p.archeryScore = 0; p.dualStacks = 0;
        p.mindUnlocked = true; p.bodyUnlocked = true; p.footworkUnlocked = true;
        p.activeTicks = 0; p.hungerRemainder = 0; p.minedRemainder = 0;
        p.bodyMetres = 0; p.footworkMetres = 0; p.hostileHpLoss = 0;
        p.remainders.clear(); p.growthDays.clear(); p.incoming.clear();
        p.incomingTick = UNSET; p.dualLastHitTick = UNSET;
        changed(p);
    }
    public synchronized boolean unlocked(UUID player, String nodeId) {
        return catalog.find(nodeId).map(node -> unlocked(read(player), node)).orElse(false);
    }
    public synchronized boolean weaponUnlocked(UUID player, String styleId) {
        Player p = read(player);
        return switch (styleId) {
            case "rapier" -> effect(p, Effect.ROOT_RAPIER);
            case "dual_blade" -> effect(p, Effect.ROOT_DUAL);
            case "bow", "bow_rapid", "bow_snipe" -> effect(p, Effect.ROOT_BOW);
            default -> true;
        };
    }
    /** Skills outside the three migrated trees retain their existing behavior (including spear). */
    public synchronized boolean skillUnlocked(UUID player, String skillId) {
        List<Node> owners = catalog.nodes().stream().filter(node -> node.skills().contains(skillId)).toList();
        return owners.isEmpty() || owners.stream().anyMatch(node -> unlocked(read(player), node));
    }
    public synchronized BowProgress archeryView(UUID player) {
        Player p = read(player); return new BowProgress(effect(p, Effect.ROOT_BOW), p.archeryScore,
                branch(p, "archery_150"), branch(p, "archery_250"));
    }
    public synchronized FootworkView footworkView(UUID player) {
        Player p = read(player); boolean enabled = p.mindUnlocked && p.footworkUnlocked;
        int earned = p.footwork.total() / catalog.rules().footwork().selectionPoints(), used = footworkSelectionsUsed(p);
        FootworkStyle style = effect(p, Effect.RABBIT) ? FootworkStyle.RABBIT
                : effect(p, Effect.FLYING_SWAN) ? FootworkStyle.FLYING_SWAN : FootworkStyle.NONE;
        int cloud = effect(p, Effect.CLOUD_STEP_III) ? 3 : effect(p, Effect.CLOUD_STEP_II) ? 2 : effect(p, Effect.CLOUD_STEP) ? 1 : 0;
        int light = effect(p, Effect.LIGHT_BODY_III) ? 3 : effect(p, Effect.LIGHT_BODY_II) ? 2 : effect(p, Effect.LIGHT_BODY) ? 1 : 0;
        return new FootworkView(enabled, style, cloud, light, earned, used, Math.max(0, earned - used));
    }
    public synchronized Effects effects(UUID player) {
        Player p = read(player); TrainingRules t = catalog.rules().training(); DualRules d = catalog.rules().dual();
        boolean dual = effect(p, Effect.ROOT_DUAL); int cap = dualCap(p), stacks = Math.min(p.dualStacks, cap);
        int body = p.bodyUnlocked ? p.body.total() : 0, mind = p.mindUnlocked ? p.mind.total() : 0;
        int attackLevel = p.bodyUnlocked ? levels(body, t.bodyAttackSpeedThresholds()) : 0;
        return new Effects(effect(p, Effect.ROOT_RAPIER), dual, effect(p, Effect.ROOT_BOW),
                effect(p, Effect.RAPIER_THRUST), effect(p, Effect.RAPIER_PARRY), effect(p, Effect.RAPIER_EXECUTION), cap, stacks,
                p.mindUnlocked ? levels(mind, t.mindHealthThresholds()) * t.mindHealthPerLevel() : 0,
                p.bodyUnlocked ? levels(body, t.bodyDamageThresholds()) * t.bodyDamagePerLevel() : 0,
                attackLevel * t.bodyAttackSpeedPerLevelSeconds(), Math.pow(t.bodyBowFactorPerLevel(), attackLevel),
                p.bodyUnlocked ? Math.pow(t.bodyMiningFactorPerLevel(), levels(body, t.bodyMiningThresholds())) : 1,
                p.footworkUnlocked ? t.footworkMaxSpeedBonus() * Math.min(p.footwork.total(), t.footworkCap()) / t.footworkCap() : 0,
                dual ? d.holdMoveBonus() : 0, dual ? d.holdMoveBonus() - d.swingMovePenalty() + stacks * d.layerSwingBonus() : 0,
                p.mindUnlocked && mind >= t.regenLowFoodMind() ? t.regenLowFoodLevel() : 18,
                p.mindUnlocked && mind >= t.regenDoubleMind() ? 2 : 1, t.minAttackIntervalSeconds());
    }
    /** Explicit server randomness makes the pure service deterministic and keeps aptitudes stable on reconnect. */
    public synchronized boolean initializeAptitudes(UUID player, int physique, int insight, long epochDay) {
        day(epochDay); LongTermRules r = catalog.rules().longTerm();
        if (physique < r.initialAptitudeMin() || physique > r.initialAptitudeMax() || insight < r.initialAptitudeMin() || insight > r.initialAptitudeMax()) throw bad("initial aptitude outside configured bounds");
        Player p = read(player); if (p.aptitudesInitialized) return false;
        p = edit(player); p.aptitudesInitialized = true; p.physiquePercent = physique; p.insightBasePercent = insight;
        if (p.lastDecayDay == UNSET) p.lastDecayDay = epochDay;
        changed(p); return true;
    }
    public synchronized boolean rerollPhysique(UUID player, int physique) {
        LongTermRules r = catalog.rules().longTerm(); if (physique < r.initialAptitudeMin() || physique > r.initialAptitudeMax()) throw bad("reroll outside configured bounds");
        Player p = read(player); if (!p.aptitudesInitialized || p.physiqueRerolled) return false;
        p = edit(player); p.physiquePercent = physique; p.physiqueRerolled = true; changed(p); return true;
    }
    /** Only call once for a player already present in a pre-progression FSRPG world. */
    public synchronized boolean migrateExistingCombat(UUID player) {
        Player p = read(player); if (p.migrated) return false;
        p = edit(player); p.migrated = true;
        for (Node node : catalog.nodes()) if (Set.of(Effect.ROOT_RAPIER, Effect.ROOT_DUAL, Effect.ROOT_BOW).contains(node.effect())) p.learned.add(node.id());
        changed(p); return true;
    }
    /** A real effective sleep/skip-night event, rather than merely opening the bed screen. */
    public synchronized boolean onSleep(UUID player) {
        Player p = read(player); if (p.mindUnlocked) return false;
        p = edit(player); p.mindUnlocked = true; changed(p); return true;
    }
    /** Includes blocked/cancelled hostile attacks, deduplicated per source/tick; excludes non-hostile events. */
    public synchronized boolean onHostileIncoming(UUID player, UUID attacker, long tick) {
        Objects.requireNonNull(attacker); gameTick(tick); Player p = read(player);
        if (!p.mindUnlocked || !p.bodyUnlocked) return false;
        p = edit(player);
        if (p.incomingTick != tick) { p.incomingTick = tick; p.incoming.clear(); }
        if (!p.incoming.add(attacker)) return false;
        gain(p, "body", 1); unlockFootwork(p); changed(p); return true;
    }
    /** Actual final hostile HP loss, supplied after armor, resistance, absorption and cancellation. */
    public synchronized void onHostileDamage(UUID player, double actualHpLoss) {
        finite(actualHpLoss, 0, 100_000, "hostile HP loss"); Player p = read(player);
        if (!p.mindUnlocked || actualHpLoss == 0 || p.bodyUnlocked) return;
        p = edit(player); p.hostileHpLoss = Math.min(catalog.rules().training().bodyUnlockHp(), p.hostileHpLoss + actualHpLoss);
        if (p.hostileHpLoss >= catalog.rules().training().bodyUnlockHp()) p.bodyUnlocked = true;
        unlockFootwork(p); changed(p);
    }
    /** Actual completed food consumption; distinct IDs count once, actual hunger restoration accumulates. */
    public synchronized void onFood(UUID player, String itemId, int actualHungerRestored) {
        SkillTreeCatalog.validId(itemId); if (actualHungerRestored < 0 || actualHungerRestored > 20) throw bad("invalid hunger restored");
        Player p = read(player); if (!p.mindUnlocked) return;
        p = edit(player); if (p.eatenFoods.add(itemId)) gain(p, "mind", 1);
        int interval = catalog.rules().training().mindHungerPerPoint(); long total = (long) p.hungerRemainder + actualHungerRestored;
        gain(p, "mind", total / interval); p.hungerRemainder = (int) (total % interval); changed(p);
    }
    /** Adapter must exclude teleporting, forced displacement, flight, mounts, falling and swimming. */
    public synchronized void onGroundTravel(UUID player, double validMetres) {
        onGroundTravel(player, validMetres, true, true);
    }
    /** Eligibility flags represent which training tracks were unlocked at the start of the sampled interval. */
    public synchronized void onGroundTravel(UUID player, double validMetres, boolean trainBody, boolean trainFootwork) {
        finite(validMetres, 0, 1_000_000, "ground travel"); Player p = read(player);
        if (!p.mindUnlocked || !p.bodyUnlocked || validMetres == 0 || !trainBody && !trainFootwork) return;
        p = edit(player); TrainingRules t = catalog.rules().training(); boolean footworkAtStart = p.footworkUnlocked && trainFootwork;
        if (trainBody) {
            double bodyTotal = p.bodyMetres + validMetres; long bodyPoints = (long) Math.floor(bodyTotal / t.bodyMetersPerPoint());
            p.bodyMetres = bodyTotal - bodyPoints * t.bodyMetersPerPoint(); gain(p, "body", bodyPoints); unlockFootwork(p);
        }
        if (footworkAtStart) {
            double footTotal = p.footworkMetres + validMetres; long footPoints = (long) Math.floor(footTotal / t.footworkMetersPerPoint());
            p.footworkMetres = footTotal - footPoints * t.footworkMetersPerPoint(); gain(p, "footwork", footPoints);
        }
        changed(p);
    }
    public synchronized void onMined(UUID player, int count) {
        if (count < 0 || count > MAX) throw bad("invalid mined block count"); Player p = read(player);
        if (!p.mindUnlocked || !p.bodyUnlocked || count == 0) return;
        p = edit(player); int interval = catalog.rules().training().bodyMinedPerPoint(); long total = (long) p.minedRemainder + count;
        gain(p, "body", total / interval); p.minedRemainder = (int) (total % interval); unlockFootwork(p); changed(p);
    }
    /** Count only real online server ticks, never time advanced by sleeping or /time. */
    public synchronized void onActiveTicks(UUID player, long count) {
        if (count < 0 || count > MAX) throw bad("invalid active tick count"); Player p = read(player);
        if (!p.mindUnlocked || count == 0) return;
        p = edit(player); long interval = catalog.rules().training().mindActiveTicksPerPoint(); long total = p.activeTicks + count;
        gain(p, "mind", total / interval); p.activeTicks = total % interval; changed(p);
    }
    public synchronized boolean recordRapierKill(UUID player, long epochDay) { return score(player, "rapier", epochDay); }
    public synchronized boolean recordArcheryKill(UUID player, long epochDay) { return score(player, "bow", epochDay); }
    /** Verified damage contact only: one call per living target actually hurt, no air-swing credit. */
    public synchronized boolean recordDualHit(UUID player, long tick, long epochDay) {
        gameTick(tick); if (!score(player, "dual_blade", epochDay)) return false;
        Player p = edit(player); int cap = dualCap(p);
        if (cap > 0) { p.dualStacks = Math.min(cap, p.dualStacks + 1); p.dualLastHitTick = tick; }
        changed(p); return true;
    }
    public synchronized boolean trialPassed(UUID player) { Player p = read(player); if (p.trialPassed) return false; p = edit(player); p.trialPassed = true; changed(p); return true; }
    public synchronized boolean dragonKilled(UUID player) { Player p = read(player); if (p.dragonKilled) return false; p = edit(player); p.dragonKilled = true; changed(p); return true; }
    public synchronized void bossDefeated(UUID player) {
        Player p = edit(player); LongTermRules r = catalog.rules().longTerm();
        p.bossInsightBonus = Math.min(r.bossInsightBonusCap(), p.bossInsightBonus + r.bossInsightBonus()); changed(p);
    }
    public synchronized void tick(UUID player, long tick) {
        gameTick(tick); Player p = read(player); if (p.dualStacks == 0) return;
        p = edit(player); int cap = dualCap(p); int interval = catalog.rules().dual().decayTicks();
        if (p.dualLastHitTick == UNSET || tick < p.dualLastHitTick) { p.dualLastHitTick = tick; changed(p); return; }
        long elapsed = (tick - p.dualLastHitTick) / interval; int next = (int) Math.max(0, Math.min(cap, p.dualStacks - elapsed));
        if (next != p.dualStacks) { p.dualStacks = next; p.dualLastHitTick += elapsed * interval; changed(p); }
    }
    /** UTC epoch-day; old daily base decay and three-day idle mastery decay are replayed exactly once. */
    public synchronized void maintain(UUID player, long epochDay) {
        day(epochDay); Player p = edit(player); LongTermRules r = catalog.rules().longTerm();
        if (p.lastDecayDay == UNSET) { p.lastDecayDay = epochDay; changed(p); return; }
        if (epochDay < p.lastDecayDay) return; // Clock rollback must never repeat decay or grant growth.
        boolean changed = false;
        if (r.enabled()) {
            long days = epochDay - p.lastDecayDay;
            if (days > 0) {
                p.mind = decay(p.mind, r.baseDailyDecay(), days); p.body = decay(p.body, r.baseDailyDecay(), days);
                p.footwork = decay(p.footwork, r.baseDailyDecay(), days); changed = true;
            }
            for (String key : SCORE_KEYS) {
                long anchor = p.growthDays.getOrDefault(key, p.lastDecayDay);
                long periods = Math.max(0, epochDay - anchor) / r.masteryDecayDays();
                if (periods > 0) {
                    int score = decay(score(p, key), r.masteryDecay(), periods); setScore(p, key, score);
                    p.growthDays.put(key, anchor + periods * r.masteryDecayDays()); changed = true;
                }
            }
        }
        if (epochDay > p.lastDecayDay) { p.lastDecayDay = epochDay; changed = true; }
        if (revokeInvalidBranches(p)) changed = true;
        int capped = Math.min(p.dualStacks, dualCap(p)); if (capped != p.dualStacks) { p.dualStacks = capped; changed = true; }
        if (changed) changed(p);
    }
    public synchronized Snapshot snapshot() {
        Map<UUID, SavedProgress> saved = new LinkedHashMap<>(); players.forEach((id, player) -> saved.put(id, player.save()));
        return new Snapshot(SCHEMA_VERSION, Collections.unmodifiableMap(saved));
    }
    /** Restore atomically; malformed saves leave existing in-memory progress untouched. Removed IDs survive. */
    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot); if (snapshot.schemaVersion() != SCHEMA_VERSION || snapshot.players() == null || snapshot.players().size() > 100_000) throw bad("invalid progression snapshot");
        Map<UUID, Player> restored = new LinkedHashMap<>();
        for (var entry : snapshot.players().entrySet()) {
            UUID id = Objects.requireNonNull(entry.getKey()); Player p = new Player(Objects.requireNonNull(entry.getValue()));
            capFootwork(p);
            Set<String> choices = new HashSet<>();
            for (Node node : catalog.nodes()) if (p.learned.contains(node.id()) && !node.exclusiveGroup().isEmpty() && !choices.add(node.exclusiveGroup())) throw bad("save contains mutually exclusive branches");
            restored.put(id, p);
        }
        players.clear(); players.putAll(restored); revision++;
    }
    private boolean score(UUID player, String tree, long epochDay) {
        day(epochDay); maintain(player, epochDay); Player p = read(player);
        Effect root = switch (tree) { case "rapier" -> Effect.ROOT_RAPIER; case "dual_blade" -> Effect.ROOT_DUAL; default -> Effect.ROOT_BOW; };
        if (!effect(p, root)) return false;
        p = edit(player); gain(p, tree, 1); p.growthDays.put(tree, Math.max(epochDay, p.lastDecayDay)); changed(p); return true;
    }
    private boolean unlocked(Player p, Node node) {
        // Free creative selections are real unlocks: active effects, prerequisites and rendering follow them.
        if (p.creative.contains(node.id())) return true;
        if (!node.automatic()) {
            if (!p.learned.contains(node.id())) return false;
            // Learned roots are permanent; branch choices keep their old score/stat eligibility.
            if (Set.of(Effect.ROOT_RAPIER, Effect.ROOT_DUAL, Effect.ROOT_BOW).contains(node.effect())) return true;
        }
        return blockers(p, node, false).isEmpty();
    }
    private List<String> blockers(Player p, Node node, boolean learning) {
        List<String> blockers = new ArrayList<>();
        if (learning && !node.automatic() && !p.mindUnlocked) blockers.add("先完成一次有效睡眠，开启心法");
        if (SkillTreeCatalog.isFootwork(node.effect()) && (!p.mindUnlocked || !p.footworkUnlocked)) blockers.add("先开启心法与步法修行");
        for (String dependency : node.prerequisites()) if (!unlocked(p, catalog.node(dependency))) blockers.add("需要前置：" + catalog.node(dependency).label());
        Requirements req = node.requirements();
        if (p.mind.total() < req.mind()) blockers.add("心法总值 " + p.mind.total() + "/" + req.mind());
        if (p.body.total() < req.body()) blockers.add("煅体总值 " + p.body.total() + "/" + req.body());
        if (p.footwork.total() < req.footwork()) blockers.add("步法总值 " + p.footwork.total() + "/" + req.footwork());
        if (!node.tree().equals("footwork") && score(p, node.tree()) < req.score()) blockers.add("武器成长 " + score(p, node.tree()) + "/" + req.score());
        if (req.trial() && !p.trialPassed) blockers.add("完整通过试炼刷怪笼");
        if (req.dragon() && !p.dragonKilled) blockers.add("击杀末影龙");
        if (learning) {
            if (SkillTreeCatalog.isFootworkTechnique(node.effect())
                    && p.footwork.total() / catalog.rules().footwork().selectionPoints() <= footworkSelectionsUsed(p))
                blockers.add("步法选学额度不足（每" + catalog.rules().footwork().selectionPoints() + "步法总值获得一次）");
            Cost cost = node.cost();
            if (p.mind.available() < cost.mind()) blockers.add("心法可用点数 " + p.mind.available() + "/" + cost.mind());
            if (p.body.available() < cost.body()) blockers.add("煅体可用点数 " + p.body.available() + "/" + cost.body());
            if (p.footwork.available() < cost.footwork()) blockers.add("步法可用点数 " + p.footwork.available() + "/" + cost.footwork());
            if (!node.exclusiveGroup().isEmpty()) for (Node other : catalog.nodes())
                if (!other.id().equals(node.id()) && other.exclusiveGroup().equals(node.exclusiveGroup()) && p.learned.contains(other.id())) blockers.add("已选互斥分支：" + other.label());
        }
        return blockers;
    }
    private boolean effect(Player p, Effect effect) { return catalog.nodes().stream().anyMatch(n -> n.effect() == effect && unlocked(p, n)); }
    private int dualCap(Player p) { int cap = 0; for (Node n : catalog.nodes()) if (n.effect() == Effect.DUAL_COMBO_CAP && unlocked(p, n)) cap = Math.max(cap, n.value()); return cap; }
    private String branch(Player p, String group) { for (Node node : catalog.nodes()) if (node.exclusiveGroup().equals(group) && unlocked(p, node)) return node.effect().name(); return "NONE"; }
    private boolean revokeInvalidBranches(Player p) {
        boolean removed = false;
        for (Node node : catalog.nodes()) if (!node.automatic() && (!node.exclusiveGroup().isEmpty() || SkillTreeCatalog.isFootworkTechnique(node.effect()))
                && p.learned.contains(node.id()) && !unlocked(p, node)) removed |= p.learned.remove(node.id());
        return removed;
    }
    private int footworkSelectionsUsed(Player p) {
        return (int) catalog.nodes().stream().filter(n -> SkillTreeCatalog.isFootworkTechnique(n.effect()) && p.learned.contains(n.id())).count();
    }
    private void unlockFootwork(Player p) { if (p.mindUnlocked && p.bodyUnlocked && p.body.total() >= catalog.rules().training().footworkUnlockBodyTotal()) p.footworkUnlocked = true; }
    private void gain(Player p, String key, long base) {
        if (base <= 0) return;
        double percent = key.equals("mind") || key.equals("body") ? p.physiquePercent : insight(p);
        double exact = base * (1 + percent / 100) + p.remainders.getOrDefault(key, 0.0);
        long count = (long) Math.floor(exact + 1e-9); p.remainders.put(key, Math.max(0, exact - count));
        switch (key) {
            case "mind" -> p.mind = add(p.mind, count, MAX);
            case "body" -> p.body = add(p.body, count, MAX);
            case "footwork" -> p.footwork = add(p.footwork, count, catalog.rules().training().footworkCap());
            default -> setScore(p, key, (int) Math.min(MAX, score(p, key) + count));
        }
    }
    private int insight(Player p) { LongTermRules r = catalog.rules().longTerm(); return Math.max(-99, Math.min(r.efficiencyPercentCap(), p.insightBasePercent + p.bossInsightBonus)); }
    private void capFootwork(Player p) { int spent = p.footwork.total() - p.footwork.available(); int total = Math.min(p.footwork.total(), catalog.rules().training().footworkCap()); p.footwork = new Points(total, Math.max(0, total - spent)); }
    private static Points add(Points p, long count, int cap) { int gained = (int) Math.min(count, Math.max(0, cap - p.total())); return new Points(p.total() + gained, p.available() + gained); }
    private static Points spend(Points p, int cost) { return new Points(p.total(), p.available() - cost); }
    private static int score(Player p, String tree) { return switch (tree) { case "rapier" -> p.rapierKills; case "dual_blade" -> p.dualHits; case "bow" -> p.archeryScore; default -> throw bad("unknown growth tree"); }; }
    private static void setScore(Player p, String tree, int value) { switch (tree) { case "rapier" -> p.rapierKills = value; case "dual_blade" -> p.dualHits = value; case "bow" -> p.archeryScore = value; default -> throw bad("unknown growth tree"); } }
    private static Points decay(Points p, double factor, long periods) { int total = decay(p.total(), factor, periods); return new Points(total, Math.min(total, decay(p.available(), factor, periods))); }
    private static int decay(int value, double factor, long periods) {
        // Stop at the rounding fixed point, so a centuries-old save cannot cause an unbounded loop.
        for (long n = 0; n < periods; n++) { int next = (int) Math.round(value * factor); if (next == value) break; value = next; }
        return value;
    }
    private static int levels(int total, List<Integer> thresholds) { return (int) thresholds.stream().filter(value -> total >= value).count(); }
    private Player read(UUID id) { Objects.requireNonNull(id); return players.getOrDefault(id, new Player()); }
    private Player edit(UUID id) { return players.computeIfAbsent(Objects.requireNonNull(id), ignored -> new Player()); }
    private void changed(Player p) { if (p.revision == Long.MAX_VALUE || revision == Long.MAX_VALUE) throw new IllegalStateException("progression revision exhausted"); p.revision++; revision++; }
    private static <T> Set<T> immutable(Set<T> set) { return Collections.unmodifiableSet(new LinkedHashSet<>(set)); }
    private static void bound(int value) { if (value < 0 || value > MAX) throw bad("progression points outside bound"); }
    private static void day(long value) { if (value < -1_000_000 || value > 1_000_000) throw bad("invalid epoch day"); }
    private static void gameTick(long value) { if (value < 0 || value > Long.MAX_VALUE / 2) throw bad("invalid server tick"); }
    private static void finite(double value, double min, double max, String name) { if (!Double.isFinite(value) || value < min || value > max) throw bad("invalid " + name); }
    private static IllegalArgumentException bad(String text) { return new IllegalArgumentException(text); }

    private static final class Player {
        long revision; boolean mindUnlocked, bodyUnlocked, footworkUnlocked, trialPassed, dragonKilled, migrated, aptitudesInitialized, physiqueRerolled;
        Points mind = new Points(0, 0), body = new Points(0, 0), footwork = new Points(0, 0);
        int rapierKills, dualHits, archeryScore, dualStacks, hungerRemainder, minedRemainder, physiquePercent, insightBasePercent, bossInsightBonus;
        Set<String> learned = new LinkedHashSet<>(), eatenFoods = new LinkedHashSet<>(), creative = new LinkedHashSet<>();
        long activeTicks, lastDecayDay = UNSET, dualLastHitTick = UNSET, incomingTick = UNSET;
        double bodyMetres, footworkMetres, hostileHpLoss;
        Map<String, Double> remainders = new LinkedHashMap<>(); Map<String, Long> growthDays = new LinkedHashMap<>();
        Set<UUID> incoming = new HashSet<>();
        Player() {}
        Player(SavedProgress s) {
            if (s.revision() < 0) throw bad("invalid progression revision");
            revision = s.revision(); mindUnlocked = s.mindUnlocked(); bodyUnlocked = s.bodyUnlocked(); footworkUnlocked = s.footworkUnlocked();
            mind = Objects.requireNonNull(s.mind()); body = Objects.requireNonNull(s.body()); footwork = Objects.requireNonNull(s.footwork());
            // Gson bypasses record compact validation only on very old Gson, so validate again on restore.
            mind = new Points(mind.total(), mind.available()); body = new Points(body.total(), body.available()); footwork = new Points(footwork.total(), footwork.available());
            bound(s.rapierKills()); bound(s.dualHits()); bound(s.archeryScore());
            rapierKills = s.rapierKills(); dualHits = s.dualHits(); archeryScore = s.archeryScore();
            if (s.dualStacks() < 0 || s.dualStacks() > 12) throw bad("invalid combo stacks"); dualStacks = s.dualStacks();
            trialPassed = s.trialPassed(); dragonKilled = s.dragonKilled(); migrated = s.migrated();
            learned = new LinkedHashSet<>(Objects.requireNonNull(s.learnedNodes())); eatenFoods = new LinkedHashSet<>(Objects.requireNonNull(s.eatenFoods()));
            // Older saves and older JSON snapshots have no creativeNodes field; they must restore as no free learning.
            creative = s.creativeNodes() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s.creativeNodes());
            if (learned.size() > 1024 || eatenFoods.size() > 100_000 || creative.size() > 1024) throw bad("excessive progression IDs");
            learned.forEach(SkillTreeCatalog::validId); eatenFoods.forEach(SkillTreeCatalog::validId); creative.forEach(SkillTreeCatalog::validId);
            if (s.activeTicks() < 0 || s.activeTicks() > MAX || s.hungerRemainder() < 0 || s.hungerRemainder() > MAX || s.minedRemainder() < 0 || s.minedRemainder() > MAX) throw bad("invalid training remainder");
            activeTicks = s.activeTicks(); hungerRemainder = s.hungerRemainder(); minedRemainder = s.minedRemainder();
            finite(s.bodyMetres(), 0, 1_000_000, "body metres"); finite(s.footworkMetres(), 0, 1_000_000, "footwork metres"); finite(s.hostileHpLoss(), 0, 100_000, "HP loss");
            bodyMetres = s.bodyMetres(); footworkMetres = s.footworkMetres(); hostileHpLoss = s.hostileHpLoss();
            aptitudesInitialized = s.aptitudesInitialized(); physiqueRerolled = s.physiqueRerolled();
            if (s.physiquePercent() < -99 || s.physiquePercent() > 100 || s.insightBasePercent() < -99 || s.insightBasePercent() > 100 || s.bossInsightBonus() < 0 || s.bossInsightBonus() > 1000) throw bad("invalid aptitude");
            physiquePercent = s.physiquePercent(); insightBasePercent = s.insightBasePercent(); bossInsightBonus = s.bossInsightBonus();
            Objects.requireNonNull(s.gainRemainders()).forEach((key, value) -> { if (!GAIN_KEYS.contains(key)) throw bad("unknown gain remainder"); finite(value, 0, 1, "fractional gain"); remainders.put(key, value); });
            Objects.requireNonNull(s.growthDays()).forEach((key, value) -> { if (!SCORE_KEYS.contains(key)) throw bad("unknown growth anchor"); day(value); growthDays.put(key, value); });
            if (s.lastDecayDay() != UNSET) day(s.lastDecayDay()); lastDecayDay = s.lastDecayDay();
            if (s.dualLastHitTick() != UNSET) gameTick(s.dualLastHitTick()); dualLastHitTick = s.dualLastHitTick();
        }
        SavedProgress save() {
            return new SavedProgress(revision, mindUnlocked, bodyUnlocked, footworkUnlocked, mind, body, footwork,
                    rapierKills, dualHits, archeryScore, dualStacks, trialPassed, dragonKilled, migrated, immutable(learned), immutable(eatenFoods),
                    activeTicks, hungerRemainder, minedRemainder, bodyMetres, footworkMetres, hostileHpLoss, aptitudesInitialized,
                    physiquePercent, insightBasePercent, bossInsightBonus, physiqueRerolled, Map.copyOf(remainders), Map.copyOf(growthDays), lastDecayDay, dualLastHitTick,
                    immutable(creative));
        }
    }
}

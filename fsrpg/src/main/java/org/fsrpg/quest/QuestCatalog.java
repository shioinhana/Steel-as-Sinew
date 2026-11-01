package org.fsrpg.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates an entire catalog before it can become the server's active config. */
public final class QuestCatalog {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:/-]{1,128}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final Map<String, QuestDefinition> byId;
    private final List<QuestDefinition> definitions;

    private QuestCatalog(Map<String, QuestDefinition> byId) {
        this.byId = Map.copyOf(byId);
        this.definitions = List.copyOf(byId.values());
    }

    public static QuestCatalog of(Collection<QuestDefinition> definitions) {
        if (definitions == null || definitions.size() > 1024) fail("quest count must be at most 1024");
        Map<String, QuestDefinition> indexed = new LinkedHashMap<>();
        for (QuestDefinition quest : definitions) {
            validate(quest);
            if (indexed.putIfAbsent(quest.id(), quest) != null) fail("duplicate quest id: " + quest.id());
        }
        for (QuestDefinition quest : indexed.values()) {
            for (String required : quest.prerequisites()) {
                if (!indexed.containsKey(required)) fail(quest.id() + ": unknown prerequisite " + required);
            }
        }
        Map<String, Integer> visits = new HashMap<>();
        for (String id : indexed.keySet()) visit(id, indexed, visits, new ArrayList<>());
        return new QuestCatalog(indexed);
    }

    public List<QuestDefinition> definitions() { return definitions; }

    public QuestDefinition get(String id) {
        QuestDefinition definition = byId.get(id);
        if (definition == null) throw new IllegalArgumentException("unknown quest: " + id);
        return definition;
    }

    public boolean contains(String id) { return byId.containsKey(id); }

    private static void validate(QuestDefinition quest) {
        if (quest == null) fail("quest must not be null");
        validId(quest.id(), "quest id");
        if (quest.label() == null || quest.label().isBlank() || quest.label().length() > 256) {
            fail(quest.id() + ": label must contain 1..256 characters");
        }
        if (quest.description().length() > 4096) fail(quest.id() + ": description exceeds 4096 characters");
        QuestDefinition.Rules rules=quest.rules();
        if(rules.difficulty().isBlank() || rules.difficulty().length()>32)fail(quest.id()+": difficulty must contain 1..32 characters");
        if(rules.completionCooldownSeconds()<0 || rules.completionCooldownSeconds()>31_536_000L
                || rules.abandonCooldownSeconds()<0 || rules.abandonCooldownSeconds()>31_536_000L
                || rules.timeLimitSeconds()<0 || rules.timeLimitSeconds()>31_536_000L)fail(quest.id()+": durations must be 0..31536000 seconds");
        if(rules.stages().size()>64)fail(quest.id()+": at most 64 stages");
        Set<String> stages=new HashSet<>();
        for(var stage:rules.stages()) {
            if(stage==null)fail(quest.id()+": null stage");
            validId(stage.id(),quest.id()+": stage id");
            if(!stages.add(stage.id()))fail(quest.id()+": duplicate stage id");
            if(stage.label()==null || stage.label().isBlank() || stage.label().length()>256)fail(quest.id()+": invalid stage label");
            validateRewards(stage.rewards(),quest.id()+"/"+stage.id());
        }
        if(rules.examRank()==QuestDefinition.Rank.D || (rules.examRank()!=null && (quest.autoAccept() || rules.daily() || quest.repeatable())))
            fail(quest.id()+": exams cannot target D or be auto-accepted, daily, or repeatable");
        if(rules.bountyTier()!=QuestDefinition.BountyTier.NONE && quest.objectives().stream().anyMatch(o->o==null || o.type()!=QuestDefinition.ObjectiveType.PLAYER_KILL))
            fail(quest.id()+": bounty orders must target players only; use ordinary elite hunt quests for monsters");
        Set<String> prerequisiteIds = new HashSet<>();
        for (String required : quest.prerequisites()) {
            validId(required, quest.id() + ": prerequisite");
            if (!prerequisiteIds.add(required)) fail(quest.id() + ": duplicate prerequisite " + required);
        }
        if (quest.objectives().isEmpty() || quest.objectives().size() > 64) {
            fail(quest.id() + ": objectives must contain 1..64 entries");
        }
        Set<String> objectiveIds = new HashSet<>();
        for (QuestDefinition.Objective objective : quest.objectives()) {
            if (objective == null) fail(quest.id() + ": objective must not be null");
            validId(objective.id(), quest.id() + ": objective id");
            if (!objectiveIds.add(objective.id())) fail(quest.id() + ": duplicate objective id " + objective.id());
            if (objective.type() == null) fail(quest.id() + "/" + objective.id() + ": unknown objective type");
            if (objective.count() < 1 || objective.count() > 1_000_000) {
                fail(quest.id() + "/" + objective.id() + ": count must be 1..1000000");
            }
            if(objective.stage()<0 || (rules.stages().isEmpty()?objective.stage()!=0:objective.stage()>=rules.stages().size()))fail(quest.id()+": invalid objective stage index");
            if(objective.inventory() && objective.type()!=QuestDefinition.ObjectiveType.COLLECT)fail(quest.id()+": inventory mode is only valid for COLLECT");
            QuestDefinition.Criteria criteria=objective.criteria();
            if((criteria.minLevel()!=null && (criteria.minLevel()<0 || criteria.minLevel()>1_000_000))
                    || (criteria.maxLevel()!=null && (criteria.maxLevel()<0 || criteria.maxLevel()>1_000_000))
                    || (criteria.minLevel()!=null && criteria.maxLevel()!=null && criteria.minLevel()>criteria.maxLevel()))fail(quest.id()+": invalid monster level range");
            if(criteria.requiredTag()!=null)validId(criteria.requiredTag(),quest.id()+": required tag");
            if(criteria.entityName()!=null && (criteria.entityName().isBlank() || criteria.entityName().length()>256))fail(quest.id()+": invalid entity name filter");
            if(criteria.playerUuid()!=null) {
                try { if(!UUID.fromString(criteria.playerUuid()).toString().equals(criteria.playerUuid()))fail(quest.id()+": noncanonical player UUID"); }
                catch(IllegalArgumentException error){fail(quest.id()+": invalid player UUID");}
                if(objective.type()!=QuestDefinition.ObjectiveType.PLAYER_KILL)fail(quest.id()+": player UUID filter requires PLAYER_KILL");
            }
            if(objective.type()==QuestDefinition.ObjectiveType.PLAYER_KILL && !"minecraft:player".equals(objective.target()))fail(quest.id()+": PLAYER_KILL requires minecraft:player target");
            if(objective.type()==QuestDefinition.ObjectiveType.KILL && "minecraft:player".equals(objective.target()))fail(quest.id()+": use PLAYER_KILL for player targets");
            if (objective.type() == QuestDefinition.ObjectiveType.VISIT) {
                if (objective.count() != 1) fail(quest.id() + ": VISIT count must be 1");
                QuestDefinition.Location location = objective.location();
                if (location == null) fail(quest.id() + ": VISIT requires location");
            } else if (objective.type() == QuestDefinition.ObjectiveType.USE_SKILL) {
                validId(objective.target(), quest.id() + ": skill target");
            } else {
                validResourceId(objective.target(), quest.id() + ": objective target");
                if(objective.type()==QuestDefinition.ObjectiveType.COLLECT && "minecraft:air".equals(objective.target()))fail(quest.id()+": cannot collect air");
            }
            if(objective.location()!=null)validateLocation(objective.location(),quest.id());
        }
        for(int stage=0;stage<rules.stages().size();stage++) {
            final int index=stage;
            if(quest.objectives().stream().noneMatch(o->o.stage()==index))fail(quest.id()+": every stage must have objectives");
        }
        validateRewards(quest.rewards(),quest.id());
    }

    private static void validateLocation(QuestDefinition.Location location,String id) {
        validResourceId(location.dimension(),id+": location dimension");
        if(!coordinate(location.x()) || !coordinate(location.y()) || !coordinate(location.z())
                || !Double.isFinite(location.radius()) || location.radius()<=0 || location.radius()>10_000)fail(id+": invalid location coordinates or radius");
    }
    private static void validateRewards(QuestDefinition.Rewards rewards,String id) {
        if(rewards.xp()<0 || rewards.xp()>1_000_000)fail(id+": reward xp must be 0..1000000");
        if(rewards.reputation()<0 || rewards.reputation()>1_000_000)fail(id+": reputation must be 0..1000000");
        if(rewards.items().size()>64 || rewards.currency().size()>64 || rewards.choices().size()>64 || rewards.randomPool().size()>64)fail(id+": reward lists at most 64 entries");
        var all=new ArrayList<QuestDefinition.RewardItem>();all.addAll(rewards.items());all.addAll(rewards.currency());all.addAll(rewards.choices());
        double total=0;
        for(var entry:rewards.randomPool()) {
            if(entry==null || !Double.isFinite(entry.weight()) || entry.weight()<=0 || entry.weight()>1_000_000)fail(id+": random weights must be finite in (0,1000000]");
            total+=entry.weight();all.add(entry.item());
        }
        if(!Double.isFinite(total) || rewards.randomRolls()<0 || rewards.randomRolls()>16 || (rewards.randomPool().isEmpty() && rewards.randomRolls()!=0))fail(id+": invalid random reward rolls");
        for(var item:all) {
            if(item==null)fail(id+": null reward item");
            validResourceId(item.id(),id+": reward item id");
            if("minecraft:air".equals(item.id()) || item.count()<1 || item.count()>4096)fail(id+": invalid reward item count/id");
        }
    }

    private static void visit(String id, Map<String, QuestDefinition> indexed,
                              Map<String, Integer> visits, List<String> stack) {
        if (visits.getOrDefault(id, 0) == 2) return;
        if (visits.getOrDefault(id, 0) == 1) fail("cyclic quest prerequisites: " + stack + " -> " + id);
        visits.put(id, 1);
        stack.add(id);
        for (String dependency : indexed.get(id).prerequisites()) visit(dependency, indexed, visits, stack);
        stack.removeLast();
        visits.put(id, 2);
    }

    private static boolean coordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 30_000_000; }
    private static void validId(String value, String description) {
        if (value == null || !ID.matcher(value).matches()) fail(description + " is invalid: " + value);
    }
    private static void validResourceId(String value, String description) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) fail(description + " must be namespace:path: " + value);
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}

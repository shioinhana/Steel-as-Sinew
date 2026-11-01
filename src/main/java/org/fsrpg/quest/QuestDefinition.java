package org.fsrpg.quest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Text-editable quest data. Identifiers remain plain strings, independent of Minecraft. */
public record QuestDefinition(String id, String label, String description, List<String> prerequisites,
        List<Objective> objectives, Rewards rewards, boolean repeatable, boolean autoAccept, Rules rules) {
    public QuestDefinition(String id, String label, String description, List<String> prerequisites,
            List<Objective> objectives, Rewards rewards, boolean repeatable, boolean autoAccept) {
        this(id,label,description,prerequisites,objectives,rewards,repeatable,autoAccept,null);
    }
    public QuestDefinition {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        rewards = rewards == null ? new Rewards(List.of(),0) : rewards;
        description = description == null ? "" : description;
        rules = rules == null ? Rules.defaults() : rules;
    }
    public enum Rank { D, C, B, A, S }
    public enum BountyTier { NONE, NORMAL, HIGH, S }
    public enum ObjectiveType { KILL, COLLECT, VISIT, USE_SKILL, PLAYER_KILL }
    public record Rules(String difficulty, Rank minimumRank, BountyTier bountyTier,
            long completionCooldownSeconds, long abandonCooldownSeconds, Boolean allowReacceptAfterAbandon,
            long timeLimitSeconds, boolean daily, Rank examRank, List<Stage> stages) {
        public Rules {
            difficulty = difficulty == null ? "D" : difficulty;
            minimumRank = minimumRank == null ? Rank.D : minimumRank;
            bountyTier = bountyTier == null ? BountyTier.NONE : bountyTier;
            allowReacceptAfterAbandon = allowReacceptAfterAbandon == null ? Boolean.TRUE : allowReacceptAfterAbandon;
            stages = stages == null ? List.of() : List.copyOf(stages);
        }
        public static Rules defaults() { return new Rules("D",Rank.D,BountyTier.NONE,0,0,true,0,false,null,List.of()); }
    }
    public record Stage(String id, String label, Rewards rewards) {
        public Stage { rewards = rewards == null ? new Rewards(List.of(),0) : rewards; }
    }
    public record Criteria(Integer minLevel, Integer maxLevel, boolean elite, String requiredTag, String playerUuid, boolean boss,String entityName) {
        public Criteria(Integer minLevel,Integer maxLevel,boolean elite,String requiredTag,String playerUuid,boolean boss) {
            this(minLevel,maxLevel,elite,requiredTag,playerUuid,boss,null);
        }
        public static Criteria any() { return new Criteria(null,null,false,null,null,false); }
    }
    public record Objective(String id, ObjectiveType type, String target, int count, Location location,
            Criteria criteria, int stage, boolean inventory) {
        public Objective(String id,ObjectiveType type,String target,int count,Location location) {
            this(id,type,target,count,location,null,0,false);
        }
        public Objective { criteria = criteria == null ? Criteria.any() : criteria; }
    }
    public record KillContext(String entityType,int level,boolean elite,Set<String> tags,UUID victim,
            boolean player,String dimension,double x,double y,double z,String entityName) {
        public KillContext(String entityType,int level,boolean elite,Set<String> tags,UUID victim,
                boolean player,String dimension,double x,double y,double z) {
            this(entityType,level,elite,tags,victim,player,dimension,x,y,z,null);
        }
        public KillContext { tags = tags == null ? Set.of() : Set.copyOf(tags); }
    }
    public record Location(String dimension,double x,double y,double z,double radius) {
        public boolean contains(String dimension,double x,double y,double z) {
            if (!this.dimension.equals(dimension) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
            double dx=x-this.x,dy=y-this.y,dz=z-this.z;
            return dx*dx+dy*dy+dz*dz <= radius*radius;
        }
    }
    public record Rewards(List<RewardItem> items,int xp,List<RewardItem> currency,List<RewardItem> choices,
            List<WeightedItem> randomPool,int randomRolls,int reputation) {
        public Rewards(List<RewardItem> items,int xp,List<RewardItem> currency,List<RewardItem> choices,List<WeightedItem> randomPool,int randomRolls) {
            this(items,xp,currency,choices,randomPool,randomRolls,0);
        }
        public Rewards(List<RewardItem> items,int xp) { this(items,xp,List.of(),List.of(),List.of(),0); }
        public Rewards {
            items = items == null ? List.of() : List.copyOf(items);
            currency = currency == null ? List.of() : List.copyOf(currency);
            choices = choices == null ? List.of() : List.copyOf(choices);
            randomPool = randomPool == null ? List.of() : List.copyOf(randomPool);
            if (!randomPool.isEmpty() && randomRolls == 0) randomRolls = 1;
        }
    }
    public record WeightedItem(RewardItem item,double weight) {}
    public record RewardItem(String id,int count) {}
}

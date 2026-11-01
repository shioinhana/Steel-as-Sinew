package org.fsrpg.quest;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.fsrpg.quest.QuestDefinition.*;

/** Server-authoritative, UUID-isolated quest ledger. Only trusted adapters may feed events. */
public final class QuestService {
    public static final int SCHEMA_VERSION=2;
    public enum Status { LOCKED, AVAILABLE, ACTIVE, READY, CLAIMED, FAILED, ABANDONED, COOLDOWN }
    public record Attempt(Status status,long endedAt,Map<String,Integer> objectiveCounts) {
        public Attempt { objectiveCounts=objectiveCounts==null?Map.of():Map.copyOf(objectiveCounts); }
    }
    public record QuestProgress(String questId,Status status,Map<String,Integer> objectiveCounts,int completions,
            boolean tracked,long cooldownUntil,long deadline,int stage,Set<String> claimedStages,String lockReason,List<Attempt> history) {
        public QuestProgress { objectiveCounts=Map.copyOf(objectiveCounts); claimedStages=Set.copyOf(claimedStages);history=List.copyOf(history); }
        public QuestProgress(String id,Status status,Map<String,Integer> counts,int completions) {
            this(id,status,counts,completions,false,0,0,0,Set.of(),"",List.of());
        }
    }
    public record StoredProgress(Status status,Map<String,Integer> objectiveCounts,int completions,
            boolean tracked,long cooldownUntil,long deadline,long acceptedDay,Set<String> claimedStages,long rewardSeed,List<Attempt> history) {
        public StoredProgress {
            objectiveCounts=objectiveCounts==null?Map.of():Map.copyOf(objectiveCounts);
            claimedStages=claimedStages==null?Set.of():Set.copyOf(claimedStages);
            history=history==null?List.of():List.copyOf(history);
        }
        public StoredProgress(Status status,Map<String,Integer> counts,int completions) {
            this(status,counts,completions,false,0,0,0,Set.of(),0,List.of());
        }
    }
    public record DailySelection(long day,List<String> questIds) {
        public DailySelection { questIds=questIds==null?List.of():List.copyOf(questIds); }
    }
    public record Snapshot(int schemaVersion,Map<UUID,Map<String,StoredProgress>> players,Map<UUID,Rank> ranks,Map<UUID,Integer> reputations,Map<UUID,DailySelection> dailySelections) {
        public Snapshot(int version,Map<UUID,Map<String,StoredProgress>> players) { this(version,players,Map.of(),Map.of(),Map.of()); }
        public Snapshot(int version,Map<UUID,Map<String,StoredProgress>> players,Map<UUID,Rank> ranks) { this(version,players,ranks,Map.of(),Map.of()); }
        public Snapshot(int version,Map<UUID,Map<String,StoredProgress>> players,Map<UUID,Rank> ranks,Map<UUID,Integer> reputations) { this(version,players,ranks,reputations,Map.of()); }
    }
    public record PromotionProgress(Rank currentRank,Rank targetRank,int totalCompletions,int requiredTotal,
            Map<String,Integer> requiredQuests,Map<String,Integer> completedQuests,boolean eligible,String examQuest) {}
    private final QuestCatalog catalog;
    private final QuestSettings settings;
    private final Clock clock;
    private final Map<UUID,Map<String,StoredProgress>> players=new LinkedHashMap<>();
    private final Map<UUID,Rank> ranks=new LinkedHashMap<>();
    private final Map<UUID,Integer> reputations=new LinkedHashMap<>();
    private final Map<UUID,DailySelection> dailySelections=new LinkedHashMap<>();
    private final SecureRandom entropy=new SecureRandom();
    private long revision;
    public QuestService(QuestCatalog catalog) { this(catalog,QuestSettings.defaults()); }
    public QuestService(QuestCatalog catalog,QuestSettings settings) { this(catalog,settings,Clock.systemUTC()); }
    public QuestService(QuestCatalog catalog,QuestSettings settings,Clock clock) {
        this.catalog=Objects.requireNonNull(catalog); this.settings=Objects.requireNonNull(settings); this.clock=Objects.requireNonNull(clock);
        settings.validate(catalog);
    }
    public QuestCatalog catalog(){return catalog;}
    public QuestSettings settings(){return settings;}
    public synchronized long revision(){return revision;}
    public synchronized Rank rank(UUID player){Objects.requireNonNull(player);return ranks.getOrDefault(player,Rank.D);}
    public synchronized int reputation(UUID player){Objects.requireNonNull(player);return reputations.getOrDefault(player,0);}
    public synchronized PromotionProgress promotionProgress(UUID player) {
        var state=playerState(player); Rank current=rank(player);
        var next=settings.promotions().stream().filter(p->p.rank().ordinal()==current.ordinal()+1).findFirst().orElse(null);
        long total=state.values().stream().mapToLong(StoredProgress::completions).sum();
        if(next==null)return new PromotionProgress(current,null,(int)Math.min(Integer.MAX_VALUE,total),0,Map.of(),Map.of(),false,"");
        Map<String,Integer> completed=new LinkedHashMap<>();
        next.requiredQuests().forEach((id,n)->completed.put(id,state.containsKey(id)?state.get(id).completions():0));
        boolean eligible=total>=next.totalCompletions() && next.requiredQuests().entrySet().stream().allMatch(e->completed.get(e.getKey())>=e.getValue());
        return new PromotionProgress(current,next.rank(),(int)Math.min(Integer.MAX_VALUE,total),next.totalCompletions(),next.requiredQuests(),Map.copyOf(completed),eligible,next.examQuest());
    }
    /** NPC proximity is checked by the Minecraft adapter before calling this API. */
    public synchronized QuestProgress requestExam(UUID player) {
        var p=promotionProgress(player);
        if(!p.eligible())throw new IllegalStateException("尚未满足称号晋升条件");
        return acceptInternal(player,p.examQuest(),true);
    }
    public synchronized void ensurePlayer(UUID player) {
        tick(player);
        if(catalog.definitions().stream().anyMatch(q->q.rules().daily()))dailySelection(player);
        var state=playerState(player);
        for(var q:catalog.definitions())if(q.autoAccept() && q.rules().examRank()==null && !state.containsKey(q.id()) && gate(player,q,true).isEmpty()) {
            state.put(q.id(),fresh(q,0,List.of()));revision++;
        }
    }
    public synchronized QuestProgress accept(UUID player,String id){return acceptInternal(player,id,false);}
    private QuestProgress acceptInternal(UUID player,String id,boolean exam) {
        tick(player); var q=catalog.get(id); var state=playerState(player);
        if(q.rules().examRank()!=null && !exam)throw new IllegalStateException("请向公会 NPC 申请晋升考试");
        String reason=gate(player,q,true);if(!reason.isEmpty())throw new IllegalStateException(reason);
        var previous=state.get(id);
        state.put(id,fresh(q,previous==null?0:previous.completions(),previous==null?List.of():previous.history()));revision++;
        return view(player,q,state.get(id));
    }
    public synchronized QuestProgress abandon(UUID player,String id) {
        tick(player);var q=catalog.get(id);var previous=playerState(player).get(id);
        if(previous==null || previous.status()!=Status.ACTIVE)throw new IllegalStateException("只能放弃未完成的进行中任务");
        stop(player,q,previous,Status.ABANDONED);return view(player,q,playerState(player).get(id));
    }
    public synchronized QuestProgress fail(UUID player,String id,String reason) {
        var q=catalog.get(id);var previous=playerState(player).get(id);
        if(previous==null || !running(previous))throw new IllegalStateException("任务未在进行中");
        stop(player,q,previous,Status.FAILED);return view(player,q,playerState(player).get(id));
    }
    /** Returns exactly the tasks newly failed by an expiry; callers can issue one notification each. */
    public synchronized List<String> tick(UUID player) {
        var state=playerState(player);List<String> failed=new ArrayList<>();long now=clock.millis(),today=day();
        for(var q:catalog.definitions()) {
            var p=state.get(q.id());if(p==null || !running(p))continue;
            if((p.deadline()>0 && now>=p.deadline()) || (q.rules().daily() && p.status()==Status.ACTIVE && p.acceptedDay()!=today)) {
                stop(player,q,p,Status.FAILED);failed.add(q.id());
            }
        }
        return List.copyOf(failed);
    }
    public synchronized QuestProgress track(UUID player,String id,boolean tracked) {
        tick(player);var q=catalog.get(id);var state=playerState(player);var p=state.get(id);
        if(p==null || !running(p))throw new IllegalStateException("只能追踪已接取任务");
        if(tracked && !p.tracked() && state.entrySet().stream().filter(e->catalog.contains(e.getKey()) && e.getValue().tracked()).count()>=settings.maxTracked())
            throw new IllegalStateException("追踪任务数量已达上限 "+settings.maxTracked());
        state.put(id,copy(p,p.status(),p.objectiveCounts(),tracked,p.claimedStages()));revision++;
        return view(player,q,state.get(id));
    }
    public synchronized List<String> kill(UUID player,String entityType,int amount) {
        return kill(player,new KillContext(entityType,0,false,Set.of(),null,"minecraft:player".equals(entityType),null,0,0,0),amount);
    }
    public synchronized List<String> kill(UUID player,KillContext event,int amount) {
        if(event==null || event.level()<0 || (event.player() && (event.victim()==null || player.equals(event.victim()))))return List.of();
        return advance(player,event.player()?ObjectiveType.PLAYER_KILL:ObjectiveType.KILL,event.entityType(),amount,event,event.dimension(),event.x(),event.y(),event.z());
    }
    /** Legacy acquisition objectives count trusted pickups; inventory=true objectives use inventory(). */
    public synchronized List<String> collect(UUID player,String item,int amount) {return advance(player,ObjectiveType.COLLECT,item,amount,null,null,0,0,0);}
    public synchronized List<String> useSkill(UUID player,String skill) {return advance(player,ObjectiveType.USE_SKILL,skill,1,null,null,0,0,0);}
    public synchronized List<String> visit(UUID player,String dimension,double x,double y,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return List.of();
        return advance(player,ObjectiveType.VISIT,null,1,null,dimension,x,y,z);
    }
    /** Live inventory objectives may lose readiness if the required materials leave the player's inventory. */
    public synchronized List<String> inventory(UUID player,Map<String,Integer> items) {
        Objects.requireNonNull(items);ensurePlayer(player);List<String> ready=new ArrayList<>();var state=playerState(player);
        for(var q:catalog.definitions()) {
            var p=state.get(q.id());if(p==null || !running(p))continue;
            Map<String,Integer> counts=new LinkedHashMap<>(p.objectiveCounts());int stage=currentStage(q,p);
            for(var o:q.objectives())if(o.type()==ObjectiveType.COLLECT && o.inventory() && (q.rules().stages().isEmpty() || o.stage()==stage))
                counts.put(o.id(),Math.min(o.count(),Math.max(0,items.getOrDefault(o.target(),0))));
            updateCounts(q,p,counts,state,ready);
        }
        return List.copyOf(ready);
    }
    public synchronized List<QuestProgress> progress(UUID player){ensurePlayer(player);return catalog.definitions().stream().map(q->view(player,q,playerState(player).get(q.id()))).toList();}
    public synchronized QuestProgress progress(UUID player,String id){ensurePlayer(player);return view(player,catalog.get(id),playerState(player).get(id));}
    public synchronized Rewards claim(UUID player,String id){return claim(player,id,-1);}
    /** Claim markers are set before returning resolved rewards. Persist before issuing external items. */
    public synchronized Rewards claim(UUID player,String id,int choice) {
        tick(player);var q=catalog.get(id);var state=playerState(player);var p=state.get(id);
        if(p==null || p.status()!=Status.READY)throw new IllegalStateException("任务尚未完成");
        if(currentStage(q,p)<q.rules().stages().size())throw new IllegalStateException("请先领取各阶段奖励");
        if(p.completions()==Integer.MAX_VALUE)throw new IllegalStateException("任务完成次数达到上限");
        Rank promoted=q.rules().examRank();
        if(promoted!=null) {
            var promotion=promotionProgress(player);
            if(!promotion.eligible() || promotion.targetRank()!=promoted || !promotion.examQuest().equals(id))throw new IllegalStateException("考试晋升条件已不满足");
        }
        Rewards result=resolve(q.rewards(),choice,p.rewardSeed());
        long cooldown=clock.millis()+q.rules().completionCooldownSeconds()*1000;
        state.put(id,new StoredProgress(Status.CLAIMED,p.objectiveCounts(),p.completions()+1,false,cooldown,0,p.acceptedDay(),p.claimedStages(),p.rewardSeed(),history(p,Status.CLAIMED)));
        if(promoted!=null)ranks.put(player,promoted);
        addReputation(player,result.reputation());
        revision++;ensurePlayer(player);return result;
    }
    public synchronized Rewards claimStage(UUID player,String id,int choice) {
        var q=catalog.get(id);var p=playerState(player).get(id);
        if(p==null || currentStage(q,p)>=q.rules().stages().size())throw new IllegalStateException("没有可领取阶段");
        return claimStage(player,id,q.rules().stages().get(currentStage(q,p)).id(),choice);
    }
    public synchronized Rewards claimStage(UUID player,String id,String stageId,int choice) {
        tick(player);var q=catalog.get(id);var state=playerState(player);var p=state.get(id);
        if(p==null || !running(p))throw new IllegalStateException("任务未在进行中");
        int stage=currentStage(q,p);
        if(stage>=q.rules().stages().size() || !q.rules().stages().get(stage).id().equals(stageId))throw new IllegalStateException("阶段未解锁或已领奖");
        if(q.objectives().stream().filter(o->o.stage()==stage).anyMatch(o->p.objectiveCounts().getOrDefault(o.id(),0)<o.count()))throw new IllegalStateException("阶段尚未完成");
        Rewards result=resolve(q.rules().stages().get(stage).rewards(),choice,p.rewardSeed()^stageId.hashCode());
        Set<String> claimed=new LinkedHashSet<>(p.claimedStages());claimed.add(stageId);
        state.put(id,copy(p,p.status(),p.objectiveCounts(),p.tracked(),claimed));addReputation(player,result.reputation());revision++;return result;
    }
    private Rewards resolve(Rewards rewards,int choice,long seed) {
        if(rewards.choices().isEmpty() && choice>=0)throw new IllegalArgumentException("该奖励没有自选项");
        if(!rewards.choices().isEmpty() && (choice<0 || choice>=rewards.choices().size()))throw new IllegalArgumentException("请选择一项有效奖励");
        if(rewards.currency().isEmpty() && rewards.choices().isEmpty() && rewards.randomPool().isEmpty())return rewards;
        List<RewardItem> items=new ArrayList<>(rewards.items());items.addAll(rewards.currency());
        if(!rewards.choices().isEmpty())items.add(rewards.choices().get(choice));
        if(!rewards.randomPool().isEmpty()) {
            var random=new Random(seed);double total=rewards.randomPool().stream().mapToDouble(WeightedItem::weight).sum();
            for(int roll=0;roll<rewards.randomRolls();roll++) {
                double pick=random.nextDouble()*total;WeightedItem chosen=rewards.randomPool().getLast();
                for(var entry:rewards.randomPool()) {pick-=entry.weight();if(pick<0){chosen=entry;break;}}
                items.add(chosen.item());
            }
        }
        return new Rewards(items,rewards.xp(),List.of(),List.of(),List.of(),0,rewards.reputation());
    }
    private List<String> advance(UUID player,ObjectiveType type,String target,int amount,KillContext kill,String dimension,double x,double y,double z) {
        if(amount<1)return List.of();ensurePlayer(player);var state=playerState(player);List<String> ready=new ArrayList<>();
        for(var q:catalog.definitions()) {
            var p=state.get(q.id());if(p==null || p.status()!=Status.ACTIVE)continue;
            var counts=new LinkedHashMap<>(p.objectiveCounts());int stage=currentStage(q,p);
            for(var o:q.objectives()) {
                if(o.type()!=type || (type==ObjectiveType.COLLECT && o.inventory()) || (!q.rules().stages().isEmpty() && o.stage()!=stage))continue;
                boolean matches=type==ObjectiveType.VISIT?o.location().contains(dimension,x,y,z):Objects.equals(o.target(),target);
                if(matches && kill!=null)matches=matchesKill(o,kill);
                int count=counts.getOrDefault(o.id(),0);
                if(matches && count<o.count())counts.put(o.id(),(int)Math.min((long)o.count(),(long)count+amount));
            }
            updateCounts(q,p,counts,state,ready);
        }
        return List.copyOf(ready);
    }
    private static boolean matchesKill(Objective o,KillContext k) {
        var c=o.criteria();
        if(c.minLevel()!=null && k.level()<c.minLevel() || c.maxLevel()!=null && k.level()>c.maxLevel())return false;
        if(c.elite() && !k.elite() || c.boss() && !k.tags().contains("fsrpg:boss"))return false;
        if(c.requiredTag()!=null && !k.tags().contains(c.requiredTag()))return false;
        if(c.playerUuid()!=null && (k.victim()==null || !c.playerUuid().equals(k.victim().toString())))return false;
        if(c.entityName()!=null && !c.entityName().equals(k.entityName()))return false;
        return o.location()==null || o.location().contains(k.dimension(),k.x(),k.y(),k.z());
    }
    private void updateCounts(QuestDefinition q,StoredProgress p,Map<String,Integer> counts,Map<String,StoredProgress> state,List<String> ready) {
        if(counts.equals(p.objectiveCounts()))return;
        Status status=complete(q,counts)?Status.READY:Status.ACTIVE;
        state.put(q.id(),copy(p,status,counts,p.tracked(),p.claimedStages()));revision++;
        if(status==Status.READY && p.status()!=Status.READY)ready.add(q.id());
    }
    private String gate(UUID player,QuestDefinition q,boolean includeExisting) {
        var state=playerState(player);var p=state.get(q.id());long now=clock.millis();
        if(rank(player).ordinal()<q.rules().minimumRank().ordinal())return "需要 "+q.rules().minimumRank()+" 级冒险者称号";
        if(!q.prerequisites().stream().allMatch(id->state.containsKey(id) && state.get(id).completions()>0))return "前置任务尚未完成";
        if(q.rules().daily() && !dailyAvailable(player,q.id()))return "今日未刷新该日常任务";
        if(q.rules().examRank()!=null) {
            var promotion=promotionProgress(player);
            if(!promotion.eligible() || promotion.targetRank()!=q.rules().examRank() || !promotion.examQuest().equals(q.id()))return "尚未满足晋升条件";
        }
        if(!includeExisting || p==null)return "";
        if(running(p))return "任务已接取";
        if(p.cooldownUntil()>now)return "任务冷却中";
        if(q.rules().daily()) {
            if(p.status()==Status.CLAIMED && p.acceptedDay()==day())return "今日日常任务已完成";
            if(p.acceptedDay()!=day())return "";
        }
        if(p.status()==Status.CLAIMED && !q.repeatable())return "任务已完成且不可重复接取";
        if((p.status()==Status.ABANDONED || p.status()==Status.FAILED) && !q.rules().allowReacceptAfterAbandon())return "该任务不允许重新接取";
        return "";
    }
    public synchronized boolean canAccept(UUID player,String id) {tick(player);return gate(player,catalog.get(id),true).isEmpty();}
    /** A deterministic UTC-instant / configured-zone daily selection; reopening cannot reroll the board. */
    public synchronized boolean dailyAvailable(UUID player,String id) {
        return dailySelection(player).questIds().contains(id);
    }
    private DailySelection dailySelection(UUID player) {
        DailySelection existing=dailySelections.get(player);
        if(existing!=null && existing.day()==day())return existing;
        var state=playerState(player);
        List<String> selected=catalog.definitions().stream().filter(q->q.rules().daily() && rank(player).ordinal()>=q.rules().minimumRank().ordinal()
                    && q.prerequisites().stream().allMatch(required->state.containsKey(required)&&state.get(required).completions()>0))
                .sorted(Comparator.comparingLong(q->dailyKey(player,q.id())))
                .limit(settings.dailyCount()).map(QuestDefinition::id).toList();
        DailySelection result=new DailySelection(day(),selected);dailySelections.put(player,result);revision++;return result;
    }
    private long dailyKey(UUID player,String id) {
        long v=player.getMostSignificantBits()^player.getLeastSignificantBits()^day()*0x9e3779b97f4a7c15L^id.hashCode();
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);
    }
    private QuestProgress view(UUID player,QuestDefinition q,StoredProgress p) {
        String reason=gate(player,q,true);Status status=p==null?(reason.isEmpty()?Status.AVAILABLE:Status.LOCKED):p.status();
        if(p!=null && !running(p) && p.cooldownUntil()>clock.millis())status=Status.COOLDOWN;
        return new QuestProgress(q.id(),status,p==null?Map.of():p.objectiveCounts(),p==null?0:p.completions(),p!=null&&p.tracked(),
                p==null?0:p.cooldownUntil(),p==null?0:p.deadline(),p==null?0:currentStage(q,p),p==null?Set.of():p.claimedStages(),reason,p==null?List.of():p.history());
    }
    private StoredProgress fresh(QuestDefinition q,int completions,List<Attempt> history) {
        Map<String,Integer> counts=new LinkedHashMap<>();q.objectives().forEach(o->counts.put(o.id(),0));
        long deadline=q.rules().timeLimitSeconds()==0?0:clock.millis()+q.rules().timeLimitSeconds()*1000;
        return new StoredProgress(Status.ACTIVE,counts,completions,false,0,deadline,day(),Set.of(),entropy.nextLong(),history);
    }
    private void stop(UUID player,QuestDefinition q,StoredProgress p,Status status) {
        playerState(player).put(q.id(),new StoredProgress(status,p.objectiveCounts(),p.completions(),false,
                clock.millis()+q.rules().abandonCooldownSeconds()*1000,0,p.acceptedDay(),p.claimedStages(),p.rewardSeed(),history(p,status)));revision++;
    }
    private List<Attempt> history(StoredProgress p,Status status) {
        List<Attempt> result=new ArrayList<>(p.history());result.add(new Attempt(status,clock.millis(),p.objectiveCounts()));
        return List.copyOf(result.subList(Math.max(0,result.size()-64),result.size()));
    }
    private static StoredProgress copy(StoredProgress p,Status status,Map<String,Integer> counts,boolean tracked,Set<String> claimed) {
        return new StoredProgress(status,counts,p.completions(),tracked,p.cooldownUntil(),p.deadline(),p.acceptedDay(),claimed,p.rewardSeed(),p.history());
    }
    private static boolean running(StoredProgress p){return p.status()==Status.ACTIVE || p.status()==Status.READY;}
    private static int currentStage(QuestDefinition q,StoredProgress p) {
        int stage=0;while(stage<q.rules().stages().size() && p.claimedStages().contains(q.rules().stages().get(stage).id()))stage++;return stage;
    }
    private static boolean complete(QuestDefinition q,Map<String,Integer> counts){return q.objectives().stream().allMatch(o->counts.getOrDefault(o.id(),0)>=o.count());}
    private long day(){return LocalDate.now(clock.withZone(ZoneId.of(settings.timezone()))).toEpochDay();}
    private Map<String,StoredProgress> playerState(UUID player){return players.computeIfAbsent(Objects.requireNonNull(player),ignored->new LinkedHashMap<>());}
    public synchronized Snapshot snapshot() {
        Map<UUID,Map<String,StoredProgress>> state=new LinkedHashMap<>();players.forEach((id,quests)->state.put(id,Map.copyOf(quests)));
        return new Snapshot(SCHEMA_VERSION,Map.copyOf(state),Map.copyOf(ranks),Map.copyOf(reputations),Map.copyOf(dailySelections));
    }
    /** Schema 1 migration preserves IDs and completion markers. Invalid input never changes live state. */
    public synchronized void restore(Snapshot snapshot) {
        if(snapshot==null || (snapshot.schemaVersion()!=1 && snapshot.schemaVersion()!=SCHEMA_VERSION) || snapshot.players()==null)throw new IllegalArgumentException("unsupported or missing quest snapshot schema");
        Map<UUID,Map<String,StoredProgress>> restored=new LinkedHashMap<>();Map<UUID,Rank> restoredRanks=new LinkedHashMap<>();
        Map<UUID,Integer> restoredReputations=new LinkedHashMap<>();
        Map<UUID,DailySelection> restoredDaily=new LinkedHashMap<>();
        if(snapshot.ranks()!=null)snapshot.ranks().forEach((id,rank)->{if(id==null||rank==null)throw new IllegalArgumentException("invalid saved rank");restoredRanks.put(id,rank);});
        if(snapshot.reputations()!=null)snapshot.reputations().forEach((id,value)->{if(id==null||value==null||value<0)throw new IllegalArgumentException("invalid saved reputation");restoredReputations.put(id,value);});
        if(snapshot.dailySelections()!=null)snapshot.dailySelections().forEach((id,selection)->{
            if(id==null || selection==null || selection.questIds().size()>64 || new HashSet<>(selection.questIds()).size()!=selection.questIds().size())throw new IllegalArgumentException("invalid daily selection");
            for(String questId:selection.questIds())if(questId==null || !questId.matches("[a-z0-9_.:/-]{1,128}"))throw new IllegalArgumentException("invalid daily quest id");
            restoredDaily.put(id,selection);
        });
        for(var player:snapshot.players().entrySet()) {
            if(player.getKey()==null || player.getValue()==null)throw new IllegalArgumentException("invalid player snapshot");
            Map<String,StoredProgress> quests=new LinkedHashMap<>();
            int trackedCount=0;
            for(var entry:player.getValue().entrySet()) {
                String id=entry.getKey();var p=entry.getValue();
                if(id==null || p==null || p.completions()<0 || p.status()==null || p.status()==Status.AVAILABLE || p.status()==Status.LOCKED || p.status()==Status.COOLDOWN
                        || p.cooldownUntil()<0 || p.deadline()<0 || p.history().size()>64 || (p.status()==Status.CLAIMED && p.completions()==0))throw new IllegalArgumentException("invalid saved quest: "+id);
                p.objectiveCounts().forEach((key,count)->{if(key==null||count==null||count<0)throw new IllegalArgumentException("invalid objective count");});
                for(var attempt:p.history()) {
                    if(attempt==null || (attempt.status()!=Status.CLAIMED && attempt.status()!=Status.FAILED && attempt.status()!=Status.ABANDONED)
                            || attempt.endedAt()<0)throw new IllegalArgumentException("invalid task history");
                    attempt.objectiveCounts().forEach((key,count)->{if(key==null||count==null||count<0)throw new IllegalArgumentException("invalid history objective count");});
                }
                for(String claimed:p.claimedStages())if(claimed==null || claimed.isBlank() || claimed.length()>128)throw new IllegalArgumentException("invalid saved claimed stage id");
                if(!catalog.contains(id)){quests.put(id,copy(p,p.status(),p.objectiveCounts(),false,p.claimedStages()));continue;}
                var q=catalog.get(id);Map<String,Integer> counts=new LinkedHashMap<>();q.objectives().forEach(o->counts.put(o.id(),Math.min(o.count(),p.objectiveCounts().getOrDefault(o.id(),0))));
                Status status=running(p)?(complete(q,counts)?Status.READY:Status.ACTIVE):p.status();
                // Preserve old stage IDs so removing and later restoring configured stages cannot award them twice.
                boolean tracked=p.tracked()&&running(p)&&trackedCount<settings.maxTracked();
                if(tracked)trackedCount++;
                quests.put(id,copy(p,status,counts,tracked,p.claimedStages()));
            }
            restored.put(player.getKey(),quests);
        }
        players.clear();players.putAll(restored);ranks.clear();ranks.putAll(restoredRanks);reputations.clear();reputations.putAll(restoredReputations);
        dailySelections.clear();dailySelections.putAll(restoredDaily);revision++;
    }
    private void addReputation(UUID player,int value) {
        if(value>0)reputations.put(player,(int)Math.min(Integer.MAX_VALUE,(long)reputation(player)+value));
    }
}

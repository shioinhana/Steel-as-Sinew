package org.fsrpg.quest;

import java.time.*;
import java.util.*;
import static org.fsrpg.quest.QuestDefinition.*;

/** Meaningful policy and abuse regression tests; no Minecraft/client classes are loaded. */
public final class QuestSystemTest {
    private static int checks;
    private static final UUID PLAYER=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER=UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Rewards EMPTY=new Rewards(List.of(),0);
    public static void main(String[] args) throws Exception {
        materialAndIsolation();filteredKillsAndPlayerOrders();abandonExpiryAndTracking();stageAndRewardLedger();rankExams();dailyRefresh();bossNameAndReputation();migrationAndValidation();persistentLedgerRoundTrip();
        System.out.println("QuestSystemTest passed: "+checks+" checks");
    }
    private static void materialAndIsolation() {
        var wood=objective("wood",ObjectiveType.COLLECT,"minecraft:oak_log",20,null,0,true);
        var iron=objective("iron",ObjectiveType.COLLECT,"minecraft:raw_iron",10,null,0,true);
        var q=quest("supplies",List.of(wood,iron),EMPTY,false,rules(null,null,0,0,true,0,false,null,List.of()));
        var s=service(List.of(q));s.accept(PLAYER,q.id());s.accept(OTHER,q.id());
        s.collect(PLAYER,"minecraft:oak_log",500);
        eq(0,s.progress(PLAYER,q.id()).objectiveCounts().get("wood"),"inventory mode ignores pickup accumulation");
        s.inventory(PLAYER,Map.of("minecraft:oak_log",20));eq(Status(),s.progress(PLAYER,q.id()).status(),"all materials required");
        eq(List.of(q.id()),s.inventory(PLAYER,Map.of("minecraft:oak_log",20,"minecraft:raw_iron",10)),"inventory completion");
        eq(0,s.progress(OTHER,q.id()).objectiveCounts().get("wood"),"other player isolated");
        s.inventory(PLAYER,Map.of("minecraft:oak_log",19,"minecraft:raw_iron",10));
        eq(QuestService.Status.ACTIVE,s.progress(PLAYER,q.id()).status(),"losing live materials revokes readiness");
        rejects(()->s.claim(PLAYER,q.id()),"cannot claim revoked readiness");
        s.inventory(PLAYER,Map.of("minecraft:oak_log",Integer.MAX_VALUE,"minecraft:raw_iron",10));
        eq(20,s.progress(PLAYER,q.id()).objectiveCounts().get("wood"),"inventory count clamps");
        s.claim(PLAYER,q.id());s.inventory(PLAYER,Map.of());eq(QuestService.Status.CLAIMED,s.progress(PLAYER,q.id()).status(),"settled completion stable");
    }
    private static QuestService.Status Status(){return QuestService.Status.ACTIVE;}
    private static void filteredKillsAndPlayerOrders() {
        var c=new Criteria(10,20,true,"captain",null,true);
        var o=new Objective("target",ObjectiveType.KILL,"minecraft:zombie",2,new Location("minecraft:overworld",0,64,0,5),c,0,false);
        var q=quest("elite",List.of(o),EMPTY,false,Rules.defaults());var s=service(List.of(q));s.accept(PLAYER,q.id());
        for(var event:List.of(kill("minecraft:skeleton",15,true,Set.of("captain","fsrpg:boss"),false,0),
                kill("minecraft:zombie",9,true,Set.of("captain","fsrpg:boss"),false,0),
                kill("minecraft:zombie",21,true,Set.of("captain","fsrpg:boss"),false,0),
                kill("minecraft:zombie",15,false,Set.of("captain","fsrpg:boss"),false,0),
                kill("minecraft:zombie",15,true,Set.of("fsrpg:boss"),false,0),
                kill("minecraft:zombie",15,true,Set.of("captain"),false,0),
                kill("minecraft:zombie",15,true,Set.of("captain","fsrpg:boss"),false,5.001)))s.kill(PLAYER,event,1);
        eq(0,s.progress(PLAYER,q.id()).objectiveCounts().get("target"),"species/level/elite/tag/boss/location all enforced");
        s.kill(PLAYER,kill("minecraft:zombie",10,true,Set.of("captain","fsrpg:boss"),false,5),1);
        eq(1,s.progress(PLAYER,q.id()).objectiveCounts().get("target"),"inclusive lower level and location boundaries");
        s.kill(PLAYER,kill("minecraft:zombie",20,true,Set.of("captain","fsrpg:boss"),false,0),1);
        eq(QuestService.Status.READY,s.progress(PLAYER,q.id()).status(),"upper level boundary inclusive");
        var p=new Objective("wanted",ObjectiveType.PLAYER_KILL,"minecraft:player",1,null,new Criteria(null,null,false,null,OTHER.toString(),false),0,false);
        var bounty=quest("bounty",List.of(p),EMPTY,true,rules(Rank.D,BountyTier.HIGH,0,0,true,0,false,null,List.of()));
        var b=service(List.of(bounty));b.accept(PLAYER,bounty.id());
        b.kill(PLAYER,"minecraft:zombie",1);b.kill(PLAYER,"minecraft:player",1);
        b.kill(PLAYER,new KillContext("minecraft:player",0,false,Set.of(),PLAYER,true,null,0,0,0),1);
        b.kill(PLAYER,new KillContext("minecraft:player",0,false,Set.of(),UUID.randomUUID(),true,null,0,0,0),1);
        eq(0,b.progress(PLAYER,bounty.id()).objectiveCounts().get("wanted"),"bounty only counts actual matching other-player victim");
        b.kill(PLAYER,new KillContext("minecraft:player",0,false,Set.of(),OTHER,true,null,0,0,0),1);
        eq(QuestService.Status.READY,b.progress(PLAYER,bounty.id()).status(),"matching player completes bounty");
        eq(BountyTier.HIGH,b.catalog().get("bounty").rules().bountyTier(),"bounty tier separate from D difficulty");
    }
    private static void abandonExpiryAndTracking() {
        var clock=new MutableClock();var rule=rules(null,null,5,3,true,10,false,null,List.of());
        var q=quest("a",List.of(objective("kill",ObjectiveType.KILL,"minecraft:zombie",1,null,0,false)),EMPTY,true,rule);
        var b=quest("b",q.objectives(),EMPTY,true,rule);var s=new QuestService(QuestCatalog.of(List.of(q,b)),new QuestSettings(1,3,"Asia/Shanghai",List.of()),clock);
        s.accept(PLAYER,"a");s.accept(PLAYER,"b");s.track(PLAYER,"a",true);rejects(()->s.track(PLAYER,"b",true),"tracking cap");
        s.track(PLAYER,"a",false);s.track(PLAYER,"b",true);eq(QuestService.Status.ACTIVE,s.progress(PLAYER,"a").status(),"untrack never abandons");
        s.abandon(PLAYER,"a");eq(QuestService.Status.COOLDOWN,s.progress(PLAYER,"a").status(),"abandon cooling");
        rejects(()->s.accept(PLAYER,"a"),"abandon cooldown enforced");s.kill(PLAYER,"minecraft:zombie",1);
        eq(0,s.progress(PLAYER,"a").objectiveCounts().get("kill"),"abandoned tasks stop progressing");
        rejects(()->s.claim(PLAYER,"a"),"abandon no reward");clock.add(3000);s.accept(PLAYER,"a");
        eq(QuestService.Status.ABANDONED,s.progress(PLAYER,"a").history().getLast().status(),"history retained on reaccept");
        s.kill(PLAYER,"minecraft:zombie",1);s.claim(PLAYER,"a");rejects(()->s.accept(PLAYER,"a"),"completion cooldown enforced");
        clock.add(5000);s.accept(PLAYER,"a");eq(1,s.progress(PLAYER,"a").completions(),"completed count preserved");
        clock.add(10_000);eq(List.of("a","b"),s.tick(PLAYER),"active and READY tasks expire at exact deadline");
        eq(List.of(),s.tick(PLAYER),"failure notification once");eq(false,s.progress(PLAYER,"b").tracked(),"failure untracks");
        rejects(()->s.claim(PLAYER,"b"),"expired READY task cannot claim");
        var one=quest("one",q.objectives(),EMPTY,false,rules(null,null,0,0,false,0,false,null,List.of()));var denied=service(List.of(one));
        denied.accept(PLAYER,"one");denied.abandon(PLAYER,"one");rejects(()->denied.accept(PLAYER,"one"),"no-reaccept rule");
    }
    private static void stageAndRewardLedger() {
        var reward=new Rewards(List.of(new RewardItem("minecraft:bread",2)),7,List.of(new RewardItem("fsrpg:silver_coin",3)),
                List.of(new RewardItem("minecraft:diamond",1),new RewardItem("minecraft:emerald",2)),
                List.of(new WeightedItem(new RewardItem("minecraft:apple",1),1),new WeightedItem(new RewardItem("minecraft:carrot",2),3)),4);
        var stages=List.of(new Stage("gather","Gather",reward),new Stage("hunt","Hunt",EMPTY));
        var q=quest("chain",List.of(objective("gather",ObjectiveType.COLLECT,"minecraft:oak_log",2,null,0,false),objective("hunt",ObjectiveType.KILL,"minecraft:zombie",1,null,1,false)),reward,true,rules(null,null,0,0,true,0,false,null,stages));
        var s=service(List.of(q));s.accept(PLAYER,q.id());s.kill(PLAYER,"minecraft:zombie",1);
        eq(0,s.progress(PLAYER,q.id()).objectiveCounts().get("hunt"),"later stage events ignored");
        rejects(()->s.claimStage(PLAYER,q.id(),"gather",0),"incomplete stage reward denied");
        s.collect(PLAYER,"minecraft:oak_log",2);rejects(()->s.claimStage(PLAYER,q.id(),"hunt",-1),"later stage cannot claim early");
        rejects(()->s.claimStage(PLAYER,q.id(),"gather",-1),"choice required before claim");
        var before=s.snapshot();var first=s.claimStage(PLAYER,q.id(),"gather",1);
        eq(7,first.xp(),"stage XP");eq(7,first.items().size(),"base currency choice plus four pool draws");
        eq(new RewardItem("fsrpg:silver_coin",3),first.items().get(1),"physical currency exact");
        eq(new RewardItem("minecraft:emerald",2),first.items().get(2),"chosen reward exact");
        for(var item:first.items().subList(3,7))ok(item.id().equals("minecraft:apple")||item.id().equals("minecraft:carrot"),"random result from configured pool");
        s.restore(before);eq(first,s.claimStage(PLAYER,q.id(),"gather",1),"same accepted attempt cannot reroll random outcome on replay");
        var saved=s.snapshot();var restored=service(List.of(q));restored.restore(saved);
        rejects(()->restored.claimStage(PLAYER,q.id(),"gather",1),"stage reward durable once-only");
        restored.kill(PLAYER,"minecraft:zombie",1);eq(QuestService.Status.READY,restored.progress(PLAYER,q.id()).status(),"final stage READY");
        rejects(()->restored.claim(PLAYER,q.id(),0),"all stage rewards must settle first");
        restored.claimStage(PLAYER,q.id(),"hunt",-1);rejects(()->restored.claim(PLAYER,q.id(),99),"bad reward choice denied");
        restored.claim(PLAYER,q.id(),0);rejects(()->restored.claim(PLAYER,q.id(),1),"same completion cannot reroll final reward");
        restored.accept(PLAYER,q.id());eq(Set.of(),restored.progress(PLAYER,q.id()).claimedStages(),"new repeat attempt resets stage claims");
        eq(1,restored.progress(PLAYER,q.id()).completions(),"new repeat retains completion count");
    }
    private static void rankExams() {
        var q=quest("work",List.of(objective("kill",ObjectiveType.KILL,"minecraft:zombie",1,null,0,false)),EMPTY,true,Rules.defaults());
        var exam=quest("exam_c",q.objectives(),EMPTY,false,rules(Rank.D,null,0,0,true,0,false,Rank.C,List.of()));
        var gated=quest("c_only",q.objectives(),EMPTY,false,rules(Rank.C,null,0,0,true,0,false,null,List.of()));
        var dailyD=quest("daily_d",q.objectives(),EMPTY,false,rules(Rank.D,null,0,0,true,0,true,null,List.of()));
        var dailyC=quest("daily_c",q.objectives(),EMPTY,false,rules(Rank.C,null,0,0,true,0,true,null,List.of()));
        var settings=new QuestSettings(3,3,"Asia/Shanghai",List.of(new QuestSettings.Promotion(Rank.C,2,Map.of("work",2),"exam_c")));
        var s=new QuestService(QuestCatalog.of(List.of(q,exam,gated,dailyD,dailyC)),settings);
        eq(Rank.D,s.rank(PLAYER),"new player rank D");rejects(()->s.accept(PLAYER,"c_only"),"rank gate enforced");
        eq(QuestService.Status.LOCKED,s.progress(PLAYER,"c_only").status(),"rank locked visibility");
        eq(List.of("daily_d"),s.snapshot().dailySelections().get(PLAYER).questIds(),"first daily board contains only eligible D pool");
        rejects(()->s.requestExam(PLAYER),"promotion needs all conditions");rejects(()->s.accept(PLAYER,"exam_c"),"exam cannot bypass NPC request");
        for(int i=0;i<2;i++){s.accept(PLAYER,"work");s.kill(PLAYER,"minecraft:zombie",1);s.claim(PLAYER,"work");}
        eq(true,s.promotionProgress(PLAYER).eligible(),"task ledger counts promotion");s.requestExam(PLAYER);
        eq(Rank.D,s.rank(PLAYER),"starting exam never promotes");rejects(()->s.claim(PLAYER,"exam_c"),"unfinished exam never promotes");
        s.fail(PLAYER,"exam_c","test");eq(Rank.D,s.rank(PLAYER),"failed exam never promotes");s.requestExam(PLAYER);s.kill(PLAYER,"minecraft:zombie",1);
        eq(Rank.D,s.rank(PLAYER),"READY exam still requires guild claim");s.claim(PLAYER,"exam_c");eq(Rank.C,s.rank(PLAYER),"successful exam claim promotes");
        eq(false,s.dailyAvailable(PLAYER,"daily_c"),"same-day rank promotion cannot reroll or add a daily task");
        eq(List.of("daily_d"),s.snapshot().dailySelections().get(PLAYER).questIds(),"same-day board frozen through promotion");
        s.accept(PLAYER,"c_only");eq(QuestService.Status.ACTIVE,s.progress(PLAYER,"c_only").status(),"new rank immediately unlocks tasks");
        var restored=new QuestService(s.catalog(),settings);
        var gson=new com.google.gson.Gson();restored.restore(gson.fromJson(gson.toJson(s.snapshot()),QuestService.Snapshot.class));eq(Rank.C,restored.rank(PLAYER),"rank survives restore");
        eq(s.snapshot().dailySelections(),restored.snapshot().dailySelections(),"fixed daily board survives real JSON restart");
        eq(false,restored.dailyAvailable(PLAYER,"daily_c"),"restarting after promotion cannot expand same-day daily quota");
        eq(Rank.D,restored.rank(OTHER),"rank isolation");rejects(()->restored.requestExam(PLAYER),"no configured next exam");
    }
    private static void dailyRefresh() {
        var clock=new MutableClock();var d=rules(null,null,0,0,true,0,true,null,List.of());List<QuestDefinition> tasks=new ArrayList<>();
        for(int i=0;i<8;i++)tasks.add(quest("daily"+i,List.of(objective("kill",ObjectiveType.KILL,"minecraft:zombie",1,null,0,false)),EMPTY,false,d));
        var settings=new QuestSettings(3,3,"Asia/Shanghai",List.of());var s=new QuestService(QuestCatalog.of(tasks),settings,clock);
        var selected=s.progress(PLAYER).stream().filter(p->s.canAccept(PLAYER,p.questId())).map(QuestService.QuestProgress::questId).toList();
        eq(3,selected.size(),"configured number daily tasks");var restored=new QuestService(s.catalog(),settings,clock);restored.restore(s.snapshot());
        eq(selected,restored.progress(PLAYER).stream().filter(p->restored.canAccept(PLAYER,p.questId())).map(QuestService.QuestProgress::questId).toList(),"daily board survives reopen/restart");
        String id=selected.getFirst();s.accept(PLAYER,id);s.kill(PLAYER,"minecraft:zombie",1);s.claim(PLAYER,id);
        rejects(()->s.accept(PLAYER,id),"daily only one completion per date");String ready=selected.get(1),pending=selected.get(2);
        s.accept(PLAYER,ready);s.kill(PLAYER,"minecraft:zombie",1);s.accept(PLAYER,pending);
        clock.add(86_400_000);eq(List.of(pending),s.tick(PLAYER),"yesterday ACTIVE quest fails at refresh");
        eq(QuestService.Status.READY,s.progress(PLAYER,ready).status(),"completed daily keeps unclaimed reward across midnight");
        s.claim(PLAYER,ready);eq(1,s.progress(PLAYER,ready).completions(),"yesterday READY daily can still claim after midnight");
        eq(3,s.progress(PLAYER).stream().filter(p->s.canAccept(PLAYER,p.questId())).toList().size(),"next day refresh produces configured count");
        eq(1,s.progress(PLAYER,id).completions(),"daily refresh retains lifetime progress");
    }
    private static void migrationAndValidation() {
        var q=quest("old",List.of(objective("kill",ObjectiveType.KILL,"minecraft:zombie",2,null,0,false)),EMPTY,true,Rules.defaults());var s=service(List.of(q));
        s.restore(new QuestService.Snapshot(1,Map.of(PLAYER,Map.of(q.id(),new QuestService.StoredProgress(QuestService.Status.CLAIMED,Map.of("kill",2),1)))));
        eq(1,s.progress(PLAYER,q.id()).completions(),"schema1 completion migrated");rejects(()->s.claim(PLAYER,q.id()),"migrated completed rewards never replay");
        eq(2,s.snapshot().schemaVersion(),"writes upgraded schema");s.accept(PLAYER,q.id());s.kill(PLAYER,"minecraft:zombie",1);
        var snapshot=s.snapshot();rejects(()->s.restore(new QuestService.Snapshot(3,Map.of())),"unknown schema rejects");eq(snapshot,s.snapshot(),"invalid restore transactional");
        var failures=List.of(
            quest("bad",q.objectives(),EMPTY,false,rules(null,null,-1,0,true,0,false,null,List.of())),
            quest("bad",q.objectives(),EMPTY,false,rules(null,BountyTier.NORMAL,0,0,true,0,false,null,List.of())),
            quest("bad",List.of(new Objective("x",ObjectiveType.KILL,"minecraft:zombie",1,null,new Criteria(20,10,false,null,null,false),0,false)),EMPTY,false,Rules.defaults()),
            quest("bad",q.objectives(),new Rewards(List.of(),0,List.of(),List.of(),List.of(new WeightedItem(new RewardItem("minecraft:apple",1),Double.NaN)),1),false,Rules.defaults()),
            quest("bad",q.objectives(),new Rewards(List.of(new RewardItem("minecraft:air",1)),0),false,Rules.defaults()),
            quest("bad",q.objectives(),EMPTY,false,rules(null,null,0,0,true,0,false,null,List.of(new Stage("a","a",EMPTY),new Stage("b","b",EMPTY)))));
        for(var invalid:failures)rejects(()->QuestCatalog.of(List.of(invalid)),"invalid configuration rejects");
        rejects(()->new QuestSettings(0,3,"Asia/Shanghai",List.of()),"bad tracking config");rejects(()->new QuestSettings(3,3,"bogus",List.of()),"bad timezone config");
    }
    private static void bossNameAndReputation() {
        var named=new Objective("boss",ObjectiveType.KILL,"minecraft:zombie",1,new Location("minecraft:overworld",0,64,0,10),
                new Criteria(20,null,true,null,null,true,"北境首领"),0,false);
        var rewards=new Rewards(List.of(),2,List.of(new RewardItem("fsrpg:gold_coin",5)),List.of(),List.of(),0,30);
        var q=quest("named_boss",List.of(named),rewards,false,Rules.defaults());var s=service(List.of(q));
        s.accept(PLAYER,q.id());s.accept(OTHER,q.id());
        var ordinary=new KillContext("minecraft:zombie",20,true,Set.of("fsrpg:boss"),UUID.randomUUID(),false,"minecraft:overworld",0,64,0,"其他首领");
        s.kill(PLAYER,ordinary,1);eq(0,s.progress(PLAYER,q.id()).objectiveCounts().get("boss"),"BOSS name filter enforced");
        var boss=new KillContext("minecraft:zombie",20,true,Set.of("fsrpg:boss"),UUID.randomUUID(),false,"minecraft:overworld",0,64,0,"北境首领");
        s.kill(PLAYER,boss,1);s.kill(OTHER,boss,1);
        eq(QuestService.Status.READY,s.progress(PLAYER,q.id()).status(),"BOSS contributor one independent progress");
        eq(QuestService.Status.READY,s.progress(OTHER,q.id()).status(),"BOSS contributor two independent progress");
        var resolved=s.claim(PLAYER,q.id());eq(30,resolved.reputation(),"resolved rewards retain reputation");eq(30,s.reputation(PLAYER),"reputation credited on final claim");
        eq(0,s.reputation(OTHER),"unclaimed contributor no reputation");rejects(()->s.claim(PLAYER,q.id()),"duplicate final reputation claim rejected");
        var restored=service(List.of(q));restored.restore(s.snapshot());eq(30,restored.reputation(PLAYER),"reputation durable");
        var stage=quest("stage_fame",List.of(objective("kill",ObjectiveType.KILL,"minecraft:zombie",1,null,0,false)),EMPTY,false,
                rules(null,null,0,0,true,0,false,null,List.of(new Stage("hunt","Hunt",rewards))));
        var st=service(List.of(stage));st.accept(PLAYER,stage.id());st.kill(PLAYER,"minecraft:zombie",1);st.claimStage(PLAYER,stage.id(),"hunt",-1);
        eq(30,st.reputation(PLAYER),"stage reputation credited once");st.claim(PLAYER,stage.id());eq(30,st.reputation(PLAYER),"final reward independent from stage");
        var lockedDaily=quest("daily_locked",stage.objectives(),EMPTY,false,rules(Rank.S,null,0,0,true,0,true,null,List.of()));
        var openDaily=quest("daily_open",stage.objectives(),EMPTY,false,rules(Rank.D,null,0,0,true,0,true,null,List.of()));
        var day=new QuestService(QuestCatalog.of(List.of(lockedDaily,openDaily)),new QuestSettings(3,1,"Asia/Shanghai",List.of()));
        eq(true,day.dailyAvailable(PLAYER,"daily_open"),"daily selection filters rank before choosing quantity");
        eq(false,day.dailyAvailable(PLAYER,"daily_locked"),"high rank dailies not assigned to D player");
    }
    private static void persistentLedgerRoundTrip() throws Exception {
        var rewards=new Rewards(List.of(),0,List.of(new RewardItem("fsrpg:silver_coin",3)),List.of(),
                List.of(new WeightedItem(new RewardItem("minecraft:apple",1),1),new WeightedItem(new RewardItem("minecraft:diamond",1),0.1)),3,5);
        var q=quest("durable",List.of(objective("kill",ObjectiveType.KILL,"minecraft:zombie",1,null,0,false)),rewards,true,
                rules(null,null,0,0,true,0,false,null,List.of(new Stage("stable_stage","Stage",rewards))));
        var s=service(List.of(q));s.accept(PLAYER,q.id());s.track(PLAYER,q.id(),true);s.kill(PLAYER,"minecraft:zombie",1);
        var dir=java.nio.file.Files.createTempDirectory("fsrpg-guild-ledger-");var file=dir.resolve("quests.json");
        var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();var store=new QuestStore(file,gson);
        try {
            store.save(s.snapshot());var restored=service(List.of(q));restored.restore(store.load());
            eq(s.snapshot(),restored.snapshot(),"complete schema2 UUID/record JSON roundtrip");
            var expected=s.claimStage(PLAYER,q.id(),"stable_stage",-1);
            eq(expected,restored.claimStage(PLAYER,q.id(),"stable_stage",-1),"random outcome stable across real JSON reload");
            restored.claim(PLAYER,q.id());store.save(restored.snapshot());var again=service(List.of(q));again.restore(store.load());
            eq(10,again.reputation(PLAYER),"stage and final reputation durable exactly once");
            rejects(()->again.claim(PLAYER,q.id()),"final reward cannot replay after disk reload");
            rejects(()->again.claimStage(PLAYER,q.id(),"stable_stage",-1),"stage reward cannot replay after disk reload");
            again.accept(PLAYER,q.id());eq(1,again.progress(PLAYER,q.id()).history().size(),"repeat log survives disk reload");
            var legacy=gson.fromJson("{\"schemaVersion\":1,\"players\":{\""+PLAYER+"\":{\"durable\":{\"status\":\"CLAIMED\",\"objectiveCounts\":{\"kill\":1},\"completions\":1}}}}",QuestService.Snapshot.class);
            again.restore(legacy);eq(1,again.progress(PLAYER,q.id()).completions(),"actual old JSON migration");
            eq(0,again.reputation(PLAYER),"legacy missing reputation starts at zero");
        } finally {java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.deleteIfExists(dir);}
    }
    private static QuestDefinition quest(String id,List<Objective> objectives,Rewards rewards,boolean repeat,Rules rules){return new QuestDefinition(id,id,"test",List.of(),objectives,rewards,repeat,false,rules);}
    private static Objective objective(String id,ObjectiveType type,String target,int count,Criteria criteria,int stage,boolean inventory){return new Objective(id,type,target,count,null,criteria,stage,inventory);}
    private static Rules rules(Rank min,BountyTier tier,long cooldown,long abandon,boolean again,long limit,boolean daily,Rank exam,List<Stage> stages){return new Rules("D",min,tier,cooldown,abandon,again,limit,daily,exam,stages);}
    private static KillContext kill(String type,int level,boolean elite,Set<String> tags,boolean player,double x){return new KillContext(type,level,elite,tags,OTHER,player,"minecraft:overworld",x,64,0);}
    private static QuestService service(List<QuestDefinition> quests){return new QuestService(QuestCatalog.of(quests));}
    private static void eq(Object expected,Object actual,String message){checks++;if(!Objects.equals(expected,actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
    private static void ok(boolean value,String message){eq(true,value,message);}
    private static void rejects(Runnable action,String message){checks++;try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(message+": unexpectedly succeeded");}
    private static final class MutableClock extends Clock {
        long now=Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        void add(long millis){now+=millis;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
}

package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** Repeated attempts keep their history visible, and presentation never offers premature claims. */
public final class QuestJournalModelTest {
    private static int checks;
    public static void main(String[] args) {
        var active = json("{id:'repeat',status:'ACTIVE',completions:2,tracked:true,history:[{status:'FAILED'}]}");
        var ready = json("{id:'ready',status:'READY',tracked:true}");
        var claimed = json("{id:'done',status:'CLAIMED',tracked:true}");
        var locked = json("{id:'exam',status:'LOCKED',rules:{examRank:'C',minimumRank:'D'}}");
        var daily = json("{id:'daily',status:'AVAILABLE',rules:{daily:true}}");
        var all = List.of(active, ready, claimed, locked, daily);
        equal(5, QuestJournalModel.filter(all, QuestJournalModel.Tab.BOARD).size(), "board retains locked and completed task details");
        equal(List.of(active, ready), QuestJournalModel.filter(all, QuestJournalModel.Tab.ACTIVE), "active journal includes unclaimed completions");
        equal(List.of(active, ready, claimed), QuestJournalModel.filter(all, QuestJournalModel.Tab.COMPLETED), "prior completion remains after reacceptance");
        equal(List.of(active), QuestJournalModel.filter(all, QuestJournalModel.Tab.FAILED), "failure history remains after reacceptance");
        equal(List.of(daily), QuestJournalModel.filter(all, QuestJournalModel.Tab.DAILY), "daily flag is read from rules");
        var pool = List.of(
                json("{id:'d0',status:'AVAILABLE',rules:{daily:true},dailySelected:true}"),
                json("{id:'d1',status:'LOCKED',rules:{daily:true},dailySelected:false}"),
                json("{id:'d2',status:'ACTIVE',rules:{daily:true},dailySelected:true}"),
                json("{id:'d3',status:'AVAILABLE',rules:{daily:true},dailySelected:false}"),
                json("{id:'d4',status:'CLAIMED',rules:{daily:true},dailySelected:true}"));
        equal(List.of(pool.get(0), pool.get(2), pool.get(4)), QuestJournalModel.filter(pool, QuestJournalModel.Tab.DAILY), "daily tab shows exactly the three server-selected tasks from a five-task pool, including accepted and claimed selections");
        equal(List.of(daily), QuestJournalModel.filter(List.of(daily), QuestJournalModel.Tab.DAILY), "older snapshots without dailySelected retain their daily entries");
        equal(List.of(locked), QuestJournalModel.filter(all, QuestJournalModel.Tab.RANK), "locked exams remain visible for promotion planning");
        equal(List.of(active, ready), QuestJournalModel.tracked(all), "claimed tasks disappear from HUD even if stale tracked flag exists");
        check(!QuestJournalModel.canAccept(locked), "locked task cannot show active accept button");
        daily.addProperty("canAccept", false);
        check(!QuestJournalModel.canAccept(daily), "server denial overrides apparently available status");
        check(QuestJournalModel.canClaim(ready), "ready ordinary task offers final claim");
        var stages = json("{id:'chain',status:'READY',rules:{stages:[{id:'a',label:'one'},{id:'b',label:'two'}]},claimedStages:['a'],objectives:[{id:'x',stage:0,count:1,current:1},{id:'y',stage:1,count:2,current:2}]}");
        check(!QuestJournalModel.canClaim(stages), "final reward stays disabled while a stage reward is pending");
        equal("b", ClientState.text(QuestJournalModel.claimableStage(stages), "id", ""), "next unclaimed completed stage is offered");
        stages.getAsJsonArray("claimedStages").add("b");
        check(QuestJournalModel.canClaim(stages), "final claim opens after all completed stage rewards are claimed");
        check(QuestJournalModel.claimableStage(stages) == null, "claimed stages cannot be shown as claimable again");
        stages.addProperty("status", "ABANDONED"); stages.getAsJsonArray("claimedStages").remove(1);
        check(QuestJournalModel.claimableStage(stages) == null, "abandoned task cannot offer stage rewards");
        var incomplete = json("{status:'ACTIVE',rules:{stages:[{id:'a'}]},objectives:[{stage:0,count:2,current:1}]}");
        check(QuestJournalModel.claimableStage(incomplete) == null, "unfinished stage remains unclaimable");
        equal("0秒", QuestJournalModel.remaining(1000, 1001), "expired cooldown never becomes negative");
        equal("1分 1秒", QuestJournalModel.remaining(61000, 0), "cooldown displays rounded-up seconds");
        equal("1天 1小时", QuestJournalModel.remaining(90000000, 0), "multi-day cooldown remains readable");
        var legacy = json("{rewardText:'{\"xp\":12}'}");
        equal(12.0, ClientState.number(QuestJournalModel.rewards(legacy), "xp", 0), "legacy rewardText remains readable");
        System.out.println("QuestJournalModelTest passed: " + checks + " checks");
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void equal(Object expected, Object actual, String label) { check(java.util.Objects.equals(expected, actual), label + " expected=" + expected + " actual=" + actual); }
}

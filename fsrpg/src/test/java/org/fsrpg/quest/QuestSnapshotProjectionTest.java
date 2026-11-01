package org.fsrpg.quest;

import com.google.gson.*;
import java.util.*;

/** Network budget, legacy compatibility and immutable source projection regression tests. */
public final class QuestSnapshotProjectionTest {
    private static int checks;
    public static void main(String[] args) {
        legacyUntouched();normalLedgerAndRecentHistory();oldTerminalSurvives();summaryBudget();redundancyAndDescriptionBudget();largeCatalogFallback();nonQuestEmergency();nearConfigurationCeiling();
        System.out.println("QuestSnapshotProjectionTest passed: "+checks+" checks");
    }
    private static void legacyUntouched() {
        JsonObject source=JsonParser.parseString("{\"stamina\":17.5,\"phase\":\"READY\",\"message\":\"旧版字段\",\"skills\":[{\"id\":\"slash\"}],\"quests\":[{\"id\":\"old\",\"status\":\"ACTIVE\",\"objectives\":[{\"id\":\"z\",\"count\":10,\"current\":3}]}]}").getAsJsonObject();
        eq(source,decode(QuestSnapshotProjection.encode(source)),"legacy fields unchanged when no projection needed");
        ok(!QuestSnapshotProjection.encode(source).contains("\n"),"wire JSON compact");
    }
    private static void normalLedgerAndRecentHistory() {
        JsonObject source=snapshot(21,64,12);source.addProperty("legacyConfigBlob","x".repeat(70_000));String original=source.toString();
        JsonObject wire=encoded(source);eq(original,source.toString(),"caller snapshot unchanged");
        eq(21,wire.getAsJsonArray("quests").size(),"all current 21 task cards retained");
        for(JsonElement value:wire.getAsJsonArray("quests")) {
            JsonObject q=value.getAsJsonObject();JsonArray history=q.getAsJsonArray("history");eq(8,history.size(),"last eight detailed records");
            eq(56L,history.get(0).getAsJsonObject().get("endedAt").getAsLong(),"recent records begin at original index 56");
            eq(63L,history.get(7).getAsJsonObject().get("endedAt").getAsLong(),"latest terminal retained");
            ok(history.get(0).getAsJsonObject().has("objectiveCounts"),"recent details retain objective counts");
            eq(64,q.get("historyTotal").getAsInt(),"wire exposes total ledger history count");
            eq(true,q.get("historyTruncated").getAsBoolean(),"history projection explicit");
        }
        eq(source.get("legacyConfigBlob"),wire.get("legacyConfigBlob"),"unrelated legacy config field retained");
    }
    private static void oldTerminalSurvives() {
        JsonObject source=snapshot(1,64,2);JsonArray history=source.getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("history");
        for(int i=0;i<64;i++)history.get(i).getAsJsonObject().addProperty("status",i==0?"FAILED":"CLAIMED");
        JsonArray projected=encoded(source).getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("history");
        eq(9,projected.size(),"older missing terminal category adds one summary beyond eight details");
        eq("FAILED",projected.get(0).getAsJsonObject().get("status").getAsString(),"failed tab retains old failure after 63 completions");
        ok(!projected.get(0).getAsJsonObject().has("objectiveCounts"),"old category summary omits counts");
        eq(63L,projected.get(8).getAsJsonObject().get("endedAt").getAsLong(),"history remains chronological");
    }
    private static void summaryBudget() {
        JsonObject source=snapshot(21,64,16);source.addProperty("legacyConfigBlob","x".repeat(210_000));
        JsonObject wire=encoded(source);eq(21,wire.getAsJsonArray("quests").size(),"history summaries preserve full 21 task catalog");
        for(JsonElement value:wire.getAsJsonArray("quests")) {
            JsonObject q=value.getAsJsonObject();eq(true,q.get("historySummary").getAsBoolean(),"budget uses terminal summaries");
            JsonArray entries=q.getAsJsonArray("history");eq(3,entries.size(),"latest completed failed abandoned categories retained");
            for(JsonElement entry:entries)ok(!entry.getAsJsonObject().has("objectiveCounts"),"summary avoids objective map duplication");
        }
    }
    private static void redundancyAndDescriptionBudget() {
        JsonObject source=snapshot(1,0,1);source.addProperty("legacyConfigBlob","x".repeat(215_000));
        JsonObject q=source.getAsJsonArray("quests").get(0).getAsJsonObject();JsonObject rewards=new JsonObject();rewards.addProperty("display","r".repeat(30_000));q.add("rewards",rewards);q.addProperty("rewardText",rewards.toString());
        q.getAsJsonArray("objectives").get(0).getAsJsonObject().addProperty("label","o".repeat(15_000));
        JsonObject wire=encoded(source);JsonObject projected=wire.getAsJsonArray("quests").get(0).getAsJsonObject();
        ok(!projected.has("rewardText"),"redundant reward text removed under pressure");eq(rewards,projected.get("rewards"),"structured reward semantics preserved");
        ok(!projected.getAsJsonArray("objectives").get(0).getAsJsonObject().has("label"),"derived objective label removed under pressure");
        eq("minecraft:zombie",projected.getAsJsonArray("objectives").get(0).getAsJsonObject().get("target").getAsString(),"objective target retained");
        source=snapshot(1,0,1);source.addProperty("legacyConfigBlob","x".repeat(245_000));q=source.getAsJsonArray("quests").get(0).getAsJsonObject();q.addProperty("description","d".repeat(30_000));
        projected=encoded(source).getAsJsonArray("quests").get(0).getAsJsonObject();eq(true,projected.get("descriptionOmitted").getAsBoolean(),"description omission flagged");
    }
    private static void largeCatalogFallback() {
        JsonObject source=snapshot(512,0,64);JsonArray tasks=source.getAsJsonArray("quests");tasks.get(511).getAsJsonObject().addProperty("tracked",true);String before=source.toString();
        JsonObject wire=encoded(source);ok(wire.get("questsOmitted").getAsInt()>0,"extreme catalog reports omissions");
        ok(wire.get("message").getAsString().contains("部分任务"),"visible Chinese message reports partial task catalog");
        ok(wire.has("projectionNotice"),"machine-readable projection notice present");
        boolean tracked=false;for(JsonElement task:wire.getAsJsonArray("quests"))if(task.getAsJsonObject().get("id").getAsString().equals("quest:511"))tracked=true;
        ok(tracked,"tracked work retained before available cards");eq(before,source.toString(),"extreme fallback also leaves source unchanged");
        eq(512,wire.getAsJsonArray("quests").size()+wire.get("questsOmitted").getAsInt(),"omission count exact");
    }
    private static void nonQuestEmergency() {
        JsonObject source=snapshot(2,1,1);source.addProperty("hugeNonQuestField","x".repeat(400_000));source.addProperty("stamina",23.5);
        JsonObject wire=encoded(source);eq(23.5,wire.get("stamina").getAsDouble(),"minimal packet retains combat scalar");
        eq(true,wire.get("snapshotTruncated").getAsBoolean(),"oversize non-task state has explicit fallback");
        ok(wire.get("message").getAsString().contains("超过"),"emergency remains visible");
        JsonObject malformed=new JsonObject();for(int i=0;i<1000;i++)malformed.addProperty("scalar"+i,"x".repeat(1000));
        wire=encoded(malformed);ok(wire.has("projectionNotice"),"unbounded unexpected scalar keys still produce valid bounded JSON");
    }
    private static void nearConfigurationCeiling() {
        // Approximate the shared 180k configuration budget with a large legal-looking quest pool,
        // then include current counts, 64 ledger attempts, and unrelated combat/config state.
        JsonObject source=snapshot(120,64,6);source.addProperty("skillsAndStylesCompatibility","x".repeat(75_000));
        JsonObject wire=encoded(source);eq(120,wire.getAsJsonArray("quests").size(),"large near-budget task catalog stays complete after projection");
    }
    private static JsonObject snapshot(int tasks,int attempts,int objectives) {
        JsonObject source=new JsonObject();source.addProperty("message","");source.addProperty("phase","READY");source.addProperty("stamina",35);
        JsonArray quests=new JsonArray();for(int q=0;q<tasks;q++) {
            JsonObject quest=new JsonObject();quest.addProperty("id","quest:"+q);quest.addProperty("label","任务 "+q);quest.addProperty("status","AVAILABLE");quest.addProperty("tracked",false);quest.addProperty("canAccept",true);quest.addProperty("completions",attempts);
            quest.add("rewards",new JsonObject());quest.addProperty("rewardText","{}");JsonArray goals=new JsonArray();
            for(int o=0;o<objectives;o++){JsonObject goal=new JsonObject();goal.addProperty("id","objective_"+o);goal.addProperty("type","KILL");goal.addProperty("target","minecraft:zombie");goal.addProperty("count",10);goal.addProperty("current",o%10);goal.addProperty("label","KILL minecraft:zombie");goals.add(goal);}quest.add("objectives",goals);
            JsonArray history=new JsonArray();for(int h=0;h<attempts;h++) {
                JsonObject attempt=new JsonObject();attempt.addProperty("status",switch(h%3){case 0->"CLAIMED";case 1->"FAILED";default->"ABANDONED";});attempt.addProperty("endedAt",h);
                JsonObject counts=new JsonObject();for(int o=0;o<objectives;o++)counts.addProperty("objective_"+o,o%10);attempt.add("objectiveCounts",counts);history.add(attempt);
            }quest.add("history",history);quests.add(quest);
        }source.add("quests",quests);return source;
    }
    private static JsonObject encoded(JsonObject source){String encoded=QuestSnapshotProjection.encode(source);ok(encoded.length()<=QuestSnapshotProjection.MAX_JSON_CHARS,"strict CODEC string budget");return decode(encoded);}
    private static JsonObject decode(String encoded){return JsonParser.parseString(encoded).getAsJsonObject();}
    private static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void eq(Object expected,Object actual,String message){checks++;if(!Objects.equals(expected,actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
}

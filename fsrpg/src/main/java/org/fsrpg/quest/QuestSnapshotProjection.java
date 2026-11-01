package org.fsrpg.quest;

import com.google.gson.*;
import java.util.*;

/** Bounded wire projection only. The authoritative ledger and caller's snapshot are never mutated. */
public final class QuestSnapshotProjection {
    public static final int MAX_JSON_CHARS=262_144;
    public static final int RECENT_HISTORY=8;
    private static final Gson COMPACT=new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> TERMINAL=Set.of("CLAIMED","FAILED","ABANDONED");
    private QuestSnapshotProjection() {}

    public static String encode(JsonObject snapshot) {
        Objects.requireNonNull(snapshot,"snapshot");
        JsonObject wire=snapshot.deepCopy();
        for(JsonObject quest:quests(wire))trimHistory(quest);
        String encoded=COMPACT.toJson(wire);
        if(fits(encoded))return encoded;
        // Keep the latest example of each terminal category, including an older failure outside the last eight.
        List<JsonObject> original=quests(snapshot),projected=quests(wire);
        for(int index=0;index<projected.size();index++)summarizeHistory(projected.get(index),original.get(index));
        encoded=COMPACT.toJson(wire);if(fits(encoded))return encoded;
        for(JsonObject quest:projected) {
            quest.remove("rewardText"); // The structured rewards object is authoritative for display.
            for(JsonObject objective:objects(quest,"objectives"))objective.remove("label"); // type and target remain.
        }
        encoded=COMPACT.toJson(wire);if(fits(encoded))return encoded;
        for(JsonObject quest:projected) {
            if(quest.has("description")){quest.remove("description");quest.addProperty("descriptionOmitted",true);}
        }
        encoded=COMPACT.toJson(wire);if(fits(encoded))return encoded;

        // An exceptionally large catalog must never disconnect a player. Preserve whole quest cards,
        // preferring tracked and running work; explicitly tell the player when cards are omitted.
        return fitQuestSubset(wire,projected);
    }
    private static void trimHistory(JsonObject quest) {
        JsonArray history=array(quest,"history");if(history==null)return;
        int size=history.size();
        if(size<=RECENT_HISTORY)return;
        int start=size-RECENT_HISTORY;Set<String> represented=new HashSet<>();
        for(int i=start;i<size;i++)if(history.get(i).isJsonObject())represented.add(text(history.get(i).getAsJsonObject(),"status",""));
        Map<String,Integer> summaryIndices=new HashMap<>();
        for(int i=0;i<start;i++)if(history.get(i).isJsonObject()) {
            String status=text(history.get(i).getAsJsonObject(),"status","");
            if(TERMINAL.contains(status) && !represented.contains(status))summaryIndices.put(status,i);
        }
        JsonArray recent=new JsonArray();
        for(int index:summaryIndices.values().stream().sorted().toList())recent.add(summary(history.get(index).getAsJsonObject()));
        for(int i=start;i<size;i++)recent.add(history.get(i));
        quest.add("history",recent);quest.addProperty("historyTotal",size);quest.addProperty("historyTruncated",true);
    }
    private static void summarizeHistory(JsonObject quest,JsonObject original) {
        JsonArray history=array(original,"history");if(history==null || history.isEmpty())return;
        Map<String,Integer> latest=new HashMap<>();
        for(int index=0;index<history.size();index++) {
            JsonElement value=history.get(index);if(!value.isJsonObject())continue;
            String status=text(value.getAsJsonObject(),"status","");if(TERMINAL.contains(status))latest.put(status,index);
        }
        JsonArray summary=new JsonArray();
        for(int index:latest.values().stream().sorted().toList()) {
            summary.add(summary(history.get(index).getAsJsonObject()));
        }
        quest.add("history",summary);quest.addProperty("historyTotal",history.size());quest.addProperty("historyTruncated",true);
        quest.addProperty("historySummary",true);
    }
    private static JsonObject summary(JsonObject entry) {
        JsonObject item=new JsonObject();item.add("status",entry.get("status").deepCopy());
        if(entry.has("endedAt"))item.add("endedAt",entry.get("endedAt").deepCopy());
        item.addProperty("summary",true);return item;
    }
    private static String fitQuestSubset(JsonObject wire,List<JsonObject> candidates) {
        wire.add("quests",new JsonArray());
        warn(wire,candidates.size(),"任务数据较大，当前仅显示部分任务；服务器中的任务进度和完整记录仍保留。");
        String base=COMPACT.toJson(wire);
        if(!fits(base))return minimal(wire,candidates.size());
        int remaining=MAX_JSON_CHARS-base.length()-32;
        List<JsonObject> priority=new ArrayList<>(candidates);
        priority.sort(Comparator.comparingInt(QuestSnapshotProjection::priority));
        Set<JsonObject> retained=Collections.newSetFromMap(new IdentityHashMap<>());
        for(JsonObject quest:priority) {
            int cost=COMPACT.toJson(quest).length()+1;
            if(cost<=remaining){retained.add(quest);remaining-=cost;}
        }
        JsonArray kept=new JsonArray();for(JsonObject quest:candidates)if(retained.contains(quest))kept.add(quest);
        wire.add("quests",kept);wire.addProperty("questsOmitted",candidates.size()-kept.size());
        String result=COMPACT.toJson(wire);
        if(fits(result))return result;
        return minimal(wire,candidates.size());
    }
    private static int priority(JsonObject quest) {
        JsonElement tracked=quest.get("tracked");if(tracked!=null && tracked.isJsonPrimitive() && tracked.getAsJsonPrimitive().isBoolean() && tracked.getAsBoolean())return 0;
        return switch(text(quest,"status","")){case "READY"->1;case "ACTIVE"->2;case "FAILED","ABANDONED"->3;case "CLAIMED"->4;case "AVAILABLE"->5;default->6;};
    }
    private static String minimal(JsonObject wire,int omitted) {
        JsonObject result=new JsonObject();
        // Preserve small legacy top-level fields so the HUD continues to receive combat/resource state.
        for(var field:wire.entrySet()) {
            JsonElement value=field.getValue();
            if(!value.isJsonPrimitive() || field.getKey().equals("message") || field.getKey().equals("projectionNotice"))continue;
            if(field.getKey().length()<=128 && COMPACT.toJson(value).length()<=1024)result.add(field.getKey(),value.deepCopy());
        }
        for(String field:List.of("guild","burstDash","targetLock","charge","combo","technique","archery","footworkMotion")) {
            JsonElement value=wire.get(field);if(value!=null && COMPACT.toJson(value).length()<=8192)result.add(field,value.deepCopy());
        }
        result.add("quests",new JsonArray());result.add("skills",new JsonArray());result.add("styles",new JsonArray());
        warn(result,omitted,"同步数据超过显示容量，暂时隐藏任务详情；服务器中的进度和奖励记录仍保留，请管理员缩减任务配置。");
        result.addProperty("snapshotTruncated",true);
        String encoded=COMPACT.toJson(result);
        if(fits(encoded))return encoded;
        // Even a malformed/unexpected snapshot with millions of scalar keys gets a bounded valid packet.
        JsonObject emergency=new JsonObject();emergency.add("quests",new JsonArray());
        warn(emergency,omitted,"同步数据超过显示容量，请管理员检查任务配置；服务器中的任务记录仍保留。");
        emergency.addProperty("snapshotTruncated",true);return COMPACT.toJson(emergency);
    }
    private static void warn(JsonObject wire,int omitted,String notice) {
        wire.addProperty("questsOmitted",omitted);wire.addProperty("projectionNotice",notice);
        String existing=text(wire,"message","");if(existing.length()>512)existing=existing.substring(0,512);
        wire.addProperty("message",existing.isBlank()?notice:existing+"；"+notice);
    }
    private static boolean fits(String json){return json.length()<=MAX_JSON_CHARS;}
    private static JsonArray array(JsonObject object,String key){JsonElement value=object.get(key);return value!=null&&value.isJsonArray()?value.getAsJsonArray():null;}
    private static List<JsonObject> quests(JsonObject object){return objects(object,"quests");}
    private static List<JsonObject> objects(JsonObject object,String key) {
        JsonArray values=array(object,key);if(values==null)return List.of();
        List<JsonObject> result=new ArrayList<>();for(JsonElement value:values)if(value.isJsonObject())result.add(value.getAsJsonObject());return result;
    }
    private static String text(JsonObject object,String key,String fallback){JsonElement value=object.get(key);return value!=null&&value.isJsonPrimitive()?value.getAsString():fallback;}
}

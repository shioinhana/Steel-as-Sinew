package org.fsrpg.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;

/** Presentation-only queries shared by the board and HUD. No player state is mutated here. */
public final class QuestJournalModel {
    public enum Tab { BOARD, ACTIVE, COMPLETED, FAILED, DAILY, RANK }
    private QuestJournalModel() {}

    public static String status(JsonObject quest) {
        return ClientState.text(quest, "status", "LOCKED").toUpperCase(Locale.ROOT);
    }
    public static String statusLabel(JsonObject quest) {
        return switch (status(quest)) {
            case "AVAILABLE", "OFFERED", "NOT_STARTED" -> "可接取";
            case "ACTIVE", "IN_PROGRESS", "ACCEPTED" -> "进行中";
            case "READY", "COMPLETE", "COMPLETED", "READY_TO_CLAIM" -> "已完成 · 待领奖";
            case "CLAIMED", "FINISHED" -> "已完成 · 已领奖";
            case "FAILED" -> "已失败";
            case "ABANDONED" -> "已放弃";
            case "COOLDOWN" -> "冷却中";
            default -> "已锁定";
        };
    }
    public static boolean active(JsonObject quest) {
        return List.of("ACTIVE", "IN_PROGRESS", "ACCEPTED").contains(status(quest));
    }
    public static boolean ready(JsonObject quest) {
        return List.of("READY", "COMPLETE", "COMPLETED", "READY_TO_CLAIM").contains(status(quest));
    }
    public static JsonObject rules(JsonObject quest) { return ClientState.object(quest, "rules"); }
    public static String rule(JsonObject quest, String key, String fallback) {
        return ClientState.text(quest, key, ClientState.text(rules(quest), key, fallback));
    }
    public static boolean daily(JsonObject quest) {
        return ClientState.bool(quest, "daily", ClientState.bool(rules(quest), "daily", false));
    }
    public static boolean exam(JsonObject quest) { return !List.of("", "NONE").contains(rule(quest, "examRank", "")); }
    public static List<JsonObject> filter(List<JsonObject> quests, Tab tab) {
        return quests.stream().filter(q -> switch (tab) {
            case BOARD -> true;
            case ACTIVE -> active(q) || ready(q);
            case COMPLETED -> ready(q) || List.of("CLAIMED", "FINISHED").contains(status(q)) || ClientState.number(q, "completions", 0) > 0;
            case FAILED -> List.of("FAILED", "ABANDONED").contains(status(q)) || ClientState.objects(q, "history").stream()
                    .anyMatch(a -> List.of("FAILED", "ABANDONED").contains(status(a)));
            case DAILY -> daily(q) && ClientState.bool(q, "dailySelected", true);
            case RANK -> exam(q);
        }).toList();
    }
    public static List<JsonObject> tracked(List<JsonObject> quests) {
        return quests.stream().filter(q -> ClientState.bool(q, "tracked", false) && (active(q) || ready(q))).toList();
    }
    public static boolean canAccept(JsonObject quest) {
        return ClientState.bool(quest, "canAccept", status(quest).equals("AVAILABLE"));
    }
    public static boolean canClaim(JsonObject quest) {
        return ready(quest) && stages(quest).stream().allMatch(stage -> stageClaimed(quest, ClientState.text(stage, "id", "")));
    }
    public static JsonObject rewards(JsonObject quest) {
        JsonObject rewards = ClientState.object(quest, "rewards");
        if (!rewards.isEmpty()) return rewards;
        try {
            JsonElement parsed = JsonParser.parseString(ClientState.text(quest, "rewardText", "{}"));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) { return new JsonObject(); }
    }
    public static List<JsonObject> stages(JsonObject quest) {
        List<JsonObject> direct = ClientState.objects(quest, "stages");
        return direct.isEmpty() ? ClientState.objects(rules(quest), "stages") : direct;
    }
    public static boolean stageClaimed(JsonObject quest, String stageId) {
        JsonElement ids = quest.get("claimedStages");
        if (ids != null && ids.isJsonArray())
            for (JsonElement id : ids.getAsJsonArray()) if (id.isJsonPrimitive() && id.getAsString().equals(stageId)) return true;
        return false;
    }
    public static boolean stageComplete(JsonObject quest, int index) {
        List<JsonObject> objectives = ClientState.objects(quest, "objectives").stream()
                .filter(o -> (int) ClientState.number(o, "stage", 0) == index).toList();
        return !objectives.isEmpty() && objectives.stream().allMatch(o -> ClientState.number(o, "current", 0) >= ClientState.number(o, "count", 1));
    }
    public static JsonObject claimableStage(JsonObject quest) {
        if (!active(quest) && !ready(quest)) return null;
        List<JsonObject> stages = stages(quest);
        for (int i = 0; i < stages.size(); i++) {
            JsonObject stage = stages.get(i);
            String id = ClientState.text(stage, "id", "");
            if (!stageClaimed(quest, id) && ClientState.bool(stage, "canClaim", stageComplete(quest, i))) return stage;
        }
        return null;
    }
    public static String remaining(long until, long now) {
        long seconds = Math.max(0, (until - now + 999) / 1000);
        if (seconds >= 86400) return seconds / 86400 + "天 " + seconds % 86400 / 3600 + "小时";
        if (seconds >= 3600) return seconds / 3600 + "小时 " + seconds % 3600 / 60 + "分";
        if (seconds >= 60) return seconds / 60 + "分 " + seconds % 60 + "秒";
        return seconds + "秒";
    }
}

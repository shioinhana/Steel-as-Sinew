package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Localized item/entity names are resolved through the actual game registries. */
final class QuestJournalText {
    private QuestJournalText() {}
    static String item(JsonObject item) {
        String id = ClientState.text(item, "id", "");
        var stack = CollectionItems.stack(id);
        return (stack.isEmpty() ? id : CollectionItems.label(stack)) + " × " + (long) ClientState.number(item, "count", 1);
    }
    static String objective(JsonObject objective) {
        String target = ClientState.text(objective, "target", "");
        String label = switch (ClientState.text(objective, "type", "")) {
            case "COLLECT" -> "收集 " + itemName(target);
            case "KILL" -> "讨伐 " + CollectionItems.entity(target, target);
            case "PLAYER_KILL" -> "悬赏玩家 " + target;
            case "USE_SKILL" -> {
                String name = target;
                for (JsonObject skill : ClientState.skills()) if (ClientState.text(skill, "id", "").equals(target)) name = ClientState.text(skill, "label", target);
                yield "使用 " + name;
            }
            case "VISIT" -> "到达指定地点";
            default -> ClientState.text(objective, "label", "任务目标");
        };
        JsonObject criteria = ClientState.object(objective, "criteria");
        int min = (int) ClientState.number(criteria, "minLevel", 0), max = (int) ClientState.number(criteria, "maxLevel", 0);
        if (min > 0) label += " · 等级≥" + min;
        if (max > 0 && max < Integer.MAX_VALUE) label += " · 等级≤" + max;
        if (ClientState.bool(criteria, "elite", false)) label += " · 精英";
        if (ClientState.bool(criteria, "boss", false)) label += " · BOSS";
        String tag = ClientState.text(criteria, "requiredTag", "");
        if (!tag.isBlank()) label += " · 标记 " + tag;
        String uuid = ClientState.text(criteria, "playerUuid", "");
        if (!uuid.isBlank()) label += " · UUID " + uuid;
        String entityName = ClientState.text(criteria, "entityName", "");
        if (!entityName.isBlank()) label += " · 名称「" + entityName + "」";
        return label;
    }
    static String location(JsonObject objective) {
        JsonObject point = ClientState.object(objective, "location");
        if (point.isEmpty()) return "";
        return ClientState.text(point, "dimension", "") + " (" + (long) ClientState.number(point, "x", 0) + ", "
                + (long) ClientState.number(point, "y", 0) + ", " + (long) ClientState.number(point, "z", 0)
                + ") 半径 " + ClientState.number(point, "radius", 1) + " 格";
    }
    static String itemName(String id) {
        var stack = CollectionItems.stack(id); return stack.isEmpty() ? id : CollectionItems.label(stack);
    }
    static List<String> rewards(JsonObject rewards) {
        List<String> lines = new ArrayList<>();
        for (JsonObject item : ClientState.objects(rewards, "items")) lines.add("道具：" + item(item));
        for (JsonObject item : ClientState.objects(rewards, "currency")) lines.add("货币：" + item(item));
        int xp = (int) ClientState.number(rewards, "xp", 0); if (xp > 0) lines.add("经验：" + xp);
        int reputation = (int) ClientState.number(rewards, "reputation", 0); if (reputation > 0) lines.add("公会声望：" + reputation);
        List<JsonObject> choices = ClientState.objects(rewards, "choices");
        if (!choices.isEmpty()) {
            lines.add("自选奖励（任选一项）");
            for (int i = 0; i < choices.size(); i++) lines.add("  " + (i + 1) + ". " + item(choices.get(i)));
        }
        List<JsonObject> pool = ClientState.objects(rewards, "randomPool");
        if (!pool.isEmpty()) {
            lines.add("随机奖励：抽取 " + (int) ClientState.number(rewards, "randomRolls", 1) + " 次");
            double sum = pool.stream().mapToDouble(p -> Math.max(0, ClientState.number(p, "weight", 0))).sum();
            for (JsonObject entry : pool) {
                double probability = sum > 0 ? ClientState.number(entry, "weight", 0) * 100 / sum : 0;
                lines.add("  " + item(ClientState.object(entry, "item")) + " · " + String.format(java.util.Locale.ROOT, "%.1f%%", probability));
            }
        }
        if (lines.isEmpty()) lines.add("无物品或经验奖励");
        return List.copyOf(lines);
    }
    static String bounty(String value) {
        return switch (value) { case "NORMAL" -> "普通玩家悬赏"; case "HIGH" -> "高阶玩家悬赏"; case "S" -> "S级玩家悬赏"; default -> "普通任务"; };
    }
}

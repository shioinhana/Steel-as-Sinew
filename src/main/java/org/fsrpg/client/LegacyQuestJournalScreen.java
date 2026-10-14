package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Quest progress comes from server event counters, with explicit accept and claim intents. */
public final class LegacyQuestJournalScreen extends Screen {
    private String selectedId = "";
    private int page, left, top, panelWidth, panelHeight, rows, listWidth;
    private int detailScroll, detailLines;
    private String message = "任务进度由服务端记录";

    private final Screen parent;
    private long revision = ClientState.revision();
    public LegacyQuestJournalScreen(Screen parent) { super(Component.literal("旧任务日志")); this.parent = parent; }
    @Override public void tick() {
        if (revision != ClientState.revision()) { revision = ClientState.revision(); acceptSnapshot(); }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override protected void init() {
        panelWidth = Math.min(650, width - 12); panelHeight = Math.min(370, height - 12);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        listWidth = Math.min(180, Math.max(112, panelWidth / 3));
        rows = Math.max(1, (panelHeight - 72) / 23);
        List<JsonObject> quests = ClientState.quests();
        int pages = Math.max(1, (quests.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index >= quests.size()) break;
            JsonObject quest = quests.get(index);
            String id = ClientState.text(quest, "id", "");
            String label = ClientState.text(quest, "label", id);
            String status = status(quest);
            String marker = status.equals("READY_TO_CLAIM") || status.equals("COMPLETED") || status.equals("COMPLETE") || status.equals("READY") ? "✓ " : "";
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(marker + label, listWidth - 15)), button -> {
                selectedId = id; detailScroll = 0; rebuild();
            }).bounds(left + 12, top + 40 + row * 23, listWidth, 20).build());
        }
        int bottom = top + panelHeight - 25;
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> { page--; rebuild(); })
                .bounds(left + 12, bottom, 23, 18).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), button -> { page++; rebuild(); })
                .bounds(left + listWidth - 11, bottom, 23, 18).build()).active = page + 1 < pages;
        JsonObject quest = selected(); String status = quest == null ? "" : status(quest);
        boolean ready = status.equals("READY_TO_CLAIM") || status.equals("COMPLETED") || status.equals("COMPLETE") || status.equals("READY");
        int detailX = left + listWidth + 24;
        int detailWidth = panelWidth - listWidth - 36;
        int actionWidth = Math.min(70, Math.max(36, (detailWidth - 51) / 2));
        Button accept = addRenderableWidget(Button.builder(Component.literal("接受任务"), button -> {
            ClientHooks.send("acceptQuest", selectedId); message = "已发送接受任务请求";
        }).bounds(detailX, bottom, actionWidth, 18).build());
        accept.active = quest != null && (status.equals("AVAILABLE") || status.equals("NOT_STARTED") || status.equals("OFFERED")
                || status.equals("CLAIMED") && ClientState.bool(quest, "repeatable", false));
        Button claim = addRenderableWidget(Button.builder(Component.literal("领取奖励"), button -> {
            ClientHooks.send("claimQuest", selectedId); message = "已发送领取奖励请求";
        }).bounds(detailX + actionWidth + 5, bottom, actionWidth, 18).build());
        claim.active = ready;
        if (quest != null) claim.setTooltip(Tooltip.create(Component.literal("奖励：" + rewardText(quest))));
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(left + panelWidth - 52, bottom, 40, 18).build());
    }
    private void rebuild() { clearWidgets(); init(); }
    public void acceptSnapshot() {
        String serverMessage = ClientState.text(ClientState.snapshot(), "message", "");
        if (!serverMessage.isBlank()) message = serverMessage;
        if (panelWidth > 0) rebuild();
    }
    private JsonObject selected() {
        List<JsonObject> quests = ClientState.quests();
        for (JsonObject quest : quests) if (ClientState.text(quest, "id", "").equals(selectedId)) return quest;
        if (quests.isEmpty()) return null;
        selectedId = ClientState.text(quests.getFirst(), "id", ""); return quests.getFirst();
    }
    private static String status(JsonObject quest) { return ClientState.text(quest, "status", "AVAILABLE").toUpperCase(Locale.ROOT); }
    private static String statusLabel(String status) {
        return switch (status) {
            case "AVAILABLE", "NOT_STARTED", "OFFERED" -> "可接受";
            case "ACTIVE", "IN_PROGRESS", "ACCEPTED" -> "进行中";
            case "READY_TO_CLAIM", "COMPLETED", "COMPLETE", "READY" -> "可领取奖励";
            case "CLAIMED", "FINISHED" -> "已完成";
            case "LOCKED", "UNAVAILABLE" -> "尚未解锁";
            default -> status;
        };
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= left + listWidth + 24 && mouseX < left + panelWidth - 12 && scrollY != 0) {
            int visible = Math.max(1, (panelHeight - 116) / 11);
            detailScroll = Math.max(0, Math.min(Math.max(0, detailLines - visible), detailScroll - (int) Math.signum(scrollY) * 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    private static String itemLabel(String id) {
        try {
            Identifier identifier = Identifier.parse(id);
            if (BuiltInRegistries.ITEM.containsKey(identifier)) return new ItemStack(BuiltInRegistries.ITEM.getValue(identifier)).getHoverName().getString();
        } catch (RuntimeException ignored) { }
        return id;
    }
    private static String objectiveLabel(JsonObject objective) {
        String target = ClientState.text(objective, "target", "");
        String type = ClientState.text(objective, "type", "");
        return switch (type) {
            case "COLLECT" -> "收集 " + itemLabel(target);
            case "KILL" -> {
                String name = target;
                try { var id = Identifier.parse(target); if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) name = BuiltInRegistries.ENTITY_TYPE.getValue(id).getDescription().getString(); }
                catch (RuntimeException ignored) { }
                yield "击败 " + name;
            }
            case "USE_SKILL" -> {
                String name = target;
                for (JsonObject skill : ClientState.skills()) if (ClientState.text(skill, "id", "").equals(target)) name = ClientState.text(skill, "label", target);
                yield "使用 " + name;
            }
            case "VISIT" -> {
                var location = objective.get("location");
                if (location != null && location.isJsonObject()) {
                    JsonObject point = location.getAsJsonObject();
                    yield "到达 " + (int) ClientState.number(point, "x", 0) + ", " + (int) ClientState.number(point, "y", 0) + ", " + (int) ClientState.number(point, "z", 0);
                }
                yield "到达任务地点";
            }
            default -> ClientState.text(objective, "label", "目标");
        };
    }
    private static String rewardText(JsonObject quest) {
        String text = ClientState.text(quest, "rewardText", "");
        try {
            JsonObject rewards = JsonParser.parseString(text).getAsJsonObject();
            List<String> parts = new ArrayList<>();
            long xp = (long) ClientState.number(rewards, "xp", 0); if (xp > 0) parts.add("经验 " + xp);
            for (JsonObject item : ClientState.objects(rewards, "items"))
                parts.add(itemLabel(ClientState.text(item, "id", "")) + " × " + (long) ClientState.number(item, "count", 1));
            return parts.isEmpty() ? "无" : String.join(" · ", parts);
        } catch (RuntimeException ignored) { return text.isBlank() ? "无" : text; }
    }
    private record DetailLine(FormattedCharSequence text, int color) {}
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29);
        gui.fill(left, top, left + panelWidth, top + 3, 0xff8ec8a0);
        gui.drawString(font, title, left + 12, top + 13, 0xffe7f4e8);
        gui.drawString(font, font.plainSubstrByWidth(message, panelWidth - 145), left + 133, top + 13, 0xff9bbcab);
        int detailX = left + listWidth + 24, detailWidth = panelWidth - listWidth - 36;
        gui.vLine(left + listWidth + 18, top + 37, top + panelHeight - 34, 0xff304238);
        JsonObject quest = selected();
        if (quest == null) gui.drawString(font, "等待服务端任务配置…", detailX, top + 44, 0xffadbac7);
        else {
            gui.drawString(font, font.plainSubstrByWidth(ClientState.text(quest, "label", selectedId), detailWidth), detailX, top + 42, 0xfff1d491);
            gui.drawString(font, font.plainSubstrByWidth(statusLabel(status(quest)) + " · " + selectedId, detailWidth), detailX, top + 56, 0xff8fbea3);
            List<DetailLine> lines = new ArrayList<>();
            font.split(Component.literal(ClientState.text(quest, "description", "")), detailWidth).forEach(line -> lines.add(new DetailLine(line, 0xffbdc9d7)));
            lines.add(new DetailLine(Component.literal("").getVisualOrderText(), 0xffbdc9d7));
            for (JsonObject objective : ClientState.objects(quest, "objectives")) {
                double current = ClientState.number(objective, "current", 0);
                double count = ClientState.number(objective, "count", ClientState.number(objective, "targetCount", 1));
                String label = objectiveLabel(objective);
                String text = (current >= count ? "✓ " : "• ") + label + "  " + (long) current + "/" + (long) count;
                font.split(Component.literal(text), detailWidth).forEach(line -> lines.add(new DetailLine(line, current >= count ? 0xff8fcca6 : 0xffcbd6e4)));
            }
            font.split(Component.literal("奖励：" + rewardText(quest)), detailWidth).forEach(line -> lines.add(new DetailLine(line, 0xffe4c18c)));
            detailLines = lines.size(); int visible = Math.max(1, (panelHeight - 116) / 11);
            detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, detailLines - visible)));
            int y = top + 74;
            for (int i = detailScroll; i < Math.min(lines.size(), detailScroll + visible); i++) {
                gui.drawString(font, lines.get(i).text(), detailX, y, lines.get(i).color()); y += 11;
            }
            if (detailLines > visible) gui.drawString(font, "滚轮查看 · " + (detailScroll + 1) + "/" + detailLines, detailX, top + panelHeight - 40, 0xff8199ad);
        }
        int pages = Math.max(1, (ClientState.quests().size() + rows - 1) / rows);
        gui.drawCenteredString(font, (page + 1) + "/" + pages, left + 12 + listWidth / 2, top + panelHeight - 20, 0xffa6b6c6);
        super.render(gui, mouseX, mouseY, partialTick);
    }
    @Override public boolean isPauseScreen() { return false; }
}

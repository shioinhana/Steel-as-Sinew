package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Public board and private journal share authoritative snapshots and send intentions only. */
public class GuildQuestScreen extends Screen {
    private static final int TEXT = 0xffd4dee9, GREEN = 0xff92d7ae, GOLD = 0xffefcd91, MUTED = 0xff9aafc4, RED = 0xffe9aaaa;
    private final Screen parent;
    private QuestJournalModel.Tab tab = QuestJournalModel.Tab.BOARD;
    private String selectedId = "", message = "点击任务查看详情；滚轮浏览目标与奖励";
    private int page, detailScroll, detailPixels, left, top, panelWidth, panelHeight, listWidth, listRows;
    private long revision = ClientState.revision();
    private boolean pending;

    public GuildQuestScreen() { this(null); }
    public GuildQuestScreen(Screen parent) { super(Component.literal("公会任务与日志")); this.parent = parent; }
    public void showTab(QuestJournalModel.Tab next) {
        tab = next; page = 0; detailScroll = 0; selectedId = "";
        if (minecraft != null) rebuild();
    }
    public void selectQuest(String id) {
        selectedId = id; detailScroll = 0;
        List<JsonObject> entries = entries();
        for (int i = 0; i < entries.size(); i++) if (ClientState.text(entries.get(i), "id", "").equals(id)) page = i / Math.max(1, listRows);
        if (minecraft != null) rebuild();
    }
    @Override protected void init() {
        panelWidth = Math.min(760, width - 12); panelHeight = Math.min(440, height - 12);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        listWidth = Math.min(205, Math.max(90, panelWidth / 3 - 8));
        listRows = Math.max(1, (panelHeight - 161) / 23);
        int tabWidth = (panelWidth - 24 - 5 * 3) / 6;
        String[] labels = {"任务板", "进行中", "已完成", "失败/放弃", "每日", "晋升"};
        var tabs = QuestJournalModel.Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            var next = tabs[i];
            Button button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth((tab == next ? "•" : "") + labels[i], tabWidth - 5)), b -> showTab(next))
                    .bounds(left + 12 + i * (tabWidth + 3), top + 32, tabWidth, 20).build());
            button.setTooltip(Tooltip.create(Component.literal(labels[i])));
        }
        List<JsonObject> entries = entries(); selected();
        int pages = Math.max(1, (entries.size() + listRows - 1) / listRows); page = Math.max(0, Math.min(pages - 1, page));
        for (int row = 0; row < listRows; row++) {
            int index = page * listRows + row; if (index >= entries.size()) break;
            JsonObject quest = entries.get(index); String id = ClientState.text(quest, "id", "");
            String marker = ClientState.bool(quest, "tracked", false) ? "★ " : QuestJournalModel.ready(quest) ? "✓ " : "";
            String label = ClientState.text(quest, "label", id);
            Button button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(marker + label, listWidth - 10)), b -> selectQuest(id))
                    .bounds(left + 12, top + 78 + row * 23, listWidth, 20).build());
            button.setTooltip(Tooltip.create(Component.literal(label + " · " + QuestJournalModel.statusLabel(quest))));
        }
        int navY = top + panelHeight - 69;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page--; rebuild(); }).bounds(left + 12, navY, 21, 18).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { page++; rebuild(); }).bounds(left + listWidth - 9, navY, 21, 18).build()).active = page + 1 < pages;
        JsonObject quest = selected();
        int actionX = detailX(), actionWidth = (detailWidth() - 6) / 3, y = top + panelHeight - 47;
        boolean exam = quest != null && QuestJournalModel.exam(quest);
        Button accept = action(exam ? "申请考试" : "接取", actionX, y, actionWidth, b -> {
            JsonObject current = selected(); if (current == null) return;
            request(QuestJournalModel.exam(current) ? "applyExam" : "acceptQuest", selectedId, "正在申请，等待服务器结果…");
        });
        accept.active = !pending && quest != null && QuestJournalModel.canAccept(quest)
                && (!exam || ClientState.bool(guild(), "nearGuildNpc", false));
        accept.setTooltip(Tooltip.create(Component.literal(exam && !ClientState.bool(guild(), "nearGuildNpc", false)
                ? "请到公会接待员附近申请考试" : quest == null ? "请选择任务" : ClientState.text(quest, "lockReason", "接取条件由服务器检查"))));
        Button claim = action("领奖", actionX + actionWidth + 3, y, actionWidth, b -> claim(selected(), null));
        claim.active = !pending && quest != null && QuestJournalModel.canClaim(quest);
        claim.setTooltip(Tooltip.create(Component.literal("所有目标完成且阶段奖励领取后，可领取最终奖励")));
        Button track = action(quest != null && ClientState.bool(quest, "tracked", false) ? "取消追踪" : "追踪", actionX + 2 * (actionWidth + 3), y, actionWidth, b -> {
            JsonObject current = selected(); if (current != null) request(ClientState.bool(current, "tracked", false) ? "untrackQuest" : "trackQuest", selectedId, "正在更新追踪…");
        });
        track.active = !pending && quest != null && (QuestJournalModel.active(quest) || QuestJournalModel.ready(quest));
        track.setTooltip(Tooltip.create(Component.literal("取消追踪仅隐藏HUD；同时追踪上限 " + (int) ClientState.number(guild(), "trackLimit", 3) + " 条")));
        y += 22;
        Button abandon = action("放弃", actionX, y, actionWidth, b -> {
            JsonObject current = selected(); if (current != null) minecraft.setScreen(new AbandonConfirmation(this, selectedId, ClientState.text(current, "label", selectedId)));
        });
        abandon.active = !pending && quest != null && QuestJournalModel.active(quest);
        JsonObject stage = quest == null ? null : QuestJournalModel.claimableStage(quest);
        Button stageClaim = action("阶段领奖", actionX + actionWidth + 3, y, actionWidth, b -> {
            JsonObject current = selected(); if (current != null) claim(current, QuestJournalModel.claimableStage(current));
        });
        stageClaim.active = !pending && stage != null;
        stageClaim.setTooltip(Tooltip.create(Component.literal(stage == null ? "当前没有可领取的阶段奖励" : "领取阶段：" + ClientState.text(stage, "label", ""))));
        action("返回", actionX + 2 * (actionWidth + 3), y, actionWidth, b -> onClose());
    }
    private Button action(String label, int x, int y, int size, Button.OnPress press) {
        return addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, size - 4)), press).bounds(x, y, size, 18).build());
    }
    private void claim(JsonObject quest, JsonObject stage) {
        if (quest == null) return;
        JsonObject rewards = stage == null ? QuestJournalModel.rewards(quest) : ClientState.object(stage, "rewards");
        String stageId = stage == null ? "" : ClientState.text(stage, "id", "");
        if (!ClientState.objects(rewards, "choices").isEmpty()) minecraft.setScreen(new GuildRewardChoiceScreen(this, selectedId, stageId));
        else sendClaim(selectedId, stageId, -1);
    }
    void sendClaim(String id, String stageId, int choice) {
        if (stageId.isBlank() && choice < 0) request("claimQuest", id, "正在领取，等待服务器结果…");
        else {
            JsonObject intent = new JsonObject(); intent.addProperty("id", id); intent.addProperty("choice", choice);
            if (!stageId.isBlank()) intent.addProperty("stage", stageId);
            request(stageId.isBlank() ? "claimQuest" : "claimQuestStage", intent.toString(), "正在领取，等待服务器结果…");
        }
    }
    private void request(String action, String argument, String nextMessage) {
        pending = true; message = nextMessage; ClientHooks.send(action, argument); rebuild();
    }
    private void rebuild() { clearWidgets(); init(); }
    public void acceptSnapshot() {
        revision = ClientState.revision(); pending = false;
        String server = ClientState.text(ClientState.snapshot(), "message", ""); if (!server.isBlank()) message = server;
        if (panelWidth > 0) rebuild();
    }
    @Override public void tick() { if (revision != ClientState.revision()) acceptSnapshot(); }
    private JsonObject guild() { return ClientState.object(ClientState.snapshot(), "guild"); }
    private List<JsonObject> entries() { return QuestJournalModel.filter(ClientState.quests(), tab); }
    private JsonObject selected() {
        List<JsonObject> entries = entries();
        for (JsonObject quest : entries) if (ClientState.text(quest, "id", "").equals(selectedId)) return quest;
        if (entries.isEmpty()) return null;
        selectedId = ClientState.text(entries.getFirst(), "id", ""); return entries.getFirst();
    }
    private int detailX() { return left + listWidth + 25; }
    private int detailWidth() { return panelWidth - listWidth - 37; }
    private int detailY() { return top + 78; }
    private int detailHeight() { return Math.max(12, panelHeight - 170); }
    private record Line(FormattedCharSequence text, int color) {}
    private void prose(List<Line> lines, String text, int color) {
        font.split(Component.literal(text), Math.max(20, detailWidth() - 7)).forEach(line -> lines.add(new Line(line, color)));
    }
    private List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        if (tab == QuestJournalModel.Tab.RANK) {
            prose(lines, "冒险者 " + ClientState.text(guild(), "rank", "D") + " 级 → " + ClientState.text(guild(), "targetRank", "已达最高级"), GOLD);
            int total = (int) ClientState.number(guild(), "totalCompletions", 0), required = (int) ClientState.number(guild(), "requiredTotal", 0);
            if (required > 0) prose(lines, (total >= required ? "✓ " : "• ") + "任务总完成数 " + total + "/" + required, total >= required ? GREEN : TEXT);
            JsonObject requiredQuests = ClientState.object(guild(), "requiredQuests"), completedQuests = ClientState.object(guild(), "completedQuests");
            for (var requirement : requiredQuests.entrySet()) {
                String id = requirement.getKey(), label = id;
                for (JsonObject configured : ClientState.quests()) if (ClientState.text(configured, "id", "").equals(id)) label = ClientState.text(configured, "label", id);
                long current = (long) ClientState.number(completedQuests, id, 0), count = (long) ClientState.number(requiredQuests, id, 1);
                prose(lines, (current >= count ? "✓ " : "• ") + label + " " + current + "/" + count, current >= count ? GREEN : TEXT);
            }
            for (JsonObject requirement : ClientState.objects(guild(), "requirements")) {
                long current = (long) ClientState.number(requirement, "current", 0), count = (long) ClientState.number(requirement, "count", 1);
                prose(lines, (current >= count ? "✓ " : "• ") + ClientState.text(requirement, "label", ClientState.text(requirement, "id", "晋升任务")) + " " + current + "/" + count, current >= count ? GREEN : TEXT);
            }
            prose(lines, ClientState.bool(guild(), "eligible", ClientState.bool(guild(), "promotionReady", false)) ? "晋升任务条件已达成，请向公会接待员申请考试。" : "先完成晋升任务条件，再申请对应考试；考试完成并领奖后晋升。", MUTED);
            prose(lines, " ", MUTED);
        }
        JsonObject quest = selected();
        if (quest == null) {
            prose(lines, switch (tab) { case ACTIVE -> "尚未接取任务。请到任务板选择任务。"; case COMPLETED -> "暂无已完成的任务记录。"; case FAILED -> "暂无失败或放弃记录。"; case DAILY -> "今日暂无任务，请等待服务器刷新。"; case RANK -> "暂无当前阶段考试任务。"; default -> "等待服务端任务配置…"; }, MUTED);
            return lines;
        }
        prose(lines, ClientState.text(quest, "label", selectedId), GOLD);
        prose(lines, QuestJournalModel.statusLabel(quest) + (ClientState.bool(quest, "tracked", false) ? " · 已追踪" : ""), QuestJournalModel.ready(quest) ? GREEN : TEXT);
        prose(lines, "难度 " + QuestJournalModel.rule(quest, "difficulty", "D") + " · 最低称号 " + QuestJournalModel.rule(quest, "minimumRank", "D") + " 级", MUTED);
        String bounty = QuestJournalModel.rule(quest, "bountyTier", "NONE"); if (!bounty.equals("NONE")) prose(lines, QuestJournalText.bounty(bounty), GOLD);
        if (QuestJournalModel.exam(quest)) prose(lines, QuestJournalModel.rule(quest, "examRank", "") + " 级晋升考试", GOLD);
        if (QuestJournalModel.daily(quest)) prose(lines, "每日任务 · " + ClientState.text(guild(), "dailyDate", "按服务器日期刷新"), MUTED);
        String reason = ClientState.text(quest, "lockReason", "");
        boolean completedOnce = QuestJournalModel.status(quest).equals("CLAIMED") && !ClientState.bool(quest, "repeatable", false);
        if (!reason.isBlank() && !completedOnce && !(reason.equals("任务已接取") && (QuestJournalModel.active(quest) || QuestJournalModel.ready(quest)))) prose(lines, reason, RED);
        long now = System.currentTimeMillis(), cooldown = (long) ClientState.number(quest, "cooldownUntil", 0), deadline = (long) ClientState.number(quest, "deadline", 0);
        if (cooldown > now) prose(lines, "重新接取冷却：" + QuestJournalModel.remaining(cooldown, now), RED);
        if (deadline > 0 && QuestJournalModel.active(quest)) prose(lines, "剩余时间：" + QuestJournalModel.remaining(deadline, now), GOLD);
        prose(lines, ClientState.text(quest, "description", ""), TEXT);
        List<JsonObject> stages = QuestJournalModel.stages(quest);
        if (stages.isEmpty()) appendObjectives(lines, quest, -1);
        else for (int index = 0; index < stages.size(); index++) {
            JsonObject stage = stages.get(index); String id = ClientState.text(stage, "id", "");
            boolean claimed = QuestJournalModel.stageClaimed(quest, id), complete = QuestJournalModel.stageComplete(quest, index);
            prose(lines, "阶段 " + (index + 1) + " · " + ClientState.text(stage, "label", id) + (claimed ? " · 已领奖" : complete ? " · 已完成" : ""), GOLD);
            appendObjectives(lines, quest, index);
            for (String reward : QuestJournalText.rewards(ClientState.object(stage, "rewards"))) prose(lines, "阶段" + reward, claimed ? MUTED : GREEN);
        }
        prose(lines, stages.isEmpty() ? "任务奖励" : "最终任务奖励", GOLD);
        for (String reward : QuestJournalText.rewards(QuestJournalModel.rewards(quest))) prose(lines, reward, GREEN);
        int completions = (int) ClientState.number(quest, "completions", 0); if (completions > 0) prose(lines, "已完成并领奖 " + completions + " 次", MUTED);
        var history = ClientState.objects(quest, "history");
        if (!history.isEmpty()) {
            prose(lines, "历次任务记录", GOLD);
            for (int index = history.size() - 1; index >= 0; index--) {
                JsonObject attempt = history.get(index);
                long endedAt = (long) ClientState.number(attempt, "endedAt", 0);
                String time = endedAt > 0 ? java.time.Instant.ofEpochMilli(endedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "";
                prose(lines, time + " · " + QuestJournalModel.statusLabel(attempt), MUTED);
                JsonObject counts = ClientState.object(attempt, "objectiveCounts");
                for (JsonObject objective : ClientState.objects(quest, "objectives")) {
                    String id = ClientState.text(objective, "id", "");
                    prose(lines, "  " + QuestJournalText.objective(objective) + " " + (long) ClientState.number(counts, id, 0) + "/" + (long) ClientState.number(objective, "count", 1), MUTED);
                }
            }
        }
        if (ClientState.canEdit()) prose(lines, "任务 ID：" + selectedId, MUTED);
        return lines;
    }
    private void appendObjectives(List<Line> lines, JsonObject quest, int stage) {
        for (JsonObject objective : ClientState.objects(quest, "objectives")) {
            if (stage >= 0 && (int) ClientState.number(objective, "stage", 0) != stage) continue;
            long count = (long) ClientState.number(objective, "count", 1), current = (long) ClientState.number(objective, "current", 0);
            prose(lines, (current >= count ? "✓ " : "• ") + QuestJournalText.objective(objective) + "  " + current + "/" + count, current >= count ? GREEN : TEXT);
            String location = QuestJournalText.location(objective); if (!location.isBlank()) prose(lines, location, MUTED);
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= left + 12 && mouseX < left + panelWidth - 12 && mouseY >= detailY() && mouseY < top + panelHeight - 75) {
            if (mouseX < detailX()) {
                int pages = Math.max(1, (entries().size() + listRows - 1) / listRows); page = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(scrollY))); rebuild();
            } else detailScroll = Math.max(0, Math.min(Math.max(0, detailPixels - detailHeight()), detailScroll - (int) Math.signum(scrollY) * 24));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29); gui.fill(left, top, left + panelWidth, top + 3, GREEN);
        gui.drawString(font, title, left + 12, top + 13, 0xffedf6ee);
        String rank = "冒险者 " + ClientState.text(guild(), "rank", "D") + " 级";
        if (panelWidth > 430) gui.drawString(font, rank, left + panelWidth - font.width(rank) - 12, top + 13, GOLD);
        String summary = "追踪 " + QuestJournalModel.tracked(ClientState.quests()).size() + "/" + (int) ClientState.number(guild(), "trackLimit", 3) + " · " + rank
                + " · 声望 " + (long) ClientState.number(guild(), "reputation", 0);
        gui.drawString(font, font.plainSubstrByWidth(summary, panelWidth - 24), left + 12, top + 60, MUTED);
        gui.vLine(left + listWidth + 18, detailY() - 3, top + panelHeight - 78, 0xff30483b);
        JsonObject quest = selected();
        if (quest != null) for (int row = 0; row < listRows; row++) {
            int index = page * listRows + row; if (index >= entries().size()) break;
            if (ClientState.text(entries().get(index), "id", "").equals(selectedId)) gui.fill(left + 9, top + 78 + row * 23, left + 11, top + 98 + row * 23, GREEN);
        }
        List<Line> lines = lines(); detailPixels = lines.size() * 12;
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, detailPixels - detailHeight())));
        gui.enableScissor(detailX(), detailY(), detailX() + detailWidth(), detailY() + detailHeight());
        int y = detailY() - detailScroll;
        for (Line line : lines) { if (y + 12 > detailY() && y < detailY() + detailHeight()) gui.drawString(font, line.text(), detailX(), y + 1, line.color()); y += 12; }
        gui.disableScissor();
        if (detailPixels > detailHeight()) {
            int x = detailX() + detailWidth() - 2, thumb = Math.max(8, detailHeight() * detailHeight() / detailPixels);
            int offset = detailScroll * (detailHeight() - thumb) / Math.max(1, detailPixels - detailHeight());
            gui.fill(x, detailY(), x + 2, detailY() + detailHeight(), 0xff30483b); gui.fill(x, detailY() + offset, x + 2, detailY() + offset + thumb, GREEN);
        }
        int pages = Math.max(1, (entries().size() + listRows - 1) / listRows);
        gui.drawCenteredString(font, (page + 1) + "/" + pages, left + 12 + listWidth / 2, top + panelHeight - 64, MUTED);
        gui.drawString(font, font.plainSubstrByWidth(message, panelWidth - 24), left + 12, top + panelHeight - 82, MUTED);
        if (mouseY >= top + panelHeight - 84 && mouseY < top + panelHeight - 72 && mouseX >= left + 12 && mouseX < left + panelWidth - 12) gui.setTooltipForNextFrame(Component.literal(message), mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private static final class AbandonConfirmation extends Screen {
        private final GuildQuestScreen parent;
        private final String id, label;
        AbandonConfirmation(GuildQuestScreen parent, String id, String label) { super(Component.literal("确认放弃任务")); this.parent = parent; this.id = id; this.label = label; }
        @Override protected void init() {
            int y = height / 2 + 35;
            addRenderableWidget(Button.builder(Component.literal("确认放弃"), b -> { minecraft.setScreen(parent); parent.request("abandonQuest", id, "正在放弃，等待服务器结果…"); })
                    .bounds(width / 2 - 102, y, 98, 20).build());
            addRenderableWidget(Button.builder(Component.literal("保留任务"), b -> onClose()).bounds(width / 2 + 4, y, 98, 20).build());
        }
        @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            int w = Math.min(410, width - 16), x = (width - w) / 2, y = height / 2 - 65;
            gui.fill(x, y, x + w, height / 2 + 65, 0xf0121b29); gui.drawCenteredString(font, title, width / 2, y + 13, GOLD);
            String text = "放弃「" + font.plainSubstrByWidth(label, w - 60) + "」后停止推进，不发放完成奖励。重新接取与冷却按任务配置执行。";
            int lineY = y + 35;
            for (var line : font.split(Component.literal(text), w - 28)) { gui.drawString(font, line, x + 14, lineY, TEXT); lineY += 12; }
            super.render(gui, mouseX, mouseY, partialTick);
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
    }
}

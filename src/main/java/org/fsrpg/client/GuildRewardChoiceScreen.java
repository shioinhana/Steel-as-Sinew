package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A reward choice is an index; the server resolves the configured item and performs one claim. */
final class GuildRewardChoiceScreen extends Screen {
    private final GuildQuestScreen parent;
    private final String questId, stageId;
    private int selected = -1, page, rows, left, top, panelWidth, panelHeight;
    private long revision = ClientState.revision();
    private boolean pending;
    private String message = "选择一项；货币、固定道具与随机奖励随本次领奖一并结算";

    GuildRewardChoiceScreen(GuildQuestScreen parent, String questId, String stageId) {
        super(Component.literal("选择任务奖励")); this.parent = parent; this.questId = questId; this.stageId = stageId;
    }
    private JsonObject quest() {
        for (JsonObject quest : ClientState.quests()) if (ClientState.text(quest, "id", "").equals(questId)) return quest;
        return new JsonObject();
    }
    private JsonObject rewards() {
        if (stageId.isBlank()) return QuestJournalModel.rewards(quest());
        for (JsonObject stage : QuestJournalModel.stages(quest()))
            if (ClientState.text(stage, "id", "").equals(stageId)) return ClientState.object(stage, "rewards");
        return new JsonObject();
    }
    private List<JsonObject> choices() { return ClientState.objects(rewards(), "choices"); }
    private boolean eligible() {
        JsonObject quest = quest();
        if (stageId.isBlank()) return QuestJournalModel.canClaim(quest);
        if (!QuestJournalModel.active(quest) && !QuestJournalModel.ready(quest)) return false;
        var stages = QuestJournalModel.stages(quest);
        for (int i = 0; i < stages.size(); i++) if (ClientState.text(stages.get(i), "id", "").equals(stageId))
            return !QuestJournalModel.stageClaimed(quest, stageId) && QuestJournalModel.stageComplete(quest, i);
        return false;
    }
    @Override protected void init() {
        panelWidth = Math.min(520, width - 16); panelHeight = Math.min(380, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 129) / 29);
        List<JsonObject> choices = choices();
        int pages = Math.max(1, (choices.size() + rows - 1) / rows); page = Math.max(0, Math.min(pages - 1, page));
        if (selected < 0 && !choices.isEmpty()) selected = 0;
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row; if (index >= choices.size()) break;
            JsonObject item = choices.get(index); String text = (selected == index ? "✓ " : "") + QuestJournalText.item(item);
            Button button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(text, panelWidth - 50)), b -> { selected = index; rebuild(); })
                    .bounds(left + 12, top + 53 + row * 29, panelWidth - 24, 25).build());
            button.active = !pending && eligible(); button.setTooltip(Tooltip.create(Component.literal(QuestJournalText.item(item))));
        }
        int bottom = top + panelHeight - 29;
        Button claim = addRenderableWidget(Button.builder(Component.literal(pending ? "等待服务器…" : "领取选中奖励"), b -> {
            if (!eligible() || selected < 0 || selected >= choices().size()) return;
            pending = true; message = "正在领取，等待服务器结果…"; parent.sendClaim(questId, stageId, selected); rebuild();
        }).bounds(left + 12, bottom, 110, 20).build());
        claim.active = !pending && eligible() && selected >= 0 && selected < choices.size();
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(left + panelWidth - 60, bottom, 48, 20).build());
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page--; rebuild(); }).bounds(left + 12, bottom - 25, 20, 18).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { page++; rebuild(); }).bounds(left + panelWidth - 32, bottom - 25, 20, 18).build()).active = page + 1 < pages;
    }
    private void rebuild() { clearWidgets(); init(); }
    @Override public void tick() {
        if (revision != ClientState.revision()) {
            revision = ClientState.revision(); pending = false;
            String server = ClientState.text(ClientState.snapshot(), "message", ""); if (!server.isBlank()) message = server;
            if (!eligible()) message = "本次奖励已处理；可返回查看任务日志";
            rebuild();
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= left && mouseX < left + panelWidth && mouseY >= top + 50 && mouseY < top + panelHeight - 75) {
            int pages = Math.max(1, (choices().size() + rows - 1) / rows); page = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(scrollY))); rebuild(); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29); gui.fill(left, top, left + panelWidth, top + 3, 0xffefcd91);
        gui.drawString(font, title, left + 12, top + 13, 0xffefcd91);
        gui.drawString(font, font.plainSubstrByWidth(ClientState.text(quest(), "label", questId), panelWidth - 24), left + 12, top + 34, 0xffd4dee9);
        int pages = Math.max(1, (choices().size() + rows - 1) / rows);
        gui.drawCenteredString(font, (page + 1) + "/" + pages, left + panelWidth / 2, top + panelHeight - 48, 0xff9aafc4);
        gui.drawString(font, font.plainSubstrByWidth(message, panelWidth - 24), left + 12, top + panelHeight - 70, 0xff9aafc4);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= top + panelHeight - 74 && mouseY < top + panelHeight - 58)
            gui.setTooltipForNextFrame(Component.literal(message), mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); parent.acceptSnapshot(); }
    @Override public boolean isPauseScreen() { return false; }
}

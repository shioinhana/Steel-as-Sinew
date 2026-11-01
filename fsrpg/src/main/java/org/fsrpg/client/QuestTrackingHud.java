package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** A bounded upper-right tracker; selection and count limits are controlled by the server. */
final class QuestTrackingHud {
    private QuestTrackingHud() {}
    static void render(GuiGraphics gui) {
        var game = Minecraft.getInstance();
        if (game.screen != null) return;
        var tracked = QuestJournalModel.tracked(ClientState.quests()); if (tracked.isEmpty()) return;
        int width = Math.min(205, Math.max(120, gui.guiWidth() / 3)), left = gui.guiWidth() - width - 8;
        int y = game.player.getActiveEffects().isEmpty() ? 10 : 48;
        int bottom = gui.guiHeight() / 2 + 15;
        int maxObjectives = Math.max(1, Math.min(5, ((bottom - y - 16) / Math.max(1, tracked.size()) - 16) / 12));
        var font = game.font;
        gui.fill(left - 4, y - 3, left + width + 3, y + 11, 0x8c121b29);
        gui.drawString(font, "任务追踪 · J 查看", left, y, 0xffefcd91); y += 16;
        int shown = 0;
        for (JsonObject quest : tracked) {
            if (y + 27 > bottom) break;
            String label = ClientState.text(quest, "label", ClientState.text(quest, "id", "任务"));
            boolean ready = QuestJournalModel.ready(quest);
            gui.fill(left - 4, y - 2, left + width + 3, y + 11, 0x8c121b29);
            gui.drawString(font, font.plainSubstrByWidth((ready ? "✓ " : "• ") + label, width), left, y, ready ? 0xff92d7ae : 0xffd4dee9); y += 12;
            if (ready) { gui.drawString(font, "已完成 · 打开任务日志领奖", left + 3, y, 0xff92d7ae); y += 12; }
            else {
                int objectiveCount = 0;
                for (JsonObject objective : ClientState.objects(quest, "objectives")) {
                    int stage = (int) ClientState.number(quest, "stage", 0);
                    if (!QuestJournalModel.stages(quest).isEmpty() && (int) ClientState.number(objective, "stage", 0) != stage) continue;
                    if (y + 11 > bottom) break;
                    if (objectiveCount++ >= maxObjectives) break;
                    long current = (long) ClientState.number(objective, "current", 0), count = (long) ClientState.number(objective, "count", 1);
                    String numbers = current + "/" + count;
                    String name = QuestJournalText.objective(objective);
                    int available = Math.max(20, width - font.width(numbers) - 12);
                    gui.fill(left - 4, y - 1, left + width + 3, y + 11, 0x8c121b29);
                    gui.drawString(font, font.plainSubstrByWidth(name, available), left + 3, y, current >= count ? 0xff92d7ae : 0xff9aafc4);
                    gui.drawString(font, numbers, left + width - font.width(numbers), y, current >= count ? 0xff92d7ae : 0xffd4dee9); y += 12;
                }
                if (QuestJournalModel.claimableStage(quest) != null && y + 11 < bottom) { gui.drawString(font, "阶段已完成 · 可领取阶段奖励", left + 3, y, 0xffefcd91); y += 12; }
            }
            shown++; y += 4;
        }
        if (shown < tracked.size() && y + 10 < gui.guiHeight() - 60) gui.drawString(font, "另有 " + (tracked.size() - shown) + " 条追踪 · J 查看", left, y, 0xff9aafc4);
    }
}

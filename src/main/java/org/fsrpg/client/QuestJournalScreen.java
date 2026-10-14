package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Collection intentions only: the server decides completion, deduction and rewards. */
public final class QuestJournalScreen extends Screen {
    public enum Tab { START, MOBS, CROPS }
    private static final int TEXT = 0xffd4dee9, GREEN = 0xff92d7ae, GOLD = 0xffefcd91, MUTED = 0xff9aafc4;
    private Tab tab = Tab.START;
    private String selectedMob = "", selectedCrop = "", message = "首次狩猎后开启生物收集；作物可直接提交";
    private int page, left, top, panelWidth, panelHeight, listWidth, listRows, detailScroll, detailPixels;
    private long revision = ClientState.revision();
    private boolean pending;
    public QuestJournalScreen() { super(Component.literal("冒险任务")); }

    /** Public navigation also allows development review to exercise actual journal widgets. */
    public void showTab(Tab next) {
        tab = next; page = 0; detailScroll = 0;
        if (next != Tab.START) {
            JsonObject entry = selected();
            if (entry != null) page = entries().indexOf(entry) / Math.max(1, listRows);
        }
        contextMessage();
        if (minecraft != null) rebuild();
    }
    public void selectEntry(String id) {
        if (tab == Tab.MOBS) selectedMob = id; else selectedCrop = id;
        detailScroll = 0; List<JsonObject> entries = entries();
        for (int i = 0; i < entries.size(); i++) if (ClientState.text(entries.get(i), "id", "").equals(id)) { page = i / Math.max(1, listRows); break; }
        contextMessage();
        if (minecraft != null) rebuild();
    }
    @Override protected void init() {
        panelWidth = Math.min(700, width - 12); panelHeight = Math.min(420, height - 12);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        listWidth = Math.min(170, Math.max(96, panelWidth / 3)); listRows = Math.max(1, (panelHeight - 122) / 23);
        int tabWidth = Math.min(85, (panelWidth - 30) / 3);
        Tab[] values = Tab.values(); String[] labels = {"入门流程", "生物收集", "作物收集"};
        for (int i = 0; i < values.length; i++) {
            Tab next = values[i];
            addRenderableWidget(Button.builder(Component.literal((tab == next ? "• " : "") + labels[i]), b -> showTab(next))
                    .bounds(left + 12 + i * (tabWidth + 3), top + 29, tabWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("旧任务"), b -> minecraft.setScreen(new LegacyQuestJournalScreen(this)))
                .bounds(left + panelWidth - 64, top + 9, 52, 18).build());
        int bottom = top + panelHeight - 26;
        if (tab == Tab.START) {
            int actionWidth = Math.min(94, Math.max(64, (panelWidth - 83) / 2));
            addRenderableWidget(Button.builder(Component.literal("查看生物收集"), b -> showTab(Tab.MOBS)).bounds(left + 12, bottom, actionWidth, 18).build());
            addRenderableWidget(Button.builder(Component.literal("查看作物收集"), b -> showTab(Tab.CROPS)).bounds(left + 17 + actionWidth, bottom, actionWidth, 18).build());
        } else {
            List<JsonObject> entries = entries(); selected();
            int pages = Math.max(1, (entries.size() + listRows - 1) / listRows); page = Math.max(0, Math.min(page, pages - 1));
            for (int row = 0; row < listRows; row++) {
                int index = page * listRows + row; if (index >= entries.size()) break;
                JsonObject entry = entries.get(index); String id = ClientState.text(entry, "id", ""), label = entryLabel(entry);
                String prefix = ClientState.bool(entry, "submitted", false) ? "✓ " : ClientState.bool(entry, "canSubmit", false) ? "+ " : "";
                Button button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(prefix + label, listWidth - 12)), b -> selectEntry(id))
                        .bounds(left + 12, top + 76 + row * 23, listWidth, 20).build());
                button.setTooltip(Tooltip.create(Component.literal(label + " · " + entryStatus(entry))));
            }
            addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page--; rebuild(); }).bounds(left + 12, bottom, 21, 18).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal("›"), b -> { page++; rebuild(); }).bounds(left + listWidth - 9, bottom, 21, 18).build()).active = page + 1 < pages;
            JsonObject entry = selected(); int detailX = left + listWidth + 25;
            boolean choice = tab == Tab.CROPS && entry != null && ClientState.bool(entry, "pendingChoice", false);
            String label = choice ? "选择种子奖励" : entry != null && ClientState.bool(entry, "submitted", false) ? "已完成提交" : "提交本种材料";
            Button action = addRenderableWidget(Button.builder(Component.literal(label), b -> {
                JsonObject current = selected(); if (current == null) return; String id = ClientState.text(current, "id", "");
                if (tab == Tab.CROPS && ClientState.bool(current, "pendingChoice", false)) minecraft.setScreen(new CropRewardScreen(this, id));
                else { pending = true; message = "正在提交，等待服务端结果…"; ClientHooks.send(tab == Tab.MOBS ? "collectionSubmitMob" : "collectionSubmitCrop", id); rebuild(); }
            }).bounds(detailX, bottom, Math.max(60, Math.min(115, panelWidth - listWidth - 92)), 18).build());
            action.active = !pending && entry != null && (choice || ClientState.bool(entry, "canSubmit", false));
            action.setTooltip(Tooltip.create(Component.literal(pending ? "正在等待服务器" : entry == null ? "等待任务配置" : choice ? "已提交材料，请从其它作物中选择一种种子" : submitReason(entry))));
        }
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(left + panelWidth - 52, bottom, 40, 18).build());
    }
    private String submitReason(JsonObject entry) {
        if (ClientState.bool(entry, "submitted", false)) return "每种生物或作物仅能提交一次";
        if (tab == Tab.MOBS && !firstHuntComplete()) return "先猎杀第一个怪物";
        return ClientState.bool(entry, "canSubmit", false) ? "扣除所列材料后由服务器发放奖励" : "材料不足；背包数量显示在提交需求中";
    }
    private void contextMessage() {
        if (tab == Tab.START) { message = firstHuntComplete() ? "首次狩猎已完成；累计收集奖励在达成时自动发放" : "首次狩猎后开启生物收集；作物可直接提交"; return; }
        JsonObject entry = selected(); if (entry == null) { message = "等待服务端任务配置…"; return; }
        if (tab == Tab.CROPS && ClientState.bool(entry, "pendingChoice", false)) message = "材料已提交，请选择其它作物的种子奖励";
        else if (ClientState.bool(entry, "submitted", false)) message = "本种任务已完成，奖励已发放；每种只能提交一次";
        else if (tab == Tab.MOBS && !firstHuntComplete()) message = "先猎杀第一个怪物，完成首次狩猎";
        else if (ClientState.bool(entry, "canSubmit", false)) message = "材料齐全，可提交本种材料";
        else message = tab == Tab.MOBS ? "收齐所列掉落物后提交，不包含装备" : "每种交 1 个种子与 10 个成熟作物；同一物品合计 11 个";
    }
    private void rebuild() { clearWidgets(); init(); }
    public void acceptSnapshot() {
        revision = ClientState.revision(); pending = false;
        String serverMessage = ClientState.text(ClientState.snapshot(), "message", ""); if (!serverMessage.isBlank()) message = serverMessage;
        if (panelWidth > 0) rebuild();
    }
    @Override public void tick() { if (revision != ClientState.revision()) acceptSnapshot(); }
    private boolean firstHuntComplete() { return ClientState.bool(ClientState.object(ClientState.collection(), "firstHunt"), "complete", false); }
    private List<JsonObject> entries() { return ClientState.objects(ClientState.collection(), tab == Tab.MOBS ? "mobs" : "crops"); }
    private JsonObject selected() {
        String id = tab == Tab.MOBS ? selectedMob : selectedCrop; List<JsonObject> entries = entries();
        for (JsonObject entry : entries) if (ClientState.text(entry, "id", "").equals(id)) return entry;
        if (entries.isEmpty()) return null; JsonObject entry = entries.getFirst();
        if (tab == Tab.CROPS) for (JsonObject crop : entries) if (ClientState.bool(crop, "pendingChoice", false)) { entry = crop; break; }
        if (tab == Tab.MOBS) selectedMob = ClientState.text(entry, "id", ""); else selectedCrop = ClientState.text(entry, "id", "");
        page = entries.indexOf(entry) / Math.max(1, listRows);
        return entry;
    }
    private String entryLabel(JsonObject entry) {
        if (tab == Tab.MOBS) return CollectionItems.entity(ClientState.text(entry, "entity", ClientState.text(entry, "id", "")), ClientState.text(entry, "label", "生物"));
        return ClientState.text(entry, "label", CollectionItems.label(ClientState.text(entry, "mature", "")));
    }
    private String entryStatus(JsonObject entry) {
        if (tab == Tab.CROPS && ClientState.bool(entry, "pendingChoice", false)) return "已完成提交 · 待选择奖励";
        if (ClientState.bool(entry, "submitted", false)) return "已完成提交 · 奖励已发放";
        if (tab == Tab.MOBS && !firstHuntComplete()) return "先完成首次狩猎";
        return ClientState.bool(entry, "canSubmit", false) ? "材料齐全 · 可提交" : "材料尚未齐全";
    }
    private record Row(ItemStack item, String text, String subtext, int color, int height) {}
    private void prose(List<Row> rows, String text, int color, int availableWidth) {
        for (var line : font.split(Component.literal(text), availableWidth)) {
            StringBuilder part = new StringBuilder(); line.accept((index, style, codepoint) -> { part.appendCodePoint(codepoint); return true; });
            rows.add(new Row(ItemStack.EMPTY, part.toString(), "", color, 12));
        }
    }
    private void heading(List<Row> rows, String text) { rows.add(new Row(ItemStack.EMPTY, text, "", GOLD, 18)); }
    private void rewards(List<Row> rows, List<JsonObject> rewards) {
        for (JsonObject item : rewards) { ItemStack stack = CollectionItems.stack(item); rows.add(new Row(stack, CollectionItems.label(stack) + " × " + (int) ClientState.number(item, "count", 1), "", GREEN, 21)); }
    }
    private List<Row> detailRows(int availableWidth) {
        List<Row> rows = new ArrayList<>(); JsonObject collection = ClientState.collection();
        if (tab == Tab.START) {
            JsonObject hunt = ClientState.object(collection, "firstHunt"); boolean complete = firstHuntComplete();
            heading(rows, "1. 猎杀第一个怪物");
            prose(rows, complete ? "✓ 已完成首次狩猎：" + CollectionItems.entity(ClientState.text(hunt, "entityId", ""), "怪物") : "击败任意一个怪物，自动完成并开启生物收集。", complete ? GREEN : TEXT, availableWidth);
            heading(rows, "2. 提交生物掉落物");
            prose(rows, "每种生物的全部非装备掉落物各交 1 份，获得这些物品各 2 份。每种只提交一次。", TEXT, availableWidth);
            for (JsonObject milestone : ClientState.objects(collection, "mobMilestones")) {
                int target = (int) ClientState.number(milestone, "target", 0); boolean claimed = ClientState.bool(milestone, "claimed", false);
                heading(rows, (claimed ? "✓ " : "• ") + "累计 " + target + " 种：" + (claimed ? "奖励已发放" : Math.min(target, (int) ClientState.number(collection, "mobCount", 0)) + "/" + target));
                rewards(rows, ClientState.objects(milestone, "rewards"));
            }
            heading(rows, "3. 提交作物并选择种子");
            prose(rows, "每种交 1 个种子和 10 个成熟作物。种子与作物相同的种类合计交 11 个。", TEXT, availableWidth);
            prose(rows, "每种提交后，从其它作物中选一种，获得种子 5 个和骨头 3 个。未选择的奖励会保留。", TEXT, availableWidth);
            JsonObject milestone = ClientState.object(collection, "cropMilestone");
            heading(rows, (ClientState.bool(milestone, "claimed", false) ? "✓ " : "• ") + "累计 " + (int) ClientState.number(milestone, "target", 10) + " 种：所有种子各 1 个");
            prose(rows, ClientState.bool(milestone, "claimed", false) ? "奖励已自动发放" : "达到目标后自动发放；奖励清单可在作物图鉴中查看。", MUTED, availableWidth);
        } else {
            JsonObject entry = selected(); if (entry == null) { prose(rows, "等待服务端任务配置…", MUTED, availableWidth); return rows; }
            boolean submitted = ClientState.bool(entry, "submitted", false);
            heading(rows, submitted ? "已提交材料（记录）" : "提交需求");
            for (JsonObject requirement : ClientState.objects(entry, "requirements")) {
                ItemStack stack = CollectionItems.stack(requirement); int need = (int) ClientState.number(requirement, "count", 1), owned = (int) ClientState.number(requirement, "owned", 0);
                rows.add(new Row(stack, CollectionItems.label(stack) + " × " + need,
                        submitted ? "已交 " + need + " · 背包 " + owned : "背包 " + owned + "/" + need,
                        submitted ? MUTED : owned >= need ? GREEN : 0xffe9aaaa, 28));
            }
            if (tab == Tab.MOBS) {
                prose(rows, "不需要提交装备。", MUTED, availableWidth); heading(rows, "本种奖励"); rewards(rows, ClientState.objects(entry, "rewards"));
                if (ClientState.bool(entry, "submitted", false)) prose(rows, "✓ 已提交，奖励已发放。", GREEN, availableWidth);
            } else {
                if (ClientState.text(entry, "seed", "").equals(ClientState.text(entry, "mature", ""))) prose(rows, "种子与成熟作物是同一物品，需要合计 11 个。", MUTED, availableWidth);
                heading(rows, "本种奖励"); prose(rows, "选择其它作物的种子 5 个，并获得骨头 3 个。", GREEN, availableWidth);
                if (ClientState.bool(entry, "pendingChoice", false)) prose(rows, "✓ 材料已提交；点击下方按钮选择种子。关闭界面也会保留本次奖励。", GOLD, availableWidth);
                else if (ClientState.bool(entry, "rewardClaimed", false)) prose(rows, "✓ 已提交并领取种子奖励。", GREEN, availableWidth);
            }
            heading(rows, "累计收集奖励");
            if (tab == Tab.MOBS) for (JsonObject milestone : ClientState.objects(collection, "mobMilestones")) {
                prose(rows, "提交 " + (int) ClientState.number(milestone, "target", 0) + " 种：" + (ClientState.bool(milestone, "claimed", false) ? "已发放" : "达成后自动发放"), GOLD, availableWidth);
                rewards(rows, ClientState.objects(milestone, "rewards"));
            } else {
                JsonObject milestone = ClientState.object(collection, "cropMilestone");
                prose(rows, "提交 " + (int) ClientState.number(milestone, "target", 10) + " 种：所有种子各 1 个", GOLD, availableWidth);
                prose(rows, ClientState.bool(milestone, "claimed", false) ? "✓ 已自动发放" : "达成后自动发放", MUTED, availableWidth);
                rewards(rows, ClientState.objects(milestone, "rewards"));
            }
        }
        return rows;
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= left + 12 && mouseX < left + panelWidth - 12 && mouseY >= top + 65 && mouseY < top + panelHeight - 44) {
            if (tab != Tab.START && mouseX < left + listWidth + 18) {
                int pages = Math.max(1, (entries().size() + listRows - 1) / listRows); page = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(scrollY))); rebuild();
            } else detailScroll = Math.max(0, Math.min(Math.max(0, detailPixels - detailHeight()), detailScroll - (int) Math.signum(scrollY) * 24));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    private int detailY() { return top + (tab == Tab.START ? 75 : 101); }
    private int detailHeight() { return Math.max(8, top + panelHeight - 49 - detailY()); }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29); gui.fill(left, top, left + panelWidth, top + 3, 0xff8ec8a0);
        gui.drawString(font, title, left + 12, top + 13, 0xffedf6ee); JsonObject collection = ClientState.collection();
        String progress = "生物 " + (int) ClientState.number(collection, "mobCount", 0) + "/" + ClientState.objects(collection, "mobs").size()
                + " 种  ·  作物 " + (int) ClientState.number(collection, "cropCount", 0) + "/" + ClientState.objects(collection, "crops").size() + " 种";
        gui.drawString(font, font.plainSubstrByWidth(progress, panelWidth - 24), left + 12, top + 57, MUTED);
        int detailX = tab == Tab.START ? left + 12 : left + listWidth + 25, detailWidth = left + panelWidth - 16 - detailX;
        if (tab != Tab.START) {
            gui.vLine(left + listWidth + 18, top + 73, top + panelHeight - 47, 0xff30483b); JsonObject entry = selected();
            if (entry != null) {
                gui.drawString(font, font.plainSubstrByWidth(entryLabel(entry), detailWidth), detailX, top + 75, GOLD);
                gui.drawString(font, font.plainSubstrByWidth(entryStatus(entry), detailWidth), detailX, top + 88, GREEN);
                for (int row = 0; row < listRows; row++) {
                    int index = page * listRows + row; if (index >= entries().size()) break;
                    if (ClientState.text(entries().get(index), "id", "").equals(ClientState.text(entry, "id", ""))) gui.fill(left + 9, top + 76 + row * 23, left + 11, top + 96 + row * 23, GREEN);
                }
            }
            int pages = Math.max(1, (entries().size() + listRows - 1) / listRows); gui.drawCenteredString(font, (page + 1) + "/" + pages, left + 12 + listWidth / 2, top + panelHeight - 21, MUTED);
        }
        List<Row> lines = detailRows(detailWidth - 6); detailPixels = lines.stream().mapToInt(Row::height).sum(); int available = detailHeight();
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, detailPixels - available)));
        gui.enableScissor(detailX, detailY(), detailX + detailWidth, detailY() + available); int y = detailY() - detailScroll;
        for (Row row : lines) {
            if (y + row.height() > detailY() && y < detailY() + available) {
                int textX = detailX; if (!row.item().isEmpty()) { gui.renderItem(row.item(), detailX + 1, y + 1); textX += 22; }
                gui.drawString(font, font.plainSubstrByWidth(row.text(), detailWidth - (textX - detailX) - 5), textX, y + 3, row.color());
                if (!row.subtext().isBlank()) gui.drawString(font, row.subtext(), textX, y + 15, row.color());
                if (mouseX >= detailX && mouseX < detailX + detailWidth && mouseY >= Math.max(y, detailY()) && mouseY < Math.min(y + row.height(), detailY() + available))
                    gui.setTooltipForNextFrame(Component.literal(row.text() + (row.subtext().isBlank() ? "" : " · " + row.subtext())), mouseX, mouseY);
            }
            y += row.height();
        }
        gui.disableScissor();
        if (detailPixels > available) {
            int barX = detailX + detailWidth - 2, thumb = Math.max(8, available * available / detailPixels), offset = detailScroll * (available - thumb) / Math.max(1, detailPixels - available);
            gui.fill(barX, detailY(), barX + 2, detailY() + available, 0xff30483b); gui.fill(barX, detailY() + offset, barX + 2, detailY() + offset + thumb, 0xff8ec8a0);
        }
        gui.drawString(font, font.plainSubstrByWidth(message, panelWidth - 24), left + 12, top + panelHeight - 40, MUTED);
        if (mouseY >= top + panelHeight - 43 && mouseY < top + panelHeight - 27 && mouseX >= left + 12 && mouseX < left + panelWidth - 12) gui.setTooltipForNextFrame(Component.literal(message), mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
    }
    @Override public boolean isPauseScreen() { return false; }
}

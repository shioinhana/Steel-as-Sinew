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

/** Persistent pending rewards are supplied by the server; only the chosen target is sent. */
public final class CropRewardScreen extends Screen {
    private final QuestJournalScreen parent;
    private final String sourceId;
    private String selectedId = "", message = "请选择其它作物；每次奖励只能领取一次";
    private int left, top, panelWidth, panelHeight, columns, rows, page, cellWidth;
    private long revision = ClientState.revision();
    private boolean pending;
    private final List<Cell> cells = new ArrayList<>();
    private record Cell(String id, String label, ItemStack item, int x, int y) {}

    public CropRewardScreen(QuestJournalScreen parent, String sourceId) {
        super(Component.literal("选择种子奖励")); this.parent = parent; this.sourceId = sourceId;
    }
    @Override protected void init() {
        cells.clear(); panelWidth = Math.min(570, width - 12); panelHeight = Math.min(370, height - 12);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        columns = panelWidth >= 480 ? 3 : 2; cellWidth = (panelWidth - 24 - 4 * (columns - 1)) / columns;
        rows = Math.max(1, (panelHeight - 164) / 32);
        List<JsonObject> choices = choices(); int pages = Math.max(1, (choices.size() + capacity() - 1) / capacity());
        page = Math.max(0, Math.min(page, pages - 1));
        if (selected() == null && !choices.isEmpty()) selectedId = ClientState.text(choices.getFirst(), "id", "");
        for (int slot = 0; slot < capacity(); slot++) {
            int index = page * capacity() + slot; if (index >= choices.size()) break;
            JsonObject choice = choices.get(index); String id = ClientState.text(choice, "id", ""), seed = ClientState.text(choice, "seed", "");
            ItemStack item = CollectionItems.stack(seed); String label = CollectionItems.label(item);
            int x = left + 12 + slot % columns * (cellWidth + 4), y = top + 73 + slot / columns * 32;
            cells.add(new Cell(id, label, item, x, y));
            Button button = addRenderableWidget(Button.builder(Component.literal("     " + font.plainSubstrByWidth(label, cellWidth - 42)), b -> { selectedId = id; rebuild(); })
                    .bounds(x, y, cellWidth, 27).build());
            button.setTooltip(Tooltip.create(Component.literal(label + " × 5 + 骨头 × 3")));
            button.active = !pending && pendingChoice();
        }
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page--; rebuild(); }).bounds(left + panelWidth - 81, top + 49, 20, 18).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { page++; rebuild(); }).bounds(left + panelWidth - 32, top + 49, 20, 18).build()).active = page + 1 < pages;
        int bottom = top + panelHeight - 26;
        Button claim = addRenderableWidget(Button.builder(Component.literal(pending ? "等待服务器…" : "领取选中奖励"), b -> {
            if (selected() == null || !pendingChoice()) return;
            pending = true; message = "正在领取，等待服务端结果…"; ClientHooks.send("collectionClaimSeeds", sourceId + "|" + selectedId); rebuild();
        }).bounds(left + 12, bottom, 108, 18).build());
        claim.active = !pending && pendingChoice() && selected() != null;
        claim.setTooltip(Tooltip.create(Component.literal(pendingChoice() ? "领取选择的种子 5 个和骨头 3 个" : "本次奖励已经领取")));
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(left + panelWidth - 60, bottom, 48, 18).build());
    }
    private void rebuild() { clearWidgets(); init(); }
    private int capacity() { return Math.max(1, columns * rows); }
    private List<JsonObject> choices() {
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject choice : ClientState.objects(ClientState.collection(), "seedChoices"))
            if (!ClientState.text(choice, "id", "").equals(sourceId)) result.add(choice);
        return result;
    }
    private JsonObject source() {
        for (JsonObject crop : ClientState.objects(ClientState.collection(), "crops")) if (ClientState.text(crop, "id", "").equals(sourceId)) return crop;
        return new JsonObject();
    }
    private boolean pendingChoice() { return ClientState.bool(source(), "pendingChoice", false); }
    private JsonObject selected() {
        for (JsonObject choice : choices()) if (ClientState.text(choice, "id", "").equals(selectedId)) return choice;
        return null;
    }
    @Override public void tick() {
        if (revision != ClientState.revision()) {
            revision = ClientState.revision(); pending = false;
            String serverMessage = ClientState.text(ClientState.snapshot(), "message", ""); if (!serverMessage.isBlank()) message = serverMessage;
            if (ClientState.bool(source(), "rewardClaimed", false) && !pendingChoice()) message = serverMessage.isBlank() ? "✓ 奖励已领取，可返回提交其它作物" : serverMessage;
            rebuild();
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= left + 12 && mouseX < left + panelWidth - 12 && mouseY >= top + 71 && mouseY < top + panelHeight - 85) {
            int pages = Math.max(1, (choices().size() + capacity() - 1) / capacity()); page = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(scrollY))); rebuild(); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29); gui.fill(left, top, left + panelWidth, top + 3, 0xffd1b580);
        gui.drawString(font, title, left + 12, top + 13, 0xffefcd91);
        gui.drawString(font, font.plainSubstrByWidth("选择其它一种作物，获得种子 5 个与骨头 3 个", panelWidth - 24), left + 12, top + 34, 0xffbdc9d7);
        gui.drawString(font, font.plainSubstrByWidth("已提交：" + ClientState.text(source(), "label", "作物"), panelWidth - 112), left + 12, top + 54, 0xff92d7ae);
        int pages = Math.max(1, (choices().size() + capacity() - 1) / capacity());
        gui.drawCenteredString(font, (page + 1) + "/" + pages, left + panelWidth - 47, top + 54, 0xff9aafc4);
        JsonObject choice = selected();
        if (choice != null) {
            ItemStack seed = CollectionItems.stack(ClientState.text(choice, "seed", ""));
            int y = top + panelHeight - 77; gui.renderItem(seed, left + 12, y);
            gui.drawString(font, font.plainSubstrByWidth("已选：" + CollectionItems.label(seed) + " × 5", panelWidth - 48), left + 34, y + 4, 0xff92d7ae);
            gui.renderItem(CollectionItems.stack("minecraft:bone"), left + 12, y + 19);
            gui.drawString(font, "另获骨头 × 3", left + 34, y + 23, 0xffbdc9d7);
        }
        gui.drawString(font, font.plainSubstrByWidth(message, panelWidth - 24), left + 12, top + panelHeight - 38, 0xff9aafc4);
        if (mouseY >= top + panelHeight - 42 && mouseY < top + panelHeight - 26 && mouseX >= left + 12 && mouseX < left + panelWidth - 12) gui.setTooltipForNextFrame(Component.literal(message), mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
        // Item states and widget backgrounds are batched separately. Put overlays in a later
        // stratum so their ordering never depends on native widget hover rendering.
        gui.nextStratum();
        for (Cell cell : cells) {
            gui.renderItem(cell.item(), cell.x() + 5, cell.y() + 5);
            if (cell.id().equals(selectedId)) {
                gui.hLine(cell.x(), cell.x() + cellWidth - 1, cell.y(), 0xff92d7ae);
                gui.hLine(cell.x(), cell.x() + cellWidth - 1, cell.y() + 26, 0xff92d7ae);
            }
        }
    }
    @Override public void onClose() { minecraft.setScreen(parent); parent.acceptSnapshot(); }
    @Override public boolean isPauseScreen() { return false; }
}

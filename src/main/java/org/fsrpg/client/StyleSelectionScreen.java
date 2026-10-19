package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shows server-defined weapon styles, including deliberately disabled future slots. */
public final class StyleSelectionScreen extends Screen {
    private int page, rows, left, top, panelWidth, panelHeight;
    public StyleSelectionScreen() { super(Component.literal("武器档位")); }
    @Override protected void init() {
        panelWidth = Math.min(420, width - 16); panelHeight = Math.min(310, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 88) / 32);
        var styles = ClientState.objects(ClientState.snapshot(), "styles");
        int pages = Math.max(1, (styles.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        String current = ClientState.text(ClientState.snapshot(), "weaponStyle", "");
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row; if (index >= styles.size()) break;
            JsonObject style = styles.get(index);
            String id = ClientState.text(style, "id", "");
            boolean enabled = ClientState.bool(style, "enabled", false);
            String label = ClientState.text(style, "label", id);
            String marker = current.equals(id) ? "✓ 当前 · " : enabled ? "选择 · " : "预留 · ";
            Button button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(marker + label, panelWidth - 40)), b -> {
                ClientHooks.send("weaponStyle", id);
            }).bounds(left + 12, top + 49 + row * 32, panelWidth - 24, 27).build());
            button.active = enabled;
            int count = ClientState.objects(style, "leftCombo").size();
            String mode = ClientState.text(ClientState.object(style, "controls"), "mode", "ordered");
            if(ClientState.text(style,"weaponGroup","").equals("bow"))mode="bow";
            String control=switch(mode) {
                case "legacy_dual" -> "左键向左斩 / 右键向右斩 · 双键同时按双刀合击";
                case "dual" -> "左键左手斩 / 右键右手斩 · 点按一次一刀";
                case "spear" -> "点按刺击 / 按住拖动扫击";
                case "bow_rapid", "bow_snipe", "bow", "archery" -> "左键弓身击退（50 熟练度后速射可按住自动引弓） · 右键按住拉弦、松开放箭 · V 选择速射/狙击";
                default -> "左键 " + count + " 段连击 · 右键蓄力刺击";
            };
            button.setTooltip(Tooltip.create(Component.literal(enabled ? control : "该档位尚未启用")));
        }
        int bottom = top + panelHeight - 26;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page--; rebuild(); }).bounds(left + 12, bottom, 24, 18).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { page++; rebuild(); }).bounds(left + 43, bottom, 24, 18).build()).active = page + 1 < pages;
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(left + panelWidth - 62, bottom, 50, 18).build());
    }
    private void rebuild() { clearWidgets(); init(); }
    public void acceptSnapshot() { if (panelWidth > 0) rebuild(); }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29);
        gui.fill(left, top, left + panelWidth, top + 3, 0xffc7b185);
        gui.drawString(font, title, left + 12, top + 13, 0xffefd8a9);
        String message = ClientState.text(ClientState.snapshot(), "message", "");
        gui.drawString(font, font.plainSubstrByWidth(message.isBlank() ? "选择当前武器姿态 · R 拔刀/收刀" : message, panelWidth - 24), left + 12, top + 30, 0xffa8b7c9);
        int pages = Math.max(1, (ClientState.objects(ClientState.snapshot(), "styles").size() + rows - 1) / rows);
        gui.drawCenteredString(font, (page + 1) + "/" + pages, left + panelWidth / 2, top + panelHeight - 20, 0xffa8b7c9);
        super.render(gui, mouseX, mouseY, partialTick);
    }
    @Override public boolean isPauseScreen() { return false; }
}

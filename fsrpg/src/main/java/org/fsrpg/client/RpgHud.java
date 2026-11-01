package org.fsrpg.client;

import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.fsrpg.mixin.GuiContextAccess;

/** Material resource bars around the official item hotbar. Combat values stay server-authoritative. */
public final class RpgHud {
    public static final Identifier LAYER = Identifier.fromNamespaceAndPath("fsrpg", "resource_bars");
    private static HudLayout lastLayout;
    private RpgHud() {}

    public static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CONTEXTUAL_INFO_BAR, LAYER, (gui, delta) -> render(gui));
    }
    public static boolean visible() {
        var game = Minecraft.getInstance();
        return ClientOptions.RPG_HUD.get() && game.player != null && game.level != null && game.gameMode != null
                && !game.player.isSpectator() && game.getCameraEntity() == game.player
                && !game.options.hideGui;
    }
    public static void beforeLayer(RenderGuiLayerEvent.Pre event) {
        if (!visible()) return;
        var name = event.getName();
        var game = Minecraft.getInstance();
        if (name.equals(VanillaGuiLayers.PLAYER_HEALTH)) {
            // Keep native armor at its original single-heart-row height, even with a large max health.
            game.gui.leftHeight += 10;
            event.setCanceled(true);
        } else if (name.equals(VanillaGuiLayers.EXPERIENCE_LEVEL)) {
            event.setCanceled(true);
        } else if ((name.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND)
                || name.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR))
                && ((GuiContextAccess) game.gui).fsrpg$contextualBar().getRight() instanceof ExperienceBarRenderer) {
            event.setCanceled(true);
        }
    }
    public static HudLayout layout(GuiGraphics gui) {
        var game = Minecraft.getInstance();
        int left = 0, right = 0;
        boolean offhandLeft = game.player.getMainArm() == HumanoidArm.RIGHT;
        if (!game.player.getOffhandItem().isEmpty()) {
            if (offhandLeft) left = 29; else right = 29;
        }
        if (game.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR) {
            if (offhandLeft) right = Math.max(right, 24); else left = Math.max(left, 24);
        }
        return HudLayout.fit(gui.guiWidth(), gui.guiHeight(), left, right, Math.max(game.gui.leftHeight, game.gui.rightHeight));
    }
    public static HudLayout lastLayout() { return lastLayout; }

    public static void render(GuiGraphics gui) {
        if (!visible()) return;
        var game = Minecraft.getInstance();
        var player = game.player;
        var position = layout(gui);
        lastLayout = position;
        int x = position.leftX(), right = position.rightX(), width = position.barWidth(), y = position.topY();
        var health = HudReadout.of(player.getHealth(), player.getMaxHealth());
        String healthText = health.label();
        if (player.getAbsorptionAmount() > 0) healthText += " +" + HudReadout.number(player.getAbsorptionAmount());
        bar(gui, "health", x, y, width, health.ratio(), healthText);
        if (player.getAbsorptionAmount() > 0) {
            int shield = (int) Math.round((width - 4) * Math.min(1, player.getAbsorptionAmount() / Math.max(1, player.getMaxHealth())));
            gui.fill(x + 2, y + 8, x + 2 + shield, y + 9, 0xffffd45b);
        }
        String heart = player.hasEffect(MobEffects.POISON) ? "poisoned_full"
                : player.hasEffect(MobEffects.WITHER) ? "withered_full"
                : player.isFullyFrozen() ? "frozen_full" : "full";
        gui.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("hud/heart/" + heart), x - 11, y, 9, 9);

        double xp = Math.max(0, Math.min(1, player.experienceProgress));
        bar(gui, "experience", x, y + HudLayout.ROW_STEP, width, xp,
                "Lv " + player.experienceLevel + " " + (int) Math.floor(xp * 100) + "%");
        marker(gui, "经", x - 11, y + HudLayout.ROW_STEP, 0xffa8e56d);

        var state = ClientState.snapshot();
        var spirit = HudReadout.of(ClientState.number(state, "spirit", Double.NaN), ClientState.number(state, "maxSpirit", Double.NaN));
        var stamina = HudReadout.of(ClientState.number(state, "stamina", Double.NaN), ClientState.number(state, "maxStamina", Double.NaN));
        bar(gui, "spirit", right, y, width, spirit.ratio(), spirit.label());
        marker(gui, "灵", right + width + 3, y, 0xff78def1);
        bar(gui, "stamina", right, y + HudLayout.ROW_STEP, width, stamina.ratio(), stamina.label());
        marker(gui, "体", right + width + 3, y + HudLayout.ROW_STEP, 0xff9ae2bc);

        if (position.compact()) {
            int reserved = gui.guiHeight() - y + 11;
            game.gui.leftHeight = Math.max(game.gui.leftHeight, reserved);
            game.gui.rightHeight = Math.max(game.gui.rightHeight, reserved);
        }
        QuestTrackingHud.render(gui);
    }
    private static Identifier texture(String kind, String layer) {
        return Identifier.fromNamespaceAndPath("fsrpg", "textures/gui/hud/" + kind + "_" + layer + ".png");
    }
    private static void bar(GuiGraphics gui, String kind, int x, int y, int width, double ratio, String label) {
        // Prepared textures use a shared canvas. Clip the fill; never stretch the visible fraction.
        gui.blit(RenderPipelines.GUI_TEXTURED, texture(kind, "empty"), x, y, 0, 0, width, 10, 256, 24, 256, 24);
        int sourceInset = kind.equals("stamina") ? 16 : kind.equals("experience") ? 8 : 7;
        int start = (int) Math.floor(width * sourceInset / 256.0);
        int sourceEnd = kind.equals("stamina") ? 242 : 256 - sourceInset;
        int end = (int) Math.ceil(width * sourceEnd / 256.0);
        int pixels = (int) Math.round(Math.max(0, Math.min(1, ratio)) * (end - start));
        if (pixels > 0) {
            gui.enableScissor(x + start, y, x + start + pixels, y + 10);
            gui.blit(RenderPipelines.GUI_TEXTURED, texture(kind, "fill"), x, y, 0, 0, width, 10, 256, 24, 256, 24);
            gui.disableScissor();
        }
        var font = Minecraft.getInstance().font;
        float scale = Math.min(.8f, (width - 6f) / Math.max(1, font.width(label)));
        gui.pose().pushMatrix();
        gui.pose().translate(x + width / 2f, y + (10 - font.lineHeight * scale) / 2f);
        gui.pose().scale(scale, scale);
        gui.drawString(font, label, -font.width(label) / 2, 0, 0xfff4f5ed, true);
        gui.pose().popMatrix();
    }
    private static void marker(GuiGraphics gui, String label, int x, int y, int color) {
        var font = Minecraft.getInstance().font;
        gui.pose().pushMatrix();
        gui.pose().translate(x, y + 1);
        gui.pose().scale(.8f, .8f);
        gui.drawString(font, label, 0, 0, color, true);
        gui.pose().popMatrix();
    }
}

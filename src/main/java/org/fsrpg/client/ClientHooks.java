package org.fsrpg.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.network.RpgActionPayload;
import org.fsrpg.network.RpgSnapshotPayload;
import org.fsrpg.network.RpgAnimationPayload;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientHooks {
    private static final Logger LOG = LoggerFactory.getLogger("FSRPG client");
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("fsrpg", "combat"));
    private static final KeyMapping DRAW = key("draw", GLFW.GLFW_KEY_R);
    private static final KeyMapping LEFT = new KeyMapping("key.fsrpg.left", KeyConflictContext.IN_GAME, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);
    private static final KeyMapping RIGHT = new KeyMapping("key.fsrpg.right", KeyConflictContext.IN_GAME, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, CATEGORY);
    private static final KeyMapping GUARD = key("guard", GLFW.GLFW_KEY_E);
    private static final KeyMapping STYLE = key("style", GLFW.GLFW_KEY_V);
    private static final KeyMapping DODGE = key("dodge", GLFW.GLFW_KEY_LEFT_ALT);
    private static final KeyMapping EDITOR = key("editor", GLFW.GLFW_KEY_K);
    private static final KeyMapping JOURNAL = key("journal", GLFW.GLFW_KEY_J);
    private static final KeyMapping SKILL = key("skill", GLFW.GLFW_KEY_Z);
    private static final KeyMapping TREE = key("skill_tree", GLFW.GLFW_KEY_P);
    private static boolean bowCharging, rapierCharging, bowAuto, dualTogether;
    private static boolean guarding;
    private static boolean heavyGuarding;
    private static long snapshotClientTick;
    private static int statusClock;
    private static int guardRetryClock;
    private static CameraType previousCamera;
    private ClientHooks() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.fsrpg." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }
    public static void register(IEventBus modBus) {
        modBus.addListener(ClientHooks::keys);
        modBus.addListener(ClientHooks::payloads);
        modBus.addListener(PlayerAnimations::layers);
        modBus.addListener(RpgHud::layers);
        NeoForge.EVENT_BUS.addListener(ClientHooks::tick);
        NeoForge.EVENT_BUS.addListener(PlayerAnimations::renderClock);
        NeoForge.EVENT_BUS.addListener(LegacyFirstPersonAnimations::onRenderHand);
        NeoForge.EVENT_BUS.addListener(ArcheryPresentation::tick);
        NeoForge.EVENT_BUS.addListener(ArcheryPresentation::fov);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed event)->{
            var effect=ClientState.object(ClientState.object(ClientState.snapshot(),"progression"),"effects");
            double factor=ClientState.number(effect,"miningTimeFactor",1);
            if(Minecraft.getInstance().player==event.getEntity()&&Double.isFinite(factor)&&factor>0)event.setNewSpeed((float)(event.getNewSpeed()/factor));
        });
        NeoForge.EVENT_BUS.addListener(CombatCamera::angles);
        NeoForge.EVENT_BUS.addListener(CombatCamera::movement);
        NeoForge.EVENT_BUS.addListener(ClientHooks::interaction);
        NeoForge.EVENT_BUS.addListener(ClientHooks::hud);
        NeoForge.EVENT_BUS.addListener(RpgHud::beforeLayer);
        NeoForge.EVENT_BUS.addListener(ClientHooks::logout);
        NeoForge.EVENT_BUS.addListener(ClientHooks::screenOpening);
    }
    private static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (KeyMapping key : new KeyMapping[]{DRAW, LEFT, RIGHT, GUARD, DODGE, EDITOR, JOURNAL, SKILL, STYLE, TREE}) event.register(key);
    }
    private static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(RpgSnapshotPayload.TYPE, (payload, context) -> acceptSnapshot(payload.json()));
        event.register(RpgAnimationPayload.TYPE, (payload, context) -> {
            PlayerAnimations.accept(payload);
            var player = Minecraft.getInstance().player;
            if (player != null && player.getUUID().equals(payload.entityId())) {
                // Visual phase packets are server authoritative and arrive immediately at transitions.
                // Keep local controls/camera responsive before the less frequent complete journal snapshot.
                var state = ClientState.snapshot(); state.addProperty("drawn", payload.drawn());
                state.addProperty("phase", payload.phase()); state.addProperty("phaseTicksRemaining", payload.phaseRemaining());
                updateCamera();
            }
        });
    }
    public static void acceptSnapshot(String json) {
        try {
            ClientState.accept(json);
            var game = Minecraft.getInstance();
            snapshotClientTick = game.level == null ? 0 : game.level.getGameTime();
            updateCamera();
            if (Minecraft.getInstance().screen instanceof SkillEditorScreen editor) editor.acceptSnapshot();
            if (Minecraft.getInstance().screen instanceof QuestJournalScreen journal) journal.acceptSnapshot();
            if (Minecraft.getInstance().screen instanceof StyleSelectionScreen styles) styles.acceptSnapshot();
            if (Minecraft.getInstance().screen instanceof SkillTreeScreen tree) tree.acceptSnapshot();
        } catch (RuntimeException ex) {
            LOG.warn("Ignored invalid RPG presentation snapshot", ex);
        }
    }
    public static void send(String action, String argument) {
        if (action.equals("attack") || action.equals("aimAttack") || action.equals("dodge") || action.equals("draw") || action.equals("guard") || action.equals("guardMode"))
            PlayerAnimations.interruptInspection();
        if (Minecraft.getInstance().getConnection() != null)
            ClientPacketDistributor.sendToServer(new RpgActionPayload(action, argument));
    }
    public static void openEditor() {
        send("status", ""); Minecraft.getInstance().setScreen(new SkillEditorScreen());
    }
    public static void openJournal() {
        send("status", ""); Minecraft.getInstance().setScreen(new QuestJournalScreen());
    }
    public static void openStyleSelector() {
        send("status", ""); Minecraft.getInstance().setScreen(new StyleSelectionScreen());
    }
    public static void openSkillTree() { send("status", ""); Minecraft.getInstance().setScreen(new SkillTreeScreen()); }
    private static void tick(ClientTickEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.getConnection() == null) return;
        if (++statusClock >= 20) { statusClock = 0; send("status", ""); }
        boolean inGame = game.screen == null;
        boolean bow = CombatCamera.style().equals("bow");
        boolean rapier = CombatCamera.style().equals("rapier") && ClientState.bool(ClientState.snapshot(),"legacySkills",false);
        boolean shouldGuard = inGame && ClientState.drawn() && !bow && GUARD.isDown();
        boolean shouldHeavy = shouldGuard && game.options.keyShift.isDown();
        String guardArgument = !shouldGuard ? "false" : shouldHeavy ? "heavy" : "true";
        if (shouldGuard != guarding || shouldHeavy != heavyGuarding) {
            guarding = shouldGuard; heavyGuarding = shouldHeavy; guardRetryClock = 0; send("guard", guardArgument);
        }
        else if (shouldGuard && java.util.Set.of("READY","WINDUP","RECOVERY").contains(ClientState.text(ClientState.snapshot(), "phase", ""))) {
            if (++guardRetryClock >= 4) { guardRetryClock = 0; send("guard", guardArgument); }
        } else guardRetryClock = 0;
        boolean nextBow=inGame && ClientState.drawn() && bow && RIGHT.isDown();
        if(nextBow!=bowCharging) {bowCharging=nextBow;if(!nextBow&&(!inGame||!bow||!ClientState.drawn()))send("archeryCancel","");else send("archeryCharge",Boolean.toString(nextBow));}
        boolean nextAuto=inGame && ClientState.drawn() && bow && LEFT.isDown();
        if(nextAuto!=bowAuto) {bowAuto=nextAuto;send("archeryAuto",Boolean.toString(nextAuto));}
        if(rapierCharging && (!inGame || !rapier || !ClientState.drawn() || !RIGHT.isDown())) {rapierCharging=false;if(!inGame||!rapier||!ClientState.drawn())send("rapierCancel","");else send("rapierCharge","false");}
        if (!inGame) { CombatCamera.tick(false); return; }
        while (DRAW.consumeClick()) send("draw", "");
        boolean wasGesture=CombatCamera.gestureHeld();
        boolean heldMouse=LEFT.isDown() || RIGHT.isDown();
        CombatCamera.tick(heldMouse);
        // Dispatch press events only: holding either dual-blade button never repeats a cut.
        boolean together=CombatCamera.style().equals("dual_blade") && LEFT.isDown() && RIGHT.isDown();
        if(together&&!dualTogether) send("attack","dual_combined");
        dualTogether=together;
        while (LEFT.consumeClick()) if (!together && ClientState.drawn() && (!CombatCamera.active() || !CombatCamera.style().equals("spear") || !wasGesture && !heldMouse)) {
            if(bow) send("archeryBash",""); else CombatCamera.attack("left");
        }
        while (RIGHT.consumeClick()) if (!together && !bow && ClientState.drawn() && (!CombatCamera.active() || !CombatCamera.style().equals("spear") || !wasGesture && !heldMouse)) {
            if(rapier) {send("rapierCharge","true");rapierCharging=RIGHT.isDown();if(!rapierCharging)send("rapierCharge","false");}
            else CombatCamera.attack("right");
        }
        while (DODGE.consumeClick()) send("dodge", "");
        while (SKILL.consumeClick()) send("attack", "skill");
        while (STYLE.consumeClick()) openStyleSelector();
        while (EDITOR.consumeClick()) openEditor();
        while (JOURNAL.consumeClick()) openJournal();
        while (TREE.consumeClick()) openSkillTree();
    }
    private static void screenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof InventoryScreen && ClientState.drawn() && GUARD.isDown()) event.setCanceled(true);
    }
    private static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft game = Minecraft.getInstance();
        if (game.screen != null || game.player == null || !ClientState.drawn()) return;
        if (event.isAttack() || event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
    private static void updateCamera() {
        Minecraft game = Minecraft.getInstance();
        if (ClientState.drawn() && ClientOptions.THIRD_PERSON.get()) {
            if (previousCamera == null) { previousCamera = game.options.getCameraType(); game.options.setCameraType(CameraType.THIRD_PERSON_BACK); }
        } else if (previousCamera != null) {
            game.options.setCameraType(previousCamera); previousCamera = null;
        }
    }
    private static void hud(RenderGuiEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.level == null || game.player.isSpectator()
                || game.getCameraEntity() != game.player || game.options.hideGui) return;
        GuiGraphics gui = event.getGuiGraphics();
        if (game.screen == null && ClientState.drawn()) CombatCamera.hud(gui);
        var state = ClientState.snapshot();
        String phase = ClientState.text(state, "phase", "IDLE");
        String phaseName = switch (phase.toUpperCase(java.util.Locale.ROOT)) {
            case "WINDUP" -> "前摇"; case "ACTIVE" -> "判定"; case "RECOVERY" -> "后摇";
            case "GUARD", "GUARDING" -> ClientState.text(state, "guardMode", "LIGHT").equals("HEAVY") ? "重格挡" : "轻格挡";
            case "DODGE", "DODGING" -> "闪避";
            case "DRAWING" -> "拔刀"; case "SHEATHING" -> "收刀";
            case "STUNNED" -> "硬直"; case "SHEATHED" -> "收刀";
            default -> ClientState.drawn() ? "就绪" : "探索";
        };
        String style = ClientState.text(state, "weaponStyle", "rapier");
        for (var definition : ClientState.objects(state, "styles")) if (ClientState.text(definition, "id", "").equals(style)) style = ClientState.text(definition, "label", style);
        boolean equipped = ClientState.bool(state, "weaponEquipped", ClientState.drawn());
        String line = equipped ? style + " · " + phaseName : "探索";
        if (equipped && ClientState.number(state, "drawStateCount", 0) > 1) line += " · R 切换姿态";
        if (!ClientOptions.RPG_HUD.get()) line += "  体力 " + Math.round(ClientState.number(state, "stamina", 0))
                + "  灵力 " + Math.round(ClientState.number(state, "spirit", 0));
        gui.fill(6, 6, Math.min(gui.guiWidth() - 6, 15 + game.font.width(line)), 22, 0xb815202e);
        gui.drawString(game.font, line, 10, 10, 0xffe4edff);
        var openingValue = state.get("technique");
        if (equipped && ClientState.drawn() && openingValue != null && openingValue.isJsonObject()) {
            var opening = openingValue.getAsJsonObject();
            long elapsed = game.level == null ? 0 : Math.max(0, game.level.getGameTime() - snapshotClientTick);
            double remaining = ClientState.number(opening, "remainingTicks", 0) - elapsed;
            if (remaining > 0) {
                String hint = ClientState.text(opening, "source", "").equals("RIPOSTE") ? "拨挡还击" : "闪步回刺";
                String info = "右键 · " + hint + "  " + String.format(java.util.Locale.ROOT, "%.1fs", remaining / 20);
                gui.fill(6, 44, Math.min(gui.guiWidth() - 6, 15 + game.font.width(info)), 60, 0xc0173439);
                gui.drawString(game.font, info, 10, 48, 0xff9ff1d3);
            }
        }
        var comboValue = state.get("combo");
        if (equipped && ClientState.drawn() && comboValue != null && comboValue.isJsonObject()) {
            var combo = comboValue.getAsJsonObject(); int stage = (int) ClientState.number(combo, "stage", 0);
            if (stage > 0) {
                boolean queued = ClientState.bool(combo, "queued", false);
                String info = "连击 " + stage + "/" + (int) ClientState.number(combo, "totalStages", 5)
                        + (queued ? " · 下一招已缓存" : " · 可预输入下一招，后摇衔接");
                gui.fill(6, 25, Math.min(gui.guiWidth() - 6, 15 + game.font.width(info)), 41, 0xb815202e);
                gui.drawString(game.font, info, 10, 29, queued ? 0xff91e0ba : 0xffffd486);
            }
        }
    }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ArcheryPresentation.reset();
        CombatCamera.reset(); ClientState.clear(); PlayerAnimations.clear(); updateCamera(); guarding = heavyGuarding = bowCharging = rapierCharging = bowAuto = dualTogether = false; statusClock = 0; guardRetryClock = 0;
    }
}

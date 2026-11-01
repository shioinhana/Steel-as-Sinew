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
import org.fsrpg.network.RpgTargetLockPayload;
import org.fsrpg.network.RpgCreatureAttackPayload;
import org.fsrpg.network.RpgGuardCuePayload;
import org.fsrpg.network.RpgBurstDashPayload;
import org.fsrpg.network.RpgFootworkMotionPayload;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientHooks {
    private static final Logger LOG = LoggerFactory.getLogger("FSRPG client");
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("fsrpg", "combat"));
    private static final KeyMapping DRAW = key("draw", GLFW.GLFW_KEY_R);
    private static final KeyMapping LEFT = new KeyMapping("key.fsrpg.left", KeyConflictContext.IN_GAME, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);
    private static final KeyMapping RIGHT = new KeyMapping("key.fsrpg.right", KeyConflictContext.IN_GAME, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, CATEGORY);
    private static final KeyMapping GUARD = key("guard", GLFW.GLFW_KEY_C);
    private static final KeyMapping PARRY = key("parry", GLFW.GLFW_KEY_X);
    private static final KeyMapping STYLE = key("style", GLFW.GLFW_KEY_V);
    private static final KeyMapping DODGE = key("dodge", GLFW.GLFW_KEY_LEFT_ALT);
    private static final KeyMapping EDITOR = key("editor", GLFW.GLFW_KEY_K);
    private static final KeyMapping JOURNAL = key("journal", GLFW.GLFW_KEY_J);
    private static final KeyMapping SKILL = key("skill", GLFW.GLFW_KEY_Z);
    private static final KeyMapping ART = key("art_gallery", GLFW.GLFW_KEY_F8);
    private static final KeyMapping TREE = key("skill_tree", GLFW.GLFW_KEY_P);
    private static final KeyMapping FOOTWORK = key("footwork", GLFW.GLFW_KEY_B);
    private static final KeyMapping FOOTWORK_SPECIAL = key("footwork_special", GLFW.GLFW_KEY_G);
    private static boolean bowCharging, rapierCharging, bowAuto, dualTogether;
    private static final GuardKeyIntent guardKeyIntent=new GuardKeyIntent();
    private static final ShiftBurstIntent shiftBurstIntent=new ShiftBurstIntent();
    private static net.minecraft.client.multiplayer.ClientLevel burstInputLevel;
    private static net.minecraft.client.player.LocalPlayer burstInputPlayer;
    private static InputConstants.Key sampledGuardBinding;
    private static final int[] rawKeyboardKeys=java.util.Arrays.stream(GLFW.class.getFields())
            .filter(field->field.getName().startsWith("GLFW_KEY_")&&!field.getName().equals("GLFW_KEY_UNKNOWN"))
            .mapToInt(field->{try{return field.getInt(null);}catch(IllegalAccessException impossible){throw new IllegalStateException(impossible);}})
            .distinct().sorted().toArray();
    private static final java.util.Map<Integer,int[]> physicalScanKeys=new java.util.HashMap<>();
    private static long snapshotClientTick;
    private static int statusClock;
    private ClientHooks() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.fsrpg." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }
    static String parryKeyLabel(){return PARRY.getTranslatedKeyMessage().getString();}
    static String guardKeyLabel(){return GUARD.getTranslatedKeyMessage().getString();}
    /** Query native state because GUI releaseAll() and synthetic KeyMapping.setDown() are not physical edges. */
    private static boolean physicalGuardDown(Minecraft game) {
        var binding=GUARD.getKey();
        if(sampledGuardBinding!=null&&!sampledGuardBinding.equals(binding)) {
            if(guardKeyIntent.ownsInput())send("guard","false");
            guardKeyIntent.reset();
        }
        sampledGuardBinding=binding;
        return physicalBindingDown(binding,game.getWindow().handle());
    }
    private static boolean physicalBindingDown(InputConstants.Key binding,long window) {
        int value=binding.getValue();
        if(binding.getType()==InputConstants.Type.MOUSE)
            return value>=0&&value<=GLFW.GLFW_MOUSE_BUTTON_LAST&&GLFW.glfwGetMouseButton(window,value)==GLFW.GLFW_PRESS;
        if(binding.getType()==InputConstants.Type.KEYSYM)
            return value>=GLFW.GLFW_KEY_SPACE&&value<=GLFW.GLFW_KEY_LAST&&GLFW.glfwGetKey(window,value)==GLFW.GLFW_PRESS;
        if(binding.getType()==InputConstants.Type.SCANCODE) {
            int[] keys=physicalScanKeys.computeIfAbsent(value,scan->java.util.Arrays.stream(rawKeyboardKeys)
                    .filter(key->GLFW.glfwGetKeyScancode(key)==scan).toArray());
            for(int key:keys)if(GLFW.glfwGetKey(window,key)==GLFW.GLFW_PRESS)return true;
        }
        return false;
    }
    public static void register(IEventBus modBus) {
        modBus.addListener(CreatureArtRenderer::register);
        modBus.addListener(ClientHooks::keys);
        modBus.addListener(ClientHooks::payloads);
        modBus.addListener(PlayerAnimations::layers);
        modBus.addListener(RpgHud::layers);
        NeoForge.EVENT_BUS.addListener(ClientHooks::tick);
        NeoForge.EVENT_BUS.addListener(GuardCuePresentation::tick);
        NeoForge.EVENT_BUS.addListener(GuardCuePresentation::render);
        NeoForge.EVENT_BUS.addListener(PlayerAnimations::renderClock);
        NeoForge.EVENT_BUS.addListener(LegacyFirstPersonAnimations::onRenderHand);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, ImportedItemPresentation::onRenderHand);
        NeoForge.EVENT_BUS.addListener(ImportedItemPresentation::tick);
        NeoForge.EVENT_BUS.addListener(ArcheryPresentation::tick);
        NeoForge.EVENT_BUS.addListener(ArcheryPresentation::fov);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed event)->{
            var effect=ClientState.object(ClientState.object(ClientState.snapshot(),"progression"),"effects");
            double factor=ClientState.number(effect,"miningTimeFactor",1);
            if(Minecraft.getInstance().player==event.getEntity()&&Double.isFinite(factor)&&factor>0)event.setNewSpeed((float)(event.getNewSpeed()/factor));
        });
        NeoForge.EVENT_BUS.addListener(CombatCamera::angles);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, CameraAdjustment::beforeKeybinds);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, CameraAdjustment::movement);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent event) -> CameraAdjustment.shutdown());
        NeoForge.EVENT_BUS.addListener(CombatCamera::movement);
        NeoForge.EVENT_BUS.addListener(ClientHooks::burstSprintInput);
        NeoForge.EVENT_BUS.addListener(ClientHooks::footworkInput);
        NeoForge.EVENT_BUS.addListener(ClientHooks::interaction);
        NeoForge.EVENT_BUS.addListener(ClientHooks::hud);
        NeoForge.EVENT_BUS.addListener(ShoulderOcclusionPresentation::extract);
        NeoForge.EVENT_BUS.addListener(ShoulderOcclusionPresentation::render);
        NeoForge.EVENT_BUS.addListener(RpgHud::beforeLayer);
        NeoForge.EVENT_BUS.addListener(ClientHooks::logout);
        NeoForge.EVENT_BUS.addListener(ClientHooks::screenOpening);
    }
    private static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (KeyMapping key : new KeyMapping[]{DRAW, LEFT, RIGHT, GUARD, PARRY, DODGE, EDITOR, JOURNAL, SKILL, STYLE, TREE, ART, FOOTWORK, FOOTWORK_SPECIAL}) event.register(key);
        CameraAdjustment.registerKeys(event, CATEGORY);
    }
    private static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(org.fsrpg.network.RpgSwordMotionPayload.TYPE,(payload,context)->SwordMotionClient.accept(payload));
        event.register(org.fsrpg.network.RpgGuardImpactPayload.TYPE,(payload,context)->GuardImpactPresentation.accept(payload));
        event.register(RpgBurstDashPayload.TYPE,(payload,context)->BurstDashMotion.accept(payload));
        event.register(RpgGuardCuePayload.TYPE, (payload, context) -> {
            try { GuardCuePresentation.accept(payload); }
            catch (RuntimeException error) { LOG.warn("Ignored invalid guard cue update", error); }
        });
        event.register(RpgCreatureAttackPayload.TYPE,(payload,context)->{
            try {CreatureCombatAnimation.accept(payload);}
            catch(RuntimeException error){LOG.warn("Ignored invalid creature combat animation update",error);}
        });
        event.register(RpgTargetLockPayload.TYPE,(payload,context)->{
            try {ClientState.acceptTargetLock(payload.json());}
            catch(RuntimeException error){LOG.warn("Ignored invalid target lock update",error);}
        });
        event.register(org.fsrpg.network.RpgOpenGuildPayload.TYPE, (payload, context) -> { if(payload.open()) Minecraft.getInstance().setScreen(new GuildQuestScreen()); });
        event.register(RpgSnapshotPayload.TYPE, (payload, context) -> acceptSnapshot(payload.json()));
        event.register(RpgFootworkMotionPayload.TYPE, (payload, context) -> FootworkClient.accept(payload));
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
        var game=Minecraft.getInstance();
        if(action.equals("burstDash")) {
            argument=BurstDashMotion.prepareIntent(argument);
            if(game.player!=null&&game.getConnection()!=null)game.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                    game.player.getYRot(),game.player.getXRot(),game.player.onGround(),game.player.horizontalCollision));
        }
        boolean rapier=ClientState.drawn()&&CombatCamera.style().equals("rapier");
        boolean orbitAttack=ClientState.drawn()&&!CombatCamera.style().equals("spear")
                &&(action.equals("attack")||action.equals("aimAttack")||action.equals("targetAttack")||action.equals("rapierCharge")&&argument.equals("true"));
        if(orbitAttack) {
            CombatCamera.prepareRapierAttack();
            // This handler runs after the usual player movement send. Keep a no-lock
            // orbit-and-click in order: vanilla rotation first, then combat intent.
            if(game.player!=null&&game.getConnection()!=null&&CombatCamera.lockedTarget()==null)
                game.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                        game.player.getYRot(),game.player.getXRot(),game.player.onGround(),game.player.horizontalCollision));
        }
        if(rapier&&action.equals("dodge")&&argument.isBlank())argument=CombatCamera.dodgeIntent();
        if(action.startsWith("spear")&&game.player!=null&&game.getConnection()!=null)
            game.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                    game.player.getYRot(),game.player.getXRot(),game.player.onGround(),game.player.horizontalCollision));
        if (action.equals("attack") || action.equals("parry") || action.equals("aimAttack") || action.equals("targetAttack") || action.startsWith("spear") || action.equals("burstDash") || action.equals("dodge") || action.equals("draw") || action.equals("guard") || action.equals("guardMode"))
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
        CameraAdjustment.tick();
        if (game.player == null || game.getConnection() == null) return;
        if(burstInputLevel!=game.level||burstInputPlayer!=game.player){
            shiftBurstIntent.reset();BurstDashMotion.reset();SwordMotionClient.reset();shiftSprintRequested=false;
            burstInputLevel=game.level;burstInputPlayer=game.player;
        }
        updateCamera();
        if (++statusClock >= 20) { statusClock = 0; send("status", ""); }
        boolean inGame = game.screen == null;
        boolean bow = CombatCamera.style().equals("bow");
        boolean rapier = CombatCamera.style().equals("rapier") && ClientState.bool(ClientState.snapshot(),"legacySkills",false);
        boolean canGuard=inGame&&ClientState.drawn()&&!bow;
        boolean physicalGuard=physicalGuardDown(game);
        boolean shouldHeavy=physicalBindingDown(game.options.keyShift.getKey(),game.getWindow().handle());
        boolean retryGuard=ClientState.number(ClientState.snapshot(),"attackDamageMultiplier",1)<=1
                &&java.util.Set.of("READY","WINDUP","RECOVERY").contains(ClientState.text(ClientState.snapshot(),"phase",""));
        String guardArgument=guardKeyIntent.sample(physicalGuard,canGuard,shouldHeavy,retryGuard);
        if(guardArgument!=null)send("guard",guardArgument);
        var burstDirection=CombatCamera.burstDirection();
        if(!inGame||!game.isWindowActive()||!ClientState.drawn()||physicalGuard)BurstDashMotion.cancelPending();
        String burstArgument=shiftBurstIntent.sample(shouldHeavy,inGame&&game.isWindowActive()&&ClientState.drawn(),physicalGuard,game.player.onGround(),
                game.level==null?0:game.level.getGameTime(),burstDirection.x,burstDirection.z);
        if(burstArgument!=null)send("burstDash",burstArgument);
        boolean nextBow=inGame && ClientState.drawn() && bow && RIGHT.isDown();
        if(nextBow!=bowCharging) {bowCharging=nextBow;if(!nextBow&&(!inGame||!bow||!ClientState.drawn()))send("archeryCancel","");else if(nextBow)CombatCamera.targetAttack("right");else send("archeryCharge","false");}
        boolean nextAuto=inGame && ClientState.drawn() && bow && LEFT.isDown();
        if(nextAuto!=bowAuto) {bowAuto=nextAuto;send("archeryAuto",Boolean.toString(nextAuto));}
        if(rapierCharging && (!inGame || !rapier || !ClientState.drawn() || !RIGHT.isDown())) {rapierCharging=false;if(!inGame||!rapier||!ClientState.drawn())send("rapierCancel","");else send("rapierCharge","false");}
        if (!inGame) { CombatCamera.tick(false); return; }
        while (DRAW.consumeClick()) CombatCamera.drawKey();
        boolean wasGesture=CombatCamera.gestureHeld();
        boolean heldMouse=LEFT.isDown() || RIGHT.isDown();
        CombatCamera.tick(heldMouse);
        // Dispatch press events only: holding either dual-blade button never repeats a cut.
        boolean together=CombatCamera.style().equals("dual_blade") && LEFT.isDown() && RIGHT.isDown();
        if(together&&!dualTogether) send("attack","dual_combined");
        dualTogether=together;
        while (LEFT.consumeClick()) if (!together && ClientState.drawn() && (!CombatCamera.active() || !CombatCamera.style().equals("spear") || !wasGesture && !heldMouse)) {
            if(bow) CombatCamera.targetAttack("left"); else CombatCamera.attack("left");
        }
        while (RIGHT.consumeClick()) if (!together && !bow && ClientState.drawn() && (!CombatCamera.active() || !CombatCamera.style().equals("spear") || !wasGesture && !heldMouse)) {
            if(rapier) {CombatCamera.targetAttack("right");rapierCharging=RIGHT.isDown();if(!rapierCharging)send("rapierCharge","false");}
            else CombatCamera.attack("right");
        }
        while (DODGE.consumeClick()) send("dodge", "");
        while (PARRY.consumeClick()) if(ClientState.drawn()&&rapier)send("parry", "");
        while (SKILL.consumeClick()) {if(ClientState.drawn()&&!CombatCamera.style().equals("spear"))CombatCamera.targetAttack("skill");else send("attack", "skill");}
        while (STYLE.consumeClick()) openStyleSelector();
        while (EDITOR.consumeClick()) if(!CameraAdjustment.consumes(EDITOR))openEditor();
        while (JOURNAL.consumeClick()) if(!CameraAdjustment.consumes(JOURNAL))openJournal();
        while (TREE.consumeClick()) openSkillTree();
        while (ART.consumeClick()) game.setScreen(new ArtGalleryScreen());
    }
    private static void screenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof InventoryScreen && ClientState.drawn() && GUARD.isDown()
                && GUARD.getKey().equals(Minecraft.getInstance().options.keyInventory.getKey())) event.setCanceled(true);
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
        CombatCamera.updateView();
    }
    private static void hud(RenderGuiEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.level == null || game.player.isSpectator()
                || game.getCameraEntity() != game.player || game.options.hideGui) return;
        GuiGraphics gui = event.getGuiGraphics();
        if (game.screen == null && CombatCamera.active()) CombatCamera.hud(gui);
        CameraAdjustment.hud(gui);
        ShoulderOcclusionPresentation.hud(gui);
        var state = ClientState.snapshot();
        String phase = ClientState.text(state, "phase", "IDLE");
        String phaseName = switch (phase.toUpperCase(java.util.Locale.ROOT)) {
            case "WINDUP" -> "前摇"; case "ACTIVE" -> "判定"; case "RECOVERY" -> "后摇";
            case "GUARD", "GUARDING" -> ClientState.text(state, "guardMode", "LIGHT").equals("HEAVY") ? "重格挡" : "轻格挡";
            case "DODGE", "DODGING" -> "闪避";
            case "PARRYING" -> "振刀";
            case "DRAWING" -> "拔刀"; case "SHEATHING" -> "收刀";
            case "STUNNED" -> "硬直"; case "SHEATHED" -> "收刀";
            default -> ClientState.drawn() ? "就绪" : "探索";
        };
        String style = ClientState.text(state, "weaponStyle", "rapier");
        for (var definition : ClientState.objects(state, "styles")) if (ClientState.text(definition, "id", "").equals(style)) style = ClientState.text(definition, "label", style);
        boolean equipped = ClientState.bool(state, "weaponEquipped", ClientState.drawn());
        String line = equipped ? style + " · " + phaseName : "探索";
        if(equipped&&ClientState.drawn()&&ClientState.bool(state,"swordCombatEnabled",false))
            line += " · "+guardKeyLabel()+" 格挡 / "+parryKeyLabel()+" 振刀";
        if (equipped && ClientState.number(state, "drawStateCount", 0) > 1) line += " · V 选择姿态";
        if (!ClientOptions.RPG_HUD.get()) line += "  体力 " + Math.round(ClientState.number(state, "stamina", 0))
                + "  灵力 " + Math.round(ClientState.number(state, "spirit", 0));
        gui.fill(6, 6, Math.min(gui.guiWidth() - 6, 15 + game.font.width(line)), 22, 0xb815202e);
        gui.drawString(game.font, line, 10, 10, 0xffe4edff);
        long counterElapsed = game.level == null ? 0 : Math.max(0, game.level.getGameTime() - snapshotClientTick);
        double counterRemaining = ClientState.number(state, "counterRemainingTicks", 0) - counterElapsed;
        double swordReturn=ClientState.number(state,"swordReturnRemainingTicks",0)-counterElapsed;
        if(equipped&&ClientState.drawn()&&swordReturn>0){
            String info="左键 · 振刀还击  "+String.format(java.util.Locale.ROOT,"%.1fs",swordReturn/20);
            gui.fill(6,44,Math.min(gui.guiWidth()-6,15+game.font.width(info)),60,0xc0173439);
            gui.drawString(game.font,info,10,48,0xff8ad9ff);
        }
        if (equipped && ClientState.drawn() && counterRemaining > 0 && swordReturn<=0) {
            String info = "左键 · 完美格挡反击 ×1.5  " + String.format(java.util.Locale.ROOT, "%.1fs", counterRemaining / 20);
            gui.fill(6, 44, Math.min(gui.guiWidth() - 6, 15 + game.font.width(info)), 60, 0xc0173439);
            gui.drawString(game.font, info, 10, 48, 0xff9ff1d3);
        }
        var openingValue = state.get("technique");
        if (equipped && ClientState.drawn() && counterRemaining <= 0 && swordReturn<=0 && openingValue != null && openingValue.isJsonObject()) {
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
        var footworkView = ClientState.object(ClientState.object(state, "progression"), "footwork");
        if (ClientState.bool(footworkView, "enabled", false)) {
            var footworkMotion = ClientState.object(state, "footworkMotion");
            String footworkStyle = ClientState.text(footworkView, "style", "NONE");
            int cloud = (int) ClientState.number(footworkView, "cloudTier", 0);
            int light = (int) ClientState.number(footworkView, "lightTier", 0);
            int earnedSelections = (int) ClientState.number(footworkView, "selectionsEarned", 0);
            int availableSelections = (int) ClientState.number(footworkView, "selectionsAvailable", 0);
            if (!footworkStyle.equals("NONE") || cloud > 0 || light > 0 || earnedSelections > 0) {
                long elapsed = game.level == null ? 0 : Math.max(0, game.level.getGameTime() - snapshotClientTick);
                int jumpsUsed = (int) ClientState.number(footworkMotion, "jumpsUsed", 0);
                long cooldown = (long) (footworkStyle.equals("RABBIT") ? ClientState.number(footworkMotion, "rabbitCooldownTicks", 0)
                        : footworkStyle.equals("FLYING_SWAN") ? ClientState.number(footworkMotion, "swanCooldownTicks", 0) : 0);
                cooldown = Math.max(0, cooldown - elapsed);
                String info = "步法 " + switch (footworkStyle) {
                    case "RABBIT" -> "脱兔";
                    case "FLYING_SWAN" -> "飞鸿";
                    default -> "未选流派";
                };
                if (cooldown > 0) info += " · 冷却 " + String.format(java.util.Locale.ROOT, "%.1fs", cooldown / 20.0);
                long specialCooldown = Math.max(0, (long) ClientState.number(footworkMotion, "specialCooldownTicks", 0) - elapsed);
                if (specialCooldown > 0) info += " · 特技冷却 " + String.format(java.util.Locale.ROOT, "%.1fs", specialCooldown / 20.0);
                if (cloud > 0) info += " · 空中跳 " + Math.max(0, cloud - jumpsUsed) + "/" + cloud;
                if (light > 0) info += " · 若轻" + "一二三".charAt(Math.min(3, light) - 1);
                if (light >= 2) info += " · 摔落免疫";
                if (earnedSelections > 0) info += " · 选学 " + availableSelections + "/" + earnedSelections;
                gui.fill(6, 63, Math.min(gui.guiWidth() - 6, 15 + game.font.width(info)), 79, 0xb815202e);
                gui.drawString(game.font, info, 10, 67, 0xffc7e5ff);
            }
        }
    }
    private static boolean shiftSprintRequested;
    /** B = school trigger; G = airborne stomp / wall kick and charged-jump modifier. */
    private static void footworkInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        FootworkClient.input(event, FOOTWORK.isDown(), FOOTWORK_SPECIAL.isDown());
    }
    /** Offer the ordinary sprint key to vanilla; WASD, hunger, collisions and vanilla travel still decide motion. */
    private static void burstSprintInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        var game=Minecraft.getInstance();if(event.getEntity()!=game.player||game.player==null)return;
        boolean wanted=game.screen==null&&game.isWindowActive()&&ClientState.drawn()&&shiftBurstIntent.ownsInput()
                &&BurstDashMotion.approved(shiftBurstIntent.requestId())&&!physicalGuardDown(game)
                &&physicalBindingDown(game.options.keyShift.getKey(),game.getWindow().handle())
                &&!java.util.Set.of("GUARDING","STUNNED","DODGING","DRAWING","SHEATHING").contains(ClientState.text(ClientState.snapshot(),"phase",""));
        if(wanted){var input=event.getInput();var keys=input.keyPresses;
            input.keyPresses=new net.minecraft.world.entity.player.Input(keys.forward(),keys.backward(),keys.left(),keys.right(),keys.jump(),false,true);
        } else if(shiftSprintRequested)game.player.setSprinting(false);
        shiftSprintRequested=wanted;
    }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        CameraAdjustment.reset();
        ShoulderOcclusionPresentation.clear();
        ImportedItemPresentation.reset();
        BurstDashMotion.reset();
        SwordMotionClient.reset();
        GuardCuePresentation.clear();
        GuardImpactPresentation.reset();
        CreatureCombatAnimation.clear();
        ArcheryPresentation.reset();
        FootworkClient.reset();
        CombatCamera.reset(); ClientState.clear(); PlayerAnimations.clear(); updateCamera(); bowCharging = rapierCharging = bowAuto = dualTogether = false; statusClock = 0;
        guardKeyIntent.reset();sampledGuardBinding=null;physicalScanKeys.clear();
        shiftBurstIntent.reset();
        shiftSprintRequested=false;
        burstInputLevel=null;burstInputPlayer=null;
    }
}

package com.actioncombat.client;

import com.actioncombat.ActionCombat;
import com.actioncombat.CombatConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

@EventBusSubscriber(modid = ActionCombat.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private static final FpViewmodelRenderer FP_VIEWMODEL = new FpViewmodelRenderer();

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ModKeybinds.DRAW);
        event.register(ModKeybinds.BLOCK);
        event.register(ModKeybinds.INSPECT);
        event.register(ModKeybinds.CROUCH);
    }

    @SubscribeEvent
    static void onClientTickPost(ClientTickEvent.Post event) {
        while (ModKeybinds.DRAW.consumeClick()) {
            ClientCombat.onDrawKey();
        }
        while (ModKeybinds.INSPECT.consumeClick()) {
            ClientCombat.onInspectKey();
        }
        ClientCombat.clientTick();
    }

    @SubscribeEvent
    static void onMovementInput(MovementInputUpdateEvent event) {
        if (event.getEntity() instanceof LocalPlayer) {
            ClientCombat.onMovementInput(event.getInput());
        }
    }

    @SubscribeEvent
    static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (event.getEntity() instanceof LocalPlayer player && ClientCombat.isLocalDodging()) {
            player.xxa = 0.0F;
            player.zza = 0.0F;
            player.setShiftKeyDown(false);
        }
    }

    /**
     * While drawn both mouse buttons are swings, so the vanilla interaction is
     * cancelled for each. While sheathed nothing is intercepted and attacking,
     * mining and item use stay exactly vanilla.
     */
    @SubscribeEvent
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack()) {
            if (ClientCombat.onAttackPress()) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        } else if (event.isUseItem()) {
            if (ClientCombat.onUsePress()) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }
    }

    /** Real-time clock for the per-frame camera/orientation easing. */
    private static long lastFovNanos;

    @SubscribeEvent
    static void onComputeFov(ViewportEvent.ComputeFov event) {
        long now = System.nanoTime();
        float deltaSeconds = lastFovNanos == 0L
                ? 1.0F / 60.0F
                : Math.min((now - lastFovNanos) / 1.0E9F, 0.1F);
        lastFovNanos = now;

        ClientCombat.updateCameraFrame(deltaSeconds);
        ClientCombat.renderTickSync();

        event.setFOV(event.getFOV() + ClientCombat.currentFovBias());

        float punch = FovPunch.envelope();
        if (punch > 0.0F) {
            event.setFOV(event.getFOV() + CombatConstants.FOV_PUNCH_DEGREES * punch);
        }
    }

    /** Impact shake, layered on top of whatever the camera director produced. */
    @SubscribeEvent
    static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float yaw = HitFeedback.shakeYaw();
        float pitch = HitFeedback.shakePitch();
        float roll = HitFeedback.shakeRoll();
        if (ClientCombat.isLocalDrawn()) {
            if (Minecraft.getInstance().options.getCameraType().isFirstPerson()
                    || ClientCombat.isAimLocked()) {
                event.setYaw(event.getYaw() + yaw);
                event.setPitch(event.getPitch() + pitch);
            } else {
                event.setYaw(CombatCamera.yaw() + yaw);
                event.setPitch(CombatCamera.pitch() + pitch);
            }
            event.setRoll(event.getRoll() + roll);
            return;
        }
        if (yaw == 0.0F && pitch == 0.0F && roll == 0.0F) return;
        event.setYaw(event.getYaw() + yaw);
        event.setPitch(event.getPitch() + pitch);
        event.setRoll(event.getRoll() + roll);
    }

    @SubscribeEvent
    static void onRenderCrosshair(RenderGuiLayerEvent.Pre event) {
        if (!event.getName().equals(VanillaGuiLayers.CROSSHAIR)) return;
        if (!ClientCombat.isLocalDrawn()) return;
        event.setCanceled(true);
    }

    /** Attach the back-weapon layer to every player skin variant. */
    @SubscribeEvent
    static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerModelType skin : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(skin);
            if (renderer != null) {
                renderer.addLayer(new BackWeaponLayer(renderer));
            }
        }
    }

    /**
     * Hide the vanilla player body while its combat dummy is showing, and while
     * sheathed move the sword out of the hand (BackWeaponLayer draws it on the back).
     * Clearing the item's render state is the only hook for this: the vanilla
     * ItemInHandLayer skips empty states, so nothing else has to be cancelled.
     */
    @SubscribeEvent
    static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        AvatarRenderState state = (AvatarRenderState) event.getRenderState();
        Minecraft mc = Minecraft.getInstance();
        if (ClientCombat.shouldRenderFirstPersonViewmodel() && mc.player != null
                && matches(mc.player, state, event.getPartialTick())) {
            event.setCanceled(true);
            return;
        }
        if (isDummyShowing(state, event.getPartialTick())) {
            event.setCanceled(true);
            return;
        }
        if (ClientCombat.shouldHideHandWeapon(state)) {
            state.rightHandItemState.clear();
            state.leftHandItemState.clear();
        }
    }

    /**
     * Hide the vanilla first-person arm while the geo viewmodel is showing instead.
     * This must also cover the sheath clip, or the vanilla arm pops back for the few
     * frames between leaving combat mode and put_away finishing.
     */
    @SubscribeEvent
    static void onRenderArm(RenderArmEvent event) {
        if ((ClientCombat.isLocalDrawn() || ClientCombat.isSheathing())
                && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            event.setCanceled(true);
        }
    }

    /**
     * First person in combat: drop vanilla arms and the held-item pass, then submit
     * the authored viewmodel once from the main-hand event.
     */
    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        if (!ClientCombat.shouldRenderFirstPersonViewmodel()) return;
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        FP_VIEWMODEL.submitViewmodel(
                event.getPoseStack(),
                event.getSubmitNodeCollector(),
                event.getPackedLight(),
                event.getPartialTick());
    }

    private static boolean isDummyShowing(AvatarRenderState state, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        for (int playerId : ClientCombat.dummyHiddenPlayerIds()) {
            if (mc.level.getEntity(playerId) instanceof Player p && matches(p, state, partialTick)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player a render state belongs to. Render states carry no entity reference, so
     * players are matched by their interpolated position — the same comparison the
     * dummy-hiding check uses.
     */
    static Player playerForRenderState(AvatarRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        for (Player p : mc.level.players()) {
            if (matches(p, state, partialTick)) return p;
        }
        return null;
    }

    /**
     * The render state stores the interpolated position (lerp(partialTick, xOld, x)),
     * not the entity's tick position, so compare against the same interpolation.
     */
    private static boolean matches(Player p, AvatarRenderState state, float partialTick) {
        return Math.abs(Mth.lerp(partialTick, p.xOld, p.getX()) - state.x) < 1.0E-4
                && Math.abs(Mth.lerp(partialTick, p.yOld, p.getY()) - state.y) < 1.0E-4
                && Math.abs(Mth.lerp(partialTick, p.zOld, p.getZ()) - state.z) < 1.0E-4;
    }

    private ClientEvents() {}
}

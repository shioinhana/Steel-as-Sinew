package com.actioncombat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a sheathed sword on the player's back.
 * <p>
 * Combat mode is explicit now, so a sheathed player is still holding the sword item in
 * their hand as far as vanilla is concerned. Showing it in the hand would contradict the
 * fiction ("the weapon lives on your back until you draw it"), so
 * {@link ClientEvents#onRenderPlayerPre} clears the hand item's render state while
 * sheathed and this layer puts the weapon on the back instead.
 * <p>
 * The transform hangs the blade off the torso bone, so it follows the body when the
 * player turns, leans or crouches rather than floating in place.
 */
public class BackWeaponLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** Reused so the layer allocates nothing per frame. */
    private final ItemStackRenderState itemState = new ItemStackRenderState();

    public BackWeaponLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       AvatarRenderState renderState, float yRot, float xRot) {
        ItemStack stack = ClientCombat.backWeaponFor(renderState);
        if (stack.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        itemState.clear();
        mc.getItemModelResolver().updateForTopItem(itemState, stack,
                ItemDisplayContext.FIXED, mc.level, null, 0);
        if (itemState.isEmpty()) return;

        poseStack.pushPose();
        // follow the torso so the sword moves with the body, not the world
        getParentModel().body.translateAndRotate(poseStack);

        // sit the blade across the upper back, tilted like a slung scabbard
        poseStack.translate(0.0F, 0.28F, 0.16F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-28.0F));
        poseStack.scale(0.85F, 0.85F, 0.85F);

        itemState.submit(poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY,
                renderState.outlineColor);
        poseStack.popPose();
    }
}

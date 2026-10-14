package org.fsrpg.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
/** Compatibility registration only: held items are submitted by the official ItemInHandLayer hook. */
final class RapierWeaponLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    RapierWeaponLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) { super(parent); }
    @Override public void submit(PoseStack poses, SubmitNodeCollector collector, int light,
                                 AvatarRenderState state, float yaw, float pitch) {
        // Never submit FS sword geometry or clear an ItemStackRenderState.
    }
}

package com.actioncombat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Either;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.model.BakedGeoModel;
import software.bernie.geckolib.cache.model.GeoBone;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.layer.builtin.BlockAndItemGeoLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Mounts the local player's real held swords onto the first-person viewmodel
 * in place of the authored blade meshes.
 * <p>
 * {@code elucidator} is already a sword-space bone (blade along +Y). The dual
 * {@code *_arm_item} bones are hand-space like third person (blade along -Z).
 */
public class FpHeldItemLayer extends BlockAndItemGeoLayer<FpViewmodel, Void, GeoRenderState> {
    public FpHeldItemLayer(GeoRenderer<FpViewmodel, Void, GeoRenderState> renderer) {
        super(renderer);
    }

    @Override
    public void addRenderData(FpViewmodel animatable, Void related, GeoRenderState state, float partialTick) {
    }

    @Override
    protected List<RenderData<GeoRenderState>> getRelevantBones(GeoRenderState state, BakedGeoModel model) {
        List<RenderData<GeoRenderState>> bones = new ArrayList<>(2);
        if (model.getBone("elucidator").isPresent()) {
            bones.add(new RenderData<>("elucidator", ItemDisplayContext.NONE,
                    (bone, s) -> Either.left(mainHand())));
        }
        if (model.getBone("right_arm_item").isPresent()) {
            bones.add(new RenderData<>("right_arm_item", ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    (bone, s) -> Either.left(mainHand())));
        }
        if (model.getBone("left_arm_item").isPresent()) {
            bones.add(new RenderData<>("left_arm_item", ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    (bone, s) -> Either.left(offHand())));
        }
        return bones;
    }

    @Override
    protected void submitItemStackRender(PoseStack poseStack, GeoBone bone, ItemStack stack,
                                         ItemDisplayContext context, GeoRenderState state,
                                         SubmitNodeCollector collector, CameraRenderState camera,
                                         int packedLight, int packedOverlay, int outlineColor) {
        poseStack.pushPose();
        if ("elucidator".equals(bone.name())) {
            if (ClientCombat.localWeaponCategory() == com.actioncombat.combat.WeaponCategory.SPEAR) SpearItemPose.apply(poseStack);
            else RapierItemPose.apply(poseStack);
        } else {
            poseStack.mulPose(Axis.XN.rotationDegrees(90.0F));
            poseStack.translate(0.0F, 0.125F, -0.0625F);
        }
        super.submitItemStackRender(poseStack, bone, stack, context, state, collector,
                camera, packedLight, packedOverlay, outlineColor);
        poseStack.popPose();
    }

    private static ItemStack mainHand() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getMainHandItem();
    }

    private static ItemStack offHand() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getOffhandItem();
    }
}

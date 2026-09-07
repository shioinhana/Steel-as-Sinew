package com.actioncombat.client;

import com.actioncombat.entity.PlayerAnimatable;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Either;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import software.bernie.geckolib.cache.model.BakedGeoModel;
import software.bernie.geckolib.cache.model.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.layer.builtin.BlockAndItemGeoLayer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Draws the mirrored player's actual held swords on the dummy hand bones.
 * The extra -90° X matches GeckoLib's ItemInHandGeoLayer so a vanilla sword
 * lies along the authored blade (-Z from the hand).
 */
public class DummyHeldItemLayer
        extends BlockAndItemGeoLayer<PlayerAnimatable, Void, PlayerGeoRenderState> {

    public DummyHeldItemLayer(GeoRenderer<PlayerAnimatable, Void, PlayerGeoRenderState> renderer) {
        super(renderer);
    }

    @Override
    public void addRenderData(PlayerAnimatable dummy, Void related, PlayerGeoRenderState state, float partialTick) {
        EnumMap<EquipmentSlot, ItemStack> equipment = equipmentMap(state);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            equipment.put(slot, dummy.getItemBySlot(slot));
        }
        state.addGeckolibData(DataTickets.EQUIPMENT_BY_SLOT, equipment);
    }

    @Override
    protected List<RenderData<PlayerGeoRenderState>> getRelevantBones(
            PlayerGeoRenderState state, BakedGeoModel model) {
        List<RenderData<PlayerGeoRenderState>> bones = new ArrayList<>(2);
        String right = YsmStyle.enabled() ? YsmStyle.RIGHT_HAND : "hand_item_r";
        String left = YsmStyle.enabled() ? YsmStyle.LEFT_HAND : "hand_item_l";
        bones.add(new RenderData<>(right, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                (bone, s) -> Either.left(stack(s, EquipmentSlot.MAINHAND))));
        if (model.getBone(left).isPresent()) {
            bones.add(new RenderData<>(left, ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    (bone, s) -> Either.left(stack(s, EquipmentSlot.OFFHAND))));
        }
        return bones;
    }

    @Override
    protected void submitItemStackRender(PoseStack poseStack, GeoBone bone, ItemStack stack,
                                         ItemDisplayContext context, PlayerGeoRenderState state,
                                         SubmitNodeCollector collector, CameraRenderState camera,
                                         int packedLight, int packedOverlay, int outlineColor) {
        poseStack.pushPose();
        if (context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            poseStack.mulPose(Axis.XN.rotationDegrees(90.0F));
            poseStack.translate(0.0F, 0.125F, -0.0625F);
            if (stack.getItem() instanceof ShieldItem) {
                if (context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
                    poseStack.translate(0.0, 0.125, 0.25);
                    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                } else {
                    poseStack.translate(0.0, 0.125, -0.25);
                }
            }
        }
        super.submitItemStackRender(poseStack, bone, stack, context, state, collector,
                camera, packedLight, packedOverlay, outlineColor);
        poseStack.popPose();
    }

    @SuppressWarnings("unchecked")
    private static EnumMap<EquipmentSlot, ItemStack> equipmentMap(PlayerGeoRenderState state) {
        if (state.hasGeckolibData(DataTickets.EQUIPMENT_BY_SLOT)) {
            return (EnumMap<EquipmentSlot, ItemStack>) state.getGeckolibData(DataTickets.EQUIPMENT_BY_SLOT);
        }
        return new EnumMap<>(EquipmentSlot.class);
    }

    private static ItemStack stack(PlayerGeoRenderState state, EquipmentSlot slot) {
        if (!state.hasGeckolibData(DataTickets.EQUIPMENT_BY_SLOT)) return ItemStack.EMPTY;
        EnumMap<EquipmentSlot, ItemStack> equipment = state.getGeckolibData(DataTickets.EQUIPMENT_BY_SLOT);
        ItemStack stack = equipment.get(slot);
        return stack == null ? ItemStack.EMPTY : stack;
    }
}

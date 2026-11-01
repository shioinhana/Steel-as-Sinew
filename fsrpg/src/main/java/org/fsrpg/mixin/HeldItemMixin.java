package org.fsrpg.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.fsrpg.client.OfficialHeldItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
abstract class HeldItemMixin {
    @Inject(method="submitArmWithItem",at=@At("HEAD"),cancellable=true)
    private void fsrpg$officialWristItem(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack,
                                      HumanoidArm arm, PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo callback) {
        if (OfficialHeldItems.submit(state,item,stack,arm,poses,collector,light)) callback.cancel();
        else OfficialHeldItems.observeVanilla(state,item,stack,arm);
    }
}

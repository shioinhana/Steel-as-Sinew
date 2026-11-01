package org.fsrpg.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.fsrpg.client.PlayerAnimations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Armor is submitted with separate humanoid models; fit it to the same server actor pose. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidArmorMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void fsrpg$armor(HumanoidRenderState state, CallbackInfo callback) {
        if (state instanceof AvatarRenderState avatar && !((Object) this instanceof PlayerModel))
            PlayerAnimations.applyHumanoid((HumanoidModel<?>) (Object) this, avatar);
    }
}

package org.fsrpg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.fsrpg.client.ShoulderInteraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class ShoulderInteractionMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void fsrpg$validateShoulderAttack(CallbackInfoReturnable<Boolean> callback) {
        if (ShoulderInteraction.beforeStartAttack()) callback.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void fsrpg$validateShoulderMining(boolean attackDown, CallbackInfo callback) {
        if (ShoulderInteraction.beforeContinueAttack(attackDown)) callback.cancel();
    }
    @Inject(method = "startAttack", at = @At("RETURN"))
    private void fsrpg$restoreOutlineAfterAttack(CallbackInfoReturnable<Boolean> callback) {
        ShoulderInteraction.afterAttack();
    }
    @Inject(method = "continueAttack", at = @At("RETURN"))
    private void fsrpg$restoreOutlineAfterMining(boolean attackDown, CallbackInfo callback) {
        ShoulderInteraction.afterAttack();
    }
    @WrapMethod(method = {"startUseItem", "pickBlock"})
    private void fsrpg$keepNativeUseTarget(Operation<Void> original) {
        ShoulderInteraction.beforeNativeUse();
        try {
            original.call();
        } finally {
            // Also restores the display when another mixin cancels at HEAD
            // or the native method returns early from a canceled input event.
            ShoulderInteraction.afterNativeUse();
        }
    }
}

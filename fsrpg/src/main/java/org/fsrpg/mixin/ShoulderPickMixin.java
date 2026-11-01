package org.fsrpg.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.fsrpg.client.ShoulderInteraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class ShoulderPickMixin {
    @Inject(method = "pick", at = @At("TAIL"))
    private void fsrpg$pickFromFinalShoulderCamera(float partialTick, CallbackInfo callback) {
        ShoulderInteraction.afterPick(partialTick);
    }
}

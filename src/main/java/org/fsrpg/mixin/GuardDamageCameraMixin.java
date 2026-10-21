package org.fsrpg.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.fsrpg.client.GuardImpactPresentation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GuardDamageCameraMixin {
    @Inject(method="bobHurt",at=@At("HEAD"),cancellable=true)
    private void fsrpg$guardedHurt(PoseStack stack,float partial,CallbackInfo ci){if(GuardImpactPresentation.skipCameraBob())ci.cancel();}
}

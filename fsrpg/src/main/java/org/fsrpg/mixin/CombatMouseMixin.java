package org.fsrpg.mixin;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.fsrpg.client.CombatCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public abstract class CombatMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Inject(method="turnPlayer",at=@At("HEAD"),cancellable=true)
    private void fsrpg$orbit(double time,CallbackInfo ci) {
        if(!Minecraft.getInstance().isWindowActive())return;
        if(CombatCamera.mouse(accumulatedDX,accumulatedDY)) {
            // Vanilla normally clears these after turnPlayer. Cancellation must consume them too.
            accumulatedDX=0;accumulatedDY=0;ci.cancel();
        }
    }
}

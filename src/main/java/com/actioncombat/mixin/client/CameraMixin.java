package com.actioncombat.mixin.client;

import com.actioncombat.client.ClientCombat;
import com.actioncombat.client.CombatCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Genshin-style third-person placement. Vanilla has no event to nudge distance
 * / height after setup, so we pose it here. Never applies a shoulder offset.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void move(float depth, float vertical, float horizontal);

    @Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"))
    private void actioncombat$combatCamera(CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        CombatCamera.prepare(self);
        if (!self.isDetached()) return;
        if (!ClientCombat.isLocalDrawn()) return;
        float height = ClientCombat.currentHeightOffset();
        if (height != 0.0F) {
            this.move(0.0F, height, 0.0F);
        }
    }
}

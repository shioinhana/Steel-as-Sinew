package com.actioncombat.mixin.client;

import com.actioncombat.client.CombatCamera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MouseHandler calls {@code LocalPlayer.turn}, which is inherited from Entity and
 * is not an invoke of {@code Entity.turn} in the MouseHandler bytecode — a Redirect
 * on that invoke matches nothing and crashes at startup.
 */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void actioncombat$cameraOrBodyTurn(double yRot, double xRot, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer)) return;
        if (CombatCamera.handleMouseTurn(yRot, xRot)) {
            ci.cancel();
        }
    }
}

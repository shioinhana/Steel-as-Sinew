package org.fsrpg.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.fsrpg.client.AvatarAppearances;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AvatarSkinMixin {
    @Inject(method="getSkin",at=@At("RETURN"),cancellable=true)
    private void fsrpg$externalSkin(CallbackInfoReturnable<PlayerSkin> callback) {
        callback.setReturnValue(AvatarAppearances.skin(((AbstractClientPlayer)(Object)this).getUUID(),callback.getReturnValue()));
    }
}

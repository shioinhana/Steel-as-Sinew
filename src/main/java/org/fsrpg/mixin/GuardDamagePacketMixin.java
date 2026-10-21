package org.fsrpg.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.fsrpg.client.GuardImpactPresentation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class GuardDamagePacketMixin {
    @Inject(method="handleDamageEvent",at=@At("TAIL"))
    private void fsrpg$damageSource(ClientboundDamageEventPacket packet,CallbackInfo ci){GuardImpactPresentation.damage(packet);}
}

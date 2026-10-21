package org.fsrpg.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.fsrpg.movement.FootworkPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class FootworkTravelMixin {
    @Inject(method="travel(Lnet/minecraft/world/phys/Vec3;)V",at=@At("TAIL"))
    private void fsrpg$lightBodyDrag(Vec3 input,CallbackInfo ci){FootworkPhysics.afterTravel((LivingEntity)(Object)this);}
}

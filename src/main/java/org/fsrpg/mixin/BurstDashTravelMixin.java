package org.fsrpg.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.fsrpg.client.BurstDashMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntity.class)
public abstract class BurstDashTravelMixin {
    @ModifyArg(method="handleRelativeFrictionAndCalculateMovement",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"),index=1)
    private Vec3 fsrpg$approvedBurst(Vec3 original) {
        BurstDashMotion.beforeMove((LivingEntity)(Object)this);
        return ((LivingEntity)(Object)this).getDeltaMovement();
    }
}

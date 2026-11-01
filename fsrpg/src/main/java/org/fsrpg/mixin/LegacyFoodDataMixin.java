package org.fsrpg.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.fsrpg.RpgServer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve vanilla exhaustion/healing; only the learned mind bonuses alter its timers. */
@Mixin(FoodData.class)
public abstract class LegacyFoodDataMixin {
    @Unique private double fsrpg$regen=1;
    @Unique private int fsrpg$food=18;
    @Inject(method="tick(Lnet/minecraft/server/level/ServerPlayer;)V",at=@At("HEAD"))
    private void fsrpg$context(ServerPlayer player,CallbackInfo ci) {
        var server=RpgServer.instance();
        if(server!=null){var effects=server.progression().effects(player.getUUID());fsrpg$regen=player.getFoodData().getFoodLevel()>=20?effects.regenerationMultiplier():1;fsrpg$food=effects.regenerationFoodLevel();}
    }
    @Inject(method="tick(Lnet/minecraft/server/level/ServerPlayer;)V",at=@At("RETURN"))
    private void fsrpg$clear(ServerPlayer player,CallbackInfo ci){fsrpg$regen=1;fsrpg$food=18;}
    @ModifyConstant(method="tick(Lnet/minecraft/server/level/ServerPlayer;)V",constant=@Constant(intValue=10))
    private int fsrpg$fastRegen(int value){return Math.max(1,(int)Math.round(value/fsrpg$regen));}
    @ModifyConstant(method="tick(Lnet/minecraft/server/level/ServerPlayer;)V",constant=@Constant(intValue=80))
    private int fsrpg$normalRegen(int value){return Math.max(1,(int)Math.round(value/fsrpg$regen));}
    @ModifyConstant(method="tick(Lnet/minecraft/server/level/ServerPlayer;)V",constant=@Constant(intValue=18))
    private int fsrpg$threshold(int value){return fsrpg$food;}
}

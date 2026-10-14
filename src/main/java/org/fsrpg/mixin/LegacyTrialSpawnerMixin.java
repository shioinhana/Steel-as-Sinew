package org.fsrpg.mixin;

import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import org.fsrpg.RpgServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The sixth dual stack requires the actual vanilla spawner's successful completion. */
@Mixin(TrialSpawnerBlockEntity.class)
public abstract class LegacyTrialSpawnerMixin {
    @Inject(method="setState(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/trialspawner/TrialSpawnerState;)V",at=@At("HEAD"))
    private void fsrpg$trial(Level level,TrialSpawnerState next,CallbackInfo ci) {
        if(!(level instanceof ServerLevel serverLevel)||next!=TrialSpawnerState.WAITING_FOR_REWARD_EJECTION||RpgServer.instance()==null)return;
        var block=(TrialSpawnerBlockEntity)(Object)this;if(block.getState()!=TrialSpawnerState.ACTIVE)return;
        for(var id:block.getTrialSpawner().getStateData().pack().detectedPlayers())
            if(serverLevel.getPlayerByUUID(id) instanceof ServerPlayer player)RpgServer.instance().progression().trialPassed(player.getUUID());
    }
}

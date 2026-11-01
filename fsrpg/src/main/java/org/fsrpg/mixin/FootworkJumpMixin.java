package org.fsrpg.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.fsrpg.RpgServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Historical JumpTiming rule (old {@code mixin/LivingEntityMixin}): a real player jump, whether
 * vanilla Space or a footwork jump, is allowed at most once per four ticks. The server uses the
 * configured footwork interval; the client keeps a local prediction with the shipped default so
 * spamming Space does not produce a predicted hop that the server then rejects.
 */
@Mixin(LivingEntity.class)
public abstract class FootworkJumpMixin {
    @Unique private static final long FSRPG_DEFAULT_JUMP_INTERVAL = 4L;
    @Unique private long fsrpg$lastJump = Long.MIN_VALUE;

    @Inject(method = "jumpFromGround()V", at = @At("HEAD"), cancellable = true)
    private void fsrpg$footworkJumpInterval(CallbackInfo ci) {
        if (!(((Object) this) instanceof Player player)) return;
        if (player instanceof ServerPlayer serverPlayer) {
            RpgServer server = RpgServer.instance();
            if (server != null) {
                if (!server.footwork().canJump(serverPlayer)) { ci.cancel(); return; }
                fsrpg$lastJump = serverPlayer.level().getGameTime();
                return;
            }
        }
        long now = player.level().getGameTime();
        if (fsrpg$lastJump != Long.MIN_VALUE && now - fsrpg$lastJump < FSRPG_DEFAULT_JUMP_INTERVAL) {
            ci.cancel();
            return;
        }
        fsrpg$lastJump = now;
    }
}

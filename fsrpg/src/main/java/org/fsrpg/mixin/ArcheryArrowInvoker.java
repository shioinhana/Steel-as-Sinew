package org.fsrpg.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse vanilla entity penetration and vanilla ground-impact settlement instead of reimplementing them. */
@Mixin(AbstractArrow.class)
public interface ArcheryArrowInvoker {
    @Invoker("setPierceLevel") void fsrpg$setPierceLevel(byte level);
    /** {@code AbstractArrow.onHitBlock} is protected; the block-piercing adapter reuses it when the fourth face stops the arrow. */
    @Invoker("onHitBlock") void fsrpg$onHitBlock(BlockHitResult hit);
}

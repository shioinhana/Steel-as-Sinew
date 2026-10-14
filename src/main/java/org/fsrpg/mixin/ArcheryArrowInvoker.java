package org.fsrpg.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse vanilla entity penetration instead of reimplementing projectile collision. */
@Mixin(AbstractArrow.class)
public interface ArcheryArrowInvoker {
    @Invoker("setPierceLevel") void fsrpg$setPierceLevel(byte level);
}

package org.fsrpg.mixin;

import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Keep pending melee subject to the original gaze predicate, including disguise and NeoForge hooks. */
@Mixin(EnderMan.class)
public interface EnderManStareInvoker {
    @Invoker("isBeingStaredBy") boolean fsrpg$isBeingStaredBy(Player player);
}

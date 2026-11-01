package org.fsrpg.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only diagnostics of foil selected by Minecraft's item model resolver. */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerAccess {
    @Accessor("foilType") ItemStackRenderState.FoilType fsrpg$foil();
}

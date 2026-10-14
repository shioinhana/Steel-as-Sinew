package org.fsrpg.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only diagnostics of the official resolved render state. */
@Mixin(ItemStackRenderState.class)
public interface ItemStateAccess {
    @Accessor("activeLayerCount") int fsrpg$activeLayers();
    @Accessor("layers") ItemStackRenderState.LayerRenderState[] fsrpg$layers();
}

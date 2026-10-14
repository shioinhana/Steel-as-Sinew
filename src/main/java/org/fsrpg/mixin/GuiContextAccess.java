package org.fsrpg.mixin;

import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the selected vanilla contextual renderer so jump/locator bars are retained. */
@Mixin(Gui.class)
public interface GuiContextAccess {
    @Accessor("contextualInfoBar") Pair<?, ContextualBarRenderer> fsrpg$contextualBar();
}

package org.fsrpg.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.fsrpg.client.SwordMotionClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Translate the submitted avatar, layers and shadow together, before final render-state extraction. */
@Mixin(EntityRenderer.class)
public abstract class SwordTravelRenderMixin {
    @Inject(method="extractRenderState",at=@At("TAIL"))
    private void fsrpg$swordTravel(Entity entity,EntityRenderState state,float partial,CallbackInfo ci) {
        var offset=SwordMotionClient.renderOffset(entity);
        state.x+=offset.x;state.y+=offset.y;state.z+=offset.z;
    }
}

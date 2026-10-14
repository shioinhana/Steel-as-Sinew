package org.fsrpg.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.fsrpg.client.SkinnedArms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class SkinnedArmMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("HEAD"), cancellable = true)
    private void fsrpg$skin(PoseStack poses, VertexConsumer vertices, int light, int overlay, int tint, CallbackInfo callback) {
        if (SkinnedArms.render((ModelPart) (Object) this, poses, vertices, light, overlay, tint)) callback.cancel();
    }
}

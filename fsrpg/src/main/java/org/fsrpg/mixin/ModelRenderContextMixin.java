package org.fsrpg.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import org.fsrpg.client.SkinnedArms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Model.class)
public abstract class ModelRenderContextMixin {
    @Inject(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("HEAD"))
    private void fsrpg$begin(PoseStack poses, VertexConsumer vertices, int light, int overlay, int tint, CallbackInfo callback) {
        SkinnedArms.begin((Model<?>) (Object) this);
    }
    @Inject(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("RETURN"))
    private void fsrpg$end(PoseStack poses, VertexConsumer vertices, int light, int overlay, int tint, CallbackInfo callback) { SkinnedArms.end(); }
}

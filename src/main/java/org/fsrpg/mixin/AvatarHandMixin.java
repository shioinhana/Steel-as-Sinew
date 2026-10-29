package org.fsrpg.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.fsrpg.client.AvatarAppearances;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarHandMixin {
    @Inject(method="renderHand",at=@At("HEAD"),cancellable=true)
    private void fsrpg$modelArm(PoseStack poses,SubmitNodeCollector collector,int light,Identifier texture,ModelPart arm,boolean sleeve,CallbackInfo callback) {
        if(AvatarAppearances.firstPersonArm(((AvatarRenderer<?>)(Object)this).getModel(),arm,poses,collector,light))callback.cancel();
    }
}

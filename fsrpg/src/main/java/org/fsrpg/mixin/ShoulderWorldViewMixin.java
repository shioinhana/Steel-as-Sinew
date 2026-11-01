package org.fsrpg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.fsrpg.client.ShoulderInteraction;
import org.fsrpg.client.ShoulderInteractionView;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class ShoulderWorldViewMixin {
    @WrapOperation(method="renderLevel", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void fsrpg$visibleRay(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta,
                                 boolean outline, Camera camera, Matrix4f view, Matrix4f projection,
                                 Matrix4f culling, GpuBufferSlice fog, Vector4f fogColor, boolean sky,
                                 Operation<Void> original) {
        ShoulderInteractionView.capture(camera,view,projection,delta.getGameTimeDeltaPartialTick(false));
        if (ShoulderInteraction.active()) {
            // Preserve vanilla adventure/spectator/highlight rules with the final target.
            outline=((GameRendererAccess)(Object)this).fsrpg$shouldRenderBlockOutline();
        }
        original.call(renderer,allocator,delta,outline,camera,view,projection,culling,fog,fogColor,sky);
    }
}

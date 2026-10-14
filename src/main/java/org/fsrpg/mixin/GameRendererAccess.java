package org.fsrpg.mixin;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Invoker("getFov") float fsrpg$fov(Camera camera,float partial,boolean useSetting);
}

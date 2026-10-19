package org.fsrpg.mixin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.fsrpg.client.CombatCamera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class CombatCameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);
    @Inject(method="setup",at=@At("TAIL"))
    private void fsrpg$shoulder(Level level,Entity entity,boolean detached,boolean mirror,float partial,CallbackInfo ci) {
        if(entity!=Minecraft.getInstance().player||!detached||mirror)return;
        Vec3 result=CombatCamera.cameraPosition(level,entity,(Camera)(Object)this,partial);
        if(result!=null)setPosition(result);
    }
}

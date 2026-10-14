package org.fsrpg.mixin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
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
        if(!CombatCamera.active() || !CombatCamera.style().equals("rapier") || entity!=Minecraft.getInstance().player || !detached||mirror)return;
        var camera=(Camera)(Object)this;var start=camera.position();
        var shift=new org.joml.Vector3f(-CombatCamera.shoulder(),.12f,0).rotate(camera.rotation());
        var end=start.add(new Vec3(shift));
        var hit=level.clip(new ClipContext(start,end,ClipContext.Block.VISUAL,ClipContext.Fluid.NONE,entity));
        setPosition(start.lerp(hit.getLocation(),.9));
    }
}

package org.fsrpg.mixin;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientInput.class)
public interface ClientInputAccess { @Accessor("moveVector") void fsrpg$move(Vec2 value); }

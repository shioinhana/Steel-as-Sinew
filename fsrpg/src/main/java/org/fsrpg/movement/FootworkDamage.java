package org.fsrpg.movement;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

public final class FootworkDamage {
    public static final ResourceKey<DamageType> FLASH_THRUST=ResourceKey.create(Registries.DAMAGE_TYPE,Identifier.fromNamespaceAndPath("fsrpg","flash_thrust"));
    private FootworkDamage(){}
    public static DamageSource flashThrust(ServerPlayer p){return new DamageSource(p.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(FLASH_THRUST),p);}
}

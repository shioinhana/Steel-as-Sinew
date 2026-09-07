package com.actioncombat;

import com.actioncombat.entity.PlayerAnimatable;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ActionCombat.MODID);

    /** Client-side-only animated player stand-in. Never spawned on the server. */
    public static final Supplier<EntityType<PlayerAnimatable>> PLAYER_DUMMY = ENTITY_TYPES.register(
            "player_dummy",
            () -> EntityType.Builder.<PlayerAnimatable>of(PlayerAnimatable::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .fireImmune()
                    .noSummon()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(ActionCombat.MODID, "player_dummy")))
    );

    private ModEntities() {}
}

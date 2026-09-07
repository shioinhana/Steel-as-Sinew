package com.actioncombat;

import com.actioncombat.client.PlayerGeoRenderer;
import com.actioncombat.entity.PlayerAnimatable;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;

@Mod(ActionCombat.MODID)
public class ActionCombat {
    public static final String MODID = "actioncombat";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ActionCombat(IEventBus modBus) {
        ModItems.ITEMS.register(modBus);
        ModEntities.ENTITY_TYPES.register(modBus);
        ModAttachments.ATTACHMENTS.register(modBus);
        ModPayloads.register(modBus);
        ModGameTests.registerTestFunctions(modBus);

        modBus.addListener(this::addCreative);
        modBus.addListener(this::registerRenderers);
        modBus.addListener(this::registerEntityAttributes);
    }

    /** The dummy is a LivingEntity (so the armour layer works) and so needs attributes. */
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.PLAYER_DUMMY.get(), PlayerAnimatable.createAttributes().build());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.LONGSWORD);
        }
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PLAYER_DUMMY.get(), PlayerGeoRenderer::new);
    }
}

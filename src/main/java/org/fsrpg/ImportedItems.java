package org.fsrpg;

import java.util.List;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Standalone items from the user-supplied 261001 archive. */
public final class ImportedItems {
    private static final DeferredRegister.Items REGISTER=DeferredRegister.createItems("fsrpg");
    public static final List<String> IDS=List.of("money_pouch","gold_coin","silver_coin","crystal_coin","nether_coin",
            "gold_adventurer_badge","cobalt_adventurer_badge","copper_adventurer_badge","silver_adventurer_badge");
    public static final List<DeferredItem<Item>> ITEMS=IDS.stream().map(id->REGISTER.registerSimpleItem(id,p->p.stacksTo(id.equals("money_pouch")||id.endsWith("badge")?1:64))).toList();
    private ImportedItems() {}
    public static void register(IEventBus bus) { REGISTER.register(bus);bus.addListener(ImportedItems::creative); }
    private static void creative(BuildCreativeModeTabContentsEvent event) {
        if(event.getTabKey().equals(CreativeModeTabs.INGREDIENTS))for(var item:ITEMS)event.accept(item);
    }
}

package org.fsrpg;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** User-supplied item resources, kept together without changing existing item IDs. */
public final class ImportedItems {
    private static final DeferredRegister.Items REGISTER=DeferredRegister.createItems("fsrpg");
    private static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,"fsrpg");
    public static final List<String> IDS=List.of("money_pouch","gold_coin","silver_coin","crystal_coin","nether_coin",
            "gold_adventurer_badge","cobalt_adventurer_badge","copper_adventurer_badge","silver_adventurer_badge","white_adventurer_badge");
    public static final List<DeferredItem<Item>> ITEMS=IDS.stream().map(id->REGISTER.registerSimpleItem(id,p->p.stacksTo(id.equals("money_pouch")||id.endsWith("badge")?1:64))).toList();
    public static final DeferredHolder<CreativeModeTab,CreativeModeTab> RESOURCE_TAB=TABS.register("resources",()->CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.fsrpg.resources"))
            .icon(()->ITEMS.get(IDS.indexOf("white_adventurer_badge")).toStack())
            .withTabsBefore(CreativeModeTabs.INGREDIENTS)
            .displayItems((parameters,output)->ITEMS.forEach(output::accept))
            .build());
    private ImportedItems() {}
    public static void register(IEventBus bus) { REGISTER.register(bus);TABS.register(bus); }
}

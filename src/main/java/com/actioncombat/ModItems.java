package com.actioncombat;

import com.actioncombat.item.LongswordItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ActionCombat.MODID);

    public static final DeferredItem<Item> LONGSWORD = ITEMS.registerItem(
            CombatConstants.LONGSWORD_NAME,
            LongswordItem::new,
            new Item.Properties().stacksTo(1)
    );

    private ModItems() {}
}

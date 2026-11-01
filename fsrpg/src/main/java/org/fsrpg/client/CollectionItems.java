package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

/** Uses the registered game's ItemStack, including potion-bearing arrow names and icons. */
final class CollectionItems {
    private CollectionItems() {}
    static ItemStack stack(String id) {
        try { Identifier key = Identifier.parse(id); if (BuiltInRegistries.ITEM.containsKey(key)) return new ItemStack(BuiltInRegistries.ITEM.getValue(key)); }
        catch (RuntimeException ignored) { }
        return ItemStack.EMPTY;
    }
    static ItemStack stack(JsonObject item) {
        ItemStack stack = stack(ClientState.text(item, "id", ""));
        String potion = ClientState.text(item, "potion", "");
        if (!stack.isEmpty() && !potion.isBlank()) {
            try { BuiltInRegistries.POTION.get(Identifier.parse(potion)).ifPresent(holder -> stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder))); }
            catch (RuntimeException ignored) { }
        }
        return stack;
    }
    static String label(String id) { return label(stack(id)); }
    static String label(ItemStack stack) { return stack.isEmpty() ? "未知物品" : stack.getHoverName().getString(); }
    static String entity(String id, String fallback) {
        try { Identifier key = Identifier.parse(id); if (BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return BuiltInRegistries.ENTITY_TYPE.getValue(key).getDescription().getString(); }
        catch (RuntimeException ignored) { }
        return fallback;
    }
}

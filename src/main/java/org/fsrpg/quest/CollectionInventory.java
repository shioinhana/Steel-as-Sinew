package org.fsrpg.quest;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.storage.LevelResource;

/** Server-only inventory adapter. Exchanges are simulated completely before any slot is changed. */
public final class CollectionInventory {
    private static final String RECEIPT = "fsrpgCollectionReceipt";
    private CollectionInventory() {}
    public static Map<String, Integer> owned(ServerPlayer player) {
        Map<String, Integer> result = new HashMap<>();
        for (int slot : slots()) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result.merge(id, stack.getCount(), Integer::sum);
            PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.potion().isPresent() && !potion.customEffects().iterator().hasNext())
                result.merge(id + "@" + BuiltInRegistries.POTION.getKey(potion.potion().get().value()), stack.getCount(), Integer::sum);
        }
        return result;
    }
    public static ItemStack stack(CollectionCatalog.Item item) {
        Identifier id = Identifier.parse(item.id());
        if (!BuiltInRegistries.ITEM.containsKey(id) || item.id().equals("minecraft:air")) throw new IllegalArgumentException("任务物品已不存在：" + item.id());
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id), item.count());
        if (!item.potion().isEmpty()) stack.set(DataComponents.POTION_CONTENTS, new PotionContents(BuiltInRegistries.POTION.get(Identifier.parse(item.potion())).orElseThrow()));
        return stack;
    }
    private static int[] slots() {
        int[] slots = new int[37]; for (int i = 0; i < 36; i++) slots[i] = i; slots[36] = 40; return slots;
    }
    private static List<ItemStack> copy(ServerPlayer p) {
        List<ItemStack> copy = new ArrayList<>(); for (int slot : slots()) copy.add(p.getInventory().getItem(slot).copy()); return copy;
    }
    private static void replace(ServerPlayer p, List<ItemStack> items) {
        int[] slots = slots(); for (int i = 0; i < slots.length; i++) p.getInventory().setItem(slots[i], items.get(i).copy());
        p.getInventory().setChanged(); p.inventoryMenu.broadcastChanges(); p.containerMenu.broadcastChanges();
    }
    /** Reject a full inventory without consuming ingredients or dropping potentially replayable rewards. */
    private static List<ItemStack> simulate(ServerPlayer p, List<CollectionCatalog.Item> requirements, List<CollectionCatalog.Item> rewards) {
        if (!CollectionService.hasRequirements(requirements, owned(p))) throw new IllegalStateException("材料不足，请先收齐任务要求的物品");
        List<ItemStack> result = copy(p);
        for (CollectionCatalog.Item requirement : requirements) {
            ItemStack template = stack(requirement); int remaining = requirement.count();
            for (ItemStack existing : result) {
                if (remaining == 0) break;
                boolean match = matchesRequirement(existing, template, requirement);
                if (!match) continue;
                int taken = Math.min(remaining, existing.getCount()); existing.shrink(taken); remaining -= taken;
            }
            if (remaining != 0) throw new IllegalStateException("材料发生变化，请重新提交");
        }
        for (CollectionCatalog.Item reward : rewards) {
            ItemStack template = stack(reward); int remaining = reward.count();
            // Vanilla also merges into an already occupied offhand stack. Never fill an empty
            // offhand automatically, and never replace its unrelated item with a reward.
            for (int i = 0; i < result.size() && remaining > 0; i++) {
                ItemStack existing = result.get(i);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, template)) {
                    int added = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                    if (added > 0) { existing.grow(added); remaining -= added; }
                }
            }
            for (int i = 0; i < 36 && remaining > 0; i++) if (result.get(i).isEmpty()) {
                int added = Math.min(remaining, template.getMaxStackSize()); result.set(i, template.copyWithCount(added)); remaining -= added;
            }
            if (remaining > 0) throw new IllegalStateException("背包空间不足，请腾出空间后再领取");
        }
        return result;
    }
    private static boolean matchesRequirement(ItemStack existing, ItemStack template, CollectionCatalog.Item requirement) {
        if (!existing.is(template.getItem())) return false;
        if (requirement.potion().isEmpty()) return true;
        PotionContents potion = existing.get(DataComponents.POTION_CONTENTS);
        PotionContents wanted = template.get(DataComponents.POTION_CONTENTS);
        // Exact registered potion and no extra effects, consistently with owned(). A normal
        // anvil name or display color does not turn a slowness arrow into a different ingredient.
        return potion != null && wanted != null && wanted.potion().isPresent() && potion.is(wanted.potion().get());
    }
    public static void preflight(ServerPlayer p, CollectionService.Plan plan) { simulate(p, plan.requirements(), plan.rewards()); }
    private static CompoundTag persisted(ServerPlayer p) {
        CompoundTag root = p.getPersistentData(); CompoundTag persisted = root.getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        root.put(Player.PERSISTED_NBT_TAG, persisted); return persisted;
    }
    public static boolean applied(ServerPlayer p, UUID transaction) { return persisted(p).getStringOr(RECEIPT, "").equals(transaction.toString()); }
    private static boolean receipt(CompoundTag tag, String expectedReceipt) {
        return tag.getCompoundOrEmpty("NeoForgeData").getCompoundOrEmpty(Player.PERSISTED_NBT_TAG).getStringOr(RECEIPT, "").equals(expectedReceipt);
    }
    private static void saveAndVerify(MinecraftServer server, ServerPlayer p, String expectedReceipt) throws IOException {
        // Both vanilla writes catch some IO failures internally, so a normal return is not proof.
        server.saveEverything(true, true, false);
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path file = root.resolve("playerdata").resolve(p.getStringUUID() + ".dat");
        if (!Files.isRegularFile(file) || !receipt(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), expectedReceipt))
            throw new IOException("玩家背包保存未通过核验");
        var profile = server.getSingleplayerProfile();
        if (profile != null && profile.id().equals(p.getUUID())) {
            CompoundTag level = NbtIo.readCompressed(root.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            if (!receipt(level.getCompoundOrEmpty("Data").getCompoundOrEmpty("Player"), expectedReceipt))
                throw new IOException("单人存档背包保存未通过核验");
        }
    }
    /** Save inventory and receipt together; also flush the single-player level.dat player copy. */
    public static void applyOnce(MinecraftServer server, ServerPlayer p, CollectionService.Pending exchange) throws IOException {
        if (!p.getUUID().equals(exchange.player())) throw new IllegalArgumentException("任务交换不属于当前玩家");
        if (applied(p, exchange.transactionId())) {
            // A prior attempt may have written only one of the two single-player copies.
            // Reconcile both copies before the collection outbox is allowed to finish.
            try { saveAndVerify(server, p, exchange.transactionId().toString()); }
            catch (Exception error) { throw new IOException("交换已应用，背包保存待恢复，请稍后重试", error); }
            return;
        }
        List<ItemStack> after = simulate(p, exchange.requirements(), exchange.rewards());
        List<ItemStack> before = copy(p);
        try {
            replace(p, after); persisted(p).putString(RECEIPT, exchange.transactionId().toString());
            saveAndVerify(server, p, exchange.transactionId().toString());
        } catch (Exception error) {
            // A fresh rollback marker proves that this attempt saved the current before image;
            // simply checking the old receipt could mistake an old, unsaved inventory for it.
            String rollbackReceipt = "rollback:" + UUID.randomUUID();
            replace(p, before); persisted(p).putString(RECEIPT, rollbackReceipt);
            try { saveAndVerify(server, p, rollbackReceipt); }
            catch (Exception rollbackError) {
                // Do not tell the outbox to cancel while an applied inventory may remain on disk.
                // Keep the applied state in memory; applyOnce can retry its save without regranting.
                persisted(p).putString(RECEIPT, exchange.transactionId().toString());
                replace(p, after);
                IOException uncertain = new IOException("交换背包保存待恢复，请稍后重试；任务不会重复发奖", error);
                uncertain.addSuppressed(rollbackError);
                throw uncertain;
            }
            throw new IOException("任务交换保存失败，背包已核验退回，请重新提交", error);
        }
    }
}

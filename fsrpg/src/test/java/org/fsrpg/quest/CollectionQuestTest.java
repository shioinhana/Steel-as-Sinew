package org.fsrpg.quest;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.quest.CollectionCatalog.Item;

/** Real configured exchanges, exact inventory accounting, one-time rewards and durable recovery. */
public final class CollectionQuestTest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static int checks;
    public static void main(String[] args) throws Exception {
        CollectionCatalog catalog = CollectionCatalog.bundled(JSON);
        catalogAndStrictValidation(catalog);
        creatureFlow(catalog);
        cropFlow(catalog);
        plansAndPlayerIsolation(catalog);
        pendingRecoveryAndPersistence(catalog);
        invalidSavedState(catalog);
        System.out.println("CollectionQuestTest passed: " + checks + " checks");
    }
    private static void catalogAndStrictValidation(CollectionCatalog catalog) {
        equal(63, catalog.mobs().size(), "official resources yield 63 eligible creatures");
        equal(22, catalog.crops().size(), "twenty-two cultivated crops including Nether fungi");
        equal(Set.of("minecraft:rotten_flesh", "minecraft:iron_ingot", "minecraft:carrot", "minecraft:potato"), keys(catalog.mobRequirements("minecraft:zombie")), "zombie exactly the user's four items");
        equal(Set.of("minecraft:nether_star"), keys(catalog.mobRequirements("minecraft:wither")), "source-code wither death drop included");
        equal(17, catalog.mobRequirements("minecraft:sheep").size(), "all genuine wool color kill drops plus mutton");
        check(!catalog.containsMob("minecraft:ravager"), "saddle-only creature excluded as equipment");
        check(!catalog.containsMob("minecraft:armadillo"), "brush/grow drops cannot create free death-loot quests");
        equal(Set.of("minecraft:gunpowder"), keys(catalog.mobRequirements("minecraft:creeper")), "special skeleton music discs excluded");
        equal(Set.of("minecraft:seagrass"), keys(catalog.mobRequirements("minecraft:turtle")), "lightning bowl and growing-up scute excluded");
        equal(6, catalog.mobRequirements("minecraft:guardian").size(), "rare fish nested table expanded");
        check(keys(catalog.mobRequirements("minecraft:elder_guardian")).contains("minecraft:tide_armor_trim_smithing_template"), "rare non-equipment template included");
        equal("minecraft:poison", catalog.mobRequirements("minecraft:bogged").getLast().potion(), "poison arrow retained");
        equal("minecraft:slowness", catalog.mobRequirements("minecraft:stray").getLast().potion(), "slowness arrow retained");
        equal("minecraft:weakness", catalog.mobRequirements("minecraft:parched").getLast().potion(), "new weakness arrow retained");
        equal("minecraft:pitcher_pod", catalog.crop("minecraft:pitcher_plant").seed(), "pitcher pod is seed");
        equal("minecraft:torchflower", catalog.crop("minecraft:torchflower").mature(), "mature torchflower distinct from seed");
        equal(List.of(new Item("minecraft:crimson_fungus",11)), catalog.cropRequirements("minecraft:crimson_fungus"), "crimson fungus is seed and harvested crop");
        equal(List.of(new Item("minecraft:warped_fungus",11)), catalog.cropRequirements("minecraft:warped_fungus"), "warped fungus is seed and harvested crop");
        catalog.validateReferences(id -> true, id -> true, id -> true);
        rejects(() -> catalog.validateReferences(id -> !id.equals("minecraft:potato"), id -> true, id -> true), "missing item registry reference");
        rejects(() -> catalog.validateReferences(id -> true, id -> !id.equals("minecraft:zombie"), id -> true), "missing entity registry reference");
        rejects(() -> catalog.validateReferences(id -> true, id -> true, id -> !id.equals("minecraft:poison")), "missing potion registry reference");
        JsonObject definition = JSON.toJsonTree(catalog.definition()).getAsJsonObject();
        JsonObject unknown = definition.deepCopy(); unknown.addProperty("surprise", 1);
        rejects(() -> CollectionCatalog.parse(unknown, JSON), "unknown root field");
        JsonObject nested = definition.deepCopy(); nested.getAsJsonArray("mobs").get(0).getAsJsonObject().addProperty("repeatable", true);
        rejects(() -> CollectionCatalog.parse(nested, JSON), "unknown nested field");
        JsonObject fractional = definition.deepCopy(); fractional.addProperty("cropSeedRequired", 1.5);
        rejects(() -> CollectionCatalog.parse(fractional, JSON), "fractional requirement rejected before Gson coercion");
        JsonObject numericString = definition.deepCopy(); numericString.addProperty("cropSeedRequired", "1");
        rejects(() -> CollectionCatalog.parse(numericString, JSON), "numeric string rejected");
        JsonObject wrongFlag = definition.deepCopy(); wrongFlag.addProperty("firstHuntRequired", "true");
        rejects(() -> CollectionCatalog.parse(wrongFlag, JSON), "boolean string rejected");
        JsonObject negative = definition.deepCopy(); negative.addProperty("seedChoiceCount", -1);
        rejects(() -> CollectionCatalog.parse(negative, JSON), "negative reward rejected");
        JsonObject duplicate = definition.deepCopy(); duplicate.getAsJsonArray("mobs").add(duplicate.getAsJsonArray("mobs").get(0).deepCopy());
        rejects(() -> CollectionCatalog.parse(duplicate, JSON), "duplicate creature rejected");
        JsonObject duplicateSeed = definition.deepCopy(); duplicateSeed.getAsJsonArray("crops").get(1).getAsJsonObject().addProperty("seed", "minecraft:wheat_seeds");
        rejects(() -> CollectionCatalog.parse(duplicateSeed, JSON), "same seed cannot earn several crop counts");
        JsonObject impossibleMilestone = definition.deepCopy(); impossibleMilestone.getAsJsonObject("cropMilestone").addProperty("target", catalog.crops().size() + 1);
        rejects(() -> CollectionCatalog.parse(impossibleMilestone, JSON), "unreachable crop milestone rejected");
        JsonObject excessive = definition.deepCopy(); excessive.addProperty("mobRewardMultiplier", 65);
        rejects(() -> CollectionCatalog.parse(excessive, JSON), "bounded multiplier");
        rejects(() -> catalog.mob("minecraft:unknown"), "unknown creature intent");
        rejects(() -> catalog.crop("minecraft:unknown"), "unknown crop intent");
        equal(catalog.definition(), CollectionCatalog.parse(definition, JSON).definition(), "definition JSON round trip");
    }
    private static void creatureFlow(CollectionCatalog catalog) {
        CollectionService service = new CollectionService(catalog); UUID player = UUID.randomUUID(); Inventory inventory = new Inventory();
        inventory.add(catalog.mobRequirements("minecraft:zombie"));
        rejects(() -> service.planMob(player, "minecraft:zombie", inventory.items), "creature submission requires first hunt");
        check(service.firstKill(player, "minecraft:zombie"), "first trusted monster kill completes tutorial");
        check(!service.firstKill(player, "minecraft:skeleton"), "first monster recorded once");
        equal("minecraft:zombie", service.progress(player).firstHuntEntity(), "first monster identity stable");
        int index = 0;
        for (CollectionCatalog.Mob mob : catalog.mobs()) {
            inventory.items.clear(); inventory.add(catalog.mobRequirements(mob.id()));
            CollectionService.Plan plan = service.planMob(player, mob.id(), inventory.items);
            for (Item item : catalog.mobRewards(mob.id())) equal(2, item.count(), "each creature drop rewarded twice: " + mob.id());
            if (++index == 5) equal(Set.of("minecraft:iron_sword", "minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel", "minecraft:iron_hoe"), ironKeys(plan.rewards(), true), "fifth unique species awards five iron tools");
            else check(ironKeys(plan.rewards(), true).isEmpty(), "tools never repeated outside fifth species");
            if (index == 10) equal(Set.of("minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"), ironKeys(plan.rewards(), false), "tenth unique species awards full iron armor");
            else check(ironKeys(plan.rewards(), false).isEmpty(), "armor never repeated outside tenth species");
            apply(service, plan, inventory);
            for (Item item : catalog.mobRewards(mob.id())) equal(2, inventory.items.get(item.key()), "ingredients consumed before drop reward: " + mob.id());
            final String id = mob.id(); rejects(() -> service.planMob(player, id, inventory.items), "duplicate species cannot farm counts/rewards");
        }
        equal(63, service.progress(player).mobCount(), "all configured unique species completed");
        equal(Set.of(5, 10), service.progress(player).claimedMobMilestones(), "milestones claimed once");
        UUID missingPlayer = UUID.randomUUID(); service.firstKill(missingPlayer, "minecraft:zombie");
        Inventory missing = new Inventory(); missing.add(catalog.mobRequirements("minecraft:zombie")); missing.items.remove("minecraft:carrot");
        Map<String,Integer> before = Map.copyOf(missing.items); CollectionService.Snapshot saved = service.snapshot();
        rejects(() -> service.planMob(missingPlayer, "minecraft:zombie", missing.items), "rare drop missing blocks entire submission");
        equal(before, missing.items, "insufficient ingredients consume none"); equal(saved, service.snapshot(), "insufficient ingredients complete none");
        missing.items.clear(); missing.add(List.of(new Item("minecraft:arrow",1),new Item("minecraft:bone",1),new Item("minecraft:tipped_arrow",1)));
        rejects(() -> service.planMob(missingPlayer, "minecraft:bogged", missing.items), "untyped/wrong potion arrows cannot satisfy exact poison arrows");
    }
    private static void cropFlow(CollectionCatalog catalog) {
        CollectionService service = new CollectionService(catalog); UUID player = UUID.randomUUID(); Inventory inventory = new Inventory();
        equal(List.of(new Item("minecraft:potato", 11)), catalog.cropRequirements("minecraft:potato"), "potato seed and mature crop sum to eleven");
        inventory.items.put("minecraft:potato", 10);
        rejects(() -> service.planCrop(player,"minecraft:potato",inventory.items), "ten potatoes cannot satisfy eleven");
        int index = 0;
        for (CollectionCatalog.Crop crop : catalog.crops()) {
            inventory.items.clear(); inventory.add(catalog.cropRequirements(crop.id()));
            CollectionService.Plan plan = service.planCrop(player,crop.id(),inventory.items);
            if (++index == 10) {
                equal(22,plan.rewards().size(), "tenth crop awards every supported seed");
                for (Item reward : plan.rewards()) equal(1,reward.count(),"all-seed reward exactly one");
            } else equal(List.of(),plan.rewards(),"all-seed milestone granted only on tenth crop");
            apply(service,plan,inventory);
            check(service.progress(player).pendingSeedChoices().contains(crop.id()),"crop choice remains available after submission");
            rejects(() -> service.planCrop(player,crop.id(),inventory.items),"crop cannot submit again");
            rejects(() -> service.planSeedChoice(player,crop.id(),crop.id()),"own seed forbidden");
            rejects(() -> service.planSeedChoice(player,crop.id(),"minecraft:unknown"),"unknown seed forbidden");
            String other = crop.id().equals("minecraft:wheat") ? "minecraft:carrot" : "minecraft:wheat";
            CollectionService.Plan choice = service.planSeedChoice(player,crop.id(),other);
            equal(List.of(new Item(catalog.crop(other).seed(),5),new Item("minecraft:bone",3)),choice.rewards(),"other crop awards five seeds and three bones");
            apply(service,choice,inventory);
            rejects(() -> service.planSeedChoice(player,crop.id(),other),"choice cannot be claimed twice");
        }
        check(!service.progress(player).firstHuntComplete(),"agriculture does not require hunting");
        equal(22,service.progress(player).cropCount(),"each cultivated crop counts once");
        equal(22,service.progress(player).claimedSeedChoices().size(),"every crop choice claimed");
        check(service.progress(player).pendingSeedChoices().isEmpty(),"no choice remains after all claims");
        check(service.progress(player).cropMilestoneClaimed(),"crop milestone persisted");
        equal(44,inventory.receipts.size(),"twenty-two submissions plus twenty-two seed choices");
    }
    private static void plansAndPlayerIsolation(CollectionCatalog catalog) {
        CollectionService service = new CollectionService(catalog); UUID first = UUID.randomUUID(),second = UUID.randomUUID(); Inventory inv = new Inventory();
        inv.add(catalog.cropRequirements("minecraft:wheat"));
        CollectionService.Plan old = service.planCrop(first,"minecraft:wheat",inv.items);
        CollectionService.Plan fresh = service.planCrop(first,"minecraft:wheat",inv.items);
        rejects(() -> service.begin(old),"replaced plan cannot execute");
        CollectionService.Pending pending = service.begin(fresh);
        rejects(() -> service.begin(fresh),"plan replay blocked while pending");
        rejects(() -> service.planCrop(first,"minecraft:wheat",inv.items),"pending inventory exchange blocks new input");
        rejects(() -> new CollectionService(catalog).begin(fresh),"foreign service cannot execute forged plan");
        check(!service.progress(second).submittedCrops().contains("minecraft:wheat"),"players remain isolated");
        check(service.cancel(first,pending.transactionId()),"unapplied exchange can cancel");
        rejects(() -> service.begin(fresh),"canceled plan cannot be reused");
        CollectionService.Plan retry = service.planCrop(first,"minecraft:wheat",inv.items); apply(service,retry,inv);
        check(service.progress(first).submittedCrops().contains("minecraft:wheat"),"fresh retry completes after cancel");
        check(!service.finish(first,retry.transactionId()),"duplicate finish is harmless");
        CollectionService.Plan next = service.planSeedChoice(first,"minecraft:wheat","minecraft:carrot"); service.begin(next);
        rejects(() -> service.finish(first,UUID.randomUUID()),"wrong transaction cannot finish pending choice");
        rejects(() -> service.cancel(first,UUID.randomUUID()),"wrong transaction cannot cancel pending choice");
        check(service.pending(second).isEmpty(),"second player has no first player's exchange");
    }
    private static void pendingRecoveryAndPersistence(CollectionCatalog catalog) throws Exception {
        Path directory = Files.createTempDirectory("fsrpg-collection-test-"); Path file = directory.resolve("收集进度.json"); CollectionStore store = new CollectionStore(file,JSON);
        UUID player = UUID.randomUUID(); CollectionService service = new CollectionService(catalog); Inventory inventory = new Inventory();
        try {
            equal(0,store.load().players().size(),"absent world data begins empty");
            service.firstKill(player,"minecraft:zombie"); inventory.add(catalog.mobRequirements("minecraft:zombie"));
            CollectionService.Plan plan = service.planMob(player,"minecraft:zombie",inventory.items); CollectionService.Pending exchange = service.begin(plan); store.save(service.snapshot());
            equal(0,service.progress(player).mobCount(),"prepared exchange is not complete before inventory commit");
            CollectionService recovered = new CollectionService(catalog); recovered.restore(store.load());
            equal(exchange,recovered.pending(player).orElseThrow(),"unapplied exchange survives saved process reload");
            inventory.applyOnce(exchange); Map<String,Integer> after = Map.copyOf(inventory.items);
            // Crash after player inventory receipt, before collection finish: applyOnce observes receipt.
            CollectionService afterPlayerSave = new CollectionService(catalog); afterPlayerSave.restore(store.load());
            inventory.applyOnce(afterPlayerSave.pending(player).orElseThrow()); equal(after,inventory.items,"receipt prevents duplicate debit and reward on crash recovery");
            afterPlayerSave.finish(player,exchange.transactionId()); store.save(afterPlayerSave.snapshot());
            service.restore(store.load()); equal(1,service.progress(player).mobCount(),"completion survives collection finish and reload");
            rejects(() -> service.planMob(player,"minecraft:zombie",inventory.items),"saved complete creature cannot farm again");
            inventory.add(catalog.cropRequirements("minecraft:potato")); apply(service,service.planCrop(player,"minecraft:potato",inventory.items),inventory); store.save(service.snapshot());
            recovered.restore(store.load()); check(recovered.progress(player).pendingSeedChoices().contains("minecraft:potato"),"unclaimed seed choice survives reopening");
            CollectionService.Plan choice = recovered.planSeedChoice(player,"minecraft:potato","minecraft:carrot"); recovered.begin(choice); store.save(recovered.snapshot());
            inventory.failBeforeCommit = true; Map<String,Integer> beforeFailure = Map.copyOf(inventory.items);
            rejects(() -> inventory.applyOnce(recovered.pending(player).orElseThrow()),"player persistence failure aborts trade");
            equal(beforeFailure,inventory.items,"failed player save restores inventory before debit/reward");
            check(!inventory.receipts.contains(choice.transactionId()),"failed player save retains no false receipt");
            // Leave pending for retry instead of cancel: restart, then retry exactly the same transaction.
            service.restore(store.load()); inventory.applyOnce(service.pending(player).orElseThrow()); service.finish(player,choice.transactionId()); store.save(service.snapshot());
            recovered.restore(store.load()); check(recovered.progress(player).claimedSeedChoices().contains("minecraft:potato"),"pending reward retry completes across restart");
            check(recovered.pending().isEmpty(),"finished outbox cleared");
            CollectionCatalog.Definition d = catalog.definition();
            CollectionCatalog smaller = CollectionCatalog.of(new CollectionCatalog.Definition(1,d.firstHuntRequired(),d.mobRewardMultiplier(),d.cropSeedRequired(),d.cropMatureRequired(),d.seedChoiceCount(),d.seedChoiceBones(),d.mobs().subList(0,20),d.crops(),d.mobMilestones(),d.cropMilestone()));
            CollectionService changed = new CollectionService(smaller); changed.restore(store.load());
            check(changed.progress(player).submittedMobs().contains("minecraft:zombie"),"removed stable creature id preserved across config reload");
            rejects(() -> changed.snapshot().players().clear(),"snapshot map immutable");
            rejects(() -> changed.progress(player).submittedCrops().clear(),"snapshot progress sets immutable");
            try (var paths = Files.list(directory)) { equal(1L,paths.count(),"atomic save leaves no temporary files"); }
            Files.writeString(file,"{bad",StandardCharsets.UTF_8); rejectsChecked(store::load,"corrupt file rejected without wiping progress");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }
    private static void invalidSavedState(CollectionCatalog catalog) {
        CollectionService service = new CollectionService(catalog); UUID player = UUID.randomUUID(); CollectionService.Snapshot before = service.snapshot();
        rejects(() -> service.restore(new CollectionService.Snapshot(99,Map.of(),Map.of())),"unknown snapshot schema");
        CollectionService.State impossible = new CollectionService.State(1,"",Set.of(),Set.of(),Set.of("minecraft:wheat"),Set.of(),Set.of(),false);
        rejects(() -> service.restore(new CollectionService.Snapshot(1,Map.of(player,impossible),Map.of())),"pending choice needs completed source crop");
        equal(before,service.snapshot(),"invalid snapshot keeps live state intact");
        rejects(() -> new CollectionService.State(0,"",null,Set.of(),Set.of(),Set.of(),Set.of(),false),"missing saved set rejected");
        rejects(() -> service.firstKill(player,"zombie"),"unqualified trusted monster id rejected");
        CollectionService.State milestones = new CollectionService.State(1,"minecraft:zombie",Set.of(),Set.of(),Set.of(),Set.of(),Set.of(5),false);
        rejects(() -> service.restore(new CollectionService.Snapshot(1,Map.of(player,milestones),Map.of())),"claimed milestone needs enough completed kinds");
    }
    private static final class Inventory {
        final Map<String,Integer> items = new LinkedHashMap<>(); final Set<UUID> receipts = new LinkedHashSet<>(); boolean failBeforeCommit;
        void add(List<Item> list) { for(Item item:list) items.merge(item.key(),item.count(),Math::addExact); }
        void applyOnce(CollectionService.Pending p) {
            if(receipts.contains(p.transactionId()))return;
            if(!CollectionService.hasRequirements(p.requirements(),items))throw new IllegalStateException("inventory changed after plan");
            Map<String,Integer> candidate = new LinkedHashMap<>(items);
            for(Item item:p.requirements()) { int next=candidate.get(item.key())-item.count();if(next==0)candidate.remove(item.key());else candidate.put(item.key(),next); }
            for(Item item:p.rewards())candidate.merge(item.key(),item.count(),Math::addExact);
            if(failBeforeCommit){failBeforeCommit=false;throw new IllegalStateException("simulated inventory/receipt save failure");}
            items.clear();items.putAll(candidate);receipts.add(p.transactionId());
        }
    }
    private static void apply(CollectionService service,CollectionService.Plan plan,Inventory inventory) { CollectionService.Pending p=service.begin(plan);inventory.applyOnce(p);check(service.finish(plan.player(),p.transactionId()),"exchange finished once"); }
    private static Set<String> keys(List<Item> list) { Set<String> result=new LinkedHashSet<>();for(Item item:list)result.add(item.key());return result; }
    private static Set<String> ironKeys(List<Item> list,boolean tools) { Set<String> result=new LinkedHashSet<>();Set<String> suffixes=tools?Set.of("sword","pickaxe","axe","shovel","hoe"):Set.of("helmet","chestplate","leggings","boots");for(Item item:list)for(String suffix:suffixes)if(item.id().equals("minecraft:iron_"+suffix))result.add(item.id());return result; }
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { checks++;if(!Objects.equals(expected,actual))throw new AssertionError(message+": expected "+expected+", actual "+actual); }
    private static void rejects(Runnable action,String message) { checks++;try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(message+": did not reject"); }
    private interface Checked {void run()throws Exception;}
    private static void rejectsChecked(Checked action,String message) { checks++;try{action.run();}catch(Exception expected){return;}throw new AssertionError(message+": did not reject"); }
}

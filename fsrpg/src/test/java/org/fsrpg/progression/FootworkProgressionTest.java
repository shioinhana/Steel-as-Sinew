package org.fsrpg.progression;

import com.google.gson.*;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.progression.ProgressionService.*;
import org.fsrpg.progression.SkillTreeCatalog.*;

/**
 * Pure-Java coverage of the migrated footwork branch: shipped catalog shape, old-config
 * compatibility, selection credits, prerequisites, exclusivity, stat gates and persistence/decay.
 */
public final class FootworkProgressionTest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long DAY = 20000;
    private static int checks;
    public static void main(String[] args) throws Exception {
        SkillTreeCatalog catalog = SkillTreeCatalog.bundled(JSON);
        catalogShape(catalog);
        parsingCompatibility(catalog);
        schoolLearning(catalog);
        techniqueCredits(catalog);
        requirementGates(catalog);
        persistenceAndDecay(catalog);
        System.out.println("FootworkProgressionTest passed: " + checks + " checks");
    }
    private static final List<String> FOOTWORK_IDS = List.of("rabbit", "flying_swan", "cloud_step", "cloud_step_ii",
            "cloud_step_iii", "light_body", "light_body_ii", "light_body_iii");
    private static void catalogShape(SkillTreeCatalog c) {
        equal(25, c.nodes().size(), "bundled catalog adds the eight legacy footwork nodes");
        equal(FootworkRules.defaults(), c.rules().footwork(), "footwork rules default to the published legacy values");
        equal(8L, c.nodes().stream().filter(n -> n.tree().equals("footwork")).count(), "eight footwork nodes");
        for (String id : FOOTWORK_IDS) {
            Node node = c.node(id);
            equal("footwork", node.tree(), id + " lives on the footwork page");
            check(!node.automatic(), id + " is a manual choice");
            equal(new Cost(0, 0, 0), node.cost(), id + " never deducts mind/body/footwork points");
            check(node.skills().isEmpty(), id + " does not gate a weapon skill definition");
        }
        Node rabbit = c.node("rabbit"), swan = c.node("flying_swan");
        equal(Effect.RABBIT, rabbit.effect(), "Rabbit effect mapping");
        equal(Effect.FLYING_SWAN, swan.effect(), "Flying Swan effect mapping");
        equal("footwork_style", rabbit.exclusiveGroup(), "Rabbit is an exclusive school");
        equal(rabbit.exclusiveGroup(), swan.exclusiveGroup(), "the two schools share one exclusive group");
        equal(new Requirements(20, 15, 100, 0, false, false), rabbit.requirements(), "Rabbit old stat gates");
        equal(new Requirements(30, 20, 200, 0, false, false), swan.requirements(), "Flying Swan old stat gates");
        check(rabbit.prerequisites().isEmpty() && swan.prerequisites().isEmpty(), "schools have no prerequisite");
        equal(new Requirements(15, 15, 150, 0, false, false), c.node("cloud_step").requirements(), "Cloud Step gates");
        equal(new Requirements(35, 30, 300, 0, false, false), c.node("cloud_step_ii").requirements(), "Cloud Step II gates");
        equal(new Requirements(55, 45, 450, 0, false, false), c.node("cloud_step_iii").requirements(), "Cloud Step III gates");
        equal(new Requirements(20, 10, 150, 0, false, false), c.node("light_body").requirements(), "Light Body gates");
        equal(new Requirements(40, 20, 300, 0, false, false), c.node("light_body_ii").requirements(), "Light Body II gates");
        equal(new Requirements(65, 30, 450, 0, false, false), c.node("light_body_iii").requirements(), "Light Body III gates");
        equal(List.of("cloud_step"), c.node("cloud_step_ii").prerequisites(), "Cloud Step II needs Cloud Step");
        equal(List.of("cloud_step_ii"), c.node("cloud_step_iii").prerequisites(), "Cloud Step III needs Cloud Step II");
        equal(List.of("light_body"), c.node("light_body_ii").prerequisites(), "Light Body II needs Light Body");
        equal(List.of("light_body_ii"), c.node("light_body_iii").prerequisites(), "Light Body III needs Light Body II");
        check(c.node("cloud_step").prerequisites().isEmpty() && c.node("light_body").prerequisites().isEmpty(),
                "the two technique chains start independently");
        equal(Effect.CLOUD_STEP, c.node("cloud_step").effect(), "Cloud Step effect");
        equal(Effect.CLOUD_STEP_II, c.node("cloud_step_ii").effect(), "Cloud Step II effect");
        equal(Effect.CLOUD_STEP_III, c.node("cloud_step_iii").effect(), "Cloud Step III effect");
        equal(Effect.LIGHT_BODY, c.node("light_body").effect(), "Light Body effect");
        equal(Effect.LIGHT_BODY_II, c.node("light_body_ii").effect(), "Light Body II effect");
        equal(Effect.LIGHT_BODY_III, c.node("light_body_iii").effect(), "Light Body III effect");
        check(c.node("light_body_ii").description().contains("摔落"), "Light Body II advertises the old fall-damage cancellation");
        c.validateReferences(id -> true, id -> true);
    }
    private static void parsingCompatibility(SkillTreeCatalog c) {
        JsonObject shipped = bundledJson();
        SkillTreeCatalog parsed = SkillTreeCatalog.parse(shipped, JSON);
        equal(25, parsed.nodes().size(), "shipped defaults parse with every footwork node");
        equal(FootworkRules.defaults(), parsed.rules().footwork(), "shipped defaults carry the legacy parameters");
        JsonObject oldRules = shipped.deepCopy();
        oldRules.getAsJsonObject("rules").remove("footwork");
        SkillTreeCatalog defaults = SkillTreeCatalog.parse(oldRules, JSON);
        equal(FootworkRules.defaults(), defaults.rules().footwork(), "pre-0.9 file without rules.footwork uses legacy defaults");
        JsonObject oldFile = oldRules.deepCopy();
        removeFootworkNodes(oldFile);
        SkillTreeCatalog legacy = SkillTreeCatalog.parse(oldFile, JSON);
        equal(17, legacy.nodes().size(), "pre-0.9 file keeps exactly its old node set");
        check(legacy.find("rabbit").isEmpty(), "old file is not silently given footwork nodes");
        JsonObject unknown = shipped.deepCopy();
        unknown.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("clientFreeStyles", true);
        invalid(unknown, "unknown footwork rule field rejected");
        JsonObject tooLarge = shipped.deepCopy();
        tooLarge.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("flashThrustDamageShare", 2);
        invalid(tooLarge, "flash share above one rejected");
        JsonObject zeroInterval = shipped.deepCopy();
        zeroInterval.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("jumpIntervalTicks", 0);
        invalid(zeroInterval, "zero jump interval rejected");
        JsonObject hugeDistance = shipped.deepCopy();
        hugeDistance.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("rabbitDistance", 1.0E300);
        invalid(hugeDistance, "absurd dash distance rejected");
        JsonObject duplicateEffect = shipped.deepCopy();
        JsonObject duplicate = node(duplicateEffect, "rabbit").deepCopy();
        duplicate.addProperty("id", "rabbit_two");
        duplicateEffect.getAsJsonArray("nodes").add(duplicate);
        invalid(duplicateEffect, "duplicate footwork effect rejected");
        JsonObject wrongTree = shipped.deepCopy();
        node(wrongTree, "rabbit").addProperty("tree", "rapier");
        invalid(wrongTree, "footwork effect outside the footwork tree rejected");
        JsonObject automatic = shipped.deepCopy();
        node(automatic, "light_body").addProperty("automatic", true);
        invalid(automatic, "footwork choices cannot become automatic");
        JsonObject wrongGroup = shipped.deepCopy();
        node(wrongGroup, "rabbit").addProperty("exclusiveGroup", "");
        invalid(wrongGroup, "school cannot drop its exclusive group");
        JsonObject groupOnTechnique = shipped.deepCopy();
        node(groupOnTechnique, "cloud_step").addProperty("exclusiveGroup", "footwork_style");
        invalid(groupOnTechnique, "techniques cannot claim the school exclusive group");
        JsonObject selfCycle = shipped.deepCopy();
        node(selfCycle, "cloud_step_ii").getAsJsonArray("prerequisites").add("cloud_step_ii");
        invalid(selfCycle, "cyclic footwork prerequisite rejected");
        JsonObject fractional = shipped.deepCopy();
        fractional.getAsJsonObject("rules").getAsJsonObject("footwork").addProperty("rabbitInvulnerabilityTicks", 8.5);
        invalid(fractional, "fractional footwork ticks rejected");
    }
    private static void schoolLearning(SkillTreeCatalog c) {
        ProgressionService rabbit = fixture(c, 20, 15, 100, true);
        FootworkView before = rabbit.footworkView(A);
        check(before.enabled(), "footwork view enabled once mind/body/footwork are unlocked");
        equal(FootworkStyle.NONE, before.style(), "no school selected initially");
        LearnResult learned = rabbit.learn(A, "rabbit");
        check(learned.success(), "Rabbit is learnable at its exact legacy gates: " + learned.reason());
        equal(FootworkStyle.RABBIT, rabbit.footworkView(A).style(), "Rabbit becomes the active school");
        equal(new Points(20, 20), rabbit.view(A).mind(), "school choice deducts no mind points");
        equal(new Points(15, 15), rabbit.view(A).body(), "school choice deducts no body points");
        equal(new Points(100, 100), rabbit.view(A).footwork(), "school choice deducts no footwork points");
        equal(0, rabbit.footworkView(A).selectionsUsed(), "school choice does not consume a selection credit");
        equal(0, rabbit.footworkView(A).selectionsEarned(), "100 total footwork has not earned a credit yet");
        Snapshot locked = rabbit.snapshot();
        LearnResult conflict = rabbit.learn(A, "flying_swan");
        check(!conflict.success(), "the other school is permanently exclusive");
        check(conflict.reason().contains("互斥"), "exclusivity reason is reported");
        equal(locked, rabbit.snapshot(), "exclusive rejection changes nothing");
        check(!rabbit.learn(A, "rabbit").success(), "duplicate school selection rejected");
        ProgressionService swan = fixture(c, 30, 20, 200, true);
        check(swan.learn(A, "flying_swan").success(), "Flying Swan learnable at its old gates");
        equal(FootworkStyle.FLYING_SWAN, swan.footworkView(A).style(), "Flying Swan active school");
        check(!swan.learn(A, "rabbit").success(), "Rabbit cannot be added after Flying Swan");
        ProgressionService lockedService = fixture(c, 20, 15, 100, false);
        check(!lockedService.footworkView(A).enabled(), "footwork view disabled before cultivation unlock");
        check(!lockedService.learn(A, "rabbit").success(), "no footwork learning before the branch unlocks");
    }
    private static void techniqueCredits(SkillTreeCatalog c) {
        ProgressionService one = fixture(c, 15, 15, 150, true);
        FootworkView view = one.footworkView(A);
        equal(1, view.selectionsEarned(), "150 total footwork earns one selection");
        equal(1, view.selectionsAvailable(), "stored credit available");
        check(one.learn(A, "cloud_step").success(), "Cloud Step purchases with one selection credit");
        equal(1, one.footworkView(A).cloudTier(), "Cloud Step tier one");
        equal(1, one.footworkView(A).selectionsUsed(), "credit consumed");
        equal(0, one.footworkView(A).selectionsAvailable(), "no credit left");
        equal(new Points(15, 15), one.view(A).mind(), "technique purchase deducts no mind points");
        equal(new Points(15, 15), one.view(A).body(), "technique purchase deducts no body points");
        equal(new Points(150, 150), one.view(A).footwork(), "technique purchase deducts no footwork points");
        Snapshot spent = one.snapshot();
        LearnResult noCredit = one.learn(A, "light_body");
        check(!noCredit.success(), "second technique needs another 150 points");
        check(noCredit.reason().contains("选学额度"), "selection credit reason reported");
        equal(spent, one.snapshot(), "failed technique purchase changes nothing");
        ProgressionService three = fixture(c, 65, 45, 450, true);
        equal(3, three.footworkView(A).selectionsEarned(), "450 total footwork earns three selections");
        check(three.learn(A, "cloud_step").success(), "Cloud Step bought");
        check(three.learn(A, "cloud_step_ii").success(), "Cloud Step II bought after its prerequisite");
        check(three.learn(A, "cloud_step_iii").success(), "Cloud Step III bought");
        equal(3, three.footworkView(A).cloudTier(), "three air jumps unlocked");
        equal(0, three.footworkView(A).selectionsAvailable(), "all three credits spent");
        check(!three.learn(A, "light_body").success(), "fourth technique requires 600 total footwork");
        ProgressionService prereq = fixture(c, 35, 30, 300, true);
        equal(2, prereq.footworkView(A).selectionsEarned(), "300 total footwork earns two selections");
        LearnResult missing = prereq.learn(A, "cloud_step_ii");
        check(!missing.success(), "Cloud Step II cannot skip its prerequisite");
        check(missing.reason().contains("前置"), "prerequisite reason reported");
        equal(2, prereq.footworkView(A).selectionsAvailable(), "failed prerequisite keeps the credit");
        check(prereq.learn(A, "cloud_step").success(), "prerequisite bought first");
        check(prereq.learn(A, "cloud_step_ii").success(), "then Cloud Step II");
        equal(2, prereq.footworkView(A).cloudTier(), "Cloud Step II tier");
        ProgressionService light = fixture(c, 40, 20, 300, true);
        check(light.learn(A, "light_body").success(), "Light Body bought");
        check(light.learn(A, "light_body_ii").success(), "Light Body II bought after its prerequisite");
        equal(2, light.footworkView(A).lightTier(), "Light Body II tier");
        check(light.footworkView(A).lightTier() >= 2,
                "Light Body II tier drives both the +10m/s jump impulse and the server fall-damage cancellation gate");
        equal(0, light.footworkView(A).selectionsAvailable(), "two credits fund both Light Body steps");
        ProgressionService maxed = fixture(c, 65, 45, 600, true);
        equal(4, maxed.footworkView(A).selectionsEarned(), "600 footwork cap yields four selections");
        check(maxed.learn(A, "rabbit").success(), "school choice at the cap");
        equal(4, maxed.footworkView(A).selectionsAvailable(), "school choice never consumes selection credits");
        check(!maxed.learn(A, "flying_swan").success(), "school exclusivity still applies at the cap");
    }
    private static void requirementGates(SkillTreeCatalog c) {
        ProgressionService mind = fixture(c, 19, 15, 100, true);
        LearnResult result = mind.learn(A, "rabbit");
        check(!result.success() && result.reason().contains("心法"), "Rabbit requires 20 mind");
        ProgressionService body = fixture(c, 20, 14, 100, true);
        result = body.learn(A, "rabbit");
        check(!result.success() && result.reason().contains("煅体"), "Rabbit requires 15 body");
        ProgressionService foot = fixture(c, 20, 15, 99, true);
        result = foot.learn(A, "rabbit");
        check(!result.success() && result.reason().contains("步法"), "Rabbit requires 100 footwork");
        ProgressionService swanMind = fixture(c, 29, 20, 200, true);
        check(!swanMind.learn(A, "flying_swan").success(), "Flying Swan requires 30 mind");
        ProgressionService swanBody = fixture(c, 30, 19, 200, true);
        check(!swanBody.learn(A, "flying_swan").success(), "Flying Swan requires 20 body");
        ProgressionService swanFoot = fixture(c, 30, 20, 199, true);
        check(!swanFoot.learn(A, "flying_swan").success(), "Flying Swan requires 200 footwork");
        ProgressionService almost = fixture(c, 15, 15, 149, true);
        result = almost.learn(A, "cloud_step");
        check(!result.success() && result.reason().contains("步法"), "Cloud Step requires 150 footwork");
        equal(0, almost.footworkView(A).selectionsEarned(), "no credit below the first interval");
        ProgressionService cloudGates = fixture(c, 34, 30, 300, true);
        check(!cloudGates.learn(A, "cloud_step_ii").success(), "Cloud Step II mind gate");
        ProgressionService lightGates = fixture(c, 40, 19, 300, true);
        check(!lightGates.learn(A, "light_body_ii").success(), "Light Body II body gate");
    }
    private static void persistenceAndDecay(SkillTreeCatalog c) throws Exception {
        ProgressionService service = fixture(c, 65, 45, 450, true);
        check(service.learn(A, "flying_swan").success(), "school bought before saving");
        check(service.learn(A, "cloud_step").success(), "first technique before saving");
        check(service.learn(A, "cloud_step_ii").success(), "second technique before saving");
        Path folder = Files.createTempDirectory("fsrpg-footwork-test-");
        Path file = folder.resolve("skill_trees.json");
        try {
            ProgressionStore store = new ProgressionStore(file, JSON);
            store.save(service.snapshot());
            ProgressionService loaded = new ProgressionService(c);
            loaded.restore(store.load());
            equal(service.snapshot(), loaded.snapshot(), "footwork learning survives an atomic save");
            equal(FootworkStyle.FLYING_SWAN, loaded.footworkView(A).style(), "school survives reload");
            equal(2, loaded.footworkView(A).cloudTier(), "technique tier survives reload");
            equal(3, loaded.footworkView(A).selectionsEarned(), "earned selections are recomputed from total footwork");
            equal(2, loaded.footworkView(A).selectionsUsed(), "used selections are recomputed from learned nodes");
            check(!loaded.learn(A, "rabbit").success(), "mutual exclusion survives reload");
            check(loaded.snapshot().players().get(A).learnedNodes().contains("flying_swan"), "school stable ID saved");
            JsonObject conflict = JSON.toJsonTree(loaded.snapshot()).getAsJsonObject();
            player(conflict).getAsJsonArray("learnedNodes").add("rabbit");
            rejects(() -> loaded.restore(JSON.fromJson(conflict, Snapshot.class)), "save with both schools rejected atomically");
            equal(FootworkStyle.FLYING_SWAN, loaded.footworkView(A).style(), "failed restore keeps the previous state");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
        ProgressionService decayed = fixture(c, 30, 20, 200, true);
        check(decayed.learn(A, "flying_swan").success(), "school bought before decay");
        decayed.maintain(A, DAY + 1);
        equal(FootworkStyle.NONE, decayed.footworkView(A).style(), "daily footwork decay revokes the school below its gate");
        equal(1, decayed.footworkView(A).selectionsEarned(), "earned selections follow the decayed total");
        ProgressionService tierDecay = fixture(c, 65, 45, 450, true);
        check(tierDecay.learn(A, "cloud_step").success(), "Cloud Step before decay");
        check(tierDecay.learn(A, "cloud_step_ii").success(), "Cloud Step II before decay");
        check(tierDecay.learn(A, "cloud_step_iii").success(), "Cloud Step III before decay");
        tierDecay.maintain(A, DAY + 1);
        equal(2, tierDecay.footworkView(A).cloudTier(), "decay below 450 revokes only Cloud Step III");
        equal(2, tierDecay.footworkView(A).selectionsUsed(), "revoked credit is available again");
        equal(0, tierDecay.footworkView(A).selectionsAvailable(), "decayed total still funds the two remaining techniques");
        ProgressionService migrated = new ProgressionService(c);
        migrated.initializeAptitudes(B, 0, 0, DAY);
        equal(0, migrated.footworkView(B).selectionsEarned(), "a new save gets no free selection credits");
        equal(0, migrated.footworkView(B).selectionsAvailable(), "nor any stored credit");
    }
    private static ProgressionService fixture(SkillTreeCatalog catalog, int mind, int body, int footwork, boolean footworkUnlocked) {
        ProgressionService service = new ProgressionService(catalog);
        service.initializeAptitudes(A, 0, 0, DAY);
        if (footworkUnlocked) {
            service.migrateExistingCombat(A);
            service.onSleep(A);
            JsonObject save = JSON.toJsonTree(service.snapshot()).getAsJsonObject();
            JsonObject player = player(save);
            player.add("mind", JSON.toJsonTree(new Points(mind, mind)));
            player.add("body", JSON.toJsonTree(new Points(body, body)));
            player.add("footwork", JSON.toJsonTree(new Points(footwork, footwork)));
            player.addProperty("bodyUnlocked", true);
            player.addProperty("footworkUnlocked", true);
            service.restore(JSON.fromJson(save, Snapshot.class));
        } else {
            JsonObject save = JSON.toJsonTree(service.snapshot()).getAsJsonObject();
            JsonObject player = player(save);
            player.add("mind", JSON.toJsonTree(new Points(mind, mind)));
            player.add("body", JSON.toJsonTree(new Points(body, body)));
            player.add("footwork", JSON.toJsonTree(new Points(footwork, footwork)));
            player.addProperty("mindUnlocked", true);
            player.addProperty("bodyUnlocked", true);
            service.restore(JSON.fromJson(save, Snapshot.class));
        }
        return service;
    }
    private static JsonObject player(JsonObject save) { return save.getAsJsonObject("players").getAsJsonObject(A.toString()); }
    private static JsonObject bundledJson() {
        try (InputStream stream = FootworkProgressionTest.class.getResourceAsStream("/defaults/skill_trees.json")) {
            if (stream == null) throw new AssertionError("missing bundled skill_trees.json");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
    private static void removeFootworkNodes(JsonObject config) {
        JsonArray nodes = config.getAsJsonArray("nodes");
        for (int i = nodes.size() - 1; i >= 0; i--)
            if (nodes.get(i).getAsJsonObject().get("tree").getAsString().equals("footwork")) nodes.remove(i);
    }
    private static JsonObject node(JsonObject config, String id) {
        for (JsonElement e : config.getAsJsonArray("nodes"))
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError(id);
    }
    private static void invalid(JsonObject json, String message) {
        rejects(() -> SkillTreeCatalog.parse(json, JSON), message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable call, String message) {
        checks++;
        try { call.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
}

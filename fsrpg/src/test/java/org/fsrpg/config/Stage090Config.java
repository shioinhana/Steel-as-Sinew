package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.creature.GuardCueSettings;
import org.fsrpg.progression.SkillTreeCatalog;

/**
 * Stage a full copy of the official 0.8.x configuration through the real 0.9.0 migrations.
 * The deployment script independently verifies that only the reported migration files change.
 */
public final class Stage090Config {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath().normalize();
        Path report = Path.of(args[1]).toAbsolutePath().normalize();
        if (!dir.toString().contains("verification") || !dir.getFileName().toString().equals("fsrpg"))
            throw new IllegalArgumentException("Expected an isolated verification/fsrpg candidate");
        Map<String, String> before = hashes(dir);
        JsonObject previous = new JsonObject();
        for (String name : List.of("skills.json", "styles.json", "skill_trees.json", "guard_cues.json", "archery.json"))
            previous.add(name, read(dir.resolve(name)));
        var definitions = new RpgConfig(dir).reload();
        SkillTreeCatalog trees = definitions.skillTrees();
        GuardCueSettings cues = GuardCueSettings.read(dir.resolve("guard_cues.json"));
        Map<String, String> after = hashes(dir);
        List<String> changed = new ArrayList<>();
        for (var entry : after.entrySet())
            if (!entry.getValue().equals(before.get(entry.getKey()))) changed.add(entry.getKey());
        changed.sort(String::compareTo);
        for (String name : before.keySet())
            if (!after.containsKey(name)) throw new IllegalStateException("Staged configuration lost a file: " + name);

        JsonArray footwork = new JsonArray();
        for (String id : FootworkSkillMigration.NODE_IDS) {
            var node = trees.node(id);
            if (node == null) throw new IllegalStateException("Staged skill tree lacks footwork node: " + id);
            if (!node.tree().equals("footwork") || node.automatic())
                throw new IllegalStateException("Staged footwork node is not a manual footwork node: " + id);
            footwork.add(id);
        }
        var rules = trees.rules().footwork();
        if (rules.selectionPoints() <= 0 || rules.rabbitDistance() <= 0 || rules.jumpIntervalTicks() <= 0)
            throw new IllegalStateException("Staged footwork rules are invalid");
        if (!cues.timingMode().equals("windup_fraction") || cues.windowCloseFraction() != .8)
            throw new IllegalStateException("Staged guard cue close fraction is not four fifths");
        var archery = definitions.archery();
        if (archery.chuanTangFengBlockPierce() != 3 || archery.chuanTangFengBlockSlow() < 0
                || archery.chuanTangFengBlockSlow() >= 1 || archery.chuanTangFengEntityPierce() != 127)
            throw new IllegalStateException("Staged archery block-penetration rules are invalid");

        JsonObject current = new JsonObject();
        for (var entry : previous.entrySet()) current.add(entry.getKey(), read(dir.resolve(entry.getKey())));
        JsonObject output = new JsonObject();
        output.addProperty("passed", true);
        output.addProperty("version", "0.9.0");
        output.addProperty("directory", dir.toString());
        output.addProperty("skillNodeCount", trees.nodes().size());
        output.add("footworkNodes", footwork);
        output.addProperty("guardCueCloseFraction", cues.windowCloseFraction());
        output.addProperty("guardCueOpenFraction", cues.windowOpenFraction());
        output.addProperty("guardCueFlashFraction", cues.flashAtFraction());
        output.addProperty("archeryBlockPierce", archery.chuanTangFengBlockPierce());
        output.addProperty("archeryBlockSlow", archery.chuanTangFengBlockSlow());
        output.addProperty("archeryEntityPierce", archery.chuanTangFengEntityPierce());
        output.add("beforeSha256", RpgConfig.JSON.toJsonTree(before));
        output.add("afterSha256", RpgConfig.JSON.toJsonTree(after));
        JsonArray changedArray = new JsonArray();
        for (String name : changed) changedArray.add(name);
        output.add("changedFiles", changedArray);
        output.add("previous", previous);
        output.add("current", current);
        Files.createDirectories(report.getParent());
        RpgConfig.atomicWrite(report, RpgConfig.JSON.toJson(output));
        System.out.println("Staged 0.9.0 configuration: " + trees.nodes().size() + " skill nodes, " + footwork.size()
                + " footwork nodes, cue close=" + cues.windowCloseFraction() + ", changed=" + changed + "; " + dir);
    }

    private static JsonElement read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file));
    }

    private static Map<String, String> hashes(Path dir) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                result.put(dir.relativize(file).toString().replace('\\', '/'), digest(file));
        }
        return result;
    }

    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

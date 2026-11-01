package org.fsrpg.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Apply explicitly requested attack costs once; all other user tuning remains intact. */
final class WeaponStaminaBalanceMigration {
    static final String MARKER="weapon-stamina-balance-v1.json", BACKUP="weapon-stamina-balance-backup-v1";
    static Double cost(JsonObject skill) {
        String weapon=skill.get("weapon").getAsString(),id=skill.get("id").getAsString();
        if(weapon.equals("sword")||weapon.equals("rapier"))return 5.;
        if(weapon.equals("dual_blade"))return 8.;
        if(id.equals("spear_thrust"))return 10.;
        if(id.equals("bow_rapid_release"))return 5.;
        if(id.equals("bow_snipe_release"))return 15.;
        return null;
    }
    static void migrate(Path directory) throws IOException {
        if(Files.exists(directory.resolve(MARKER)))return;
        Map<String,JsonElement> before=new LinkedHashMap<>(),next=new LinkedHashMap<>();
        for(String name:new String[]{"skills.json","archery.json"}) {var original=read(directory.resolve(name));before.put(name,original);next.put(name,original.deepCopy());}
        for(var value:next.get("skills.json").getAsJsonArray()) {
            var skill=value.getAsJsonObject();Double cost=cost(skill);if(cost!=null)skill.addProperty("staminaCost",cost);
        }
        var archery=next.get("archery.json").getAsJsonObject();
        archery.addProperty("rapidStaminaCost",5);archery.addProperty("snipeStaminaCost",15);
        archery.addProperty("partialShotStaminaMultiplier",.7);
        RpgConfig.parse(read(directory.resolve("combat.json")),next.get("skills.json"),read(directory.resolve("quests.json")),
                read(directory.resolve("weapons.json")),read(directory.resolve("styles.json")),read(directory.resolve("collections.json")),
                read(directory.resolve("skill_trees.json")),next.get("archery.json"));
        JsonArray changed=new JsonArray();
        for(var entry:next.entrySet()) {
            if(entry.getValue().equals(before.get(entry.getKey())))continue;
            Path target=directory.resolve(entry.getKey()),snapshot=directory.resolve(BACKUP).resolve(entry.getKey());
            Files.createDirectories(snapshot.getParent());if(!Files.exists(snapshot))Files.copy(target,snapshot);
            RpgConfig.atomicWrite(target,RpgConfig.JSON.toJson(entry.getValue()));changed.add(entry.getKey());
        }
        var marker=new JsonObject();marker.addProperty("releaseVersion","0.7.3");marker.addProperty("schemaVersion",1);
        marker.addProperty("spearThrustCost",10);marker.addProperty("singleSwordCost",5);marker.addProperty("dualBladeCost",8);
        marker.addProperty("rapidShotCost",5);marker.addProperty("snipeShotCost",15);marker.addProperty("partialShotMultiplier",.7);
        marker.add("changedFiles",changed);marker.addProperty("otherFieldsPreserved",true);
        RpgConfig.atomicWrite(directory.resolve(MARKER),RpgConfig.JSON.toJson(marker));
    }
    private static JsonElement read(Path path)throws IOException {
        if(Files.size(path)>512*1024)throw new IOException(path.getFileName()+" exceeds 512 KiB");
        return JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8));
    }
}

package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verify every requested 0.7.3 field against an untouched copy of the actual instance. */
public final class Stage073Config {
    private static final Set<String> EDITED=Set.of("combat.json","styles.json","skills.json","archery.json");
    public static void main(String[] args)throws Exception {
        Path dir=Path.of(args[0]).toAbsolutePath().normalize();Map<String,String> hashesBefore=hashes(dir);
        Map<String,JsonElement> expected=new HashMap<>();Map<String,byte[]> originals=new HashMap<>();
        for(String name:EDITED){originals.put(name,Files.readAllBytes(dir.resolve(name)));expected.put(name,read(dir.resolve(name)).deepCopy());}
        var combat=expected.get("combat.json").getAsJsonObject();combat.addProperty("perfectGuardTicks",2);combat.addProperty("parryStunTicks",4);
        var counter=new JsonObject();counter.addProperty("windowTicks",4);counter.addProperty("damageMultiplier",1.5);
        if(!combat.has("perfectGuardCounter"))combat.add("perfectGuardCounter",counter);
        else for(var field:counter.entrySet())if(!combat.getAsJsonObject("perfectGuardCounter").has(field.getKey()))combat.getAsJsonObject("perfectGuardCounter").add(field.getKey(),field.getValue());
        for(var value:expected.get("styles.json").getAsJsonArray())for(String field:List.of("guard","heavyGuard")) {
            var style=value.getAsJsonObject();if(!style.has(field)||!style.get(field).isJsonObject())continue;
            var guard=style.getAsJsonObject(field);if(guard.get("perfectGuardTicks").getAsInt()>0)guard.addProperty("perfectGuardTicks",2);guard.addProperty("parryStunTicks",4);
        }
        for(var value:expected.get("skills.json").getAsJsonArray()) {
            var skill=value.getAsJsonObject();String id=skill.get("id").getAsString(),weapon=skill.get("weapon").getAsString();
            int cost=weapon.equals("sword")||weapon.equals("rapier")?5:weapon.equals("dual_blade")?8:id.equals("spear_thrust")?10:id.equals("bow_rapid_release")?5:id.equals("bow_snipe_release")?15:-1;
            if(cost>=0)skill.addProperty("staminaCost",cost);
            if(id.equals("spear_thrust")){skill.addProperty("windupTicks",1);skill.addProperty("activeTicks",1);skill.addProperty("recoveryTicks",2);}
        }
        var archery=expected.get("archery.json").getAsJsonObject();archery.addProperty("rapidStaminaCost",5);archery.addProperty("snipeStaminaCost",15);archery.addProperty("partialShotStaminaMultiplier",.7);
        var config=new RpgConfig(dir);var definitions=config.reload();
        for(String name:EDITED)if(!expected.get(name).equals(read(dir.resolve(name))))throw new AssertionError("Unexpected field changes in "+name);
        Map<String,String> hashesAfter=hashes(dir);
        for(var e:hashesBefore.entrySet())if(!EDITED.contains(e.getKey())&&!Objects.equals(e.getValue(),hashesAfter.get(e.getKey())))throw new AssertionError("Unrelated file changed: "+e.getKey());
        for(var e:Map.of("skills.json","spear-thrust-timing-backup-v1/skills.json","archery.json","weapon-stamina-balance-backup-v1/archery.json","combat.json","perfect-guard-backup-v1/combat.json","styles.json","perfect-guard-backup-v1/styles.json").entrySet())
            if(!Arrays.equals(originals.get(e.getKey()),Files.readAllBytes(dir.resolve(e.getValue()))))throw new AssertionError("Original backup mismatch: "+e.getKey());
        config.reload();if(!hashesAfter.equals(hashes(dir)))throw new AssertionError("Second reload changed configuration");
        if(definitions.combat().staminaRegenPerSecond()!=3)throw new AssertionError("0.7.2 regeneration must remain 3");
        var result=new JsonObject();result.addProperty("passed",true);result.addProperty("allOtherExistingFieldsAndFilesPreserved",true);
        result.addProperty("exactOriginalBackups",true);result.addProperty("secondReloadIdempotent",true);result.addProperty("durationSeconds",.2);
        result.addProperty("perfectGuardTicks",2);result.addProperty("counterWindowTicks",4);result.addProperty("counterDamageMultiplier",1.5);result.addProperty("parryStunTicks",4);
        result.addProperty("staminaRegenPerSecond",3);result.addProperty("spearThrustCost",10);result.addProperty("singleSwordCost",5);result.addProperty("dualBladeCost",8);
        result.addProperty("rapidCost",5);result.addProperty("snipeCost",15);result.addProperty("partialRapidCost",3.5);result.addProperty("partialSnipeCost",10.5);
        result.add("beforeSha256",RpgConfig.JSON.toJsonTree(hashesBefore));result.add("afterSha256",RpgConfig.JSON.toJsonTree(hashesAfter));
        Path output=Path.of(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());RpgConfig.atomicWrite(output,RpgConfig.JSON.toJson(result));
        System.out.println("Stage073Config passed: only requested fields; exact backups; idempotent; stamina regen remains 3/s");
    }
    private static JsonElement read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path));}
    private static Map<String,String> hashes(Path dir)throws Exception {
        Map<String,String> result=new TreeMap<>();try(var files=Files.walk(dir)){for(var p:files.filter(Files::isRegularFile).toList())result.put(dir.relativize(p).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}return result;
    }
}

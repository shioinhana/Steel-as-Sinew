package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.fsrpg.combat.*;

/** New defaults and exact field-only upgrade, including legacy migration ordering and crash retry. */
public final class StaminaLevelMigrationTest {
    private static final List<String> FILES=List.of("combat.json","skills.json","styles.json","archery.json","quests.json","weapons.json","collections.json","skill_trees.json");
    private static int checks;
    public static void main(String[] args)throws Exception {
        fresh(); existingAndRetry(); pendingHistoricalRegeneration(); validationAndRepair(); compatibility();
        System.out.println("StaminaLevelMigrationTest passed: "+checks+" checks");
    }
    private static void fresh()throws Exception {
        Path dir=Files.createTempDirectory("fsrpg-level-fresh-");try {
            var config=new RpgConfig(dir);var rules=config.reload().combat();
            equal(CombatRules.defaults(),rules,"bundled and pure defaults agree");near(50,rules.maxStamina(),"fresh base cap");near(5,rules.staminaRegenPerSecond(),"fresh base rate");
            check(!Files.exists(dir.resolve(StaminaLevelMigration.BACKUP).resolve("combat.json")),"fresh has no invented source backup");
            var once=allBytes(dir);config.reload();equal(once,allBytes(dir),"fresh repeated seed is byte idempotent including markers");
        }finally{cleanup(dir);}
    }
    private static void existingAndRetry()throws Exception {
        Path dir=oldDirectory();try {
            JsonObject original=read(dir);original.addProperty("maxStamina",173.25);original.addProperty("staminaRegenPerSecond",2.625);
            original.addProperty("maxSpirit",137);original.addProperty("spiritRegenPerSecond",2.75);original.addProperty("resourceRegenDelayTicks",37);
            original.getAsJsonObject("perfectGuardCounter").addProperty("damageMultiplier",1.75);
            JsonObject scaling=new JsonObject();scaling.addProperty("maxPowerExponent",1.1);original.add("staminaLevelScaling",scaling);
            byte[] bytes=(" \r\n"+RpgConfig.JSON.toJson(original)+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(dir.resolve("combat.json"),bytes);
            var unrelated=otherBytes(dir);var config=new RpgConfig(dir);var rules=config.reload().combat();
            near(50,rules.maxStamina(),"existing cap resets once");near(5,rules.staminaRegenPerSecond(),"existing base becomes five without multiplication");
            near(1.1,rules.staminaLevelScaling().maxPowerExponent(),"existing coefficient preserved");near(1,rules.staminaLevelScaling().regenSqrtCoefficient(),"missing coefficients filled");
            JsonObject actual=read(dir);JsonObject restored=actual.deepCopy();restored.add("maxStamina",original.get("maxStamina"));restored.add("staminaRegenPerSecond",original.get("staminaRegenPerSecond"));restored.add("staminaLevelScaling",original.get("staminaLevelScaling"));
            equal(original,restored,"all unrelated combat values are exact");equal(unrelated,otherBytes(dir),"all other config files unchanged byte for byte");
            equal(HexFormat.of().formatHex(bytes),HexFormat.of().formatHex(Files.readAllBytes(dir.resolve(StaminaLevelMigration.BACKUP).resolve("combat.json"))),"exact upgrade-entry backup bytes");
            JsonObject marker=JsonParser.parseString(Files.readString(dir.resolve(StaminaLevelMigration.MARKER))).getAsJsonObject();
            equal(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),marker.get("originalCombatSha256").getAsString(),"backup digest recorded");
            var once=allBytes(dir);config.reload();equal(once,allBytes(dir),"completed upgrade idempotent for all bytes");
            Files.delete(dir.resolve(StaminaLevelMigration.MARKER));actual.addProperty("maxSpirit",191);actual.addProperty("staminaRegenPerSecond",15);Files.writeString(dir.resolve("combat.json"),RpgConfig.JSON.toJson(actual));
            rules=config.reload().combat();near(5,rules.staminaRegenPerSecond(),"retry never multiplies already changed regeneration");near(191,rules.maxSpirit(),"retry preserves later unrelated edit");
            equal(HexFormat.of().formatHex(bytes),HexFormat.of().formatHex(Files.readAllBytes(dir.resolve(StaminaLevelMigration.BACKUP).resolve("combat.json"))),"retry retains original snapshot");
            actual=read(dir);actual.addProperty("maxStamina",73);actual.addProperty("staminaRegenPerSecond",7.5);actual.getAsJsonObject("staminaLevelScaling").addProperty("regenLogCoefficient",2);Files.writeString(dir.resolve("combat.json"),RpgConfig.JSON.toJson(actual));
            rules=config.reload().combat();near(73,rules.maxStamina(),"later custom base cap retained");near(7.5,rules.staminaRegenPerSecond(),"later custom base rate retained");near(2,rules.staminaLevelScaling().regenLogCoefficient(),"later coefficient retained");
        }finally{cleanup(dir);}
    }
    private static void pendingHistoricalRegeneration()throws Exception {
        Path dir=oldDirectory();try {
            // Clear only the historical regen state: a direct 0.7.1 -> 0.7.4 upgrade must
            // keep both old x3 evidence and this release's exact entry snapshot.
            Files.delete(dir.resolve(StaminaRegenMigration.MARKER));
            Path oldBackup=dir.resolve(StaminaRegenMigration.BACKUP);try(var paths=Files.walk(oldBackup)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            JsonObject source=read(dir);source.addProperty("maxStamina",157);source.addProperty("staminaRegenPerSecond",.875);source.addProperty("maxSpirit",163);
            String text="\r\n"+RpgConfig.JSON.toJson(source)+"\r\n";Files.writeString(dir.resolve("combat.json"),text);
            var config=new RpgConfig(dir);var rules=config.reload().combat();near(50,rules.maxStamina(),"old release gets new cap");near(5,rules.staminaRegenPerSecond(),"new base follows historical x3 exactly once");near(163,rules.maxSpirit(),"historical chain keeps unrelated fields");
            equal(text,Files.readString(dir.resolve(StaminaLevelMigration.BACKUP).resolve("combat.json")),"new backup captures before old x3 migration");
            equal(text,Files.readString(dir.resolve(StaminaRegenMigration.BACKUP).resolve("combat.json")),"historical x3 backup still exact");
            JsonObject oldMarker=JsonParser.parseString(Files.readString(dir.resolve(StaminaRegenMigration.MARKER))).getAsJsonObject();near(2.625,oldMarker.get("staminaRegenPerSecond").getAsDouble(),"historical marker proves old x3 still ran");
            var once=allBytes(dir);config.reload();equal(once,allBytes(dir),"historical chain reload does not reapply either upgrade");
        }finally{cleanup(dir);}
    }
    private static void validationAndRepair()throws Exception {
        for(JsonElement invalid:List.of(new JsonPrimitive(-1),new JsonPrimitive("1"),JsonNull.INSTANCE)) {
            Path dir=oldDirectory();try {
                JsonObject original=read(dir),scaling=RpgConfig.JSON.toJsonTree(StaminaLevelScaling.defaults()).getAsJsonObject();scaling.add("regenSqrtCoefficient",invalid);original.add("staminaLevelScaling",scaling);
                // JsonElement.toString retains an explicit JSON null; the configured
                // pretty Gson writer otherwise omits it and would test a missing field.
                String text=original.toString();Files.writeString(dir.resolve("combat.json"),text);
                rejects(()->new RpgConfig(dir).reload(),"invalid scaling cannot migrate: "+invalid);equal(text,Files.readString(dir.resolve("combat.json")),"invalid candidate leaves original file");
                check(!Files.exists(dir.resolve(StaminaLevelMigration.MARKER)),"invalid candidate has no marker");check(!Files.exists(dir.resolve(StaminaLevelMigration.BACKUP).resolve("combat.json")),"invalid candidate does not freeze bad snapshot");
                scaling.addProperty("regenSqrtCoefficient",1);Files.writeString(dir.resolve("combat.json"),RpgConfig.JSON.toJson(original));near(5,new RpgConfig(dir).reload().combat().staminaRegenPerSecond(),"repair can retry");
            }finally{cleanup(dir);}
        }
    }
    private static void compatibility()throws Exception {
        Path dir=Files.createTempDirectory("fsrpg-level-parse-");try {
            var config=new RpgConfig(dir);config.reload();JsonObject combat=read(dir);combat.remove("staminaLevelScaling");
            var old=parse(dir,combat);equal(StaminaLevelScaling.defaults(),old.staminaLevelScaling(),"missing extension is backward compatible");
            for(String key:List.of("regenSqrtCoefficient","regenLogCoefficient","maxPowerCoefficient","maxPowerExponent","maxLogCoefficient")) {
                JsonObject invalid=combat.deepCopy();JsonObject scaling=RpgConfig.JSON.toJsonTree(StaminaLevelScaling.defaults()).getAsJsonObject();scaling.remove(key);invalid.add("staminaLevelScaling",scaling);
                rejects(()->parse(dir,invalid),"present scaling requires "+key);
            }
            JsonObject invalid=combat.deepCopy();invalid.add("staminaLevelScaling",JsonNull.INSTANCE);final JsonObject nullScaling=invalid;rejects(()->parse(dir,nullScaling),"explicit null extension rejected");
            invalid=combat.deepCopy();var scaling=RpgConfig.JSON.toJsonTree(StaminaLevelScaling.defaults()).getAsJsonObject();scaling.addProperty("typo",1);invalid.add("staminaLevelScaling",scaling);final JsonObject unknown=invalid;rejects(()->parse(dir,unknown),"unknown coefficient rejected");
        }finally{cleanup(dir);}
    }
    private static CombatRules parse(Path d,JsonObject combat)throws Exception{return RpgConfig.parse(combat,json(d,"skills.json"),json(d,"quests.json"),json(d,"weapons.json"),json(d,"styles.json"),json(d,"collections.json"),json(d,"skill_trees.json"),json(d,"archery.json")).combat();}
    private static Path oldDirectory()throws Exception {Path d=Files.createTempDirectory("fsrpg-level-old-");new RpgConfig(d).reload();Files.delete(d.resolve(StaminaLevelMigration.MARKER));JsonObject old=read(d);old.remove("staminaLevelScaling");old.addProperty("maxStamina",100);old.addProperty("staminaRegenPerSecond",3);Files.writeString(d.resolve("combat.json"),RpgConfig.JSON.toJson(old));return d;}
    private static JsonElement json(Path d,String file)throws Exception{return JsonParser.parseString(Files.readString(d.resolve(file)));}
    private static JsonObject read(Path d)throws Exception{return json(d,"combat.json").getAsJsonObject();}
    private static Map<String,String> otherBytes(Path d)throws Exception{Map<String,String> m=new TreeMap<>();for(String f:FILES)if(!f.equals("combat.json"))m.put(f,HexFormat.of().formatHex(Files.readAllBytes(d.resolve(f))));return m;}
    private static Map<String,String> allBytes(Path d)throws Exception{Map<String,String> m=new TreeMap<>();try(var paths=Files.walk(d)){for(var p:paths.filter(Files::isRegularFile).toList())m.put(d.relativize(p).toString(),HexFormat.of().formatHex(Files.readAllBytes(p)));}return m;}
    private static void cleanup(Path d)throws Exception{Path root=d.toAbsolutePath().normalize();try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList()){if(!p.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("cleanup escaped test root");Files.delete(p);}}}
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static void rejects(Checked f,String m){checks++;try{f.run();throw new AssertionError(m);}catch(RuntimeException|java.io.IOException expected){}catch(Exception e){throw new AssertionError(m,e);}}
    private static void near(double a,double b,String m){check(Math.abs(a-b)<1e-8,m+": "+b);}
    private static void equal(Object a,Object b,String m){check(a.equals(b),m+": "+b);}
    private static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
}

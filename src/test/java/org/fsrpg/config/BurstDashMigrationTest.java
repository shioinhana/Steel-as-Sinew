package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.*;

/** New Shift-only defaults never overwrite custom Alt dodge, stamina, assets or completed tuning. */
public final class BurstDashMigrationTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        fresh();existingAndRetry();completeCustom();invalid();
        System.out.println("BurstDashMigrationTest passed: "+checks+" checks");
    }
    private static void fresh()throws Exception{
        Path dir=Files.createTempDirectory("fsrpg-burst-fresh-");try{
            var config=new RpgConfig(dir);var data=config.reload();
            equal(CombatRules.defaults(),data.combat(),"new seed and fallback agree");
            equal(BurstDashRules.defaults(),data.combat().burstDash(),"Shift defaults are three blocks, half second and five stamina");
            check(!Files.exists(dir.resolve(BurstDashMigration.BACKUP)),"fresh seed invents no backup");
            check(Files.exists(dir.resolve(BurstDashMigration.MARKER)),"fresh migration marked complete");
            var old=read(dir,"combat.json").getAsJsonObject();old.remove("burstDash");old.remove("spearSwipeKnockbackMultiplier");
            equal(BurstDashRules.defaults(),parse(dir,old).combat().burstDash(),"missing old JSON field has compatible defaults");
            equal(.5,parse(dir,old).combat().spearSwipeKnockbackMultiplier(),"missing spear swipe scalar defaults to half");
            var before=bytes(dir);config.reload();sameBytes(before,bytes(dir),"fresh reload byte-idempotent");
        }finally{cleanup(dir);}
    }
    private static void existingAndRetry()throws Exception{
        for(boolean partial:new boolean[]{false,true}){
            Path dir=fixture();try{
                var combat=read(dir,"combat.json").getAsJsonObject();combat.remove("burstDash");
                if(partial)combat.addProperty("spearSwipeKnockbackMultiplier",.27);else combat.remove("spearSwipeKnockbackMultiplier");
                combat.addProperty("dodgeStaminaCost",8.25);combat.addProperty("dodgeDistance",4.75);combat.addProperty("allowAirDodge",true);
                combat.addProperty("maxSpirit",177);combat.addProperty("resourceRegenDelayTicks",37);
                if(partial){var custom=new JsonObject();custom.addProperty("staminaCost",6.25);combat.add("burstDash",custom);}
                write(dir,"combat.json",combat);var before=bytes(dir);byte[] original=Files.readAllBytes(dir.resolve("combat.json"));
                var expected=combat.deepCopy();var burst=RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()).getAsJsonObject();
                if(!expected.has("spearSwipeKnockbackMultiplier"))expected.addProperty("spearSwipeKnockbackMultiplier",.5);
                if(partial)burst.addProperty("staminaCost",6.25);expected.add("burstDash",burst);
                new RpgConfig(dir).reload();equal(expected,read(dir,"combat.json"),"only absent Shift fields added while Alt remains exact");
                check(Arrays.equals(original,Files.readAllBytes(dir.resolve(BurstDashMigration.BACKUP).resolve("combat.json"))),"original combat backup byte exact");
                var after=bytes(dir);for(var file:before.entrySet())if(!file.getKey().equals("combat.json"))
                    check(Arrays.equals(file.getValue(),after.get(file.getKey())),"unrelated file preserved "+file.getKey());
                var added=new HashSet<>(after.keySet());added.removeAll(before.keySet());
                equal(Set.of(BurstDashMigration.MARKER,BurstDashMigration.BACKUP+"/combat.json"),added,"migration adds exactly marker and combat backup");
                new RpgConfig(dir).reload();sameBytes(after,bytes(dir),"second load does not repeat migration");
                Files.delete(dir.resolve(BurstDashMigration.MARKER));combat=read(dir,"combat.json").getAsJsonObject();
                combat.getAsJsonObject("burstDash").remove("inputLeaseTicks");combat.addProperty("maxSpirit",191);write(dir,"combat.json",combat);
                new RpgConfig(dir).reload();check(Arrays.equals(original,Files.readAllBytes(dir.resolve(BurstDashMigration.BACKUP).resolve("combat.json"))),"retry retains true original backup");
                equal(191,read(dir,"combat.json").getAsJsonObject().get("maxSpirit").getAsInt(),"retry preserves later unrelated edit");
            }finally{cleanup(dir);}
        }
    }
    private static void completeCustom()throws Exception{
        Path dir=fixture();try{
            var combat=read(dir,"combat.json").getAsJsonObject();combat.add("burstDash",RpgConfig.JSON.toJsonTree(new BurstDashRules(4,12,7,15,6,16)));
            combat.addProperty("spearSwipeKnockbackMultiplier",.17);
            write(dir,"combat.json",combat);byte[] before=Files.readAllBytes(dir.resolve("combat.json"));
            new RpgConfig(dir).reload();check(Arrays.equals(before,Files.readAllBytes(dir.resolve("combat.json"))),"complete custom Shift tuning remains byte exact");
            check(!Files.exists(dir.resolve(BurstDashMigration.BACKUP)),"unchanged custom record needs no fictitious original");
            combat.getAsJsonObject("burstDash").addProperty("staminaCost",9);write(dir,"combat.json",combat);
            equal(9.0,new RpgConfig(dir).reload().combat().burstDash().staminaCost(),"later edits survive the completed marker");
        }finally{cleanup(dir);}
    }
    private static void invalid()throws Exception{
        Path dir=fixture();try{
            var base=read(dir,"combat.json").getAsJsonObject();
            var invalid=new ArrayList<JsonElement>();invalid.add(JsonNull.INSTANCE);invalid.add(new JsonArray());
            for(String field:List.of("distance","durationTicks","staminaCost","cooldownTicks","runSpeed","inputLeaseTicks")){
                var value=RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()).getAsJsonObject();value.add(field,JsonNull.INSTANCE);invalid.add(value);
                value=RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()).getAsJsonObject();value.addProperty(field,"3");invalid.add(value);
            }
            var fraction=RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()).getAsJsonObject();fraction.addProperty("durationTicks",10.5);invalid.add(fraction);
            var unsafe=RpgConfig.JSON.toJsonTree(BurstDashRules.defaults()).getAsJsonObject();unsafe.addProperty("distance",.25);unsafe.addProperty("durationTicks",100);invalid.add(unsafe);
            for(var value:invalid){
                var combat=base.deepCopy();combat.add("burstDash",value);write(dir,"combat.json",combat);var before=bytes(dir);boolean rejected=false;
                try{BurstDashMigration.capture(dir).apply();}catch(RuntimeException expected){rejected=true;}
                check(rejected,"invalid new movement record rejected "+value);sameBytes(before,bytes(dir),"invalid migration writes nothing");
                check(!Files.exists(dir.resolve(BurstDashMigration.MARKER))&&!Files.exists(dir.resolve(BurstDashMigration.BACKUP)),"invalid migration neither completes nor freezes bad original");
            }
            for(String scalar:List.of("null","\"0.5\"","NaN","Infinity","true","{}","-0.01","1.01")) {
                var combat=base.deepCopy();combat.add("spearSwipeKnockbackMultiplier",JsonParser.parseString(scalar));
                write(dir,"combat.json",combat);var before=bytes(dir);boolean rejected=false;
                try{BurstDashMigration.capture(dir).apply();}catch(RuntimeException expected){rejected=true;}
                check(rejected,"invalid swipe scalar rejected "+scalar);sameBytes(before,bytes(dir),"invalid scalar migration writes nothing");
                check(!Files.exists(dir.resolve(BurstDashMigration.MARKER))&&!Files.exists(dir.resolve(BurstDashMigration.BACKUP)),"invalid scalar cannot complete or back up");
            }
        }finally{cleanup(dir);}
    }
    private static Path fixture()throws Exception{Path dir=Files.createTempDirectory("fsrpg-burst-existing-");new RpgConfig(dir).reload();Files.delete(dir.resolve(BurstDashMigration.MARKER));return dir;}
    private static RpgConfig.Definitions parse(Path dir,JsonObject combat)throws Exception{return RpgConfig.parse(combat,read(dir,"skills.json"),read(dir,"quests.json"),read(dir,"weapons.json"),read(dir,"styles.json"),read(dir,"collections.json"),read(dir,"skill_trees.json"),read(dir,"archery.json"));}
    private static JsonElement read(Path dir,String name)throws Exception{return JsonParser.parseString(Files.readString(dir.resolve(name)));}
    private static void write(Path dir,String name,JsonElement value)throws Exception{Files.writeString(dir.resolve(name)," \n"+value+"\n");}
    private static Map<String,byte[]> bytes(Path dir)throws Exception{var result=new TreeMap<String,byte[]>();try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).toList())result.put(dir.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));}return result;}
    private static void sameBytes(Map<String,byte[]> before,Map<String,byte[]> after,String message){equal(before.keySet(),after.keySet(),message+" inventory");for(String name:before.keySet())check(Arrays.equals(before.get(name),after.get(name)),message+" "+name);}
    private static void cleanup(Path directory)throws Exception{Path root=directory.toAbsolutePath().normalize();try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList()){if(!file.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("fixture escape");Files.deleteIfExists(file);}}}
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+": "+expected+" != "+actual);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}

package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Upgrade stock definitions while preserving explicit opt-out, custom tuning, models and byte-for-byte backups. */
public final class SwordCombatMigrationTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Path stock=fixture();JsonArray skills=read(stock,"skills.json").getAsJsonArray();find(skills,"sword_left").addProperty("damage",17);write(stock,"skills.json",skills);byte[] original=Files.readAllBytes(stock.resolve("skills.json"));
        SwordCombatMigration.migrate(stock);var rules=read(stock,"combat.json").getAsJsonObject().getAsJsonObject("swordCombat");check(rules.get("enabled").getAsBoolean(),"stock upgrade enabled");skills=read(stock,"skills.json").getAsJsonArray();check(find(skills,"sword_left").get("damage").getAsInt()==17,"custom damage retained");check(find(skills,"sword_left").get("model").getAsString().equals("rapier_fullbody.fsvoxel"),"stock model path upgraded");check(find(skills,"sword_parry_return")!=null,"return added once");check(Arrays.equals(original,Files.readAllBytes(stock.resolve(SwordCombatMigration.BACKUP).resolve("skills.json"))),"exact backup");byte[] after=Files.readAllBytes(stock.resolve("skills.json"));SwordCombatMigration.migrate(stock);check(Arrays.equals(after,Files.readAllBytes(stock.resolve("skills.json"))),"idempotent rerun");
        Path custom=fixture();JsonElement customTrees=read(custom,"skill_trees.json");Files.createDirectories(custom.resolve("models"));Files.writeString(custom.resolve("models/legacy_rapier.fsvoxel"),"custom bytes");SwordCombatMigration.migrate(custom);check(!read(custom,"combat.json").getAsJsonObject().getAsJsonObject("swordCombat").get("enabled").getAsBoolean(),"custom geometry opts out");check(find(read(custom,"skills.json").getAsJsonArray(),"sword_left").get("model").getAsString().equals("legacy_rapier.fsvoxel"),"custom external model still used");check(Files.readString(custom.resolve("models/legacy_rapier.fsvoxel")).equals("custom bytes"),"custom model not overwritten");check(customTrees.equals(read(custom,"skill_trees.json")),"custom model retains matching legacy lesson descriptions");
        Path opt=fixture();JsonElement optTrees=read(opt,"skill_trees.json");var combat=read(opt,"combat.json").getAsJsonObject();JsonObject config=new JsonObject();config.addProperty("enabled",false);config.addProperty("steeringFraction",.11);combat.add("swordCombat",config);write(opt,"combat.json",combat);SwordCombatMigration.migrate(opt);rules=read(opt,"combat.json").getAsJsonObject().getAsJsonObject("swordCombat");check(!rules.get("enabled").getAsBoolean()&&rules.get("steeringFraction").getAsDouble()==.11,"explicit opt-out/tuning preserved");check(find(read(opt,"skills.json").getAsJsonArray(),"sword_left").get("model").getAsString().equals("legacy_rapier.fsvoxel"),"opt-out keeps visual path");check(optTrees.equals(read(opt,"skill_trees.json")),"explicit opt-out retains matching legacy lesson descriptions");
        Path invalid=fixture();combat=read(invalid,"combat.json").getAsJsonObject();config=RpgConfig.JSON.toJsonTree(org.fsrpg.combat.SwordCombatRules.defaults()).getAsJsonObject();config.addProperty("steeringFraction",2);combat.add("swordCombat",config);write(invalid,"combat.json",combat);byte[] untouched=Files.readAllBytes(invalid.resolve("skills.json"));boolean rejected=false;try{SwordCombatMigration.migrate(invalid);}catch(RuntimeException e){rejected=true;}check(rejected,"invalid candidate rejected");check(Arrays.equals(untouched,Files.readAllBytes(invalid.resolve("skills.json")))&&!Files.exists(invalid.resolve(SwordCombatMigration.MARKER)),"invalid migration no partial changes");
        invalidReferences(false);invalidReferences(true);
        System.out.println("SwordCombatMigrationTest passed: "+checks+" checks");
    }
    private static void invalidReferences(boolean returnCollision)throws Exception{
        Path dir=fixture();
        if(returnCollision){
            var skills=read(dir,"skills.json").getAsJsonArray();var custom=find(skills,"sword_left").deepCopy();
            custom.addProperty("id","sword_parry_return");custom.addProperty("input","RIGHT");skills.add(custom);write(dir,"skills.json",skills);
        }else{
            var combat=read(dir,"combat.json").getAsJsonObject();var rules=RpgConfig.JSON.toJsonTree(org.fsrpg.combat.SwordCombatRules.defaults()).getAsJsonObject();
            rules.getAsJsonObject("motionDistances").addProperty("missing_custom_action",.4);combat.add("swordCombat",rules);write(dir,"combat.json",combat);
        }
        Map<String,String> before=new LinkedHashMap<>();for(String file:List.of("combat.json","skills.json","skill_trees.json"))before.put(file,Files.readString(dir.resolve(file)));
        boolean rejected=false;try{SwordCombatMigration.migrate(dir);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"cross-file sword candidate rejected before writes: "+returnCollision);
        for(var file:before.entrySet())check(file.getValue().equals(Files.readString(dir.resolve(file.getKey()))),"invalid cross-file candidate preserves exact original "+file.getKey());
        check(!Files.exists(dir.resolve(SwordCombatMigration.MARKER))&&!Files.exists(dir.resolve(SwordCombatMigration.BACKUP)),"invalid cross-file candidate creates no marker or durable upgrade plan");
    }
    private static Path fixture()throws Exception{
        Path d=Files.createTempDirectory("fsrpg-sword-migration-");for(String f:List.of("combat.json","skills.json","quests.json","weapons.json","styles.json","collections.json","skill_trees.json","archery.json"))write(d,f,resource(f));
        var combat=read(d,"combat.json").getAsJsonObject();combat.remove("swordCombat");write(d,"combat.json",combat);
        var base=resource("sword-combat-0.11.0.json").getAsJsonObject();JsonArray skills=read(d,"skills.json").getAsJsonArray(),old=new JsonArray();for(var e:skills){var r=e.getAsJsonObject();if(r.get("id").getAsString().equals("sword_parry_return"))continue;var previous=find(base.getAsJsonArray("skills"),r.get("id").getAsString());old.add(previous==null?r:previous);}write(d,"skills.json",old);
        var trees=read(d,"skill_trees.json").getAsJsonObject();for(var e:trees.getAsJsonArray("nodes")){var n=e.getAsJsonObject();var previous=find(base.getAsJsonArray("nodes"),n.get("id").getAsString());if(previous!=null)n.add("description",previous.get("description"));}write(d,"skill_trees.json",trees);return d;
    }
    private static JsonObject find(JsonArray a,String id){for(var e:a){var o=e.getAsJsonObject();if(o.get("id").getAsString().equals(id))return o;}return null;}
    private static JsonElement resource(String file)throws Exception{try(var in=SwordCombatMigrationTest.class.getResourceAsStream("/defaults/"+file)){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));}}
    private static JsonElement read(Path d,String f)throws Exception{return JsonParser.parseString(Files.readString(d.resolve(f),StandardCharsets.UTF_8));}
    private static void write(Path d,String f,JsonElement e)throws Exception{Files.writeString(d.resolve(f),RpgConfig.JSON.toJson(e),StandardCharsets.UTF_8);}
    private static void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
}

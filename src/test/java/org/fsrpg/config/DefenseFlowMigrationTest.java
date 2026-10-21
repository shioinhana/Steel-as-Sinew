package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.CombatRules;
import org.fsrpg.creature.GuardCueSettings;

public final class DefenseFlowMigrationTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path fresh=Files.createTempDirectory("fsrpg-defense-fresh-");
        try{
            var config=new RpgConfig(fresh);var d=config.reload();
            check(d.combat().equals(CombatRules.defaults()),"fresh immutable and bundled rules agree");
            check(d.combat().perfectGuardTicks()==7&&d.combat().weaponClash().contactWindowTicks()==3,"fresh seven-tick guard and three-tick clash");
            check(GuardCueSettings.read(fresh.resolve("guard_cues.json")).equals(GuardCueSettings.defaults()),"fresh cue agrees with seven-tick defaults");
            byte[] before=Files.readAllBytes(fresh.resolve("combat.json"));config.reload();
            check(Arrays.equals(before,Files.readAllBytes(fresh.resolve("combat.json"))),"second full reload never adds another tick");
            Files.delete(fresh.resolve(DefenseFlowMigration.MARKER));
            try(var entries=Files.walk(fresh.resolve(DefenseFlowMigration.BACKUP))){for(Path p:entries.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            Files.delete(fresh.resolve("styles.json"));Files.delete(fresh.resolve("combat.json"));
            var repaired=config.reload();
            check(repaired.combat().perfectGuardTicks()==7&&repaired.combat().weaponClash().contactWindowTicks()==3,"missing files seed current defaults without another increment");
            check(repaired.styles().values().stream().allMatch(s->s.guard().perfectGuardTicks()==0||s.guard().perfectGuardTicks()==7),"missing styles seed seven rather than eight ticks");
        }finally{cleanup(fresh);}
        existing(false);existing(true);
        System.out.println("DefenseFlowMigrationTest passed: "+checks+" checks");
    }
    private static void existing(boolean disabled)throws Exception{
        Path dir=Files.createTempDirectory("fsrpg-defense-existing-");
        try{
            for(String name:List.of("combat.json","styles.json","skills.json","quests.json","weapons.json","collections.json","skill_trees.json","archery.json","guard_cues.json"))
                try(var in=DefenseFlowMigrationTest.class.getResourceAsStream("/defaults/"+name)){Files.copy(Objects.requireNonNull(in),dir.resolve(name));}
            JsonObject combat=read(dir,"combat.json").getAsJsonObject();combat.addProperty("perfectGuardTicks",disabled?0:6);combat.addProperty("maxSpirit",137);
            combat.getAsJsonObject("weaponClash").addProperty("contactWindowTicks",2);combat.getAsJsonObject("weaponClash").addProperty("damageTolerance",4.5);write(dir,"combat.json",combat);
            JsonArray styles=read(dir,"styles.json").getAsJsonArray();
            for(var value:styles)for(String field:List.of("guard","heavyGuard")){
                var style=value.getAsJsonObject();if(style.has(field)&&style.get(field).isJsonObject()){
                    var guard=style.getAsJsonObject(field);if(guard.get("perfectGuardTicks").getAsInt()>0)guard.addProperty("perfectGuardTicks",disabled?0:6);
                }
            }write(dir,"styles.json",styles);
            JsonObject cue=read(dir,"guard_cues.json").getAsJsonObject();cue.addProperty("enabled",!disabled);cue.addProperty("inputWindowTicks",6);cue.addProperty("flashLeadTicks",6);write(dir,"guard_cues.json",cue);
            Map<String,byte[]> before=new HashMap<>();for(String name:List.of("combat.json","styles.json","guard_cues.json","skills.json"))before.put(name,Files.readAllBytes(dir.resolve(name)));
            DefenseFlowMigration.capture(dir).apply();var after=read(dir,"combat.json").getAsJsonObject();
            check(after.get("perfectGuardTicks").getAsInt()==(disabled?0:7),"enabled guards gain one tick; disabled remains zero");
            check(after.get("maxSpirit").getAsInt()==137,"unrelated custom spirit preserved");
            check(after.getAsJsonObject("weaponClash").get("contactWindowTicks").getAsInt()==3,"existing two-tick clash gains exactly one");
            check(after.getAsJsonObject("weaponClash").get("damageTolerance").getAsDouble()==4.5,"custom damage tolerance preserved");
            check(read(dir,"guard_cues.json").getAsJsonObject().get("inputWindowTicks").getAsInt()==(disabled?6:7),"cue timing follows enabled window only");
            for(String name:List.of("combat.json","styles.json","guard_cues.json"))check(Arrays.equals(before.get(name),Files.readAllBytes(dir.resolve(DefenseFlowMigration.BACKUP).resolve(name))),"exact original backup: "+name);
            check(Arrays.equals(before.get("skills.json"),Files.readAllBytes(dir.resolve("skills.json"))),"skills untouched");
            check(DefenseFlowMigration.capture(dir)==null,"completed marker blocks further increments");
            // Simulate interruption after only combat was committed. The durable candidate
            // plan makes retries idempotent even when some files are old and some new.
            Files.delete(dir.resolve(DefenseFlowMigration.MARKER));Files.write(dir.resolve("styles.json"),before.get("styles.json"));
            DefenseFlowMigration.capture(dir).apply();
            check(after.equals(read(dir,"combat.json")),"interrupted retry does not add a second tick");
            check(Files.exists(dir.resolve(DefenseFlowMigration.MARKER)),"interrupted retry completes marker");
        }finally{cleanup(dir);}
    }
    private static JsonElement read(Path dir,String name)throws Exception{return JsonParser.parseString(Files.readString(dir.resolve(name)));}
    private static void write(Path dir,String name,JsonElement value)throws Exception{Files.writeString(dir.resolve(name),RpgConfig.JSON.toJson(value));}
    private static void cleanup(Path dir)throws Exception{
        if(!dir.toAbsolutePath().getFileName().toString().startsWith("fsrpg-defense-"))throw new IllegalArgumentException("Unexpected temporary directory");
        try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}

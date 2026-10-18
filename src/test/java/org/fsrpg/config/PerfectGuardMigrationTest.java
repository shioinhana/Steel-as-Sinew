package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.CombatRules;

/** Exact backups, old optional config support and interrupted retries for guard-only fields. */
public final class PerfectGuardMigrationTest {
    private static int checks;
    private static final List<String> FILES=List.of("combat.json","skills.json","styles.json","weapons.json","quests.json","collections.json","skill_trees.json","archery.json");
    public static void main(String[] args)throws Exception {
        freshAndCompatibility(); existingCustomAndRetry(); invalidAndRepair();
        System.out.println("PerfectGuardMigrationTest passed: "+checks+" checks");
    }
    private static void freshAndCompatibility()throws Exception {
        Path directory=Files.createTempDirectory("fsrpg-perfect-fresh-");
        try {
            // Exercise the historical 0.7.3 migration directly; final application reload also runs later releases.
            for(String name:FILES)try(var input=PerfectGuardMigrationTest.class.getResourceAsStream("/defaults/"+name)){
                if(input==null)throw new IllegalStateException("Missing fixture default "+name);Files.copy(input,directory.resolve(name));
            }
            JsonObject historical=read(directory,"combat.json").getAsJsonObject();historical.addProperty("perfectGuardTicks",2);write(directory,"combat.json",historical);
            JsonArray historicalStyles=read(directory,"styles.json").getAsJsonArray();for(var value:historicalStyles)for(String field:List.of("guard","heavyGuard")){
                JsonObject style=value.getAsJsonObject();if(style.has(field)&&style.get(field).isJsonObject()){
                    var guard=style.getAsJsonObject(field);if(guard.get("perfectGuardTicks").getAsInt()>0)guard.addProperty("perfectGuardTicks",2);
                }
            }write(directory,"styles.json",historicalStyles);
            PerfectGuardMigration.migrate(directory);
            var definitions=RpgConfig.parse(read(directory,"combat.json"),read(directory,"skills.json"),read(directory,"quests.json"),read(directory,"weapons.json"),read(directory,"styles.json"),
                    read(directory,"collections.json"),read(directory,"skill_trees.json"),read(directory,"archery.json"));
            equal(2,definitions.combat().perfectGuardTicks(),"new global perfect window is two ticks");
            equal(4,definitions.combat().parryStunTicks(),"new global parry stun is four ticks");
            equal(CombatRules.PerfectGuardCounter.defaults(),definitions.combat().perfectGuardCounter(),"new counter defaults are four ticks and 1.5 damage");
            JsonObject historicalDefaults=RpgConfig.JSON.toJsonTree(CombatRules.defaults()).getAsJsonObject();historicalDefaults.addProperty("perfectGuardTicks",2);
            equal(RpgConfig.JSON.fromJson(historicalDefaults,CombatRules.class),definitions.combat(),"historical two-tick constructor and bundled combat config agree");
            check(Files.exists(directory.resolve(PerfectGuardMigration.MARKER)),"fresh defaults mark completed migration");
            check(!Files.exists(directory.resolve(PerfectGuardMigration.BACKUP)),"fresh correct defaults need no invented previous files");
            Map<String,String> before=texts(directory);PerfectGuardMigration.migrate(directory);equal(before,texts(directory),"historical repeated migration is byte-idempotent");
            JsonObject combat=read(directory,"combat.json").getAsJsonObject();combat.remove("perfectGuardCounter");
            var old=RpgConfig.parse(combat,read(directory,"skills.json"),read(directory,"quests.json"),read(directory,"weapons.json"),read(directory,"styles.json"),
                    read(directory,"collections.json"),read(directory,"skill_trees.json"),read(directory,"archery.json"));
            equal(CombatRules.PerfectGuardCounter.defaults(),old.combat().perfectGuardCounter(),"older JSON lacking optional counter block remains compatible");
        }finally{cleanup(directory);}
    }
    private static void existingCustomAndRetry()throws Exception {
        Path directory=oldDirectory();
        try {
            JsonObject combat=read(directory,"combat.json").getAsJsonObject();combat.addProperty("maxStamina",173);combat.addProperty("staminaRegenPerSecond",4.25);
            JsonArray styles=read(directory,"styles.json").getAsJsonArray();
            JsonObject guard=styles.get(0).getAsJsonObject().getAsJsonObject("guard");guard.addProperty("damageMultiplier",.37);guard.addProperty("arcDegrees",143);
            write(directory,"combat.json",combat);write(directory,"styles.json",styles);
            Map<String,String> before=texts(directory);
            JsonObject expectedCombat=combat.deepCopy();expectedCombat.addProperty("perfectGuardTicks",2);expectedCombat.addProperty("parryStunTicks",4);
            expectedCombat.add("perfectGuardCounter",RpgConfig.JSON.toJsonTree(CombatRules.PerfectGuardCounter.defaults()));
            JsonArray expectedStyles=styles.deepCopy();for(var value:expectedStyles)for(String field:List.of("guard","heavyGuard")){
                JsonObject style=value.getAsJsonObject();if(!style.has(field)||!style.get(field).isJsonObject())continue;
                JsonObject entry=style.getAsJsonObject(field);if(entry.get("perfectGuardTicks").getAsInt()>0)entry.addProperty("perfectGuardTicks",2);entry.addProperty("parryStunTicks",4);
            }
            PerfectGuardMigration.migrate(directory);
            equal(expectedCombat,read(directory,"combat.json"),"global guard migration changes only requested timing and optional counter fields");
            equal(expectedStyles,read(directory,"styles.json"),"style migration changes only enabled perfect windows and stun fields");
            for(String name:List.of("combat.json","styles.json"))equal(before.get(name),Files.readString(directory.resolve(PerfectGuardMigration.BACKUP).resolve(name)),"exact original backup "+name);
            for(String name:FILES)if(!List.of("combat.json","styles.json").contains(name))equal(before.get(name),Files.readString(directory.resolve(name)),"unrelated config bytes preserved "+name);
            Map<String,String> once=texts(directory);String marker=Files.readString(directory.resolve(PerfectGuardMigration.MARKER));
            PerfectGuardMigration.migrate(directory);equal(once,texts(directory),"completed migration is byte-idempotent");
            equal(marker,Files.readString(directory.resolve(PerfectGuardMigration.MARKER)),"completion marker remains stable");
            Files.delete(directory.resolve(PerfectGuardMigration.MARKER));
            JsonObject edit=read(directory,"combat.json").getAsJsonObject();edit.addProperty("maxSpirit",197);write(directory,"combat.json",edit);
            PerfectGuardMigration.migrate(directory);
            equal(197,read(directory,"combat.json").getAsJsonObject().get("maxSpirit").getAsInt(),"interrupted retry preserves later unrelated edits");
            for(String name:List.of("combat.json","styles.json"))equal(before.get(name),Files.readString(directory.resolve(PerfectGuardMigration.BACKUP).resolve(name)),"retry retains original backup "+name);
            edit=read(directory,"combat.json").getAsJsonObject();edit.addProperty("perfectGuardTicks",5);edit.getAsJsonObject("perfectGuardCounter").addProperty("windowTicks",8);write(directory,"combat.json",edit);
            PerfectGuardMigration.migrate(directory);
            equal(edit,read(directory,"combat.json"),"later explicit guard tuning is not re-migrated");
        }finally{cleanup(directory);}
    }
    private static void invalidAndRepair()throws Exception {
        Path directory=oldDirectory();
        try {
            JsonObject combat=read(directory,"combat.json").getAsJsonObject();combat.addProperty("maxSpirit",-1);write(directory,"combat.json",combat);
            Map<String,String> before=texts(directory);boolean rejected=false;
            try{PerfectGuardMigration.migrate(directory);}catch(RuntimeException expected){rejected=true;}
            check(rejected,"invalid complete candidate refuses migration");equal(before,texts(directory),"invalid candidate does not change files");
            check(!Files.exists(directory.resolve(PerfectGuardMigration.MARKER)),"invalid candidate cannot complete marker");
            check(!Files.exists(directory.resolve(PerfectGuardMigration.BACKUP)),"invalid candidate does not freeze a bad original");
            combat.addProperty("maxSpirit",100);write(directory,"combat.json",combat);PerfectGuardMigration.migrate(directory);
            equal(2,read(directory,"combat.json").getAsJsonObject().get("perfectGuardTicks").getAsInt(),"repaired config can retry guard upgrade");
        }finally{cleanup(directory);}
    }
    private static Path oldDirectory()throws Exception {
        Path directory=Files.createTempDirectory("fsrpg-perfect-existing-");new RpgConfig(directory).reload();Files.delete(directory.resolve(PerfectGuardMigration.MARKER));
        // Latest fresh seeds have six-tick windows; their earlier two-tick migration may already have backed them up.
        // Remove only that synthetic fixture pair so the historical upgrade below captures this fixture's true originals.
        Path previousBackup=directory.resolve(PerfectGuardMigration.BACKUP).toAbsolutePath().normalize();
        if(!previousBackup.startsWith(directory.toAbsolutePath().normalize()))throw new IllegalStateException("Historical backup outside fixture");
        if(Files.isDirectory(previousBackup)){
            for(String name:List.of("combat.json","styles.json"))Files.deleteIfExists(previousBackup.resolve(name));
            Files.delete(previousBackup);
        }
        JsonObject combat=read(directory,"combat.json").getAsJsonObject();combat.addProperty("perfectGuardTicks",4);combat.addProperty("parryStunTicks",8);combat.remove("perfectGuardCounter");write(directory,"combat.json",combat);
        JsonArray styles=read(directory,"styles.json").getAsJsonArray();for(var value:styles)for(String field:List.of("guard","heavyGuard")){
            JsonObject style=value.getAsJsonObject();if(!style.has(field)||!style.get(field).isJsonObject())continue;
            JsonObject guard=style.getAsJsonObject(field);if(guard.get("perfectGuardTicks").getAsInt()>0)guard.addProperty("perfectGuardTicks",3);guard.addProperty("parryStunTicks",8);
        }write(directory,"styles.json",styles);return directory;
    }
    private static JsonElement read(Path directory,String name)throws Exception{return JsonParser.parseString(Files.readString(directory.resolve(name)));}
    private static void write(Path directory,String name,JsonElement value)throws Exception{Files.writeString(directory.resolve(name)," \n"+RpgConfig.JSON.toJson(value)+"\n");}
    private static Map<String,String> texts(Path directory)throws Exception{var result=new LinkedHashMap<String,String>();for(String name:FILES)result.put(name,Files.readString(directory.resolve(name)));return result;}
    private static void cleanup(Path directory)throws Exception{Path root=directory.toAbsolutePath().normalize();try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList()){if(!file.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("Cleanup outside temp fixture");Files.deleteIfExists(file);}}}
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+": "+expected+" != "+actual);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}

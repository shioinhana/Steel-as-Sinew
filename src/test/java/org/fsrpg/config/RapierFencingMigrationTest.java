package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercise the actual durable migration with deterministic stock/custom model byte sources. */
public final class RapierFencingMigrationTest {
    private static int checks;
    private static final List<String> FILES=List.of("combat.json","skills.json","quests.json","weapons.json","styles.json","collections.json","skill_trees.json","archery.json");
    private static final List<String> MODELS=List.of("models/legacy_rapier.fsvoxel","models/legacy_rapier_fp.fsvoxel");
    private static final List<Path> OWNED=new ArrayList<>();
    public static void main(String[] args)throws Exception{run();}
    static void run()throws Exception {
        checks=0;
        try {
            freshDefaults();stockAndRetry();customFields();customModelsAndLegacy();customCatalogs();interruptedAndConcurrent();
            System.out.println("RapierFencingMigrationTest passed: "+checks+" checks");
        }finally {
            Path temp=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            for(Path dir:OWNED){if(!dir.toAbsolutePath().normalize().startsWith(temp)||!dir.getFileName().toString().startsWith("fsrpg-fencing-test-"))throw new AssertionError("Unexpected cleanup path");
                try(var files=Files.walk(dir)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
            OWNED.clear();
        }
    }
    private static void freshDefaults()throws Exception {
        Path dir=create();var definitions=new RpgConfig(dir).reload();
        equal(5,definitions.styles().get("rapier").leftCombo().size(),"fresh seed has five stages without requiring preexisting model files");
        for(String id:List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust")){
            var skill=definitions.skills().get(id);equal(3,skill.windupTicks(),"fresh authored windup survives legacy migration");equal(3,skill.activeTicks(),"fresh active ticks survive legacy migration");equal(3,skill.recoveryTicks(),"fresh recovery survives legacy migration");}
        var before=hashes(dir);new RpgConfig(dir).reload();equal(before,hashes(dir),"fresh reload does not rewrite seeded files");
    }
    private static void stockAndRetry()throws Exception {
        Path dir=fixture();var before=hashes(dir);var baseline=baseline();var models=nextModels();
        RapierFencingMigration.migrate(dir,baseline,models);
        var skills=read(dir,"skills.json").getAsJsonArray();equal(22,skills.size(),"stock 084 catalog adds just the fifth stable ID");
        equal(5,rapier(dir).getAsJsonArray("leftCombo").size(),"stock 084 chain expands to five");
        var fifth=find(skills,"rapier_lunge_finish");equal(4,fifth.get("windupTicks").getAsInt(),"finisher preparation");equal(3,fifth.get("activeTicks").getAsInt(),"finisher contacts");equal(5,fifth.get("recoveryTicks").getAsInt(),"finisher natural return");
        for(var value:baseline.getAsJsonArray("skills")){var old=value.getAsJsonObject();var next=find(skills,old.get("id").getAsString());equal(old.get("damage"),next.get("damage"),"old damage tuning stays unchanged");equal(3,next.get("activeTicks").getAsInt(),"stock active timing updates");}
        for(String name:MODELS){equal(RapierFencingMigration.digest(models.get(name)),hash(dir.resolve(name)),"only stock model bytes upgrade");equal(before.get(name),hash(dir.resolve(RapierFencingMigration.BACKUP).resolve(name)),"old model backup is byte exact");}
        for(String name:List.of("skills.json","styles.json","skill_trees.json"))equal(before.get(name),hash(dir.resolve(RapierFencingMigration.BACKUP).resolve(name)),"old JSON backup is byte exact");
        for(String name:List.of("combat.json","quests.json","weapons.json","collections.json","archery.json","user-custom.txt"))equal(before.get(name),hash(dir.resolve(name)),"unrelated user file is byte exact");
        var marker=read(dir,RapierFencingMigration.MARKER).getAsJsonObject();check(marker.getAsJsonObject("skillUpgrade").get("enabled").getAsBoolean(),"stock eligibility is recorded");
        check(root(dir).getAsJsonArray("skills").asList().stream().anyMatch(v->v.getAsString().equals("rapier_lunge_finish")),"stock root unlock list includes the finisher");
        var after=hashes(dir);RapierFencingMigration.migrate(dir,baseline,models);equal(after,hashes(dir),"completed migration is byte-idempotent");
    }
    private static void customFields()throws Exception {
        Path dir=fixture();var skills=read(dir,"skills.json").getAsJsonArray();var first=find(skills,"sword_left");first.addProperty("damage",31.25);first.addProperty("windupTicks",9);first.addProperty("label","My custom thrust");write(dir,"skills.json",skills);
        var trees=read(dir,"skill_trees.json").getAsJsonObject();var root=find(trees.getAsJsonArray("nodes"),"rapier");root.addProperty("description","My custom lesson");root.getAsJsonArray("skills").add("bow_bash");write(dir,"skill_trees.json",trees);
        RapierFencingMigration.migrate(dir,baseline(),nextModels());first=find(read(dir,"skills.json").getAsJsonArray(),"sword_left");
        equal(31.25,first.get("damage").getAsDouble(),"custom damage survives");equal(9,first.get("windupTicks").getAsInt(),"custom preparation survives");equal("My custom thrust",first.get("label").getAsString(),"custom label survives");equal(3,first.get("activeTicks").getAsInt(),"untouched active field still updates");
        equal("My custom lesson",root(dir).get("description").getAsString(),"custom root description survives");equal(root.get("skills"),root(dir).get("skills"),"custom root unlock list survives intact");equal(5,rapier(dir).getAsJsonArray("leftCombo").size(),"custom numeric tuning does not break a stock animated chain");
    }
    private static void customModelsAndLegacy()throws Exception {
        Path dir=fixture();Files.writeString(dir.resolve(MODELS.getFirst()),"user-authored-no-fifth-action");
        var jsonBefore=new LinkedHashMap<String,String>();for(String name:List.of("skills.json","styles.json","skill_trees.json"))jsonBefore.put(name,hash(dir.resolve(name)));
        LegacySkillMigration.migrate(dir);
        equal(4,rapier(dir).getAsJsonArray("leftCombo").size(),"an old missing legacy marker cannot select the new five-stage default");
        check(find(read(dir,"skills.json").getAsJsonArray(),"rapier_lunge_finish")==null,"historical legacy upgrade does not seed a missing fifth animation skill");
        for(String name:jsonBefore.keySet())jsonBefore.put(name,hash(dir.resolve(name)));
        RapierFencingMigration.migrate(dir,baseline(),nextModels());
        equal("user-authored-no-fifth-action",Files.readString(dir.resolve(MODELS.getFirst())),"custom authoritative model is never overwritten");
        equal(4,rapier(dir).getAsJsonArray("leftCombo").size(),"custom model without attack5 keeps its four-stage chain");
        check(find(read(dir,"skills.json").getAsJsonArray(),"rapier_lunge_finish")==null,"custom model cannot receive a dangling fifth skill");
        for(String name:jsonBefore.keySet())equal(jsonBefore.get(name),hash(dir.resolve(name)),"old custom-model catalog remains byte exact");
        equal(RapierFencingMigration.digest(nextModels().get(MODELS.get(1))),hash(dir.resolve(MODELS.get(1))),"the other stock model can still upgrade independently");
        Path missing=fixture();for(String name:MODELS)Files.delete(missing.resolve(name));RapierFencingMigration.migrate(missing,baseline(),nextModels());
        for(String name:MODELS)check(!Files.exists(missing.resolve(name)),"missing custom model is not seeded by upgrade");equal(4,rapier(missing).getAsJsonArray("leftCombo").size(),"missing models cannot enable five-stage chain");
    }
    private static void customCatalogs()throws Exception {
        Path animation=fixture();var skills=read(animation,"skills.json").getAsJsonArray();find(skills,"sword_left").addProperty("action","custom_attack");write(animation,"skills.json",skills);String old=hash(animation.resolve("skills.json"));
        RapierFencingMigration.migrate(animation,baseline(),nextModels());equal(old,hash(animation.resolve("skills.json")),"custom animation skill stays byte exact");equal(4,rapier(animation).getAsJsonArray("leftCombo").size(),"custom action does not acquire an incompatible finisher");
        Path chain=fixture();var styles=read(chain,"styles.json").getAsJsonArray();var combo=find(styles,"rapier").getAsJsonArray("leftCombo");var first=combo.get(0);combo.set(0,combo.get(1));combo.set(1,first);write(chain,"styles.json",styles);old=hash(chain.resolve("styles.json"));
        RapierFencingMigration.migrate(chain,baseline(),nextModels());equal(old,hash(chain.resolve("styles.json")),"custom chain order is byte exact");check(find(read(chain,"skills.json").getAsJsonArray(),"rapier_lunge_finish")==null,"custom chain does not cause an unused fifth ID insertion");
        Path collision=fixture();skills=read(collision,"skills.json").getAsJsonArray();var fifth=find(resource("skills.json").getAsJsonArray(),"rapier_lunge_finish").deepCopy();fifth.addProperty("damage",73);skills.add(fifth);write(collision,"skills.json",skills);old=hash(collision.resolve("skills.json"));
        RapierFencingMigration.migrate(collision,baseline(),nextModels());equal(old,hash(collision.resolve("skills.json")),"custom fifth ID collision stays byte exact");equal(4,rapier(collision).getAsJsonArray("leftCombo").size(),"a custom colliding ID is not silently attached to stock chain");
    }
    private static void interruptedAndConcurrent()throws Exception {
        Path dir=fixture();var baseline=baseline();var models=nextModels();RapierFencingMigration.migrate(dir,baseline,models);var completed=hashes(dir);
        Files.delete(dir.resolve(RapierFencingMigration.MARKER));Files.copy(dir.resolve(RapierFencingMigration.BACKUP).resolve("skills.json"),dir.resolve("skills.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dir.resolve(RapierFencingMigration.BACKUP).resolve(MODELS.get(1)),dir.resolve(MODELS.get(1)),StandardCopyOption.REPLACE_EXISTING);
        RapierFencingMigration.migrate(dir,baseline,models);equal(completed,hashes(dir),"interrupted JSON/model mix resumes the exact durable proposal and backups");
        Files.delete(dir.resolve(RapierFencingMigration.MARKER));var skills=read(dir,"skills.json").getAsJsonArray();find(skills,"sword_left").addProperty("damage",100);write(dir,"skills.json",skills);String custom=hash(dir.resolve("skills.json"));
        try{RapierFencingMigration.migrate(dir,baseline,models);throw new AssertionError("concurrent edit accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("Concurrent custom edit"),"interrupted migration rejects a new custom edit");}
        equal(custom,hash(dir.resolve("skills.json")),"rejected retry does not overwrite the custom edit");check(!Files.exists(dir.resolve(RapierFencingMigration.MARKER)),"rejected retry cannot claim completion");
    }
    private static Path fixture()throws Exception {
        Path dir=create();for(String name:FILES)write(dir,name,resource(name));
        write(dir,"skills.json",resource("legacy-0.8.4/skills.json"));write(dir,"styles.json",resource("legacy-0.8.4/styles.json"));
        var trees=read(dir,"skill_trees.json").getAsJsonObject();var root=find(trees.getAsJsonArray("nodes"),"rapier");var baseline=baseline();root.add("skills",baseline.get("rootSkills").deepCopy());root.add("description",baseline.get("rootDescription").deepCopy());write(dir,"skill_trees.json",trees);
        for(String name:MODELS){Files.createDirectories(dir.resolve(name).getParent());Files.write(dir.resolve(name),oldModel(name));}
        Files.writeString(dir.resolve("user-custom.txt"),"preserve bytes\r\ncustom setting = yes\r\n");return dir;
    }
    private static Path create()throws Exception{Path dir=Files.createTempDirectory("fsrpg-fencing-test-");OWNED.add(dir);return dir;}
    private static JsonObject baseline()throws Exception{var value=resource("rapier-fencing-0.8.4.json").getAsJsonObject();for(String name:MODELS)value.getAsJsonObject("models").addProperty(name,RapierFencingMigration.digest(oldModel(name)));return value;}
    private static byte[] oldModel(String name){return ("old-stock-084-"+name).getBytes(StandardCharsets.UTF_8);}
    private static Map<String,byte[]> nextModels(){Map<String,byte[]> result=new LinkedHashMap<>();for(String name:MODELS)result.put(name,("reviewed-five-stage-085-"+name).getBytes(StandardCharsets.UTF_8));return result;}
    private static JsonElement resource(String name)throws Exception{try(var in=RapierFencingMigrationTest.class.getResourceAsStream("/defaults/"+name)){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));}}
    private static JsonElement read(Path dir,String name)throws Exception{return JsonParser.parseString(Files.readString(dir.resolve(name)));}
    private static void write(Path dir,String name,JsonElement value)throws Exception{Files.writeString(dir.resolve(name),RpgConfig.JSON.toJson(value));}
    private static JsonObject rapier(Path dir)throws Exception{return find(read(dir,"styles.json").getAsJsonArray(),"rapier");}
    private static JsonObject root(Path dir)throws Exception{return find(read(dir,"skill_trees.json").getAsJsonObject().getAsJsonArray("nodes"),"rapier");}
    private static JsonObject find(JsonArray rows,String id){for(var value:rows){var row=value.getAsJsonObject();if(id.equals(row.get("id").getAsString()))return row;}return null;}
    private static String hash(Path path)throws Exception{return RapierFencingMigration.digest(Files.readAllBytes(path));}
    private static Map<String,String> hashes(Path dir)throws Exception{Map<String,String> result=new TreeMap<>();try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).toList())result.put(dir.relativize(file).toString().replace('\\','/'),hash(file));}return result;}
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual);}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}

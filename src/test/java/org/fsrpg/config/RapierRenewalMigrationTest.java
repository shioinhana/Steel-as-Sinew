package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Upgrade stock assets only; preserve authored settings, model overrides and recoverable originals. */
public final class RapierRenewalMigrationTest {
    private static int checks;
    private static final List<String> FILES=List.of("combat.json","skills.json","quests.json","weapons.json","styles.json","collections.json","skill_trees.json","archery.json");
    private static final List<String> MODELS=List.of("models/legacy_rapier.fsvoxel","models/legacy_rapier_fp.fsvoxel");
    private static final List<Path> OWNED=new ArrayList<>();
    public static void main(String[] args)throws Exception {
        try{stockAndRetry();customFields();customModels();fresh();System.out.println("RapierRenewalMigrationTest passed: "+checks+" checks");}
        finally {
            Path temp=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            for(Path dir:OWNED){if(!dir.toAbsolutePath().normalize().startsWith(temp)||!dir.getFileName().toString().startsWith("fsrpg-renewal-test-"))throw new AssertionError("Unexpected cleanup path");
                try(var files=Files.walk(dir)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        }
    }
    private static void stockAndRetry()throws Exception {
        Path dir=fixture();var before=hashes(dir);RapierRenewalMigration.migrate(dir,baseline(),models());
        JsonArray skills=read(dir,"skills.json").getAsJsonArray();
        equal("西洋剑 · 斜挑",find(skills,"rapier_downcut").get("label").getAsString(),"stock label follows new authored stroke");
        for(String id:List.of("rapier_riposte","rapier_reprise"))equal("legacy_rapier.fsvoxel",find(skills,id).get("model").getAsString(),"stock conditional branch uses new paired assets");
        for(String name:MODELS){equal(RapierFencingMigration.digest(models().get(name)),hash(dir.resolve(name)),"stock model upgraded");equal(before.get(name),hash(dir.resolve(RapierRenewalMigration.BACKUP).resolve(name)),"model original backed up exactly");}
        for(String name:List.of("skills.json","skill_trees.json"))equal(before.get(name),hash(dir.resolve(RapierRenewalMigration.BACKUP).resolve(name)),"configuration original backed up exactly");
        for(String name:List.of("styles.json","combat.json","weapons.json","quests.json","collections.json","archery.json","user.txt","models/rapier.fsvoxel"))equal(before.get(name),hash(dir.resolve(name)),"unrelated bytes preserved: "+name);
        var after=hashes(dir);RapierRenewalMigration.migrate(dir,baseline(),models());equal(after,hashes(dir),"completed reload is byte idempotent");
        Files.delete(dir.resolve(RapierRenewalMigration.MARKER));
        Files.copy(dir.resolve(RapierRenewalMigration.BACKUP).resolve("skills.json"),dir.resolve("skills.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dir.resolve(RapierRenewalMigration.BACKUP).resolve(MODELS.get(0)),dir.resolve(MODELS.get(0)),StandardCopyOption.REPLACE_EXISTING);
        RapierRenewalMigration.migrate(dir,baseline(),models());equal(after,hashes(dir),"interrupted JSON/model replacement resumes the same plan");
        Files.delete(dir.resolve(RapierRenewalMigration.MARKER));skills=read(dir,"skills.json").getAsJsonArray();find(skills,"sword_left").addProperty("damage",111);write(dir,"skills.json",skills);String custom=hash(dir.resolve("skills.json"));
        try{RapierRenewalMigration.migrate(dir,baseline(),models());throw new AssertionError("Concurrent custom edit accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("Concurrent custom edit"),"retry rejects edits after its durable plan");}
        equal(custom,hash(dir.resolve("skills.json")),"rejected retry leaves custom bytes intact");
    }
    private static void customFields()throws Exception {
        Path dir=fixture();JsonArray skills=read(dir,"skills.json").getAsJsonArray();var cut=find(skills,"rapier_downcut");cut.addProperty("label","自定义挑剑");cut.addProperty("damage",23.25);cut.addProperty("windupTicks",9);
        find(skills,"rapier_riposte").addProperty("action","my_custom_return");write(dir,"skills.json",skills);
        JsonObject trees=read(dir,"skill_trees.json").getAsJsonObject();find(trees.getAsJsonArray("nodes"),"rapier").addProperty("description","用户自己的课程");write(dir,"skill_trees.json",trees);
        RapierRenewalMigration.migrate(dir,baseline(),models());skills=read(dir,"skills.json").getAsJsonArray();cut=find(skills,"rapier_downcut");
        equal("自定义挑剑",cut.get("label").getAsString(),"custom label preserved");equal(23.25,cut.get("damage").getAsDouble(),"custom damage preserved");equal(9,cut.get("windupTicks").getAsInt(),"custom timing preserved");
        equal("rapier.fsvoxel",find(skills,"rapier_riposte").get("model").getAsString(),"custom action cannot be silently moved to new model");
        equal("my_custom_return",find(skills,"rapier_riposte").get("action").getAsString(),"custom action preserved");
        equal("legacy_rapier.fsvoxel",find(skills,"rapier_reprise").get("model").getAsString(),"other untouched technique migrates independently");
        equal("用户自己的课程",find(read(dir,"skill_trees.json").getAsJsonObject().getAsJsonArray("nodes"),"rapier").get("description").getAsString(),"custom lesson preserved");
    }
    private static void customModels()throws Exception {
        Path dir=fixture();Files.writeString(dir.resolve(MODELS.get(0)),"custom third-person authored model");var before=hashes(dir);
        RapierRenewalMigration.migrate(dir,baseline(),models());equal(before.get(MODELS.get(0)),hash(dir.resolve(MODELS.get(0))),"custom authoritative model is never overwritten");
        for(String name:List.of("skills.json","skill_trees.json"))equal(before.get(name),hash(dir.resolve(name)),"incompatible paired models retain old animation references and descriptions");
        Path legacy=fixture();Files.writeString(legacy.resolve("models/rapier.fsvoxel"),"custom old return animations");RapierRenewalMigration.migrate(legacy,baseline(),models());
        for(String id:List.of("rapier_riposte","rapier_reprise"))equal("rapier.fsvoxel",find(read(legacy,"skills.json").getAsJsonArray(),id).get("model").getAsString(),"custom older technique assets keep their configured reference");
        Path missing=fixture();Files.delete(missing.resolve(MODELS.get(1)));before=hashes(missing);RapierRenewalMigration.migrate(missing,baseline(),models());
        check(!Files.exists(missing.resolve(MODELS.get(1))),"migration does not manufacture a missing custom model");equal(before.get("skills.json"),hash(missing.resolve("skills.json")),"missing paired model does not create dangling branch actions");
    }
    private static void fresh()throws Exception {
        Path dir=create();var d=new RpgConfig(dir).reload();equal("legacy_rapier.fsvoxel",d.skills().get("rapier_reprise").model(),"fresh installs use new branch assets");
        var before=hashes(dir);new RpgConfig(dir).reload();equal(before,hashes(dir),"fresh seed plus all historical migrations stays idempotent");
    }
    private static Path fixture()throws Exception {
        Path dir=create();for(String name:FILES)write(dir,name,resource(name));
        JsonArray skills=read(dir,"skills.json").getAsJsonArray();var oldBaseline=baseline();
        for(var value:oldBaseline.getAsJsonArray("skills")) {var old=value.getAsJsonObject();for(int i=0;i<skills.size();i++)if(skills.get(i).getAsJsonObject().get("id").equals(old.get("id")))skills.set(i,old.deepCopy());}
        write(dir,"skills.json",skills);JsonObject trees=read(dir,"skill_trees.json").getAsJsonObject();
        for(var value:oldBaseline.getAsJsonArray("nodes")){var old=value.getAsJsonObject();find(trees.getAsJsonArray("nodes"),old.get("id").getAsString()).add("description",old.get("description").deepCopy());}
        write(dir,"skill_trees.json",trees);
        for(String name:List.of(MODELS.get(0),MODELS.get(1),"models/rapier.fsvoxel")){Files.createDirectories(dir.resolve(name).getParent());Files.write(dir.resolve(name),old(name));}
        Files.writeString(dir.resolve("user.txt"),"keep my file\r\n");return dir;
    }
    private static Path create()throws Exception{Path dir=Files.createTempDirectory("fsrpg-renewal-test-");OWNED.add(dir);return dir;}
    private static byte[] old(String name){return ("old-stock-095-"+name).getBytes(StandardCharsets.UTF_8);}
    private static Map<String,byte[]> models(){Map<String,byte[]> result=new LinkedHashMap<>();for(String name:MODELS)result.put(name,("reviewed-0100-"+name).getBytes(StandardCharsets.UTF_8));return result;}
    private static JsonObject baseline()throws Exception{var json=resource("rapier-renewal-0.9.5.json").getAsJsonObject();for(var entry:json.getAsJsonObject("models").entrySet())entry.setValue(new JsonPrimitive(RapierFencingMigration.digest(old(entry.getKey()))));return json;}
    private static JsonElement resource(String name)throws Exception{try(var in=RapierRenewalMigrationTest.class.getResourceAsStream("/defaults/"+name)){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));}}
    private static JsonElement read(Path dir,String name)throws Exception{return JsonParser.parseString(Files.readString(dir.resolve(name)));}
    private static void write(Path dir,String name,JsonElement value)throws Exception{Files.writeString(dir.resolve(name),RpgConfig.JSON.toJson(value));}
    private static JsonObject find(JsonArray rows,String id){for(var value:rows){var row=value.getAsJsonObject();if(id.equals(row.get("id").getAsString()))return row;}return null;}
    private static String hash(Path path)throws Exception{return RapierFencingMigration.digest(Files.readAllBytes(path));}
    private static Map<String,String> hashes(Path dir)throws Exception{Map<String,String> values=new TreeMap<>();try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).toList())values.put(dir.relativize(file).toString().replace('\\','/'),hash(file));}return values;}
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}

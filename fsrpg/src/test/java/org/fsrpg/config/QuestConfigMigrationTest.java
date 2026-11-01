package org.fsrpg.config;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.quest.*;

/** Upgrade preservation, strict user JSON, and transactional task creation without Minecraft registries. */
public final class QuestConfigMigrationTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        preserveLegacyAndSeedOnce();strictDefinitionsAndCreateTransaction();strictGuildSettings();
        System.out.println("QuestConfigMigrationTest passed: "+checks+" checks");
    }
    private static void preserveLegacyAndSeedOnce() throws Exception {
        Path dir=Files.createTempDirectory("fsrpg-guild-upgrade-");
        try {
            JsonObject original=oldQuest("custom:family_request");original.addProperty("label","祖传委托 自定名");original.addProperty("description","保留原样的自定义任务");
            JsonObject sameId=oldQuest("fsrpg:guild_supplies");sameId.addProperty("label","用户已有的公会募集");
            sameId.getAsJsonArray("objectives").get(0).getAsJsonObject().addProperty("count",7);
            JsonArray before=new JsonArray();before.add(original);before.add(sameId);
            String originalText=" \n"+RpgConfig.JSON.toJson(before)+"\n";Files.writeString(dir.resolve("quests.json"),originalText,StandardCharsets.UTF_8);
            JsonObject customized=new JsonObject();customized.addProperty("maxTracked",7);customized.addProperty("dailyCount",2);
            customized.addProperty("timezone","UTC");customized.add("promotions",new JsonArray());
            String guildText=RpgConfig.JSON.toJson(customized);Files.writeString(dir.resolve("guild.json"),guildText,StandardCharsets.UTF_8);
            RpgConfig config=new RpgConfig(dir);var loaded=config.reload();JsonArray after=read(dir,"quests.json").getAsJsonArray();
            eq(original,find(after,"custom:family_request"),"legacy custom definition every original JSON field preserved");
            eq(sameId,find(after,"fsrpg:guild_supplies"),"same-ID guild definition never overwritten by seed");
            eq(originalText,Files.readString(dir.resolve("quest-system-backup-v1/quests.json")),"original task backup is byte-for-byte text");
            int expected=2;for(JsonElement addition:resource("guild_quests.json").getAsJsonArray())if(!addition.getAsJsonObject().get("id").getAsString().equals("fsrpg:guild_supplies"))expected++;
            eq(expected,after.size(),"only missing guild IDs appended");
            eq(guildText,Files.readString(dir.resolve("guild.json")),"custom global settings bytes preserved");
            var settings=QuestSettingsConfig.load(dir);eq(7,settings.maxTracked(),"custom tracking limit preserved");eq("UTC",settings.timezone(),"custom timezone preserved");
            String seeded=Files.readString(dir.resolve("quests.json"));config.seed();config.reload();eq(seeded,Files.readString(dir.resolve("quests.json")),"repeated seed/reload idempotent");
            eq(true,loaded.quests().get("custom:family_request").rules().allowReacceptAfterAbandon(),"old task missing entire rules retains reaccept default");
            JsonObject partial=oldQuest("custom:partial_rules");partial.add("rules",new JsonObject());var q=RpgConfig.JSON.fromJson(partial,QuestDefinition.class);
            eq(true,q.rules().allowReacceptAfterAbandon(),"provided partial rules with absent boolean also default true");
            var service=new QuestService(QuestCatalog.of(List.of(q)));UUID player=UUID.randomUUID();service.accept(player,q.id());service.abandon(player,q.id());service.accept(player,q.id());
            eq(QuestService.Status.ACTIVE,service.progress(player,q.id()).status(),"legacy custom task can abandon and reaccept");
            eq(1,find(after,"custom:family_request").getAsJsonArray("objectives").size(),"no new objective silently added to old task");
            ok(Files.isRegularFile(dir.resolve("quest-system-migration-v1.json")),"migration marker saved");
        } finally {removeTemporary(dir);}
    }
    private static void strictDefinitionsAndCreateTransaction() throws Exception {
        Path dir=Files.createTempDirectory("fsrpg-guild-create-");
        try {
            RpgConfig config=new RpgConfig(dir);var original=config.reload();String before=Files.readString(dir.resolve("quests.json"));
            JsonObject duplicate=oldQuest("fsrpg:guild_supplies");rejects(()->config.createQuest(duplicate),"duplicate task ID rejected");
            eq(before,Files.readString(dir.resolve("quests.json")),"duplicate rejection preserves source bytes");eq(original,config.current(),"duplicate rejection preserves live definitions");
            List<JsonObject> invalid=new ArrayList<>();
            for(JsonElement duration:List.of(new JsonPrimitive(1.5),new JsonPrimitive("10"),new JsonPrimitive(-1),new JsonPrimitive(31_536_001L),new JsonPrimitive(true))) {
                JsonObject q=oldQuest("custom:invalid_duration");JsonObject rules=new JsonObject();rules.add("completionCooldownSeconds",duration);q.add("rules",rules);invalid.add(q);
            }
            for(JsonElement level:List.of(new JsonPrimitive(0.5),new JsonPrimitive("10"),new JsonPrimitive(-1))) {
                JsonObject q=oldQuest("custom:invalid_level");JsonObject criteria=new JsonObject();criteria.add("minLevel",level);q.getAsJsonArray("objectives").get(0).getAsJsonObject().add("criteria",criteria);invalid.add(q);
            }
            for(JsonElement weight:List.of(new JsonPrimitive(-1),new JsonPrimitive(0),new JsonPrimitive(Double.NaN),new JsonPrimitive(Double.POSITIVE_INFINITY),new JsonPrimitive("1"))) {
                JsonObject q=oldQuest("custom:invalid_weight");JsonObject rewards=new JsonObject();JsonArray pool=new JsonArray();JsonObject entry=new JsonObject();entry.add("item",JsonParser.parseString("{\"id\":\"minecraft:apple\",\"count\":1}"));entry.add("weight",weight);pool.add(entry);rewards.add("randomPool",pool);q.add("rewards",rewards);invalid.add(q);
            }
            JsonObject unknown=oldQuest("custom:unknown_rank");JsonObject rules=new JsonObject();rules.addProperty("minimumRank","Z");unknown.add("rules",rules);invalid.add(unknown);
            for(JsonObject bad:invalid) {
                rejects(()->config.createQuest(bad),"strict invalid custom task rejected: "+bad.get("id"));
                eq(before,Files.readString(dir.resolve("quests.json")),"invalid creation leaves source unchanged");
                eq(original,config.current(),"invalid creation leaves live definitions unchanged");
            }
            // Registry-free adapter contract: inject the same validation hook used by the server.
            config.setValidator(definitions->{for(var q:definitions.quests().definitions())for(var objective:q.objectives())
                if(objective.type()==QuestDefinition.ObjectiveType.KILL && objective.target().equals("custom:missing_entity"))throw new IllegalArgumentException("Unknown entity custom:missing_entity");});
            JsonObject badReference=oldQuest("custom:missing_entity_task");badReference.getAsJsonArray("objectives").get(0).getAsJsonObject().addProperty("target","custom:missing_entity");
            rejects(()->config.createQuest(badReference),"server reference validation runs before task file mutation");
            eq(before,Files.readString(dir.resolve("quests.json")),"failed entity reference keeps exact task file");
            JsonObject valid=oldQuest("custom:created_task");var updated=config.createQuest(valid);
            ok(updated.quests().contains("custom:created_task"),"valid custom task enters live catalog");
            eq(valid,find(read(dir,"quests.json").getAsJsonArray(),"custom:created_task"),"created JSON keeps exact supplied fields");
            eq(before,Files.readString(dir.resolve("quests.json.bak")),"create stores previous exact task file backup");
            String after=Files.readString(dir.resolve("quests.json"));rejects(()->config.createQuest(valid),"created stable ID cannot be created twice");
            eq(after,Files.readString(dir.resolve("quests.json")),"second creation cannot change task source");
        } finally {removeTemporary(dir);}
    }
    private static void strictGuildSettings() throws Exception {
        Path dir=Files.createTempDirectory("fsrpg-guild-settings-");
        try {
            JsonObject baseline=resource("guild.json").getAsJsonObject();
            for(String field:List.of("maxTracked","dailyCount"))for(JsonElement bad:List.of(new JsonPrimitive(2.5),new JsonPrimitive("3"),new JsonPrimitive(true),JsonNull.INSTANCE)) {
                JsonObject json=baseline.deepCopy();json.add(field,bad);write(dir,"guild.json",json);rejects(()->QuestSettingsConfig.load(dir),"strict "+field+" rejects fractional/string/boolean/null");
            }
            JsonObject json=baseline.deepCopy();json.getAsJsonArray("promotions").get(0).getAsJsonObject().addProperty("totalCompletions",2.5);write(dir,"guild.json",json);
            rejects(()->QuestSettingsConfig.load(dir),"strict promotion total rejects fractional");
            json=baseline.deepCopy();json.getAsJsonArray("promotions").get(0).getAsJsonObject().getAsJsonObject("requiredQuests").addProperty("fsrpg:guild_supplies",0.25);write(dir,"guild.json",json);
            rejects(()->QuestSettingsConfig.load(dir),"strict per-task promotion counts reject fractional");
            json=baseline.deepCopy();json.getAsJsonArray("promotions").get(0).getAsJsonObject().addProperty("rank","Z");write(dir,"guild.json",json);
            rejects(()->QuestSettingsConfig.load(dir),"unknown promotion rank rejected");
            json=baseline.deepCopy();json.addProperty("typoTracking",8);write(dir,"guild.json",json);rejects(()->QuestSettingsConfig.load(dir),"unknown global field rejected");
            write(dir,"guild.json",baseline);eq(3,QuestSettingsConfig.load(dir).maxTracked(),"valid bundled settings load after repair");
        } finally {removeTemporary(dir);}
    }
    private static JsonObject oldQuest(String id){return JsonParser.parseString("{\"id\":\""+id+"\",\"label\":\"Old custom\",\"description\":\"Keep me\",\"objectives\":[{\"id\":\"zombie\",\"type\":\"KILL\",\"target\":\"minecraft:zombie\",\"count\":2}],\"rewards\":{\"items\":[{\"id\":\"minecraft:bread\",\"count\":3}],\"xp\":7},\"repeatable\":true,\"autoAccept\":false}").getAsJsonObject();}
    private static JsonObject find(JsonArray tasks,String id){for(JsonElement entry:tasks)if(entry.getAsJsonObject().get("id").getAsString().equals(id))return entry.getAsJsonObject();throw new AssertionError("Missing "+id);}
    private static JsonElement resource(String file)throws Exception{try(var in=QuestConfigMigrationTest.class.getResourceAsStream("/defaults/"+file)){return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));}}
    private static JsonElement read(Path dir,String name)throws Exception{return JsonParser.parseString(Files.readString(dir.resolve(name),StandardCharsets.UTF_8));}
    private static void write(Path dir,String name,JsonElement json)throws Exception{Files.writeString(dir.resolve(name),json.toString(),StandardCharsets.UTF_8);}
    private static void removeTemporary(Path dir)throws Exception{Path root=dir.toAbsolutePath().normalize();try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList()){if(!path.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("test cleanup escaped root");Files.deleteIfExists(path);}}}
    private static void ok(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    private static void eq(Object expected,Object actual,String text){checks++;if(!Objects.equals(expected,actual))throw new AssertionError(text+": expected "+expected+", got "+actual);}
    private static void rejects(Checked action,String text){checks++;try{action.run();}catch(Exception expected){return;}throw new AssertionError(text+": unexpectedly succeeded");}
    @FunctionalInterface private interface Checked{void run()throws Exception;}
}

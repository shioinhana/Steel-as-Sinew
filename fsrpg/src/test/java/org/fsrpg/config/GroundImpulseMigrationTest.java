package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.combat.BurstDashRules;

/** An existing 0.7.8 config gains only an explicit initial speed; all old tuning and exact source bytes survive. */
public final class GroundImpulseMigrationTest {
    private static int checks;
    public static void main(String[] args)throws Exception{fresh();old();customAndInvalid();System.out.println("GroundImpulseMigrationTest passed: "+checks+" checks");}
    private static void fresh()throws Exception{Path dir=fixture();try{
        check(Files.exists(dir.resolve(GroundImpulseMigration.MARKER)),"fresh config receives completion marker");
        check(!Files.exists(dir.resolve(GroundImpulseMigration.BACKUP)),"fresh config invents no prior backup");
        equal(27.0,new RpgConfig(dir).reload().combat().burstDash().initialSpeed(),"new seed uses 27 blocks per second");
    }finally{cleanup(dir);}}
    private static void old()throws Exception{for(double distance:new double[]{.25,3,7,32}){Path dir=fixture();try{
        Files.delete(dir.resolve(GroundImpulseMigration.MARKER));var before=read(dir);var burst=before.getAsJsonObject("burstDash");burst.remove("initialSpeed");
        burst.addProperty("distance",distance);burst.addProperty("runSpeed",0);before.addProperty("dodgeDistance",4.5);before.addProperty("allowAirDodge",true);
        String entry=" \n"+before+"\n";Files.writeString(dir.resolve("combat.json"),entry);var inventory=bytes(dir);
        var config=new RpgConfig(dir);var parsed=config.reload();var expected=before.deepCopy();expected.getAsJsonObject("burstDash").addProperty("initialSpeed",BurstDashRules.legacyInitialSpeed(distance));
        equal(expected,read(dir),"only missing initial speed added to custom legacy fields");equal(BurstDashRules.legacyInitialSpeed(distance),parsed.combat().burstDash().initialSpeed(),"custom legacy distance is conservatively mapped to an initial speed");
        equal(entry,Files.readString(dir.resolve(GroundImpulseMigration.BACKUP).resolve("combat.json")),"exact pre-upgrade bytes backed up");
        var after=bytes(dir);var added=new HashSet<>(after.keySet());added.removeAll(inventory.keySet());equal(Set.of(GroundImpulseMigration.MARKER,GroundImpulseMigration.BACKUP+"/combat.json"),added,"exactly one new marker and one exact backup");
        for(var file:inventory.entrySet())if(!file.getKey().equals("combat.json"))check(Arrays.equals(file.getValue(),after.get(file.getKey())),"unrelated file preserved "+file.getKey());
        config.reload();sameBytes(after,bytes(dir),"reload byte-idempotent");
    }finally{cleanup(dir);}}}
    private static void customAndInvalid()throws Exception{Path dir=fixture();try{
        Files.delete(dir.resolve(GroundImpulseMigration.MARKER));var combat=read(dir);combat.getAsJsonObject("burstDash").addProperty("initialSpeed",19.25);Files.writeString(dir.resolve("combat.json"),combat.toString());
        String custom=Files.readString(dir.resolve("combat.json"));new RpgConfig(dir).reload();equal(custom,Files.readString(dir.resolve("combat.json")),"explicit custom speed remains byte exact");
        check(!Files.exists(dir.resolve(GroundImpulseMigration.BACKUP)),"already explicit initial speed needs no invented backup");
        Files.delete(dir.resolve(GroundImpulseMigration.MARKER));
        for(String bad:List.of("null","\"27\"","NaN","Infinity","true","[]","{}","0","80.01")){
            var invalid=combat.deepCopy();invalid.getAsJsonObject("burstDash").add("initialSpeed",JsonParser.parseString(bad));Files.writeString(dir.resolve("combat.json"),invalid.toString());var before=bytes(dir);
            boolean rejected=false;try{GroundImpulseMigration.capture(dir).apply();}catch(RuntimeException expected){rejected=true;}
            check(rejected,"invalid explicit speed rejected "+bad);sameBytes(before,bytes(dir),"invalid migration does not write anything "+bad);
        }
    }finally{cleanup(dir);}}
    private static Path fixture()throws Exception{Path p=Files.createTempDirectory("fsrpg-ground-impulse-");new RpgConfig(p).reload();return p;}
    private static JsonObject read(Path dir)throws Exception{return JsonParser.parseString(Files.readString(dir.resolve("combat.json"))).getAsJsonObject();}
    private static Map<String,byte[]> bytes(Path dir)throws Exception{var result=new TreeMap<String,byte[]>();try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).toList())result.put(dir.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));}return result;}
    private static void sameBytes(Map<String,byte[]> a,Map<String,byte[]> b,String message){equal(a.keySet(),b.keySet(),message+" inventory");for(String key:a.keySet())check(Arrays.equals(a.get(key),b.get(key)),message+" "+key);}
    private static void cleanup(Path dir)throws Exception{Path root=dir.toAbsolutePath().normalize();try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList()){if(!file.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("fixture escape");Files.deleteIfExists(file);}}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+": "+expected+" != "+actual);}
}

package org.fsrpg.creature;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

public final class GuardCueWindowTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        for(int lead=1;lead<=60;lead++)for(int length=1;length<=lead;length++){
            var settings=new GuardCueSettings(true,lead,length,120,true);
            check(settings.windupTicks(1)==lead,"Short windup remains readable");
            var window=settings.window(100,lead+3);
            check(window.flashGameTick()==103,"Flash relative to real contact");
            for(long time=100;time<=window.contactGameTick()+2;time++){
                var probe=settings.window(100,lead+3);
                boolean valid=time>=probe.openGameTick()&&time<=probe.closeGameTick();
                check(probe.press(time,1)==valid,"Exact inclusive input bounds");
                check(!probe.consume(probe.contactGameTick()-1,1,true),"Never perfect before attack contact");
                check(probe.consume(probe.contactGameTick(),1,true)==valid,"Only authorized attack may consume");
                check(!probe.consume(probe.contactGameTick()+1,1,true),"One contact credit only");
            }
            var released=settings.window(100,lead+3);released.press(released.openGameTick(),3);
            check(!released.consume(released.contactGameTick(),3,false),"Release cancels eligibility");
            check(!released.consume(released.contactGameTick(),4,true),"Repress cannot revive older credit");
            var canceled=settings.window(100,lead+3);canceled.press(canceled.openGameTick(),1);canceled.cancel();
            check(!canceled.consume(canceled.contactGameTick(),1,true),"Canceled attack carries no credit");
            var other=settings.window(100,lead+3);
            check(!other.consume(other.contactGameTick(),1,true),"Unrelated attack cannot borrow a press");
        }
        var disabled=new GuardCueSettings(false,6,6,120,true);check(disabled.windupTicks(2)==2,"Disabled cue keeps authored timing");
        for(String json:new String[]{"{}","{\"enabled\":true}","{\"enabled\":true,\"flashLeadTicks\":6,\"inputWindowTicks\":7,\"flashDurationMillis\":120,\"guardCancelsActive\":true}"}){
            boolean rejected=false;try{GuardCueSettings.parse(json);}catch(RuntimeException expected){rejected=true;}check(rejected,"Invalid settings rejected");
        }
        fractionalSchedule();fractionalConfiguration();singleContactTick();closeMigration();
        System.out.println("GuardCueWindowTest passed: "+checks+" checks");
    }

    private static void fractionalSchedule(){
        var settings=GuardCueSettings.defaults();
        check(settings.timingMode().equals("windup_fraction")&&settings.flashAtFraction()==.2
                &&settings.windowOpenFraction()==.6&&settings.windowCloseFraction()==.8,"Default flash/open/close fractions are one/three/four fifths");
        for(int windup=1;windup<=600;windup++)for(long start:new long[]{100,9_007_199_254_740_993L,Long.MAX_VALUE-windup}){
            var window=settings.window(start,windup);
            long flash=windup/5,open=(3L*windup+4)/5,close=4L*windup/5;
            boolean inputExists=open<=close;
            check(settings.windupTicks(windup)==windup,"Fraction timing never extends the authored contact");
            check(window.flashGameTick()==start+flash&&window.openGameTick()==start+open&&window.closeGameTick()==start+close
                    &&window.contactGameTick()==start+windup,"Integer rounding preserves exact fractional deadlines even beyond double precision");
            check(!window.press(start+open-1,1)&&window.press(start+open,1)==inputExists,"Input opens at ceil three-fifths or remains quantized empty");
            check(!window.consume(start+windup-1,1,true)&&window.consume(start+windup,1,true)==inputExists,"Earlier legitimate input survives the final forbidden fifth through contact");
            check(!window.consume(start+windup,1,true),"One native contact consumes its token once");
            var late=settings.window(start,windup);
            check(!late.press(start+close+1,2)&&!late.press(start+windup,3),"Final fifth and contact refuse new precision input");
            check(late.acceptsClash(start+open)&&late.acceptsClash(start+windup),"Melee clashes retain opening through contact independently of guard close");
            check(!late.acceptsClash(start+open-1),"Clash cannot start before three-fifths");
            if(start+windup<Long.MAX_VALUE)check(!late.acceptsClash(start+windup+1),"Clash cannot start after real contact");
            var canceled=settings.window(start,windup);canceled.press(start+open,4);canceled.cancel();
            check(!canceled.acceptsClash(start+windup)&&!canceled.consume(start+windup,4,true),"Cancellation kills both defensive input and clash availability");
        }
        var fixed=new GuardCueSettings(true,7,7,120,true);
        check(fixed.timingMode().equals("fixed_ticks")&&fixed.windupTicks(1)==7&&fixed.window(100,1).accepts(107),"Explicit fixed-tick compatibility stays contact-inclusive");
    }

    private static void fractionalConfiguration(){
        String legacy="{\"enabled\":true,\"flashLeadTicks\":7,\"inputWindowTicks\":7,\"flashDurationMillis\":120,\"guardCancelsActive\":true}";
        var old=GuardCueSettings.parse(legacy);
        check(old.equals(GuardCueSettings.defaults())&&old.windupTicks(1)==1,"Existing five-field JSON adopts fraction defaults without requiring a file rewrite");
        check(GuardCueSettings.parse(append(legacy,"\"timingMode\":\"fixed_ticks\"")).windupTicks(1)==7,"Explicit fixed-tick JSON retains its former readable lead extension");
        var custom=GuardCueSettings.parse(append(legacy,"\"timingMode\":\"windup_fraction\",\"flashAtFraction\":0.25,\"windowOpenFraction\":0.75"));
        var customWindow=custom.window(50,7);
        check(customWindow.flashGameTick()==51&&customWindow.openGameTick()==56&&customWindow.contactGameTick()==57,"Finite custom fractions control floor flash and ceil opening");
        check(GuardCueSettings.parse(append(legacy,"\"flashAtFraction\":0.1")).windowOpenFraction()==.6,"Independently omitted optional fields receive their current defaults");
        for(String fields:new String[]{"\"timingMode\":null","\"timingMode\":\"fraction\"","\"timingMode\":1","\"flashAtFraction\":null","\"windowOpenFraction\":null",
                "\"flashAtFraction\":-0.01","\"flashAtFraction\":1","\"windowOpenFraction\":1.01","\"windowOpenFraction\":-0.01",
                "\"flashAtFraction\":0.7,\"windowOpenFraction\":0.6","\"flashAtFraction\":\"0.2\"","\"windowOpenFraction\":true",
                "\"flashAtFraction\":NaN","\"windowOpenFraction\":Infinity","\"unknown\":1"}){
            reject(()->GuardCueSettings.parse(append(legacy,fields)),"Invalid optional timing field rejected: "+fields);
        }
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            reject(()->new GuardCueSettings(true,7,7,120,true,"windup_fraction",bad,.6),"Nonfinite flash fraction rejected");
            reject(()->new GuardCueSettings(true,7,7,120,true,"windup_fraction",.2,bad),"Nonfinite window fraction rejected");
            reject(()->new GuardCueSettings(true,7,7,120,true,"windup_fraction",.2,.6,bad),"Nonfinite close fraction rejected");
        }
    }

    private static void closeMigration()throws Exception{
        Path root=Files.createTempDirectory("fsrpg-guard-close-");
        try{
            // A five-field legacy file needs no rewrite: parse alone applies the four-fifths default.
            String legacy="{\"enabled\":true,\"flashLeadTicks\":6,\"inputWindowTicks\":6,\"flashDurationMillis\":120,\"guardCancelsActive\":true}";
            Path legacyFile=write(root,"legacy","guard_cues.json",legacy);
            var legacySettings=GuardCueSettings.read(legacyFile);
            check(legacySettings.windowCloseFraction()==.8,"Five-field legacy JSON adopts the four-fifths close point");
            check(legacyFile.toFile().length()==legacy.length()&&Files.readString(legacyFile,StandardCharsets.UTF_8).equals(legacy),
                    "A legacy file that omitted the close fraction is never rewritten");
            check(!Files.exists(legacyFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_BACKUP))
                    &&!Files.exists(legacyFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_MARKER)),
                    "A pure omission creates no migration evidence");

            // The pre-0.9 implied default (explicit one) is translated once; every other custom value survives.
            String old="{\"enabled\":false,\"flashLeadTicks\":9,\"inputWindowTicks\":5,\"flashDurationMillis\":200,\"guardCancelsActive\":false,"
                    +"\"timingMode\":\"windup_fraction\",\"flashAtFraction\":0.25,\"windowOpenFraction\":0.65,\"windowCloseFraction\":1}";
            Path oldFile=write(root,"old","guard_cues.json",old);
            byte[] original=Files.readAllBytes(oldFile);
            var migrated=GuardCueSettings.read(oldFile);
            check(migrated.windowCloseFraction()==.8,"Explicit old close=1 migrates to four fifths");
            check(migrated.windowOpenFraction()==.65&&migrated.flashAtFraction()==.25&&migrated.flashLeadTicks()==9
                    &&migrated.inputWindowTicks()==5&&migrated.flashDurationMillis()==200&&!migrated.enabled()&&!migrated.guardCancelsActive(),
                    "Migration preserves every custom value and field");
            check(!migrated.equals(GuardCueSettings.defaults()),"Migration does not reset unrelated custom tuning");
            check(Arrays.equals(original,Files.readAllBytes(oldFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_BACKUP).resolve("guard_cues.json"))),
                    "The exact pre-migration bytes are backed up");
            var marker=JsonParser.parseString(Files.readString(oldFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_MARKER),StandardCharsets.UTF_8)).getAsJsonObject();
            check(marker.get("schemaVersion").getAsInt()==1&&marker.get("previousValue").getAsDouble()==1
                    &&marker.get("windowCloseFraction").getAsDouble()==.8,"Migration marker records the one-time field translation");
            check(GuardCueSettings.parse(Files.readString(oldFile,StandardCharsets.UTF_8)).windowCloseFraction()==.8,
                    "The rewritten file parses to the migrated boundary");
            byte[] rewritten=Files.readAllBytes(oldFile);
            GuardCueSettings.read(oldFile);GuardCueSettings.read(oldFile);
            check(Arrays.equals(rewritten,Files.readAllBytes(oldFile)),"Repeat reads can never migrate the same directory twice");

            // A custom close point is never the old default; it stays untouched without any backup.
            String custom="{\"enabled\":true,\"flashLeadTicks\":7,\"inputWindowTicks\":7,\"flashDurationMillis\":120,\"guardCancelsActive\":true,"
                    +"\"timingMode\":\"windup_fraction\",\"flashAtFraction\":0.2,\"windowOpenFraction\":0.6,\"windowCloseFraction\":0.9}";
            Path customFile=write(root,"custom","guard_cues.json",custom);
            check(GuardCueSettings.read(customFile).windowCloseFraction()==.9,"A custom close fraction is preserved");
            check(!Files.exists(customFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_BACKUP))
                    &&!Files.exists(customFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_MARKER)),
                    "Custom tuning creates no fictitious migration");

            // Contact-only is still a legal explicit custom convention; translation must not invent a window.
            String contact="{\"enabled\":true,\"flashLeadTicks\":7,\"inputWindowTicks\":7,\"flashDurationMillis\":120,\"guardCancelsActive\":true,"
                    +"\"timingMode\":\"windup_fraction\",\"flashAtFraction\":0.2,\"windowOpenFraction\":1,\"windowCloseFraction\":1}";
            Path contactFile=write(root,"contact","guard_cues.json",contact);
            check(GuardCueSettings.read(contactFile).windowCloseFraction()==1,"Explicit contact-only windows remain available");
            check(!Files.exists(contactFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_BACKUP)),
                    "Contact-only configuration is not mistaken for the old default");

            // An invalid complete candidate must never touch the original file or its evidence directories.
            String invalid="{\"enabled\":true,\"flashLeadTicks\":7,\"inputWindowTicks\":7,\"flashDurationMillis\":120,\"guardCancelsActive\":true,"
                    +"\"timingMode\":\"windup_fraction\",\"flashAtFraction\":-0.5,\"windowOpenFraction\":0.6,\"windowCloseFraction\":1}";
            Path invalidFile=write(root,"invalid","guard_cues.json",invalid);
            boolean rejected=false;
            try{GuardCueSettings.read(invalidFile);}catch(java.io.IOException expected){rejected=true;}
            check(rejected,"An invalid migrated candidate is rejected");
            check(Files.readString(invalidFile,StandardCharsets.UTF_8).equals(invalid)
                    &&!Files.exists(invalidFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_BACKUP))
                    &&!Files.exists(invalidFile.getParent().resolve(GuardCueSettings.CLOSE_MIGRATION_MARKER)),
                    "Failed validation modifies no configuration and creates no backup");

            try(var input=GuardCueWindowTest.class.getResourceAsStream("/defaults/guard_cues.json")){
                check(input!=null,"Bundled default cue file is present");
                var bundled=GuardCueSettings.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
                check(bundled.equals(GuardCueSettings.defaults())&&bundled.windowCloseFraction()==.8,
                        "Bundled default already writes the four-fifths close point");
            }
        }finally{cleanup(root);}
    }
    private static Path write(Path root,String directory,String name,String contents)throws Exception{
        Path folder=root.resolve(directory);Files.createDirectories(folder);Path file=folder.resolve(name);
        Files.writeString(file,contents,StandardCharsets.UTF_8);return file;
    }
    private static void cleanup(Path root)throws Exception{
        Path normalized=root.toAbsolutePath().normalize();
        try(var files=Files.walk(normalized)){
            for(Path file:files.sorted(Comparator.reverseOrder()).toList()){
                if(!file.toAbsolutePath().normalize().startsWith(normalized))throw new IllegalStateException("Cleanup escaped the fixture");
                Files.deleteIfExists(file);
            }
        }
    }

    private static void singleContactTick(){
        for(int duration:new int[]{1,2}){
            var shortest=GuardCueSettings.defaults().window(100,duration);
            check(shortest.openGameTick()>shortest.closeGameTick(),"One/two-tick attacks have a mathematically empty precision input window");
            for(int offset=0;offset<=duration;offset++)check(!shortest.accepts(100+offset)&&!shortest.press(100+offset,1),"No rounding extends the window to contact");
            check(shortest.acceptsClash(shortest.contactGameTick()),"The separate clash window still includes actual contact");
            check(!shortest.consume(shortest.contactGameTick(),1,true),"An empty input interval cannot mint guard credit");
        }
        var one=GuardCueSettings.defaults().window(100,3);
        check(one.openGameTick()==102&&one.closeGameTick()==102&&one.press(102,1),"Three-tick attack has exactly one admissible guard input tick");
        check(!one.press(103,2)&&one.consume(103,1,true),"Contact rejects a new press but accepts the previously authorized token");
        var explicit=new GuardCueSettings(true,7,7,120,true,"windup_fraction",0,1).window(200,5);
        check(explicit.openGameTick()==205&&explicit.press(205,1),"Explicit historical Java fixture keeps its contact-only convention");
        reject(()->new GuardCueWindow(100,100,102,99,102),"Input close cannot precede the flash/start");
        reject(()->GuardCueSettings.defaults().window(Long.MAX_VALUE,1),"Contact cannot wrap beyond the long tick range");
    }
    private static String append(String object,String fields){return object.substring(0,object.length()-1)+","+fields+"}";}
    private static void reject(Runnable action,String message){boolean rejected=false;try{action.run();}catch(RuntimeException expected){rejected=true;}check(rejected,message);}
}

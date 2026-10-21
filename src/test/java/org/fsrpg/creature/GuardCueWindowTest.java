package org.fsrpg.creature;

public final class GuardCueWindowTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
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
        fractionalSchedule();fractionalConfiguration();singleContactTick();
        System.out.println("GuardCueWindowTest passed: "+checks+" checks");
    }

    private static void fractionalSchedule(){
        var settings=GuardCueSettings.defaults();
        check(settings.timingMode().equals("windup_fraction")&&settings.flashAtFraction()==.2&&settings.windowOpenFraction()==.6,"Current defaults use one-fifth flash and three-fifths guard opening");
        for(int windup=1;windup<=600;windup++)for(long start:new long[]{100,9_007_199_254_740_993L,Long.MAX_VALUE-windup}){
            var window=settings.window(start,windup);
            long flashOffset=windup/5,openOffset=(3L*windup+4)/5;
            check(settings.windupTicks(windup)==windup,"Fraction timing preserves every authored windup, including one tick");
            check(window.startedGameTick()==start&&window.flashGameTick()==start+flashOffset,"Flash floors the one-fifth point without losing large-world integer precision");
            check(window.openGameTick()==start+openOffset,"Input opens at the ceiling of three-fifths of the actual windup");
            check(window.closeGameTick()==start+windup&&window.contactGameTick()==start+windup,"Inclusive endpoint remains the exact authored contact");
            check(5*flashOffset<=windup&&windup-5*flashOffset<5,"Flash tick quantization is earlier by less than one tick");
            check(5*openOffset>=3L*windup&&5*openOffset-3L*windup<5,"Window quantization is later by less than one tick");
            check(!window.press(start+openOffset-1,1)&&window.press(start+openOffset,1),"Exact fractional opening rejects one tick early and accepts its endpoint");
            check(!window.consume(start+windup-1,1,true)&&window.consume(start+windup,1,true),"A valid fractional press is consumed only on real contact");
            check(!window.consume(start+windup,1,true),"Fractional contact credit cannot be consumed twice");
            var endpoint=settings.window(start,windup);
            check(endpoint.press(start+windup,2)&&endpoint.consume(start+windup,2,true),"A fresh press at the exact contact tick is still perfect eligible");
            if(start+windup<Long.MAX_VALUE)check(!settings.window(start,windup).press(start+windup+1,3),"A press after actual contact cannot defend retroactively");
            var canceled=settings.window(start,windup);canceled.press(start+openOffset,4);canceled.cancel();
            check(!canceled.consume(start+windup,4,true)&&!canceled.press(start+windup,5),"Cancellation kills both earlier and endpoint authorization");
        }
        var fixed=new GuardCueSettings(true,7,7,120,true);
        check(fixed.timingMode().equals("fixed_ticks")&&fixed.windupTicks(1)==7,"Historical five-argument Java fixtures explicitly retain fixed-tick behavior");
        var disabled=new GuardCueSettings(false,7,7,120,true,"windup_fraction",.2,.6);
        check(disabled.windupTicks(1)==1,"Disabled fractional cues do not extend authored timing");
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
        }
    }

    private static void singleContactTick(){
        var shortest=GuardCueSettings.defaults().window(100,1);
        check(shortest.flashGameTick()==100&&shortest.openGameTick()==101&&shortest.closeGameTick()==101,"One-tick authored attack flashes before its contact-only guard window");
        check(!shortest.accepts(100)&&shortest.accepts(101)&&!shortest.accepts(102),"Equal open and close represent one inclusive game tick");
        check(!shortest.press(101,0)&&shortest.press(101,1),"Contact-only window still requires a real positive press serial");
        check(!shortest.consume(101,2,true)&&!shortest.consume(101,1,false)&&shortest.consume(101,1,true),"Contact-only credit still validates the exact serial and held state");
        var contactOnly=new GuardCueSettings(true,7,7,120,true,"windup_fraction",0,1).window(200,5);
        check(contactOnly.flashGameTick()==200&&contactOnly.openGameTick()==205&&contactOnly.press(205,1),"Configured opening at one allows the exact contact tick");
        var sameTick=new GuardCueSettings(true,7,7,120,true,"windup_fraction",0,0).window(300,1);
        check(sameTick.flashGameTick()==300&&sameTick.openGameTick()==300&&sameTick.accepts(300),"Flash and window opening may occupy the same game tick");
        reject(()->new GuardCueWindow(100,100,102,101,102),"Inverted window remains invalid");
        reject(()->GuardCueSettings.defaults().window(Long.MAX_VALUE,1),"Contact schedule cannot wrap past the long tick boundary");
    }
    private static String append(String object,String fields){return object.substring(0,object.length()-1)+","+fields+"}";}
    private static void reject(Runnable action,String message){boolean rejected=false;try{action.run();}catch(RuntimeException expected){rejected=true;}check(rejected,message);}
}

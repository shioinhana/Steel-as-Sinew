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
                boolean valid=time>=probe.openGameTick()&&time<probe.closeGameTick();
                check(probe.press(time,1)==valid,"Exact half-open input bounds");
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
        System.out.println("GuardCueWindowTest passed: "+checks+" checks");
    }
}

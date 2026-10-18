package org.fsrpg.client;

import java.util.Objects;

/** Raw physical edges remain distinct from screen/equipment state and scripted guard fixtures. */
public final class GuardKeyIntentTest {
    private static int checks;
    public static void main(String[] args) {
        var intent=new GuardKeyIntent();
        for(int tick=0;tick<40;tick++)equal(null,intent.sample(false,true,false,true),"idle native input cannot release a scripted guard");
        equal("true",intent.sample(true,true,false,false),"real press while eligible is precision eligible");
        equal(null,intent.sample(true,true,false,false),"held input is not repeated press");
        equal("suspend",intent.sample(true,false,false,false),"GUI opening suspends without pretending physical release");
        equal("held",intent.sample(true,true,false,false),"GUI closing while physically held is only ordinary renewal");
        equal("held_heavy",intent.sample(true,true,true,false),"changing weight while held is not a precision press");
        equal("held",intent.sample(true,true,false,false),"returning to light guard is still held");
        equal("false",intent.sample(false,true,false,false),"only a real release clears the physical held state");
        equal("heavy",intent.sample(true,true,true,false),"release then real repress can precision guard again");
        intent=new GuardKeyIntent();
        equal(null,intent.sample(false,false,false,false),"empty hands start without input ownership");
        equal("suspend",intent.sample(true,false,false,false),"pressing C before equipping records physical hold only");
        equal("held",intent.sample(true,true,false,false),"automatic drawing cannot manufacture a fresh C");
        equal("suspend",intent.sample(true,false,false,false),"unequipping suspends the same hold");
        equal("held_heavy",intent.sample(true,true,true,false),"re-equipping in heavy guard still cannot manufacture C");
        equal("false",intent.sample(false,true,true,false),"physical release after equip is sent exactly once");
        equal(null,intent.sample(false,true,true,true),"unchanged release cannot fight fixture guard state");
        intent=new GuardKeyIntent();
        equal("held",intent.sample(true,true,false,false),"first observed already-held key is not assumed to have just been pressed");
        for(int round=0;round<3;round++) {
            for(int tick=0;tick<3;tick++)equal(null,intent.sample(true,true,false,true),"renewals wait four client ticks");
            equal("held",intent.sample(true,true,false,true),"automatic retry is always a held renewal");
        }
        equal(null,intent.sample(true,true,false,false),"counter/ACTIVE disables retry without releasing key");
        for(int tick=0;tick<3;tick++)equal(null,intent.sample(true,true,false,true),"retry delay restarts after inactive period");
        equal("held",intent.sample(true,true,false,true),"retry after an attack is never a precision press");
        intent.reset();
        check(!intent.ownsInput(),"logout/remap resets local ownership");
        equal(null,intent.sample(false,true,false,true),"unowned input after reset emits no false");
        for(int mask=0;mask<16;mask++) {
            intent=new GuardKeyIntent();intent.sample(false,false,false,false);
            String press=intent.sample(true,(mask&1)!=0,(mask&2)!=0,false);
            check(Objects.equals(press,(mask&1)==0?"suspend":(mask&2)==0?"true":"heavy"),"only actual eligible press selects a fresh packet");
            for(int tick=0;tick<12;tick++) {
                String held=intent.sample(true,(mask&4)!=0,(mask&8)!=0,true);
                check(held==null||held.equals("suspend")||held.equals("held")||held.equals("held_heavy"),"eligibility and modifiers never create new presses");
            }
        }
        System.out.println("GuardKeyIntentTest passed: "+checks+" checks");
    }
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual);}
    private static void check(boolean valid,String message){checks++;if(!valid)throw new AssertionError(message);}
}

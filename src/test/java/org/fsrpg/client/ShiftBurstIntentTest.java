package org.fsrpg.client;

public final class ShiftBurstIntentTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var edge=new ShiftBurstIntent();
        check(edge.sample(true,true,false,0,1,0)==null,"Joining while Shift is held does not invent a new press");
        edge.sample(false,true,false,1,1,0);
        String first=edge.sample(true,true,false,2,3,4);
        check(first.equals("down 0.600000000 0.800000000"),"Fresh press normalizes the actual initial world direction");
        check(edge.sample(true,true,false,3,-1,0)==null,"Held Shift does not repeat a paid burst");
        check(first.equals(edge.sample(true,true,false,6,-1,0)),"Lease renewal retains the initial direction despite turning");
        check("up".equals(edge.sample(true,false,false,7,1,0)),"Focus or GUI cancellation releases server running state");
        check(edge.sample(true,true,false,8,1,0)==null,"Returning to the game while held does not retrigger");
        edge.sample(false,true,false,9,1,0);
        check(edge.sample(true,true,true,10,1,0)==null,"Shift plus C gives guarding priority");
        check(edge.sample(true,true,false,15,1,0)==null,"Releasing C while Shift remains down does not create a burst");
        edge.sample(false,true,false,16,1,0);edge.sample(true,true,false,17,1,0);
        check("up".equals(edge.sample(false,true,false,18,1,0)),"A physical release ends one hold exactly once");
        check(edge.sample(false,true,false,19,1,0)==null,"Repeated up samples do not emit extra packets");
        System.out.println("ShiftBurstIntentTest passed: "+checks+" checks");
    }
}

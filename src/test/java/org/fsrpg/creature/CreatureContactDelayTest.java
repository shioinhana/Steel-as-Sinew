package org.fsrpg.creature;

public final class CreatureContactDelayTest {
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static int run() {
        checks=0;
        var immediate=new CreatureContactDelay(100,2,false);
        check(immediate.claimExecution(100,false),"Ordinary defense invokes at the actual contact");
        check(!immediate.deferred(),"An immediate invocation retains its original guard timing");
        check(!immediate.claimExecution(101,true),"A later reverse contact never invokes the native method twice");
        for (int offset=0;offset<=2;offset++) {
            var delayed=new CreatureContactDelay(100,2,true);
            for (long now=100;now<100+offset;now++)
                check(!delayed.claimExecution(now,false),"An already-started reverse swing can reach within the window");
            check(delayed.claimExecution(100+offset,true),"Reverse contact is accepted at inclusive offset "+offset);
            check(delayed.deferred()==(offset>0),"Only actual cross-tick delay disables later guard input");
            check(delayed.contactTick()==100,"Native incoming damage retains its original contact timestamp");
            check(!delayed.pending()&&delayed.executed(),"Successful dispatch retires the waiting invocation");
            check(!delayed.claimExecution(103,true),"Successful dispatch is single use");
        }
        var unmatched=new CreatureContactDelay(100,2,true);
        check(!unmatched.claimExecution(99,true),"A reverse hint cannot invoke before the real contact");
        check(!unmatched.claimExecution(100,false),"Unmatched mutual attack waits at contact");
        check(!unmatched.claimExecution(101,false),"Unmatched mutual attack waits through tick one");
        check(unmatched.claimExecution(102,false),"Unmatched attack invokes once at the inclusive deadline");
        check(!unmatched.claimExecution(102,true),"Deadline dispatch cannot repeat after a late reverse hint");
        var zero=new CreatureContactDelay(100,0,true);
        check(zero.claimExecution(100,false),"A zero-tick window expires in the same collision phase");
        check(!zero.deferred(),"A zero-tick window never changes guard timing");
        var canceled=new CreatureContactDelay(100,2,true);
        canceled.cancel();
        check(!canceled.pending(),"Lifecycle cancellation releases the wait");
        check(!canceled.claimExecution(100,true)&&!canceled.claimExecution(200,false),"Canceled contact never invokes native effects");
        var lateObservation=new CreatureContactDelay(100,2,true);
        check(lateObservation.claimExecution(105,false),"A skipped flush dispatches only once when observed later");
        check(!lateObservation.claimExecution(106,false),"Late observation remains single use");
        var clock=new CreatureAttackTimeline(99,1,1);
        check(clock.claimContact(100),"Timeline claims its true animation contact once");
        var outlastsAnimation=new CreatureContactDelay(100,2,true);
        check(clock.phase(101)==CreatureAttackTimeline.Phase.FINISHED&&outlastsAnimation.pending(),
                "A short animation may finish while its original invocation still awaits matching");
        check(outlastsAnimation.claimExecution(102,true),"Pending invocation survives animation end to match the boundary");
        for (int bad:new int[]{-1,11}) {
            boolean rejected=false;
            try { new CreatureContactDelay(100,bad,true); }
            catch (IllegalArgumentException expected) { rejected=true; }
            check(rejected,"Invalid contact window rejected");
        }
        boolean rejected=false;
        try { new CreatureContactDelay(-1,2,true); }
        catch (IllegalArgumentException expected) { rejected=true; }
        check(rejected,"Negative contact timestamp rejected");
        return checks;
    }
    public static void main(String[] args) {
        System.out.println("CreatureContactDelayTest passed: "+run()+" checks");
    }
}

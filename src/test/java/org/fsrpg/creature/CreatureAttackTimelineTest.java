package org.fsrpg.creature;

public final class CreatureAttackTimelineTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args) {
        for(int windup=1;windup<=40;windup++)for(int recovery=1;recovery<=40;recovery++) {
            var clock=new CreatureAttackTimeline(100,windup,recovery);
            for(long tick=100;tick<100+windup;tick++) {
                check(clock.phase(tick)==CreatureAttackTimeline.Phase.WINDUP,"No early contact phase");
                check(!clock.claimContact(tick),"No early attack");
            }
            check(clock.claimContact(100+windup),"Contact exactly at configured tick");
            check(clock.contactClaimed(),"Contact recorded");
            for(long tick=100+windup;tick<100+windup+recovery;tick++) {
                check(clock.phase(tick)==CreatureAttackTimeline.Phase.RECOVERY,"Recovery retains same attack");
                check(!clock.claimContact(tick),"One contact only");
            }
            check(clock.phase(100+windup+recovery)==CreatureAttackTimeline.Phase.FINISHED,"Exact recovery end");
            check(!clock.claimContact(100+windup+recovery),"No late damage after recovery");
            var interrupted=new CreatureAttackTimeline(100,windup,recovery);interrupted.cancel();
            check(!interrupted.claimContact(100+windup),"Canceled attack cannot hit");
            check(interrupted.phase(100+windup)==CreatureAttackTimeline.Phase.CANCELED,"Canceled phase remains stable");
        }
        var skipped=new CreatureAttackTimeline(50,3,5);
        check(skipped.claimContact(54),"One delayed boundary observation remains valid within recovery");
        check(!skipped.claimContact(55),"Catchup cannot duplicate hit");
        var expired=new CreatureAttackTimeline(50,3,5);
        check(!expired.claimContact(58),"Expired attack cannot catch up stale damage");
        for(int[] bad:new int[][]{{0,1},{1,0},{-1,2},{2,-1},{1201,1},{1,1201}}) {
            boolean failed=false;try{new CreatureAttackTimeline(0,bad[0],bad[1]);}catch(IllegalArgumentException expected){failed=true;}
            check(failed,"Invalid durations rejected");
        }
        System.out.println("CreatureAttackTimelineTest passed: "+checks+" checks");
    }
}

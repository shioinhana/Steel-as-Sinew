package org.fsrpg.client;

public final class GuardHurtMotionTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var motion=new GuardHurtMotion();
        check(!motion.damage(1,1,10),"ordinary incoming damage retains hurt camera");
        motion.guarded(1,1,11);check(motion.damage(1,1,11)&&motion.suppressed(),"committed guard removes only matching hurt transform");
        check(!motion.damage(1,1,11),"second unguarded hit from same attacker restores shake");
        motion.guarded(1,1,12);check(!motion.damage(2,2,12),"unrelated damage cannot borrow guard immunity");
        motion.guarded(1,1,13);check(!motion.damage(1,1,16),"stale marker never hides later damage");
        motion.guarded(1,1,20);check(!motion.damage(1,1,19),"out-of-order clock cannot consume guard marker");
        motion.guarded(1,1,21);check(motion.damage(1,1,21),"first of two same-frame blocked hits is muted");
        motion.guarded(1,1,21);check(motion.damage(1,1,21),"next distinct guarded event from same source is also muted");
        motion.reset();check(!motion.suppressed()&&!motion.damage(1,1,22),"logout reset clears presentation state");
        motion.cancelCurrentHurt();check(motion.suppressed(),"zero-damage perfect guard also stops the preceding hurt camera interval");
        check(!motion.damage(1,1,23),"zero-damage block cannot mute a later unguarded notification");
        System.out.println("GuardHurtMotionTest passed: "+checks+" checks");
    }
}

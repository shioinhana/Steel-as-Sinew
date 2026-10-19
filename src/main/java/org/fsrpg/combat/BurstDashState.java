package org.fsrpg.combat;

/** Horizontal movement only. The game adapter retains vertical velocity and applies collision-aware movement. */
public final class BurstDashState {
    public record Step(double x,double z,boolean bursting,boolean running,long sequence) {
        public static Step none(){return new Step(0,0,false,false,0);}
        public boolean moving(){return x!=0||z!=0;}
    }
    public record Snapshot(boolean held,boolean active,boolean running,int elapsedTicks,int durationTicks,
                           double directionX,double directionZ,long sequence,int inputAgeTicks) {}
    private boolean held,armed;
    private long lastInput=-1,readyAt,sequence;
    private int elapsed;
    private double x,z;
    private BurstDashRules activeRules;

    /** A failed start still consumes its physical press edge; renewal and lease expiry cannot repeat the cost. */
    public boolean input(boolean down,long now) {
        checkTick(now);
        boolean fresh=down&&!held;
        held=down;lastInput=now;
        return fresh;
    }
    public boolean active(){return armed&&activeRules!=null&&elapsed<activeRules.durationTicks();}
    public boolean coolingDown(long now){return now<readyAt;}
    public void begin(long now,double directionX,double directionZ,BurstDashRules rules) {
        checkTick(now);
        SkillDefinition.checkNumber(directionX,"burst dash directionX",-1_000_000,1_000_000);
        SkillDefinition.checkNumber(directionZ,"burst dash directionZ",-1_000_000,1_000_000);
        double length=Math.hypot(directionX,directionZ);
        if(length<1e-6)throw new IllegalArgumentException("burst dash direction must be nonzero");
        if(rules==null)throw new IllegalArgumentException("burst dash rules required");
        x=directionX/length;z=directionZ/length;activeRules=rules;
        elapsed=0;armed=true;sequence++;readyAt=now+rules.cooldownTicks();
    }
    public Step tick(long now) {
        checkTick(now);
        if(!armed||activeRules==null)return Step.none();
        if(elapsed<activeRules.durationTicks()) {
            double before=elapsed/(double)activeRules.durationTicks();
            double after=++elapsed/(double)activeRules.durationTicks();
            double distance=activeRules.displacement(after)-activeRules.displacement(before);
            return new Step(x*distance,z*distance,true,false,sequence);
        }
        if(!held||now-lastInput>activeRules.inputLeaseTicks())return Step.none();
        double distance=activeRules.runSpeed()/20.0;
        return new Step(x*distance,z*distance,false,true,sequence);
    }
    /** Death, a stun or another incompatible state stops motion but never invents a release edge. */
    public void stopMotion(){armed=false;}
    public Snapshot snapshot(long now) {
        boolean running=armed&&!active()&&held&&activeRules!=null&&now-lastInput<=activeRules.inputLeaseTicks();
        return new Snapshot(held,active(),running,elapsed,activeRules==null?0:activeRules.durationTicks(),x,z,sequence,
                lastInput<0?Integer.MAX_VALUE:(int)Math.min(Integer.MAX_VALUE,Math.max(0,now-lastInput)));
    }
    private static void checkTick(long now){if(now<0)throw new IllegalArgumentException("burst dash time must be nonnegative");}
}

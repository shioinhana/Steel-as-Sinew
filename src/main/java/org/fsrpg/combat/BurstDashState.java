package org.fsrpg.combat;

/** One paid velocity impulse; ordinary Minecraft travel owns all later acceleration and friction. */
public final class BurstDashState {
    /** Retained for source compatibility. A tick never manufactures a movement step. */
    public record Step(double x,double z,boolean bursting,boolean running,long sequence) {
        public static Step none(){return new Step(0,0,false,false,0);}
        public boolean moving(){return x!=0||z!=0;}
    }
    public record Snapshot(boolean held,boolean active,boolean running,int elapsedTicks,int durationTicks,
                           double directionX,double directionZ,long sequence,int inputAgeTicks) {}
    private boolean held,armed,pending;
    private long lastInput=-1,readyAt,sequence,started;
    private double x,z;
    private BurstDashRules activeRules;
    /** Rejected starts consume their physical edge too; landing or a lease renewal cannot rearm it. */
    public boolean input(boolean down,long now){checkTick(now);boolean fresh=down&&!held;held=down;lastInput=now;return fresh;}
    public boolean active(){return pending;}
    public boolean coolingDown(long now){return now<readyAt;}
    public void begin(long now,double directionX,double directionZ,BurstDashRules rules){
        checkTick(now);SkillDefinition.checkNumber(directionX,"burst dash directionX",-1_000_000,1_000_000);
        SkillDefinition.checkNumber(directionZ,"burst dash directionZ",-1_000_000,1_000_000);
        double length=Math.hypot(directionX,directionZ);
        if(length<1e-6||rules==null)throw new IllegalArgumentException("burst dash requires direction and rules");
        x=directionX/length;z=directionZ/length;activeRules=rules;started=now;armed=pending=true;sequence++;readyAt=now+rules.cooldownTicks();
    }
    /** Adapter consumes exactly once in the same action that authorizes and charges the impulse. */
    public Step consumeImpulse(){if(!pending)return Step.none();pending=false;return new Step(x*activeRules.initialVelocity(),z*activeRules.initialVelocity(),true,false,sequence);}
    public Step tick(long now){checkTick(now);return Step.none();}
    /** Stops permission for held sprint, never the physical velocity already applied. */
    public void stopMotion(){armed=pending=false;}
    public Snapshot snapshot(long now){
        checkTick(now);int age=lastInput<0?Integer.MAX_VALUE:(int)Math.min(Integer.MAX_VALUE,Math.max(0,now-lastInput));
        return new Snapshot(held,pending,armed&&held&&activeRules!=null&&age<=activeRules.inputLeaseTicks(),
                armed?(int)Math.min(Integer.MAX_VALUE,now-started):0,0,x,z,sequence,age);
    }
    private static void checkTick(long now){if(now<0)throw new IllegalArgumentException("burst dash time must be nonnegative");}
}

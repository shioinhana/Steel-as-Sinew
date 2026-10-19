package org.fsrpg.combat;

/** Shift burst is independent of the original Alt dodge and never supplies invulnerability. */
public record BurstDashRules(double distance, int durationTicks, double staminaCost, int cooldownTicks,
                             double runSpeed, int inputLeaseTicks) {
    public BurstDashRules {
        SkillDefinition.checkNumber(distance,"burst dash distance",.25,32);
        SkillDefinition.checkTicks(durationTicks,"burst dash durationTicks",false);
        if(durationTicks>100)throw new IllegalArgumentException("burst dash durationTicks must be <=100");
        SkillDefinition.checkNumber(staminaCost,"burst dash staminaCost",0,1_000_000);
        SkillDefinition.checkTicks(cooldownTicks,"burst dash cooldownTicks",true);
        SkillDefinition.checkNumber(runSpeed,"burst dash runSpeed",0,12);
        if(inputLeaseTicks<2||inputLeaseTicks>100)throw new IllegalArgumentException("burst dash inputLeaseTicks must be 2..100");
        if(runSpeed*durationTicks/20.0>distance*3+1e-9)
            throw new IllegalArgumentException("burst dash terminal run speed exceeds a monotone acceleration curve");
    }
    public static BurstDashRules defaults(){return new BurstDashRules(3,10,5,10,5.6,12);}

    /**
     * Calibrated for actual 20 Hz displacement slices, rather than only an instantaneous end derivative.
     * The first N-1 ticks retain the zero-start Hermite shape, scaled to leave exactly one running step.
     * The last tick therefore joins the held run at exactly the same horizontal speed.
     */
    public double displacement(double progress) {
        SkillDefinition.checkNumber(progress,"burst dash progress",0,1);
        double runningStep=runSpeed/20.0;
        // In one tick, or when one running step exceeds the complete burst distance, both endpoint
        // conditions cannot coexist. Retain the original monotone exact-distance curve for those coarse settings.
        if(durationTicks==1||runningStep>distance)return hermite(progress);
        double finalStart=(durationTicks-1.0)/durationTicks;
        double preceding=distance-runningStep;
        if(progress<=finalStart)return hermite(progress)*preceding/hermite(finalStart);
        return preceding+runningStep*((progress-finalStart)/(1-finalStart));
    }

    private double hermite(double progress) {
        double terminal=runSpeed*durationTicks/(20.0*distance);
        return distance*((terminal-2)*progress*progress*progress+(3-terminal)*progress*progress);
    }
}

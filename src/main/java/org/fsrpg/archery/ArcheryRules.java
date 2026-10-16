package org.fsrpg.archery;

/** Legacy bow rules. Time is in server ticks; arrow velocity is blocks per tick. */
public record ArcheryRules(
        double rapidDrawTicks, double snipeDrawTicks, double rapidArrowSpeed, double snipeArrowSpeed,
        double minReleasePower, int rapidScoreStep, double rapidReductionPerStep, double rapidReductionCap,
        int snipeScoreStep, double snipeSpeedPerStep, double snipeSpeedCap,
        int autoChargeScore, int trajectoryScore, int scopeScore, double scopeMaxMagnification,
        int ruSuoCharges, double ruSuoReduction, double totalDrawReductionCap, int ruSuoRecoverTicks,
        int nieShiCooldownTicks, double yanXiaDianSpeedBonus, double chuanTangFengArmorPierce,
        int chuanTangFengBlockPierce, double chuanTangFengBlockSlow, int chuanTangFengEntityPierce,
        int rapidDurability, int snipeDurability, double bashRange, double bashDamage,
        double bashKnockback, int bashCooldownTicks, double rapidStaminaCost, double snipeStaminaCost,
        double shotSpiritCost, double bashStaminaCost, int shotAnimationTicks, int bashAnimationTicks,
        Double partialShotStaminaMultiplier) {
    /** Compatible constructor for existing integrations using the original bow fields. */
    public ArcheryRules(
        double rapidDrawTicks, double snipeDrawTicks, double rapidArrowSpeed, double snipeArrowSpeed,
        double minReleasePower, int rapidScoreStep, double rapidReductionPerStep, double rapidReductionCap,
        int snipeScoreStep, double snipeSpeedPerStep, double snipeSpeedCap,
        int autoChargeScore, int trajectoryScore, int scopeScore, double scopeMaxMagnification,
        int ruSuoCharges, double ruSuoReduction, double totalDrawReductionCap, int ruSuoRecoverTicks,
        int nieShiCooldownTicks, double yanXiaDianSpeedBonus, double chuanTangFengArmorPierce,
        int chuanTangFengBlockPierce, double chuanTangFengBlockSlow, int chuanTangFengEntityPierce,
        int rapidDurability, int snipeDurability, double bashRange, double bashDamage,
        double bashKnockback, int bashCooldownTicks, double rapidStaminaCost, double snipeStaminaCost,
        double shotSpiritCost, double bashStaminaCost, int shotAnimationTicks, int bashAnimationTicks) {
        this(rapidDrawTicks,snipeDrawTicks,rapidArrowSpeed,snipeArrowSpeed,minReleasePower,rapidScoreStep,rapidReductionPerStep,rapidReductionCap,snipeScoreStep,snipeSpeedPerStep,snipeSpeedCap,autoChargeScore,trajectoryScore,scopeScore,scopeMaxMagnification,ruSuoCharges,ruSuoReduction,totalDrawReductionCap,ruSuoRecoverTicks,nieShiCooldownTicks,yanXiaDianSpeedBonus,chuanTangFengArmorPierce,chuanTangFengBlockPierce,chuanTangFengBlockSlow,chuanTangFengEntityPierce,rapidDurability,snipeDurability,bashRange,bashDamage,bashKnockback,bashCooldownTicks,rapidStaminaCost,snipeStaminaCost,shotSpiritCost,bashStaminaCost,shotAnimationTicks,bashAnimationTicks, .7);
    }
    public ArcheryRules {
        if(partialShotStaminaMultiplier==null)partialShotStaminaMultiplier=.7;
        number(partialShotStaminaMultiplier,"partialShotStaminaMultiplier",0,1);
        number(rapidDrawTicks,"rapidDrawTicks",1,12000); number(snipeDrawTicks,"snipeDrawTicks",1,12000);
        number(rapidArrowSpeed,"rapidArrowSpeed",0.01,128); number(snipeArrowSpeed,"snipeArrowSpeed",0.01,128);
        number(minReleasePower,"minReleasePower",0,1); integer(rapidScoreStep,"rapidScoreStep",1,1000000);
        number(rapidReductionPerStep,"rapidReductionPerStep",0,1); number(rapidReductionCap,"rapidReductionCap",0,0.99);
        integer(snipeScoreStep,"snipeScoreStep",1,1000000); number(snipeSpeedPerStep,"snipeSpeedPerStep",0,100);
        number(snipeSpeedCap,"snipeSpeedCap",0,100); integer(autoChargeScore,"autoChargeScore",0,1000000);
        integer(trajectoryScore,"trajectoryScore",0,1000000); integer(scopeScore,"scopeScore",0,1000000);
        number(scopeMaxMagnification,"scopeMaxMagnification",1,32); integer(ruSuoCharges,"ruSuoCharges",1,128);
        number(ruSuoReduction,"ruSuoReduction",0,0.99); number(totalDrawReductionCap,"totalDrawReductionCap",0,0.99);
        integer(ruSuoRecoverTicks,"ruSuoRecoverTicks",1,12000); integer(nieShiCooldownTicks,"nieShiCooldownTicks",1,12000);
        number(yanXiaDianSpeedBonus,"yanXiaDianSpeedBonus",0,100); number(chuanTangFengArmorPierce,"chuanTangFengArmorPierce",0,10);
        integer(chuanTangFengBlockPierce,"chuanTangFengBlockPierce",0,32); number(chuanTangFengBlockSlow,"chuanTangFengBlockSlow",0,1);
        integer(chuanTangFengEntityPierce,"chuanTangFengEntityPierce",0,127);
        integer(rapidDurability,"rapidDurability",0,1000); integer(snipeDurability,"snipeDurability",0,1000);
        number(bashRange,"bashRange",0,32); number(bashDamage,"bashDamage",0,1000000); number(bashKnockback,"bashKnockback",0,16);
        integer(bashCooldownTicks,"bashCooldownTicks",0,12000); number(rapidStaminaCost,"rapidStaminaCost",0,1000000);
        number(snipeStaminaCost,"snipeStaminaCost",0,1000000); number(shotSpiritCost,"shotSpiritCost",0,1000000);
        number(bashStaminaCost,"bashStaminaCost",0,1000000); integer(shotAnimationTicks,"shotAnimationTicks",1,12000);
        integer(bashAnimationTicks,"bashAnimationTicks",1,12000);
    }
    private static void number(double value,String name,double min,double max) {
        if (!Double.isFinite(value)||value<min||value>max) throw new IllegalArgumentException("archery."+name+" outside "+min+".."+max);
    }
    private static void integer(int value,String name,int min,int max) { number(value,name,min,max); }
    public static ArcheryRules defaults() {
        return new ArcheryRules(10,30,3,3.6,.2,5,.01,.3,3,.01,1,50,100,300,8,
                5,.2,.5,60,40,.3,.3,3,.2,127,1,3,2.5,4,1.4,7,5,15,0,0,6,7);
    }
}

package org.fsrpg.archery;

/** The legacy formulas, shared by server decisions and client trajectory/scope presentation. */
public final class ArcheryScores {
    private ArcheryScores() {}
    public static double rapidReduction(ArcheryRules r,int score) {
        return Math.min(r.rapidReductionCap(),Math.max(0,score)/r.rapidScoreStep()*r.rapidReductionPerStep());
    }
    public static double snipeSpeedBonus(ArcheryRules r,int score) {
        return Math.min(r.snipeSpeedCap(),Math.max(0,score)/r.snipeScoreStep()*r.snipeSpeedPerStep());
    }
    public static double requiredDrawTicks(ArcheryRules r,ArcheryProgress p,boolean snipe,boolean ruSuoCharged) {
        double reduction=(snipe?0:rapidReduction(r,p.score()))+(ruSuoCharged?r.ruSuoReduction():0);
        return Math.max(1,(snipe?r.snipeDrawTicks():r.rapidDrawTicks())*(1-Math.min(r.totalDrawReductionCap(),reduction))*p.bowDrawFactor());
    }
    public static double arrowSpeed(ArcheryRules r,ArcheryProgress p,boolean snipe,double power) {
        if(!Double.isFinite(power)) throw new IllegalArgumentException("Nonfinite bow power");
        double speed=(snipe?r.snipeArrowSpeed():r.rapidArrowSpeed())*Math.max(.25,Math.min(1,power));
        if(snipe) speed*=1+snipeSpeedBonus(r,p.score());
        if(p.branch250()==ArcheryProgress.Branch250.YAN_XIA_DIAN) speed*=1+r.yanXiaDianSpeedBonus();
        return speed;
    }
    public static double scopeMagnification(ArcheryRules r,ArcheryProgress p,double charge) {
        if(p.score()<r.scopeScore()) return 1;
        double maximum=r.snipeArrowSpeed()*(1+r.snipeSpeedCap())*(1+r.yanXiaDianSpeedBonus());
        double ratio=Math.max(0,Math.min(1,arrowSpeed(r,p,true,1)/maximum));
        return 1+(r.scopeMaxMagnification()-1)*ratio*Math.max(0,Math.min(1,charge));
    }
}

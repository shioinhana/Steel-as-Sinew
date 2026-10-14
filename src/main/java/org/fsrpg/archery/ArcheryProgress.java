package org.fsrpg.archery;

/** Read-only view supplied by the server's weapon skill-tree service. */
public record ArcheryProgress(boolean unlocked, int score, Branch150 branch150, Branch250 branch250,
                              double damageBonus,double bowDrawFactor) {
    public enum Branch150 { NONE, RU_SUO, NIE_SHI }
    public enum Branch250 { NONE, YAN_XIA_DIAN, CHUAN_TANG_FENG }
    public ArcheryProgress {
        if(score<0||score>1000000000||branch150==null||branch250==null) throw new IllegalArgumentException("Invalid archery progress");
        if(!Double.isFinite(damageBonus)||damageBonus<0||damageBonus>1000000
                ||!Double.isFinite(bowDrawFactor)||bowDrawFactor<=0||bowDrawFactor>100) throw new IllegalArgumentException("Invalid base bow effects");
    }
    public ArcheryProgress(boolean unlocked,int score,Branch150 branch150,Branch250 branch250) { this(unlocked,score,branch150,branch250,0,1); }
    public static ArcheryProgress locked() { return new ArcheryProgress(false,0,Branch150.NONE,Branch250.NONE); }
}

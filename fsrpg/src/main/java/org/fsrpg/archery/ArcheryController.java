package org.fsrpg.archery;

/** Deterministic server bow timing. Ammo/spawn/resource changes are committed by the adapter. */
public final class ArcheryController {
    public enum Stance { RAPID, SNIPE }
    public record Shot(Stance stance,double power,double speed,boolean automatic,boolean ruSuo,int durability) {}
    public record Persisted(int ruSuoCharges,long recoveryAnchor,long nieShiReadyTick,ArcheryProgress.Branch150 branch150) {}
    public record Snapshot(boolean equipped,boolean active,String stance,boolean charging,long chargeStartTick,
            double chargeProgress,double requiredDrawTicks,long lastShotTick,int ruSuoCharges,
            String animationAction,long animationStartTick,int animationRemainingTicks,long sequence,
            boolean trajectoryUnlocked,double scopeMagnification) {}
    private boolean equipped,active,charging,automaticHeld;
    private String equippedKey="";
    private Stance stance=Stance.RAPID;
    private long chargeStart=Long.MIN_VALUE,lastShot=Long.MIN_VALUE,bashReady=Long.MIN_VALUE;
    private long animationStart,sequence,recoveryAnchor=Long.MIN_VALUE,nieShiReady=Long.MIN_VALUE;
    private int charges;
    private ArcheryProgress.Branch150 previousBranch=ArcheryProgress.Branch150.NONE;
    private String transientAction="";
    private int transientDuration;
    public ArcheryController(ArcheryRules r) { charges=r.ruSuoCharges(); }
    /** The actual equipped item/hand key changes cancel a draw even when both stacks are bows. */
    public boolean reconcile(String key,boolean isActive,Stance selected,long now,ArcheryRules r,ArcheryProgress p) {
        boolean hasBow=key!=null&&!key.isEmpty();
        boolean nextActive=hasBow&&isActive&&p.unlocked();
        boolean changed=hasBow!=equipped||nextActive!=active||!equippedKey.equals(key==null?"":key)||selected!=stance;
        if(changed) {
            cancelCharge(); automaticHeld=false; transientAction=""; transientDuration=0;
            animationStart=now; sequence++;
        }
        equipped=hasBow; equippedKey=key==null?"":key; active=nextActive; stance=selected;
        if(!active) { cancelCharge(); automaticHeld=false; }
        if(p.branch150()!=previousBranch) {
            if(p.branch150()==ArcheryProgress.Branch150.RU_SUO) { charges=r.ruSuoCharges(); recoveryAnchor=Long.MIN_VALUE; }
            previousBranch=p.branch150();
        }
        charges=Math.min(charges,r.ruSuoCharges());
        return changed;
    }
    public boolean beginCharge(long now) {
        if(!active||charging||transientAction.equals("bow_bash")&&now-animationStart<transientDuration) return false;
        charging=true; chargeStart=now; transientAction=""; animationStart=now; sequence++; return true;
    }
    public void cancelCharge() { charging=false; chargeStart=Long.MIN_VALUE; }
    /** A bash, cancelled input or combat interruption requires a new physical press. */
    public void interruptHeld() { automaticHeld=false; cancelCharge(); }
    public void detach() {
        equipped=false; active=false; equippedKey=""; automaticHeld=false; cancelCharge(); transientAction=""; transientDuration=0; sequence++;
    }
    /** 50-score left-button hold-to-fire (kept from the 0.5 migration); the held right button auto-releases too. */
    public boolean automatic(boolean held,long now,ArcheryRules r,ArcheryProgress p) {
        automaticHeld=held&&active&&stance==Stance.RAPID&&p.score()>=r.autoChargeScore();
        if(automaticHeld) beginCharge(now);
        return automaticHeld;
    }
    public boolean maintainAutomatic(long now) { return automaticHeld&&beginCharge(now); }
    public boolean automaticHeld() { return automaticHeld; }
    public Shot release(long now,boolean automatic,ArcheryRules r,ArcheryProgress p) {
        if(!active||!charging) return null;
        double power=power(now,r,p);
        cancelCharge();
        if(power+1e-9<r.minReleasePower()) { sequence++; return null; }
        boolean ruSuo=p.branch150()==ArcheryProgress.Branch150.RU_SUO&&charges>0;
        return new Shot(stance,power,ArcheryScores.arrowSpeed(r,p,stance==Stance.SNIPE,power),automatic,ruSuo,
                stance==Stance.SNIPE?r.snipeDurability():r.rapidDurability());
    }
    /** Invoke only after successful entity spawn, ammo debit and resource checks. */
    public void commitShot(Shot shot,long now,ArcheryRules r) {
        if(shot.ruSuo()&&charges>0) {
            charges--; if(recoveryAnchor==Long.MIN_VALUE) recoveryAnchor=now;
        }
        lastShot=now; transientAction=prefix()+"release"; transientDuration=r.shotAnimationTicks(); animationStart=now; sequence++;
        if(shot.automatic()&&active) { charging=true; chargeStart=now; }
    }
    public boolean autoReady(long now,ArcheryRules r,ArcheryProgress p) {
        return active&&charging&&stance==Stance.RAPID&&p.score()>=r.autoChargeScore()&&power(now,r,p)>=1;
    }
    public boolean bash(long now,ArcheryRules r) {
        interruptHeld();
        if(!active||now<bashReady) return false;
        bashReady=now+r.bashCooldownTicks(); transientAction="bow_bash"; transientDuration=r.bashAnimationTicks();
        animationStart=now; sequence++; return true;
    }
    public boolean recover(long now,ArcheryRules r,ArcheryProgress p) {
        if(p.branch150()!=ArcheryProgress.Branch150.RU_SUO||charges>=r.ruSuoCharges()) return false;
        if(recoveryAnchor==Long.MIN_VALUE||now<recoveryAnchor) { recoveryAnchor=now; return false; }
        long gained=(now-recoveryAnchor)/r.ruSuoRecoverTicks();
        if(gained<=0) return false;
        charges=(int)Math.min(r.ruSuoCharges(),charges+gained); recoveryAnchor+=gained*r.ruSuoRecoverTicks();
        if(charges==r.ruSuoCharges()) recoveryAnchor=Long.MIN_VALUE;
        return true;
    }
    public boolean claimCapturedArrow(long now,ArcheryRules r) {
        if(now<nieShiReady) return false;
        nieShiReady=now+r.nieShiCooldownTicks(); return true;
    }
    private String prefix() { return stance==Stance.SNIPE?"bow_snipe_":"bow_rapid_"; }
    public double power(long now,ArcheryRules r,ArcheryProgress p) {
        return !charging?0:Math.max(0,Math.min(1,(now-chargeStart)/requiredDrawTicks(r,p)));
    }
    public double requiredDrawTicks(ArcheryRules r,ArcheryProgress p) {
        return ArcheryScores.requiredDrawTicks(r,p,stance==Stance.SNIPE,p.branch150()==ArcheryProgress.Branch150.RU_SUO&&charges>0);
    }
    public boolean active() { return active; }
    public boolean equipmentChanged(String key) { return key!=null&&!key.isEmpty()&&!equippedKey.equals(key); }
    public boolean charging() { return charging; }
    public Stance stance() { return stance; }
    public Persisted persisted() { return new Persisted(charges,recoveryAnchor,nieShiReady,previousBranch); }
    public void restore(Persisted s,ArcheryRules r) {
        if(s==null||s.branch150()==null||s.ruSuoCharges()<0||s.ruSuoCharges()>128) throw new IllegalArgumentException("Invalid persisted bow state");
        charges=Math.min(r.ruSuoCharges(),s.ruSuoCharges()); recoveryAnchor=s.recoveryAnchor(); nieShiReady=s.nieShiReadyTick();
        previousBranch=s.branch150();
    }
    public Snapshot snapshot(long now,ArcheryRules r,ArcheryProgress p) {
        int remaining=(int)Math.max(0,transientDuration-Math.max(0,now-animationStart));
        String action=remaining>0?transientAction:charging?prefix()+"charge":prefix()+"idle";
        double power=power(now,r,p);
        return new Snapshot(equipped,active,stance.name(),charging,chargeStart,power,requiredDrawTicks(r,p),lastShot,charges,
                active?action:"",animationStart,remaining,sequence,p.score()>=r.trajectoryScore(),
                active&&stance==Stance.SNIPE?ArcheryScores.scopeMagnification(r,p,power):1);
    }
}

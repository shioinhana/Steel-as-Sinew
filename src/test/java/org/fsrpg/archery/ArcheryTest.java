package org.fsrpg.archery;

import static org.fsrpg.archery.ArcheryProgress.Branch150.*;
import static org.fsrpg.archery.ArcheryProgress.Branch250.YAN_XIA_DIAN;

/** Pure state regression for server-only timing, branch formulas and interruption. */
public final class ArcheryTest {
    private static int checks;
    private static final ArcheryRules R=ArcheryRules.defaults();
    private static ArcheryProgress progress(int score,ArcheryProgress.Branch150 first,ArcheryProgress.Branch250 second) {
        return new ArcheryProgress(true,score,first,second);
    }
    private static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    private static void near(double actual,double expected,String label) { check(Math.abs(actual-expected)<1e-8,label+": "+actual); }
    private static ArcheryController active(ArcheryProgress p) {
        var c=new ArcheryController(R); c.reconcile("main:0:bow",true,ArcheryController.Stance.RAPID,100,R,p); return c;
    }
    public static void main(String[] args) {
        var novice=progress(0,NONE,ArcheryProgress.Branch250.NONE);
        near(ArcheryScores.requiredDrawTicks(R,novice,false,false),10,"legacy rapid half second");
        near(ArcheryScores.requiredDrawTicks(R,novice,true,false),30,"legacy sniper one and half seconds");
        near(ArcheryScores.rapidReduction(R,4),0,"five score step flooring");
        near(ArcheryScores.rapidReduction(R,5),.01,"first rapid bonus");
        near(ArcheryScores.rapidReduction(R,2000),.3,"rapid bonus cap");
        near(ArcheryScores.snipeSpeedBonus(R,2),0,"three score step flooring");
        near(ArcheryScores.snipeSpeedBonus(R,300),1,"sniper speed cap");
        var c=active(novice); check(c.beginCharge(100),"start server draw");
        check(!c.beginCharge(101),"repeat held intent preserves start");
        check(c.release(101,false,R,novice)==null,"under threshold costs no shot");
        c.beginCharge(102); var partial=c.release(104,false,R,novice);
        near(partial.power(),.2,"threshold release"); near(partial.speed(),.75,"legacy minimum velocity quarter");
        check(partial.durability()==1,"rapid normal durability");
        c.beginCharge(200); check(!c.autoReady(220,R,novice),"50 score gate");
        var rapid=progress(50,NONE,ArcheryProgress.Branch250.NONE);
        check(c.autoReady(220,R,rapid),"50 score full draw auto");
        var shot=c.release(220,true,R,rapid); c.commitShot(shot,220,R);
        check(c.charging(),"automatic shot starts next server draw");
        check(c.snapshot(221,R,rapid).animationAction().equals("bow_rapid_release"),"release priority over next charge");
        check(c.snapshot(226,R,rapid).animationAction().equals("bow_rapid_charge"),"returns to progressing draw");
        check(c.reconcile("off:bow",true,ArcheryController.Stance.RAPID,227,R,rapid),"equipment identity interrupt");
        check(!c.charging(),"bow hand change aborts draw");
        c.beginCharge(228); c.reconcile("off:bow",true,ArcheryController.Stance.SNIPE,229,R,rapid);
        check(!c.charging(),"stance selection aborts draw without releasing");
        c.beginCharge(230); var sniper=c.release(260,false,R,rapid);
        check(sniper.stance()==ArcheryController.Stance.SNIPE&&sniper.durability()==3,"sniper three fixed durability");
        near(sniper.speed(),3.6*1.16,"sniper score speed scaling");
        var shuttle=progress(150,RU_SUO,YAN_XIA_DIAN); var s=active(shuttle);
        near(s.requiredDrawTicks(R,shuttle),5,"additive reduction cap");
        for(int i=0;i<5;i++) { s.beginCharge(300+i*10); var q=s.release(305+i*10,false,R,shuttle); check(q.ruSuo(),"charged shuttle shot"); s.commitShot(q,305+i*10,R); }
        check(s.persisted().ruSuoCharges()==0,"legacy five charges"); near(s.requiredDrawTicks(R,shuttle),7,"exhausted branch only score bonus");
        check(!s.recover(364,R,shuttle),"recovery before sixty ticks"); check(s.recover(365,R,shuttle),"one charge after sixty ticks");
        check(s.persisted().ruSuoCharges()==1,"single charge recovery");
        var persist=s.persisted(); var restored=new ArcheryController(R); restored.restore(persist,R);
        restored.reconcile("bow",true,ArcheryController.Stance.RAPID,365,R,shuttle);
        check(restored.persisted().ruSuoCharges()==1,"relogin does not refill saved charge");
        restored.detach(); restored.reconcile("bow",true,ArcheryController.Stance.RAPID,366,R,shuttle);
        check(restored.persisted().ruSuoCharges()==1,"logout clears animation only");
        check(restored.claimCapturedArrow(400,R),"capture available"); check(!restored.claimCapturedArrow(439,R),"capture cooldown");
        check(restored.claimCapturedArrow(440,R),"capture forty ticks boundary");
        var max=progress(300,RU_SUO,YAN_XIA_DIAN);
        near(ArcheryScores.arrowSpeed(R,max,true,1),9.36,"rock lightning plus sniper score");
        near(ArcheryScores.scopeMagnification(R,max,1),8,"maximum scope eight");
        near(ArcheryScores.scopeMagnification(R,max,.5),4.5,"scope follows actual draw");
        near(ArcheryScores.scopeMagnification(R,shuttle,1),1,"scope score gate");
        var base=new ArcheryProgress(true,0,NONE,ArcheryProgress.Branch250.NONE,3,.8);
        near(ArcheryScores.requiredDrawTicks(R,base,true,false),24,"base progression bow draw multiplier");
        var b=active(rapid); check(b.bash(500,R),"bash accepts"); check(!b.beginCharge(501),"bash blocks immediate draw overwrite");
        check(b.automatic(true,501,R,rapid),"automatic hold queued after bash"); check(!b.charging(),"queued automatic does not overwrite bash");
        check(b.maintainAutomatic(507),"automatic begins after bash finishes");
        check(!b.bash(506,R),"independent bash cooldown"); check(!b.charging(),"cooldown bash still cancels draw");
        check(!b.automaticHeld()&&!b.maintainAutomatic(520),"cooldown bash clears the old held intention");
        check(b.automatic(true,530,R,rapid),"new press can restart continuous draw");
        b.interruptHeld();
        check(!b.charging()&&!b.automaticHeld()&&!b.maintainAutomatic(540),"screen or stun interruption cannot restart itself");
        check(b.release(540,false,R,rapid)==null,"releasing an interrupted press never fires a free arrow");
        check(b.beginCharge(550),"fresh right press starts charge");
        check(b.bash(551,R),"left bash cancels an in-progress right draw");
        b.reconcile("main:0:bow",true,ArcheryController.Stance.SNIPE,552,R,rapid);
        check(!b.charging()&&!b.bash(552,R),"mode switch cancels draw but preserves bash cooldown");
        check(b.claimCapturedArrow(552,R),"capture before switching stance");
        b.reconcile("main:0:bow",true,ArcheryController.Stance.RAPID,553,R,rapid);
        check(!b.claimCapturedArrow(591,R),"stance switch preserves capture cooldown");
        check(b.claimCapturedArrow(592,R),"capture recovers exactly at forty ticks after stance switch");
        var scoped=active(max);
        scoped.reconcile("main:0:bow",true,ArcheryController.Stance.SNIPE,100,R,max);
        scoped.beginCharge(100);
        near(scoped.requiredDrawTicks(R,max),24,"shuttle also reduces sniper draw by twenty percent");
        check(!scoped.autoReady(200,R,max),"sniper never automatically releases even above fifty score");
        var low=active(progress(49,NONE,ArcheryProgress.Branch250.NONE));low.beginCharge(100);
        check(!low.autoReady(1000,R,progress(49,NONE,ArcheryProgress.Branch250.NONE)),"49 score cannot auto fire a held right draw");
        var uninterrupted=active(rapid);uninterrupted.beginCharge(100);
        for(int cycle=0;cycle<12;cycle++) {
            long now=109+cycle*9;
            check(uninterrupted.autoReady(now,R,rapid),"held right repeats only at full server draw "+cycle);
            var fired=uninterrupted.release(now,true,R,rapid);uninterrupted.commitShot(fired,now,R);
            check(!uninterrupted.autoReady(now,R,rapid),"successful shot resets full draw timer "+cycle);
        }
        b.beginCharge(510); b.reconcile("bow",false,ArcheryController.Stance.RAPID,511,R,rapid); check(!b.charging(),"death or stun cancel");
        var locked=new ArcheryController(R);
        locked.reconcile("bow",false,ArcheryController.Stance.RAPID,1,R,ArcheryProgress.locked());
        check(!locked.reconcile("bow",false,ArcheryController.Stance.RAPID,2,R,ArcheryProgress.locked()),"locked snapshot stable");
        // Partial releases always receive one flat seventy-percent discount, independent of draw.
        near(ArcheryScores.shotStaminaCost(R,false,.2),3.5,"partial rapid shot costs seventy percent");
        near(ArcheryScores.shotStaminaCost(R,true,.5),10.5,"partial snipe shot costs seventy percent");
        near(ArcheryScores.shotStaminaCost(R,false,1),5,"full rapid shot costs the configured value");
        near(ArcheryScores.shotStaminaCost(R,true,1),15,"full snipe shot costs the configured value");
        near(ArcheryScores.arrowSpeed(R,progress(0,NONE,ArcheryProgress.Branch250.NONE),false,1),3,"rapid base speed");
        near(ArcheryScores.arrowSpeed(R,progress(0,NONE,YAN_XIA_DIAN),false,1),3.9,"rock lightning also speeds rapid arrows");
        // Ru Suo charges recover one per sixty ticks and survive logout.
        var pierced=progress(150,RU_SUO,ArcheryProgress.Branch250.CHUAN_TANG_FENG);
        var refill=new ArcheryController(R); refill.reconcile("bow",true,ArcheryController.Stance.RAPID,1,R,pierced);
        refill.beginCharge(1); var first=refill.release(6,false,R,pierced); refill.commitShot(first,6,R);
        check(refill.persisted().ruSuoCharges()==4,"the first shot spends one stored charge");
        for(int i=0;i<4;i++) { refill.beginCharge(20+i*5); var spent=refill.release(25+i*5,false,R,pierced); refill.commitShot(spent,25+i*5,R); }
        check(refill.persisted().ruSuoCharges()==0,"five shots exhaust the shuttle reserve");
        check(!refill.recover(65,R,pierced),"recovery waits a full sixty ticks from the first spent shot");
        check(refill.recover(66,R,pierced)&&refill.persisted().ruSuoCharges()==1,"first recovered charge");
        check(refill.recover(126,R,pierced)&&refill.persisted().ruSuoCharges()==2,"second recovered charge");
        var refillState=refill.persisted();
        var relogged=new ArcheryController(R); relogged.restore(refillState,R);
        check(relogged.persisted().ruSuoCharges()==2,"relogin keeps recovered charges");
        check(refill.recover(186,R,pierced)&&refill.persisted().ruSuoCharges()==3,"third recovered charge");
        check(refill.recover(246,R,pierced)&&refill.persisted().ruSuoCharges()==4,"fourth recovered charge");
        check(refill.recover(306,R,pierced)&&refill.persisted().ruSuoCharges()==5,"reserve refills to five");
        check(!refill.recover(366,R,pierced)&&refill.persisted().ruSuoCharges()==5,"a full reserve stops recovery");
        // A below-threshold release never consumes a shuttle charge.
        var cautious=new ArcheryController(R); cautious.reconcile("bow",true,ArcheryController.Stance.RAPID,1,R,pierced);
        cautious.beginCharge(100); check(cautious.release(100,false,R,pierced)==null,"under-threshold release has no shot");
        check(cautious.persisted().ruSuoCharges()==5,"under-threshold release keeps all charges");
        // Nie Shi cooldown is persisted, not cleared by relogin.
        var catcher=new ArcheryController(R); catcher.reconcile("bow",true,ArcheryController.Stance.RAPID,1,R,progress(150,NIE_SHI,ArcheryProgress.Branch250.NONE));
        check(catcher.claimCapturedArrow(400,R),"capture accepted");
        var catcherState=catcher.persisted();
        var catcherRelogged=new ArcheryController(R); catcherRelogged.restore(catcherState,R);
        check(!catcherRelogged.claimCapturedArrow(439,R),"relogin keeps the capture cooldown");
        check(catcherRelogged.claimCapturedArrow(440,R),"capture ready after forty ticks");
        // Persisted state validation rejects corrupt files instead of inventing charges.
        for(var corrupt:new ArcheryController.Persisted[]{
                new ArcheryController.Persisted(-1,0,0,ArcheryProgress.Branch150.NONE),
                new ArcheryController.Persisted(129,0,0,ArcheryProgress.Branch150.NONE),
                new ArcheryController.Persisted(0,0,0,null)})
            try { new ArcheryController(R).restore(corrupt,R);throw new AssertionError("corrupt persisted state accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
        // Shuttle reduction also applies to the scope's sniper draw; pierce branch caps magnification below eight.
        near(ArcheryScores.scopeMagnification(R,progress(300,RU_SUO,ArcheryProgress.Branch250.CHUAN_TANG_FENG),1),1+7*(7.2/9.36),"pierce branch scope follows actual arrow speed");
        near(ArcheryScores.scopeMagnification(R,progress(299,RU_SUO,YAN_XIA_DIAN),1),1,"scope needs its three hundred score unlock");
        // Both shipped automatic inputs keep working: the 0.5-migration left-button hold and the
        // held right-button charge that auto-releases at full draw.
        var leftHold=new ArcheryController(R); leftHold.reconcile("main:0:bow",true,ArcheryController.Stance.RAPID,1,R,rapid);
        check(leftHold.automatic(true,1,R,rapid)&&leftHold.charging(),"left hold starts the rapid draw");
        check(leftHold.automaticHeld(),"left hold intention persists while held");
        check(leftHold.autoReady(10,R,rapid),"left hold auto releases at a full rapid draw");
        leftHold.release(10,true,R,rapid);
        check(!leftHold.automatic(false,10,R,rapid)&&!leftHold.automaticHeld(),"releasing left clears the hold intention");
        var rightHold=new ArcheryController(R); rightHold.reconcile("main:0:bow",true,ArcheryController.Stance.RAPID,1,R,rapid);
        rightHold.beginCharge(100);
        check(rightHold.autoReady(109,R,rapid),"right hold reaches a full rapid draw");
        var rightShot=rightHold.release(109,true,R,rapid); rightHold.commitShot(rightShot,109,R);
        check(rightHold.charging()&&rightHold.autoReady(118,R,rapid),"right hold keeps firing after its automatic release");
        var noAutoYet=progress(49,NONE,ArcheryProgress.Branch250.NONE);
        var lowLeft=new ArcheryController(R); lowLeft.reconcile("main:0:bow",true,ArcheryController.Stance.RAPID,1,R,noAutoYet);
        check(!lowLeft.automatic(true,1,R,noAutoYet)&&!lowLeft.charging(),"left hold needs the fifty score unlock");
        checks+=ArcheryBallisticsTest.run();
        System.out.println("ArcheryTest: "+checks+" checks passed");
    }
}

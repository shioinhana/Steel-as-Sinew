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
        check(!c.charging(),"R stance change aborts draw");
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
        b.beginCharge(510); b.reconcile("bow",false,ArcheryController.Stance.RAPID,511,R,rapid); check(!b.charging(),"death or stun cancel");
        var locked=new ArcheryController(R);
        locked.reconcile("bow",false,ArcheryController.Stance.RAPID,1,R,ArcheryProgress.locked());
        check(!locked.reconcile("bow",false,ArcheryController.Stance.RAPID,2,R,ArcheryProgress.locked()),"locked snapshot stable");
        System.out.println("ArcheryTest: "+checks+" checks passed");
    }
}

package org.fsrpg.combat;

import java.util.UUID;

public final class TelegraphedGuardTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static CombatActor guarding(){
        var actor=new CombatActor(UUID.randomUUID(),CombatRules.defaults());actor.requestDrawn();
        while(actor.phase()!=CombatPhase.READY)actor.tick();
        check(actor.setGuarding(true)==ActionResult.ACCEPTED,"Real guard pose accepted");return actor;
    }
    private static HitResult receive(CombatActor actor,boolean authorized){return CombatResolver.receiveTelegraphed(null,actor,0,10,0,true,true,false,authorized);}
    public static void main(String[] args){
        var early=guarding();check(early.inPerfectGuardWindow(),"Generic guard initially recent");
        check(receive(early,false).outcome()==HitOutcome.BLOCKED,"Recent generic guard cannot steal this enemy's cue");
        var held=guarding();for(int i=0;i<20;i++)held.tick();
        check(receive(held,false).outcome()==HitOutcome.BLOCKED,"Held guard stays ordinary without a fresh authorized input");
        var precise=guarding();for(int i=0;i<20;i++)precise.tick();
        check(!precise.inPerfectGuardWindow(),"Generic press-age window has expired");
        double stamina=precise.stamina();
        check(receive(precise,true).outcome()==HitOutcome.PARRIED,"This attack's timely input survives until its own contact");
        check(Math.abs(stamina-precise.stamina()-precise.guardRules().perfectGuardStaminaCost())<1e-6,"Perfect guard still pays configured stamina");
        check(precise.counterRemainingTicks()>0,"Authorized actual parry enables counter");
        var back=guarding();
        check(CombatResolver.receiveTelegraphed(null,back,180,10,0,true,true,false,true).outcome()==HitOutcome.HIT,"Authorization cannot bypass facing arc");
        var released=guarding();released.setGuarding(false);
        check(receive(released,true).outcome()==HitOutcome.HIT,"Authorization cannot invent a guard pose");
        var disabled=guarding();disabled.forceSheathed();
        var base=WeaponStyleDefinition.GuardRules.from(disabled.rules());
        var zero=new WeaponStyleDefinition.GuardRules(base.arcDegrees(),base.damageMultiplier(),base.staminaCost(),0,
                base.perfectGuardStaminaCost(),base.parryStunTicks(),base.guardBreakStunTicks(),0,128,false,false);
        var style=new WeaponStyleDefinition("disabled_perfect","test","sword",true,
                java.util.List.of(new WeaponStyleDefinition.ComboStage("test")),"",zero);
        disabled.setStyle(style);disabled.requestDrawn();while(disabled.phase()!=CombatPhase.READY)disabled.tick();disabled.setGuarding(true);
        check(receive(disabled,true).outcome()==HitOutcome.BLOCKED,"Explicitly disabled perfect guard remains disabled");
        var generic=guarding();check(CombatResolver.receiveIncoming(null,generic,0,10,0,true,true,false).outcome()==HitOutcome.PARRIED,"Non-cue compatibility path retains ordinary core timing");
        System.out.println("TelegraphedGuardTest passed: "+checks+" checks");
    }
}

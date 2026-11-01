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
        heldPerfectChain();automaticWindowBoundary();heldRenewalAndRelease();firstActiveDetectionGate();fractionalTokenAndHeldChain();
        System.out.println("TelegraphedGuardTest passed: "+checks+" checks");
    }

    private static CombatActor chainGuarding() {
        var guard=new WeaponStyleDefinition.GuardRules(120,.25,1,7,2,4,10,12,1,true,true);
        var heavy=new WeaponStyleDefinition.GuardRules(120,.2,1,0,2,4,10,12,1,true,true);
        var style=new WeaponStyleDefinition("chain","chain","sword",true,
                java.util.List.of(new WeaponStyleDefinition.ComboStage("test")),"",guard,heavy,
                WeaponStyleDefinition.Techniques.none());
        var actor=new CombatActor(UUID.randomUUID(),CombatRules.defaults());actor.setStyle(style);actor.requestDrawn();
        while(actor.phase()!=CombatPhase.READY)actor.tick();actor.setGuarding(true);return actor;
    }

    private static void heldPerfectChain() {
        var actor=chainGuarding();
        check(receive(actor,false).outcome()==HitOutcome.BLOCKED,"The first cue still requires its own press authorization");
        double stamina=actor.stamina();
        for(int perfect=1;perfect<=3;perfect++) {
            check(receive(actor,perfect==1).outcome()==HitOutcome.PARRIED,"Held C performs real perfect "+perfect+" despite ordinary repeat pressure");
            check(actor.heldPerfectGuards()==perfect,"Only real perfect contact advances continuous-hold count");
            check(actor.phase()==CombatPhase.GUARDING&&actor.snapshot().phaseTicksRemaining()==0,"Each success immediately rearms held guard without recovery");
            check(actor.counterRemainingTicks()==4,"Each real perfect grants its own four-tick counter");
            check(actor.guardAutoRearmed()==(perfect<3),"Automatic eligibility ends after the third success");
            check(Math.abs(stamina-actor.stamina()-perfect*2)<1e-8,"Each actual perfect pays its configured cost exactly once");
        }
        actor.setGuarding(true);actor.setGuarding(true,true);actor.setGuarding(true,false);actor.requestPrecisionGuard(false);
        check(actor.heldPerfectGuards()==3&&!actor.guardPerfectAgainst(true)&&!actor.guardPerfectAgainst(null),"Held and mode packets cannot refresh the exhausted three-perfect cap");
        actor.suspendGuarding();check(actor.phase()==CombatPhase.READY&&actor.guardHeld()&&actor.heldPerfectGuards()==3,"GUI suspension stops the pose without a physical release or cap reset");
        actor.setGuarding(true);check(!actor.guardPerfectAgainst(true)&&actor.heldPerfectGuards()==3,"GUI close and held renewal cannot wash the cap");
        check(receive(actor,true).outcome()==HitOutcome.GUARD_BROKEN,"Even an authorized fourth contact cannot perfect while C remains held");
        check(actor.guardHeld()&&actor.heldPerfectGuards()==3,"Forced guard break is not a physical release and retains the cap");
        for(int i=0;i<10;i++)actor.tick();actor.setGuarding(true);
        check(!actor.guardPerfectAgainst(true)&&actor.heldPerfectGuards()==3,"Recovery and another held renewal cannot wash the cap");
        actor.setGuarding(false);actor.setGuarding(true);
        check(actor.heldPerfectGuards()==0&&actor.inPerfectGuardWindow(),"An actual release and new press opens a fresh three-success chain");
        check(receive(actor,true).outcome()==HitOutcome.PARRIED&&actor.heldPerfectGuards()==1,"A fresh chain can perfect again despite old ordinary pressure");
    }

    private static void automaticWindowBoundary() {
        for(int elapsed=0;elapsed<=7;elapsed++) {
            var actor=chainGuarding();check(receive(actor,true).outcome()==HitOutcome.PARRIED,"First authorized contact starts the automatic timer");
            for(int i=0;i<elapsed;i++)actor.tick();
            check(actor.guardAutoRearmed()==(elapsed<7),"Rearmed perfect is eligible for exactly the configured seven ticks: "+elapsed);
            check(receive(actor,false).outcome()==(elapsed<7?HitOutcome.PARRIED:HitOutcome.GUARD_BROKEN),"An unclaimed telegraph uses only the fresh post-success timer: "+elapsed);
        }
    }

    private static void heldRenewalAndRelease() {
        var actor=chainGuarding();receive(actor,true);
        for(int i=0;i<6;i++)actor.tick();
        actor.setGuarding(true,true);check(!actor.guardAutoRearmed(),"Disabled heavy perfect remains disabled during a chain");
        actor.setGuarding(true,false);actor.requestPrecisionGuard(false);actor.setGuarding(true);
        check(actor.guardAutoRearmed()&&actor.heldPerfectGuards()==1,"Mode switches retain the real automatic timer and count");
        actor.tick();check(!actor.guardAutoRearmed(),"Mode and held renewal cannot extend the seventh-tick boundary");
        actor.setGuarding(false);actor.setGuarding(true);
        check(!actor.guardAutoRearmed()&&actor.heldPerfectGuards()==0,"Release discards automatic cue credit from the previous hold");
        check(!actor.guardPerfectAgainst(false)&&actor.guardPerfectAgainst(true),"A new hold needs its own cue authorization before its first perfect");
        check(receive(actor,true).outcome()==HitOutcome.PARRIED,"New press restarts the chain with real contact");
        actor.forceSheathed();check(actor.heldPerfectGuards()==0&&!actor.guardAutoRearmed(),"Equipment removal clears held-chain state");
        actor.suspendGuarding();actor.requestDrawn();while(actor.phase()!=CombatPhase.READY)actor.tick();actor.setGuarding(true);
        check(!actor.inPerfectGuardWindow()&&!actor.guardPerfectAgainst(false),"An already-held key after equipment change cannot invent a fresh press");
    }

    private static void firstActiveDetectionGate() {
        var actor=chainGuarding();actor.setGuarding(false);
        var swing=new SkillDefinition("gate_swing","gate_swing","sword",SkillInput.LEFT,2,3,1,0,2,0,6,3,90,0,true,true,false,"","");
        var gate=new SwingActivationGate();actor.requestAttack(swing);
        check(actor.phase()==CombatPhase.WINDUP,"Activation detector waits for actual ACTIVE rather than attack acceptance");
        actor.tick();actor.tick();long sequence=actor.snapshot().swingSequence();
        check(actor.phase()==CombatPhase.ACTIVE&&gate.claim(sequence),"The first real ACTIVE frame claims this swing's one detection opportunity");
        // No opponent qualifies on that first frame. Entering an NPC window later must not retry this swing.
        actor.tick();check(actor.phase()==CombatPhase.ACTIVE&&!gate.claim(sequence),"No-candidate first ACTIVE still consumes the detection before a later NPC window opens");
        actor.tick();check(!gate.claim(sequence),"Further active frames cannot repeatedly trigger automatic clashes");
        actor.tick();actor.tick();check(actor.phase()==CombatPhase.READY,"Fixture completes its authored active and recovery interval");
        actor.requestAttack(swing);actor.tick();actor.tick();long next=actor.snapshot().swingSequence();
        check(next==sequence+1&&gate.claim(next)&&!gate.claim(next),"A newly accepted swing receives exactly one independent ACTIVE check");
        check(new SwingActivationGate().claim(sequence),"A new actor's own gate can start from the same numeric sequence after login");
        for(long invalid:new long[]{0,-1,Long.MIN_VALUE}){
            boolean rejected=false;try{gate.claim(invalid);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Synthetic or invalid swing sequence cannot claim automatic detection");
        }
        check(!gate.claim(next),"Invalid claims cannot erase the previously consumed real sequence");
    }

    private static void fractionalTokenAndHeldChain() {
        var actor=chainGuarding();
        var window=org.fsrpg.creature.GuardCueSettings.defaults().window(100,100);
        check(window.openGameTick()==160&&window.press(160,1),"Long authored windup grants a token only in its final two-fifths");
        for(int i=0;i<40;i++)actor.tick();
        check(!actor.inPerfectGuardWindow(),"The local seven-tick press-age window expires before this long authored contact");
        boolean precise=window.consume(200,1,true);
        check(precise&&receive(actor,precise).outcome()==HitOutcome.PARRIED,"Attack-specific fractional token survives from opening through its exact real contact");
        check(!window.consume(200,1,true),"Real success cannot reuse that same attack's fractional input token");
        check(receive(actor,false).outcome()==HitOutcome.PARRIED&&receive(actor,false).outcome()==HitOutcome.PARRIED,"Two later actual contacts can use held automatic rearm without borrowing another token");
        check(actor.heldPerfectGuards()==3&&!actor.guardPerfectAgainst(true),"Fractional authorization and automatic rearm share the same continuous three-perfect cap");
    }
}

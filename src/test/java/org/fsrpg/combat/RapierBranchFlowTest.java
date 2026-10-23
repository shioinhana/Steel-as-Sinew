package org.fsrpg.combat;

import java.util.*;
import org.fsrpg.config.RpgConfig;

/** Real action-state branches: single-slot inputs, full contacts, charge commitment and defense priority. */
public final class RapierBranchFlowTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        var definitions=WeaponControlsTest.definitions();
        everyCutFrame(definitions);latestIntent(definitions);resourceFailure(definitions);
        defensiveCancellation(definitions);conditionalOpenings(definitions);standaloneCharge(definitions);
        System.out.println("RapierBranchFlowTest passed: "+checks+" checks");
    }
    private static CombatActor actor(RpgConfig.Definitions definitions) {
        var rules=new CombatRules(1000,100,0,0,72000,0,0,120,.25,10,2,10,4,10,2,120,12,0,6,20,6,3,true,false);
        var actor=new CombatActor(UUID.randomUUID(),rules);actor.setStyle(definitions.styles().get("rapier"));actor.requestDrawn();return actor;
    }
    private static ActionResult right(CombatActor actor,RpgConfig.Definitions d) {
        return actor.requestRapierThrust(d.skills().get("sword_thrust"),AttackAim.at(23,-12),true,true,16,200);
    }
    private static void everyCutFrame(RpgConfig.Definitions d) {
        for(var stage:d.styles().get("rapier").leftCombo()) {
            var cut=d.skills().get(stage.skillId());int contact=cut.windupTicks()+cut.activeTicks();
            for(int at=0;at<cut.durationTicks();at++) {
                var actor=actor(d);actor.requestAttack(cut);ticks(actor,at);long first=actor.snapshot().swingSequence();double before=actor.stamina();
                equal(ActionResult.ACCEPTED,right(actor,d),"right accepts each cut frame "+stage.skillId()+" @"+at);
                if(at<contact) {
                    equal(first,actor.snapshot().swingSequence(),"branch never cuts off preparation/contact");near(before,actor.stamina(),"buffering reserves no extra stamina");
                    equal(ActionResult.ACCEPTED,right(actor,d),"repeated pending right stays one slot");
                    actor.releaseChargedAttack();
                    int active=0;for(int remaining=contact-at;remaining>0;remaining--){if(actor.phase()==CombatPhase.ACTIVE)active++;actor.tick();}
                    equal(Math.min(cut.activeTicks(),contact-at),active,"every remaining collision tick is preserved");
                }
                equal(first+1,actor.snapshot().swingSequence(),"exactly one branch starts");equal("sword_thrust",actor.currentSkill().id(),"right branches into the stable thrust");
                check(actor.chargeSnapshot().active()&&!actor.chargeSnapshot().holding(),"branch uses commitment rules without a held charge");
                equal(0,actor.cooldownRemaining("sword_thrust"),"queued thrust has no cooldown before contact");
                equal(AttackAim.at(23,-12),actor.attackAim(),"latest validated branch direction retained");
                actor.releaseChargedAttack();ticks(actor,7);equal(CombatPhase.ACTIVE,actor.phase(),"branch has its entire authored preparation");
                equal(20,actor.cooldownRemaining("sword_thrust"),"branch cooldown starts only when committed");
                check(actor.consumeChargeLunge()&&!actor.consumeChargeLunge(),"one branch emits exactly one lunge");
                ticks(actor,4);equal(CombatPhase.READY,actor.phase(),"right branch naturally returns without held-input deadlock");
                check(!actor.comboSnapshot().queued(),"branch consumed the only input slot");
                actor.requestStyledAttack(SkillInput.LEFT,d.skills());equal("sword_left",actor.currentSkill().id(),"left after right starts a clean main-chain entry");
            }
        }
    }
    private static void latestIntent(RpgConfig.Definitions d) {
        var actor=actor(d);actor.requestStyledAttack(SkillInput.LEFT,d.skills());right(actor,d);
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());ticks(actor,6);
        equal("rapier_downcut",actor.currentSkill().id(),"latest left replaces the single buffered right");check(!actor.chargeSnapshot().active(),"replaced thrust cannot leak charge metadata into a cut");
        actor=actor(d);actor.requestStyledAttack(SkillInput.LEFT,d.skills());right(actor,d);ticks(actor,6);
        long sequence=actor.snapshot().swingSequence();actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        equal(sequence,actor.snapshot().swingSequence(),"left during branch preparation waits instead of feinting away its thrust");
        ticks(actor,8);equal("sword_thrust",actor.currentSkill().id(),"branch keeps both active collision ticks");actor.tick();
        equal("sword_left",actor.currentSkill().id(),"left returns to the main chain after branch contact");
    }
    private static void resourceFailure(RpgConfig.Definitions d) {
        var actor=actor(d);actor.requestStyledAttack(SkillInput.LEFT,d.skills());right(actor,d);actor.setResources(0,0);
        // Mark the cut as a true contact so no miss refund can pay for a queued thrust.
        ticks(actor,3);actor.markAttackContact(actor.snapshot().swingSequence());ticks(actor,3);
        equal("sword_left",actor.currentSkill().id(),"unaffordable branch does not erase the original recovery");
        equal(CombatPhase.RECOVERY,actor.phase(),"failed branch returns through normal recovery");check(!actor.comboSnapshot().queued(),"failed branch clears the pending input once");
        equal("INSUFFICIENT_STAMINA",actor.comboSnapshot().lastRejection(),"resource failure remains visible");
        ticks(actor,3);equal(CombatPhase.READY,actor.phase(),"resource failure cannot strand the actor");actor.restoreResources(20,0);
        check(actor.requestStyledAttack(SkillInput.LEFT,d.skills()).accepted(),"next affordable input is accepted");
    }
    private static void defensiveCancellation(RpgConfig.Definitions d) {
        for(boolean dodge:List.of(false,true)) {
            var actor=actor(d);actor.requestStyledAttack(SkillInput.LEFT,d.skills());right(actor,d);
            equal(ActionResult.ACCEPTED,dodge?actor.requestDodge(true):actor.setGuarding(true),"defense cancels branch preparation");
            check(!actor.comboSnapshot().queued(),"defense clears pending thrust and its metadata");
            if(!dodge)actor.setGuarding(false);ticks(actor,30);
            equal(1L,actor.snapshot().swingSequence(),"cancelled branch never starts later");check(!actor.consumeChargeLunge(),"cancelled branch cannot emit delayed motion");
        }
        var actor=actor(d);actor.requestStyledAttack(SkillInput.LEFT,d.skills());right(actor,d);ticks(actor,3);
        equal(ActionResult.BUSY,actor.requestDodge(true),"ordinary dodge cannot erase an already active contact");
        equal(ActionResult.ACCEPTED,actor.requestPrecisionGuard(false),"validated precise guard retains emergency priority");
        check(!actor.comboSnapshot().queued()&&actor.currentSkill()==null,"precise guard drops current and pending attacks");
        actor=actor(d);actor.requestStyledAttack(SkillInput.LEFT,d.skills());right(actor,d);ticks(actor,3);
        check(actor.cancelChargedPreparation(),"opening a GUI drops an unstarted thrust branch");
        equal("sword_left",actor.currentSkill().id(),"GUI cancellation preserves already active cut contact");ticks(actor,20);
        equal(1L,actor.snapshot().swingSequence(),"GUI cannot release a hidden queued thrust");
    }
    private static void conditionalOpenings(RpgConfig.Definitions d) {
        var actor=actor(d);actor.setGuarding(true);actor.perfectGuardSucceeded();
        equal("rapier_riposte",actor.techniqueSnapshot().skillId(),"successful light precision guard opens riposte");
        check(actor.aimedInput(SkillInput.RIGHT,d.skills(),AttackAim.at(0,0)).accepted(),"right uses the server-owned riposte window");
        equal("rapier_riposte",actor.currentSkill().id(),"riposte takes precedence over ordinary thrust");check(!actor.chargeSnapshot().active(),"riposte does not inherit charge hold");
        actor=actor(d);actor.requestDodge(true);ticks(actor,12);
        equal("rapier_reprise",actor.techniqueSnapshot().skillId(),"completed dodge opens reprise");
        check(actor.aimedInput(SkillInput.RIGHT,d.skills(),AttackAim.at(0,0)).accepted(),"right consumes reprise opening");equal("rapier_reprise",actor.currentSkill().id(),"dodge branches into return thrust");
        actor=actor(d);actor.requestDodge(true);actor.stun(1);ticks(actor,12);equal("",actor.techniqueSnapshot().skillId(),"interrupted dodge never grants return thrust");
        actor=actor(d);actor.perfectGuardSucceeded();ticks(actor,20);
        equal(ActionResult.TECHNIQUE_NOT_READY,actor.requestAttack(d.skills().get("rapier_riposte")),"expired named technique cannot bypass its condition");
    }
    private static void standaloneCharge(RpgConfig.Definitions d) {
        var actor=actor(d);equal(ActionResult.ACCEPTED,right(actor,d),"standalone right retains legacy charge");ticks(actor,20);
        check(actor.chargeSnapshot().holding()&&actor.chargeSnapshot().fullyCharged(),"idle hold still reaches full charge");
        actor.releaseChargedAttack();equal(CombatPhase.ACTIVE,actor.phase(),"standalone release commits the full charge");check(actor.consumeChargeLunge(),"standalone release still lunges");
    }
    private static void ticks(CombatActor actor,int ticks){for(int i=0;i<ticks;i++)actor.tick();}
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual);}
    private static void near(double expected,double actual,String message){check(Math.abs(expected-actual)<1e-8,message);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}

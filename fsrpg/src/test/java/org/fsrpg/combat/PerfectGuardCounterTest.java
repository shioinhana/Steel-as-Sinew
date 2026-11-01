package org.fsrpg.combat;

import java.util.*;

/** Exact authoritative 20 Hz boundaries, physical LEFT intent and one-swing damage ownership. */
public final class PerfectGuardCounterTest {
    private static int checks;
    private static final SkillDefinition LEFT = skill("left", SkillInput.LEFT, 0, 1, 0, 0, 5);
    private static final SkillDefinition RIGHT = skill("right", SkillInput.RIGHT, 0, 1, 0, 0, 5);

    public static void main(String[] args) {
        perfectGuardBoundaries(); counterBoundaries(); physicalInputRoutes(); failedInputAndSingleUse();
        interruptCleanup(); attackerStunBoundaries(); staleGuardRenewal(); independentAcceptedLeftCounter();
        successfulDefenseCleanup(); clashClearsExistingReactions(); delayedClashRewardOwnership(); heldProjectileReflection();
        heldRenewalYieldsTheAcceptedSwing();
        System.out.println("PerfectGuardCounterTest passed: " + checks + " checks");
    }

    private static void independentAcceptedLeftCounter() {
        for(int elapsed=0;elapsed<=4;elapsed++) {
            CombatActor actor=parried(style("bow",false));ticks(actor,elapsed);
            double stamina=actor.stamina();
            double frozen=actor.consumeCounterForAcceptedLeftAttack();
            near(elapsed<4?1.5:1,frozen,"independent accepted bow left captures the same four-tick counter boundary");
            near(1,actor.consumeCounterForAcceptedLeftAttack(),"independent counter is consumed once");
            ticks(actor,10);near(elapsed<4?1.5:1,frozen,"accepted action keeps its frozen multiplier after the window closes");
            check(actor.stamina()>=stamina,"counter multiplier does not debit resources a second time");
        }
    }

    private static void perfectGuardBoundaries() {
        for (int elapsed = 0; elapsed <= 3; elapsed++) {
            CombatActor defender = ready(style("rapier", false)); defender.setGuarding(true); ticks(defender, elapsed);
            var result = incoming(defender);
            equal(elapsed < 2 ? HitOutcome.PARRIED : HitOutcome.BLOCKED, result.outcome(), "perfect guard elapsed tick " + elapsed);
            equal(elapsed < 2 ? 4 : 0, defender.counterRemainingTicks(), "only perfect contact grants left counter");
        }
        CombatActor defender = ready(style("rapier", false)); defender.setGuarding(true); ticks(defender, 1);
        defender.setGuarding(true); ticks(defender, 1);
        check(!defender.inPerfectGuardWindow(), "held guard renewals cannot extend initial two ticks");
        defender.setGuarding(false); defender.setGuarding(true);
        check(defender.inPerfectGuardWindow(), "a real release followed by a new guard starts a new window");
        defender = ready(style("rapier", false)); defender.setGuarding(true, true); ticks(defender, 1);
        defender.setGuarding(true, false); ticks(defender, 1);
        check(!defender.inPerfectGuardWindow(), "heavy/light switch does not renew original guard start");
        defender = ready(style("rapier", false)); defender.setGuarding(true, true);
        equal(HitOutcome.BLOCKED, incoming(defender).outcome(), "existing disabled heavy perfect window remains disabled");
        equal(0, defender.counterRemainingTicks(), "ordinary heavy block grants no counter");
    }

    private static void counterBoundaries() {
        for (int elapsed = 0; elapsed <= 4; elapsed++) {
            CombatActor defender = parried(style("rapier", false)); ticks(defender, elapsed);
            equal(Math.max(0, 4 - elapsed), defender.counterRemainingTicks(), "counter deadline at elapsed " + elapsed);
            check(defender.aimedInput(SkillInput.LEFT, Map.of("left", LEFT, "right", RIGHT), AttackAim.at(0,0)).started(), "physical left attack starts");
            near(elapsed < 4 ? 1.5 : 1, defender.attackDamageMultiplier(), "T through T+3 counter, T+4 ordinary");
            CombatActor target = ready(style("victim", false));
            HitResult hit = CombatResolver.resolveHit(defender, target, 1, 0, 0);
            near(elapsed < 4 ? 12 : 8, hit.damage(), "base damage multiplier applies at contact");
            equal(0, defender.counterRemainingTicks(), "successful counter consumes its single opportunity");
        }
    }

    private static void physicalInputRoutes() {
        for (String weapon : List.of("rapier", "spear", "dual")) {
            var style = style(weapon, weapon.equals("dual"));
            CombatActor actor = parried(style);
            if (weapon.equals("spear")) equal(ActionResult.ACCEPTED, actor.aimedLeftSkill(LEFT, AttackAim.at(0,0)), "spear cursor's left intent starts");
            else check(actor.aimedInput(SkillInput.LEFT, catalog(), AttackAim.at(0,0)).started(), weapon + " left route starts");
            near(1.5, actor.attackDamageMultiplier(), weapon + " physical left receives counter bonus");
        }
        CombatActor actor = parried(style("dual", true));
        check(actor.aimedInput(SkillInput.RIGHT, catalog(), AttackAim.at(0,0)).started(), "dual physical right starts its configured cut");
        near(1, actor.attackDamageMultiplier(), "physical right cannot receive a left counter");
        equal(4, actor.counterRemainingTicks(), "physical right does not consume left-only opportunity");
        actor.tick(); actor.aimedInput(SkillInput.LEFT, catalog(), AttackAim.at(0,0));
        near(1.5, actor.attackDamageMultiplier(), "subsequent real left within four ticks receives bonus");
        actor = parried(style("rapier", false)); actor.aimedSkill(LEFT, AttackAim.at(0,0));
        near(1, actor.attackDamageMultiplier(), "named skill route is not a physical left intent");
        equal(4, actor.counterRemainingTicks(), "named skill leaves left opportunity untouched");
        actor = parried(style("rapier", false)); actor.aimedLeftSkill(LEFT, AttackAim.at(0,0));
        actor.queueHitStun(2);
        near(1.5,actor.attackDamageMultiplier(),"queued ordinary hit preserves this tick's committed outgoing trade");
        actor.tick();near(1,actor.attackDamageMultiplier(),"next-tick stun clears the traded swing's multiplier");
    }

    private static void failedInputAndSingleUse() {
        CombatActor actor = parried(style("rapier", false)); actor.setResources(0, 100);
        var result = actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0));
        equal(ActionResult.INSUFFICIENT_STAMINA, result.rejection(), "insufficient resources reject counter attack");
        equal(4, actor.counterRemainingTicks(), "failed input cannot consume counter window");
        near(1, actor.attackDamageMultiplier(), "failed attack has no active damage bonus");
        actor.setResources(100, 100);
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0)).started(), "resource repair can retry within window");
        near(1.5, actor.attackDamageMultiplier(), "retried accepted left consumes counter");
        actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0));
        actor.tick();
        near(1, actor.attackDamageMultiplier(), "buffered next attack does not inherit previous swing's bonus");
        actor.tick();
        near(1, actor.attackDamageMultiplier(), "finished swing clears bonus");
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0)).started(), "later ordinary attack starts");
        near(1, actor.attackDamageMultiplier(), "one perfect guard cannot enhance another attack");
        actor=ready(style("rapier",false));
        var cooling=skill("left",SkillInput.LEFT,0,1,0,20,5);actor.requestAttack(cooling);actor.tick();actor.setGuarding(true);incoming(actor);
        equal(ActionResult.COOLDOWN,actor.aimedInput(SkillInput.LEFT,Map.of("left",cooling,"right",RIGHT),AttackAim.at(0,0)).rejection(),"cooldown rejects attempted counter");
        equal(4,actor.counterRemainingTicks(),"cooldown failure preserves counter opportunity");
        // A distinct existing right-click riposte remains governed by its own technique window.
        var style = style("rapier", false);
        var withRiposte = new WeaponStyleDefinition(style.id(),style.label(),style.weaponGroup(),true,style.leftCombo(),style.rightSkill(),style.guard(),style.heavyGuard(),
                new WeaponStyleDefinition.Techniques("riposte",20,"",0),style.cancels(),style.controls());
        actor = parried(withRiposte);
        equal("riposte", actor.techniqueSnapshot().skillId(), "old right-click riposte is independently available");
        equal(4, actor.counterRemainingTicks(), "new left window is exactly four ticks beside old twenty-tick riposte");
        var riposte=skill("riposte",SkillInput.RIGHT,0,1,0,0,5);
        check(actor.aimedInput(SkillInput.RIGHT,Map.of("left",LEFT,"right",RIGHT,"riposte",riposte),AttackAim.at(0,0)).started(), "old right-click riposte remains usable");
        near(1,actor.attackDamageMultiplier(),"right-click riposte does not receive new left-only bonus");
    }

    private static void interruptCleanup() {
        CombatActor actor = parried(style("rapier", false)); actor.stun(1);
        equal(0, actor.counterRemainingTicks(), "forced stun clears unused counter");
        actor = parried(style("rapier", false)); actor.queueHitStun(1);
        equal(0, actor.counterRemainingTicks(), "damage interruption clears unused counter immediately");
        actor = parried(style("rapier", false)); actor.requestDodge(true);
        equal(0, actor.counterRemainingTicks(), "accepted dodge clears unused counter");
        actor = parried(style("rapier", false)); actor.forceSheathed();
        equal(0, actor.counterRemainingTicks(), "death/equipment removal clears unused counter");
        actor = parried(style("rapier", false)); actor.setGuarding(false); actor.setStyle(style("other",false));
        equal(0, actor.counterRemainingTicks(), "changing weapon style clears unused counter");
        actor = parried(style("rapier", false)); actor.aimedLeftSkill(LEFT,AttackAim.at(0,0)); actor.stun(1);
        near(1,actor.attackDamageMultiplier(),"interrupting enhanced attack clears its multiplier");
        actor.tick(); actor.aimedLeftSkill(LEFT,AttackAim.at(0,0));
        near(1,actor.attackDamageMultiplier(),"interrupted enhancement cannot reappear on another swing");
    }

    private static void attackerStunBoundaries() {
        CombatActor defender = ready(style("rapier",false)), attacker = ready(style("enemy",false));
        defender.setGuarding(true); attacker.aimedLeftSkill(LEFT,AttackAim.at(0,0));
        equal(HitOutcome.PARRIED,CombatResolver.resolveHit(attacker,defender,1,0,0).outcome(),"real incoming attack is perfectly blocked");
        for(int elapsed=0;elapsed<4;elapsed++) {
            equal(CombatPhase.STUNNED,attacker.phase(),"attacker is stunned at elapsed "+elapsed);
            equal(ActionResult.BUSY,attacker.requestAttack(LEFT),"stunned attacker cannot attack");
            equal(4-elapsed,attacker.snapshot().phaseTicksRemaining(),"exact enemy stun remaining");
            attacker.tick();
        }
        equal(CombatPhase.READY,attacker.phase(),"attacker resumes at exact fourth tick");
        equal(ActionResult.ACCEPTED,attacker.requestAttack(LEFT),"attacker can act after four complete stun ticks");
    }

    private static void staleGuardRenewal() {
        CombatActor actor=parried(style("rapier",false));
        var longer=skill("left",SkillInput.LEFT,2,2,3,0,5);
        actor.aimedLeftSkill(longer,AttackAim.at(0,0));
        equal(CombatPhase.WINDUP,actor.phase(),"enhanced attack enters preparation");
        equal(ActionResult.ACCEPTED,actor.setGuarding(true),"late held renewal is idempotent during counter windup");
        check(actor.currentSkill()==longer&&actor.phase()==CombatPhase.WINDUP,"late held packet cannot cancel preparation");
        near(1.5,actor.attackDamageMultiplier(),"late held packet retains current enhancement");
        ticks(actor,2);actor.setGuarding(true,true);
        check(actor.currentSkill()==longer&&actor.phase()==CombatPhase.ACTIVE,"held mode renewal cannot cancel contact");
        ticks(actor,2);equal(CombatPhase.RECOVERY,actor.phase(),"enhanced attack reaches recovery");
        actor.setGuarding(true);
        check(actor.currentSkill()==longer&&actor.phase()==CombatPhase.RECOVERY,"late held packet cannot cancel counter recovery");
        actor.setGuarding(false);
        equal(ActionResult.ACCEPTED,actor.setGuarding(true),"real release then re-press retains intentional recovery cancel");
        equal(CombatPhase.GUARDING,actor.phase(),"new guard press actually starts guarding");
        near(1,actor.attackDamageMultiplier(),"intentional guard cancellation clears enhanced swing");
    }

    private static void successfulDefenseCleanup() {
        var paid=skill("paid",SkillInput.LEFT,2,2,6,20,5);
        for(int elapsed:new int[]{0,2,4,8}) {
            var actor=ready(style("rapier",false));actor.aimedLeftSkill(paid,AttackAim.at(10,5));ticks(actor,elapsed);
            if(elapsed<4)actor.requestStyledAttack(SkillInput.RIGHT,catalog());
            actor.queueHitStun(9);
            double stamina=actor.stamina();int cooldown=actor.cooldownRemaining(paid.id());
            actor.perfectGuardSucceeded();
            equal(CombatPhase.READY,actor.phase(),"real perfect cancels every accepted attack phase "+elapsed);
            equal(0,actor.snapshot().phaseTicksRemaining(),"perfect clears prior recovery duration");
            check(actor.currentSkill()==null&&!actor.comboSnapshot().queued()&&actor.attackAim()==null,"perfect clears skill, aim and buffered next action");
            check(actor.drainStartedSkills().isEmpty(),"perfect removes unconsumed canceled attack events");
            near(stamina,actor.stamina(),"successful cleanup does not refund an accepted attack");
            equal(cooldown,actor.cooldownRemaining(paid.id()),"successful cleanup keeps accepted cooldown");
            equal(4,actor.counterRemainingTicks(),"perfect grants a fresh counter after clearing old state");
            equal(ActionResult.ACCEPTED,actor.aimedLeftSkill(LEFT,AttackAim.at(0,0)),"counter starts immediately after successful cleanup");
            near(1.5,actor.attackDamageMultiplier(),"immediate new physical left consumes the fresh reward");
            actor.tick();equal(CombatPhase.READY,actor.phase(),"old queued hurt stun cannot return after the new action");
        }
        for(boolean held:List.of(false,true)) {
            var actor=ready(style("rapier",false));if(held)actor.setGuarding(true);
            actor.stun(12);actor.queueHitStun(9);
            equal(ActionResult.BUSY,actor.setGuarding(true),"a normal guard request cannot escape existing stun");
            actor.perfectGuardSucceeded();
            equal(held?CombatPhase.GUARDING:CombatPhase.READY,actor.phase(),"trusted real success clears existing stun and restores held guard");
            equal(held?1:0,actor.heldPerfectGuards(),"only a held guard consumes the hold's first perfect");
            check(!held||actor.guardAutoRearmed(),"held success starts its fresh automatic window");
            actor.tick();check(actor.phase()!=CombatPhase.STUNNED,"cleared pending stun cannot reappear on next tick");
        }
        var actor=ready(style("rapier",false));
        actor.beginChargedAttack(paid,AttackAim.at(0,0),true,true,16,20);actor.perfectGuardSucceeded();
        check(!actor.chargeSnapshot().active()&&!actor.consumeChargeLunge(),"perfect cancels preparation, charge hold and lunge intent");
        var spear=new SkillDefinition("spear","spear","spear",SkillInput.LEFT,0,1,4,0,5,0,8,3,90,0,true,true,false,"","");
        actor=ready(style("spear",false));actor.requestAttack(spear);actor.setSpearAttackHeld(true,AttackAim.at(20,4));
        actor.perfectGuardSucceeded();check(!actor.spearAttackHeld()&&actor.currentSkill()==null,"perfect cancels an active held spear");
    }

    private static void clashClearsExistingReactions() {
        var paid=skill("clash_paid",SkillInput.LEFT,0,1,0,20,5);
        for(boolean completed:List.of(false,true)) {
            var actor=ready(style("rapier",false));actor.requestAttack(paid);
            long sequence=actor.snapshot().swingSequence();actor.markAttackContact(sequence);
            if(completed)actor.tick();
            actor.stun(12);actor.queueHitStun(9);double stamina=actor.stamina();int cooldown=actor.cooldownRemaining(paid.id());
            check(actor.currentSkill()==null&&actor.phase()==CombatPhase.STUNNED,"fixture lost current skill to an existing stun");
            actor.finishClash();equal(CombatPhase.STUNNED,actor.phase(),"empty no-argument clash cannot clear an unrelated reaction");
            check(actor.finishClash(sequence),"matching real contact can clear its old reaction even after skill ends");
            equal(CombatPhase.READY,actor.phase(),"matching clash clears all existing recovery and stun");
            equal(0,actor.snapshot().phaseTicksRemaining(),"matching clash clears reaction duration");
            near(stamina,actor.stamina(),"matching clash charges no guard fee and refunds no contact");
            equal(cooldown,actor.cooldownRemaining(paid.id()),"matching clash preserves cooldown after natural end or stun");
            actor.perfectGuardSucceeded();equal(4,actor.counterRemainingTicks(),"advantage can award a fresh opening after matching cleanup");
            check(!actor.finishClash(sequence),"duplicate old contact cannot clear its later reward");
            equal(4,actor.counterRemainingTicks(),"duplicate clash preserves newly earned counter");
            actor.aimedLeftSkill(LEFT,AttackAim.at(0,0));long fresh=actor.snapshot().swingSequence();
            check(!actor.finishClash(sequence),"old contact cannot clear a newer accepted action");
            equal(fresh,actor.snapshot().swingSequence(),"old contact preserves new sequence");near(1.5,actor.attackDamageMultiplier(),"old contact preserves accepted enhancement");
            actor.tick();equal(CombatPhase.READY,actor.phase(),"matching success permanently removed the old pending stun");
        }
    }

    private static void delayedClashRewardOwnership() {
        for(boolean charged:List.of(false,true)) {
            var actor=ready(style("rapier",false));actor.setGuarding(true);actor.requestAttack(LEFT);
            long old=actor.snapshot().swingSequence();actor.markAttackContact(old);actor.tick();
            var fresh=charged?skill("held_charge",SkillInput.RIGHT,2,2,6,20,5)
                    :new SkillDefinition("held_spear","held_spear","spear",SkillInput.LEFT,0,1,4,0,5,0,8,3,90,0,true,true,false,"","");
            if(charged)actor.beginChargedAttack(fresh,AttackAim.at(15,3),true,true,16,20);
            else {actor.requestAttack(fresh);actor.setSpearAttackHeld(true,AttackAim.at(15,3));}
            long sequence=actor.snapshot().swingSequence();var phase=actor.phase();double stamina=actor.stamina();
            actor.rewardClashAdvantage(old);
            check(actor.currentSkill()==fresh&&actor.phase()==phase&&actor.snapshot().swingSequence()==sequence,"old advantage reward cannot cancel a newer accepted action");
            check(actor.guardHeld()&&(charged?actor.chargeSnapshot().holding():actor.spearAttackHeld()),"old reward preserves actual guard and weapon holds");
            equal(0,actor.heldPerfectGuards(),"old contact reward cannot invent another held-guard success");
            near(stamina,actor.stamina(),"old reward neither refunds nor charges the new action");
            near(1,actor.attackDamageMultiplier(),"old reward cannot enhance the action accepted before it arrived");
            equal(4,actor.counterRemainingTicks(),"old contact reserves its reward for a later new LEFT input");
            actor.tick();actor.rewardClashAdvantage(old);
            equal(3,actor.counterRemainingTicks(),"duplicate advantage cannot refresh its own reward window");
            actor.rewardClashAdvantage(sequence);
            check(actor.currentSkill()==null&&!actor.chargeSnapshot().active()&&!actor.spearAttackHeld(),"matching new clash success cancels all current skill and hold state");
            equal(CombatPhase.GUARDING,actor.phase(),"matching success returns immediately to a physically held guard");
            equal(4,actor.counterRemainingTicks(),"matching new success grants a fresh counter");
        }
    }

    private static void heldProjectileReflection() {
        var actor=ready(style("rapier",false));actor.setGuarding(true);double stamina=actor.stamina();
        for(int contact=1;contact<=3;contact++) {
            check(actor.guardPerfectAgainst(null),"Arrow prepass accepts the next real held-C perfect before ordinary pressure checks");
            var reflected=CombatResolver.receiveIncoming(null,actor,0,8,0,true,true,true);
            check(reflected.outcome()==HitOutcome.PARRIED&&reflected.reflectProjectile(),"Every one of the three held perfects can reflect its actual projectile");
            equal(contact,actor.heldPerfectGuards(),"Each reflected projectile consumes exactly one held perfect");
            near(stamina-contact,actor.stamina(),"Reflection pays its normal perfect-guard fee once");
            check(actor.defenseLimitReached(),"Ordinary repeat pressure remains recorded alongside legal reflection");
        }
        check(!actor.guardPerfectAgainst(null),"A fourth projectile cannot enter the perfect reflection prepass");
        actor.setGuarding(false);actor.setGuarding(true);
        check(actor.guardPerfectAgainst(null),"A real release and new C opens reflection again");
        var reflected=CombatResolver.receiveIncoming(null,actor,0,8,0,true,true,true);
        check(reflected.reflectProjectile()&&actor.heldPerfectGuards()==1,"Fresh reflection ignores prior ordinary pressure while preserving the new cap");
    }

    /**
     * Real reports describe the attack after a perfect guard as very sluggish. The server accepts the
     * next left click immediately, and a physical C hold that was renewed by the client must never
     * interpret its renewed packet as a fresh guard intent: it yields to the accepted swing for the
     * whole windup/active/recovery lifecycle. Only an actual release can turn the same key into a
     * new intentional guard action.
     */
    private static void heldRenewalYieldsTheAcceptedSwing() {
        immediateLeftCounterSurvivesHeldRenewals();
        normalAttackAfterCounterExpirySurvivesHeldRenewals();
        rightThrustSurvivesHeldRenewals();
        namedSkillSurvivesHeldRenewals();
        dualAndSpearSurviveHeldRenewals();
        releaseStillAllowsIntentionalGuardCancel();
        heldRenewalsNeverRaiseGuardDuringTheSwing();
    }

    private static void immediateLeftCounterSurvivesHeldRenewals() {
        CombatActor actor = parried(style("rapier", false));
        check(actor.phase() == CombatPhase.GUARDING && actor.guardHeld() && actor.guardAutoRearmed(),
                "perfect success reopens the held guard without recovery");
        equal(4, actor.counterRemainingTicks(), "the immediate left counter window is still granted");
        var left = skill("left", SkillInput.LEFT, 3, 3, 3, 0, 5);
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left", left, "right", RIGHT), AttackAim.at(0, 0)).started(),
                "the next tick left attack is accepted while C is still held");
        SkillDefinition accepted = actor.currentSkill();
        equal(1, actor.drainStartedSkills().size(), "the accepted counter emits exactly one start event");
        equal(CombatPhase.WINDUP, actor.phase(), "the accepted counter enters windup");
        near(1.5, actor.attackDamageMultiplier(), "the accepted counter keeps the x1.5 reward");
        equal(ActionResult.ACCEPTED, actor.setGuarding(true), "same-tick held renewal is accepted idempotently");
        check(actor.currentSkill() == accepted && actor.phase() == CombatPhase.WINDUP,
                "same-tick held renewal cannot cancel the accepted counter");
        near(1.5, actor.attackDamageMultiplier(), "same-tick held renewal keeps the counter damage");
        while (actor.phase() != CombatPhase.READY) {
            equal(ActionResult.ACCEPTED, actor.setGuarding(true), "each held renewal inside the swing yields");
            check(actor.currentSkill() == accepted, "each held renewal keeps the accepted swing");
            actor.tick();
        }
        check(actor.currentSkill() == null, "the swing completes naturally instead of being canceled into guard");
        check(actor.drainStartedSkills().size() == 0 && !actor.comboSnapshot().queued(),
                "held renewals neither replay nor queue a stale attack");
        // The real monster cue path uses the same successful-defense transition.
        CombatActor telegraphed = ready(style("rapier", false));
        telegraphed.setGuarding(true);
        check(CombatResolver.receiveTelegraphed(null, telegraphed, 0, 8, 0, true, true, false, true).outcome() == HitOutcome.PARRIED,
                "an authorized telegraph still performs a real perfect guard");
        check(telegraphed.aimedInput(SkillInput.LEFT, Map.of("left", left, "right", RIGHT), AttackAim.at(0, 0)).started(),
                "the telegraphed perfect guard accepts the very next left attack");
        SkillDefinition telegraphSkill = telegraphed.currentSkill();
        equal(ActionResult.ACCEPTED, telegraphed.setGuarding(true), "telegraphed held renewal yields to the new swing");
        check(telegraphed.currentSkill() == telegraphSkill && telegraphed.phase() == CombatPhase.WINDUP,
                "telegraphed perfect guard plus held renewal cannot delay the counter");
    }

    private static void normalAttackAfterCounterExpirySurvivesHeldRenewals() {
        CombatActor actor = parried(style("rapier", false));
        ticks(actor, 6);
        equal(0, actor.counterRemainingTicks(), "human reaction beyond the four-tick counter boundary");
        var left = skill("left", SkillInput.LEFT, 3, 3, 3, 0, 5);
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left", left, "right", RIGHT), AttackAim.at(0, 0)).started(),
                "ordinary left attack after the counter expires is accepted");
        SkillDefinition accepted = actor.currentSkill();
        equal(1, actor.drainStartedSkills().size(), "the ordinary attack emits exactly one start event");
        near(1, actor.attackDamageMultiplier(), "an expired counter is an ordinary attack");
        while (actor.phase() != CombatPhase.READY) {
            equal(ActionResult.ACCEPTED, actor.setGuarding(true), "held renewal must not become a new guard intent");
            equal(ActionResult.ACCEPTED, actor.setGuarding(true, false), "light held renewal also yields");
            check(actor.currentSkill() == accepted, "ordinary attack keeps its identity through every renewal");
            actor.tick();
        }
        check(actor.drainStartedSkills().size() == 0 && !actor.comboSnapshot().queued(),
                "ordinary attack has no queued replay after renewals");
    }

    private static void rightThrustSurvivesHeldRenewals() {
        CombatActor actor = parried(style("rapier", false));
        ticks(actor, 6);
        var thrust = skill("right_thrust", SkillInput.RIGHT, 7, 2, 2, 20, 5);
        var charged = skill("charged", SkillInput.RIGHT, 10, 2, 4, 20, 5);
        check(actor.aimedInput(SkillInput.RIGHT, Map.of("left", LEFT, "right", thrust, "charged", charged), AttackAim.at(5, 2)).started(),
                "right thrust after a perfect guard is accepted");
        SkillDefinition accepted = actor.currentSkill();
        equal(1, actor.drainStartedSkills().size(), "right thrust emits exactly one start event");
        int guardTicks = 0;
        while (actor.phase() != CombatPhase.READY) {
            equal(ActionResult.ACCEPTED, actor.setGuarding(true), "right thrust windup/recovery renewal yields");
            check(actor.currentSkill() == accepted, "right thrust is never canceled by a held renewal");
            actor.tick();
            if (++guardTicks > 20) throw new AssertionError("right thrust failed to complete");
        }
        CombatActor holding = parried(style("rapier", false));
        ticks(holding, 6);
        equal(ActionResult.ACCEPTED, holding.beginChargedAttack(charged, AttackAim.at(0, 0), true, true, 10, 20),
                "charged thrust starts from the held guard");
        check(holding.chargeSnapshot().active() && holding.phase() == CombatPhase.WINDUP, "charged thrust winds up");
        equal(ActionResult.ACCEPTED, holding.setGuarding(true), "held renewal cannot cancel an uncommitted charge");
        check(holding.chargeSnapshot().active() && holding.currentSkill() == charged,
                "held renewal keeps the charged attack intact");
        ticks(holding, 9);
        equal(CombatPhase.WINDUP, holding.phase(), "charge stays in windup without an early commit");
        equal(ActionResult.ACCEPTED, holding.setGuarding(true, false), "renewal during charge does not clear it");
        check(holding.chargeSnapshot().active(), "renewal during charge does not disarm it");
    }

    private static void namedSkillSurvivesHeldRenewals() {
        CombatActor actor = parried(style("rapier", false));
        ticks(actor, 6);
        var named = skill("named", SkillInput.LEFT, 8, 3, 4, 0, 5);
        equal(ActionResult.ACCEPTED, actor.requestAttack(named), "named skill is accepted from the held guard");
        SkillDefinition accepted = actor.currentSkill();
        equal(1, actor.drainStartedSkills().size(), "named skill emits exactly one start event");
        while (actor.phase() != CombatPhase.READY) {
            equal(ActionResult.ACCEPTED, actor.setGuarding(true), "named skill renewal yields");
            check(actor.currentSkill() == accepted, "named skill cannot be canceled by a held renewal");
            actor.tick();
        }
        check(actor.drainStartedSkills().size() == 0 && !actor.comboSnapshot().queued(),
                "named skill leaves no stale replay after renewals");
    }

    private static void dualAndSpearSurviveHeldRenewals() {
        CombatActor dual = parried(style("dual", true));
        ticks(dual, 6);
        var catalog = catalog();
        check(dual.aimedInput(SkillInput.LEFT, catalog, AttackAim.at(0, 0)).started(), "dual left cut is accepted while C is held");
        SkillDefinition acceptedDual = dual.currentSkill();
        equal(1, dual.drainStartedSkills().size(), "dual cut emits exactly one start event");
        while (dual.phase() != CombatPhase.READY) {
            equal(ActionResult.ACCEPTED, dual.setGuarding(true), "dual held renewal yields");
            check(dual.currentSkill() == acceptedDual, "dual cut is never canceled by a held renewal");
            dual.tick();
        }
        CombatActor spear = parried(style("spear", false));
        ticks(spear, 6);
        var thrust = new SkillDefinition("spear", "spear", "spear", SkillInput.LEFT, 1, 1, 4, 0, 5, 0, 8, 3, 90, 0, true, true, true, "", "");
        equal(ActionResult.ACCEPTED, spear.aimedLeftSkill(thrust, AttackAim.at(20, 4)), "spear press after a perfect guard is accepted");
        SkillDefinition acceptedSpear = spear.currentSkill();
        equal(1, spear.drainStartedSkills().size(), "spear press emits exactly one start event");
        equal(CombatPhase.WINDUP, spear.phase(), "spear press starts its short windup");
        equal(ActionResult.ACCEPTED, spear.setGuarding(true), "held renewal cannot cancel the spear press");
        check(spear.currentSkill() == acceptedSpear && spear.phase() == CombatPhase.WINDUP,
                "spear press survives the renewal");
        spear.setSpearAttackHeld(true, AttackAim.at(20, 4));
        equal(ActionResult.ACCEPTED, spear.setGuarding(true), "held renewal cannot cancel a held spear contact");
        check(spear.spearAttackHeld() && spear.currentSkill() == acceptedSpear,
                "held spear contact stays under the player's own hold");
    }

    private static void releaseStillAllowsIntentionalGuardCancel() {
        CombatActor actor = parried(style("rapier", false));
        var left = skill("left", SkillInput.LEFT, 3, 3, 3, 0, 5);
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left", left, "right", RIGHT), AttackAim.at(0, 0)).started(),
                "counter attack starts before the intentional release");
        SkillDefinition accepted = actor.currentSkill();
        equal(ActionResult.ACCEPTED, actor.setGuarding(false), "physical release clears the yielded hold");
        equal(CombatPhase.WINDUP, actor.phase(), "release alone does not interrupt the swing");
        equal(ActionResult.ACCEPTED, actor.setGuarding(true), "a real re-press is a new intentional guard action");
        equal(CombatPhase.GUARDING, actor.phase(), "the new physical press actually raises guard");
        check(actor.currentSkill() != accepted, "a genuinely new guard press keeps its ordinary windup-cancel rule");
    }

    private static void heldRenewalsNeverRaiseGuardDuringTheSwing() {
        CombatActor actor = parried(style("rapier", false));
        var left = skill("left", SkillInput.LEFT, 6, 4, 6, 0, 5);
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left", left, "right", RIGHT), AttackAim.at(0, 0)).started(),
                "long swing accepted while C is held");
        int tick = 0;
        while (actor.phase() != CombatPhase.READY && tick < 30) {
            check(actor.phase() != CombatPhase.GUARDING, "a held renewal never re-opens guard inside the swing at tick " + tick);
            equal(ActionResult.ACCEPTED, actor.setGuarding(true), "renewal accepted without changing the action at tick " + tick);
            actor.tick();
            tick++;
        }
        equal(CombatPhase.READY, actor.phase(), "long swing completes after all renewals");
        equal(ActionResult.ACCEPTED, actor.setGuarding(true), "after the swing, the still-held C raises guard again");
        equal(CombatPhase.GUARDING, actor.phase(), "post-swing guard restoration remains available");
    }


    private static CombatActor ready(WeaponStyleDefinition style) { CombatActor actor=new CombatActor(UUID.randomUUID(),CombatRules.defaults());actor.setStyle(style);actor.requestDrawn();ticks(actor,actor.rules().drawTicks());return actor; }
    private static CombatActor parried(WeaponStyleDefinition style) { CombatActor actor=ready(style);actor.setGuarding(true);equal(HitOutcome.PARRIED,incoming(actor).outcome(),"perfect contact");return actor; }
    private static HitResult incoming(CombatActor actor) {return CombatResolver.receiveIncoming(null,actor,0,8,0,true,true,false);}
    private static WeaponStyleDefinition style(String id,boolean dual) {
        var guard=new WeaponStyleDefinition.GuardRules(120,.25,1,2,1,4,10,12,1,true,true);
        var heavy=new WeaponStyleDefinition.GuardRules(120,.2,1,0,1,4,10,12,1,true,true);
        return new WeaponStyleDefinition(id,id,"sword",true,List.of(new WeaponStyleDefinition.ComboStage("left")),dual?"":"right",guard,heavy,
                WeaponStyleDefinition.Techniques.none(),WeaponStyleDefinition.CancelRules.defaults(),dual?new WeaponControls("dual","left","left_other","right","right_other","",.18):WeaponControls.defaults());
    }
    private static Map<String,SkillDefinition> catalog() {return Map.of("left",LEFT,"right",RIGHT,"left_other",skill("left_other",SkillInput.LEFT,0,1,0,0,5),"right_other",skill("right_other",SkillInput.RIGHT,0,1,0,0,5));}
    private static SkillDefinition skill(String id,SkillInput input,int windup,int active,int recovery,int cooldown,double stamina) { return new SkillDefinition(id,id,"sword",input,windup,active,recovery,cooldown,stamina,0,8,3,90,0,true,true,false,"",""); }
    private static void ticks(CombatActor actor,int count) {for(int i=0;i<count;i++)actor.tick();}
    private static void near(double expected,double actual,String message) {check(Math.abs(expected-actual)<1e-8,message+": "+expected+" != "+actual);}
    private static void equal(Object expected,Object actual,String message) {check(expected.equals(actual),message+": "+expected+" != "+actual);}
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
}

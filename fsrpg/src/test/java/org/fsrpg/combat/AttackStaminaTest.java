package org.fsrpg.combat;

import java.util.UUID;

/** Outcome-based stamina accounting, independent recovery tiers and one paid held spear. */
public final class AttackStaminaTest {
    private static int checks;
    public static void main(String[] args) {
        emptyAttackAndRecovery(); contactsAndBoundaries(); cancellationAndBuffer();
        independentProjectiles(); spiritDelay(); heldSpear(); customRulesAndValidation();
        projectileDuringEmptySwing(); administrativeInterruptKeepsTier();
        System.out.println("AttackStaminaTest passed: " + checks + " checks");
    }

    private static void emptyAttackAndRecovery() {
        var actor = actor(5); var skill = skill("cut", "sword", 2, 2, 3, 10, 0);
        actor.setResources(30, 30); actor.requestAttack(skill);
        near(20, actor.stamina(), "full cost reserved at acceptance");
        check(!actor.staminaCombatState(), "acceptance cannot suppress recovery");
        ticks(actor, 3); near(20.75, actor.stamina(), "windup and empty contact recover at full rate");
        actor.tick(); equal(CombatPhase.RECOVERY, actor.phase(), "discount at end of full contact interval");
        near(26, actor.stamina(), "empty attack returns half while full recovery continues");
        actor.forceSheathed(); near(26, actor.stamina(), "later clears cannot refund twice");
        check(!actor.staminaCombatState(), "empty attack never enters combat");
        ticks(actor, 20); near(31, actor.stamina(), "stationary recovery is five per second");
    }

    private static void contactsAndBoundaries() {
        var actor = actor(5); var skill = skill("cut", "sword", 2, 2, 3, 10, 0);
        actor.setResources(30, 30); actor.requestAttack(skill); long sequence = actor.snapshot().swingSequence();
        check(!actor.markAttackContact(sequence), "uncommitted preparation is not contact");
        ticks(actor, 2); check(!actor.markAttackContact(sequence + 1), "wrong swing cannot consume a reservation");
        check(actor.markAttackContact(sequence), "current emitted swing can mark contact");
        check(actor.staminaCombatState(), "real contact starts combat"); near(2.5, actor.currentStaminaRegenPerSecond(), "combat recovers half of five");
        actor.tick(); near(20.625, actor.stamina(), "combat recovery happens immediately");
        actor.tick(); near(20.75, actor.stamina(), "hit retains full cost when contact window ends");
        check(!actor.markAttackContact(sequence), "settled swing cannot re-enter combat");
        ticks(actor, 57); check(actor.staminaCombatState(), "combat persists to tick before exact three-second boundary");
        double before = actor.stamina(); actor.tick();
        check(!actor.staminaCombatState(), "combat expires sixty ticks after contact");
        near(before + .25, actor.stamina(), "expiration restores full recovery on the boundary");
        actor.markCombatActivity(); ticks(actor, 30); actor.markCombatActivity(); ticks(actor, 59);
        check(actor.staminaCombatState(), "new actual activity extends the latest three-second period");
        actor.tick(); check(!actor.staminaCombatState(), "extension also ends exactly");
        actor = actor(5); actor.setResources(30, 30); actor.queueHitStun(0);
        check(actor.staminaCombatState(), "ordinary incoming contact with zero stun still starts combat");
        actor.tick(); near(30.125, actor.stamina(), "being hit also halves rather than disables recovery");
        actor = actor(5); actor.setResources(3, 30);
        check(!actor.consumeStamina(4) && !actor.staminaCombatState(), "failed defensive payment changes no recovery state");
        check(actor.consumeStamina(2) && actor.staminaCombatState(), "successful defensive spend enters combat");
    }

    private static void cancellationAndBuffer() {
        var skill = skill("cut", "sword", 2, 2, 3, 10, 0);
        for (int at : new int[]{0, 1, 2, 3, 4}) {
            var actor = actor(0); actor.requestAttack(skill); ticks(actor, at);
            actor.requestPrecisionGuard(false);
            near(at < 2 ? 90 : 95, actor.stamina(), "only emitted empty attacks receive a discount, cancel at " + at);
            actor.forceSheathed(); near(at < 2 ? 90 : 95, actor.stamina(), "forced clear is idempotent at " + at);
        }
        var actor = actor(0); actor.requestAttack(skill); ticks(actor, 2);
        actor.markAttackContact(actor.snapshot().swingSequence()); actor.requestPrecisionGuard(false);
        near(90, actor.stamina(), "a real contact prevents interruption from refunding its cost");
        actor = actor(0); actor.setResources(15, 30); actor.requestAttack(skill); ticks(actor, 2);
        check(actor.requestAttack(skill) == ActionResult.ACCEPTED, "active input can buffer an otherwise unaffordable cut");
        near(5, actor.stamina(), "buffer itself costs nothing");
        ticks(actor, 2); equal(2L, actor.snapshot().swingSequence(), "previous empty discount is available before queued payment");
        near(0, actor.stamina(), "two paid starts with one settled miss charge fifteen so far");
        ticks(actor, skill.durationTicks()); near(5, actor.stamina(), "both emitted misses finally cost ten together");
        actor.forceSheathed(); near(5, actor.stamina(), "queue settlement also cannot refund twice");
    }

    private static void independentProjectiles() {
        var actor = actor(5); actor.setResources(30, 30);
        long miss = actor.reserveAttackStamina(5), hit = actor.reserveAttackStamina(15);
        check(miss > 0 && hit > miss, "distinct reservations survive simultaneously");
        near(10, actor.stamina(), "both shots reserve their full individual prices");
        check(!actor.staminaCombatState(), "a projectile in flight is not automatically a hit");
        actor.tick(); near(10.25, actor.stamina(), "flight leaves full regeneration running");
        check(actor.markReservedAttackContact(hit), "projectile contact is tied to its own token");
        near(2.5, actor.settleReservedAttackStamina(miss), "only empty projectile returns half");
        near(0, actor.settleReservedAttackStamina(hit), "contact projectile retains all fifteen");
        near(12.75, actor.stamina(), "settlement keeps resources from both independent outcomes");
        near(0, actor.settleReservedAttackStamina(miss), "repeated removal cannot refund");
        check(!actor.markReservedAttackContact(miss), "removed projectile cannot refresh combat");
        check(actor.reserveAttackStamina(100) == 0, "unaffordable projectile has no token or partial debit");
        actor = actor(0); actor.setResources(30, 30); long token = actor.reserveAttackStamina(3.5);
        near(1.75, actor.settleReservedAttackStamina(token), "partial draw cost also halves without rounding");
        near(28.25, actor.stamina(), "partial-draw miss pays exactly 1.75");
        token = actor.reserveAttackStamina(5); actor.setResources(100, 30);
        actor.settleReservedAttackStamina(token); near(100, actor.stamina(), "refund cannot exceed current level maximum");
        token = actor.reserveAttackStamina(5);
        actor.setRules(rules(0, new StaminaCombatRules(60, .5, .9)));
        near(2.5, actor.settleReservedAttackStamina(token), "in-flight final cost uses the accepted rules");
    }

    private static void spiritDelay() {
        var actor = actor(5); actor.setResources(30, 30);
        actor.consumeStamina(5); actor.consumeSpirit(5); ticks(actor, 19);
        near(27.375, actor.stamina(), "stamina recovers throughout old delay period");
        near(25, actor.spirit(), "spirit still waits for its own configured delay");
        actor.tick(); near(25.05, actor.spirit(), "spirit resumes at its historical boundary");
    }

    private static void heldSpear() {
        var actor = actor(0); var spear = skill("spear_thrust", "spear", 1, 1, 2, 10, 0);
        actor.requestAttack(spear); long sequence = actor.snapshot().swingSequence();
        check(actor.setSpearAttackHeld(true, AttackAim.at(20, 5)), "validated press can hold through preparation");
        actor.tick(); equal(CombatPhase.ACTIVE, actor.phase(), "spear enters authored contact after windup");
        UUID victim = UUID.randomUUID(); check(actor.claimVictim(victim), "first swept victim claimed");
        for (int i = 0; i < 80; i++) {
            actor.setSpearAttackHeld(true, AttackAim.at(i / 2.0, 5)); actor.tick();
            equal(CombatPhase.ACTIVE, actor.phase(), "held spear stays extended");
            equal(sequence, actor.snapshot().swingSequence(), "hold never invents another paid swing");
        }
        near(90, actor.stamina(), "holding pays one thrust cost");
        check(!actor.claimVictim(victim), "holding cannot repeatedly damage an old victim");
        near(39.5, actor.attackAim().sample(1).yaw(), "holding follows validated aim");
        actor.setSpearAttackHeld(false, null); actor.tick(); equal(CombatPhase.RECOVERY, actor.phase(), "release retracts normally");
        near(95, actor.stamina(), "an entirely empty held attack receives only one half refund");
        ticks(actor, 2); equal(CombatPhase.READY, actor.phase(), "spear ends after authored recovery");
        check(!actor.spearAttackHeld(), "release leaves no held state");
        actor.requestAttack(spear); actor.setSpearAttackHeld(true, null); actor.tick(); actor.stun(2);
        check(!actor.spearAttackHeld(), "parry or other authoritative interruption clears hold");
        actor = actor(0); actor.requestAttack(skill("cut", "sword", 0, 2, 1, 5, 0));
        check(!actor.setSpearAttackHeld(true, null), "sword cannot borrow spear contact extension");
    }

    private static void customRulesAndValidation() {
        var actor = new CombatActor(UUID.randomUUID(), rules(10, new StaminaCombatRules(4, .25, .2)));
        actor.requestDrawn(); actor.setResources(50, 30); long shot = actor.reserveAttackStamina(10);
        near(8, actor.settleReservedAttackStamina(shot), "custom final miss multiplier determines exact debit");
        actor.markCombatActivity(); actor.tick(); near(48.125, actor.stamina(), "custom combat recovery multiplier applies");
        ticks(actor, 3); check(!actor.staminaCombatState(), "custom combat duration boundary");
        for (double invalid : new double[]{-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            rejects(() -> new StaminaCombatRules(60, invalid, .5), "invalid regen multiplier");
            rejects(() -> new StaminaCombatRules(60, .5, invalid), "invalid miss cost multiplier");
        }
        rejects(() -> new StaminaCombatRules(-1, .5, .5), "negative combat duration");
        rejects(() -> actor.reserveAttackStamina(Double.NaN), "invalid reservation amount");
    }

    private static void projectileDuringEmptySwing() {
        var shooter = actor(0); var target = actor(0); shooter.setResources(30, 30);
        long arrow = shooter.reserveAttackStamina(5);
        shooter.requestAttack(skill("cut", "sword", 0, 2, 1, 10, 0));
        long swing = shooter.snapshot().swingSequence();
        var impact = CombatResolver.receiveIncoming(shooter,target,0,6,0,true,true,true);
        equal(HitOutcome.HIT,impact.outcome(),"earlier released projectile can hit during another melee swing");
        shooter.markReservedAttackContact(arrow);
        near(0,shooter.settleReservedAttackStamina(arrow),"arrow keeps its own full cost");
        equal(swing,shooter.snapshot().swingSequence(),"projectile impact does not substitute a new melee swing");
        ticks(shooter,3);
        near(20,shooter.stamina(),"unrelated empty melee still receives its half refund after the arrow hit");
        check(shooter.staminaCombatState(),"real arrow contact independently enters combat");
    }

    private static void administrativeInterruptKeepsTier() {
        var actor = actor(5); actor.setResources(30,30);
        actor.requestAttack(skill("cut", "sword", 0, 2, 1, 10, 0));
        actor.stun(1);
        near(25,actor.stamina(),"administrative interruption settles an emitted miss once");
        check(!actor.staminaCombatState(),"equipment/reload interruption alone is not real combat contact");
        actor.tick(); near(25.25,actor.stamina(),"full recovery continues through an administrative interruption");
        actor.markCombatActivity(); ticks(actor,30); actor.stun(1); ticks(actor,30);
        check(!actor.staminaCombatState(),"administrative interruption cannot extend an earlier real combat period");
    }

    private static CombatActor actor(double regen) { var actor = new CombatActor(UUID.randomUUID(), rules(regen, StaminaCombatRules.defaults())); actor.requestDrawn(); return actor; }
    private static CombatRules rules(double regen, StaminaCombatRules combat) {
        var d = CombatRules.defaults();
        return new CombatRules(100,100,regen,1,20,0,0,d.guardArcDegrees(),d.guardDamageMultiplier(),d.guardStaminaCost(),
                d.perfectGuardTicks(),d.perfectGuardStaminaCost(),d.parryStunTicks(),d.guardBreakStunTicks(),d.hitStunTicks(),
                d.clashFacingArcDegrees(),d.dodgeDurationTicks(),d.dodgeInvulnerabilityStartTicks(),d.dodgeInvulnerabilityTicks(),
                d.dodgeCooldownTicks(),d.dodgeStaminaCost(),d.dodgeDistance(),true,false,d.rapierTargetLock(),d.perfectGuardCounter(),d.staminaLevelScaling(),combat);
    }
    private static SkillDefinition skill(String id, String weapon, int windup, int active, int recovery, double stamina, double spirit) {
        return new SkillDefinition(id,id,weapon,SkillInput.LEFT,windup,active,recovery,0,stamina,spirit,6,3,90,.3,true,true,true,"","");
    }
    private static void ticks(CombatActor actor, int count) { for (int i = 0; i < count; i++) actor.tick(); }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected-actual)<1e-8, message+": "+expected+" != "+actual); }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message+": "+expected+" != "+actual); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) { checks++; try { action.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) {} }
}

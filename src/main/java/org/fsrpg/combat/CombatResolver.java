package org.fsrpg.combat;

/** Deterministic server geometry and defense resolution. Angles are relative to each actor's forward direction. */
public final class CombatResolver {
    private CombatResolver() {}

    public static HitResult resolveHit(CombatActor attacker, CombatActor defender, double distance,
                                       double attackerFacingAngleDegrees, double defenderFacingAngleDegrees) {
        if (attacker == null || defender == null) throw new IllegalArgumentException("both actors are required");
        SkillDefinition.checkNumber(distance, "distance", 0, Double.MAX_VALUE);
        requireAngle(attackerFacingAngleDegrees);
        requireAngle(defenderFacingAngleDegrees);
        SkillDefinition skill = attacker.activeSkill();
        if (skill == null || attacker.id().equals(defender.id())) return empty(HitOutcome.INACTIVE, "");
        if (distance > skill.reach()) return empty(HitOutcome.OUT_OF_RANGE, skill.id());
        if (!withinArc(attackerFacingAngleDegrees, skill.arcDegrees())) return empty(HitOutcome.OUTSIDE_ARC, skill.id());
        if (attacker.hasHit(defender.id())) return empty(HitOutcome.ALREADY_HIT, skill.id());
        if (canClash(attacker, defender, distance, attackerFacingAngleDegrees, defenderFacingAngleDegrees)) {
            // Preserve the original cooldown timestamps. A clash adds no stamina cost or stun.
            attacker.markHit(defender.id());
            defender.markHit(attacker.id());
            attacker.finishClash();
            defender.finishClash();
            return empty(HitOutcome.CLASH, skill.id());
        }
        attacker.markHit(defender.id());
        return receiveIncoming(attacker, defender, defenderFacingAngleDegrees, skill.damage() * attacker.attackDamageMultiplier(), skill.knockback(),
                skill.blockable(), skill.parryable(), false, skill.id(),null);
    }

    /** Side-effect-free prepass predicate; a failed clash check must not resolve an ordinary hit early. */
    public static boolean canClash(CombatActor attacker, CombatActor defender, double distance,
                                   double attackerFacingAngleDegrees, double defenderFacingAngleDegrees) {
        if (attacker == null || defender == null) throw new IllegalArgumentException("both actors are required");
        SkillDefinition.checkNumber(distance, "distance", 0, Double.MAX_VALUE);
        requireAngle(attackerFacingAngleDegrees);
        requireAngle(defenderFacingAngleDegrees);
        SkillDefinition skill = attacker.activeSkill(), counter = defender.activeSkill();
        return skill != null && counter != null && !attacker.id().equals(defender.id())
                && !attacker.hasHit(defender.id()) && !defender.hasHit(attacker.id())
                && skill.clashable() && counter.clashable()
                && skill.input() != SkillInput.SPECIAL && skill.input() == counter.input()
                && distance <= skill.reach() && distance <= counter.reach()
                && withinArc(attackerFacingAngleDegrees, skill.arcDegrees())
                && withinArc(defenderFacingAngleDegrees, counter.arcDegrees())
                && withinArc(attackerFacingAngleDegrees, attacker.rules().clashFacingArcDegrees())
                && withinArc(defenderFacingAngleDegrees, defender.rules().clashFacingArcDegrees());
    }

    /** For vanilla melee/projectiles; attacker may be null when it has no RPG combat state. */
    public static HitResult receiveIncoming(CombatActor attacker, CombatActor defender, double defenderFacingAngleDegrees,
                                           double damage, double knockback, boolean blockable, boolean parryable,
                                           boolean projectile) {
        return receiveIncoming(attacker, defender, defenderFacingAngleDegrees, damage, knockback,
                blockable, parryable, projectile, "",null);
    }

    /** A telegraphed enemy owns its input window; generic recent guarding cannot borrow another attack's cue. */
    public static HitResult receiveTelegraphed(CombatActor attacker,CombatActor defender,double facing,double damage,
            double knockback,boolean blockable,boolean parryable,boolean projectile,boolean perfectGuardAuthorized){
        return receiveIncoming(attacker,defender,facing,damage,knockback,blockable,parryable,projectile,"",perfectGuardAuthorized);
    }

    private static HitResult receiveIncoming(CombatActor attacker, CombatActor defender, double defenderFacingAngleDegrees,
                                            double damage, double knockback, boolean blockable, boolean parryable,
                                            boolean projectile, String skillId,Boolean perfectGuardAuthorized) {
        if (defender == null) throw new IllegalArgumentException("defender is required");
        requireAngle(defenderFacingAngleDegrees);
        SkillDefinition.checkNumber(damage, "incoming damage", 0, 100_000_000);
        SkillDefinition.checkNumber(knockback, "incoming knockback", 0, 128);
        if (defender.invulnerable()) return empty(HitOutcome.DODGED, skillId);
        CombatRules rules = defender.rules();
        var guard = defender.guardRules();
        if (defender.phase() == CombatPhase.GUARDING && withinArc(defenderFacingAngleDegrees, guard.arcDegrees())) {
            // Repeated pressure and heavy strikes are checked before the perfect-guard window.
            // E release/repress does not clear contact history, so it cannot bypass either rule.
            if (defender.defenseLimitReached() || (guard.heavyBreaksGuard() && !blockable)) {
                defender.breakGuard(guard.guardBreakStunTicks());
                return new HitResult(HitOutcome.GUARD_BROKEN, damage, knockback, false, guard.guardBreakStunTicks(), skillId);
            }
            boolean precise=perfectGuardAuthorized==null?defender.inPerfectGuardWindow():perfectGuardAuthorized&&guard.perfectGuardTicks()>0;
            if (parryable && (!guard.perfectGuardRequiresBlockable() || blockable) && precise) {
                if (defender.consumeStamina(guard.perfectGuardStaminaCost())) {
                    defender.recordGuardContact();
                    defender.perfectGuardSucceeded();
                    if (attacker != null) attacker.stun(guard.parryStunTicks());
                    return new HitResult(HitOutcome.PARRIED, 0, 0, projectile, 0, skillId);
                }
                // If perfect guard is too expensive, a cheaper ordinary block may still succeed.
            }
            if (blockable) {
                if (defender.consumeStamina(guard.staminaCost())) {
                    defender.recordGuardContact();
                    return new HitResult(HitOutcome.BLOCKED, damage * guard.damageMultiplier(), 0, false, 0, skillId);
                }
                if (defender.style() == null) defender.queueHitStun(guard.guardBreakStunTicks());
                else defender.breakGuard(guard.guardBreakStunTicks());
                return new HitResult(HitOutcome.GUARD_BROKEN, damage, knockback, false, guard.guardBreakStunTicks(), skillId);
            }
        }
        defender.queueHitStun(rules.hitStunTicks());
        return new HitResult(HitOutcome.HIT, damage, knockback, false, rules.hitStunTicks(), skillId);
    }

    public static boolean withinArc(double angleDegrees, double fullArcDegrees) {
        requireAngle(angleDegrees);
        SkillDefinition.checkNumber(fullArcDegrees, "arcDegrees", 0, 360);
        double normalized = ((angleDegrees % 360) + 540) % 360 - 180;
        return Math.abs(normalized) <= fullArcDegrees / 2.0;
    }

    private static void requireAngle(double angle) {
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("facing angle must be finite");
    }

    private static HitResult empty(HitOutcome outcome, String skillId) {
        return new HitResult(outcome, 0, 0, false, 0, skillId);
    }
}

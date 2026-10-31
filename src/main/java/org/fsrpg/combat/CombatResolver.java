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
            var exchange = WeaponClash.compare(skill.damage() * attacker.attackDamageMultiplier(),
                    defender.activeSkill().damage() * defender.attackDamageMultiplier(), attacker.rules().weaponClash().damageTolerance());
            attacker.markHit(defender.id());
            defender.markHit(attacker.id());
            attacker.markAttackContact(attacker.snapshot().swingSequence());
            defender.markAttackContact(defender.snapshot().swingSequence());
            attacker.finishClash();
            defender.finishClash();
            attacker.recordGuardContact(); defender.recordGuardContact();
            if (exchange.winner() > 0) attacker.perfectGuardSucceeded();
            if (exchange.winner() < 0) defender.perfectGuardSucceeded();
            return new HitResult(HitOutcome.CLASH, exchange.firstDamage(), 0, false, 0, skill.id());
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
        return !attacker.swordHeavyAttack() && !defender.swordHeavyAttack() && !attacker.swordSuperArmor() && !defender.swordSuperArmor() && skill != null && counter != null && !attacker.id().equals(defender.id())
                && !attacker.hasHit(defender.id()) && !defender.hasHit(attacker.id())
                && distance <= skill.reach() && distance <= counter.reach()
                && withinArc(attackerFacingAngleDegrees, skill.arcDegrees())
                && withinArc(defenderFacingAngleDegrees, counter.arcDegrees());
    }

    /** Capture a real contact without stunning away a return hit in the configured contact window.
     * Guard and dodge are evaluated now, so later input cannot defend a contact retroactively. */
    public static HitResult captureMelee(CombatActor attacker, CombatActor defender, double distance,
                                         double attackerFacing, double defenderFacing) {
        if(attacker==null||defender==null)throw new IllegalArgumentException("both actors are required");
        SkillDefinition.checkNumber(distance,"distance",0,Double.MAX_VALUE);
        requireAngle(attackerFacing);requireAngle(defenderFacing);
        SkillDefinition skill = attacker.activeSkill();
        if (skill == null || attacker.id().equals(defender.id())) return empty(HitOutcome.INACTIVE, "");
        if (distance > skill.reach()) return empty(HitOutcome.OUT_OF_RANGE, skill.id());
        if (!withinArc(attackerFacing, skill.arcDegrees())) return empty(HitOutcome.OUTSIDE_ARC, skill.id());
        if (attacker.hasHit(defender.id())) return empty(HitOutcome.ALREADY_HIT, skill.id());
        attacker.markHit(defender.id());
        if(defender.invulnerable())return empty(HitOutcome.DODGED,skill.id());
        attacker.markAttackContact(attacker.snapshot().swingSequence());
        if (defender.swordCombatEnabled() || defender.phase()==CombatPhase.GUARDING && withinArc(defenderFacing,defender.guardRules().arcDegrees()))
            return receiveIncoming(attacker,defender,defenderFacing,skill.damage()*attacker.attackDamageMultiplier(),
                    skill.knockback(),skill.blockable(),skill.parryable(),false,skill.id(),null);
        defender.markCombatActivity();
        return new HitResult(HitOutcome.HIT,skill.damage()*attacker.attackDamageMultiplier(),skill.knockback(),false,
                defender.rules().hitStunTicks(),skill.id());
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
        if (defender.swordCombatEnabled()) return receiveSwordIncoming(attacker,defender,defenderFacingAngleDegrees,damage,knockback,blockable,projectile,attacker!=null&&attacker.swordHeavyAttack(),skillId);
        if (defender.invulnerable()) return empty(HitOutcome.DODGED, skillId);
        if (attacker != null && !projectile) attacker.markAttackContact(attacker.snapshot().swingSequence());
        defender.markCombatActivity();
        CombatRules rules = defender.rules();
        var guard = defender.guardRules();
        if (defender.phase() == CombatPhase.GUARDING && withinArc(defenderFacingAngleDegrees, guard.arcDegrees())) {
            // An unblockable heavy strike still breaks guard, including a fresh perfect window.
            if (guard.heavyBreaksGuard() && !blockable) {
                defender.breakGuard(guard.guardBreakStunTicks());
                return new HitResult(HitOutcome.GUARD_BROKEN, damage, knockback, false, guard.guardBreakStunTicks(), skillId);
            }
            boolean precise=defender.guardPerfectAgainst(perfectGuardAuthorized);
            if (parryable && (!guard.perfectGuardRequiresBlockable() || blockable) && precise) {
                if (defender.consumeStamina(guard.perfectGuardStaminaCost())) {
                    defender.recordGuardContact();
                    defender.perfectGuardSucceeded();
                    if (attacker != null) attacker.stun(guard.parryStunTicks());
                    return new HitResult(HitOutcome.PARRIED, 0, 0, projectile, 0, skillId);
                }
                // If perfect guard is too expensive, a cheaper ordinary block may still succeed.
            }
            // A genuine perfect can answer repeated pressure; ordinary blocks retain their limit.
            if (defender.defenseLimitReached()) {
                defender.breakGuard(guard.guardBreakStunTicks());
                return new HitResult(HitOutcome.GUARD_BROKEN, damage, knockback, false, guard.guardBreakStunTicks(), skillId);
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

    /** Explicit attack class: ordinary hits punish X, charged/heavy hits beat C, active X answers heavy melee. */
    public static HitResult receiveSwordIncoming(CombatActor attacker,CombatActor defender,double facing,
            double damage,double knockback,boolean blockable,boolean projectile,boolean heavy,String skillId) {
        requireAngle(facing);SkillDefinition.checkNumber(damage,"sword incoming damage",0,100_000_000);
        SkillDefinition.checkNumber(knockback,"sword incoming knockback",0,128);
        // A released projectile cannot borrow its owner's later melee charge classification.
        heavy=heavy&&!projectile;
        if(defender.invulnerable())return empty(HitOutcome.DODGED,skillId);
        if(attacker!=null&&!projectile)attacker.markAttackContact(attacker.snapshot().swingSequence());
        defender.markCombatActivity();var guard=defender.guardRules();
        if(heavy&&!projectile&&defender.swordParryWindow()&&withinArc(facing,guard.arcDegrees())) {
            defender.swordParrySucceeded();if(attacker!=null)attacker.stun(defender.rules().swordCombat().parryStunTicks());
            return new HitResult(HitOutcome.PARRIED,0,0,false,0,skillId);
        }
        if(defender.phase()==CombatPhase.GUARDING&&withinArc(facing,guard.arcDegrees())) {
            if(heavy||!blockable||defender.defenseLimitReached()||!defender.consumeStamina(guard.staminaCost())) {
                defender.breakGuard(guard.guardBreakStunTicks());
                return new HitResult(HitOutcome.GUARD_BROKEN,damage,knockback,false,guard.guardBreakStunTicks(),skillId);
            }
            defender.recordGuardContact();return new HitResult(HitOutcome.BLOCKED,damage*guard.damageMultiplier(),0,false,0,skillId);
        }
        int stun=defender.swordSuperArmor()?0:defender.rules().hitStunTicks();
        defender.queueHitStun(stun);return new HitResult(HitOutcome.HIT,damage,knockback,false,stun,skillId);
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

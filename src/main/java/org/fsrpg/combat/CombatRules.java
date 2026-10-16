package org.fsrpg.combat;

/** Shared combat tunables, directly deserializable with Gson's record adapter. */
public record CombatRules(
        double maxStamina, double maxSpirit,
        double staminaRegenPerSecond, double spiritRegenPerSecond, int resourceRegenDelayTicks,
        int drawTicks, int sheathTicks,
        double guardArcDegrees, double guardDamageMultiplier, double guardStaminaCost,
        int perfectGuardTicks, double perfectGuardStaminaCost, int parryStunTicks, int guardBreakStunTicks,
        int hitStunTicks, double clashFacingArcDegrees,
        int dodgeDurationTicks, int dodgeInvulnerabilityStartTicks, int dodgeInvulnerabilityTicks,
        int dodgeCooldownTicks, double dodgeStaminaCost, double dodgeDistance,
        boolean dodgeCancelsRecovery, boolean allowAirDodge, RapierTargeting.Rules rapierTargetLock,
        PerfectGuardCounter perfectGuardCounter, StaminaLevelScaling staminaLevelScaling) {

    /** Keep the 0.7.3 constructor source-compatible. */
    public CombatRules(double maxStamina, double maxSpirit, double staminaRegenPerSecond,
                       double spiritRegenPerSecond, int resourceRegenDelayTicks, int drawTicks, int sheathTicks,
                       double guardArcDegrees, double guardDamageMultiplier, double guardStaminaCost,
                       int perfectGuardTicks, double perfectGuardStaminaCost, int parryStunTicks, int guardBreakStunTicks,
                       int hitStunTicks, double clashFacingArcDegrees, int dodgeDurationTicks,
                       int dodgeInvulnerabilityStartTicks, int dodgeInvulnerabilityTicks, int dodgeCooldownTicks,
                       double dodgeStaminaCost, double dodgeDistance, boolean dodgeCancelsRecovery, boolean allowAirDodge,
                       RapierTargeting.Rules rapierTargetLock, PerfectGuardCounter perfectGuardCounter) {
        this(maxStamina, maxSpirit, staminaRegenPerSecond, spiritRegenPerSecond, resourceRegenDelayTicks,
                drawTicks, sheathTicks, guardArcDegrees, guardDamageMultiplier, guardStaminaCost,
                perfectGuardTicks, perfectGuardStaminaCost, parryStunTicks, guardBreakStunTicks, hitStunTicks,
                clashFacingArcDegrees, dodgeDurationTicks, dodgeInvulnerabilityStartTicks, dodgeInvulnerabilityTicks,
                dodgeCooldownTicks, dodgeStaminaCost, dodgeDistance, dodgeCancelsRecovery, allowAirDodge,
                rapierTargetLock, perfectGuardCounter, StaminaLevelScaling.defaults());
    }

    /** A left-click opportunity is separate from a style's existing right-click riposte. */
    public record PerfectGuardCounter(int windowTicks, double damageMultiplier) {
        public PerfectGuardCounter {
            SkillDefinition.checkTicks(windowTicks, "perfect guard counter windowTicks", true);
            SkillDefinition.checkNumber(damageMultiplier, "perfect guard counter damageMultiplier", 1, 100);
        }
        public static PerfectGuardCounter defaults() { return new PerfectGuardCounter(4, 1.5); }
    }

    /** Keep the 0.7.1 constructor source-compatible. */
    public CombatRules(double maxStamina, double maxSpirit, double staminaRegenPerSecond,
                       double spiritRegenPerSecond, int resourceRegenDelayTicks, int drawTicks, int sheathTicks,
                       double guardArcDegrees, double guardDamageMultiplier, double guardStaminaCost,
                       int perfectGuardTicks, double perfectGuardStaminaCost, int parryStunTicks, int guardBreakStunTicks,
                       int hitStunTicks, double clashFacingArcDegrees, int dodgeDurationTicks,
                       int dodgeInvulnerabilityStartTicks, int dodgeInvulnerabilityTicks, int dodgeCooldownTicks,
                       double dodgeStaminaCost, double dodgeDistance, boolean dodgeCancelsRecovery, boolean allowAirDodge,
                       RapierTargeting.Rules rapierTargetLock) {
        this(maxStamina, maxSpirit, staminaRegenPerSecond, spiritRegenPerSecond, resourceRegenDelayTicks,
                drawTicks, sheathTicks, guardArcDegrees, guardDamageMultiplier, guardStaminaCost,
                perfectGuardTicks, perfectGuardStaminaCost, parryStunTicks, guardBreakStunTicks, hitStunTicks,
                clashFacingArcDegrees, dodgeDurationTicks, dodgeInvulnerabilityStartTicks, dodgeInvulnerabilityTicks,
                dodgeCooldownTicks, dodgeStaminaCost, dodgeDistance, dodgeCancelsRecovery, allowAirDodge,
                rapierTargetLock, PerfectGuardCounter.defaults());
    }

    /** Retains the pre-lock constructor for existing integrations and saved test fixtures. */
    public CombatRules(double maxStamina, double maxSpirit, double staminaRegenPerSecond,
                       double spiritRegenPerSecond, int resourceRegenDelayTicks, int drawTicks, int sheathTicks,
                       double guardArcDegrees, double guardDamageMultiplier, double guardStaminaCost,
                       int perfectGuardTicks, double perfectGuardStaminaCost, int parryStunTicks, int guardBreakStunTicks,
                       int hitStunTicks, double clashFacingArcDegrees, int dodgeDurationTicks,
                       int dodgeInvulnerabilityStartTicks, int dodgeInvulnerabilityTicks, int dodgeCooldownTicks,
                       double dodgeStaminaCost, double dodgeDistance, boolean dodgeCancelsRecovery, boolean allowAirDodge) {
        this(maxStamina, maxSpirit, staminaRegenPerSecond, spiritRegenPerSecond, resourceRegenDelayTicks,
                drawTicks, sheathTicks, guardArcDegrees, guardDamageMultiplier, guardStaminaCost,
                perfectGuardTicks, perfectGuardStaminaCost, parryStunTicks, guardBreakStunTicks, hitStunTicks,
                clashFacingArcDegrees, dodgeDurationTicks, dodgeInvulnerabilityStartTicks, dodgeInvulnerabilityTicks,
                dodgeCooldownTicks, dodgeStaminaCost, dodgeDistance, dodgeCancelsRecovery, allowAirDodge,
                RapierTargeting.Rules.defaults());
    }

    public CombatRules {
        if (rapierTargetLock == null) rapierTargetLock = RapierTargeting.Rules.defaults();
        if (perfectGuardCounter == null) perfectGuardCounter = PerfectGuardCounter.defaults();
        if (staminaLevelScaling == null) staminaLevelScaling = StaminaLevelScaling.defaults();
        SkillDefinition.checkNumber(maxStamina, "maxStamina", 1, 1_000_000);
        SkillDefinition.checkNumber(maxSpirit, "maxSpirit", 1, 1_000_000);
        SkillDefinition.checkNumber(staminaRegenPerSecond, "staminaRegenPerSecond", 0, 1_000_000);
        SkillDefinition.checkNumber(spiritRegenPerSecond, "spiritRegenPerSecond", 0, 1_000_000);
        SkillDefinition.checkTicks(resourceRegenDelayTicks, "resourceRegenDelayTicks", true);
        SkillDefinition.checkTicks(drawTicks, "drawTicks", true);
        SkillDefinition.checkTicks(sheathTicks, "sheathTicks", true);
        SkillDefinition.checkNumber(guardArcDegrees, "guardArcDegrees", 0, 360);
        SkillDefinition.checkNumber(guardDamageMultiplier, "guardDamageMultiplier", 0, 1);
        SkillDefinition.checkNumber(guardStaminaCost, "guardStaminaCost", 0, 1_000_000);
        SkillDefinition.checkTicks(perfectGuardTicks, "perfectGuardTicks", true);
        SkillDefinition.checkNumber(perfectGuardStaminaCost, "perfectGuardStaminaCost", 0, 1_000_000);
        SkillDefinition.checkTicks(parryStunTicks, "parryStunTicks", true);
        SkillDefinition.checkTicks(guardBreakStunTicks, "guardBreakStunTicks", true);
        SkillDefinition.checkTicks(hitStunTicks, "hitStunTicks", true);
        SkillDefinition.checkNumber(clashFacingArcDegrees, "clashFacingArcDegrees", 0, 360);
        SkillDefinition.checkTicks(dodgeDurationTicks, "dodgeDurationTicks", false);
        SkillDefinition.checkTicks(dodgeInvulnerabilityStartTicks, "dodgeInvulnerabilityStartTicks", true);
        SkillDefinition.checkTicks(dodgeInvulnerabilityTicks, "dodgeInvulnerabilityTicks", true);
        if ((long) dodgeInvulnerabilityStartTicks + dodgeInvulnerabilityTicks > dodgeDurationTicks)
            throw new IllegalArgumentException("dodge invulnerability must fit inside dodgeDurationTicks");
        SkillDefinition.checkTicks(dodgeCooldownTicks, "dodgeCooldownTicks", true);
        SkillDefinition.checkNumber(dodgeStaminaCost, "dodgeStaminaCost", 0, 1_000_000);
        SkillDefinition.checkNumber(dodgeDistance, "dodgeDistance", 0, 32);
    }

    public static CombatRules defaults() {
        return new CombatRules(50, 100, 5, 1, 20, 8, 8,
                120, 0.25, 10, 2, 10, 4, 10, 2, 120,
                12, 0, 6, 20, 6, 3, true, false);
    }

    public double maxStaminaAtLevel(int level) { return staminaLevelScaling.maximum(maxStamina, level); }
    public double staminaRegenAtLevel(int level) { return staminaLevelScaling.regeneration(staminaRegenPerSecond, level); }
}

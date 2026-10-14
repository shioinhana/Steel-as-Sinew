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
        boolean dodgeCancelsRecovery, boolean allowAirDodge) {

    public CombatRules {
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
        return new CombatRules(100, 100, 1, 1, 20, 8, 8,
                120, 0.25, 10, 4, 10, 8, 10, 2, 120,
                12, 0, 6, 20, 6, 3, true, false);
    }
}

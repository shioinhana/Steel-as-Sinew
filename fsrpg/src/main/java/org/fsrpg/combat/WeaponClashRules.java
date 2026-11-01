package org.fsrpg.combat;

/** Server-owned contact timing and damage tolerance for opposing melee attacks. */
public record WeaponClashRules(int contactWindowTicks, double damageTolerance) {
    public WeaponClashRules {
        if (contactWindowTicks < 0 || contactWindowTicks > 10)
            throw new IllegalArgumentException("weapon clash contactWindowTicks must be between 0 and 10");
        SkillDefinition.checkNumber(damageTolerance, "weapon clash damageTolerance", 0, 1_000_000);
    }
    public static WeaponClashRules defaults() { return new WeaponClashRules(3, 3); }
}

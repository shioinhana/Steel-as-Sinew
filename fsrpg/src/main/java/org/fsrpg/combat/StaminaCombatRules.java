package org.fsrpg.combat;

/** Stamina-only combat recovery and the final cost of an emitted attack that contacts nothing. */
public record StaminaCombatRules(int combatTicks, double regenMultiplier, double missCostMultiplier) {
    public StaminaCombatRules {
        SkillDefinition.checkTicks(combatTicks, "stamina combatTicks", true);
        SkillDefinition.checkNumber(regenMultiplier, "stamina combat regenMultiplier", 0, 1);
        SkillDefinition.checkNumber(missCostMultiplier, "stamina missCostMultiplier", 0, 1);
    }
    public static StaminaCombatRules defaults() { return new StaminaCombatRules(60, .5, .5); }
}

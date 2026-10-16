package org.fsrpg.combat;

/** Level coefficients; the level is supplied by the authoritative adapter, independent of Minecraft. */
public record StaminaLevelScaling(double regenSqrtCoefficient, double regenLogCoefficient,
                                  double maxPowerCoefficient, double maxPowerExponent, double maxLogCoefficient) {
    public StaminaLevelScaling {
        SkillDefinition.checkNumber(regenSqrtCoefficient, "stamina level regenSqrtCoefficient", 0, 1_000_000);
        SkillDefinition.checkNumber(regenLogCoefficient, "stamina level regenLogCoefficient", 0, 1_000_000);
        SkillDefinition.checkNumber(maxPowerCoefficient, "stamina level maxPowerCoefficient", 0, 1_000_000);
        SkillDefinition.checkNumber(maxPowerExponent, "stamina level maxPowerExponent", 0, 4);
        SkillDefinition.checkNumber(maxLogCoefficient, "stamina level maxLogCoefficient", 0, 1_000_000);
    }

    public static StaminaLevelScaling defaults() { return new StaminaLevelScaling(1, 1, 1, .8, 3); }

    public double maximum(double base, int level) {
        checkLevel(level);
        return level == 0 ? base : base + maxPowerCoefficient * Math.pow(level, maxPowerExponent) + maxLogCoefficient * Math.log(level);
    }

    public double regeneration(double base, int level) {
        checkLevel(level);
        // Level zero has no logarithm. Only the complete positive-level regeneration expression is floored.
        return level == 0 ? base : Math.floor(base + regenSqrtCoefficient * Math.sqrt(level) + regenLogCoefficient * Math.log(level));
    }

    static void checkLevel(int level) { if (level < 0) throw new IllegalArgumentException("player level must be nonnegative"); }
}

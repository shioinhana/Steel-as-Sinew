package org.fsrpg.combat;

/** Immutable, directly JSON-editable server skill definition. All timings are 20 Hz ticks. */
public record SkillDefinition(
        String id, String label, String weapon, SkillInput input,
        int windupTicks, int activeTicks, int recoveryTicks, int cooldownTicks,
        double staminaCost, double spiritCost, double damage, double reach,
        double arcDegrees, double knockback,
        boolean blockable, boolean parryable, boolean clashable,
        String model, String action) {

    public SkillDefinition {
        requireText(id, "id");
        if (!id.matches("[a-z0-9_.:-]+")) throw new IllegalArgumentException("skill id is not a stable identifier: " + id);
        requireText(label, "label");
        requireText(weapon, "weapon");
        if (input == null) throw new IllegalArgumentException("input is required for " + id);
        checkTicks(windupTicks, "windupTicks", true);
        checkTicks(activeTicks, "activeTicks", false);
        checkTicks(recoveryTicks, "recoveryTicks", true);
        checkTicks(cooldownTicks, "cooldownTicks", true);
        checkNumber(staminaCost, "staminaCost", 0, 1_000_000);
        checkNumber(spiritCost, "spiritCost", 0, 1_000_000);
        checkNumber(damage, "damage", 0, 1_000_000);
        checkNumber(reach, "reach", 0.001, 128);
        checkNumber(arcDegrees, "arcDegrees", 0.001, 360);
        checkNumber(knockback, "knockback", 0, 128);
        if (model == null || action == null) throw new IllegalArgumentException("model/action must be strings for " + id);
        if (model.contains("..") || model.startsWith("/") || model.contains("\\") || model.contains(":"))
            throw new IllegalArgumentException("model must be a relative asset path for " + id);
    }

    public int durationTicks() { return windupTicks + activeTicks + recoveryTicks; }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    static void checkTicks(int value, String name, boolean allowZero) {
        if (value < (allowZero ? 0 : 1) || value > 72_000)
            throw new IllegalArgumentException(name + " must be " + (allowZero ? "0" : "1") + "..72000 ticks");
    }

    static void checkNumber(double value, String name, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be finite and in " + minimum + ".." + maximum);
    }
}

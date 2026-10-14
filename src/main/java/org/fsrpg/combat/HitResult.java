package org.fsrpg.combat;

/** The adapter applies damage/knockback once. Projectile reflection is an explicit instruction. */
public record HitResult(HitOutcome outcome, double damage, double knockback,
                        boolean reflectProjectile, int victimStunTicks, String skillId) {
    public boolean dealsDamage() { return damage > 0; }
    public boolean contacted() {
        return switch (outcome) {
            case HIT, BLOCKED, GUARD_BROKEN, PARRIED, CLASH, DODGED -> true;
            default -> false;
        };
    }
}

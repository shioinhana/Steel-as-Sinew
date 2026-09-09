package com.actioncombat.combat;

import com.actioncombat.CombatConstants;

/**
 * Shared client/server cadence. Rapier gates follow the authored chain
 * (0.25, 0.25, 0.45, 0.45 s), tightened to (0.20, 0.20, 0.30, 0.40) while a charged
 * thrust's hit window is live. Dual wield runs on a 0.25 s gate that doubles when the
 * same hand swings twice in a row; a swing with the other hand relaxes that penalty.
 */
public final class AttackCadence {
    private long readyAtTick = Long.MIN_VALUE;
    private int lastIntervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;

    /** Dual / default: one accepted attack on the base gate. */
    public int begin(long nowTick) {
        return begin(nowTick, CombatConstants.ATTACK_INTERVAL_TICKS);
    }

    /** Start one accepted attack and return its latched recovery-cancel gate. */
    public int begin(long nowTick, int intervalTicks) {
        if (!canStart(nowTick)) throw new IllegalStateException("attack began before its cadence gate");
        int ticks = Math.max(1, intervalTicks);
        synchronize(nowTick, ticks);
        return ticks;
    }

    /** Correct the current predicted attack using its original start, never ACK arrival time. */
    public void synchronize(long startTick, int intervalTicks) {
        lastIntervalTicks = Math.max(1, intervalTicks);
        readyAtTick = startTick + lastIntervalTicks;
    }

    /** Inspect the next accepted attack's interval without consuming recovery. */
    public int previewIntervalTicks(long nowTick) {
        return lastIntervalTicks;
    }

    public boolean canStart(long nowTick) {
        return readyAtTick == Long.MIN_VALUE || nowTick >= readyAtTick;
    }

    public void reset() {
        readyAtTick = Long.MIN_VALUE;
        lastIntervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;
    }

    /** Rapier chain gate after the slash that just started; everything else is uniform. */
    public static int intervalTicksFor(WeaponCategory category, int rapierSlashJustStarted) {
        return intervalTicksFor(category, rapierSlashJustStarted, false);
    }

    public static int intervalTicksFor(WeaponCategory category, int rapierSlashJustStarted,
                                       boolean fast) {
        if (category == WeaponCategory.SINGLE) {
            int[] gates = fast
                    ? CombatConstants.RAPIER_GATE_FAST_TICKS : CombatConstants.RAPIER_GATE_TICKS;
            return gates[Math.floorMod(rapierSlashJustStarted, gates.length)];
        }
        if (category == WeaponCategory.DOUBLE) return CombatConstants.DUAL_INTERVAL_TICKS;
        return CombatConstants.ATTACK_INTERVAL_TICKS;
    }

    /**
     * Dual-wield gate for a swing with {@code hand} (0 = left, 1 = right). Two swings in
     * a row on the same hand take the slow gate; a swing with the other hand relaxes it.
     */
    public static int dualIntervalTicks(boolean leftHand, int leftStreak, int rightStreak) {
        int streak = leftHand ? leftStreak : rightStreak;
        return streak >= CombatConstants.DUAL_SLOW_AFTER_HITS
                ? CombatConstants.DUAL_SLOW_INTERVAL_TICKS
                : CombatConstants.DUAL_INTERVAL_TICKS;
    }
}

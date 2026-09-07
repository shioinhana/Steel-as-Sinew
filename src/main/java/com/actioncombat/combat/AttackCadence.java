package com.actioncombat.combat;

import com.actioncombat.CombatConstants;

/** Shared client/server cadence. Every accepted swing uses the same 0.3s cancel gate. */
public final class AttackCadence {
    private long readyAtTick = Long.MIN_VALUE;

    /** Start one accepted attack and return its latched recovery-cancel gate. */
    public int begin(long nowTick) {
        if (!canStart(nowTick)) throw new IllegalStateException("attack began before its cadence gate");
        int intervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;
        synchronize(nowTick, intervalTicks);
        return intervalTicks;
    }

    /** Correct the current predicted attack using its original start, never ACK arrival time. */
    public void synchronize(long startTick, int intervalTicks) {
        readyAtTick = startTick + CombatConstants.ATTACK_INTERVAL_TICKS;
    }

    /** Inspect the next accepted attack's interval without consuming recovery. */
    public int previewIntervalTicks(long nowTick) {
        return CombatConstants.ATTACK_INTERVAL_TICKS;
    }

    public boolean canStart(long nowTick) {
        return readyAtTick == Long.MIN_VALUE || nowTick >= readyAtTick;
    }

    public void reset() {
        readyAtTick = Long.MIN_VALUE;
    }
}

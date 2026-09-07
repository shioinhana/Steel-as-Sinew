package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import com.actioncombat.combat.SwingSide;
import java.util.function.LongSupplier;

/**
 * One pending swing, kept until the current action's next cancel opportunity plus
 * a short grace period. A newer press replaces it, so mashing cannot queue a chain.
 */
public final class InputBuffer {
    private SwingSide pendingSide;
    private boolean pendingCharged;
    private boolean pendingDual;
    private long pendingUntilNanos;
    private final LongSupplier clock;

    public InputBuffer() {
        this(System::nanoTime);
    }

    public InputBuffer(LongSupplier clock) {
        this.clock = clock;
    }

    /** Remember a press that could not be sent yet. */
    public void push(SwingSide side, boolean charged) {
        push(side, charged, false, clock.getAsLong());
    }

    public void push(SwingSide side, boolean charged, boolean dual, long availableAtNanos) {
        pendingSide = side;
        pendingCharged = charged;
        pendingDual = dual;
        pendingUntilNanos = Math.max(clock.getAsLong(), availableAtNanos) + bufferNanos();
    }

    public boolean hasPending() {
        if (pendingSide == null) return false;
        if (clock.getAsLong() > pendingUntilNanos) {
            clear();
            return false;
        }
        return true;
    }

    public SwingSide pendingSide() {
        return pendingSide;
    }

    public boolean pendingCharged() {
        return pendingCharged;
    }

    public boolean pendingDual() {
        return pendingDual;
    }

    public void clear() {
        pendingSide = null;
        pendingCharged = false;
        pendingDual = false;
        pendingUntilNanos = 0L;
    }

    private static long bufferNanos() {
        return (long) (CombatConstants.INPUT_BUFFER_SECONDS * 1.0E9F);
    }
}

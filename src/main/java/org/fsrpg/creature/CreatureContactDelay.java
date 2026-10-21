package org.fsrpg.creature;

/** One original melee invocation, held briefly for an already-started opposing attack. */
public final class CreatureContactDelay {
    private final long contactTick;
    private final int windowTicks;
    private final boolean waitForReverse;
    private boolean executed, canceled, deferred;

    public CreatureContactDelay(long contactTick, int windowTicks, boolean waitForReverse) {
        if (contactTick < 0 || windowTicks < 0 || windowTicks > 10)
            throw new IllegalArgumentException("Invalid creature contact delay");
        this.contactTick = contactTick;
        this.windowTicks = windowTicks;
        this.waitForReverse = waitForReverse;
    }

    public long contactTick() { return contactTick; }
    public boolean waitForReverse() { return waitForReverse; }
    public boolean pending() { return !executed && !canceled; }
    public boolean executed() { return executed; }
    public boolean deferred() { return deferred; }

    /** The adapter calls deadline checks after collecting all contacts for the current tick. */
    public boolean claimExecution(long now, boolean reverseContactPresent) {
        if (now < contactTick || !pending()) return false;
        if (waitForReverse && !reverseContactPresent && now - contactTick < windowTicks) return false;
        executed = true;
        deferred = now > contactTick;
        return true;
    }

    public void cancel() { canceled = true; }
}

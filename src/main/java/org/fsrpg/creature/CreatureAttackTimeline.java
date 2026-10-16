package org.fsrpg.creature;

/** World-tick attack clock, independent of Minecraft, rendering and wall-clock time. */
public final class CreatureAttackTimeline {
    public enum Phase { WINDUP, RECOVERY, FINISHED, CANCELED }
    private final long started;
    private final int windup, recovery;
    private boolean contactClaimed, canceled;

    public CreatureAttackTimeline(long startedGameTick, int windupTicks, int recoveryTicks) {
        if (startedGameTick < 0 || windupTicks < 1 || recoveryTicks < 1
                || windupTicks > 1200 || recoveryTicks > 1200)
            throw new IllegalArgumentException("Invalid creature attack timeline");
        started = startedGameTick; windup = windupTicks; recovery = recoveryTicks;
    }
    public long startedGameTick() { return started; }
    public long contactGameTick() { return started + windup; }
    public long endGameTick() { return started + windup + recovery; }
    public boolean contactClaimed() { return contactClaimed; }
    public Phase phase(long gameTick) {
        if (canceled) return Phase.CANCELED;
        if (gameTick >= endGameTick()) return Phase.FINISHED;
        return gameTick < contactGameTick() ? Phase.WINDUP : Phase.RECOVERY;
    }
    /** Exactly one contact is possible, including when a world tick advances over the boundary. */
    public boolean claimContact(long gameTick) {
        if (canceled || contactClaimed || gameTick < contactGameTick() || gameTick >= endGameTick()) return false;
        contactClaimed = true; return true;
    }
    public void cancel() { canceled = true; }
}

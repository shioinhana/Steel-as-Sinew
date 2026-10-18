package org.fsrpg.creature;

/** A single cosmetic flash follows an immutable, server-authoritative attack schedule. */
public final class GuardCueClock {
    public record Message(long sequence, String phase, long startedGameTime, long serverGameTime,
                          long flashGameTime, long openGameTime, long closeGameTime, long contactGameTime,
                          int flashDurationMillis) {
        public Message {
            if (sequence < 0 || phase == null || !java.util.Set.of("BEGIN", "CONTACT", "CANCEL", "END").contains(phase)
                    || startedGameTime < 0 || serverGameTime < startedGameTime || flashGameTime < startedGameTime
                    || openGameTime < flashGameTime || closeGameTime <= openGameTime || contactGameTime < closeGameTime
                    || contactGameTime - startedGameTime > 600 || flashDurationMillis < 1 || flashDurationMillis > 1000)
                throw new IllegalArgumentException("Invalid authoritative guard cue schedule");
        }
        public boolean terminal() { return phase.equals("CANCEL") || phase.equals("END"); }
    }

    /** Render progress is continuous at native frame rate; it never controls server guard eligibility. */
    public record Sample(long sequence, double progress, double serverGameTime, long flashGameTime,
                         long openGameTime, long closeGameTime, long contactGameTime) {}

    private Message current;
    private double clockOffset;
    private double lastServerTime;
    private boolean expired;

    public boolean accept(Message incoming, long localGameTime) {
        if (current != null) {
            if (incoming.sequence() < current.sequence()) return false;
            if (incoming.sequence() == current.sequence()) {
                if (current.terminal() || incoming.serverGameTime() < current.serverGameTime()
                        || rank(incoming.phase()) < rank(current.phase()) || !sameAttack(incoming, current)) return false;
                // A duplicate packet cannot move the flash to its later receive time.
                if (incoming.serverGameTime() == current.serverGameTime() && incoming.phase().equals(current.phase())) return true;
            } else {
                expired = false;
                lastServerTime = incoming.startedGameTime();
            }
        } else lastServerTime = incoming.startedGameTime();
        current = incoming;
        // World ticks are already synchronized by Minecraft. Only tolerate a one-tick receive skew:
        // rebasing a queued BEGIN all the way back to its send time could produce a ghost flash after contact.
        clockOffset = Math.clamp(incoming.serverGameTime() - (double) localGameTime, -1, 1);
        lastServerTime = Math.max(lastServerTime, incoming.serverGameTime());
        return true;
    }

    public Sample sample(long localGameTime, float partialTick) {
        if (current == null || current.terminal() || expired || !Float.isFinite(partialTick)) return null;
        double rawNow = localGameTime + Math.clamp(partialTick, 0, 1);
        double durationTicks = current.flashDurationMillis() / 50.0;
        // The end of the success window is also a hard cosmetic deadline. Late packets never create a late flash.
        double end = Math.min(current.flashGameTime() + durationTicks, current.closeGameTime());
        // A late packet can never rewind the synchronized world clock past an already expired visual/window deadline.
        if (rawNow >= end) { expired = true; return null; }
        double now = Math.max(lastServerTime, rawNow + clockOffset);
        lastServerTime = now;
        if (now >= end) { expired = true; return null; }
        if (now < current.flashGameTime()) return null;
        return new Sample(current.sequence(), Math.clamp((now - current.flashGameTime()) / durationTicks, 0, 1), now,
                current.flashGameTime(), current.openGameTime(), current.closeGameTime(), current.contactGameTime());
    }

    public long sequence() { return current == null ? -1 : current.sequence(); }
    public boolean terminal() { return current == null || current.terminal() || expired; }

    private static boolean sameAttack(Message first, Message second) {
        return first.startedGameTime() == second.startedGameTime() && first.flashGameTime() == second.flashGameTime()
                && first.openGameTime() == second.openGameTime() && first.closeGameTime() == second.closeGameTime()
                && first.contactGameTime() == second.contactGameTime() && first.flashDurationMillis() == second.flashDurationMillis();
    }
    private static int rank(String phase) {
        return switch (phase) { case "BEGIN" -> 0; case "CONTACT" -> 1; case "CANCEL", "END" -> 2; default -> -1; };
    }
}

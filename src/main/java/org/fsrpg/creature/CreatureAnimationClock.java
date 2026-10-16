package org.fsrpg.creature;

/** Pure server-clock follower; no wall clock, entity swing or hurt animation starts an attack. */
public final class CreatureAnimationClock {
    public record Message(long sequence, String clip, String phase, long startedGameTime, long serverGameTime,
                          int windupTicks, int recoveryTicks, double contactSeconds, double durationSeconds) {
        public Message {
            if (sequence < 0 || clip == null || !clip.matches("attack(?:_[a-z0-9_]+)?")
                    || phase == null || !java.util.Set.of("BEGIN", "CONTACT", "CANCEL", "END").contains(phase)
                    || startedGameTime < 0 || serverGameTime < startedGameTime
                    || windupTicks < 1 || windupTicks > 600 || recoveryTicks < 1 || recoveryTicks > 600
                    || !Double.isFinite(contactSeconds) || !Double.isFinite(durationSeconds)
                    || contactSeconds <= 0 || durationSeconds <= contactSeconds || durationSeconds > 30)
                throw new IllegalArgumentException("Invalid authoritative creature animation message");
        }
        public int totalTicks() { return windupTicks + recoveryTicks; }
        public boolean terminal() { return phase.equals("CANCEL") || phase.equals("END"); }
    }

    public record Sample(String clip, String phase, long sequence, double elapsedTicks, double seconds,
                         long startedGameTime, long contactGameTime, long endedGameTime) {}

    private Message current;
    private double clockOffset;
    private double lastElapsed;

    public boolean accept(Message incoming, long localGameTime) {
        if (current != null) {
            if (incoming.sequence() < current.sequence()) return false;
            if (incoming.sequence() == current.sequence()) {
                if (current.terminal() || incoming.serverGameTime() < current.serverGameTime()
                        || rank(incoming.phase()) < rank(current.phase()) || !sameAttack(incoming, current)) return false;
                // A retransmission has no newer clock information and must not
                // shift the origin to its later local receive time.
                if (incoming.serverGameTime() == current.serverGameTime() && incoming.phase().equals(current.phase())) return true;
            } else lastElapsed = 0;
        }
        current = incoming;
        // Client world time can lead or lag. Signed correction follows the
        // authoritative packet, while sample() prevents same-sequence rewinds.
        clockOffset = incoming.serverGameTime() - (double) localGameTime;
        return true;
    }

    public Sample sample(long localGameTime, float partialTick) {
        if (current == null || current.terminal()) return null;
        if (!Float.isFinite(partialTick)) return null;
        double elapsed = Math.max(lastElapsed, Math.max(0,
                localGameTime + Math.clamp(partialTick, 0, 1) + clockOffset - current.startedGameTime()));
        lastElapsed = elapsed;
        if (elapsed >= current.totalTicks()) return null;
        double seconds = elapsed <= current.windupTicks() ? current.contactSeconds() * elapsed / current.windupTicks()
                : current.contactSeconds() + (current.durationSeconds() - current.contactSeconds())
                * (elapsed - current.windupTicks()) / current.recoveryTicks();
        String phase = elapsed < current.windupTicks() ? "WINDUP"
                : elapsed < current.windupTicks() + 1 ? "CONTACT" : "RECOVERY";
        return new Sample(current.clip(), phase, current.sequence(), elapsed, seconds, current.startedGameTime(),
                current.startedGameTime() + current.windupTicks(), current.startedGameTime() + current.totalTicks());
    }

    public long sequence() { return current == null ? -1 : current.sequence(); }

    private static boolean sameAttack(Message first, Message second) {
        return first.clip().equals(second.clip()) && first.startedGameTime() == second.startedGameTime()
                && first.windupTicks() == second.windupTicks() && first.recoveryTicks() == second.recoveryTicks()
                && first.contactSeconds() == second.contactSeconds() && first.durationSeconds() == second.durationSeconds();
    }

    private static int rank(String phase) {
        return switch (phase) { case "BEGIN" -> 0; case "CONTACT" -> 1; case "CANCEL", "END" -> 2; default -> -1; };
    }
}

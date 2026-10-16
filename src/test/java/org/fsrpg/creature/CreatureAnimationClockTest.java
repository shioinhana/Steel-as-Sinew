package org.fsrpg.creature;

/** Pure client-clock ordering, late tracking and exact source-contact tests. */
public final class CreatureAnimationClockTest {
    private static int checks;

    public static void main(String[] args) {
        var clock = new CreatureAnimationClock();
        check(clock.sample(100, 0) == null, "no attack inferred before a server begin");
        check(clock.accept(message(1, "BEGIN", 100, 100), 100), "begin accepted");
        near(0, clock.sample(100, 0).seconds(), "begin starts source");
        var subtick = clock.sample(101, .5f);
        near(.29167 * 1.5 / 6, subtick.seconds(), "real native subtick sampling");
        check(subtick.phase().equals("WINDUP"), "windup phase");
        check(clock.accept(message(1, "CONTACT", 100, 106), 106), "contact accepted");
        near(.29167, clock.sample(106, 0).seconds(), "contact maps exact source impact");
        check(clock.sample(106, 0).phase().equals("CONTACT"), "contact phase spans actual contact tick");
        double afterContact = clock.sample(107, .5f).seconds();
        check(clock.accept(message(1, "CONTACT", 100, 106), 107), "duplicate contact harmless");
        near(afterContact, clock.sample(107, .5f).seconds(), "duplicate contact does not rewind or replay");
        check(clock.sample(107, .5f).phase().equals("RECOVERY"), "recovery phase");
        check(!clock.accept(message(0, "BEGIN", 100, 100), 108), "old sequence ignored");
        check(!clock.accept(message(1, "BEGIN", 100, 108), 108), "same-sequence older phase ignored");
        check(clock.accept(message(1, "CANCEL", 100, 108), 108), "cancel accepted");
        check(clock.sample(108, .5f) == null, "cancel stops source attack immediately");
        check(!clock.accept(message(1, "CONTACT", 100, 109), 109), "late same-sequence contact cannot undo cancel");
        check(clock.accept(message(2, "BEGIN", 120, 120), 120), "new sequence after cancel");
        check(clock.sample(148, 0) == null, "attack ends at configured tick even without end packet");
        check(clock.accept(message(2, "END", 120, 148), 148), "end packet accepted");
        check(clock.sample(149, 0) == null, "ended animation not restarted by missing packets");

        var late = new CreatureAnimationClock();
        check(late.accept(message(50, "CONTACT", 200, 214), 214), "tracking begins during recovery");
        near(.29167 + (1.375 - .29167) * 8 / 22, late.sample(214, 0).seconds(), "late viewer samples server elapsed time");
        var skewed = new CreatureAnimationClock();
        skewed.accept(message(10, "BEGIN", 300, 300), 295);
        near(0, skewed.sample(295, 0).seconds(), "server clock corrects a lagging client world tick");
        skewed.accept(message(10, "CONTACT", 300, 306), 301);
        near(.29167, skewed.sample(301, 0).seconds(), "contact correction uses fixed attack origin");
        check(!skewed.accept(new CreatureAnimationClock.Message(10, "attack", "CONTACT", 299, 307, 6, 22, .29167, 1.375), 302),
                "same-sequence origin cannot change");
        check(skewed.sample(301, Float.NaN) == null, "nonfinite native partial rejected");
        var ahead = new CreatureAnimationClock();
        ahead.accept(message(20, "BEGIN", 500, 500), 502);
        near(0, ahead.sample(502, 0).seconds(), "leading client world does not skip the windup start");
        near(.29167 * 2.5 / 6, ahead.sample(504, .5f).seconds(), "signed leading-world clock advances at native rate");
        ahead.accept(message(20, "CONTACT", 500, 506), 508);
        near(.29167, ahead.sample(508, 0).seconds(), "leading-world contact stays exact");
        var corrected = new CreatureAnimationClock();
        corrected.accept(message(30, "BEGIN", 600, 600), 600);
        near(.29167 * 2 / 6, corrected.sample(602, 0).seconds(), "initial correct world clock");
        corrected.accept(message(30, "BEGIN", 600, 603), 623);
        near(.29167 * 3 / 6, corrected.sample(623, 0).seconds(), "forward world-time correction follows newer server clock");
        corrected.accept(message(30, "BEGIN", 600, 604), 584);
        near(.29167 * 4 / 6, corrected.sample(584, 0).seconds(), "backward world-time correction follows newer server clock");
        double beforeRollback = corrected.sample(585, .5f).seconds();
        check(corrected.sample(580, 0).seconds() == beforeRollback, "same-sequence raw world rollback cannot rewind animation");
        corrected.accept(message(31, "BEGIN", 700, 700), 720);
        near(0, corrected.sample(720, 0).seconds(), "new sequence resets elapsed even after a large previous correction");
        invalid(() -> message(-1, "BEGIN", 100, 100), "negative sequence");
        invalid(() -> message(1, "HURT", 100, 100), "unrecognized phase");
        invalid(() -> message(1, "BEGIN", 100, 99), "server time before begin");
        invalid(() -> new CreatureAnimationClock.Message(1, "idle", "BEGIN", 100, 100, 6, 22, .29167, 1.375), "nonattack clip");
        invalid(() -> new CreatureAnimationClock.Message(1, "attack", "BEGIN", 100, 100, 0, 22, .29167, 1.375), "zero windup");
        invalid(() -> new CreatureAnimationClock.Message(1, "attack", "BEGIN", 100, 100, 6, 22, Double.NaN, 1.375), "nonfinite source time");
        System.out.println("CreatureAnimationClockTest passed: " + checks + " checks");
    }

    private static CreatureAnimationClock.Message message(long sequence, String phase, long start, long now) {
        return new CreatureAnimationClock.Message(sequence, "attack", phase, start, now, 6, 22, .29167, 1.375);
    }
    private static void invalid(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Accepted " + message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-8, message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

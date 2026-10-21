package org.fsrpg.creature;

/** Timing, native-rate progression, deduplication and attack lifecycle for the cosmetic guard warning. */
public final class GuardCueClockTest {
    private static int checks;

    public static void main(String[] args) {
        var clock = new GuardCueClock();
        check(clock.sample(100, 0) == null, "nothing inferred without authoritative schedule");
        check(clock.accept(message(1, "BEGIN", 100, 100), 100), "begin schedules future flash");
        check(clock.sample(103, .99f) == null, "no flash before server flash time");
        near(0, clock.sample(104, 0).progress(), "exact flash time");
        near(.5 / 2.4, clock.sample(104, .5f).progress(), "native partial ticks animate independently of 20 Hz logic");
        check(clock.accept(message(1, "BEGIN", 100, 100), 105), "duplicate packet accepted harmlessly");
        near(1 / 2.4, clock.sample(105, 0).progress(), "duplicate does not move flash origin");
        check(!clock.accept(message(0, "BEGIN", 100, 105), 105), "stale sequence ignored");
        check(clock.sample(106, .4f) == null, "flash expires after configured 120 ms");
        check(clock.sample(104, 0) == null, "world time rollback cannot replay expired flash");
        check(clock.accept(message(1, "BEGIN", 100, 106), 104), "newer same-sequence clock message");
        check(clock.sample(104, 0) == null, "clock correction cannot restart expired flash");
        check(clock.accept(message(1, "CONTACT", 100, 110), 110), "contact accepted");
        check(clock.sample(110, 0) == null, "contact never generates a second flash");
        check(!clock.accept(message(1, "BEGIN", 100, 111), 111), "older phase cannot supersede contact");
        check(clock.accept(message(1, "END", 100, 112), 112), "end accepted");
        check(clock.sample(112, 0) == null, "end has no display");
        check(!clock.accept(message(1, "BEGIN", 100, 112), 113), "terminal sequence cannot resurrect");

        check(clock.accept(message(2, "BEGIN", 200, 200), 200), "new attack resets single flash state");
        check(clock.sample(203, .5f) == null, "next attack still waits its own flash");
        near(0, clock.sample(204, 0).progress(), "second attack has one independent flash");
        check(clock.accept(message(2, "CANCEL", 200, 204), 204), "cancel during flash accepted");
        check(clock.sample(204, .1f) == null, "cancel hides immediately");
        check(!clock.accept(message(2, "CONTACT", 200, 210), 210), "late contact cannot undo cancel");

        var canceledBefore = new GuardCueClock();
        canceledBefore.accept(message(3, "BEGIN", 300, 300), 300);
        canceledBefore.accept(message(3, "CANCEL", 300, 302), 302);
        check(canceledBefore.sample(304, 0) == null, "cancel before flash suppresses it");

        var late = new GuardCueClock();
        late.accept(message(50, "BEGIN", 100, 107), 107);
        check(late.sample(107, 0) == null, "late begin never emits an overdue flash");
        var queuedBegin = new GuardCueClock();
        queuedBegin.accept(new GuardCueClock.Message(51, "BEGIN", 100, 100, 108, 108, 114, 114, 120), 200);
        check(queuedBegin.sample(200, 0) == null, "genuinely delayed old BEGIN cannot rebase into a future ghost flash");
        check(queuedBegin.sample(208, 0) == null, "old BEGIN stays expired at its incorrectly rebased flash time");
        var partlyLate = new GuardCueClock();
        partlyLate.accept(message(50, "BEGIN", 100, 105), 105);
        near(1 / 2.4, partlyLate.sample(105, 0).progress(), "within visual interval samples actual elapsed time");
        var contactOnly = new GuardCueClock();
        contactOnly.accept(message(50, "CONTACT", 100, 110), 100);
        check(contactOnly.sample(100, 0) == null, "late tracking at contact never replays flash");

        var behind = new GuardCueClock();
        behind.accept(message(10, "BEGIN", 500, 500), 499);
        check(behind.sample(502, .9f) == null, "one-tick lagging client follows signed server offset before flash");
        near(0, behind.sample(503, 0).progress(), "one-tick lagging client exact flash");
        var ahead = new GuardCueClock();
        ahead.accept(message(10, "BEGIN", 500, 500), 501);
        near(0, ahead.sample(505, 0).progress(), "one-tick leading client also follows signed server offset");
        var correction = new GuardCueClock();
        correction.accept(message(1, "BEGIN", 100, 100), 100);
        correction.sample(104, .5f);
        correction.accept(message(1, "BEGIN", 100, 104), 105);
        near(.5 / 2.4, correction.sample(105, 0).progress(), "same attack cannot rewind during one-tick clock correction");
        check(!correction.accept(new GuardCueClock.Message(1, "BEGIN", 100, 104, 105, 105, 110, 110, 120), 124),
                "same sequence cannot change schedule");
        check(correction.sample(124, Float.NaN) == null, "nonfinite partial rejected");
        check(correction.sample(124, 0) == null, "large late-world correction expires rather than replaying a warning");

        var clipped = new GuardCueClock();
        clipped.accept(new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 104, 105, 105, 1000), 100);
        check(clipped.sample(104, .5f) != null, "long visual duration still continuous before hard window deadline");
        check(clipped.sample(105, 0) == null, "visual cannot linger after success window expires");

        int nativeSamples = 0;
        var nativeRate = new GuardCueClock();
        nativeRate.accept(message(1, "BEGIN", 100, 100), 100);
        for (int frame = 0; frame < 72; frame++) {
            double now = 100 + frame / 3.0;
            if (nativeRate.sample((long) now, (float) (now % 1)) != null) nativeSamples++;
        }
        check(nativeSamples >= 7, "120 ms warning has at least seven distinct native samples at 60 Hz");

        invalid(() -> message(-1, "BEGIN", 100, 100), "negative sequence");
        invalid(() -> message(1, "FLASH", 100, 100), "unsupported phase");
        invalid(() -> message(1, "BEGIN", 100, 99), "server time precedes start");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 99, 104, 110, 110, 120), "flash precedes attack");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 103, 110, 110, 120), "success window precedes flash");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 104, 103, 110, 120), "inverted window");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 104, 110, 109, 120), "window exceeds contact");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 104, 710, 710, 120), "unbounded attack");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 104, 110, 110, 0), "empty duration");
        invalid(() -> new GuardCueClock.Message(1, "BEGIN", 100, 100, 104, 104, 110, 110, 1001), "excessive duration");
        fractionalSchedules();
        System.out.println("GuardCueClockTest passed: " + checks + " checks");
    }

    private static void fractionalSchedules() {
        for(int windup:new int[]{1,2,3,5,7,10,15,59,600}) {
            var window=GuardCueSettings.defaults().window(100,windup);
            var begin=new GuardCueClock.Message(windup,"BEGIN",100,100,window.flashGameTick(),window.openGameTick(),
                    window.closeGameTick(),window.contactGameTick(),120);
            var clock=new GuardCueClock();
            check(clock.accept(begin,100),"Authored fractional schedule is accepted, including contact-only input windows");
            if(window.flashGameTick()>100)check(clock.sample(window.flashGameTick()-1,.99f)==null,"Fractional cosmetic flash cannot precede its floor-quantized server tick");
            near(0,clock.sample(window.flashGameTick(),0).progress(),"Fractional warning begins at its actual server flash tick");
            check(clock.sample(window.flashGameTick(),.5f)!=null,"A short authored attack still has native sub-tick visual samples");
            check(clock.sample(window.contactGameTick(),0)==null,"Inclusive input contact does not extend the cosmetic flash beyond contact");
            check(clock.sample(window.flashGameTick(),0)==null,"Expired short/fractional flash cannot replay after a clock rollback");
            var late=new GuardCueClock();late.accept(begin,window.contactGameTick());
            check(late.sample(window.contactGameTick(),0)==null,"Queued old fractional BEGIN cannot create a late flash");
            var contact=new GuardCueClock();
            contact.accept(new GuardCueClock.Message(windup,"CONTACT",100,window.contactGameTick(),window.flashGameTick(),window.openGameTick(),
                    window.closeGameTick(),window.contactGameTick(),120),100);
            check(contact.sample(100,0)==null,"Contact-only tracking never rewinds to a fractional flash");
            var canceled=new GuardCueClock();canceled.accept(begin,100);
            canceled.accept(new GuardCueClock.Message(windup,"CANCEL",100,100,window.flashGameTick(),window.openGameTick(),
                    window.closeGameTick(),window.contactGameTick(),120),100);
            check(canceled.sample(window.flashGameTick(),0)==null,"Canceled fractional attack cannot display its scheduled warning");
        }
        var point=new GuardCueClock.Message(1,"BEGIN",100,100,100,100,100,100,120);
        var clock=new GuardCueClock();check(clock.accept(point,100),"Equal flash/open/close schedule remains a valid authoritative message");
        check(clock.sample(100,0)==null,"A zero cosmetic interval does not invent a late flash");
    }

    private static GuardCueClock.Message message(long sequence, String phase, long start, long now) {
        return new GuardCueClock.Message(sequence, phase, start, now, start + 4, start + 4, start + 10, start + 10, 120);
    }
    private static void invalid(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Accepted " + message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-6, message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

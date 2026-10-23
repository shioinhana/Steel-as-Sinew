package org.fsrpg.client;

import static org.fsrpg.client.ImportedItemMotion.*;

/** Pure trajectory and lifecycle checks, including count-independent equip keys. */
public final class ImportedItemMotionTest {
    private static int checks;
    public static void main(String[] args) {
        endpointsAndBounds();
        continuousMotionAtFlightAndLanding();
        subTickSampling();
        equipAndCountIdentity();
        independentHandsAndReset();
        pendingAnimationAndClockCorrection();
        outgoingCacheKeepsPreviousPose();
        pouchHasWeightAndCushionedCatch();
        invalidSettingsRejected();
        System.out.println("ImportedItemMotionTest passed: " + checks + " checks");
    }

    private static void endpointsAndBounds() {
        var settings = Settings.DEFAULT;
        for (var kind : new Kind[]{Kind.POUCH, Kind.COIN, Kind.BADGE}) {
            var start = pose(kind, 0, settings);
            equal(0, start.armLift(), "start has no heft discontinuity");
            equal(0, start.itemLift(), "start is on the hand");
            equal(0, start.itemSpinDegrees(), "initial orientation");
            equal(0, start.drawProgress(), "starts withdrawn");
            var end = pose(kind, settings.duration(kind), settings);
            check(end.settled(), "endpoint is settled");
            equal(0, end.armLift(), "hand returns exactly");
            equal(0, end.armTiltDegrees(), "wrist/forearm tilt returns exactly");
            equal(0, end.itemLift(), "item lands exactly");
            equal(0, end.itemSpinDegrees() % 360, "landed orientation equals initial");
            equal(1, end.drawProgress(), "fully drawn");
            double maxLift = 0, maxHeft = 0;
            for (int i = 0; i <= 240; i++) {
                var sample = pose(kind, settings.duration(kind) * i / 240, settings);
                double flightLimit = kind == Kind.POUCH ? settings.pouchTossHeight() : settings.tossHeight();
                check(sample.itemLift() >= 0 && sample.itemLift() <= flightLimit + 1e-12, "bounded nonpenetrating flight");
                check(sample.drawProgress() >= 0 && sample.drawProgress() <= 1, "draw cannot overshoot");
                maxLift = Math.max(maxLift, sample.itemLift()); maxHeft = Math.max(maxHeft, sample.armLift());
            }
            if (kind == Kind.COIN) equal(settings.tossHeight(), maxLift, "coin reaches configured apex");
            else if (kind == Kind.POUCH) check(maxLift > .99 * settings.pouchTossHeight(), "pouch clearly leaves hand in initial toss");
            else equal(0, maxLift, "badge never detaches from hand");
            if (kind == Kind.POUCH) check(maxHeft > .99 * settings.heftHeight(), "pouch reaches full heft height");
        }
    }

    private static void continuousMotionAtFlightAndLanding() {
        var settings = Settings.DEFAULT;
        for (var kind : new Kind[]{Kind.POUCH, Kind.COIN, Kind.BADGE}) {
            for (double fraction : new double[]{0, .15, .22, .30, .50, .75, .78, .82, 1}) {
                double t = fraction * settings.duration(kind), epsilon = 1e-6;
                var a = pose(kind, Math.max(0, t - epsilon), settings);
                var b = pose(kind, t, settings);
                var c = pose(kind, t + epsilon, settings);
                check(Math.abs(a.itemLift() - c.itemLift()) < 1e-5, "flight C0 at phase boundary");
                check(Math.abs(a.armLift() - c.armLift()) < 1e-5, "arm C0 at phase boundary");
                check(Math.abs(a.armTiltDegrees() - c.armTiltDegrees()) < .001, "arm tilt C0 at phase boundary");
                check(Math.abs(a.itemSpinDegrees() - c.itemSpinDegrees()) < .005, "spin C0, including 360deg settle");
                if (fraction > 0) {
                    double before = (b.itemLift() - a.itemLift()) / epsilon;
                    double after = (c.itemLift() - b.itemLift()) / epsilon;
                    check(Math.abs(before - after) < .001, "flight velocity continuous at launch/landing");
                    check(Math.abs((b.armLift() - a.armLift()) / epsilon - (c.armLift() - b.armLift()) / epsilon) < .001,
                            "weighted hand velocity continuous at load/lift/catch boundaries");
                }
            }
        }
        double t = .5 * settings.coinSeconds();
        check(pose(Kind.COIN, t - .01, settings).itemLift() < pose(Kind.COIN, t, settings).itemLift(), "coin ascends before apex");
        check(pose(Kind.COIN, t + .01, settings).itemLift() < pose(Kind.COIN, t, settings).itemLift(), "coin descends after apex");
    }

    private static void subTickSampling() {
        double t = .35;
        var a = pose(Kind.COIN, t, Settings.DEFAULT);
        var b = pose(Kind.COIN, t + 1.0 / 120, Settings.DEFAULT);
        check(a.itemLift() != b.itemLift(), "motion advances at 120Hz, not quantized to game ticks");
        var direct = pose(Kind.COIN, .7, Settings.DEFAULT);
        var state = new State(); state.select(key("coin", 0), Kind.COIN); state.sample(10, true, true, Settings.DEFAULT);
        for (int i = 1; i <= 84; i++) state.sample(10 + i / 120.0, true, true, Settings.DEFAULT);
        near(direct.itemLift(), state.last().itemLift(), 1e-10, "trajectory is independent of frame subdivision");
    }

    private static void equipAndCountIdentity() {
        var state = new State();
        check(state.select(key("coin", 0), Kind.COIN), "first equip accepted");
        equal(1, state.serial(), "first serial");
        state.sample(0, true, true, Settings.DEFAULT);
        state.sample(2, true, true, Settings.DEFAULT);
        // Newly decoded network stack with identical components and another
        // count yields this same key; neither Java object identity nor count is used.
        check(!state.select(new EquipKey(new String("coin"), 0, new String("same components"), false), Kind.COIN), "fresh same-item/count update does not re-equip");
        check(state.sample(3, true, true, Settings.DEFAULT).settled(), "idle never restarts one shot");
        equal(1, state.serial(), "count update preserves serial");
        check(state.select(key("coin", 1), Kind.COIN), "identical item in another slot is a real equip");
        equal(2, state.serial(), "slot equip increments exactly once");
        state.select(null, Kind.NONE);
        equal(2, state.serial(), "empty slot does not invent an animation");
        state.select(key("coin", 1), Kind.COIN);
        equal(3, state.serial(), "return from empty equips again");
        state.select(key("badge", 1), Kind.BADGE);
        equal(4, state.serial(), "different item equips once");
    }

    private static void independentHandsAndReset() {
        var main = new State(); var off = new State();
        main.select(key("coin", 0), Kind.COIN); off.select(key("badge", -1), Kind.BADGE);
        main.sample(1, true, true, Settings.DEFAULT); off.sample(1, true, true, Settings.DEFAULT);
        main.sample(1.5, true, true, Settings.DEFAULT); off.sample(1.5, true, true, Settings.DEFAULT);
        check(main.last().itemLift() > .1 && off.last().settled(), "hands keep independent timing and kind");
        main.select(key("pouch", 2), Kind.POUCH);
        equal(1, off.serial(), "changing main hand cannot retrigger offhand");
        main.reset(); off.reset();
        check(main.key() == null && main.kind() == Kind.NONE && main.serial() == 0, "logout/world/player reset clears state");
        check(main.last().settled() && off.last().settled(), "reset leaves no residual transform");
    }

    private static void pendingAnimationAndClockCorrection() {
        var state = new State(); state.select(key("coin", 0), Kind.COIN);
        state.sample(100, false, true, Settings.DEFAULT);
        state.sample(101, false, true, Settings.DEFAULT);
        check(state.pending() && state.last().elapsedSeconds() == 0, "incoming lower phase does not consume toss");
        state.sample(102, true, true, Settings.DEFAULT);
        check(!state.pending() && state.last().elapsedSeconds() == 0, "first visible raising frame starts once");
        state.sample(102.5, true, true, Settings.DEFAULT);
        double elapsed = state.last().elapsedSeconds();
        state.sample(0, true, true, Settings.DEFAULT);
        equal(elapsed, state.last().elapsedSeconds(), "clock correction cannot rewind");
        state.sample(103, true, false, Settings.DEFAULT);
        check(state.last().settled(), "disabled cosmetic animation goes directly to held pose");
        state.sample(103.1, true, true, Settings.DEFAULT);
        check(state.last().settled() && state.serial() == 1, "reenabling does not replay existing equip");
    }

    private static void invalidSettingsRejected() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, .44, 2.01}) {
            boolean rejected = false;
            try { new Settings(.8, invalid, .35, .18, .028, .70, .22); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid duration rejected " + invalid);
        }
        for (double invalid : new double[]{Double.NaN, -.01, .301}) {
            boolean rejected = false;
            try { new Settings(.8, 1.05, .35, invalid, .028, .70, .22); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid amplitude rejected " + invalid);
        }
    }
    private static void outgoingCacheKeepsPreviousPose() {
        var next = new State(); var held = new HeldPose();
        next.select(key("coin", 0), Kind.COIN);
        next.sample(0, true, true, Settings.DEFAULT);
        var flying = next.sample(.5, true, true, Settings.DEFAULT);
        held.remember("old coin components", flying);
        next.select(key("badge", 1), Kind.BADGE);
        for (int frame = 0; frame < 12; frame++) {
            var outgoing = held.outgoing("old coin components", Kind.COIN);
            check(outgoing == flying, "old cached stack retains the exact last pose while lowering");
            check(next.pending() && next.last().elapsedSeconds() == 0, "drawing outgoing stack cannot consume incoming animation");
            equal(2, next.serial(), "outgoing frames cannot retrigger equip");
        }
        var firstIncoming = next.sample(1, true, true, Settings.DEFAULT);
        equal(0, firstIncoming.elapsedSeconds(), "incoming one shot starts only on its own visible render");
        next.select(null, Kind.NONE);
        check(held.outgoing("old coin components", Kind.COIN) == flying, "resource to ordinary item also retains outgoing visual");
        check(held.outgoing("different visual", Kind.POUCH).settled(), "unknown outgoing visual uses neutral hold, never another item's motion");
        held.reset();
        equal(0, held.outgoing("old coin components", Kind.COIN).itemLift(), "world reset removes old airborne pose");
    }
    private static void pouchHasWeightAndCushionedCatch() {
        var settings = Settings.DEFAULT;
        double landing = settings.pouchTossSeconds();
        equal(2.7, settings.duration(Kind.POUCH), "one initial toss and two complete heft periods");
        equal(settings.pouchTossHeight(), pose(Kind.POUCH, .46 * landing, settings).itemLift(), "initial toss reaches its own configured apex");
        equal(0, pose(Kind.POUCH, .80 * landing, settings).itemLift(), "pouch lands before the two hefts begin");
        for (int heft = 0; heft < 2; heft++) {
        double start = landing + heft * settings.pouchSeconds();
        var anticipation = pose(Kind.POUCH, start + .22 * settings.pouchSeconds(), settings);
        var lift = pose(Kind.POUCH, start + .50 * settings.pouchSeconds(), settings);
        var catchPose = pose(Kind.POUCH, start + .82 * settings.pouchSeconds(), settings);
        equal(-.04, anticipation.armLift(), "pouch visibly loads the hand before lifting");
        equal(.10, lift.armLift(), "pouch makes a full ten-centimeter heft");
        equal(-.025, catchPose.armLift(), "fall is caught below rest before easing home");
        equal(-6, anticipation.armTiltDegrees(), "loaded forearm follows the weight");
        equal(8, lift.armTiltDegrees(), "lift rolls the forearm with the bag");
        equal(-3, catchPose.armTiltDegrees(), "catch cushions the returning weight");
        }
        int risingToFalling = 0; double previousLift = 0, previousSlope = 0;
        for (int frame = 0; frame <= 240; frame++) {
            double time = landing + frame / 120.0;
            var sample = pose(Kind.POUCH, time, settings);
            equal(0, sample.itemLift(), "after landing bag remains supported during both hefts");
            equal(0, sample.itemSpinDegrees(), "bag has no independent spin that tears its grip");
            double slope = sample.armLift() - previousLift;
            if (previousSlope > 0 && slope < 0 && previousLift > .09) risingToFalling++;
            if (slope != 0) previousSlope = slope;
            previousLift = sample.armLift();
        }
        equal(2, risingToFalling, "exactly two visible heft peaks follow the initial landing");
        for (double boundary : new double[]{0, .12 * landing, .80 * landing, landing,
                landing + .22, landing + .50, landing + .82, landing + 1,
                landing + 1.22, landing + 1.50, landing + 1.82, landing + 2}) {
            double e = 1e-6;
            var a = pose(Kind.POUCH, Math.max(0, boundary - e), settings);
            var b = pose(Kind.POUCH, boundary, settings);
            var c = pose(Kind.POUCH, boundary + e, settings);
            check(Math.abs(a.itemLift() - c.itemLift()) < 1e-5 && Math.abs(a.armLift() - c.armLift()) < 1e-5,
                    "toss, landing, both hefts and stop join without position jumps");
            check(Math.abs((b.itemLift() - a.itemLift()) / e - (c.itemLift() - b.itemLift()) / e) < .001,
                    "toss release and landing are velocity continuous");
            check(Math.abs((b.armLift() - a.armLift()) / e - (c.armLift() - b.armLift()) / e) < .001,
                    "hand push, catches and repeat join with continuous velocity");
        }
        for (double invalid : new double[]{Double.NaN, .34, 1.51}) {
            boolean rejected = false;
            try { new Settings(1, 1.05, .35, .18, .10, invalid, .22); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid pouch toss duration rejected");
        }
        for (double invalid : new double[]{Double.POSITIVE_INFINITY, .059, .401}) {
            boolean rejected = false;
            try { new Settings(1, 1.05, .35, .18, .10, .70, invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid pouch toss height rejected");
        }
    }
    private static EquipKey key(String item, int slot) { return new EquipKey(item, slot, "same components", false); }
    private static void equal(double expected, double actual, String message) { near(expected, actual, 1e-9, message); }
    private static void near(double expected, double actual, double epsilon, String message) { check(Double.isFinite(actual) && Math.abs(expected - actual) <= epsilon, message + ": " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

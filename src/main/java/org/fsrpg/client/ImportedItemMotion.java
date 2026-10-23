package org.fsrpg.client;

import java.util.Objects;

/** Cosmetic, render-time equip trajectories; no Minecraft or combat dependencies. */
public final class ImportedItemMotion {
    public enum Kind { NONE, POUCH, COIN, BADGE }

    public record Settings(double pouchSeconds, double coinSeconds, double badgeSeconds,
                           double tossHeight, double heftHeight) {
        public static final Settings DEFAULT = new Settings(1.0, 1.05, .35, .18, .10);
        public Settings {
            bounded(pouchSeconds, .5, 2, "pouch seconds");
            bounded(coinSeconds, .45, 2, "coin seconds");
            bounded(badgeSeconds, .15, 1, "badge seconds");
            bounded(tossHeight, .04, .3, "toss height");
            bounded(heftHeight, 0, .18, "heft height");
        }
        public double duration(Kind kind) {
            return switch (kind) { case POUCH -> pouchSeconds; case COIN -> coinSeconds; case BADGE -> badgeSeconds; default -> 0; };
        }
    }

    /** Lift is in blocks; spin is in degrees. Coin's settled 360 degrees is identity. */
    public record Pose(double armLift, double armTiltDegrees, double itemLift, double itemSpinDegrees,
                       double drawProgress, double elapsedSeconds, boolean settled) {}

    /** Identity deliberately has no stack count; slot distinguishes equal items in two slots. */
    public record EquipKey(String itemId, int slot, Object components, boolean leftArm) {}

    /** Vanilla keeps the old stack for several lowering frames. Its last drawn
     * pose belongs to that old visual, independently of the next equip state. */
    public static final class HeldPose {
        private Object visualKey;
        private Pose pose;
        public void remember(Object key, Pose value) { visualKey = key; pose = Objects.requireNonNull(value); }
        public Pose outgoing(Object key, Kind kind) {
            return pose != null && Objects.equals(visualKey, key) ? pose : rest(kind, 0);
        }
        public void reset() { visualKey = null; pose = null; }
    }

    public static final class State {
        private EquipKey key;
        private Kind kind = Kind.NONE;
        private long serial;
        private boolean pending;
        private double started, lastElapsed;
        private Pose last = rest(Kind.NONE, 0);

        public boolean select(EquipKey next, Kind nextKind) {
            Objects.requireNonNull(nextKind);
            if (Objects.equals(key, next) && kind == nextKind) return false;
            key = next;
            kind = nextKind;
            pending = nextKind != Kind.NONE;
            if (pending) serial++;
            lastElapsed = 0;
            last = rest(nextKind, 0);
            return true;
        }

        /** A cached outgoing stack can still render while vanilla lowers the arm.
         * Start the one shot only when the incoming hand is visibly raising. */
        public Pose sample(double seconds, boolean ready, boolean animate, Settings settings) {
            if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("finite nonnegative clock required");
            Objects.requireNonNull(settings);
            if (kind == Kind.NONE) return last = rest(kind, 0);
            if (!animate) {
                pending = false;
                lastElapsed = settings.duration(kind);
                return last = rest(kind, lastElapsed);
            }
            if (pending) {
                if (!ready) return last = new Pose(0, 0, 0, 0, 0, 0, false);
                pending = false;
                started = seconds;
                lastElapsed = 0;
            }
            // Presentation clocks can be reset independently by a resource reload.
            // Never rewind a running equip or replay it because of that correction.
            double elapsed = Math.max(lastElapsed, Math.max(0, seconds - started));
            lastElapsed = elapsed;
            return last = pose(kind, elapsed, settings);
        }

        public void reset() {
            key = null; kind = Kind.NONE; serial = 0; pending = false;
            started = lastElapsed = 0; last = rest(Kind.NONE, 0);
        }
        public long serial() { return serial; }
        public Kind kind() { return kind; }
        public EquipKey key() { return key; }
        public Pose last() { return last; }
        public boolean pending() { return pending; }
    }

    public static Pose pose(Kind kind, double elapsed, Settings settings) {
        if (!Double.isFinite(elapsed) || elapsed < 0) throw new IllegalArgumentException("finite nonnegative elapsed required");
        double duration = settings.duration(kind);
        if (kind == Kind.NONE || elapsed >= duration) return rest(kind, elapsed);
        double phase = elapsed / duration;
        double draw = smooth(phase / (kind == Kind.BADGE ? 1 : .22));
        double arm = 0, tilt = 0, lift = 0, spin = 0;
        if (kind == Kind.POUCH) {
            // Weight first loads the hand, then accelerates upward, drops into
            // a softer catch, and settles. Each boundary has zero velocity.
            if (phase < .22) {
                arm = blend(0, -.4, phase / .22); tilt = blend(0, -6, phase / .22);
            } else if (phase < .50) {
                arm = blend(-.4, 1, (phase - .22) / .28); tilt = blend(-6, 8, (phase - .22) / .28);
            } else if (phase < .82) {
                arm = blend(1, -.25, (phase - .50) / .32); tilt = blend(8, -3, (phase - .50) / .32);
            } else {
                arm = blend(-.25, 0, (phase - .82) / .18); tilt = blend(-3, 0, (phase - .82) / .18);
            }
            arm *= settings.heftHeight;
            tilt *= settings.heftHeight / .10;
        } else if (kind == Kind.COIN) {
            double flight = clamp((phase - .22) / .56);
            lift = settings.tossHeight * bump(flight);
            spin = 360 * smooth(flight);
            arm = .018 * bump(phase / .30) - .012 * bump((phase - .75) / .25);
        }
        return new Pose(arm, tilt, lift, spin, draw, elapsed, false);
    }

    private static Pose rest(Kind kind, double elapsed) {
        return new Pose(0, 0, 0, kind == Kind.COIN ? 360 : 0, 1, elapsed, true);
    }
    private static double blend(double from, double to, double t) { return from + (to - from) * smooth(t); }
    private static double bump(double value) {
        double x = clamp(value);
        return 16 * x * x * (1 - x) * (1 - x);
    }
    private static double smooth(double value) {
        double x = clamp(value);
        return x * x * x * (10 + x * (-15 + 6 * x));
    }
    private static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
    private static void bounded(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(name + " outside finite bounds");
    }
    private ImportedItemMotion() {}
}

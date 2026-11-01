package org.fsrpg.client;

public final class SwordTravelPresentationTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double value, double expected, String message) {
        check(Math.abs(value - expected) < 1e-10, message + ": " + value + " != " + expected);
    }
    private static double length(SwordTravelPresentation.Offset value) {
        return Math.hypot(Math.hypot(value.x(), value.y()), value.z());
    }

    private static void frameRate(int fps) {
        var state = new SwordTravelPresentation.State();
        double endpoint = 0, previousRoot = Double.NEGATIVE_INFINITY;
        int packet = 0;
        // Actual server steps occur at 20 Hz; render samples use independent frame rates.
        for (int frame = 0; frame <= fps * 2; frame++) {
            double tick = frame * 20.0 / fps;
            while (packet <= (int) Math.floor(tick + 1e-10)) {
                double priorRoot = endpoint + state.sample(packet).z();
                endpoint += .04;
                check(state.accept(7, 100 + packet, packet, 0, 0, .04, true), "step accepted");
                near(endpoint + state.sample(packet).z(), priorRoot, "packet cannot jump the visual root");
                packet++;
            }
            var offset = state.sample(tick);
            double root = endpoint + offset.z();
            near(root, .04 * tick, fps + " Hz continuous world root");
            check(root >= previousRoot - 1e-10, "no backwards root at tick boundary");
            near(offset.x(), 0, "no lateral invented motion");
            // Native WASD motion is independent and must not be smoothed a second time.
            near(endpoint + .015 * tick + offset.z(), .055 * tick, "native locomotion preserved");
            check(offset.equals(state.sample(tick)), "camera and avatar sample the same offset");
            previousRoot = root;
        }
    }

    public static void main(String[] args) {
        frameRate(30);
        frameRate(60);
        frameRate(120);

        var state = new SwordTravelPresentation.State();
        check(state.accept(1, 10, 0, .06, .02, -.03, true), "first packet");
        near(state.sample(0).x(), -.06, "first packet uses actual delta without guessing origin");
        near(state.sample(.5).y(), -.01, "vertical motion shares clock");
        near(state.sample(.5).z(), .015, "signed world motion shares clock");
        check(state.accept(1, 11, .5, .02, 0, 0, true), "early next step");
        near(state.sample(.5).x(), -.05, "new debt rebases current interpolated debt");
        near(state.sample(1).x(), -.025, "new segment is linear");
        near(length(state.sample(1.5)), 0, "no receipt settles exactly once");
        near(length(state.sample(2.2)), 0, "next client tick cannot rewind interpolation");

        var before = state.sample(1);
        check(!state.accept(1, 11, 1, .1, 0, 0, true), "duplicate rejected");
        check(!state.accept(0, 12, 1, .1, 0, 0, true), "old sequence rejected");
        check(!state.accept(2, 9, 1, .1, 0, 0, true), "old tick rejected");
        check(!state.accept(2, 12, 1, Double.NaN, 0, 0, true), "invalid movement rejected");
        check(!state.accept(2, 12, Double.POSITIVE_INFINITY, 0, 0, 0, true), "invalid clock rejected");
        check(before.equals(state.sample(1)), "rejected packets cannot alter visual state");

        state.reset();
        check(state.accept(1, 1, 0, .1, 0, 0, true), "reset permits new world numbering");
        check(state.accept(1, 2, .25, 0, 0, 0, false), "cancel packet accepted");
        near(state.sample(.5).x(), -.05, "cancel does not restart debt lifetime");
        near(length(state.sample(1)), 0, "cancel settles without a pose snap");
        check(!state.accept(1, 3, .5, .1, 0, 0, true), "closed action cannot reopen");
        check(state.accept(2, 3, .5, .03, 0, 0, true), "new action can begin before prior debt settles");
        near(state.sample(.5).x(), -.08, "combo boundary preserves remaining visual debt");
        check(state.accept(2, 4, .75, .01, 0, 0, false), "final packet can contain actual motion");
        near(state.sample(.75).x(), -.07, "final step receives same smoothing");
        near(length(state.sample(1.75)), 0, "final step fully releases native presentation");

        state.reset();
        for (int i = 0; i < 20; i++) {
            check(state.accept(4, i, 0, .1, .1, .1, true), "burst receipt accepted");
            check(length(state.sample(0)) <= SwordTravelPresentation.MAX_DEBT_METERS + 1e-10, "burst debt is bounded");
        }
        near(length(state.sample(1)), 0, "bounded burst also expires after one tick");
        check(state.accept(4, 21, 1.1, 3, 0, 0, true), "discontinuity receipt accepted");
        near(length(state.sample(1.1)), 0, "external-size movement cannot trail across terrain");
        check(state.accept(5, 22, 2, .1, 0, 0, true), "resume after discontinuity");
        check(state.accept(5, 23, 0, .1, 0, 0, true), "backward clock safely clears old interpolation");
        near(length(state.sample(0)), 0, "clock reset cannot recreate stale offset");
        state.reset();
        near(length(state.sample(100)), 0, "context reset removes all visual debt");
        System.out.println("SwordTravelPresentationTest passed " + checks + " checks");
    }
}

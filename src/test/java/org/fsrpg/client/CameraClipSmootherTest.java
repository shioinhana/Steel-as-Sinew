package org.fsrpg.client;

/** Camera wall distance must snap inward and recover smoothly; invalid decimals are rejected. */
public final class CameraClipSmootherTest {
    private static int checks;

    public static void main(String[] args) {
        firstSampleUsesTarget();
        obstacleSnapsInwardImmediately();
        releaseRecoversSmoothly();
        invalidValuesRejected();
        resetRestoresFullDistance();
        System.out.println("CameraClipSmootherTest passed: " + checks + " checks");
    }

    private static void firstSampleUsesTarget() {
        var smoother = new CameraClipSmoother();
        equal(1, smoother.advance(1, .008));
        var near = new CameraClipSmoother();
        equal(.4, near.advance(.4, .008), "first clipped sample is already safe");
    }

    private static void obstacleSnapsInwardImmediately() {
        var smoother = new CameraClipSmoother();
        smoother.advance(1, .008);
        equal(.35, smoother.advance(.35, .008), "closer obstacle snaps before drawing");
        equal(.1, smoother.advance(.1, .008), "repeated step-in keeps snapping");
    }

    private static void releaseRecoversSmoothly() {
        var smoother = new CameraClipSmoother();
        smoother.advance(1, .008);
        smoother.advance(.3, .008);
        double first = smoother.advance(1, .008);
        double second = smoother.advance(1, .008);
        check(first > .3 && first < .7, "release starts easing out rather than jumping: " + first);
        check(second > first && second < 1, "release continues smoothly: " + first + " -> " + second);
        double longFrame = smoother.advance(1, .25);
        check(longFrame > second && longFrame <= 1, "larger frame time catches up but never overshoots");
        for (int i = 0; i < 200; i++) smoother.advance(1, .008);
        check(smoother.fraction() > .999, "long release settles at the full offset");
    }

    private static void invalidValuesRejected() {
        var smoother = new CameraClipSmoother();
        for (double target : new double[]{-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { smoother.advance(target, .008); } catch (RuntimeException expected) { rejected = true; }
            check(rejected, "invalid target rejected: " + target);
        }
        for (double dt : new double[]{-.01, 1.01, Double.NaN}) {
            boolean rejected = false;
            try { smoother.advance(1, dt); } catch (RuntimeException expected) { rejected = true; }
            check(rejected, "invalid frame time rejected: " + dt);
        }
    }

    private static void resetRestoresFullDistance() {
        var smoother = new CameraClipSmoother();
        smoother.advance(.2, .008);
        smoother.reset();
        equal(1, smoother.advance(1, .008), "camera mode changes restore the full desired offset");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(double expected, double actual) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-9, expected + " != " + actual);
    }

    private static void equal(double expected, double actual, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-9, message + ": " + expected + " != " + actual);
    }
}

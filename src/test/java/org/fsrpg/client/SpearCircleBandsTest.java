package org.fsrpg.client;

import java.util.Random;

/** Analytic circle intersections, independently checked against a dense angular grid. */
public final class SpearCircleBandsTest {
    private static int checks;

    public static void main(String[] args) {
        near(0, nearest(0, 1, 0, .5, 1, 0, 0, 0, -1, 1), "feasible zero remains exact");
        check(Double.doubleToRawLongBits(nearest(0, 1, 0, .5, 1, 0, 0, 0, -1, 1)) == 0,
                "zero is positive, unmodified zero");
        near(Math.acos(.6), nearest(0, 1, 0, .1, .6, 0, 0, 1, .65, .9), "overlapping bands");
        near(-Math.PI + Math.asin(.25), nearest(0, 1, 0, -1, -.8, 0, 0, 1, -.25, -.1), "arc across negative pi");
        near(5 * Math.PI / 6, nearest(0, 1, 0, -1, .5, 0, 0, 1, 0, .5), "correct component of split circle");
        near(Math.PI / 3, nearest(0, 1, 0, .5, .5, 0, 0, 1, .8, .9), "isolated intersection");
        near(Math.PI / 2, nearest(0, 1, 0, 0, 0, 0, 0, 1, 1, 1), "two tangent bands");
        near(Math.PI, Math.abs(nearest(0, 1, 0, -1, -1, 0, 0, 1, -.1, .1)), "pi tangent and wrapping");
        near(1, nearest(0, 1, 0, Math.cos(1.000001), Math.cos(1), 0, 0, 1, .5, 1), "arc narrower than grid");
        noSolution(nearest(0, 0, 1, .5, 1, 0, 0, 1, -1, -.5), "disjoint bands");
        noSolution(nearest(2, 0, 0, 0, 1, 0, 1, 0, -1, 1), "impossible constant");
        noSolution(nearest(0, 1, 0, 2, 3, 0, 1, 0, -1, 1), "outside circle range");
        noSolution(nearest(0, 1, 0, 1, -1, 0, 1, 0, -1, 1), "reversed interval");
        noSolution(nearest(Double.NaN, 1, 0, -1, 1, 0, 1, 0, -1, 1), "nonfinite coefficient");
        double[] split = candidates(0, 1, 0, .4, .6, 0, 0, 0, -1, 1);
        check(split.length <= 5 && split.length >= 2, "bounded disconnected-arc representatives");
        boolean positive = false, negative = false;
        for (double angle : split) { positive |= angle > 0; negative |= angle < 0; }
        check(positive && negative, "both disconnected arcs represented");
        double[] circle = candidates(0, 0, 0, -1, 1, 0, 0, 0, -1, 1);
        check(circle.length == 5 && circle[0] == 0, "whole-circle coverage preserves zero");
        check(java.util.Arrays.stream(circle).anyMatch(value -> Math.abs(value - Math.PI / 2) < 1e-6)
                && java.util.Arrays.stream(circle).anyMatch(value -> Math.abs(value + Math.PI / 2) < 1e-6),
                "long arcs include interior directions across branch cut");
        check(candidates(0, 0, 1, .5, 1, 0, 0, 1, -1, -.5).length == 0, "empty candidate set");

        var random = new Random(0x5350454152434952L);
        int finite = 0, empty = 0;
        final int samples = 3600;
        final double step = 2 * Math.PI / samples;
        for (int test = 0; test < 300; test++) {
            double[] bands = new double[10];
            for (int band = 0; band < 2; band++) {
                int i = band * 5;
                bands[i] = (random.nextDouble() - .5) * 20000;
                double radius = 1 + random.nextDouble() * 10000;
                double phase = random.nextDouble() * 2 * Math.PI;
                bands[i + 1] = radius * Math.cos(phase);
                bands[i + 2] = radius * Math.sin(phase);
                double center = (random.nextDouble() * 3 - 1.5) * radius;
                double halfWidth = (.02 + random.nextDouble()) * radius;
                bands[i + 3] = bands[i] + center - halfWidth;
                bands[i + 4] = bands[i] + center + halfWidth;
            }
            double angle = nearest(bands);
            double[] candidates = candidates(bands);
            check(candidates.length <= 5, "candidate budget, case " + test);
            check((candidates.length == 0) == Double.isNaN(angle), "candidate emptiness, case " + test);
            if (candidates.length > 0) check(candidates[0] == angle, "nearest candidate remains first, case " + test);
            for (int i = 0; i < candidates.length; i++) {
                check(valid(bands, candidates[i], 1e-7), "illegal representative, case " + test);
                for (int j = 0; j < i; j++) check(Math.abs(Math.IEEEremainder(candidates[i] - candidates[j], 2 * Math.PI)) >= 1e-9,
                        "duplicate physical representative, case " + test);
            }
            double gridNearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i <= samples; i++) {
                double sampled = -Math.PI + i * step;
                if (valid(bands, sampled, 0)) gridNearest = Math.min(gridNearest, Math.abs(sampled));
            }
            if (Double.isNaN(angle)) {
                empty++;
                check(!Double.isFinite(gridNearest), "analytic miss of legal grid point, case " + test);
            } else {
                finite++;
                check(angle >= -Math.PI && angle <= Math.PI, "wrapped result, case " + test);
                check(valid(bands, angle, 1e-7), "illegal analytic result, case " + test);
                check(!Double.isFinite(gridNearest) || Math.abs(angle) <= gridNearest + step + 2e-7,
                        "analytic result farther than nearest grid point, case " + test);
                // Conversely, for non-isolated arcs the next grid point must be
                // within one grid step of the true nearest boundary.
                check(!Double.isFinite(gridNearest) || gridNearest <= Math.abs(angle) + step + 2e-7,
                        "analytic result skipped a closer arc, case " + test);
            }
            if (valid(bands, 0, 0)) check(angle == 0, "feasible random zero preserved, case " + test);
        }
        check(finite > 50 && empty > 50, "random coverage contains both legal and disjoint intersections");
        System.out.println("SpearCircleBandsTest passed: " + checks + " checks, 300 random intersections, "
                + finite + " finite / " + empty + " empty, 1,080,300 independent grid points");
    }

    private static double nearest(double... values) {
        return SpearCircleBands.nearest(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9]);
    }

    private static double[] candidates(double... values) {
        return SpearCircleBands.candidates(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9]);
    }

    private static boolean valid(double[] bands, double angle, double tolerance) {
        for (int i = 0; i < 10; i += 5) {
            double value = bands[i] + bands[i + 1] * Math.cos(angle) + bands[i + 2] * Math.sin(angle);
            if (value < bands[i + 3] - tolerance || value > bands[i + 4] + tolerance) return false;
        }
        return true;
    }

    private static void near(double expected, double actual, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= 2e-7,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void noSolution(double actual, String message) {
        check(Double.isNaN(actual), message + ": got " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

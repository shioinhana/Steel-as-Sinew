package org.fsrpg.client;

import java.util.Arrays;

/** Exact circle-band intersection used by the finite-target spear bridge. */
final class SpearCircleBands {
    private static final double PI = Math.PI;
    private static final double TAU = 2 * PI;
    private static final double INSET = 1e-7;

    private SpearCircleBands() {}

    /**
     * Find the angle nearest zero for which both low <= base + cos*cos(theta)
     * + sin*sin(theta) <= high bands hold. A feasible zero remains exactly zero.
     * The search partitions at the at most eight analytic boundary roots; it
     * never samples a grid. Nonempty arcs are inset slightly to survive float FK.
     */
    static double nearest(double firstBase, double firstCos, double firstSin, double firstLow, double firstHigh,
                          double secondBase, double secondCos, double secondSin, double secondLow, double secondHigh) {
        var first = new Band(firstBase, firstCos, firstSin, firstLow, firstHigh);
        var second = new Band(secondBase, secondCos, secondSin, secondLow, secondHigh);
        if (!first.possible() || !second.possible()) return Double.NaN;
        if (first.contains(0, false) && second.contains(0, false)) return 0;

        double[] boundaries = new double[10];
        boundaries[0] = -PI;
        boundaries[1] = PI;
        int count = first.boundaries(boundaries, 2);
        count = second.boundaries(boundaries, count);
        Arrays.sort(boundaries, 0, count);

        double best = Double.NaN;
        double bestDistance = Double.POSITIVE_INFINITY;
        // Closed intersections can contain only a tangent point, with no legal
        // open interval. Keep those boundary points as well as nonempty arcs.
        for (int i = 0; i < count; i++) {
            double angle = boundaries[i];
            if (Math.abs(angle) < bestDistance && first.contains(angle, true) && second.contains(angle, true)) {
                best = angle;
                bestDistance = Math.abs(angle);
            }
        }
        for (int i = 1; i < count; i++) {
            double low = boundaries[i - 1], high = boundaries[i];
            if (high <= low) continue;
            double middle = low + (high - low) * .5;
            if (!first.contains(middle, false) || !second.contains(middle, false)) continue;
            double edge = low > 0 ? low : high < 0 ? high : 0;
            double distance = Math.abs(edge);
            if (distance > bestDistance + 1e-12) continue;
            double inset = Math.min(INSET, (high - low) * .25);
            double candidate = edge == 0 ? 0 : edge + Math.copySign(inset, middle - edge);
            if (!first.contains(candidate, true) || !second.contains(candidate, true)) candidate = middle;
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }

    /**
     * Up to five legal representatives: nearest first, then each disconnected
     * arc, then separated inset endpoints/midpoints. Collision testing can thus
     * inspect both sides of an arc without increasing its fixed trial budget.
     */
    static double[] candidates(double firstBase, double firstCos, double firstSin, double firstLow, double firstHigh,
                               double secondBase, double secondCos, double secondSin, double secondLow, double secondHigh) {
        double nearest = nearest(firstBase, firstCos, firstSin, firstLow, firstHigh,
                secondBase, secondCos, secondSin, secondLow, secondHigh);
        if (Double.isNaN(nearest)) return new double[0];
        var first = new Band(firstBase, firstCos, firstSin, firstLow, firstHigh);
        var second = new Band(secondBase, secondCos, secondSin, secondLow, secondHigh);
        double[] boundaries = new double[10];
        boundaries[0] = -PI; boundaries[1] = PI;
        int count = first.boundaries(boundaries, 2);
        count = second.boundaries(boundaries, count);
        Arrays.sort(boundaries, 0, count);
        double[] lows = new double[10], highs = new double[10];
        int arcs = 0;
        for (int i = 1; i < count; i++) {
            double low = boundaries[i - 1], high = boundaries[i];
            if (high <= low) continue;
            double middle = low + (high - low) * .5;
            if (!first.contains(middle, false) || !second.contains(middle, false)) continue;
            if (arcs > 0 && low <= highs[arcs - 1]) highs[arcs - 1] = high;
            else { lows[arcs] = low; highs[arcs++] = high; }
        }
        // Add isolated closed intersections not belonging to an open arc.
        for (int i = 0; i < count; i++) {
            double point = boundaries[i];
            if (!first.contains(point, true) || !second.contains(point, true)) continue;
            boolean covered = false;
            for (int arc = 0; arc < arcs; arc++) if (point >= lows[arc] && point <= highs[arc]) { covered = true; break; }
            if (!covered) { lows[arcs] = point; highs[arcs++] = point; }
        }
        // Sorting by the nearest point puts a distinct legal branch before any
        // additional samples of the first branch.
        for (int i = 0; i < arcs; i++) for (int j = i + 1; j < arcs; j++) {
            if (Math.abs(closest(lows[j], highs[j])) < Math.abs(closest(lows[i], highs[i]))) {
                double swap = lows[i]; lows[i] = lows[j]; lows[j] = swap;
                swap = highs[i]; highs[i] = highs[j]; highs[j] = swap;
            }
        }
        double[] selected = new double[5];
        selected[0] = nearest;
        int selectedCount = 1;
        for (int arc = 0; arc < arcs && selectedCount < 5; arc++) {
            selectedCount = append(selected, selectedCount, closest(lows[arc], highs[arc]));
        }
        double[] pool = new double[50];
        int poolCount = 0;
        for (int arc = 0; arc < arcs; arc++) {
            double low = lows[arc], high = highs[arc];
            double inset = Math.min(INSET, (high - low) * .25);
            pool[poolCount++] = low + inset;
            pool[poolCount++] = high - inset;
            pool[poolCount++] = low + (high - low) * .5;
            // A full or very long arc needs interior coverage even when its
            // two endpoints are the same physical direction across the cut.
            pool[poolCount++] = low + (high - low) * .25;
            pool[poolCount++] = low + (high - low) * .75;
        }
        while (selectedCount < 5) {
            double best = Double.NaN, greatestGap = 1e-9;
            for (int i = 0; i < poolCount; i++) {
                double gap = Double.POSITIVE_INFINITY;
                for (int j = 0; j < selectedCount; j++) gap = Math.min(gap, Math.abs(Math.IEEEremainder(pool[i] - selected[j], TAU)));
                if (gap > greatestGap) { greatestGap = gap; best = pool[i]; }
            }
            if (Double.isNaN(best)) break;
            selected[selectedCount++] = best;
        }
        return Arrays.copyOf(selected, selectedCount);
    }

    private static double closest(double low, double high) {
        double inset = Math.min(INSET, (high - low) * .25);
        return low > 0 ? low + inset : high < 0 ? high - inset : 0;
    }

    private static int append(double[] selected, int count, double candidate) {
        for (int i = 0; i < count; i++) if (Math.abs(Math.IEEEremainder(candidate - selected[i], TAU)) < 1e-9) return count;
        selected[count] = candidate;
        return count + 1;
    }

    private record Band(double base, double cosine, double sine, double low, double high) {
        boolean possible() {
            if (!Double.isFinite(base) || !Double.isFinite(cosine) || !Double.isFinite(sine)
                    || !Double.isFinite(low) || !Double.isFinite(high) || low > high) return false;
            double radius = Math.hypot(cosine, sine);
            return Double.isFinite(radius) && low <= base + radius && high >= base - radius;
        }

        boolean contains(double angle, boolean boundary) {
            double value = base + cosine * Math.cos(angle) + sine * Math.sin(angle);
            double tolerance = boundary ? 64 * Math.ulp(Math.max(1,
                    Math.max(Math.abs(base) + Math.hypot(cosine, sine), Math.max(Math.abs(low), Math.abs(high))))) : 0;
            return value >= low - tolerance && value <= high + tolerance;
        }

        int boundaries(double[] angles, int count) {
            double radius = Math.hypot(cosine, sine);
            if (radius == 0) return count;
            double phase = Math.atan2(sine, cosine);
            count = roots(angles, count, phase, (low - base) / radius);
            return roots(angles, count, phase, (high - base) / radius);
        }

        private static int roots(double[] angles, int count, double phase, double value) {
            if (value < -1 || value > 1) return count;
            double offset = Math.acos(value);
            angles[count++] = Math.IEEEremainder(phase + offset, TAU);
            angles[count++] = Math.IEEEremainder(phase - offset, TAU);
            return count;
        }
    }
}

package org.fsrpg.progression;

/** Pure form of the old autonomous ground-distance filter. Client positions alone never train. */
public final class GroundTrainingRules {
    public static final double MAX_SAMPLE_METRES = 1.5;
    public static final double MIN_INPUT_ALIGNMENT = .5;
    private GroundTrainingRules() {}
    public static double distance(boolean beforeEligible, boolean afterEligible, boolean sameDimension,
            long tickDelta, boolean forced, double dx, double dz, double inputX, double inputZ) {
        if (!beforeEligible || !afterEligible || !sameDimension || forced || tickDelta < 0 || tickDelta > 1
                || !Double.isFinite(dx) || !Double.isFinite(dz) || !Double.isFinite(inputX) || !Double.isFinite(inputZ)) return 0;
        double metres = Math.hypot(dx, dz), input = Math.hypot(inputX, inputZ);
        if (metres <= 0 || metres >= MAX_SAMPLE_METRES || input < 1e-4) return 0;
        double alignment = (dx / metres) * (inputX / input) + (dz / metres) * (inputZ / input);
        return alignment >= MIN_INPUT_ALIGNMENT ? metres : 0;
    }
}

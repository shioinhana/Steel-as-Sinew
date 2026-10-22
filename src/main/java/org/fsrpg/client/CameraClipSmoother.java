package org.fsrpg.client;

/**
 * Third-person camera distance response. An obstacle that becomes nearer snaps immediately (the
 * camera must never enter a block), while released distance eases back so running past a wall does
 * not produce a one-frame camera jump. Pure timing, so it is validated without a game client.
 */
public final class CameraClipSmoother {
    private double fraction = 1;
    private boolean initialized;

    /** @return the fraction of the desired camera offset that may be used this frame. */
    public double advance(double target, double frameSeconds) {
        if (!Double.isFinite(target) || target < 0 || target > 1)
            throw new IllegalArgumentException("camera clip fraction outside [0,1]");
        if (!Double.isFinite(frameSeconds) || frameSeconds < 0 || frameSeconds > 1)
            throw new IllegalArgumentException("camera clip frame time outside [0,1] s");
        if (!initialized) {
            initialized = true;
            fraction = target;
            return fraction;
        }
        if (target <= fraction) fraction = target;
        else fraction += (target - fraction) * (1 - Math.exp(-frameSeconds / .10));
        return fraction;
    }

    public double fraction() { return fraction; }
    public void reset() { fraction = 1; initialized = false; }
}

package org.fsrpg.client;

/** Bounded local camera coordinates; positive horizontal offset is camera-right. */
public record CameraProfilePosition(double side, double height, double distance) {
    public CameraProfilePosition {
        if (!Double.isFinite(side) || !Double.isFinite(height) || !Double.isFinite(distance))
            throw new IllegalArgumentException("Non-finite camera profile");
    }
    public CameraProfilePosition adjust(double horizontal, double vertical, double step, boolean mounted) {
        if (!Double.isFinite(horizontal) || !Double.isFinite(vertical) || !Double.isFinite(step) || step < 0)
            throw new IllegalArgumentException("Invalid camera adjustment");
        return new CameraProfilePosition(Math.clamp(side + horizontal * step, -1.5, 1.5),
                Math.clamp(height + vertical * step, -.5, mounted ? 1.5 : 1.0), distance);
    }
    public CameraProfilePosition forMounted(double extraDistance) {
        if (!Double.isFinite(extraDistance) || extraDistance < 0) throw new IllegalArgumentException("Invalid mounted distance");
        return new CameraProfilePosition(side, height, Math.clamp(distance + extraDistance, .5, 8));
    }
}

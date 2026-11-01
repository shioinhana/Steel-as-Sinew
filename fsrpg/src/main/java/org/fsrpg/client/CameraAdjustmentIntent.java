package org.fsrpg.client;

/** Only the dedicated modifier plus a direction owns camera input. */
public final class CameraAdjustmentIntent {
    public record Decision(boolean adjusting, double horizontal, double vertical) {}
    public Decision sample(boolean eligible, boolean modifier, boolean up, boolean down, boolean left, boolean right) {
        if (!eligible || !modifier || !(up || down || left || right)) return new Decision(false, 0, 0);
        double x = (right ? 1 : 0) - (left ? 1 : 0), y = (up ? 1 : 0) - (down ? 1 : 0);
        double length = Math.max(1, Math.hypot(x, y));
        return new Decision(true, x / length, y / length);
    }
}

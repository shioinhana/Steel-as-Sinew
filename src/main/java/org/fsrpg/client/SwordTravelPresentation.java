package org.fsrpg.client;

/**
 * Render-only debt for the server's collision-clipped sword steps. Add the sampled
 * offset to the native render root and camera eye; never write it to an entity.
 */
public final class SwordTravelPresentation {
    public static final double SETTLE_TICKS = 1;
    public static final double MAX_DEBT_METERS = .5;
    public static final double DISCONTINUITY_METERS = 2;

    public record Offset(double x, double y, double z) {
        public static final Offset ZERO = new Offset(0, 0, 0);
    }

    private SwordTravelPresentation() {}

    public static final class State {
        private long sequence = Long.MIN_VALUE, serverTick = Long.MIN_VALUE;
        private long closedSequence = Long.MIN_VALUE;
        private double receivedAt = Double.NaN;
        private Offset debt = Offset.ZERO;

        /**
         * applied XYZ is this packet's actual server movement in world metres,
         * not requested travel, a position endpoint, or a cumulative distance.
         * Call once after accepting the matching authoritative position receipt.
         */
        public boolean accept(long nextSequence, long nextServerTick, double renderTick,
                              double appliedX, double appliedY, double appliedZ, boolean active) {
            if (!Double.isFinite(renderTick) || !Double.isFinite(appliedX)
                    || !Double.isFinite(appliedY) || !Double.isFinite(appliedZ)
                    || nextSequence < sequence || nextServerTick <= serverTick
                    || (active && nextSequence == closedSequence)) return false;
            Offset previous = sample(renderTick);
            sequence = nextSequence;
            serverTick = nextServerTick;
            if (!active) closedSequence = nextSequence;

            double stepLength = Math.hypot(Math.hypot(appliedX, appliedY), appliedZ);
            if (stepLength > DISCONTINUITY_METERS || (!Double.isNaN(receivedAt) && renderTick < receivedAt)) {
                debt = Offset.ZERO;
                receivedAt = renderTick;
                return true;
            }
            // A stop/blocked receipt must not restart the remaining visual settle.
            if (stepLength == 0) return true;
            double x = previous.x - appliedX;
            double y = previous.y - appliedY;
            double z = previous.z - appliedZ;
            double length = Math.hypot(Math.hypot(x, y), z);
            double scale = length > MAX_DEBT_METERS ? MAX_DEBT_METERS / length : 1;
            debt = new Offset(x * scale, y * scale, z * scale);
            receivedAt = renderTick;
            return true;
        }

        /** Pure sampling: camera, skin, armor and diagnostics may call in any order. */
        public Offset sample(double renderTick) {
            if (!Double.isFinite(renderTick) || Double.isNaN(receivedAt) || renderTick < receivedAt)
                return Offset.ZERO;
            double remaining = 1 - Math.clamp((renderTick - receivedAt) / SETTLE_TICKS, 0, 1);
            if (remaining == 0) return Offset.ZERO;
            return new Offset(debt.x * remaining, debt.y * remaining, debt.z * remaining);
        }

        /** Clear on world/player changes, respawn, external teleport or clock reset. */
        public void reset() {
            sequence = serverTick = closedSequence = Long.MIN_VALUE;
            receivedAt = Double.NaN;
            debt = Offset.ZERO;
        }
    }
}

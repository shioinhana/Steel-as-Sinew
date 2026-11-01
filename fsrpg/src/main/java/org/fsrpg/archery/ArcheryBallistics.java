package org.fsrpg.archery;

import java.util.List;
import java.util.function.Function;

/**
 * Shared, Minecraft-independent geometry for real wall penetration and its landing preview.
 *
 * <p>Both the server impact handler and the client landing preview call the same traversal, so a
 * scoped shot's predicted landing point and the server's authoritative flight follow the identical
 * collision-shape geometry. Only the arrow's vanilla per-tick order lives outside this class:
 * {@link #nextVelocity} mirrors {@code AbstractArrow.tick} (move, inertia, gravity).
 */
public final class ArcheryBallistics {
    /** Distance added past a real exit face so the next tick cannot start inside that shape. */
    public static final double EPSILON = 1e-5;
    /** Hard bound shared with the configuration schema for {@code chuanTangFengBlockPierce}. */
    public static final int MAX_BLOCKS = 32;

    private ArcheryBallistics() {}

    public record Vector(double x, double y, double z) {
        public Vector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Nonfinite arrow position or direction");
        }
        public Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        public Vector scale(double scale) {
            if (!Double.isFinite(scale)) throw new IllegalArgumentException("Nonfinite arrow scale");
            return new Vector(x * scale, y * scale, z * scale);
        }
        public double length() { return Math.sqrt(x * x + y * y + z * z); }
        public Vector normalized() {
            double length = length();
            if (!Double.isFinite(length) || length < 1e-12) throw new IllegalArgumentException("Invalid arrow direction");
            return scale(1 / length);
        }
    }

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                    || minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Invalid collision box");
        }
        /** Mirrors vanilla {@code AABB.contains}: lower bound inclusive, upper bound exclusive. */
        public boolean contains(Vector point) {
            return point.x() >= minX && point.x() < maxX
                    && point.y() >= minY && point.y() < maxY
                    && point.z() >= minZ && point.z() < maxZ;
        }
    }

    /** Identity is the adapter's actual hit, so a terminal wall retains its block and hit face. */
    public record Obstacle(Object identity, Vector entry, List<Box> boxes) {
        public Obstacle {
            if (identity == null || entry == null) throw new IllegalArgumentException("Missing obstacle identity or entry");
            boxes = List.copyOf(boxes);
        }
        public boolean contains(Vector point) {
            for (Box box : boxes) if (box.contains(point)) return true;
            return false;
        }
    }

    public record Penetration(Vector position, int blocks, double speedFactor, Obstacle stoppedAt) {
        public Penetration {
            if (blocks < 0 || blocks > MAX_BLOCKS || !Double.isFinite(speedFactor) || speedFactor < 0 || speedFactor > 1)
                throw new IllegalArgumentException("Invalid penetration result");
        }
    }

    /** Vanilla 1.21.11 moves with this tick's velocity, then applies float inertia and gravity. */
    public static Vector nextVelocity(Vector velocity, boolean inWater) {
        double drag = inWater ? (double) .6f : (double) .99f;
        return new Vector(velocity.x * drag, velocity.y * drag - .05, velocity.z * drag);
    }

    /**
     * Farthest intersected surface of this block's actual collision shape, not a fixed 1.05 step.
     * A ray that starts inside the shape exits at the far face of the farthest sub-box it meets.
     */
    public static double exitDistance(Vector entry, Vector direction, List<Box> boxes) {
        double exit = Double.NaN;
        double[] origin = {entry.x, entry.y, entry.z}, ray = {direction.x, direction.y, direction.z};
        for (Box box : boxes) {
            double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
            double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
            boolean intersects = true;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(ray[axis]) < 1e-12) {
                    if (origin[axis] < min[axis] - EPSILON || origin[axis] > max[axis] + EPSILON) { intersects = false; break; }
                } else {
                    double a = (min[axis] - origin[axis]) / ray[axis], b = (max[axis] - origin[axis]) / ray[axis];
                    lower = Math.max(lower, Math.min(a, b));
                    upper = Math.min(upper, Math.max(a, b));
                    if (lower > upper + EPSILON) { intersects = false; break; }
                }
            }
            if (intersects && upper >= -EPSILON && Double.isFinite(upper)) exit = Double.isNaN(exit) ? Math.max(0, upper) : Math.max(exit, upper);
        }
        return exit;
    }

    /**
     * Cross connected solid blocks in one impact, since vanilla immediately grounds arrows that start
     * the following tick inside a collision box. {@code atExit} receives the point just past a real
     * exit face and returns the block whose collision shape contains it, or {@code null} for free
     * air. Only those contacts consume a charge; a fourth wall is returned as the stopping obstacle
     * and never traversed. A shape that cannot be exited (a graze, an interaction-shape hit or a
     * degenerate box) also stops the arrow instead of guessing, so a miss can never tunnel.
     */
    public static Penetration penetrate(Obstacle first, Vector direction, int budget, double slow,
            Function<Vector, Obstacle> atExit) {
        if (first == null || atExit == null || budget < 0 || budget > MAX_BLOCKS
                || !Double.isFinite(slow) || slow < 0 || slow > 1)
            throw new IllegalArgumentException("Invalid block penetration");
        Vector ray = direction.normalized(), position = first.entry;
        Obstacle obstacle = first;
        int crossed = 0;
        while (true) {
            if (crossed >= budget) return new Penetration(obstacle.entry, crossed, speedFactor(crossed, slow), obstacle);
            double distance = exitDistance(obstacle.entry, ray, obstacle.boxes);
            if (!Double.isFinite(distance) || distance <= 0)
                return new Penetration(obstacle.entry, crossed, speedFactor(crossed, slow), obstacle);
            position = obstacle.entry.add(ray.scale(distance + EPSILON));
            if (!finite(position)) return new Penetration(obstacle.entry, crossed, speedFactor(crossed, slow), obstacle);
            crossed++;
            Obstacle next = atExit.apply(position);
            if (next == null) return new Penetration(position, crossed, speedFactor(crossed, slow), null);
            obstacle = next;
        }
    }

    private static boolean finite(Vector vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    private static double speedFactor(int crossed, double slow) {
        return crossed <= 0 ? 1 : Math.pow(1 - slow, crossed);
    }
}

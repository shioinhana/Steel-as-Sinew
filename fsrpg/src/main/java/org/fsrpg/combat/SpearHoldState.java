package org.fsrpg.combat;

import java.util.ArrayList;
import java.util.List;

/** Server-owned input lease for one paid thrust. The adapter owns the actual attack and its victims. */
public final class SpearHoldState {
    public static final int DEFAULT_LEASE_TICKS = 12;
    public static final int MAX_PENDING_POINTS = 256;
    public static final double MAX_PENDING_DEGREES = 240;
    private final int leaseTicks;
    private final ArrayList<AttackAim.Point> pending = new ArrayList<>();
    private long sequence = -1, startedTick, updatedTick;
    private boolean holding;
    private AttackAim.Point point;
    private double pendingDegrees;

    public SpearHoldState() { this(DEFAULT_LEASE_TICKS); }

    public SpearHoldState(int leaseTicks) {
        if (leaseTicks < 2 || leaseTicks > 100) throw new IllegalArgumentException("spear input lease must be 2..100 ticks");
        this.leaseTicks = leaseTicks;
    }

    /** Call only after the combat actor accepted a new thrust, using its server swing sequence. */
    public boolean begin(long now, long swingSequence, AttackAim.Point initial, double bodyYaw) {
        checkTick(now);
        if (swingSequence < 0 || initial == null || !Double.isFinite(bodyYaw)) throw new IllegalArgumentException("invalid spear press");
        if (held(now) || !SpearAimField.contains(initial, bodyYaw)) return false;
        pending.clear();
        pendingDegrees = 0;
        sequence = swingSequence;
        startedTick = updatedTick = now;
        point = initial;
        pending.add(initial);
        holding = true;
        return true;
    }

    /** Atomic, bounded, forward-only input. Rejected packets neither extend the lease nor drop an older path. */
    public boolean update(long now, long swingSequence, AttackAim path, double bodyYaw) {
        checkTick(now);
        if (path == null || !Double.isFinite(bodyYaw)) throw new IllegalArgumentException("invalid spear update");
        if (!held(now) || swingSequence != sequence || now < updatedTick) return false;
        for (var next : path.points()) if (!SpearAimField.contains(next, bodyYaw)) return false;
        int additions = 0;
        double degrees = pendingDegrees;
        AttackAim.Point previous = pending.isEmpty() ? point : pending.getLast();
        for (var next : path.points()) {
            if (!same(previous, next)) additions++;
            if (previous != null && SpearAimField.contains(previous, bodyYaw))
                degrees += Math.hypot(AttackAim.wrap(next.yaw() - previous.yaw()), next.pitch() - previous.pitch());
            previous = next;
        }
        int initial = pending.isEmpty() && point != null ? 1 : 0;
        if (pending.size() + initial + additions > MAX_PENDING_POINTS || degrees > MAX_PENDING_DEGREES + 1e-9) return false;
        if (initial != 0) pending.add(point);
        for (var next : path.points()) {
            if (pending.isEmpty() || !same(pending.getLast(), next)) pending.add(next);
        }
        point = path.points().getLast();
        pendingDegrees = degrees;
        updatedTick = now;
        return true;
    }

    /** A late release for a previous swing cannot retract the next attack. Final pending geometry remains drainable. */
    public boolean release(long now, long swingSequence) {
        checkTick(now);
        if (!holding || swingSequence != sequence || now < updatedTick) return false;
        holding = false;
        return true;
    }

    public void cancel() {
        holding = false;
        sequence = -1;
        point = null;
        pending.clear();
        pendingDegrees = 0;
    }

    public boolean held(long now) {
        checkTick(now);
        return holding && now >= updatedTick && now - updatedTick < leaseTicks;
    }

    public boolean expired(long now) {
        checkTick(now);
        return holding && now >= updatedTick && now - updatedTick >= leaseTicks;
    }

    public long heldTicks(long now) { return held(now) ? Math.max(0, now - startedTick) : 0; }
    public long sequence() { return sequence; }
    public AttackAim.Point point() { return point; }
    public int pendingPoints() { return pending.size(); }
    public int leaseTicks() { return leaseTicks; }

    /**
     * Drain each continuous legal segment for this collision tick. A body turn may invalidate an old world-space
     * endpoint; skip that bridge rather than creating a new arc through the actor or joining disconnected pieces.
     * A stationary extended spear still has one point segment so walking into its reach is tested each server tick.
     */
    public List<AttackAim> drainSegments(double bodyYaw) {
        if (!Double.isFinite(bodyYaw)) throw new IllegalArgumentException("invalid spear body yaw");
        if (point == null) return List.of();
        var result = new ArrayList<AttackAim>();
        if (pending.isEmpty()) {
            if (SpearAimField.contains(point, bodyYaw)) result.add(new AttackAim(List.of(point)));
            return List.copyOf(result);
        }
        AttackAim.Point previous = null;
        for (var next : pending) {
            if (!SpearAimField.contains(next, bodyYaw)) { previous = null; continue; }
            result.add(new AttackAim(previous == null ? List.of(next) : List.of(previous, next)));
            previous = next;
        }
        pending.clear();
        pendingDegrees = 0;
        return List.copyOf(result);
    }

    private static boolean same(AttackAim.Point first, AttackAim.Point second) {
        return first != null && Math.abs(AttackAim.wrap(first.yaw() - second.yaw())) < 1e-9
                && Math.abs(first.pitch() - second.pitch()) < 1e-9 && Math.abs(first.distance() - second.distance()) < 1e-9;
    }

    private static void checkTick(long now) { if (now < 0) throw new IllegalArgumentException("spear time must be nonnegative"); }
}

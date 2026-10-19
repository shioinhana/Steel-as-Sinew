package org.fsrpg.combat;

import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;

/** Pure server selection policy. Clients supply neither a target nor its priority. */
public final class RapierTargeting {
    private RapierTargeting() {}

    public record Rules(boolean enabled, double range, boolean allowPassive, boolean requireLineOfSight) {
        public Rules { SkillDefinition.checkNumber(range, "rapierTargetLock.range (blocks)", .5, 64); }
        public static Rules defaults() { return new Rules(true, 10, true, true); }
    }

    public record Candidate(UUID uuid, int entityId, double distanceSquared, boolean hostile,
                            boolean alive, boolean attackable, boolean friendly, boolean visible) {
        public Candidate {
            if (uuid == null) throw new IllegalArgumentException("target UUID is required");
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0)
                throw new IllegalArgumentException("target distance must be finite and nonnegative");
        }
        public boolean eligible(Rules rules) {
            return rules.enabled() && alive && attackable && !friendly
                    && distanceSquared <= rules.range() * rules.range()
                    && (hostile || rules.allowPassive()) && (visible || !rules.requireLineOfSight());
        }
    }

    /** A nearer same-priority arrival never steals a valid lock. A hostile can replace a passive. */
    public static Candidate select(UUID current, Collection<Candidate> candidates, Rules rules) {
        if (!rules.enabled()) return null;
        Candidate held = null, nearestHostile = null, nearestPassive = null;
        Comparator<Candidate> nearest = Comparator.comparingDouble(Candidate::distanceSquared)
                .thenComparing(candidate -> candidate.uuid().toString());
        for (Candidate candidate : candidates) {
            if (!candidate.eligible(rules)) continue;
            if (candidate.uuid().equals(current)) held = candidate;
            if (candidate.hostile()) {
                if (nearestHostile == null || nearest.compare(candidate, nearestHostile) < 0) nearestHostile = candidate;
            } else if (nearestPassive == null || nearest.compare(candidate, nearestPassive) < 0) nearestPassive = candidate;
        }
        if (held != null && (held.hostile() || nearestHostile == null)) return held;
        return nearestHostile != null ? nearestHostile : nearestPassive;
    }

    /** A crosshair-selected lock is explicit. Nearby or higher-priority arrivals cannot steal it. */
    public static Candidate selected(UUID requested, Collection<Candidate> candidates, Rules rules) {
        if(requested==null||!rules.enabled())return null;
        for(var candidate:candidates)if(candidate.uuid().equals(requested)&&candidate.eligible(rules))return candidate;
        return null;
    }

    /** Minecraft world yaw and pitch from an authoritative displacement. */
    public static AttackAim.Point direction(double dx, double dy, double dz, double fallbackYaw) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz))
            throw new IllegalArgumentException("target displacement must be finite");
        double horizontal = Math.hypot(dx, dz);
        double yaw = horizontal < 1e-9 ? fallbackYaw : Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        return new AttackAim.Point(AttackAim.wrap(yaw), Math.clamp(pitch, -80, 80),
                Math.clamp(Math.hypot(horizontal, dy), .5, 96));
    }
    public record DodgeDirection(double x, double z) {}
    /** Only horizontal intent is supplied; travel distance and resource rules stay server-owned. */
    public static DodgeDirection dodgeDirection(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 1 || Math.abs(z) > 1)
            throw new IllegalArgumentException("dodge direction components must be finite and within [-1,1]");
        double length = Math.hypot(x, z);
        if (!(length > 0) || length > 1.01)
            throw new IllegalArgumentException("dodge direction length must be greater than zero and at most 1.01");
        return new DodgeDirection(x / length, z / length);
    }
}

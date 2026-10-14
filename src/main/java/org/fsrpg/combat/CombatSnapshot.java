package org.fsrpg.combat;

/** A transport-safe view. No mutable collections or animation engine objects escape. */
public record CombatSnapshot(
        boolean drawn, CombatPhase phase, int phaseTicksRemaining,
        String skillId, int skillElapsedTicks, int guardElapsedTicks,
        boolean invulnerable, double stamina, double maxStamina, double spirit, double maxSpirit,
        long serverTick, long swingSequence) {}

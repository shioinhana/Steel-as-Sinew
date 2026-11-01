package org.fsrpg.combat;

/** Presentation only; eligibility is always checked against the actor's server clock. */
public record TechniqueSnapshot(String skillId, String source, int remainingTicks) {}

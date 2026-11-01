package org.fsrpg.combat;

/** Stage numbers are one-based; zero means no active combo. */
public record ComboSnapshot(String styleId, int stage, int totalStages, boolean queued,
                            int gateStartTick, int gateEndTick, int chainTick, int elapsedTicks,
                            String lastInputStatus, String lastRejection, String queuedSkillId) {}

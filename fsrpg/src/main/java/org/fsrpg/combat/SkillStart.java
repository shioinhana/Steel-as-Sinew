package org.fsrpg.combat;

/** Emitted once after resource costs were successfully charged; comboStage is zero for standalone attacks. */
public record SkillStart(String skillId, long swingSequence, int comboStage, String styleId) {}

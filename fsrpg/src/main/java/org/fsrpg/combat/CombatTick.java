package org.fsrpg.combat;

/** Move by dodgeDistanceThisTick through the game's collision-aware movement API. */
public record CombatTick(CombatPhase before, CombatPhase after, double dodgeDistanceThisTick,BurstDashState.Step burstDashStep) {
    public CombatTick(CombatPhase before,CombatPhase after,double dodgeDistanceThisTick){this(before,after,dodgeDistanceThisTick,BurstDashState.Step.none());}
}

package com.actioncombat.combat;

import net.minecraft.world.entity.LivingEntity;

/** Per-player server-side combat state, kept as an in-memory attachment (never saved to disk). */
public class CombatData {
    public boolean drawn;
    public boolean defending;
    /** True while the block key is physically down. */
    public boolean defendHeld;
    /** True once this guard crossed the hold threshold. */
    public boolean defendHold;
    /** Game time the current guard was raised, for the parry window. */
    public long defendStartGameTime = Long.MIN_VALUE;
    /** Next accepted swing after a perfect parry uses extra knockback. */
    public boolean parryCounter;
    public HandRest leftHand = HandRest.CENTER;
    public HandRest rightHand = HandRest.CENTER;
    public boolean parryReady;
    public long parryReadyUntilGameTime = Long.MIN_VALUE;
    /** Hand changes, guard, dodge and sheathing never reset accumulated attack cadence. */
    public final AttackCadence cadence = new AttackCadence();
    /** Kept across action cancellation and sheathing so neither bypasses attack cadence. */
    public long lastAttackStartGameTime = Long.MIN_VALUE;
    public long lastAttackEndGameTime = Long.MIN_VALUE;
    public ActiveAttack active;
    public int dodgeTicksLeft;
    public double dodgeDx;
    public double dodgeDz;
    /** Camera pose used to trace the strike; range is still measured from the player. */
    public float aimYaw;
    public float aimPitch;
    public double camX;
    public double camY;
    public double camZ;
    public boolean hasAim;

    public static class ActiveAttack {
        public final SwingSide side;
        /** Which hand is swinging this clip: 0 = left, 1 = right. Dual uses 0. */
        public final int depth;
        public final boolean charged;
        public final boolean dual;
        public final long startGameTime;
        public final float durationSeconds;
        /** Contact frames from the third-person clip, in seconds from swing start. */
        public float activeStartSeconds;
        public float activeEndSeconds;
        /** Instant the blade is in place; scanned once. */
        public float contactSeconds;
        public boolean scanned;
        /** Contact frames: damage is scanned between these ticks. */
        public int activeStartTick;
        public int activeEndTick;
        /** Input window kept for diagnostics; chaining uses {@link #cancelStartTick}. */
        public int comboStartTick;
        public int comboEndTick;
        /** Earliest tick another swing may interrupt this one. */
        public int cancelStartTick;
        public int durationTicks;
        public final LivingEntity[] alreadyHit = new LivingEntity[16];
        public int hitCount;

        public ActiveAttack(SwingSide side, int depth, boolean charged, boolean dual,
                            long startGameTime, float durationSeconds) {
            this.side = side;
            this.depth = depth;
            this.charged = charged;
            this.dual = dual;
            this.startGameTime = startGameTime;
            this.durationSeconds = durationSeconds;
        }
    }

    public void resetForSheath() {
        active = null;
        defending = false;
        defendHeld = false;
        defendHold = false;
        parryCounter = false;
        defendStartGameTime = Long.MIN_VALUE;
        lastAttackEndGameTime = Long.MIN_VALUE;
        parryReady = false;
        parryReadyUntilGameTime = Long.MIN_VALUE;
        dodgeTicksLeft = 0;
        dodgeDx = 0.0;
        dodgeDz = 0.0;
        hasAim = false;
        resetHandsApart();
    }

    /** Idle timeout / guard puts both hands back in the open ready stance. */
    public void resetHandsApart() {
        leftHand = HandRest.CENTER;
        rightHand = HandRest.CENTER;
        lastAttackEndGameTime = Long.MIN_VALUE;
    }
}

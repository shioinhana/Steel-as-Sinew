package com.actioncombat.combat;

import net.minecraft.world.entity.LivingEntity;

/** Per-player server-side combat state, kept as an in-memory attachment (never saved to disk). */
public class CombatData {
    public boolean drawn;
    public long spearSprintStopUntil;
    public long spearDodgeReadyAt;
    // Cooldowns survive sheathing and weapon changes.
    public long thrustReadyAt = Long.MIN_VALUE;
    public long thrustRecoveryUntil = Long.MIN_VALUE;
    public boolean guardBashUsed;
    public boolean sprintWasDown;
    public boolean defending;
    /** True while the block key is physically down. */
    public boolean defendHeld;
    /** True once this guard crossed the hold threshold. */
    public boolean defendHold;
    /** Game time the current guard was raised, for the parry window. */
    public long defendStartGameTime = Long.MIN_VALUE;
    /** Next accepted swing after a perfect parry uses extra knockback. */
    public boolean parryCounter;
    /**
     * Rapier charge stored as server state so a held X key on the client survives
     * a momentary network blip. Consumed by the next confirmed thrust hit.
     */
    public boolean chargeReady;
    public long chargeReadyAt = Long.MIN_VALUE;
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
    /**
     * Dual-wield Shift dash: the max move speed modifier stays on until this game
     * time. A pure buff, so it cancels nothing and blocks nothing.
     */
    public long dodgeBoostUntilGameTime = Long.MIN_VALUE;
    /** Camera pose used to trace the strike; range is still measured from the player. */
    public float aimYaw;
    public float aimPitch;
    public double camX;
    public double camY;
    public double camZ;
    public boolean hasAim;

    // ===== katana stance (左手拔刀) =====
    /** True while this draw is a two-handed katana stance instead of the rapier. */
    public boolean katanaStance;
    /** Entity id the katana lock-on currently points at; -1 = no lock. */
    public int katanaLockId = -1;
    /** A confirmed katana hit tightens the cadence until the next whiff. */
    public boolean katanaHitStreak;
    /** Successful guard boosts the cadence until this game time. */
    public long katanaHasteUntilGameTime = Long.MIN_VALUE;

    /** Rapier left-click slash index; cycles attack1→2→3→4 without dual-wield L/R gates. */
    public int rapierSlashIndex;
    /**
     * A charged thrust that landed keeps the tightened rapier gates live until this
     * game time (0.20, 0.20, 0.30, 0.40 instead of 0.25, 0.25, 0.45, 0.45).
     */
    public long rapierFastUntilGameTime = Long.MIN_VALUE;
    /** Dual-wield: consecutive swings per hand; the other hand's swing relaxes it. */
    public int leftSwingStreak;
    public int rightSwingStreak;

    // ===== rapier combo reward: 4 hits on the same enemy silence AI and mark a thrust =====
    public int rapierComboHits;
    public long rapierLastHitGameTime = Long.MIN_VALUE;
    public java.util.UUID rapierComboTarget;
    public java.util.UUID rapierThrustBonusTarget;
    public long rapierThrustBonusUntil = Long.MIN_VALUE;
    /** Dual-wield: incoming hits on or before this tick count as a raised guard. */
    public long autoGuardUntilGameTime = Long.MIN_VALUE;

    public static class ActiveAttack {
        public WeaponCategory category;
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
        /** Rapier-only: this swing should treat the target's armor as 30 % weaker. */
        public boolean chargePiercingArmor;
        /** Rapier RMB thrust: contact is delayed until the blade actually extends. */
        public boolean thrust;
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
        dodgeBoostUntilGameTime = Long.MIN_VALUE;
        autoGuardUntilGameTime = Long.MIN_VALUE;
        rapierComboTarget = null;
        rapierThrustBonusTarget = null;
        rapierThrustBonusUntil = Long.MIN_VALUE;
        hasAim = false;
        chargeReady = false;
        chargeReadyAt = Long.MIN_VALUE;
        katanaStance = false;
        katanaLockId = -1;
        katanaHitStreak = false;
        katanaHasteUntilGameTime = Long.MIN_VALUE;
        rapierComboHits = 0;
        rapierLastHitGameTime = Long.MIN_VALUE;
        rapierSlashIndex = 0;
        rapierFastUntilGameTime = Long.MIN_VALUE;
        leftSwingStreak = 0;
        rightSwingStreak = 0;
        resetHandsApart();
    }

    /** Idle timeout / guard puts both hands back in the open ready stance. */
    public void resetHandsApart() {
        leftHand = HandRest.CENTER;
        rightHand = HandRest.CENTER;
        lastAttackEndGameTime = Long.MIN_VALUE;
        rapierSlashIndex = 0;
    }
}

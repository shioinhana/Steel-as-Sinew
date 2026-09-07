package com.actioncombat.combat;

/**
 * First-person viewmodel clip sets. Each side has two swings that rotate the
 * same way the third-person body does. Pure data with no client dependencies so
 * tools/check_assets.mjs and AssetBakeSmokeTest can parse it.
 * <p>
 * Dual clips are copied from the corresponding third-person clips by
 * {@code tools/generate_fp_swings.mjs}, preserving each hand's actual swing.
 */
public final class FpWeaponClips {
    /** Null clips are absent from the DOUBLE rig; the controller skips them. */
    public record Clips(String modelId, String animationsId,
                        String idle, String inspect,
                        String runStart, String runHold, String runEnd,
                        String walkStart, String walkHold, String walkEnd,
                        String draw, String putAway, String defend, String aiming,
                        String[] leftAttacks, String[] rightAttacks, float drawSeconds,
                        float[] leftAttackSeconds, float[] rightAttackSeconds) {
        public String attackClip(SwingSide side, int hand) {
            String[] clips = side == SwingSide.RIGHT ? rightAttacks : leftAttacks;
            if (clips == null || clips.length == 0) return null;
            if (hand < 0 || hand >= clips.length) return null;
            return clips[hand];
        }

        public float attackSeconds(SwingSide side, int hand) {
            float[] seconds = side == SwingSide.RIGHT ? rightAttackSeconds : leftAttackSeconds;
            if (seconds == null || seconds.length == 0) return 0.8F;
            if (hand < 0 || hand >= seconds.length) return seconds[0];
            return seconds[hand];
        }

        public String dualAttack() {
            return WeaponCategory.DUAL_ATTACK;
        }

        public float dualSeconds() {
            return Math.max(
                    rightAttackSeconds.length > 0 ? rightAttackSeconds[0] : 0.8F,
                    leftAttackSeconds.length > 0 ? leftAttackSeconds[0] : 0.8F);
        }
    }

    public static final Clips SINGLE = new Clips(
            "fp_singlesword", "fp_singlesword",
            "static_idle", "inspect",
            "run_start", "run_hold", "run_end",
            "walk_start", "walk_hold", "walk_end",
            "draw", "put_away", "defend_1", "aiming",
            new String[]{"attack1", "attack3"},
            new String[]{"attack2", "attack4"},
            0.73333F,
            new float[]{0.4F, 0.4F},
            new float[]{0.4F, 0.4F});

    public static final Clips DOUBLE = new Clips(
            "fp_doublesword", "fp_doublesword",
            "static_idle", "inspect",
            "run_start", "run_hold", "run_end",
            "walk_start", "walk_hold", "walk_end",
            "draw", "put_away", "defend_1", "aiming",
            new String[]{"attack1", "attack3"},
            new String[]{"attack2", "attack4"},
            0.8F,
            new float[]{0.4F, 0.4F},
            new float[]{0.4F, 0.4F});

    private FpWeaponClips() {
    }

    public static Clips of(WeaponCategory category) {
        return category == WeaponCategory.DOUBLE ? DOUBLE : SINGLE;
    }
}

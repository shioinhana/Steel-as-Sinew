package com.actioncombat;

/** All demo tunables in one place; will move into config files later. */
public final class CombatConstants {
    private CombatConstants() {}

    // Items
    public static final String LONGSWORD_NAME = "longsword";

    // Keys
    public static final String KEY_DRAW = "draw";
    public static final String KEY_BLOCK = "block";
    public static final String KEY_INSPECT = "inspect";
    public static final String KEY_CROUCH = "crouch";

    // Dodge (drawn combat: Shift)
    /** Horizontal dodge distance in metres, used as the unit of the scripted step dodge. */
    public static final float DODGE_DISTANCE = 4.5F;
    /** Wall-clock length of the scripted step dodge (rapier / katana). */
    public static final float DODGE_SECONDS = 0.20F;
    public static final int DODGE_TICKS = 4;
    /**
     * Dual-wield Shift is not a scripted step. It raises the maximum move speed for
     * this long, so a moving player accelerates into a dash on their own and may
     * chain a jump, a swing or a guard at any point during it.
     */
    public static final float DUAL_DASH_SECONDS = 0.20F;
    public static final int DUAL_DASH_TICKS = 4;
    /**
     * {@code ADD_MULTIPLIED_TOTAL} on {@code MOVEMENT_SPEED} while dashing: 3.0 is
     * 4x walking speed (5.2x while sprinting, since these modifiers compose). The
     * FOV side effect of the attribute is cancelled in {@code ComputeFovModifierEvent},
     * so the dash changes movement only.
     */
    public static final float DUAL_DASH_SPEED_BONUS = 3.0F;

    // Camera — Genshin-style third person only. No over-the-shoulder offset.
    public static final float THIRD_PERSON_DISTANCE = 4.0F;
    /**
     * Camera spring stiffness. Damping is derived as critical (2*sqrt(k)), so this is
     * the only knob: higher snaps harder to the target rig, lower drifts.
     */
    public static final float CAMERA_SPRING_STIFFNESS = 90.0F;

    // Genshin/Zelda-style movement: WASD is camera-relative and the body turns to face
    // its movement direction instead of the look direction.
    /** Degrees per second the body rotates toward its movement heading. */
    public static final float BODY_TURN_DEGREES_PER_SECOND = 720.0F;
    /** Below this walk speed the body keeps its current heading instead of snapping. */
    public static final float HEADING_SPEED_THRESHOLD = 0.02F;
    /** While a swing is locked, the body faces the camera instead of the stick. */
    public static final float COMBAT_FACING_TURN_DEGREES_PER_SECOND = 1080.0F;
    /** Max degrees the head overlay may yaw toward the locked enemy. */
    public static final float LOOK_OVERLAY_MAX_YAW = 75.0F;
    /** Max degrees the head overlay may pitch. */
    public static final float LOOK_OVERLAY_MAX_PITCH = 35.0F;

    // Attacks — stereo fan from the swinging hand. Same in first and third person.
    /** Horizontal reach in metres, measured from the hand. */
    public static final float ATTACK_RANGE = 1.5F;
    /** Full horizontal arc in degrees. */
    public static final float ATTACK_FAN_DEGREES = 80.0F;
    public static final double ATTACK_CONE_COS = Math.cos(Math.toRadians(ATTACK_FAN_DEGREES * 0.5F));
    /** Vertical thickness of the fan, centered on the hand. */
    public static final float ATTACK_FAN_HEIGHT = 3.0F;
    /** Hand height above the player's feet. */
    public static final float ATTACK_HAND_HEIGHT = 1.15F;
    /** Hand offset along facing / left-right, in metres. */
    public static final float ATTACK_HAND_FORWARD = 0.25F;
    public static final float ATTACK_HAND_SIDE = 0.35F;
    public static final double ATTACK_KNOCKBACK = 0.6;
    /** Knockback on the first swing after a perfect parry. */
    public static final double PARRY_COUNTER_KNOCKBACK = 1.35;
    /**
     * Uniform swing clock: the authored slash plays in {@link #ATTACK_SLASH_SECONDS},
     * then {@link #ATTACK_RECOVERY_SECONDS} of settle. Pre-input opens at 0.2s;
     * the next attack may start at 0.3s.
     */
    public static final float ATTACK_SLASH_SECONDS = 0.30F;
    public static final float ATTACK_RECOVERY_SECONDS = 0.10F;
    public static final float COMBO_WINDOW_START = 0.20F;
    public static final float COMBO_WINDOW_END = 0.40F;
    /** A combo chain stays alive this long after a swing resolves. */
    public static final float COMBO_RESET_SECONDS = 2.0F;
    /** Holding the attack key this long enters the charge stance. */
    public static final float CHARGE_DELAY_SECONDS = 0.35F;
    public static final float CHARGED_DAMAGE_MULTIPLIER = 1.5F;
    /** How long before the 0.3s cancel gate a press is remembered. */
    public static final float INPUT_BUFFER_SECONDS = 0.10F;
    /** Recovery that plays after the slash if the attack is not cancelled. */
    public static final float MIN_RECOVERY_SECONDS = 0.10F;
    /** Contact sits in the middle of the 0.3s slash. */
    public static final float ATTACK_ACTIVE_START_SECONDS = 0.12F;
    public static final float ATTACK_ACTIVE_END_SECONDS = 0.18F;
    public static final float ATTACK_DURATION_SECONDS = 0.40F;
    public static final float ATTACK_CANCEL_TAIL_SECONDS = 0.10F;
    public static final float ATTACK_RECOVERY_POSE_SHARE = 1.0F;
    /** Every swing uses the same 0.3s cancel gate. No progressive interval. */
    public static final float ATTACK_INTERVAL_SECONDS = 0.30F;
    public static final int ATTACK_INTERVAL_TICKS = 6;
    public static final double ATTACK_INTERVAL_MAX_SECONDS = 0.30;
    public static final int ATTACK_INTERVAL_MAX_TICKS = 6;
    public static final double ATTACK_INTERVAL_RECOVERY_PER_SECOND = 0.0;
    /**
     * Attack-time camera lock is disabled. Third-person mouse always orbits;
     * the body may face a lock target (katana) without dragging the camera.
     */
    public static final float ATTACK_FACE_LOCK_SECONDS = 0.0F;
    /** Contact is already aligned with server tick three; no early scan offset. */
    public static final float HIT_SCAN_LEAD_SECONDS = 0.0F;
    /** The uniform contact phase occupies one server tick. */
    public static final float MIN_ACTIVE_WINDOW_SECONDS = 0.05F;

    // Defend
    /** Still holding X at this point turns a tap into a sustained hold. */
    public static final float DEFEND_HOLD_THRESHOLD_SECONDS = 0.2F;
    /** Tap guard pose ends this long after the press. */
    public static final float DEFEND_TAP_SECONDS = 0.3F;
    /** Hold guard ends this long after the press, even if X is still down. */
    public static final float DEFEND_HOLD_SECONDS = 0.5F;
    /** Hits in this window after raising the guard deal no damage. */
    public static final float PARRY_WINDOW_SECONDS = 0.30F;
    public static final float PARRY_START_SECONDS = 0.05F;
    public static final float RAPIER_DODGE_DISTANCE = 2.0F;
    /** Dual-wield swings multiply movement speed by (1 - this). */
    public static final float DUAL_SWING_SLOW_PERCENT = 0.30F;
    /**
     * Dual-wield treats this window after a swing starts as a raised guard. A guard
     * that lands inside it arms the dual cut for the next attack.
     */
    public static final float DUAL_AUTO_GUARD_SECONDS = 0.10F;
    public static final float RAPIER_RANGE = 4.0F;
    /**
     * Rapier slash gates after the 1st, 2nd, 3rd and 4th slash: 0.4, 0.4, 0.4, 0.5.
     */
    public static final int[] RAPIER_GATE_TICKS = {8, 8, 8, 10};
    /** 第四刀: its damage scan lands this much later than the other slashes. */
    public static final float RAPIER_FOURTH_CONTACT_DELAY_SECONDS = 0.05F;
    /**
     * Gates while a charged thrust's hit window is live: 0.3, 0.3, 0.3, 0.3.
     */
    public static final int[] RAPIER_GATE_FAST_TICKS = {6, 6, 6, 6};
    /** A charged thrust that lands keeps the tightened gates alive this long. */
    public static final float RAPIER_FAST_WINDOW_SECONDS = 3.0F;
    /** Dual-wield base gate (0.45s); the third swing in a row on one hand takes the slow gate. */
    public static final int DUAL_INTERVAL_TICKS = 9;
    public static final int DUAL_SLOW_INTERVAL_TICKS = 12;
    /**
     * 双刀 holds the clip's final frame this much longer than the clip itself, so the
     * gap before the next swing never flashes the idle pose. The cancel window is not
     * affected: it still opens on {@link #DUAL_INTERVAL_TICKS}.
     */
    public static final float DUAL_ATTACK_LAST_FRAME_HOLD_SECONDS = 0.10F;
    /** Same-hand swings that may chain before the slow gate; the other hand resets it. */
    public static final int DUAL_SLOW_AFTER_HITS = 2;
    /** Normal thrust length. Contact and the lunge sit at {@link #RAPIER_THRUST_CONTACT_SHARE} of it. */
    public static final float RAPIER_THRUST_SECONDS = 0.50F;
    /** Charged (强化) thrust length: shorter and faster than a normal poke. */
    public static final float RAPIER_CHARGED_THRUST_SECONDS = 0.20F;
    public static final float RAPIER_THRUST_CONTACT_SHARE = 0.5F;
    /**
     * Thrust body motion: the player is carried {@link #RAPIER_LUNGE_DISTANCE} blocks
     * forward when the blade extends. There is no pull-back; the lunge is kept.
     * Spread over {@link #RAPIER_LUNGE_TICKS} so it reads as a lunge, not a teleport.
     */
    public static final float RAPIER_LUNGE_DISTANCE = 0.5F;
    public static final int RAPIER_LUNGE_TICKS = 3;
    /** After four consecutive hits on one enemy, the next thrust in this window deals bonus damage. */
    public static final float RAPIER_THRUST_BONUS_SECONDS = 2.0F;
    public static final float RAPIER_THRUST_BONUS_MULTIPLIER = 2.5F;
    /** Rapier lock-on acquisition / retention range. */
    public static final float LOCK_RANGE = 12.0F;
    /** Rapier sheath: 挽剑花 — a fast reversing twirl, then the blade goes home at the left hip. */
    public static final float RAPIER_SHEATH_SECONDS = 1.10F;
    public static final int THRUST_COOLDOWN_TICKS = 40;
    public static final int THRUST_MISS_RECOVERY_TICKS = 20;
    public static final double GUARD_BASH_RADIUS = 2.0;
    public static final double GUARD_BASH_KNOCKBACK = 1.2;

    // Katana (左手拔刀): two-handed stance with lock-on command attacks.
    /** Base attack cadence: one swing every 0.45s. */
    public static final int KATANA_ATTACK_INTERVAL_TICKS = 9;
    /** After a confirmed hit the cadence tightens to 0.35s and stays until a whiff. */
    public static final int KATANA_STREAK_INTERVAL_TICKS = 7;
    /** A successful guard boosts the cadence to 0.25s for {@link #KATANA_HASTE_SECONDS}. */
    public static final int KATANA_HASTE_INTERVAL_TICKS = 5;
    public static final float KATANA_HASTE_SECONDS = 2.0F;
    /** Katana dodge steps a little further than the rapier's 2.0m. */
    public static final float KATANA_DODGE_DISTANCE = 2.6F;
    /** Lock-on acquisition / retention range. Unused while katana is disabled. */
    public static final float KATANA_LOCK_RANGE = LOCK_RANGE;
    /** Command attacks only reach a locked target inside this radius. */
    public static final float KATANA_ATTACK_RANGE = 4.0F;
    /** Iaido draw: circular damage around the player, equal to one hit. */
    public static final double KATANA_DRAW_AOE_RADIUS = 3.0;
    public static final float KATANA_DRAW_AOE_DAMAGE = 8.0F;
    public static final double KATANA_DRAW_AOE_KNOCKBACK = 0.8;

    // Rapier combo reward: 4 consecutive hits on the same enemy silence its AI.
    public static final int RAPIER_AI_CANCEL_HITS = 4;
    public static final float RAPIER_AI_CANCEL_SECONDS = 1.0F;
    /** Rapier: holding the block key longer than this primes a charge strike. */
    public static final float RAPIER_CHARGE_HOLD_SECONDS = 0.55F;
    /** Share of the target's armor reduction that a charge-pierced rapier thrust ignores (0.30 = 30 %). */
    public static final float RAPIER_CHARGE_ARMOR_PIERCE = 0.30F;
    /** Rapier thrusts never knock the target back: the sword is about lines, not impact. */
    public static final boolean RAPIER_NO_KNOCKBACK = true;
    /** Hold damage-reduction lerp: 100% until 0.2s, 50% at 0.5s. */
    public static final float DEFEND_HOLD_DECAY_START_SECONDS = 0.3F;
    public static final float DEFEND_HOLD_DECAY_END_SECONDS = 0.5F;
    public static final float DEFEND_HOLD_END_REDUCTION = 0.5F;
    /** After a successful parry, both buttons together can fire the dual cut. */
    public static final float PARRY_FOLLOWUP_SECONDS = 2.0F;

    // Movement / idling
    /** Walk animations start above this speed. */
    public static final float WALK_SPEED_THRESHOLD = 0.02F;
    /** Run animations take over above this speed. */
    public static final float RUN_SPEED_THRESHOLD = 0.12F;
    /** Idle this long (drawn, stationary) and the character inspects the weapon. */
    public static final float INSPECT_DELAY_SECONDS = 8.0F;

    // Hit feedback
    /** Impact freeze: the attacker's animation clock holds this long on a confirmed hit. */
    public static final float HITSTOP_SECONDS = 0.05F;
    /** Camera shake on impact. */
    public static final float SHAKE_SECONDS = 0.14F;
    public static final float SHAKE_FREQUENCY_HZ = 18.0F;
    public static final float SHAKE_YAW_DEGREES = 0.85F;
    public static final float SHAKE_PITCH_DEGREES = 0.6F;
    public static final float SHAKE_ROLL_DEGREES = 1.15F;

    /** IJKL camera translation speed, blocks per second. */
    public static final float CAMERA_MOVE_SPEED = 6.0F;
    /** Unlocked camera may not stray further than this from the player. */
    public static final float CAMERA_MAX_SEPARATION = 8.0F;

    // Blade trail / spark VFX (fp_singlesword rig ships these bones unanimated)
    /** Trail segments fade out over this long after a swing starts. */
    public static final float TRAIL_SECONDS = 0.28F;
    /** Impact sparks are visible this long. */
    public static final float SPARK_SECONDS = 0.16F;
}

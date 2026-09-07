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
    /** Horizontal dodge distance in metres. */
    public static final float DODGE_DISTANCE = 4.5F;
    /** Wall-clock length of the dodge step. */
    public static final float DODGE_SECONDS = 0.20F;
    public static final int DODGE_TICKS = 4;

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
    /** Max degrees the head/spine overlay may yaw toward the camera. */
    public static final float LOOK_OVERLAY_MAX_YAW = 55.0F;
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
     * Body+camera stay glued only for the length of the swing itself.
     * After the clip ends the facing is kept but the camera unlocks.
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
    public static final float PARRY_WINDOW_SECONDS = 0.2F;
    /** Hold damage-reduction lerp: 100% until 0.2s, 50% at 0.5s. */
    public static final float DEFEND_HOLD_DECAY_START_SECONDS = 0.2F;
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
    public static final float FOV_PUNCH_DEGREES = 6.0F;
    public static final float FOV_PUNCH_DECAY_SECONDS = 0.18F;
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

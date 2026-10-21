package org.fsrpg.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Presentation settings remain separate from server-authoritative combat definitions. */
final class ClientOptions {
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue THIRD_PERSON;
    static final ModConfigSpec.BooleanValue RPG_HUD;
    static final ModConfigSpec.DoubleValue MOVING_FULL_BODY_LOWER_WEIGHT;
    static final ModConfigSpec.DoubleValue STANCE_FOOT_YAW_LIMIT;
    static final ModConfigSpec.DoubleValue MOVEMENT_LEAN, MOTION_RESPONSE, BREATH_SCALE, BREATH_SPEED;
    static final ModConfigSpec.DoubleValue STRIDE_WEIGHT, ATTACK_LEAN_WEIGHT;
    static final ModConfigSpec.DoubleValue KNEE_BEND, STRIDE_LENGTH;
    static final ModConfigSpec.DoubleValue HEAD_LEAN_FOLLOW, HIP_SHIFT;
    static final ModConfigSpec.DoubleValue AIR_INERTIA_STRENGTH;
    static final ModConfigSpec.DoubleValue SPEAR_CHEST_RETURN;
    static final ModConfigSpec.DoubleValue SPEAR_AIM_RETURN;
    static final ModConfigSpec.DoubleValue SPEAR_ELBOW_LIMIT, SPEAR_WRIST_LIMIT, SPEAR_CLEARANCE, SPEAR_NECK_LIMIT, SPEAR_VERTICAL_BANK;
    static final ModConfigSpec.DoubleValue SPEAR_CARRY_DOWN, SPEAR_CARRY_BACK, SPEAR_CARRY_SIDE, SPEAR_PELVIS_RESPONSE, SPEAR_TURN_STEP_TIME, SPEAR_TURN_STEP_ANGLE, SPEAR_TURN_STEP_LIFT, SPEAR_AIM_CROUCH;
    static final ModConfigSpec.DoubleValue WAIST_TWIST_LIMIT, TURN_ROOT_FOLLOW;
    static final ModConfigSpec.DoubleValue SPEAR_READY_RETRACT;
    static final ModConfigSpec.DoubleValue CAMERA_DISTANCE,CAMERA_HEIGHT,CAMERA_TRANSITION,CAMERA_RESPONSE,CAMERA_ROLL;
    static final ModConfigSpec.DoubleValue SPEAR_LOCKED_RANGE;
    static final ModConfigSpec.DoubleValue SPEAR_TURN_CENTER,SPEAR_TURN_EDGE,SPEAR_TURN_EDGE_START,SPEAR_TURN_RESPONSE;
    static final ModConfigSpec.BooleanValue IDLE_INSPECTIONS;
    static final ModConfigSpec.DoubleValue INSPECT_MIN, INSPECT_MAX, INSPECT_BLEND, ACTION_BLEND;
    static {
        var builder = new ModConfigSpec.Builder();
        RPG_HUD = builder.comment("Use the material HUD: health/experience left, spirit/stamina right of the vanilla hotbar. Disable to restore vanilla health and experience.")
                .define("materialHud", true);
        THIRD_PERSON = builder.comment("Switch to third-person while a combat weapon is drawn; restore previous view after sheathing.")
                .define("thirdPersonWhenDrawn", true);
        MOVING_FULL_BODY_LOWER_WEIGHT = builder.comment("FS lower-body contribution during movement for full-body actions; the remaining weight uses native walking. Action event moving_lower_weight with payload value overrides this value; legacy tags also work.")
                .defineInRange("movingFullBodyLowerWeight", .25, 0, 1);
        STANCE_FOOT_YAW_LIMIT = builder.comment("Maximum authored leg yaw in degrees before aim rotation. Action tag foot_yaw_limit overrides this value.")
                .defineInRange("stanceFootYawLimit", 55.0, 0, 90);
        AIR_INERTIA_STRENGTH = builder.comment("Small airborne pose response to actual ascent, descent and acceleration. Zero preserves the takeoff frame exactly; entity physics and combat are unchanged.")
                .defineInRange("airInertiaStrength", 1.0, 0, 1);
        MOVEMENT_LEAN = builder.comment("Additional torso lean toward movement, in degrees, capped at 4 by the shared movement layer. Authored attack tilt is retained; zero disables movement lean.")
                .defineInRange("movementLeanDegrees", 4.0, 0, 25);
        MOTION_RESPONSE = builder.comment("Visual acceleration/braking smoothing in ticks; applies to directional lean and the upright sprint carry.")
                .defineInRange("motionResponseTicks", 1.4, .5, 10);
        STRIDE_WEIGHT = builder.comment("Grounded stride drives shoulder counter-rotation and a small torso compression; 0 disables it.")
                .defineInRange("strideBodyWeight", 1.0, 0, 2);
        ATTACK_LEAN_WEIGHT = builder.comment("Fraction of movement lean retained while attacking; authored attack weight shift remains independent.")
                .defineInRange("attackMovementLeanWeight", .65, 0, 1);
        KNEE_BEND = builder.comment("Recovering-foot lift expressed as a knee bend in degrees. Planted feet remain on the ground; the shared pelvis and two-part legs determine the final joint angles.")
                .defineInRange("movingKneeBendDegrees", 48.0, 0, 70);
        STRIDE_LENGTH = builder.comment("Directional step length scale. Feet follow forward/backward/sideways travel while hips stay attached to the pelvis.")
                .defineInRange("movingStrideScale", .72, .4, 1.2);
        HEAD_LEAN_FOLLOW = builder.comment("Head follows torso tilt: 1 moves naturally with the body, 0 keeps the head level.")
                .defineInRange("headLeanFollow", 1.0, 0, 1);
        HIP_SHIFT = builder.comment("Whole pelvis and leg-root shift toward movement, in centimeters; a small leg tilt balances it over the feet.")
                .defineInRange("movingHipShiftCm", 6.0, 0, 10);
        BREATH_SCALE = builder.comment("Amplitude of the authored idle breathing; 0 disables it, 1 is the Workbench motion.")
                .defineInRange("idleBreathingScale", 1.0, 0, 2);
        BREATH_SPEED = builder.comment("Playback rate of the authored 4-second idle breathing loop.")
                .defineInRange("idleBreathingSpeed", 1.0, .25, 3);
        IDLE_INSPECTIONS = builder.comment("Play cosmetic rapier inspections after uninterrupted grounded idle; movement and combat interrupt them.")
                .define("idleInspections", true);
        INSPECT_MIN = builder.comment("Minimum stationary wait before an inspection, in seconds.")
                .defineInRange("idleInspectionMinSeconds", 10.0, 1, 600);
        INSPECT_MAX = builder.comment("Maximum stationary wait, in seconds. Values below the minimum use the minimum. The wait restarts after completion or interruption.")
                .defineInRange("idleInspectionMaxSeconds", 30.0, 1, 600);
        INSPECT_BLEND = builder.comment("Seconds to blend into/out of an inspection; attacks keep their server timing.")
                .defineInRange("idleInspectionBlendSeconds", .22, .05, .6);
        ACTION_BLEND = builder.comment("Minimum blend duration between combat/stance actions, in seconds; attack blends end by the active marker.")
                .defineInRange("actionBlendSeconds", .12, .02, .3);
        SPEAR_CHEST_RETURN = builder.comment("Spear torso return toward its authored side-on stance during ready/recovery, in degrees per second. Zero retains the current feasible chest turn; each render step is capped at 10 degrees.")
                .defineInRange("spearChestReturnDegreesPerSecond", 90.0, 0, 240);
        SPEAR_AIM_RETURN = builder.comment("Maximum angular speed of the spear aim return from an attack to the ready cursor, in degrees per second. Windup still settles before contact and ACTIVE follows the server trajectory.")
                .defineInRange("spearAimReturnDegreesPerSecond", 240.0, 90, 720);
        SPEAR_ELBOW_LIMIT = builder.comment("Maximum positive spear elbow flexion in degrees. Applied to the common reachable grip before solving both arms.")
                .defineInRange("spearElbowLimitDegrees", 135.0, 100, 145);
        SPEAR_WRIST_LIMIT = builder.comment("Preferred maximum spear forearm pronation in degrees. Extreme targets may require extra twist up to the hard 150-degree shared-grip bound; the elbow never reverses to hide it.")
                .defineInRange("spearWristLimitDegrees", 120.0, 90, 150);
        SPEAR_CLEARANCE = builder.comment("Additional spear/arm clearance around the chest and head in centimeters; collision trials move the shared grip instead of detaching either hand.")
                .defineInRange("spearBodyClearanceCm", 1.5, 0, 6);
        SPEAR_NECK_LIMIT = builder.comment("Maximum local spear head yaw in degrees when the chest must turn for rearward targets.")
                .defineInRange("spearNeckYawLimitDegrees", 85.0, 50, 100);
        SPEAR_VERTICAL_BANK = builder.comment("Maximum additional spear aiming bank. The underarm guard leans the head away from a raised pole progressively beyond 20 degrees pitch while preserving the authored movement lean.")
                .defineInRange("spearVerticalAimBankDegrees", 18.0, 0, 25);
        SPEAR_CARRY_DOWN=builder.comment("Signed vertical offset from the authored rear grip, in centimeters, positive down. The diagonal underarm guard retains attack displacement.").defineInRange("spearCarryDownCm",-12.0,-20,30);
        SPEAR_CARRY_BACK=builder.comment("Draw the spear rear grip back toward the ribs, in centimeters.").defineInRange("spearCarryBackCm",22.0,0,35);
        SPEAR_READY_RETRACT=builder.comment("Additional withdrawal of the spear in READY, in centimeters. A thrust extends from this position; holding the attack keeps the extended source pose until release.").defineInRange("spearReadyRetractCm",14.0,0,30);
        SPEAR_CARRY_SIDE=builder.comment("Move the carrying anchor to the right side of the chest, in centimeters; the pole remains outside the torso.").defineInRange("spearCarrySideCm",50.0,0,65);
        SPEAR_PELVIS_RESPONSE=builder.comment("Pelvis follows spear chest turn with this render-time response in ticks.").defineInRange("spearPelvisResponseTicks",3.2,1,10);
        WAIST_TWIST_LIMIT=builder.comment("Maximum additional pelvis lag from the spear/rapier fighting stance, in degrees. Larger turns immediately carry the lower body and start a turning step; the rapier's authored side-on carry is excluded.").defineInRange("waistTwistLimitDegrees",45.0,10,90);
        TURN_ROOT_FOLLOW=builder.comment("Share of a spear/rapier root turn followed immediately by the lower body. Remaining turn uses planted feet and alternate turning steps until the waist limit requires a direct follow.").defineInRange("turnRootFollowWeight",.35,0,1);
        SPEAR_TURN_STEP_TIME=builder.comment("Duration of one alternating turning step, in ticks.").defineInRange("spearTurnStepTicks",6.0,3,12);
        SPEAR_TURN_STEP_ANGLE=builder.comment("Maximum heading change per turning step, in degrees.").defineInRange("spearTurnStepDegrees",28.0,6,45);
        SPEAR_TURN_STEP_LIFT=builder.comment("Small turning foot lift, in centimeters; the other sole remains planted.").defineInRange("spearTurnStepLiftCm",5.625,1.25,10);
        SPEAR_AIM_CROUCH=builder.comment("How far the aim lowers the pelvis, in centimeters. Aiming down folds both knees while the soles stay planted; aiming up rises slightly. Zero keeps the legs straight.").defineInRange("spearAimCrouchCm",6.0,0,12);
        SHOULDER=builder.comment("Over-shoulder horizontal camera offset in blocks: positive is right. Shared by drawn weapons and camera-only exploration. Original key retained for existing user settings.").defineInRange("rapierShoulderOffset",.65,-1.5,1.5);
        CAMERA_DISTANCE=builder.comment("Over-shoulder camera distance behind the eyes, in blocks. Native block collision still limits actual travel.").defineInRange("shoulderCameraDistance",3.0,.5,6);
        CAMERA_HEIGHT=builder.comment("Additional over-shoulder camera height, in blocks.").defineInRange("shoulderCameraHeight",.12,-.5,1);
        CAMERA_TRANSITION=builder.comment("Seconds for a smooth eye-to-shoulder entry or return before restoring the previous camera type.").defineInRange("shoulderCameraTransitionSeconds",.5,.15,1.5);
        CAMERA_RESPONSE=builder.comment("Render-time position response for camera side, heading and wall-distance changes, in seconds. Final wall safety is applied after smoothing.").defineInRange("shoulderCameraResponseSeconds",.12,.02,.5);
        CAMERA_ROLL=builder.comment("Small over-shoulder camera roll in degrees, faded with the existing eye-to-shoulder transition. Zero keeps the horizon level.").defineInRange("shoulderCameraRollDegrees",2.0,-6,6);
        SPEAR_LOCKED_RANGE=builder.comment("Physical free spear cursor range relative to the original 45/40 degree limits. Full viewport input maps into this envelope; legacy key retained, target locking removed.").defineInRange("spearLockedAimRangeScale",.7,.1,1);
        SPEAR_TURN_CENTER=builder.comment("Base camera turn speed in degrees per second at full cursor deflection; small offsets turn proportionally.").defineInRange("spearCursorCenterTurnSpeed",28.0,1,120);
        SPEAR_TURN_EDGE=builder.comment("Additional camera turn speed near the physical spear limit, in degrees per second.").defineInRange("spearCursorEdgeTurnSpeed",240.0,0,720);
        SPEAR_TURN_EDGE_START=builder.comment("Normalized cursor deflection where the smooth near-limit turn acceleration begins.").defineInRange("spearCursorEdgeStart",.65,.1,.95);
        SPEAR_TURN_RESPONSE=builder.comment("Camera angular acceleration response in seconds. Turning consumes cursor offset so the reticle gradually returns to center.").defineInRange("spearCursorTurnResponseSeconds",.06,.01,.4);
        SPEAR_RECT_W=builder.comment("Legacy region width retained for compatibility. Free spear cursor uses full viewport input mapped to the physical range; this field no longer affects controls or HUD.").defineInRange("spearCursorWidth",.56,.2,.9);
        SPEAR_RECT_H=builder.defineInRange("spearCursorHeight",.5,.2,.85);
        SPEAR_DRAG=builder.comment("Minimum angular path length in degrees for a sweep.").defineInRange("spearDragDegrees",5.0,1,30);
        SPEAR_HOLD=builder.comment("Minimum held ticks before a dragged gesture becomes a sweep.").defineInRange("spearHoldTicks",3,1,20);
        SPEC = builder.build();
    }
    public static final net.neoforged.neoforge.common.ModConfigSpec.DoubleValue SHOULDER, SPEAR_RECT_W, SPEAR_RECT_H, SPEAR_DRAG;
    public static final net.neoforged.neoforge.common.ModConfigSpec.IntValue SPEAR_HOLD;
    private ClientOptions() {}
}

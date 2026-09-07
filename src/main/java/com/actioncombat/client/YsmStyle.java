package com.actioncombat.client;

import com.actioncombat.combat.WeaponCategory;

/**
 * Third-person dummy rendering that follows Yes Steve Model conventions:
 * vanilla Steve bones ({@code Head}/{@code Body}/{@code LeftArm}/…), a main
 * locomotion file plus extra combat clips, and hand locators for held items.
 * <p>
 * This is not a loader for encrypted {@code .ysm} packs; it replays the same
 * Bedrock geo/animation format GeckoLib already understands.
 */
public final class YsmStyle {
    public static final String MODEL_ID = "ysm_player";
    public static final String MODEL_ID_DUAL = "ysm_player_dual";
    public static final String ANIMATIONS_ID = "ysm_player";
    public static final String ANIMATIONS_ID_DUAL = "ysm_player_dual";
    public static final String HEAD = "Head";
    public static final String BODY = "Body";
    public static final String RIGHT_ARM = "RightArm";
    public static final String LEFT_ARM = "LeftArm";
    public static final String RIGHT_LEG = "RightLeg";
    public static final String LEFT_LEG = "LeftLeg";
    public static final String RIGHT_FOOT = "RightForeLeg";
    public static final String LEFT_FOOT = "LeftForeLeg";
    public static final String RIGHT_HAND = "RightHandLocator";
    public static final String LEFT_HAND = "LeftHandLocator";
    /** Vanilla player model scale (32px Steve is 2 blocks unscaled). */
    public static final float MODEL_SCALE = 0.9375F;

    private static boolean enabled = true;

    private YsmStyle() {}

    /**
     * Yes Steve Model is present: keep the live player (the selected YSM avatar,
     * e.g. DS鲸鱼娘) visible while drawn, and skip our GeckoLib dummy.
     */
    public static boolean useHostPlayerModel() {
        try {
            var list = net.neoforged.fml.ModList.get();
            return list.isLoaded("yes_steve_model") || list.isLoaded("ysm");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean enabled() {
        return enabled && !useHostPlayerModel();
    }

    /** Client-only switch; the dummy keeps the old action rig when this is false. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static String modelId(WeaponCategory category) {
        return category == WeaponCategory.DOUBLE ? MODEL_ID_DUAL : MODEL_ID;
    }

    public static String animationsId(WeaponCategory category) {
        return category == WeaponCategory.DOUBLE ? ANIMATIONS_ID_DUAL : ANIMATIONS_ID;
    }
}

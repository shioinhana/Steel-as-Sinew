package com.actioncombat.combat;

import com.actioncombat.ActionCombat;
import com.actioncombat.CombatConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/** Transient combat-only attribute modifiers. */
public final class CombatAttributes {
    public static final Identifier DUAL_SWING_SLOW =
            Identifier.fromNamespaceAndPath(ActionCombat.MODID, "dual_swing_slow");
    /** Dual-wield Shift dash: a short maximum-move-speed boost. */
    public static final Identifier DUAL_DASH_BOOST =
            Identifier.fromNamespaceAndPath(ActionCombat.MODID, "dual_dash_boost");

    private static final AttributeModifier SWING_SLOW_MODIFIER = new AttributeModifier(
            DUAL_SWING_SLOW,
            -CombatConstants.DUAL_SWING_SLOW_PERCENT,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier DASH_BOOST_MODIFIER = new AttributeModifier(
            DUAL_DASH_BOOST,
            CombatConstants.DUAL_DASH_SPEED_BONUS,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private CombatAttributes() {}

    /** Dual-wield swings cut move speed by {@link CombatConstants#DUAL_SWING_SLOW_PERCENT}. */
    public static void setDualSwingSlow(LivingEntity entity, boolean on) {
        apply(entity, SWING_SLOW_MODIFIER, on);
    }

    /**
     * Dual-wield Shift raises the maximum move speed by
     * {@link CombatConstants#DUAL_DASH_SPEED_BONUS} for the dash window. It is a
     * buff only: the player keeps full control and every other action stays legal.
     */
    public static void setDualDashBoost(LivingEntity entity, boolean on) {
        apply(entity, DASH_BOOST_MODIFIER, on);
    }

    /**
     * Idempotent toggle. The attribute is only marked dirty when the modifier really
     * appears or disappears, and a modifier that is already present is re-asserted so
     * an attribute sync from the other side cannot silently drop the client's copy.
     */
    private static void apply(LivingEntity entity, AttributeModifier modifier, boolean on) {
        if (entity == null) return;
        var instance = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (instance == null) return;
        boolean present = instance.getModifier(modifier.id()) != null;
        if (on) {
            if (present) instance.addOrUpdateTransientModifier(modifier);
            else instance.addTransientModifier(modifier);
        } else if (present) {
            instance.removeModifier(modifier.id());
        }
    }

    /**
     * The FOV modifier vanilla would compute if the dash were not applied. Vanilla
     * scales FOV by {@code (speed / walkSpeed + 1) / 2}, so a 4x dash would slam into
     * the 0.1–1.5 clamp; dividing out only our own factor keeps the dash a pure
     * movement change. Returns {@code fovModifier} untouched when no dash is active.
     */
    public static float fovModifierWithoutDash(Player player, float fovModifier) {
        if (player == null) return fovModifier;
        var instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (instance == null || instance.getModifier(DUAL_DASH_BOOST) == null) return fovModifier;
        float walk = player.getAbilities().getWalkingSpeed();
        if (walk == 0.0F) return fovModifier;
        double speed = instance.getValue();
        double factor = speed / walk + 1.0;
        if (factor <= 1.0E-4) return fovModifier;
        double neutral = speed / (1.0 + CombatConstants.DUAL_DASH_SPEED_BONUS);
        return (float) (fovModifier * (neutral / walk + 1.0) / factor);
    }
}

package com.actioncombat.client;

import com.actioncombat.ActionCombat;
import com.actioncombat.combat.WeaponCategory;
import com.actioncombat.entity.PlayerAnimatable;
import net.minecraft.resources.Identifier;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class PlayerGeoModel extends GeoModel<PlayerAnimatable> {
    public static final DataTicket<Identifier> SKIN_TEXTURE =
            DataTicket.create("skin_texture", Identifier.class);
    public static final DataTicket<WeaponCategory> WEAPON_CATEGORY =
            DataTicket.create("weapon_category", WeaponCategory.class);
    /** Entity id of the player this dummy mirrors (to skip it for the local player in FP). */
    public static final DataTicket<Integer> DUMMY_OWNER_ID =
            DataTicket.create("dummy_owner_id", Integer.class);
    /** Head/spine look overlay, in degrees, applied per-frame during the render pass. */
    public static final DataTicket<Float> LOOK_YAW =
            DataTicket.create("look_yaw", Float.class);
    public static final DataTicket<Float> LOOK_PITCH =
            DataTicket.create("look_pitch", Float.class);
    public static final DataTicket<Boolean> FULL_BODY_ACTION =
            DataTicket.create("full_body_action", Boolean.class);

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        WeaponCategory cat = renderState.getOrDefaultGeckolibData(WEAPON_CATEGORY, WeaponCategory.SINGLE);
        if (YsmStyle.enabled()) {
            return Identifier.fromNamespaceAndPath(ActionCombat.MODID, YsmStyle.modelId(cat));
        }
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID, cat.modelId);
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(SKIN_TEXTURE, PlayerAnimatable.DEFAULT_SKIN);
    }

    @Override
    public Identifier getAnimationResource(PlayerAnimatable animatable) {
        if (YsmStyle.enabled()) {
            return Identifier.fromNamespaceAndPath(ActionCombat.MODID, YsmStyle.animationsId(animatable.weaponCategory));
        }
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID, animatable.weaponCategory.animationsId);
    }

    @Override
    public void addAdditionalStateData(PlayerAnimatable animatable, Object context, GeoRenderState renderState) {
        renderState.addGeckolibData(SKIN_TEXTURE, animatable.skinTexture);
        renderState.addGeckolibData(WEAPON_CATEGORY, animatable.weaponCategory);
        renderState.addGeckolibData(DUMMY_OWNER_ID, animatable.ownerPlayerId);
        renderState.addGeckolibData(LOOK_YAW, animatable.lookYawOffset);
        renderState.addGeckolibData(LOOK_PITCH, animatable.lookPitchOffset);
        renderState.addGeckolibData(FULL_BODY_ACTION, animatable.hasFullBodyAction());
        renderState.addGeckolibData(software.bernie.geckolib.constant.DataTickets.TICK,
                animatable.animationTick);
        java.util.EnumMap<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> equipment =
                new java.util.EnumMap<>(net.minecraft.world.entity.EquipmentSlot.class);
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            equipment.put(slot, animatable.getItemBySlot(slot));
        }
        renderState.addGeckolibData(
                software.bernie.geckolib.constant.DataTickets.EQUIPMENT_BY_SLOT, equipment);
    }
}

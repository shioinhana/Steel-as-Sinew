package com.actioncombat.client;

import com.actioncombat.ActionCombat;
import com.actioncombat.combat.FpWeaponClips;
import com.actioncombat.item.LongswordItem;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

/**
 * Model for the longsword viewmodel. Picks rig + texture by render perspective
 * and dual-wield state: first-person dual-wield uses the two-arm rig, everything
 * else the sword-only rig (static pose).
 *
 * getAnimationResource receives no render state; the animation file id is
 * mirrored here from addAdditionalStateData, which GeckoLib calls before the
 * controller predicates run for the same render state (both on the render
 * thread during extract, so the mirror is always current when consumed).
 */
public class LongswordFpModel extends GeoModel<LongswordItem> {
    private static String animationFileId = FpWeaponClips.SINGLE.animationsId();

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID, clipsFor(renderState).modelId());
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID,
                "textures/entity/" + clipsFor(renderState).modelId() + ".png");
    }

    @Override
    public Identifier getAnimationResource(LongswordItem animatable) {
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID, animationFileId);
    }

    @Override
    public void addAdditionalStateData(LongswordItem animatable, Object context, GeoRenderState renderState) {
        animationFileId = clipsFor(renderState).animationsId();
    }

    private static FpWeaponClips.Clips clipsFor(GeoRenderState renderState) {
        ItemDisplayContext ctx = renderState.getOrDefaultGeckolibData(
                DataTickets.ITEM_RENDER_PERSPECTIVE, ItemDisplayContext.NONE);
        return ctx.firstPerson() && ClientCombat.isLocalDualWield()
                ? FpWeaponClips.DOUBLE
                : FpWeaponClips.SINGLE;
    }
}

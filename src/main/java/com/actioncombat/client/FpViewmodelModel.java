package com.actioncombat.client;

import com.actioncombat.ActionCombat;
import com.actioncombat.combat.FpWeaponClips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

/**
 * Picks the authored first-person rig. Dual-wield uses the two-arm skeleton;
 * everything else uses the single-sword viewmodel.
 */
public class FpViewmodelModel extends GeoModel<FpViewmodel> {
    private static String animationFileId = FpWeaponClips.SINGLE.animationsId();

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID, clips().modelId());
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player instanceof AbstractClientPlayer player) {
            return player.getSkin().body().texturePath();
        }
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID,
                "textures/entity/" + clips().modelId() + ".png");
    }

    @Override
    public Identifier getAnimationResource(FpViewmodel animatable) {
        animationFileId = clips().animationsId();
        return Identifier.fromNamespaceAndPath(ActionCombat.MODID, animationFileId);
    }

    @Override
    public void addAdditionalStateData(FpViewmodel animatable, Object context, GeoRenderState renderState) {
        animationFileId = clips().animationsId();
        var dummy = FpViewmodel.localDualDummy();
        if (dummy != null) {
            renderState.addGeckolibData(software.bernie.geckolib.constant.DataTickets.TICK,
                    dummy.animationTick);
        }
    }

    private static FpWeaponClips.Clips clips() {
        return FpWeaponClips.of(ClientCombat.localWeaponCategory());
    }
}

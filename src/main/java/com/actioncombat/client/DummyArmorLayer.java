package com.actioncombat.client;

import com.actioncombat.entity.PlayerAnimatable;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.base.RenderPassInfo;
import software.bernie.geckolib.renderer.layer.builtin.ItemArmorGeoLayer;

import java.util.List;

/**
 * Draws the mirrored player's armour on the combat dummy.
 * <p>
 * Without this, entering combat mode makes armour, and therefore any visual sign of what
 * the player is wearing, disappear — the dummy is a bare geo rig. GeckoLib fits the
 * vanilla humanoid armour models onto named rig bones, so each armour segment is mapped
 * to the bone that plays the same role in this skeleton.
 * <p>
 * The rig has no separate foot bones, so boots ride the lower leg. That is a small
 * inaccuracy in exchange for boots being visible at all.
 */
public class DummyArmorLayer
        extends ItemArmorGeoLayer<PlayerAnimatable, Void, PlayerGeoRenderState> {

    public DummyArmorLayer(GeoRenderer<PlayerAnimatable, Void, PlayerGeoRenderState> renderer,
                           EntityRendererProvider.Context context) {
        super(renderer, context);
    }

    @Override
    protected List<RenderData> getRelevantBones(RenderPassInfo<PlayerGeoRenderState> pass) {
        if (YsmStyle.enabled()) {
            return List.of(
                    RenderData.head(YsmStyle.HEAD),
                    RenderData.body(YsmStyle.BODY),
                    RenderData.rightArm(YsmStyle.RIGHT_ARM),
                    RenderData.leftArm(YsmStyle.LEFT_ARM),
                    RenderData.rightLeg(YsmStyle.RIGHT_LEG),
                    RenderData.leftLeg(YsmStyle.LEFT_LEG),
                    RenderData.rightFoot(YsmStyle.RIGHT_FOOT),
                    RenderData.leftFoot(YsmStyle.LEFT_FOOT));
        }
        return List.of(
                RenderData.head("head"),
                RenderData.body("upper_body"),
                RenderData.rightArm("upper_arm_r"),
                RenderData.leftArm("upper_arm_l"),
                RenderData.rightLeg("leg_r"),
                RenderData.leftLeg("leg_l"),
                // no dedicated foot bones on this rig; boots follow the lower leg
                RenderData.rightFoot("lower_leg_r"),
                RenderData.leftFoot("lower_leg_l"));
    }
}

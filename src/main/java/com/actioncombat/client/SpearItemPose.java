package com.actioncombat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/** Vanilla 32px spear sprite: rear grip at (27,27), axis normalized to +Y. */
public final class SpearItemPose {
    private SpearItemPose() {}
    public static void apply(PoseStack pose) {
        pose.mulPose(Axis.ZP.rotationDegrees(-45.0F));
        pose.scale(2.393F, 2.393F, 2.393F);
        pose.translate(-0.34375F, 0.34375F, 0);
    }
}

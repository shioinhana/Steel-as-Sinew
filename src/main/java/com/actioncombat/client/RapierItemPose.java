package com.actioncombat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/** Canonical diagonal sword sprite -> hilt at origin, blade along +Y, face normal +Z.
 * NONE prevents third-person item display rotations/offsets from moving the grip.
 * The same mount is used in both perspectives so edge alignment stays authoritative.
 */
public final class RapierItemPose {
    private RapierItemPose() {}
    public static void apply(PoseStack pose) {
        pose.scale(0.85F, 0.85F, 0.85F);
        pose.translate(-0.020905148F, 0.443653694F, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(41.987212496F));
    }
}

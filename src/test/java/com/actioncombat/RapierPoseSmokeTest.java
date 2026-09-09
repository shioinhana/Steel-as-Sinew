package com.actioncombat;

import com.actioncombat.client.RapierItemPose;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;

/** Check actual item sprite coordinates, rather than assuming the texture follows a bone axis. */
public final class RapierPoseSmokeTest {
    public static void verify() {
        PoseStack pose = new PoseStack();
        RapierItemPose.apply(pose);
        Vector3f grip = pose.last().pose().transformPosition(new Vector3f(3.5F / 16 - 0.5F, 2.5F / 16 - 0.5F, 0));
        Vector3f tip = pose.last().pose().transformPosition(new Vector3f(12.5F / 16 - 0.5F, 12.5F / 16 - 0.5F, 0));
        if (grip.length() > 0.00001F || Math.abs(tip.x) > 0.00001F || Math.abs(tip.z) > 0.00001F || tip.y < .70F) {
            throw new AssertionError("Rapier sprite must start at grip and extend along +Y: " + grip + " / " + tip);
        }
        System.out.println("Rapier actual sprite mount: centred grip and blade axis passed");
    }
}

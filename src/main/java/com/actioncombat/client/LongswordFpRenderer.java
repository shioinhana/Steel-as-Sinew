package com.actioncombat.client;

import com.actioncombat.item.LongswordItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemDisplayContext;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.RenderPassInfo;

public class LongswordFpRenderer extends GeoItemRenderer<LongswordItem> {
    public LongswordFpRenderer() {
        super(new LongswordFpModel());
    }

    @Override
    public void submit(GeoRenderState renderState, PoseStack poseStack,
                       SubmitNodeCollector collector, int packedLight) {
        // The dual-wield skeleton already carries both swords; rendering the
        // offhand item too would draw the left blade twice.
        ItemDisplayContext ctx = renderState.getOrDefaultGeckolibData(
                DataTickets.ITEM_RENDER_PERSPECTIVE, ItemDisplayContext.NONE);
        if (ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) return;
        super.submit(renderState, poseStack, collector, packedLight);
    }

    /**
     * Pose the blade-trail and spark bones. The rig ships them but no authored clip
     * animates them, so without this they sit stacked at their rest pose in view of the
     * camera. Queued as a bone updater so it runs after the animation has posed the rig.
     */
    @Override
    public void preRenderPass(RenderPassInfo<GeoRenderState> pass, SubmitNodeCollector collector) {
        super.preRenderPass(pass, collector);

        ItemDisplayContext ctx = pass.renderState().getOrDefaultGeckolibData(
                DataTickets.ITEM_RENDER_PERSPECTIVE, ItemDisplayContext.NONE);
        boolean firstPersonMain = ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;

        float swingProgress = firstPersonMain ? ClientCombat.localSwingProgress() : -1.0F;
        float sparkProgress = firstPersonMain
                ? BladeVfx.sparkProgress(ClientCombat.lastHitNanos())
                : -1.0F;

        pass.addBoneUpdater((info, snapshots) ->
                BladeVfx.apply(snapshots, swingProgress, sparkProgress));
    }
}

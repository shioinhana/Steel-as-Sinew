package com.actioncombat.client;

import com.actioncombat.entity.PlayerAnimatable;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.CameraRenderState;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.base.RenderPassInfo;

/**
 * Renders the combat dummy. The local player's dummy is skipped in first person
 * (the viewmodel shows instead). The head stays with the authored pose, facing
 * the body's heading. Held items are the player's real swords, not the geo cubes.
 */
public class PlayerGeoRenderer extends GeoEntityRenderer<PlayerAnimatable, PlayerGeoRenderState> {
    public PlayerGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerGeoModel());
        withRenderLayer(new DummyArmorLayer(this, context));
        withRenderLayer(new DummyHeldItemLayer(this));
    }

    @Override
    public PlayerGeoRenderState createRenderState(PlayerAnimatable animatable, Void relatedObject) {
        return new PlayerGeoRenderState();
    }

    /**
     * First person uses the item viewmodel, not this body. Skipping here (rather than
     * in submit) also drops the dummy's shadow, which would otherwise sit on the camera.
     */
    @Override
    public boolean shouldRender(PlayerAnimatable dummy, Frustum frustum,
                                double camX, double camY, double camZ) {
        if (YsmStyle.useHostPlayerModel()) return false;
        if (ClientCombat.shouldHideDummyInFirstPerson(dummy.ownerPlayerId)) {
            return false;
        }
        return super.shouldRender(dummy, frustum, camX, camY, camZ);
    }

    @Override
    public void submit(PlayerGeoRenderState renderState, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        Minecraft mc = Minecraft.getInstance();
        int ownerId = renderState.getOrDefaultGeckolibData(PlayerGeoModel.DUMMY_OWNER_ID, -1);
        if (mc.player != null && ClientCombat.shouldHideDummyInFirstPerson(ownerId)) {
            return;
        }
        super.submit(renderState, poseStack, collector, cameraState);
    }

    @Override
    public void preRenderPass(RenderPassInfo<PlayerGeoRenderState> pass, SubmitNodeCollector collector) {
        super.preRenderPass(pass, collector);
        pass.addBoneUpdater((info, snapshots) -> {
            snapshots.ifPresent("hand_item_r", snapshot -> snapshot.skipRender(true));
            snapshots.ifPresent("hand_item_l", snapshot -> snapshot.skipRender(true));
            snapshots.ifPresent(YsmStyle.RIGHT_HAND, snapshot -> snapshot.skipRender(true));
            snapshots.ifPresent(YsmStyle.LEFT_HAND, snapshot -> snapshot.skipRender(true));
            if (!info.renderState().getOrDefaultGeckolibData(PlayerGeoModel.FULL_BODY_ACTION, false)) {
                String hip = YsmStyle.enabled() ? "Hip" : "lower_body";
                String torso = YsmStyle.enabled() ? YsmStyle.BODY : "upper_body";
                snapshots.ifPresent(hip, pelvis -> snapshots.ifPresent(torso, upper -> upper.setRotation(
                        upper.getRotX(), upper.getRotY() - pelvis.getRotY(), upper.getRotZ())));
            }
            if (!YsmStyle.enabled()) return;
            float yaw = info.renderState().getOrDefaultGeckolibData(PlayerGeoModel.LOOK_YAW, 0.0F);
            float pitch = info.renderState().getOrDefaultGeckolibData(PlayerGeoModel.LOOK_PITCH, 0.0F);
            snapshots.ifPresent(YsmStyle.HEAD, snapshot -> snapshot.setRotation(
                    snapshot.getRotX() + pitch * net.minecraft.util.Mth.DEG_TO_RAD,
                    snapshot.getRotY() + yaw * net.minecraft.util.Mth.DEG_TO_RAD,
                    snapshot.getRotZ()));
        });
    }

    @Override
    public void scaleModelForRender(software.bernie.geckolib.renderer.base.RenderPassInfo<PlayerGeoRenderState> pass,
                                    float widthScale, float heightScale) {
        if (YsmStyle.enabled()) {
            super.scaleModelForRender(pass, widthScale * YsmStyle.MODEL_SCALE, heightScale * YsmStyle.MODEL_SCALE);
        } else {
            super.scaleModelForRender(pass, widthScale, heightScale);
        }
    }
}

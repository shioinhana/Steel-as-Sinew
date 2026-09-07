package com.actioncombat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.model.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.renderer.base.BoneSnapshots;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.RenderPassInfo;

import java.util.Set;

/**
 * The hand pass supplies the camera rotation to cancel the global view matrix.
 * Preserve it when placing the viewmodel, including for attached item renders.
 */
public class FpViewmodelRenderer extends GeoObjectRenderer<FpViewmodel, Void, GeoRenderState> {
    private static final float SINGLE_OFFSET_Y = -1.40F;
    private static final float SINGLE_OFFSET_Z = -0.10F;
    private static final float DUAL_OFFSET_Y = -1.62F;
    private static final float DUAL_OFFSET_Z = -0.60F;
    private static final float LEFT_SHOULDER_X = -0.60F;
    private static final float LEFT_SHOULDER_Y = -0.55F;
    private static final float LEFT_SHOULDER_Z = 0.35F;
    private static final float RIGHT_SHOULDER_X = 0.60F;
    private static final float RIGHT_SHOULDER_Y = -0.55F;
    private static final float RIGHT_SHOULDER_Z = 0.35F;
    private static final float ELBOW_OVERLAP = 0.0625F;
    private static final Set<String> ARM_MESH_BONES = Set.of(
            "fp_upper_arm_r", "fp_upper_arm_l", "right_sleve_bottom", "left_arm_bottom",
            "hand_r", "hand_l", "dissipate_view_0", "dissipate_view_452");

    public FpViewmodelRenderer() {
        super(new FpViewmodelModel());
        withRenderLayer(new FpHeldItemLayer(this));
    }

    @Override
    public long getInstanceId(FpViewmodel animatable, Void related) {
        var dummy = FpViewmodel.localDualDummy();
        if (dummy != null) return dummy.getId();
        return 1L;
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<GeoRenderState> pass) {
        // Do not apply GeoObjectRenderer's block-item (0.5, 0.51, 0.5) offset.
    }

    @Override
    public void preRenderPass(RenderPassInfo<GeoRenderState> pass, SubmitNodeCollector collector) {
        super.preRenderPass(pass, collector);
        pass.addBoneUpdater((info, snapshots) -> {
            for (GeoBone bone : info.model().boneLookup().get().values()) {
                snapshots.get(bone).skipRender(!isFirstPersonArmMesh(bone.name()));
            }
            var dummy = FpViewmodel.localDualDummy();
            if (dummy != null && !dummy.hasFullBodyAction()) {
                float hipYaw = snapshots.get("lower_body").map(BoneSnapshot::getRotY).orElse(0.0F);
                snapshots.ifPresent("upper_body", snapshot -> snapshot.setRotY(snapshot.getRotY() - hipYaw));
            }
            anchorUpperArm(snapshots, false);
            anchorUpperArm(snapshots, true);
        });
    }

    public static boolean isFirstPersonArmMesh(String boneName) {
        return ARM_MESH_BONES.contains(boneName);
    }

    public static Vector3f shoulderAnchor(boolean left) {
        return new Vector3f(left ? LEFT_SHOULDER_X : RIGHT_SHOULDER_X,
                (left ? LEFT_SHOULDER_Y : RIGHT_SHOULDER_Y) - DUAL_OFFSET_Y,
                (left ? LEFT_SHOULDER_Z : RIGHT_SHOULDER_Z) - DUAL_OFFSET_Z);
    }

    private static void anchorUpperArm(BoneSnapshots snapshots, boolean left) {
        var mesh = snapshots.get(left ? "fp_upper_arm_l" : "fp_upper_arm_r");
        var elbowSnapshot = snapshots.get(left ? "left_arm_bottom" : "right_sleve_bottom");
        if (mesh.isEmpty() || elbowSnapshot.isEmpty()) return;
        GeoBone elbow = elbowSnapshot.get().getBone();
        Matrix4f forearmPose = bonePose(elbow, snapshots);
        Vector3f elbowCenter = forearmPose.transformPosition(new Vector3f(
                (elbow.pivotX() + (left ? -1.0F : 1.0F)) / 16.0F,
                elbow.pivotY() / 16.0F, elbow.pivotZ() / 16.0F));
        poseUpperArm(mesh.get(), elbowCenter, shoulderAnchor(left),
                forearmPose.getUnnormalizedRotation(new Quaternionf()));
    }

    public static void poseUpperArm(BoneSnapshot mesh, Vector3f elbow, Vector3f shoulder,
                                    Quaternionf forearmRotation) {
        Vector3f direction = new Vector3f(shoulder).sub(elbow);
        float length = direction.length();
        if (length < 0.0001F) return;
        direction.div(length);
        Vector3f start = new Vector3f(elbow).fma(-ELBOW_OVERLAP, direction);
        // Keep the forearm's skin roll while stretching only the upper-arm surface.
        Vector3f forearmUp = forearmRotation.transform(new Vector3f(0, 1, 0));
        Vector3f angles = new Quaternionf().rotationTo(forearmUp, direction)
                .mul(forearmRotation).getEulerAnglesZYX(new Vector3f());
        mesh.setTranslation(-start.x * 16.0F, start.y * 16.0F, start.z * 16.0F);
        mesh.setRotation(angles.x, angles.y, angles.z);
        mesh.setScale(1.0F, (length + ELBOW_OVERLAP) * 16.0F, 1.0F);
    }

    private static Matrix4f bonePose(GeoBone bone, BoneSnapshots snapshots) {
        Matrix4f pose = bone.parent() == null ? new Matrix4f() : bonePose(bone.parent(), snapshots);
        BoneSnapshot snapshot = snapshots.get(bone);
        return pose.translate(-snapshot.getTranslateX() / 16.0F,
                        snapshot.getTranslateY() / 16.0F, snapshot.getTranslateZ() / 16.0F)
                .translate(bone.pivotX() / 16.0F, bone.pivotY() / 16.0F, bone.pivotZ() / 16.0F)
                .rotateZYX(bone.baseRotZ() + snapshot.getRotZ(),
                        bone.baseRotY() + snapshot.getRotY(), bone.baseRotX() + snapshot.getRotX())
                .scale(snapshot.getScaleX(), snapshot.getScaleY(), snapshot.getScaleZ())
                .translate(-bone.pivotX() / 16.0F, -bone.pivotY() / 16.0F, -bone.pivotZ() / 16.0F);
    }

    public void submitViewmodel(PoseStack poseStack, SubmitNodeCollector collector,
                                int packedLight, float partialTick) {
        CameraRenderState viewCamera = new CameraRenderState();
        viewCamera.initialized = true;
        viewCamera.pos = Vec3.ZERO;
        viewCamera.orientation = new Quaternionf();

        poseStack.pushPose();
        applyViewmodelPose(poseStack, ClientCombat.isLocalDualWield());

        GeoRenderState state = fillRenderState(
                FpViewmodel.INSTANCE, null, createRenderState(FpViewmodel.INSTANCE, null), partialTick);
        state.addGeckolibData(DataTickets.PACKED_LIGHT, packedLight);
        state.addGeckolibData(DataTickets.PACKED_OVERLAY, OverlayTexture.NO_OVERLAY);
        performRenderPass(state, poseStack, collector, viewCamera);
        poseStack.popPose();
    }

    public static void applyViewmodelPose(PoseStack poseStack, boolean dualWield) {
        // RenderHandEvent fires before per-item offsets. Its camera rotation must
        // survive: the global ModelView applies the inverse during submission.
        if (dualWield) {
            poseStack.translate(0.0F, DUAL_OFFSET_Y, DUAL_OFFSET_Z);
        } else {
            poseStack.translate(0.0F, SINGLE_OFFSET_Y, SINGLE_OFFSET_Z);
        }
    }
}

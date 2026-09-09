package com.actioncombat;

import com.actioncombat.combat.FpWeaponClips;
import com.actioncombat.combat.WeaponCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.resources.Identifier;
import software.bernie.geckolib.cache.GeckoLibResources;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Headless smoke test: runs the real GeckoLib resource-baking pipeline over
 * src/main/resources and asserts the baked cache contains every file,
 * animation and bone the code references. Exits non-zero on failure.
 */
public final class AssetBakeSmokeTest {

    /** Minimal directory pack: like PathPackResources but without any SharedConstants/FML access. */
    private static final class FolderPackResources implements PackResources {
        private final String id;
        private final Path root;

        FolderPackResources(String id, Path root) { this.id = id; this.root = root; }

        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) { return null; }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            Path file = fileFor(type, location);
            return Files.isRegularFile(file) ? supplier(file) : null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput out) {
            Path dir0 = root.resolve(type.getDirectory()).resolve(namespace);
            for (String part : path.split("/")) dir0 = dir0.resolve(part);
            if (!Files.isDirectory(dir0)) return;
            Path dir = dir0;
            try (var stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    String rel = dir.relativize(file).toString().replace('\\', '/');
                    var id2 = Identifier.fromNamespaceAndPath(namespace, path + "/" + rel);
                    out.accept(id2, supplier(file));
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Path dir = root.resolve(type.getDirectory());
            if (!Files.isDirectory(dir)) return Set.of();
            try (var stream = Files.list(dir)) {
                return stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> type) { return null; }

        @Override
        public PackLocationInfo location() {
            return new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
        }

        @Override
        public void close() {}

        private Path fileFor(PackType type, Identifier location) {
            Path file = root.resolve(type.getDirectory()).resolve(location.getNamespace());
            for (String part : location.getPath().split("/")) file = file.resolve(part);
            return file;
        }

        private IoSupplier<InputStream> supplier(Path file) {
            return () -> Files.newInputStream(file);
        }
    }

    private static void minimalBootstrap() throws Exception {
        Field flag = Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true);
        flag.setBoolean(null, true);
        if (BuiltInRegistries.REGISTRY.keySet().isEmpty()) {
            throw new IllegalStateException("built-in registries did not initialize");
        }
    }

    public static void main(String[] args) throws Exception {
        minimalBootstrap();
        AttackCadenceSmokeTest.verify();
        AttackCadenceStateSmokeTest.verify();
        AttackAcknowledgementsSmokeTest.verify();
        CombatAnimationControllerSmokeTest.verify();
        verifyFirstPersonCameraPose();
        RapierPoseSmokeTest.verify();
        Path resources = Path.of(args.length > 0 ? args[0] : "src/main/resources");
        var pack = new FolderPackResources("asset_smoke", resources);

        int failures = 0;
        try (var rm = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack))) {
            var state = new PreparableReloadListener.SharedState(rm);
            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                GeckoLibResources.reload(state, exec, CompletableFuture::completedFuture, exec).join();
            } finally {
                exec.shutdown();
            }

            var bakedAnims = GeckoLibResources.getBakedAnimations().cache();
            var bakedModels = GeckoLibResources.getBakedModels().cache();
            verifyFirstPersonUpperArms(bakedModels.get(
                    Identifier.fromNamespaceAndPath(ActionCombat.MODID, "fp_doublesword")));

            for (WeaponCategory cat : WeaponCategory.values()) {
                Identifier modelFile =
                        Identifier.fromNamespaceAndPath(ActionCombat.MODID, cat.modelId);
                Identifier animFile =
                        Identifier.fromNamespaceAndPath(ActionCombat.MODID, cat.animationsId);

                if (!bakedAnims.containsKey(animFile)) {
                    System.err.println("FAIL: animation file not baked: " + animFile);
                    failures++;
                } else {
                    var anims = bakedAnims.get(animFile).animations();
                    var stances = new java.util.ArrayList<String>(java.util.List.of(cat.idle,
                            cat.inspect, cat.runStart, cat.runHold, cat.runEnd,
                            cat.draw, cat.putAway, cat.defend, cat.aiming));
                    stances.addAll(java.util.List.of(cat.locomotionClips()));
                    for (String name : stances) {
                        if (anims.get(name) == null) {
                            System.err.println("FAIL: animation '" + name + "' missing from baked "
                                    + animFile + " (baked: " + anims.keySet() + ")");
                            failures++;
                        }
                    }
                    for (String name : cat.allAttacks()) {
                        if (anims.get(name) == null) {
                            System.err.println("FAIL: attack animation '" + name + "' missing from baked "
                                    + animFile + " (baked: " + anims.keySet() + ")");
                            failures++;
                        }
                    }
                    if (anims.get(cat.putAway) == null) {
                        System.err.println("FAIL: generated put_away clip missing from " + animFile);
                        failures++;
                    }
                    System.out.println("baked animations in " + animFile + ": " + anims.keySet());
                }

                if (!bakedModels.containsKey(modelFile)) {
                    System.err.println("FAIL: model file not baked: " + modelFile);
                    failures++;
                } else {
                    var model = bakedModels.get(modelFile);
                    for (String bone : new String[]{"head", "upper_arm_r", "hand_r", "leg_l", "hand_item_r"}) {
                        if (model.getBone(bone).isEmpty()) {
                            System.err.println("FAIL: bone '" + bone + "' missing from baked model " + modelFile);
                            failures++;
                        }
                    }
                    System.out.println("baked model " + modelFile + " ok, top-level bones: "
                            + model.topLevelBones().length);
                }
            }

            for (FpWeaponClips.Clips fp : List.of(FpWeaponClips.SINGLE, FpWeaponClips.DOUBLE,
                    FpWeaponClips.KATANA, FpWeaponClips.SPEAR)) {
                Identifier modelFile =
                        Identifier.fromNamespaceAndPath(ActionCombat.MODID, fp.modelId());
                Identifier animFile =
                        Identifier.fromNamespaceAndPath(ActionCombat.MODID, fp.animationsId());

                if (!bakedAnims.containsKey(animFile)) {
                    System.err.println("FAIL: animation file not baked: " + animFile);
                    failures++;
                } else {
                    var anims = bakedAnims.get(animFile).animations();
                    List<String> clips = new java.util.ArrayList<>();
                    for (String name : new String[]{fp.idle(), fp.inspect(), fp.runStart(),
                            fp.runHold(), fp.runEnd(), fp.walkStart(), fp.walkHold(),
                            fp.walkEnd(), fp.draw(), fp.putAway(),
                            fp.defend(), fp.aiming(), "static_pose"}) {
                        if (name != null) clips.add(name);
                    }
                    clips.addAll(List.of(fp.leftAttacks()));
                    clips.addAll(List.of(fp.rightAttacks()));
                    for (String name : clips) {
                        if (name != null && anims.get(name) == null) {
                            System.err.println("FAIL: animation '" + name + "' missing from baked "
                                    + animFile + " (baked: " + anims.keySet() + ")");
                            failures++;
                        }
                    }
                    System.out.println("baked animations in " + animFile + ": " + anims.keySet());
                }

                if (!bakedModels.containsKey(modelFile)) {
                    System.err.println("FAIL: model file not baked: " + modelFile);
                    failures++;
                } else {
                    var model = bakedModels.get(modelFile);
                    String[] bones = fp.modelId().endsWith("doublesword")
                            ? new String[]{"right_arm", "left_arm", "right_arm_item", "left_arm_item"}
                            : new String[]{"root", "knife_R", "right", "left", "elucidator"};
                    for (String bone : bones) {
                        if (model.getBone(bone).isEmpty()) {
                            System.err.println("FAIL: bone '" + bone + "' missing from baked model " + modelFile);
                            failures++;
                        }
                    }
                    System.out.println("baked model " + modelFile + " ok, top-level bones: "
                            + model.topLevelBones().length);
                }
            }
        }

        if (failures > 0) {
            System.err.println("smoke test FAILED: " + failures + " problem(s)");
            System.exit(1);
        }
        System.out.println("asset bake smoke test PASSED");
    }

    private static void verifyFirstPersonCameraPose() {
        for (boolean dual : new boolean[]{false, true}) {
            var reference = new com.mojang.blaze3d.vertex.PoseStack();
            com.actioncombat.client.FpViewmodelRenderer.applyViewmodelPose(reference, dual);
            for (float yaw : new float[]{0, 90, 180, 270}) {
                for (float pitch : new float[]{-60, 0, 60}) {
                    var rotation = new org.joml.Quaternionf().rotationYXZ(
                            yaw * (float) Math.PI / 180, pitch * (float) Math.PI / 180, 0);
                    var pose = new com.mojang.blaze3d.vertex.PoseStack();
                    pose.mulPose(rotation);
                    var original = new org.joml.Matrix4f(pose.last().pose());
                    pose.pushPose();
                    com.actioncombat.client.FpViewmodelRenderer.applyViewmodelPose(pose, dual);
                    var inCamera = new org.joml.Matrix4f().rotation(rotation.conjugate(new org.joml.Quaternionf()))
                            .mul(pose.last().pose());
                    if (!inCamera.equals(reference.last().pose(), 0.00001F)) {
                        throw new AssertionError("FP pose depends on camera yaw/pitch: " + yaw + "/" + pitch);
                    }
                    pose.popPose();
                    if (!pose.last().pose().equals(original)) {
                        throw new AssertionError("FP pose leaked into the other hand pass");
                    }
                }
            }
        }
        System.out.println("FP camera pose: 24 yaw/pitch/perspective cases passed");
    }

    private static void verifyFirstPersonUpperArms(software.bernie.geckolib.cache.model.BakedGeoModel model) {
        if (model == null) throw new AssertionError("dual FP model missing for shoulder projection check");
        int poses = 0;
        var framing = new com.mojang.blaze3d.vertex.PoseStack();
        com.actioncombat.client.FpViewmodelRenderer.applyViewmodelPose(framing, true);
        for (boolean left : new boolean[]{false, true}) {
            var bone = model.getBone(left ? "fp_upper_arm_l" : "fp_upper_arm_r").orElseThrow();
            for (float x : new float[]{-1.5F, -0.5F, 0, 0.5F, 1.5F}) {
                for (float y : new float[]{0.5F, 1.5F, 2.5F}) {
                    for (float z : new float[]{-1.5F, -0.5F, 0.5F, 1.5F}) {
                        for (float rotation : new float[]{-2.4F, 0, 1.3F}) {
                            var elbow = new org.joml.Vector3f(x, y, z);
                            var shoulder = com.actioncombat.client.FpViewmodelRenderer.shoulderAnchor(left);
                            var snapshot = software.bernie.geckolib.animation.state.BoneSnapshot.create(bone);
                            var forearm = new org.joml.Quaternionf().rotationZYX(rotation, rotation * 0.4F, rotation * -0.8F);
                            com.actioncombat.client.FpViewmodelRenderer.poseUpperArm(snapshot, elbow, shoulder, forearm);
                            var pose = new org.joml.Matrix4f().translate(-snapshot.getTranslateX() / 16,
                                            snapshot.getTranslateY() / 16, snapshot.getTranslateZ() / 16)
                                    .rotateZYX(snapshot.getRotZ(), snapshot.getRotY(), snapshot.getRotX())
                                    .scale(snapshot.getScaleX(), snapshot.getScaleY(), snapshot.getScaleZ());
                            var endpoint = pose.transformPosition(new org.joml.Vector3f(0, 1.0F / 16, 0));
                            if (endpoint.distance(shoulder) > 0.00001F) {
                                throw new AssertionError("upper-arm pose does not reach camera shoulder anchor");
                            }
                            var localElbow = new org.joml.Matrix4f(pose).invert().transformPosition(new org.joml.Vector3f(elbow));
                            if (Math.abs(localElbow.x) > 0.00001F || Math.abs(localElbow.z) > 0.00001F
                                    || localElbow.y <= 0 || localElbow.y >= 1.0F / 16) {
                                throw new AssertionError("animated elbow is detached from upper-arm segment");
                            }
                            var inCamera = new org.joml.Matrix4f(framing.last().pose()).mul(pose);
                            for (float cx : new float[]{-2.25F / 16, 2.25F / 16}) {
                                for (float cz : new float[]{-2.25F / 16, 2.25F / 16}) {
                                    var cap = inCamera.transformPosition(new org.joml.Vector3f(cx, 1.0F / 16, cz));
                                    if (cap.z <= 0.1F) throw new AssertionError("visible first-person shoulder cap: " + cap);
                                }
                            }
                            poses++;
                        }
                    }
                }
            }
        }
        System.out.println("FP upper-arm geometry: " + poses + " JOML elbow/shoulder/cap poses passed");
    }
}

package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import net.minecraft.util.Mth;
import software.bernie.geckolib.renderer.base.BoneSnapshots;

/**
 * Drives the blade-trail and impact-spark bones that ship with the first-person rig.
 * <p>
 * `fp_singlesword.geo.json` contains five `daoguang*` trail quads and four `spark*`
 * quads, but no authored animation touches them, so they sat permanently stacked at
 * their rest pose — visible clutter rather than an effect. Rather than hand-author
 * clips, they are posed procedurally each frame: the trail quads fan out behind the
 * blade over the course of a swing and shrink away, and the sparks scatter briefly on a
 * confirmed hit.
 * <p>
 * Hidden by default. Zero-thickness quads cannot be scaled to nothing reliably, so
 * unused segments are hidden outright via the bone snapshot.
 */
public final class BladeVfx {
    /** Trail quads, ordered from the one nearest the hilt outward. */
    private static final String[] TRAIL_BONES = {
            "daoguang_illuminated",
            "daoguang1_illuminated",
            "daoguang2_illuminated",
            "daoguang3_illuminated",
            "daoguang4_illuminated",
    };

    private static final String[] SPARK_BONES = {"spark1", "spark2", "spark3", "spark4"};
    private static final String SPARK_ROOT = "spark_illuminated";

    /** How far apart, in degrees, consecutive trail quads sit along the swing arc. */
    private static final float TRAIL_ARC_STEP = 13.0F;

    /**
     * Pose every VFX bone for this frame.
     *
     * @param snapshots       bones of the rig being rendered
     * @param swingProgress   0..1 through the current swing, or -1 when not swinging
     * @param sparkProgress   0..1 through the spark lifetime, or -1 when no recent hit
     */
    public static void apply(BoneSnapshots snapshots, float swingProgress, float sparkProgress) {
        applyTrail(snapshots, swingProgress);
        applySparks(snapshots, sparkProgress);
    }

    private static void applyTrail(BoneSnapshots snapshots, float progress) {
        boolean active = progress >= 0.0F && progress <= 1.0F;

        for (int i = 0; i < TRAIL_BONES.length; i++) {
            final int index = i;
            snapshots.ifPresent(TRAIL_BONES[i], snapshot -> {
                if (!active) {
                    snapshot.skipRender(true);
                    return;
                }
                // each quad lags the blade a little further, tracing where it has been
                float lag = index / (float) TRAIL_BONES.length;
                float segmentProgress = progress - lag * 0.35F;
                if (segmentProgress <= 0.0F) {
                    snapshot.skipRender(true);
                    return;
                }

                // fade the whole trail out over the second half of the swing, and fade
                // each segment as it falls behind, so the tail dissolves first
                float fade = (1.0F - progress) * (1.0F - lag * 0.6F);
                if (fade <= 0.02F) {
                    snapshot.skipRender(true);
                    return;
                }

                snapshot.skipRender(false);
                snapshot.setRotation(
                        snapshot.getRotX(),
                        snapshot.getRotY() + (float) Math.toRadians(-TRAIL_ARC_STEP * index),
                        snapshot.getRotZ());
                // widen with the swing then thin out as it fades
                float scale = Mth.clamp(fade * 1.4F, 0.0F, 1.0F);
                snapshot.setScale(scale, scale, scale);
            });
        }
    }

    private static void applySparks(BoneSnapshots snapshots, float progress) {
        boolean active = progress >= 0.0F && progress <= 1.0F;

        snapshots.ifPresent(SPARK_ROOT, snapshot -> snapshot.skipChildrenRender(!active));

        for (int i = 0; i < SPARK_BONES.length; i++) {
            final int index = i;
            snapshots.ifPresent(SPARK_BONES[i], snapshot -> {
                if (!active) {
                    snapshot.skipRender(true);
                    return;
                }
                snapshot.skipRender(false);

                // deterministic fan so sparks read as a burst rather than random noise
                float angle = (float) (index * (Mth.TWO_PI / SPARK_BONES.length));
                float spread = progress * 0.35F;
                snapshot.setTranslation(
                        Mth.cos(angle) * spread,
                        Mth.sin(angle) * spread - progress * 0.1F, // slight fall
                        0.0F);
                snapshot.setRotation(
                        snapshot.getRotX(),
                        snapshot.getRotY(),
                        snapshot.getRotZ() + angle);
                // shrink away over the spark lifetime
                float scale = 1.0F - progress;
                snapshot.setScale(scale, scale, scale);
            });
        }
    }

    /** 0..1 spark lifetime progress, or -1 when the last hit is too old. */
    public static float sparkProgress(long lastHitNanos) {
        if (lastHitNanos <= 0L) return -1.0F;
        float elapsed = (System.nanoTime() - lastHitNanos) / 1.0E9F;
        return elapsed <= CombatConstants.SPARK_SECONDS
                ? elapsed / CombatConstants.SPARK_SECONDS
                : -1.0F;
    }

    private BladeVfx() {}
}

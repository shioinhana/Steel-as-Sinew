package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Combat crosshair. Drawn at the projected 3D impact of the camera look ray so it
 * sits on the actual strike point rather than vanilla screen-center.
 */
public final class CombatHud {
    private CombatHud() {
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (!ClientCombat.isLocalDrawn()) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;

        Vec3 origin = camera.position();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        boolean rapier = ClientCombat.localWeaponCategory() == com.actioncombat.combat.WeaponCategory.SINGLE;
        double range = rapier ? 16.0 : CombatConstants.ATTACK_RANGE + 1.0;
        Vec3 end = origin.add(look.scale(range));

        HitResult block = mc.level.clip(new ClipContext(
                origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 impact = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        boolean onEntity = false;

        AABB box = player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult entityHit = traceEntity(player, origin, end, box);
        if (entityHit != null) {
            double entityDist = entityHit.getLocation().distanceToSqr(origin);
            double blockDist = impact.distanceToSqr(origin);
            if (entityDist < blockDist) {
                impact = entityHit.getLocation();
                onEntity = true;
            }
        }

        float[] screen = new float[2];
        if (!worldToScreen(impact, camera, graphics.guiWidth(), graphics.guiHeight(), screen)) {
            return;
        }
        int x = Math.round(screen[0]);
        int y = Math.round(screen[1]);
        int color = onEntity ? 0xFFFF4A4A : 0xFFFFFFFF;
        int outline = 0xFF000000;
        drawReticle(graphics, x, y, outline, 5);
        drawReticle(graphics, x, y, color, 4);
    }

    private static void drawReticle(GuiGraphics g, int x, int y, int color, int arm) {
        int gap = 2;
        g.fill(x - arm, y, x - gap, y + 1, color);
        g.fill(x + gap + 1, y, x + arm + 1, y + 1, color);
        g.fill(x, y - arm, x + 1, y - gap, color);
        g.fill(x, y + gap + 1, x + 1, y + arm + 1, color);
        g.fill(x, y, x + 1, y + 1, color);
    }

    private static EntityHitResult traceEntity(LocalPlayer player, Vec3 start, Vec3 end, AABB search) {
        EntityHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : player.level().getEntities(player, search, e ->
                e instanceof LivingEntity living && living.isAlive() && !living.isSpectator())) {
            boolean rapier = ClientCombat.localWeaponCategory() == com.actioncombat.combat.WeaponCategory.SINGLE;
            AABB bb = rapier ? entity.getBoundingBox() : entity.getBoundingBox().inflate(0.15);
            var hit = bb.clip(start, end);
            if (hit.isEmpty()) continue;
            if (rapier && player.getEyePosition().distanceToSqr(hit.get()) > CombatConstants.RAPIER_RANGE * CombatConstants.RAPIER_RANGE) continue;
            if (rapier && !player.hasLineOfSight(entity)) continue;
            double d = start.distanceToSqr(hit.get());
            if (d < bestDist) {
                bestDist = d;
                best = new EntityHitResult(entity, hit.get());
            }
        }
        return best;
    }

    private static boolean worldToScreen(Vec3 world, Camera camera, int width, int height, float[] out) {
        Vector3f rel = new Vector3f(
                (float) (world.x - camera.position().x),
                (float) (world.y - camera.position().y),
                (float) (world.z - camera.position().z));
        Quaternionf inv = new Quaternionf(camera.rotation()).conjugate();
        inv.transform(rel);
        float depth = -rel.z;
        if (depth < 0.05F) return false;
        Minecraft mc = Minecraft.getInstance();
        float fov = mc.options.fov().get().intValue() + ClientCombat.currentFovBias();
        float tan = (float) Math.tan(Math.toRadians(Math.max(20.0F, fov) * 0.5));
        float aspect = (float) width / (float) height;
        float ndcX = rel.x / (depth * tan * aspect);
        float ndcY = rel.y / (depth * tan);
        out[0] = (ndcX + 1.0F) * 0.5F * width;
        out[1] = (1.0F - ndcY) * 0.5F * height;
        return Mth.abs(ndcX) <= 1.4F && Mth.abs(ndcY) <= 1.4F;
    }
}

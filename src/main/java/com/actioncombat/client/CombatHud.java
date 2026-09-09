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

        boolean dual = ClientCombat.isLocalDualWield();
        boolean spear = ClientCombat.localWeaponCategory() == com.actioncombat.combat.WeaponCategory.SPEAR;
        if (dual || spear) {
            Vec3 origin = spear ? player.getEyePosition() : camera.position();
            Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
            double range = spear ? (ModKeybinds.CROUCH.isDown()
                    ? (mc.options.keyUse.isDown() ? com.actioncombat.combat.SpearMoves.BASH_RANGE : com.actioncombat.combat.SpearMoves.LONG_RANGE)
                    : com.actioncombat.combat.SpearMoves.POKE_RANGE) : CombatConstants.ATTACK_RANGE + 1.0;
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
            if (worldToScreen(impact, camera, graphics.guiWidth(), graphics.guiHeight(), screen)) {
                int x = Math.round(screen[0]);
                int y = Math.round(screen[1]);
                int color = onEntity ? 0xFFFF4A4A : 0xFFFFFFFF;
                drawReticle(graphics, x, y, 0xFF000000, 5);
                drawReticle(graphics, x, y, color, 4);
            }
        }

        LivingEntity lock = ClientCombat.lockTarget();
        if (lock != null) {
            Vec3 markerPos = lock.getEyePosition().add(0.0, lock.getBbHeight() * 0.35 + 0.25, 0.0);
            float[] lockScreen = new float[2];
            if (worldToScreen(markerPos, camera, graphics.guiWidth(), graphics.guiHeight(), lockScreen)) {
                int lx = Math.round(lockScreen[0]);
                int ly = Math.round(lockScreen[1]);
                drawDiamond(graphics, lx, ly, 0xFF000000, 6);
                drawDiamond(graphics, lx, ly, 0xFFFFD24A, 5);
                var font = mc.font;
                // Name, then health, then the rapier streak / AI-deprivation timer:
                // three lines stacked over the marker, all in the vanilla font.
                var name = lock.getDisplayName();
                if (name != null) {
                    drawCentered(graphics, font, name.getString(), lx, ly - 44, 0xFFFFD24A);
                }
                drawCentered(graphics, font, healthLabel(lock), lx, ly - 32, healthColor(lock));
            }
        }

        // Rapier streak on the enemy it is counted on (usually the locked one).
        int comboId = ClientCombat.usesLockOn() ? ClientCombat.rapierComboTargetId() : -1;
        if (comboId >= 0 && mc.level != null) {
            Entity target = mc.level.getEntity(comboId);
            if (target instanceof LivingEntity living && living.isAlive()) {
                Vec3 pos = living.getEyePosition().add(0.0, living.getBbHeight() * 0.35 + 0.25, 0.0);
                float[] screen = new float[2];
                if (worldToScreen(pos, camera, graphics.guiWidth(), graphics.guiHeight(), screen)) {
                    String label = comboLabel();
                    if (label != null) {
                        // Green while the enemy is actually reachable, yellow outside.
                        boolean reachable = canReach(player, living);
                        drawCentered(graphics, mc.font, label, Math.round(screen[0]),
                                Math.round(screen[1]) - 18,
                                reachable ? 0xFF55FF55 : 0xFFFFD24A);
                    }
                }
            }
        }
    }

    /** True when the rapier could actually connect right now: range plus line of sight. */
    private static boolean canReach(LocalPlayer player, LivingEntity target) {
        if (player == null || target == null || !target.isAlive()) return false;
        if (player.distanceToSqr(target)
                > CombatConstants.RAPIER_RANGE * CombatConstants.RAPIER_RANGE) return false;
        return player.hasLineOfSight(target);
    }

    /**
     * Streak / AI-deprivation readout. The text is rebuilt at most every 0.1s so the
     * countdown ticks in tenths instead of every frame.
     */
    private static String comboLabel() {
        long now = System.nanoTime();
        if (comboLabelNanos != 0L && now - comboLabelNanos < 100_000_000L) return comboLabelCache;
        comboLabelNanos = now;
        float silence = ClientCombat.rapierSilenceSeconds();
        if (silence > 0.0F) {
            comboLabelCache = String.format(java.util.Locale.ROOT, "%.1fs", silence);
        } else {
            int hits = ClientCombat.rapierComboHits();
            comboLabelCache = hits <= 0 ? null : hits + "/" + CombatConstants.RAPIER_AI_CANCEL_HITS;
        }
        return comboLabelCache;
    }

    private static String comboLabelCache;
    private static long comboLabelNanos;

    private static void drawCentered(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                     String text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, true);
    }

    /** Integer health when it is whole, one decimal otherwise. */
    private static String healthLabel(LivingEntity entity) {
        float health = Math.max(0.0F, entity.getHealth());
        String current = health == Math.round(health)
                ? String.valueOf(Math.round(health))
                : String.format(java.util.Locale.ROOT, "%.1f", health);
        return current + " / " + Math.round(entity.getMaxHealth());
    }

    private static int healthColor(LivingEntity entity) {
        float ratio = entity.getMaxHealth() <= 0.0F
                ? 0.0F : Math.max(0.0F, Math.min(1.0F, entity.getHealth() / entity.getMaxHealth()));
        int green = Math.round(0x50 + 0xAF * ratio);
        int red = Math.round(0xFF - 0xAF * ratio);
        return 0xFF000000 | (red << 16) | (green << 8) | 0x50;
    }

    private static void drawDiamond(GuiGraphics g, int x, int y, int color, int r) {
        for (int i = 0; i < r; i++) {
            int span = r - i;
            g.fill(x - span, y - i, x - span + 1, y - i + 1, color);
            g.fill(x + span - 1, y - i, x + span, y - i + 1, color);
            g.fill(x - span, y + i, x - span + 1, y + i + 1, color);
            g.fill(x + span - 1, y + i, x + span, y + i + 1, color);
        }
        g.fill(x, y, x + 1, y + 1, color);
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

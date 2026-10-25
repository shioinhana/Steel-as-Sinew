package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** A local visual explanation of the actual obstruction, independent of damage or interaction packets. */
public final class ShoulderOcclusionPresentation {
    private record Blocked(HitResult target, HitResult obstruction, String mode) {}
    private record Visual(Blocked blocked, VoxelShape shape, Vec3 origin, Vec3 camera, Vec3 anchor,
                          ShoulderViewRay.Frame projection, long sequence) {}
    private static final ContextKey<Visual> KEY = new ContextKey<>(Identifier.fromNamespaceAndPath("fsrpg", "shoulder_obstruction"));
    private static final int COLOR = 0xffff7851;
    private static ClientLevel level;
    private static LocalPlayer player;
    private static Visual current;
    private static long frameSequence, worldDraws, blockDraws, entityDraws, labelDraws, lastDrawFrame, lastLabelFrame;
    private static int labelX, labelY;
    private static boolean labelVisible;

    private ShoulderOcclusionPresentation() {}

    private static boolean visibleScope() {
        var game = Minecraft.getInstance();
        return game.player != null && game.level != null && game.player.isAlive() && !game.player.isSpectator()
                && game.getCameraEntity() == game.player && game.screen == null && !game.options.hideGui
                && CombatCamera.active();
    }
    private static void synchronize() {
        var game = Minecraft.getInstance();
        if (level == game.level && player == game.player) return;
        level = game.level; player = game.player; clear();
        frameSequence = worldDraws = blockDraws = entityDraws = labelDraws = lastDrawFrame = lastLabelFrame = 0;
    }
    public static void clear() { current = null; labelVisible = false; }

    /** Snapshot geometry in the official extraction phase; the later render phase never consults mutable world state. */
    public static void extract(ExtractLevelRenderStateEvent event) {
        synchronize(); clear(); frameSequence++;
        event.getRenderState().setRenderData(KEY, null);
        if (!visibleScope()) return;
        var projection = ShoulderInteractionView.current();
        if (projection == null) return;
        Blocked blocked;
        if (ShoulderInteraction.active()) {
            var obstruction = ShoulderInteraction.obstruction();
            blocked = obstruction == null ? null : new Blocked(ShoulderInteraction.intendedTarget(), obstruction, "ordinary");
        } else if (ClientState.drawn()) blocked = drawnObstruction(projection);
        else blocked = null;
        if (blocked == null) return;

        VoxelShape shape;
        Vec3 origin, anchor;
        if (blocked.obstruction() instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var state = event.getLevel().getBlockState(hit.getBlockPos());
            if (state.isAir()) return;
            shape = state.getShape(event.getLevel(), hit.getBlockPos(), CollisionContext.of(player));
            if (shape.isEmpty()) return;
            origin = Vec3.atLowerCornerOf(hit.getBlockPos());
            var bounds = shape.bounds();
            anchor = origin.add((bounds.minX + bounds.maxX) * .5, bounds.maxY + .12, (bounds.minZ + bounds.maxZ) * .5);
        } else if (blocked.obstruction() instanceof EntityHitResult hit) {
            var entity = hit.getEntity();
            if (!entity.isAlive() || entity.isRemoved()) return;
            float partial = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var box = entity.getBoundingBox().move(entity.getPosition(partial).subtract(entity.position())).inflate(.012);
            shape = Shapes.create(box); origin = Vec3.ZERO;
            anchor = new Vec3((box.minX + box.maxX) * .5, box.maxY + .12, (box.minZ + box.maxZ) * .5);
        } else return;
        current = new Visual(blocked, shape, origin, event.getCamera().position(), anchor, projection, frameSequence);
        event.getRenderState().setRenderData(KEY, current);
    }

    /** Drawn weapons keep their existing authoritative combat; this ray is for explanation only. */
    private static Blocked drawnObstruction(ShoulderViewRay.Frame projection) {
        var game = Minecraft.getInstance();
        Vec3 eye = player.getEyePosition();
        HitResult target;
        Entity locked = CombatCamera.lockedTarget();
        if (locked != null) {
            target = new EntityHitResult(locked, locked.getBoundingBox().getCenter());
        } else {
            double x = 0, y = 0;
            if (CombatCamera.style().equals("spear")) {
                x = 2 * CombatCamera.cursorX() / game.getWindow().getScreenWidth();
                y = -2 * CombatCamera.cursorY() / game.getWindow().getScreenHeight();
            }
            var point = projection.directionAt(x, y);
            if (point == null) return null;
            Vec3 start = vector(projection.origin()), direction = vector(point);
            // Existing drawn target acquisition is limited to ten blocks. This does not grant attack reach.
            Vec3 end = start.add(direction.scale(10 + start.distanceTo(eye)));
            var block = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            double nearest = start.distanceToSqr(block.getLocation());
            var entity = ProjectileUtil.getEntityHitResult(player, start, end, new AABB(start, end).inflate(1),
                    EntitySelector.CAN_BE_PICKED.and(other -> other.getRootVehicle() != player.getRootVehicle()), nearest);
            target = entity != null && start.distanceToSqr(entity.getLocation()) < nearest ? entity : block;
            if (target.getType() == HitResult.Type.MISS || eye.distanceToSqr(target.getLocation()) > 100) return null;
        }
        Entity selected = target instanceof EntityHitResult hit ? hit.getEntity() : null;
        Vec3 end = target.getLocation();
        if (selected != null) {
            var box = selected.getBoundingBox().inflate(selected.getPickRadius());
            end = box.contains(eye) ? eye : box.clip(eye, end).orElse(end);
        }
        Vec3 direction = end.subtract(eye).normalize();
        var wall = level.clip(new ClipContext(eye, end.add(direction.scale(1e-7)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        boolean sameBlock = target instanceof BlockHitResult selectedBlock && wall.getType() == HitResult.Type.BLOCK
                && selectedBlock.getBlockPos().equals(wall.getBlockPos());
        HitResult obstruction = wall.getType() == HitResult.Type.BLOCK && !sameBlock ? wall : null;
        double nearest = eye.distanceToSqr(obstruction == null ? end : obstruction.getLocation());
        if (nearest > 1e-8) {
            var entity = ProjectileUtil.getEntityHitResult(player, eye, end, new AABB(eye, end).inflate(1),
                    EntitySelector.CAN_BE_PICKED.and(other -> other != selected && other.getRootVehicle() != player.getRootVehicle()), nearest);
            if (entity != null && eye.distanceToSqr(entity.getLocation()) + 1e-8 < nearest) obstruction = entity;
        }
        return obstruction == null ? null : new Blocked(target, obstruction, "drawn");
    }

    /** Registered on AfterEntities so the geometry is painted while the real world matrices are still active. */
    public static void render(RenderLevelStageEvent.AfterEntities event) {
        var visual = event.getLevelRenderState().getRenderData(KEY);
        if (visual == null || visual != current || visual.sequence() != frameSequence || !visibleScope()) return;
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var type = RenderTypes.lines();
        var offset = visual.origin().subtract(visual.camera());
        ShapeRenderer.renderShape(event.getPoseStack(), buffers.getBuffer(type), visual.shape(), offset.x, offset.y, offset.z, COLOR, 3.5f);
        buffers.endBatch(type);
        worldDraws++; lastDrawFrame = visual.sequence();
        if (visual.blocked().obstruction() instanceof EntityHitResult) entityDraws++; else blockDraws++;
    }

    public static void hud(GuiGraphics gui) {
        synchronize(); labelVisible = false;
        if (!visibleScope()) { clear(); return; }
        var visual = current;
        if (visual == null || visual.sequence() != lastDrawFrame) return;
        var anchor = visual.anchor();
        var point = visual.projection().project(new ShoulderViewRay.Point(anchor.x, anchor.y, anchor.z));
        if (!point.inFront() || !Double.isFinite(point.x()) || !Double.isFinite(point.y()) || Math.abs(point.x()) > 1 || Math.abs(point.y()) > 1) return;
        var game = Minecraft.getInstance();
        var text = Component.translatable("message.fsrpg.shoulder_interaction.obstructed");
        int width = game.font.width(text);
        labelX = (int) Math.clamp(Math.round((point.x() + 1) * gui.guiWidth() * .5), width / 2 + 4, Math.max(width / 2 + 4, gui.guiWidth() - width / 2 - 4));
        labelY = (int) Math.clamp(Math.round((1 - point.y()) * gui.guiHeight() * .5) - 12, 4, Math.max(4, gui.guiHeight() - 15));
        gui.fill(labelX - width / 2 - 4, labelY - 3, labelX + width / 2 + 4, labelY + game.font.lineHeight + 3, 0xcc201411);
        gui.drawString(game.font, text, labelX - width / 2, labelY, COLOR, true);
        labelVisible = true; labelDraws++; lastLabelFrame = visual.sequence();
    }

    /** Only the acceptance harness materializes this diagnostic JSON; rendering uses typed values. */
    public static JsonObject diagnostics() {
        synchronize();
        if (!visibleScope()) clear();
        var result = new JsonObject(); var visual = current;
        result.addProperty("active", visual != null);
        result.addProperty("mode", visual == null ? "none" : visual.blocked().mode());
        result.addProperty("label", Component.translatable("message.fsrpg.shoulder_interaction.obstructed").getString());
        result.addProperty("labelVisible", labelVisible && visual != null);
        result.add("obstruction", hit(visual == null ? null : visual.blocked().obstruction()));
        result.add("target", hit(visual == null ? null : visual.blocked().target()));
        result.addProperty("worldDraws", worldDraws); result.addProperty("blockDraws", blockDraws); result.addProperty("entityDraws", entityDraws);
        result.addProperty("labelDraws", labelDraws); result.addProperty("labelX", labelX); result.addProperty("labelY", labelY);
        result.addProperty("frameSequence", frameSequence); result.addProperty("lastDrawFrame", lastDrawFrame); result.addProperty("lastLabelFrame", lastLabelFrame);
        return result;
    }
    private static Vec3 vector(ShoulderViewRay.Point point) { return new Vec3(point.x(), point.y(), point.z()); }
    private static JsonObject hit(HitResult hit) {
        var result = new JsonObject(); result.addProperty("type", hit == null ? "NONE" : hit.getType().name());
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            var pos = new JsonObject(); pos.addProperty("x", block.getBlockPos().getX()); pos.addProperty("y", block.getBlockPos().getY()); pos.addProperty("z", block.getBlockPos().getZ());
            result.add("blockPos", pos);
        } else if (hit instanceof EntityHitResult entity) result.addProperty("entityId", entity.getEntity().getId());
        return result;
    }
}

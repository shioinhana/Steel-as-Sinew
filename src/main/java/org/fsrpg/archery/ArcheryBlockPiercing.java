package org.fsrpg.archery;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The server impact and client landing preview use exactly the same collision-shape traversal.
 *
 * <p>Vanilla grounds an arrow that starts a tick inside any collision box, so every connected solid
 * block has to be crossed inside the single impact that reached the wall. The probe after a real
 * exit face re-tests containment against the same collision shape the vanilla physics uses instead
 * of a fixed 1.05 step, which makes oblique shots, corner exits and partial shapes (slabs, fences,
 * stairs) behave correctly. The world border is never traversed.
 */
public final class ArcheryBlockPiercing {
    private ArcheryBlockPiercing() {}

    public record Result(Vec3 position, int blocks, double speedFactor, BlockHitResult stoppedAt) {}

    public static Result trace(Level level, Entity context, BlockHitResult hit, Vec3 direction, int budget, double slow) {
        if (hit.isWorldBorderHit()) return new Result(hit.getLocation(), 0, 1, hit);
        ArcheryBallistics.Vector ray;
        try { ray = vector(direction).normalized(); }
        catch (IllegalArgumentException degenerate) { return new Result(hit.getLocation(), 0, 1, hit); }
        int clamped = Math.max(0, Math.min(ArcheryBallistics.MAX_BLOCKS, budget));
        var result = ArcheryBallistics.penetrate(obstacle(level, context, hit), ray, clamped, slow,
                position -> probe(level, context, position, ray));
        return new Result(vec3(result.position()), result.blocks(), result.speedFactor(),
                result.stoppedAt() == null ? null : (BlockHitResult) result.stoppedAt().identity());
    }

    private static ArcheryBallistics.Obstacle obstacle(Level level, Entity context, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        return new ArcheryBallistics.Obstacle(hit, vector(hit.getLocation()), boxes(level, context, pos));
    }

    private static List<ArcheryBallistics.Box> boxes(Level level, Entity context, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(context));
        if (shape.isEmpty()) return List.of();
        List<ArcheryBallistics.Box> boxes = new ArrayList<>();
        for (var box : shape.toAabbs()) boxes.add(new ArcheryBallistics.Box(
                box.minX + pos.getX(), box.minY + pos.getY(), box.minZ + pos.getZ(),
                box.maxX + pos.getX(), box.maxY + pos.getY(), box.maxZ + pos.getZ()));
        return List.copyOf(boxes);
    }

    /**
     * Find the next block whose real collision shape already contains the exit point. Free space
     * ends this tick's traversal; the next tick's ordinary vanilla clip continues the flight.
     */
    private static ArcheryBallistics.Obstacle probe(Level level, Entity context,
            ArcheryBallistics.Vector point, ArcheryBallistics.Vector ray) {
        Vec3 position = vec3(point);
        WorldBorder border = level.getWorldBorder();
        if (!border.isWithinBounds(position)) {
            Vec3 clamped = border.clampVec3ToBound(position);
            Direction direction = Direction.getApproximateNearest(clamped.x - position.x, clamped.y - position.y, clamped.z - position.z);
            BlockHitResult hit = new BlockHitResult(clamped, direction, BlockPos.containing(clamped), false, true);
            return new ArcheryBallistics.Obstacle(hit, vector(clamped), List.of());
        }
        BlockPos pos = BlockPos.containing(position);
        List<ArcheryBallistics.Box> boxes = boxes(level, context, pos);
        for (var box : boxes) if (box.contains(point)) {
            Direction direction = Direction.getApproximateNearest(-ray.x(), -ray.y(), -ray.z());
            BlockHitResult hit = new BlockHitResult(position, direction, pos, true);
            return new ArcheryBallistics.Obstacle(hit, point, boxes);
        }
        return null;
    }

    public static ArcheryBallistics.Vector vector(Vec3 v) { return new ArcheryBallistics.Vector(v.x, v.y, v.z); }

    public static Vec3 vec3(ArcheryBallistics.Vector v) { return new Vec3(v.x(), v.y(), v.z()); }
}

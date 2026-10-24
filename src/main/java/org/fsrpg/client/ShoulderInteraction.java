package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Camera selection for ordinary left clicks; every interaction remains vanilla. */
public final class ShoulderInteraction {
    private record Selection(HitResult raw, HitResult effective, HitResult obstruction,
                             Vec3 camera, Vec3 eye, Vec3 direction, String reason, boolean denied) {}
    private static ClientLevel level;
    private static LocalPlayer player;
    private static Selection selection;
    private static boolean ownsDisplay;
    private static int nativeUseDepth;
    private static long selections, blockedAttempts, blockedStartAttempts, blockedContinueAttempts,
            nativePasses, startPasses, continuePasses, noticeCount;
    private static long lastNoticeTick = Long.MIN_VALUE;
    private static String lastNoticeText = "", lastAttemptType = "", lastAttemptReason = "";

    private static boolean scope() {
        var game = Minecraft.getInstance();
        return game.player != null && game.level != null && game.gameMode != null
                && game.player.isAlive() && !game.player.isSpectator() && game.screen == null
                && game.getCameraEntity() == game.player && CombatCamera.active() && !ClientState.drawn();
    }

    /** STAB reads the player's head direction, not hitResult. Keep its native
     * targeting and packet path; the shoulder HUD projects that native target. */
    public static boolean nativeDirectionalAttack() {
        var game = Minecraft.getInstance();
        return scope() && game.player.getMainHandItem().has(DataComponents.PIERCING_WEAPON);
    }

    public static boolean active() { return scope() && !nativeDirectionalAttack(); }
    public static boolean blocked() { return active() && selection != null && selection.denied(); }

    private static void synchronize() {
        var game = Minecraft.getInstance();
        if (level == game.level && player == game.player) return;
        level = game.level; player = game.player; selection = null;
        ownsDisplay = false; nativeUseDepth = 0;
        selections = blockedAttempts = blockedStartAttempts = blockedContinueAttempts = 0;
        nativePasses = startPasses = continuePasses = noticeCount = 0;
        lastNoticeTick = Long.MIN_VALUE;
        lastNoticeText = lastAttemptType = lastAttemptReason = "";
    }

    /** GameRenderer.pick TAIL runs after the final shoulder camera update. */
    public static void afterPick(float partialTick) {
        synchronize();
        if (nativeUseDepth > 0) return;
        if (active()) refresh(true);
        else {
            // Vanilla just supplied this result; never retain a shoulder hit
            // on a first-person frame, an open screen, or a drawn combo.
            ownsDisplay = false;
            recordBypass();
        }
    }

    /** Called before Minecraft copies hitResult/blockPos into local variables. */
    public static boolean beforeStartAttack() { return beforeAttack(true, "START"); }
    public static boolean beforeContinueAttack(boolean attackDown) { return beforeAttack(attackDown, "CONTINUE"); }

    private static boolean beforeAttack(boolean attempted, String type) {
        synchronize();
        if (!active()) {
            restoreNativeIfOwned();
            return false;
        }
        refresh(false);
        if (!attempted || selection == null) return false;
        lastAttemptType = type; lastAttemptReason = selection.reason();
        if (selection.denied()) {
            // Abort the previous vanilla mining session as well as this input;
            // replacing hitResult alone leaves cracks/held mining alive.
            Minecraft.getInstance().gameMode.stopDestroyBlock();
            blockedAttempts++;
            if (type.equals("START")) blockedStartAttempts++; else blockedContinueAttempts++;
            notice(selection.reason());
            return true;
        }
        nativePasses++;
        if (type.equals("START")) startPasses++; else continuePasses++;
        return false;
    }

    /** Restore the camera selection for the outline after vanilla consumed the
     * validated hit. A denied HEAD remains MISS until the next render pick. */
    public static void afterAttack() {
        synchronize();
        if (active()) refresh(true); else restoreNativeIfOwned();
    }

    /** Right click and middle click are deliberately outside this correction. */
    public static void beforeNativeUse() {
        synchronize();
        if (!active()) { restoreNativeIfOwned(); return; }
        nativeUseDepth++;
        applyNativePick();
    }

    public static void afterNativeUse() {
        synchronize();
        if (nativeUseDepth == 0) return;
        if (--nativeUseDepth == 0) {
            if (active()) refresh(true); else restoreNativeIfOwned();
        }
    }

    private static void restoreNativeIfOwned() {
        if (ownsDisplay) applyNativePick();
        recordBypass();
    }

    private static void applyNativePick() {
        var game = Minecraft.getInstance();
        if (game.player != null && game.level != null && game.getCameraEntity() != null) {
            apply(game.player.raycastHitResult(game.getDeltaTracker().getGameTimeDeltaPartialTick(false), game.getCameraEntity()));
        }
        ownsDisplay = false;
    }

    private static void recordBypass() {
        var game = Minecraft.getInstance();
        Vec3 eye = game.player == null ? Vec3.ZERO : game.player.getEyePosition();
        selection = new Selection(game.hitResult, game.hitResult, null, eye, eye, Vec3.ZERO,
                nativeDirectionalAttack() ? "NATIVE_DIRECTIONAL" : "BYPASS", false);
    }

    private static void refresh(boolean display) {
        var game = Minecraft.getInstance();
        var camera = game.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) { applyNativePick(); recordBypass(); return; }
        // Use the last visible frame's actual view/projection ray, including
        // vanilla bob/hurt/nausea. Input revalidates its target against the
        // current eye/world without advancing or rebuilding the camera.
        var frame = ShoulderInteractionView.current();
        Vec3 start = frame == null ? camera.position()
                : new Vec3(frame.origin().x(), frame.origin().y(), frame.origin().z());
        Vec3 eye = player.getEyePosition();
        Vec3 direction = (frame == null ? new Vec3(camera.forwardVector())
                : new Vec3(frame.direction().x(), frame.direction().y(), frame.direction().z())).normalize();
        if (!finite(start) || !finite(eye) || !finite(direction) || direction.lengthSqr() < 1e-12) {
            applyNativePick(); recordBypass(); return;
        }
        var attackRange = player.getMainHandItem().get(DataComponents.ATTACK_RANGE);
        double blockReach = player.blockInteractionRange();
        double entityReach = attackRange == null ? player.entityInteractionRange()
                : attackRange.effectiveMaxRange(player) + attackRange.hitboxMargin();
        // Extra ray length only reaches the eye's legal interaction domain from
        // the offset camera. Acceptance below always measures from the eye.
        double rayLength = Math.max(blockReach, entityReach) + start.distanceTo(eye);
        if (!Double.isFinite(rayLength) || rayLength <= 0) { applyNativePick(); recordBypass(); return; }
        Vec3 end = start.add(direction.scale(rayLength));
        BlockHitResult block = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double closest = start.distanceToSqr(block.getLocation());
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, start, end,
                new AABB(start, end).inflate(1), EntitySelector.CAN_BE_PICKED, closest);
        HitResult raw = entity != null && start.distanceToSqr(entity.getLocation()) < closest ? entity : block;
        HitResult effective = raw;
        HitResult obstruction = null;
        String reason = raw.getType() == HitResult.Type.MISS ? "MISS" : "CLEAR";
        boolean denied = false;
        if (raw.getType() != HitResult.Type.MISS) {
            Vec3 target = raw.getLocation();
            Vec3 eyeDirection = target.subtract(eye).normalize();
            // A tiny extension includes the target block's surface in the clip
            // despite endpoint rounding. It cannot select a different block.
            Vec3 clipEnd = raw instanceof BlockHitResult ? target.add(eyeDirection.scale(1e-7)) : target;
            BlockHitResult eyeHit = level.clip(new ClipContext(eye, clipEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            boolean sameBlock = raw instanceof BlockHitResult selected && eyeHit.getType() == HitResult.Type.BLOCK
                    && selected.getBlockPos().equals(eyeHit.getBlockPos());
            if (eyeHit.getType() == HitResult.Type.BLOCK && !sameBlock
                    || raw instanceof BlockHitResult && !sameBlock) {
                obstruction = eyeHit;
                reason = "OBSTRUCTED"; denied = true;
            } else if (raw instanceof BlockHitResult) {
                // Use the genuine eye-facing face/location for vanilla block
                // destruction packets, never the camera's rear/side face.
                effective = eyeHit;
                if (!(eye.distanceToSqr(eyeHit.getLocation()) < blockReach * blockReach)) {
                    reason = "OUT_OF_RANGE"; denied = true;
                }
            } else if (attackRange != null ? !attackRange.isInRange(player, target)
                    : !(eye.distanceToSqr(target) < entityReach * entityReach)) {
                reason = "OUT_OF_RANGE"; denied = true;
            }
            if (!denied) {
                // Native eye picking gives a nearer entity priority over a
                // block or another entity. A shoulder camera seeing past it
                // must not silently attack/mine the object behind it.
                var selectedEntity = raw instanceof EntityHitResult selected ? selected.getEntity() : null;
                Vec3 obstructionEnd = effective.getLocation();
                if (selectedEntity != null) {
                    var box = selectedEntity.getBoundingBox().inflate(selectedEntity.getPickRadius());
                    // The camera may hit the far side of this same pick box.
                    // Stop at its first eye-side surface, and exclude the
                    // selected entity itself from the obstruction search.
                    obstructionEnd = box.contains(eye) ? eye : box.clip(eye, obstructionEnd).orElse(obstructionEnd);
                }
                double limit = eye.distanceToSqr(obstructionEnd);
                if (limit > 1e-8) {
                    EntityHitResult nearer = ProjectileUtil.getEntityHitResult(player, eye, obstructionEnd,
                            new AABB(eye, obstructionEnd).inflate(1),
                            EntitySelector.CAN_BE_PICKED.and(other -> other != selectedEntity), limit);
                    if (nearer != null && eye.distanceToSqr(nearer.getLocation()) + 1e-8 < limit) {
                        obstruction = nearer;
                        reason = "OBSTRUCTED"; denied = true;
                    }
                }
            }
            if (denied) effective = miss(target, eyeDirection);
        }
        selection = new Selection(raw, effective, obstruction, start, eye, direction, reason, denied);
        selections++;
        // Display keeps the intended camera outline even when red/blocked.
        // Only the validated effective result ever enters left-click methods.
        apply(display ? raw : effective);
        ownsDisplay = true;
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
    private static BlockHitResult miss(Vec3 point, Vec3 direction) {
        return BlockHitResult.miss(point, Direction.getApproximateNearest(direction), BlockPos.containing(point));
    }
    private static void apply(HitResult hit) {
        var game = Minecraft.getInstance();
        game.hitResult = hit;
        game.crosshairPickEntity = hit instanceof EntityHitResult entity ? entity.getEntity() : null;
    }
    private static void notice(String reason) {
        long now = level.getGameTime();
        if (lastNoticeTick != Long.MIN_VALUE && now >= lastNoticeTick && now - lastNoticeTick < 20) return;
        String key = reason.equals("OUT_OF_RANGE") ? "message.fsrpg.shoulder_interaction.out_of_range"
                : "message.fsrpg.shoulder_interaction.obstructed";
        Component text = Component.translatable(key);
        player.displayClientMessage(text, true);
        lastNoticeTick = now; lastNoticeText = text.getString(); noticeCount++;
    }

    /** Raw is the display intention; effective is the only accepted interaction.
     * Counters observe real entry points, and expose no test-only controls. */
    public static JsonObject diagnostics() {
        synchronize();
        var out = new JsonObject();
        out.addProperty("active", active()); out.addProperty("blocked", blocked());
        out.addProperty("nativeDirectionalAttack", nativeDirectionalAttack());
        out.addProperty("reason", selection == null ? "UNSELECTED" : selection.reason());
        out.add("raw", hit(selection == null ? null : selection.raw()));
        out.add("effective", hit(selection == null ? null : selection.effective()));
        out.add("obstruction", hit(selection == null ? null : selection.obstruction()));
        out.add("display", hit(Minecraft.getInstance().hitResult));
        out.add("camera", vector(selection == null ? Vec3.ZERO : selection.camera()));
        out.add("eye", vector(selection == null ? Vec3.ZERO : selection.eye()));
        out.add("direction", vector(selection == null ? Vec3.ZERO : selection.direction()));
        out.add("target", vector(selection == null || selection.raw() == null ? Vec3.ZERO : selection.raw().getLocation()));
        out.addProperty("selections", selections);
        out.addProperty("blockedAttempts", blockedAttempts); out.addProperty("blockedStartAttempts", blockedStartAttempts);
        out.addProperty("blockedContinueAttempts", blockedContinueAttempts); out.addProperty("nativePasses", nativePasses);
        out.addProperty("startPasses", startPasses); out.addProperty("continuePasses", continuePasses);
        out.addProperty("noticeCount", noticeCount); out.addProperty("lastNoticeTick", lastNoticeTick);
        out.addProperty("lastNoticeText", lastNoticeText); out.addProperty("lastAttemptType", lastAttemptType);
        out.addProperty("lastAttemptReason", lastAttemptReason);
        return out;
    }
    private static JsonObject vector(Vec3 value) {
        var out = new JsonObject();
        out.addProperty("x", value.x); out.addProperty("y", value.y); out.addProperty("z", value.z);
        return out;
    }
    private static JsonObject hit(HitResult value) {
        var out = new JsonObject();
        out.addProperty("type", value == null ? "NONE" : value.getType().name());
        if (value == null) return out;
        out.add("location", vector(value.getLocation()));
        if (value instanceof BlockHitResult block) {
            var pos = new JsonObject();
            pos.addProperty("x", block.getBlockPos().getX()); pos.addProperty("y", block.getBlockPos().getY()); pos.addProperty("z", block.getBlockPos().getZ());
            out.add("blockPos", pos); out.addProperty("face", block.getDirection().name());
        } else if (value instanceof EntityHitResult entity) {
            out.addProperty("entityId", entity.getEntity().getId()); out.addProperty("entityUuid", entity.getEntity().getUUID().toString());
        }
        return out;
    }
    private ShoulderInteraction() {}
}

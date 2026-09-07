package com.actioncombat.combat;

import com.actioncombat.ActionCombat;
import com.actioncombat.CombatConstants;
import com.actioncombat.ModAttachments;
import com.actioncombat.ModPayloads;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = ActionCombat.MODID)
public final class ServerCombat {

    public static boolean isHoldingSword(ServerPlayer player) {
        return isSword(player.getMainHandItem());
    }

    public static WeaponCategory categoryOf(ServerPlayer player) {
        return WeaponCategory.of(
                isSword(player.getMainHandItem()) && isSword(player.getOffhandItem()));
    }

    private static boolean isSword(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.SWORDS);
    }

    public static void handleSetDrawn(ServerPlayer player, boolean drawn) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (drawn && !data.drawn) {
            if (!isHoldingSword(player)) return;
            data.drawn = true;
        } else if (!drawn && data.drawn) {
            data.drawn = false;
            data.resetForSheath();
        } else {
            return;
        }
        if (data.drawn) {
            data.aimYaw = player.getYRot();
            data.aimPitch = player.getXRot();
            var eye = player.getEyePosition();
            data.camX = eye.x;
            data.camY = eye.y;
            data.camZ = eye.z;
            data.hasAim = true;
        }
        broadcastDrawState(player, data.drawn);
        broadcastDefendState(player, false);
    }

    public static void handleAim(ServerPlayer player, float yaw, float pitch,
                                 double camX, double camY, double camZ) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Double.isFinite(camX) || !Double.isFinite(camY) || !Double.isFinite(camZ)) return;
        if (player.getEyePosition().distanceToSqr(new Vec3(camX, camY, camZ)) > 144.0) return;
        data.aimYaw = yaw;
        data.aimPitch = net.minecraft.util.Mth.clamp(pitch, -90.0F, 90.0F);
        data.camX = camX;
        data.camY = camY;
        data.camZ = camZ;
        data.hasAim = true;
    }

    public static void handleSetDefending(ServerPlayer player, boolean defending) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        long now = player.level().getGameTime();
        if (defending) {
            if (!data.drawn || !isHoldingSword(player)) {
                if (data.defending) endDefend(player, data);
                else broadcastDefendState(player, false);
                return;
            }
            if (data.defending && data.defendHeld) return;
            if (data.dodgeTicksLeft > 0 || !canCancelAttack(data, now)) return;
            data.defending = true;
            data.defendHeld = true;
            data.defendHold = false;
            data.active = null;
            data.resetHandsApart();
            data.defendStartGameTime = now;
            data.guardBashUsed = false;
            data.sprintWasDown = player.isSprinting();
            broadcastDefendState(player, true);
            return;
        }
        data.defendHeld = false;
        if (!data.defending) return;
        long elapsed = now - data.defendStartGameTime;
        long holdTicks = Math.round(CombatConstants.DEFEND_HOLD_THRESHOLD_SECONDS * 20.0F);
        if (!data.defendHold && elapsed < holdTicks) {
            return;
        }
        endDefend(player, data);
    }

    private static void endDefend(ServerPlayer player, CombatData data) {
        if (!data.defending && !data.defendHeld) return;
        data.defending = false;
        data.defendHeld = false;
        data.defendHold = false;
        data.defendStartGameTime = Long.MIN_VALUE;
        broadcastDefendState(player, false);
    }

    public static boolean isPerfectGuard(CombatData data, long now) {
        if (!data.defending || data.defendStartGameTime == Long.MIN_VALUE) return false;
        long elapsed = now - data.defendStartGameTime;
        return elapsed >= Math.round(CombatConstants.PARRY_START_SECONDS * 20)
                && elapsed <= Math.round(CombatConstants.PARRY_WINDOW_SECONDS * 20);
    }

    public static float defendReduction(CombatData data, long now) {
        if (!data.defending || data.defendStartGameTime == Long.MIN_VALUE) return 0.0F;
        float t = (now - data.defendStartGameTime) / 20.0F;
        if (t < CombatConstants.PARRY_START_SECONDS) return 0.0F;
        if (t <= CombatConstants.PARRY_WINDOW_SECONDS) return 1.0F;
        if (data.defendHold || data.defendHeld) {
            if (t >= CombatConstants.DEFEND_HOLD_DECAY_END_SECONDS) {
                return CombatConstants.DEFEND_HOLD_END_REDUCTION;
            }
            float span = CombatConstants.DEFEND_HOLD_DECAY_END_SECONDS
                    - CombatConstants.DEFEND_HOLD_DECAY_START_SECONDS;
            float a = (t - CombatConstants.DEFEND_HOLD_DECAY_START_SECONDS) / span;
            return 1.0F + (CombatConstants.DEFEND_HOLD_END_REDUCTION - 1.0F) * a;
        }
        return 0.0F;
    }

    public static void handleAttack(ServerPlayer player, SwingSide side, boolean charged) {
        handleAttack(player, side, charged, false);
    }

    /**
     * Left click sends one sword left, left hand first; right click the same to
     * the right, right hand first (attack4 then attack2). Once both swords sit
     * on a side that button does nothing. Both buttons together after a parry
     * play the dual cut.
     */
    public static void handleAttack(ServerPlayer player, SwingSide side, boolean charged, boolean dual) {
        if (categoryOf(player) == WeaponCategory.SINGLE && side == SwingSide.RIGHT) { handleThrust(player); return; }
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || !isHoldingSword(player)) return;
        long now = player.level().getGameTime();
        if (data.dodgeTicksLeft > 0) return;
        if (!data.cadence.canStart(now)) return;
        if (!canCancelAttack(data, now)) return;
        expireParry(data, now);
        WeaponCategory cat = categoryOf(player);
        if (cat == WeaponCategory.SINGLE) {
            if (side == SwingSide.RIGHT) { handleThrust(player); return; }
            int variant = player.getRandom().nextInt(4);
            SwingSide clipSide = variant < 2 ? SwingSide.LEFT : SwingSide.RIGHT;
            int hand = variant % 2;
            startAttack(player, data, cat, clipSide, hand, false, false,
                    cat.attackDuration(clipSide, hand), cat.attackAnim(clipSide, hand), now);
            return;
        }

        if (dual) {
            if (!data.parryReady) return;
            startAttack(player, data, cat, SwingSide.LEFT, 0, false, true, cat.dualDuration(),
                    WeaponCategory.DUAL_ATTACK, now);
            data.leftHand = HandRest.LEFT;
            data.rightHand = HandRest.RIGHT;
            data.parryReady = false;
            data.parryReadyUntilGameTime = Long.MIN_VALUE;
            return;
        }

        if (charged) data.resetHandsApart();
        int hand = HandRest.pickHand(side, data.leftHand, data.rightHand);
        if (hand < 0) return;
        if (hand == 0) data.leftHand = HandRest.of(side);
        else data.rightHand = HandRest.of(side);

        String clip = cat.attackAnim(side, hand);
        if (clip == null) return;
        startAttack(player, data, cat, side, hand, charged, false, cat.attackDuration(side, hand), clip, now);
    }

    private static boolean canCancelAttack(CombatData data, long now) {
        return now >= data.thrustRecoveryUntil
                && (data.active == null || now - data.active.startGameTime >= data.active.cancelStartTick);
    }

    public static void handleDodge(ServerPlayer player, float yaw, int dirs) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || !isHoldingSword(player)) return;
        if (data.dodgeTicksLeft > 0) return;
        if (!player.onGround()) return;
        if (!canCancelAttack(data, player.level().getGameTime())) return;
        data.active = null;
        if (data.defending) endDefend(player, data);
        Vec3 total = CombatMoves.dodgeOffset(yaw, dirs);
        if (categoryOf(player) == WeaponCategory.SINGLE)
            total = total.scale(CombatConstants.RAPIER_DODGE_DISTANCE / CombatConstants.DODGE_DISTANCE);
        int ticks = Math.max(1, CombatConstants.DODGE_TICKS);
        data.dodgeDx = total.x / ticks;
        data.dodgeDz = total.z / ticks;
        data.dodgeTicksLeft = ticks;
        sendToTrackers(player, new ModPayloads.ClientboundDodge(player.getId()));
    }

    private static void startAttack(ServerPlayer player, CombatData data, WeaponCategory cat,
                                    SwingSide side, int hand, boolean charged, boolean dual,
                                    float duration, String clip, long now) {
        int intervalTicks = data.cadence.begin(now);
        AttackTimings.Timing timing = AttackTimings.of(cat, clip, duration).withInterval(intervalTicks);
        float length = timing.length() > 0.05F ? timing.length() : duration;
        CombatData.ActiveAttack attack =
                new CombatData.ActiveAttack(side, hand, charged, dual, now, length);
        attack.category = cat;
        float activeStart = timing.activeStart();
        float activeEnd = timing.activeEnd();
        attack.activeStartSeconds = activeStart;
        attack.activeEndSeconds = activeEnd;
        attack.contactSeconds = activeEnd;
        attack.scanned = false;
        attack.activeStartTick = Math.round(activeStart * 20.0F);
        attack.activeEndTick = Math.max(attack.activeStartTick + 1, Math.round(activeEnd * 20.0F));
        attack.comboStartTick = timing.comboStartTick();
        attack.comboEndTick = timing.comboEndTick();
        attack.cancelStartTick = timing.cancelStartTick();
        attack.durationTicks = Math.max(1, (int) Math.ceil(length * 20.0F - 1.0E-4F));
        data.lastAttackStartGameTime = now;
        data.active = attack;
        if (data.defending) {
            endDefend(player, data);
        }
        sendToTrackers(player,
                new ModPayloads.ClientboundAttackAnim(player.getId(), side.id(), hand, dual, intervalTicks));
    }

    /** gametest mock players never negotiate mod channels; NeoForge rejects S2C payload
     *  sends to listeners without the channel, so guard every custom-payload broadcast. */
    private static void sendToTrackers(ServerPlayer player, CustomPacketPayload payload) {
        if (!((ICommonPacketListener) player.connection).hasChannel(payload)) return;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload);
    }

    private static void broadcastDrawState(ServerPlayer player, boolean drawn) {
        sendToTrackers(player, new ModPayloads.ClientboundDrawState(player.getId(), drawn));
    }

    private static void broadcastDefendState(ServerPlayer player, boolean defending) {
        sendToTrackers(player, new ModPayloads.ClientboundDefendState(player.getId(), defending));
    }

    private static long comboResetTicks() {
        return (long) (CombatConstants.COMBO_RESET_SECONDS * 20.0F);
    }

    private static void expireDefend(ServerPlayer player, CombatData data, long now) {
        if (!data.defending) return;
        long elapsed = now - data.defendStartGameTime;
        long holdTicks = Math.round(CombatConstants.DEFEND_HOLD_THRESHOLD_SECONDS * 20.0F);
        if (data.defendHeld && elapsed >= holdTicks) data.defendHold = true;
        long maxTicks = data.defendHold
                ? Math.round(CombatConstants.DEFEND_HOLD_SECONDS * 20.0F)
                : Math.round(CombatConstants.DEFEND_TAP_SECONDS * 20.0F);
        if (elapsed > maxTicks) endDefend(player, data);
    }

    private static void expireParry(CombatData data, long now) {
        if (data.parryReady && (data.parryReadyUntilGameTime == Long.MIN_VALUE
                || now > data.parryReadyUntilGameTime)) {
            data.parryReady = false;
            data.parryReadyUntilGameTime = Long.MIN_VALUE;
        }
    }

    @SubscribeEvent
    static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (data.dodgeTicksLeft > 0) {
            player.xxa = 0.0F;
            player.zza = 0.0F;
            player.setShiftKeyDown(false);
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CombatData data = player.getData(ModAttachments.COMBAT);
            long now = player.level().getGameTime();
            expireParry(data, now);
            expireDefend(player, data, now);
            boolean sprint = player.isSprinting();
            if (sprint && !data.sprintWasDown) tryGuardBash(player);
            data.sprintWasDown = sprint;
            if (data.dodgeTicksLeft > 0) {
                player.move(MoverType.SELF, new Vec3(data.dodgeDx, 0.0, data.dodgeDz));
                data.dodgeTicksLeft--;
                if (data.dodgeTicksLeft <= 0) {
                    data.dodgeDx = 0.0;
                    data.dodgeDz = 0.0;
                }
            }
            if (data.active == null
                    && data.lastAttackEndGameTime != Long.MIN_VALUE
                    && now - data.lastAttackEndGameTime > comboResetTicks()) {
                data.resetHandsApart();
            }
            if (data.active == null) continue;
            tickActiveAttack(player, data);
        }
    }

    private static void tickActiveAttack(ServerPlayer player, CombatData data) {
        CombatData.ActiveAttack attack = data.active;
        long elapsedTicks = player.level().getGameTime() - attack.startGameTime;
        float elapsed = elapsedTicks / 20.0F + CombatConstants.HIT_SCAN_LEAD_SECONDS;

        if (!attack.scanned && elapsed >= attack.contactSeconds) {
            attack.scanned = true;
            scanTargets(player, attack);
        }

        if (elapsedTicks >= attack.durationTicks) {
            data.lastAttackEndGameTime = player.level().getGameTime();
            data.active = null;
        }
    }

    private static void scanTargets(ServerPlayer player, CombatData.ActiveAttack attack) {
        ServerLevel level = player.level();
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (attack.category == WeaponCategory.SINGLE) {
            LivingEntity target = rapierTarget(player);
            if (target != null && hitTarget(player, target, attack)) {
                markHit(attack, target);
                var hit = new ModPayloads.ClientboundHitConfirm(true);
                if (((ICommonPacketListener)player.connection).hasChannel(hit)) PacketDistributor.sendToPlayer(player, hit);
            }
            return;
        }
        float yaw = data.hasAim ? data.aimYaw : player.getYRot();
        Vec3 origin = attack.dual
                ? player.position().add(0.0, CombatConstants.ATTACK_HAND_HEIGHT, 0.0)
                    .add(Vec3.directionFromRotation(0.0F, yaw).scale(CombatConstants.ATTACK_HAND_FORWARD))
                : CombatMoves.attackHandOrigin(player.position(), yaw, attack.depth == 0);
        double pad = CombatConstants.ATTACK_RANGE + CombatConstants.ATTACK_FAN_HEIGHT * 0.5F;
        var candidates = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(pad),
                e -> e != player && e.isAlive() && !e.isSpectator());

        boolean any = false;
        for (LivingEntity target : candidates) {
            if (!CombatMoves.inAttackFan(origin, yaw, target.getBoundingBox().inflate(0.15))) continue;
            if (hitTarget(player, target, attack)) {
                markHit(attack, target);
                any = true;
            }
        }
        if (!any) return;
        var payload = new ModPayloads.ClientboundHitConfirm(true);
        if (((ICommonPacketListener) player.connection).hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static boolean hitTarget(ServerPlayer player, LivingEntity target, CombatData.ActiveAttack attack) {
        WeaponCategory cat = attack.category != null ? attack.category : categoryOf(player);
        float damage = cat == WeaponCategory.SINGLE ? (attack.durationSeconds == 0 ? 9.0F : 6.0F) : attack.dual
                ? cat.attackDamage(SwingSide.LEFT, 0) + cat.attackDamage(SwingSide.RIGHT, 0)
                : cat.attackDamage(attack.side, attack.depth)
                * (attack.charged ? CombatConstants.CHARGED_DAMAGE_MULTIPLIER : 1.0F);

        var toTarget = target.position().subtract(player.position());
        double dx = toTarget.x, dz = toTarget.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0E-4) { dx /= len; dz /= len; } else { dx = dz = 0; }

        boolean hurt = target.hurtServer(player.level(),
                player.damageSources().playerAttack(player), damage);
        if (hurt) {
            CombatData data = player.getData(ModAttachments.COMBAT);
            double knockback = CombatConstants.ATTACK_KNOCKBACK;
            if (data.parryCounter) {
                knockback = CombatConstants.PARRY_COUNTER_KNOCKBACK;
                data.parryCounter = false;
            }
            target.knockback(knockback, -dx, -dz);
            target.hurtMarked = true;
            levelFeedback(player, target);
        }
        return hurt;
    }

    private static void levelFeedback(ServerPlayer player, LivingEntity target) {
        ServerLevel level = player.level();
        level.sendParticles(ParticleTypes.CRIT,
                target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                6, 0.2, 0.2, 0.2, 0.15);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn) return;

        if (data.defending) {
            var attacker = event.getSource().getEntity();
            long now = player.level().getGameTime();
            long elapsed = now - data.defendStartGameTime;
            float reduction = defendReduction(data, now);
            boolean perfect = isPerfectGuard(data, now);
            if (perfect) {
                event.setCanceled(true);
                if (attacker instanceof LivingEntity) {
                    data.parryReady = true;
                    data.parryCounter = true;
                    data.parryReadyUntilGameTime = now
                            + Math.round(CombatConstants.PARRY_FOLLOWUP_SECONDS * 20.0F);
                    sendToTrackers(player, new ModPayloads.ClientboundParry(player.getId()));
                }
            } else if (reduction > 0.0F) {
                event.setAmount(event.getAmount() * (1.0F - reduction));
            }
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0F, 1.0F);
        } else {
            sendToTrackers(player, new ModPayloads.ClientboundHurt(player.getId()));
        }
    }

    private static boolean isAlreadyHit(CombatData.ActiveAttack attack, LivingEntity entity) {
        for (int i = 0; i < attack.hitCount; i++) {
            if (attack.alreadyHit[i] == entity) return true;
        }
        return false;
    }

    private static void markHit(CombatData.ActiveAttack attack, LivingEntity entity) {
        if (attack.hitCount < attack.alreadyHit.length) {
            attack.alreadyHit[attack.hitCount++] = entity;
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CombatData data = player.getData(ModAttachments.COMBAT);
            if (data.drawn) {
                data.drawn = false;
                data.resetForSheath();
            }
            broadcastDrawState(player, false);
            broadcastDefendState(player, false);
        }
    }


    /** Camera ray picks exactly one visible target; eye LOS prevents reaching around walls. */
    public static LivingEntity rapierTarget(ServerPlayer player) {
        CombatData d = player.getData(ModAttachments.COMBAT);
        Vec3 eye = player.getEyePosition();
        Vec3 origin = d.hasAim ? new Vec3(d.camX, d.camY, d.camZ) : eye;
        if (origin.distanceToSqr(eye) > 144) origin = eye;
        Vec3 direction = Vec3.directionFromRotation(d.hasAim ? d.aimPitch : player.getXRot(),
                d.hasAim ? d.aimYaw : player.getYRot());
        Vec3 end = origin.add(direction.scale(16));
        var block = player.level().clip(new net.minecraft.world.level.ClipContext(origin, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        end = block.getLocation();
        LivingEntity nearest = null;
        double distance = origin.distanceToSqr(end);
        for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(CombatConstants.RAPIER_RANGE + 1),
                e -> e != player && e.isAlive() && !e.isSpectator())) {
            var intersection = target.getBoundingBox().clip(origin, end);
            if (intersection.isEmpty()) continue;
            Vec3 point = intersection.get();
            if (eye.distanceToSqr(point) > CombatConstants.RAPIER_RANGE * CombatConstants.RAPIER_RANGE) continue;
            var wall = player.level().clip(new net.minecraft.world.level.ClipContext(eye, point,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && eye.distanceToSqr(wall.getLocation()) + 0.0001 < eye.distanceToSqr(point)) continue;
            double dist = origin.distanceToSqr(point);
            if (dist < distance) { distance = dist; nearest = target; }
        }
        return nearest;
    }

    public static void handleThrust(ServerPlayer player) {
        CombatData d = player.getData(ModAttachments.COMBAT);
        long now = player.level().getGameTime();
        boolean accepted = d.drawn && isHoldingSword(player) && categoryOf(player) == WeaponCategory.SINGLE
                && d.dodgeTicksLeft == 0 && now >= d.thrustReadyAt && d.cadence.canStart(now)
                && canCancelAttack(d, now);
        boolean hit = false;
        if (accepted) {
            d.active = null;
            endDefend(player, d);
            d.thrustReadyAt = now + CombatConstants.THRUST_COOLDOWN_TICKS;
            LivingEntity target = rapierTarget(player);
            CombatData.ActiveAttack thrust = new CombatData.ActiveAttack(SwingSide.RIGHT, 1, false, false, now, 0);
            thrust.category = WeaponCategory.SINGLE;
            hit = target != null && hitTarget(player, target, thrust);
            d.thrustRecoveryUntil = now + (hit ? 0 : CombatConstants.THRUST_MISS_RECOVERY_TICKS);
        }
        var result = new ModPayloads.ClientboundThrust(player.getId(), accepted, hit,
                (int)Math.max(0, d.thrustReadyAt == Long.MIN_VALUE ? 0 : d.thrustReadyAt - now),
                (int)Math.max(0, d.thrustRecoveryUntil == Long.MIN_VALUE ? 0 : d.thrustRecoveryUntil - now));
        if (accepted) sendToTrackers(player, result);
        else if (((ICommonPacketListener)player.connection).hasChannel(result)) PacketDistributor.sendToPlayer(player, result);
    }

    /** One radial shove per raised guard, on a new sprint press. No damage and no repeated ticks. */
    public static void tryGuardBash(ServerPlayer player) {
        CombatData d = player.getData(ModAttachments.COMBAT);
        if (!d.drawn || !d.defending || d.guardBashUsed || categoryOf(player) != WeaponCategory.DOUBLE
                || !player.isSprinting()) return;
        d.guardBashUsed = true;
        for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(CombatConstants.GUARD_BASH_RADIUS),
                e -> e != player && e.isAlive() && !e.isSpectator())) {
            Vec3 delta = target.position().subtract(player.position());
            if (delta.lengthSqr() > CombatConstants.GUARD_BASH_RADIUS * CombatConstants.GUARD_BASH_RADIUS
                    || !player.hasLineOfSight(target)) continue;
            if (delta.horizontalDistanceSqr() < 1.0E-6) delta = player.getLookAngle();
            target.knockback(CombatConstants.GUARD_BASH_KNOCKBACK, -delta.x, -delta.z);
            target.hurtMarked = true;
        }
        sendToTrackers(player, new ModPayloads.ClientboundGuardBash(player.getId()));
    }

    private ServerCombat() {}
}

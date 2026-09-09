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
        return player.getMainHandItem().is(net.minecraft.tags.ItemTags.SPEARS)
                || isSword(player.getMainHandItem()) || isSword(player.getOffhandItem());
    }

    public static WeaponCategory categoryOf(ServerPlayer player) {
        return WeaponCategory.of(player);
    }

    private static boolean isSword(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.SWORDS);
    }

    public static void handleSetDrawn(ServerPlayer player, boolean drawn) {
        handleSetDrawn(player, drawn, false);
    }

    public static void handleSetDrawn(ServerPlayer player, boolean drawn, boolean katana) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        // Katana stance is disabled: any one-handed draw is the rapier.
        if (drawn && !data.drawn) {
            if (!isHoldingSword(player)) { data.katanaStance = false; return; }
            data.katanaStance = false;
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
            if (categoryOf(player) == WeaponCategory.SINGLE) {
                data.katanaLockId = -1;
                LivingEntity nearest = nearestLockTarget(player, CombatConstants.LOCK_RANGE);
                if (nearest != null) data.katanaLockId = nearest.getId();
            }
        }
        broadcastDrawState(player, data.drawn);
        broadcastDefendState(player, false);
    }

    /** Iaido opening: the draw itself is one circular hit around the swordsman. */
    private static void katanaDrawSlash(ServerPlayer player, CombatData data) {
        ServerLevel level = player.level();
        double radius = CombatConstants.KATANA_DRAW_AOE_RADIUS;
        boolean any = false;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(radius),
                e -> e != player && e.isAlive() && !e.isSpectator())) {
            Vec3 delta = target.position().subtract(player.position());
            if (delta.horizontalDistanceSqr() > radius * radius) continue;
            boolean hurt = target.hurtServer(level,
                    player.damageSources().playerAttack(player),
                    CombatConstants.KATANA_DRAW_AOE_DAMAGE);
            if (hurt) {
                if (delta.horizontalDistanceSqr() < 1.0E-6) delta = player.getLookAngle();
                target.knockback(CombatConstants.KATANA_DRAW_AOE_KNOCKBACK, -delta.x, -delta.z);
                target.hurtMarked = true;
                any = true;
            }
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                player.getX(), player.getY() + 1.0, player.getZ(), 6, 1.2, 0.4, 1.2, 0.0);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.9F);
        if (any) {
            var hit = new ModPayloads.ClientboundHitConfirm(true);
            if (((ICommonPacketListener) player.connection).hasChannel(hit)) {
                PacketDistributor.sendToPlayer(player, hit);
            }
        }
    }

    /** C2S lock-on update; the server re-validates at attack time anyway. */
    public static void handleKatanaLock(ServerPlayer player, int targetId) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || categoryOf(player) != WeaponCategory.SINGLE) return;
        if (targetId < 0) {
            data.katanaLockId = -1;
            return;
        }
        var entity = player.level().getEntity(targetId);
        if (entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator()
                && living.distanceToSqr(player)
                        <= CombatConstants.LOCK_RANGE * CombatConstants.LOCK_RANGE) {
            data.katanaLockId = targetId;
        }
    }

    private static LivingEntity nearestLockTarget(ServerPlayer player, double range) {
        LivingEntity nearest = null;
        double best = range * range;
        for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range),
                e -> e != player && e.isAlive() && !e.isSpectator())) {
            double dist = target.distanceToSqr(player);
            if (dist < best && player.hasLineOfSight(target)) {
                best = dist;
                nearest = target;
            }
        }
        return nearest;
    }

    /** Current katana cadence tier in ticks: 0.45s base, 0.35s on a hit streak,
     *  0.25s while the guard haste runs. */
    public static int katanaIntervalTicks(CombatData data, long now) {
        if (data.katanaHasteUntilGameTime != Long.MIN_VALUE
                && now < data.katanaHasteUntilGameTime) {
            return CombatConstants.KATANA_HASTE_INTERVAL_TICKS;
        }
        return data.katanaHitStreak
                ? CombatConstants.KATANA_STREAK_INTERVAL_TICKS
                : CombatConstants.KATANA_ATTACK_INTERVAL_TICKS;
    }

    private static void sendKatanaSpeed(ServerPlayer player) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        // The packet carries the non-haste tier (streak vs base) plus the haste
        // deadline; both sides derive the effective tier as haste ? 0.25s : packet.
        var packet = new ModPayloads.ClientboundKatanaSpeed(
                data.katanaHitStreak
                        ? CombatConstants.KATANA_STREAK_INTERVAL_TICKS
                        : CombatConstants.KATANA_ATTACK_INTERVAL_TICKS,
                data.katanaHasteUntilGameTime);
        if (((ICommonPacketListener) player.connection).hasChannel(packet)) {
            PacketDistributor.sendToPlayer(player, packet);
        }
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
        if (categoryOf(player) == WeaponCategory.SPEAR) { endDefend(player, data); return; }
        if (!data.defending) return;
        if (categoryOf(player) == WeaponCategory.SPEAR && data.defendHeld) return;
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
        handleAttack(player, side, charged, false, false);
    }

    /** Legacy overload: callers without charge state still compile. */
    public static void handleAttack(ServerPlayer player, SwingSide side, boolean charged, boolean dual) {
        handleAttack(player, side, charged, dual, false);
    }

    /**
     * Left click sends one sword left, left hand first; right click the same to
     * the right, right hand first (attack4 then attack2). Once both swords sit
     * on a side that button does nothing. Both buttons together after a parry
     * play the dual cut.
     */
    public static void handleAttack(ServerPlayer player, SwingSide side, boolean charged, boolean dual,
                                   boolean chargePiercingArmor) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (categoryOf(player) == WeaponCategory.SPEAR) {
            if (!data.drawn || data.dodgeTicksLeft > 0) return;
            long tick = player.level().getGameTime();
            if (!data.cadence.canStart(tick) || !canCancelAttack(data, tick)) return;
            if (side == SwingSide.RIGHT && !charged) { handleSetDefending(player, true); return; }
            int variant = side == SwingSide.LEFT && charged ? 1 : 0;
            startAttack(player, data, WeaponCategory.SPEAR, side, variant, false, false, 0.4F,
                    WeaponCategory.SPEAR.attackAnim(side, variant), tick, false, 8);
            return;
        }
        if (categoryOf(player) == WeaponCategory.SINGLE && side == SwingSide.RIGHT) {
            handleThrust(player, chargePiercingArmor);
            return;
        }
        if (!data.drawn || !isHoldingSword(player)) return;
        long now = player.level().getGameTime();
        WeaponCategory incoming = categoryOf(player);
        if (data.dodgeTicksLeft > 0) return;
        if (!data.cadence.canStart(now)) return;
        if (!canCancelAttack(data, now)) return;
        expireParry(data, now);
        WeaponCategory cat = incoming;
        if (cat == WeaponCategory.SINGLE) {
            int variant = Math.floorMod(data.rapierSlashIndex++, 4);
            SwingSide clipSide = (variant == 0 || variant == 2) ? SwingSide.LEFT : SwingSide.RIGHT;
            int hand = (variant == 0 || variant == 1) ? 0 : 1;
            boolean fast = data.rapierFastUntilGameTime != Long.MIN_VALUE
                    && now <= data.rapierFastUntilGameTime;
            // 第四刀 connects a little later than the other three slashes.
            float contactDelay = variant == 3
                    ? CombatConstants.RAPIER_FOURTH_CONTACT_DELAY_SECONDS : 0.0F;
            startAttack(player, data, cat, clipSide, hand, false, false,
                    cat.attackDuration(clipSide, hand), cat.attackAnim(clipSide, hand), now, false,
                    AttackCadence.intervalTicksFor(cat, variant, fast), contactDelay);
            return;
        }

        if (cat == WeaponCategory.DOUBLE) {
            // A guard that landed arms the dual cut: the next attack IS the dual cut.
            if (data.parryReady) {
                startAttack(player, data, cat, SwingSide.LEFT, 0, false, true, cat.dualDuration(),
                        WeaponCategory.DUAL_ATTACK, now, false, CombatConstants.DUAL_INTERVAL_TICKS);
                data.leftHand = HandRest.LEFT;
                data.rightHand = HandRest.RIGHT;
                data.parryReady = false;
                data.parryReadyUntilGameTime = Long.MIN_VALUE;
                return;
            }
            if (charged) data.resetHandsApart();
            int dualHand = HandRest.pickHand(side, data.leftHand, data.rightHand);
            if (dualHand < 0) return;
            if (dualHand == 0) data.leftHand = HandRest.of(side);
            else data.rightHand = HandRest.of(side);
            int dualInterval = AttackCadence.dualIntervalTicks(dualHand == 0,
                    data.leftSwingStreak, data.rightSwingStreak);
            String dualClip = cat.attackAnim(side, dualHand);
            if (dualClip == null) return;
            startAttack(player, data, cat, side, dualHand, charged, false,
                    cat.attackDuration(side, dualHand), dualClip, now, false, dualInterval);
            if (dualHand == 0) {
                data.leftSwingStreak++;
                data.rightSwingStreak = Math.max(0, data.rightSwingStreak - 1);
            } else {
                data.rightSwingStreak++;
                data.leftSwingStreak = Math.max(0, data.leftSwingStreak - 1);
            }
            return;
        }

        if (charged) data.resetHandsApart();
        int hand = HandRest.pickHand(side, data.leftHand, data.rightHand);
        if (hand < 0) return;
        if (hand == 0) data.leftHand = HandRest.of(side);
        else data.rightHand = HandRest.of(side);

        String clip = cat.attackAnim(side, hand);
        if (clip == null) return;
        startAttack(player, data, cat, side, hand, charged, false, cat.attackDuration(side, hand), clip, now, false);
    }

    private static boolean canCancelAttack(CombatData data, long now) {
        return now >= data.thrustRecoveryUntil
                && (data.active == null || now - data.active.startGameTime >= data.active.cancelStartTick);
    }

    /**
     * Katana command attack: swings on the stance's own cadence (0.45s, tightened to
     * 0.35s on a hit streak, 0.25s under guard haste) and only strikes the locked
     * target. Without a lock the nearest enemy is locked first; with no enemy in
     * lock range the swing still plays but whiffs.
     */
    public static void handleKatanaAttack(ServerPlayer player, SwingSide side) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || !isHoldingSword(player)) return;
        long now = player.level().getGameTime();
        if (data.dodgeTicksLeft > 0) return;
        if (data.lastAttackStartGameTime != Long.MIN_VALUE
                && now - data.lastAttackStartGameTime < katanaIntervalTicks(data, now)) return;

        LivingEntity lock = resolveLock(player, data);
        if (lock == null) {
            lock = nearestLockTarget(player, CombatConstants.LOCK_RANGE);
            data.katanaLockId = lock != null ? lock.getId() : -1;
        }

        int hand = HandRest.pickHand(side, data.leftHand, data.rightHand);
        if (hand < 0) { data.resetHandsApart(); hand = HandRest.pickHand(side, data.leftHand, data.rightHand); }
        if (hand < 0) return;
        if (hand == 0) data.leftHand = HandRest.of(side);
        else data.rightHand = HandRest.of(side);

        WeaponCategory cat = WeaponCategory.KATANA;
        String clip = cat.attackAnim(side, hand);
        if (clip == null) return;

        int intervalTicks = katanaIntervalTicks(data, now);
        AttackTimings.Timing timing = AttackTimings.of(cat, clip, cat.attackDuration(side, hand));
        CombatData.ActiveAttack attack =
                new CombatData.ActiveAttack(side, hand, false, false, now, timing.length());
        attack.category = cat;
        attack.activeStartSeconds = timing.activeStart();
        attack.activeEndSeconds = timing.activeEnd();
        attack.contactSeconds = timing.activeEnd();
        attack.scanned = false;
        attack.activeStartTick = timing.activeStartTick();
        attack.activeEndTick = timing.activeEndTick();
        attack.comboStartTick = timing.comboStartTick();
        attack.comboEndTick = timing.comboEndTick();
        // The stance's cadence tier is the cancel gate, not the uniform 0.3s clock.
        attack.cancelStartTick = intervalTicks;
        attack.durationTicks = Math.max(1, (int) Math.ceil(timing.length() * 20.0F - 1.0E-4F));
        data.lastAttackStartGameTime = now;
        data.active = attack;
        if (data.defending) endDefend(player, data);
        sendToTrackers(player,
                new ModPayloads.ClientboundAttackAnim(player.getId(), side.id(), hand, false, intervalTicks));
    }

    /** The locked entity, or null when it died, left the world or broke lock range. */
    private static LivingEntity resolveLock(ServerPlayer player, CombatData data) {
        if (data.katanaLockId < 0) return null;
        var entity = player.level().getEntity(data.katanaLockId);
        if (entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator()
                && living.distanceToSqr(player)
                        <= CombatConstants.LOCK_RANGE * CombatConstants.LOCK_RANGE) {
            return living;
        }
        data.katanaLockId = -1;
        return null;
    }

    /** Rapier: a held X key on the client just crossed the charge threshold. Stage the
     *  marker so the next rapier thrust can ignore {@link CombatConstants#RAPIER_CHARGE_ARMOR_PIERCE}
     *  of the target's armor. */
    public static void handleRapierChargeReady(ServerPlayer player) {
        if (player == null) return;
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || categoryOf(player) != WeaponCategory.SINGLE) return;
        data.chargeReady = true;
        data.chargeReadyAt = player.level().getGameTime();
    }

    public static void handleDodge(ServerPlayer player, float yaw, int dirs) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn || !isHoldingSword(player)) return;
        long now = player.level().getGameTime();
        WeaponCategory cat = categoryOf(player);
        if (cat == WeaponCategory.DOUBLE) {
            // Dual-wield Shift is a 0.3s maximum-move-speed boost, not a scripted step:
            // no clip, no displacement, no cancelled attack or guard. A moving player
            // turns it into a dash and may jump, swing or guard at any point.
            if (now < data.dodgeBoostUntilGameTime) return;
            data.dodgeBoostUntilGameTime = now + CombatConstants.DUAL_DASH_TICKS;
            CombatAttributes.setDualDashBoost(player, true);
            return;
        }
        if (data.dodgeTicksLeft > 0) return;
        if (!player.onGround()) return;
        if (cat == WeaponCategory.SPEAR && now < data.spearDodgeReadyAt) return;
        // 闪避 has the top interrupt priority: it cancels any running attack or guard
        // outright instead of waiting for that action's cancel frame.
        data.active = null;
        if (data.defending) endDefend(player, data);
        Vec3 total = cat == WeaponCategory.SPEAR ? SpearMoves.backstep(player.getYRot())
                : CombatMoves.dodgeOffset(yaw, dirs);
        if (cat == WeaponCategory.SPEAR) {
            data.spearDodgeReadyAt = now + 8;
            SpearCombat.cancelPush(player);
        }
        if (cat == WeaponCategory.SINGLE)
            total = total.scale(CombatConstants.RAPIER_DODGE_DISTANCE / CombatConstants.DODGE_DISTANCE);
        else if (cat == WeaponCategory.KATANA)
            total = total.scale(CombatConstants.KATANA_DODGE_DISTANCE / CombatConstants.DODGE_DISTANCE);
        int ticks = Math.max(1, CombatConstants.DODGE_TICKS);
        data.dodgeDx = total.x / ticks;
        data.dodgeDz = total.z / ticks;
        data.dodgeTicksLeft = ticks;
        sendToTrackers(player, new ModPayloads.ClientboundDodge(player.getId()));
    }

    private static void startAttack(ServerPlayer player, CombatData data, WeaponCategory cat,
                                    SwingSide side, int hand, boolean charged, boolean dual,
                                    float duration, String clip, long now, boolean chargePiercingArmor) {
        startAttack(player, data, cat, side, hand, charged, dual, duration, clip, now, chargePiercingArmor,
                AttackCadence.intervalTicksFor(cat, 0), 0.0F);
    }

    private static void startAttack(ServerPlayer player, CombatData data, WeaponCategory cat,
                                    SwingSide side, int hand, boolean charged, boolean dual,
                                    float duration, String clip, long now, boolean chargePiercingArmor,
                                    int intervalTicks) {
        startAttack(player, data, cat, side, hand, charged, dual, duration, clip, now,
                chargePiercingArmor, intervalTicks, 0.0F);
    }

    private static void startAttack(ServerPlayer player, CombatData data, WeaponCategory cat,
                                    SwingSide side, int hand, boolean charged, boolean dual,
                                    float duration, String clip, long now, boolean chargePiercingArmor,
                                    int intervalTicks, float contactDelaySeconds) {
        intervalTicks = data.cadence.begin(now, intervalTicks);
        AttackTimings.Timing timing = AttackTimings.of(cat, clip, duration).withInterval(intervalTicks);
        float length = timing.length() > 0.05F ? timing.length() : duration;
        CombatData.ActiveAttack attack =
                new CombatData.ActiveAttack(side, hand, charged, dual, now, length);
        attack.category = cat;
        attack.chargePiercingArmor = chargePiercingArmor;
        float activeStart = timing.activeStart();
        // The contact frame is when damage is scanned; a per-clip delay may push it later.
        float activeEnd = timing.activeEnd() + Math.max(0.0F, contactDelaySeconds);
        attack.activeStartSeconds = activeStart;
        attack.activeEndSeconds = activeEnd;
        attack.contactSeconds = activeEnd;
        attack.scanned = false;
        attack.activeStartTick = Math.round(activeStart * 20.0F);
        attack.activeEndTick = Math.max(attack.activeStartTick + 1, Math.round(activeEnd * 20.0F));
        attack.comboStartTick = timing.comboStartTick();
        attack.comboEndTick = timing.comboEndTick();
        attack.cancelStartTick = intervalTicks;
        attack.durationTicks = Math.max(1, (int) Math.ceil(length * 20.0F - 1.0E-4F));
        data.lastAttackStartGameTime = now;
        data.active = attack;
        if (cat == WeaponCategory.DOUBLE) {
            // 双刀攻击后的 0.1s 视为自动格挡时间.
            data.autoGuardUntilGameTime = now
                    + Math.round(CombatConstants.DUAL_AUTO_GUARD_SECONDS * 20.0F);
        }
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
        CombatData data = player.getData(ModAttachments.COMBAT);
        sendToTrackers(player, new ModPayloads.ClientboundDrawState(
                player.getId(), drawn, drawn && data.katanaStance));
    }

    private static void broadcastDefendState(ServerPlayer player, boolean defending) {
        sendToTrackers(player, new ModPayloads.ClientboundDefendState(player.getId(), defending));
    }

    private static long comboResetTicks() {
        return (long) (CombatConstants.COMBO_RESET_SECONDS * 20.0F);
    }

    private static void expireDefend(ServerPlayer player, CombatData data, long now) {
        if (!data.defending) return;
        if (categoryOf(player) == WeaponCategory.SPEAR && data.defendHeld) return;
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
            if (categoryOf(player) == WeaponCategory.SPEAR)
                player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
            player.xxa = 0.0F;
            player.zza = 0.0F;
            player.setShiftKeyDown(false);
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        tickAiSilences(event.getServer());
        SpearCombat.tick(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CombatData data = player.getData(ModAttachments.COMBAT);
            long now = player.level().getGameTime();
            if (!player.isPassenger() && now < data.spearSprintStopUntil) player.setSprinting(false);
            expireParry(data, now);
            expireDefend(player, data, now);
            boolean sprint = player.isSprinting();
            if (sprint && !data.sprintWasDown) tryGuardBash(player);
            data.sprintWasDown = sprint;
            CombatAttributes.setDualSwingSlow(player,
                    data.drawn && categoryOf(player) == WeaponCategory.DOUBLE && data.active != null);
            boolean dash = data.drawn && data.dodgeBoostUntilGameTime != Long.MIN_VALUE
                    && now < data.dodgeBoostUntilGameTime;
            if (!dash) data.dodgeBoostUntilGameTime = Long.MIN_VALUE;
            CombatAttributes.setDualDashBoost(player, dash);
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
            if (attack.thrust) {
                long now = player.level().getGameTime();
                if (attack.chargePiercingArmor) {
                    data.chargeReady = false;
                    data.chargeReadyAt = Long.MIN_VALUE;
                }
                data.thrustRecoveryUntil = now + (attack.hitCount > 0
                        ? 0 : CombatConstants.THRUST_MISS_RECOVERY_TICKS);
                var result = new ModPayloads.ClientboundThrust(player.getId(), true, attack.hitCount > 0,
                        (int) Math.max(0, data.thrustReadyAt == Long.MIN_VALUE ? 0 : data.thrustReadyAt - now),
                        (int) Math.max(0, data.thrustRecoveryUntil == Long.MIN_VALUE ? 0 : data.thrustRecoveryUntil - now));
                if (((ICommonPacketListener) player.connection).hasChannel(result)) {
                    PacketDistributor.sendToPlayer(player, result);
                }
                if (attack.hitCount > 0) {
                    // 命中无动作锁定: a landed thrust must not hold the cancel gate open.
                    data.active = null;
                    if (attack.chargePiercingArmor) {
                        // 强化刺击命中: the chain tightens to 0.20/0.20/0.30/0.40 for a while.
                        data.rapierFastUntilGameTime = now
                                + Math.round(CombatConstants.RAPIER_FAST_WINDOW_SECONDS * 20.0F);
                    }
                    return;
                }
            }
        }

        if (elapsedTicks >= attack.durationTicks) {
            data.lastAttackEndGameTime = player.level().getGameTime();
            data.active = null;
        }
    }

    private static void scanTargets(ServerPlayer player, CombatData.ActiveAttack attack) {
        ServerLevel level = player.level();
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (attack.category == WeaponCategory.SPEAR) {
            LivingEntity target = SpearCombat.target(player, attack);
            if (target != null && hitTarget(player, target, attack)) {
                markHit(attack, target);
                sendToTrackers(player, new ModPayloads.ClientboundHitConfirm(true));
            }
            if (attack.side == SwingSide.LEFT && attack.depth == 0) SpearCombat.finishPoke(player);
            if (attack.side == SwingSide.RIGHT && !player.isPassenger())
                SpearCombat.push(player, Vec3.directionFromRotation(0, data.hasAim ? data.aimYaw : player.getYRot()).scale(0.4));
            return;
        }
        if (attack.category == WeaponCategory.SINGLE) {
            LivingEntity lock = resolveLock(player, data);
            if (lock == null) {
                lock = nearestLockTarget(player, CombatConstants.LOCK_RANGE);
                data.katanaLockId = lock != null ? lock.getId() : -1;
            }
            boolean hit = false;
            if (lock != null && lock.distanceToSqr(player)
                    <= CombatConstants.RAPIER_RANGE * CombatConstants.RAPIER_RANGE
                    && player.hasLineOfSight(lock)) {
                hit = hitTarget(player, lock, attack);
                if (hit) {
                    markHit(attack, lock);
                    recordRapierHit(player, lock);
                }
            }
            if (!hit) {
                if (data.rapierComboHits != 0) sendRapierCombo(player, -1, 0, 0);
                data.rapierComboHits = 0;
            }
            if (hit) {
                var payload = new ModPayloads.ClientboundHitConfirm(true);
                if (((ICommonPacketListener) player.connection).hasChannel(payload)) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
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
        float damage = cat == WeaponCategory.SINGLE ? (attack.thrust ? 9.0F : 6.0F) : attack.dual
                ? cat.attackDamage(SwingSide.LEFT, 0) + cat.attackDamage(SwingSide.RIGHT, 0)
                : cat.attackDamage(attack.side, attack.depth)
                * (attack.charged ? CombatConstants.CHARGED_DAMAGE_MULTIPLIER : 1.0F);

        if (cat == WeaponCategory.SPEAR && attack.side == SwingSide.LEFT && attack.depth == 0) {
            damage *= SpearMoves.speedMultiplier(player.getKnownMovement(), target.getKnownMovement(), target.position().subtract(player.position()));
        }
        // Rapier charge pierces 30 % of the target's armor reduction; recalculate so the
        // final damage lands at (1 - 0.7 × armorReduction)/(1 - armorReduction) × raw.
        CombatData data = player.getData(ModAttachments.COMBAT);
        long now = player.level().getGameTime();
        if (attack.thrust && data.rapierThrustBonusUntil != Long.MIN_VALUE
                && now <= data.rapierThrustBonusUntil
                && (data.rapierThrustBonusTarget == null
                        || data.rapierThrustBonusTarget.equals(target.getUUID()))) {
            damage *= CombatConstants.RAPIER_THRUST_BONUS_MULTIPLIER;
            data.rapierThrustBonusUntil = Long.MIN_VALUE;
            data.rapierThrustBonusTarget = null;
        }
        if (cat == WeaponCategory.SINGLE && attack.chargePiercingArmor) {
            int armor = target.getArmorValue();
            float reduction = Math.min(1.0F,
                    Math.max(armor / 5.0F, armor - damage / 2.0F) / 25.0F);
            if (reduction > 0.0F) {
                float pierceFactor = 1.0F - CombatConstants.RAPIER_CHARGE_ARMOR_PIERCE;
                damage = damage * (1.0F - pierceFactor * reduction)
                        / Math.max(0.05F, 1.0F - reduction);
            }
        }

        var toTarget = target.position().subtract(player.position());
        double dx = toTarget.x, dz = toTarget.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0E-4) { dx /= len; dz /= len; } else { dx = dz = 0; }

        // Vanilla's hurt cooldown silently drops a second hit of equal damage while
        // invulnerableTime > 10, and every cadence here is faster than that window
        // (dual 6 ticks, katana 9) — half the authored contacts would deal nothing.
        // Each confirmed contact opens its own damage window.
        if (target.invulnerableTime > 10) target.invulnerableTime = 0;

        boolean hurt = target.hurtServer(player.level(),
                player.damageSources().playerAttack(player), damage);
        if (hurt) {
            // Rapier strokes never knock the target back — a charged thrust finishes the line,
            // it does not bully the opponent.
            if (cat == WeaponCategory.SPEAR && attack.side == SwingSide.RIGHT) {
                SpearCombat.push(target, new Vec3(dx * 2.0, 0, dz * 2.0));
                target.setDeltaMovement(0, target.getDeltaMovement().y, 0);
            }
            if (cat != WeaponCategory.SPEAR && (cat != WeaponCategory.SINGLE || !CombatConstants.RAPIER_NO_KNOCKBACK)) {
                double knockback = CombatConstants.ATTACK_KNOCKBACK;
                if (data.parryCounter) {
                    knockback = CombatConstants.PARRY_COUNTER_KNOCKBACK;
                    data.parryCounter = false;
                }
                target.knockback(knockback, -dx, -dz);
                target.hurtMarked = true;
                levelFeedback(player, target);
            }
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

    // ===== rapier combo reward: every 4th consecutive hit silences the target's AI =====

    /** Pending setNoAi(false) restorations, processed on the server tick. */
    private static final java.util.List<AiSilence> AI_SILENCES = new java.util.ArrayList<>();

    private record AiSilence(int entityId,
                             net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                             long restoreGameTime) {}

    /**
     * A confirmed rapier hit. Four consecutive hits on the same enemy silence its
     * AI for 1s and mark it so the next thrust in 2s deals 250 % damage.
     */
    private static void recordRapierHit(ServerPlayer player, LivingEntity target) {
        CombatData data = player.getData(ModAttachments.COMBAT);
        long now = player.level().getGameTime();
        if (data.rapierComboTarget != null && !data.rapierComboTarget.equals(target.getUUID())) {
            data.rapierComboHits = 0;
        } else if (data.rapierLastHitGameTime == Long.MIN_VALUE
                || now - data.rapierLastHitGameTime > comboResetTicks()) {
            data.rapierComboHits = 0;
        }
        data.rapierComboTarget = target.getUUID();
        data.rapierComboHits++;
        data.rapierLastHitGameTime = now;
        int silenceTicks = 0;
        if (data.rapierComboHits % CombatConstants.RAPIER_AI_CANCEL_HITS != 0) {
            sendRapierCombo(player, target.getId(), data.rapierComboHits, 0);
            return;
        }

        data.rapierThrustBonusTarget = target.getUUID();
        data.rapierThrustBonusUntil = now
                + Math.round(CombatConstants.RAPIER_THRUST_BONUS_SECONDS * 20.0F);

        if (!(target instanceof net.minecraft.world.entity.Mob mob)) {
            sendRapierCombo(player, target.getId(), data.rapierComboHits, 0);
            return;
        }
        boolean naturallyNoAi = mob.isNoAi()
                && AI_SILENCES.stream().noneMatch(s -> s.entityId() == mob.getId());
        if (naturallyNoAi) {
            sendRapierCombo(player, target.getId(), data.rapierComboHits, 0);
            return;
        }

        mob.setNoAi(true);
        silenceTicks = Math.round(CombatConstants.RAPIER_AI_CANCEL_SECONDS * 20.0F);
        long restoreAt = now + silenceTicks;
        AI_SILENCES.removeIf(s -> s.entityId() == mob.getId());
        AI_SILENCES.add(new AiSilence(mob.getId(), mob.level().dimension(), restoreAt));
        sendRapierCombo(player, target.getId(), data.rapierComboHits, silenceTicks);
        player.level().playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.0F, 0.6F);
    }

    /** HUD hint: streak length and any AI-deprivation window that just started. */
    private static void sendRapierCombo(ServerPlayer player, int targetId, int hits, int silenceTicks) {
        var payload = new ModPayloads.ClientboundRapierCombo(targetId, hits, silenceTicks);
        if (((ICommonPacketListener) player.connection).hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static void tickAiSilences(net.minecraft.server.MinecraftServer server) {
        if (AI_SILENCES.isEmpty()) return;
        AI_SILENCES.removeIf(silence -> {
            ServerLevel level = server.getLevel(silence.dimension());
            if (level == null) return true;
            if (level.getGameTime() < silence.restoreGameTime()) return false;
            var entity = level.getEntity(silence.entityId());
            if (entity instanceof net.minecraft.world.entity.Mob mob && mob.isNoAi()) {
                mob.setNoAi(false);
            }
            return true;
        });
    }

    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.drawn) return;

        if (categoryOf(player) == WeaponCategory.SPEAR) {
            Vec3 source = event.getSource().getSourcePosition();
            if (data.defending && data.defendHeld && source != null
                    && !event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD)) {
                Vec3 facing = Vec3.directionFromRotation(0, data.hasAim ? data.aimYaw : player.getYRot());
                if (source.subtract(player.position()).normalize().dot(facing) > 0.0) event.setCanceled(true);
            }
            return;
        }
        long now = player.level().getGameTime();
        boolean autoGuard = !data.defending
                && data.autoGuardUntilGameTime != Long.MIN_VALUE
                && now <= data.autoGuardUntilGameTime
                && categoryOf(player) == WeaponCategory.DOUBLE;
        if (data.defending) {
            var attacker = event.getSource().getEntity();
            float reduction = defendReduction(data, now);
            boolean perfect = isPerfectGuard(data, now);
            boolean guarded = false;
            if (perfect) {
                event.setCanceled(true);
                guarded = true;
                if (attacker instanceof LivingEntity) {
                    data.parryReady = true;
                    data.parryCounter = true;
                    data.parryReadyUntilGameTime = now
                            + Math.round(CombatConstants.PARRY_FOLLOWUP_SECONDS * 20.0F);
                    sendToTrackers(player, new ModPayloads.ClientboundParry(player.getId()));
                }
            } else if (reduction > 0.0F) {
                event.setAmount(event.getAmount() * (1.0F - reduction));
                guarded = true;
                if (categoryOf(player) == WeaponCategory.DOUBLE) {
                    // 格挡后攻击: any guard that lands arms the dual cut.
                    data.parryReady = true;
                    data.parryReadyUntilGameTime = now
                            + Math.round(CombatConstants.PARRY_FOLLOWUP_SECONDS * 20.0F);
                    sendToTrackers(player, new ModPayloads.ClientboundParry(player.getId()));
                }
            }
            // Katana reward: a successful guard sharpens the cadence to 0.25s for 2s.
            if (guarded && data.katanaStance) {
                data.katanaHasteUntilGameTime = now
                        + Math.round(CombatConstants.KATANA_HASTE_SECONDS * 20.0F);
                sendKatanaSpeed(player);
            }
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0F, 1.0F);
        } else if (autoGuard) {
            event.setCanceled(true);
            // 自动格挡成功: the next attack becomes the dual cut.
            data.parryReady = true;
            data.parryReadyUntilGameTime = now
                    + Math.round(CombatConstants.PARRY_FOLLOWUP_SECONDS * 20.0F);
            sendToTrackers(player, new ModPayloads.ClientboundParry(player.getId()));
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

    /** Q is lock-switch while drawn, so a leftover drop packet must not toss the sword. */
    @SubscribeEvent
    static void onItemToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && player.getData(ModAttachments.COMBAT).drawn
                && isSword(event.getEntity().getItem())) {
            event.setCanceled(true);
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
        handleThrust(player, false);
    }

    /**
     * Rapier RMB thrust. {@code chargePiercingArmor} is the single packet-carried hint
     * of "this attack should ignore 30 % of the target's armor" — driven by the client's
     * X hold plus the more deliberate {@link #handleRapierChargeReady} staging.
     */
    public static void handleThrust(ServerPlayer player, boolean chargePiercingArmor) {
        CombatData d = player.getData(ModAttachments.COMBAT);
        long now = player.level().getGameTime();
        boolean effectivePierce = chargePiercingArmor
                || (d.chargeReady
                    && d.chargeReadyAt != Long.MIN_VALUE
                    && now - d.chargeReadyAt <= Math.round(CombatConstants.DEFEND_HOLD_SECONDS * 20.0F * 4.0F));
        // 强化刺击 has the top interrupt priority: it ignores the chain gate, the cancel
        // gate and an in-flight dodge, and only respects its own cooldown. A plain poke
        // still waits for the current action to reach its cancel frame.
        boolean accepted = d.drawn && isHoldingSword(player) && categoryOf(player) == WeaponCategory.SINGLE
                && now >= d.thrustReadyAt
                && (effectivePierce
                    || (d.dodgeTicksLeft == 0 && d.cadence.canStart(now) && canCancelAttack(d, now)));
        boolean hit = false;
        if (accepted) {
            d.active = null;
            endDefend(player, d);
            d.dodgeTicksLeft = 0;
            d.dodgeDx = 0.0;
            d.dodgeDz = 0.0;
            d.thrustReadyAt = now + CombatConstants.THRUST_COOLDOWN_TICKS;
            // Charged thrusts are shorter and snappier; the contact and the lunge
            // share the same fraction of the length either way.
            float length = effectivePierce
                    ? CombatConstants.RAPIER_CHARGED_THRUST_SECONDS
                    : CombatConstants.RAPIER_THRUST_SECONDS;
            float contact = length * CombatConstants.RAPIER_THRUST_CONTACT_SHARE;
            CombatData.ActiveAttack thrust = new CombatData.ActiveAttack(
                    SwingSide.RIGHT, 1, false, false, now, length);
            thrust.category = WeaponCategory.SINGLE;
            thrust.chargePiercingArmor = effectivePierce;
            thrust.thrust = true;
            thrust.contactSeconds = contact;
            thrust.activeStartSeconds = contact;
            thrust.activeEndSeconds = contact + CombatConstants.MIN_ACTIVE_WINDOW_SECONDS;
            thrust.scanned = false;
            thrust.activeStartTick = Math.round(thrust.activeStartSeconds * 20.0F);
            thrust.activeEndTick = Math.max(thrust.activeStartTick + 1,
                    Math.round(thrust.activeEndSeconds * 20.0F));
            thrust.cancelStartTick = Math.max(1,
                    (int) Math.ceil(length * 20.0F - 1.0E-4F));
            thrust.durationTicks = thrust.cancelStartTick;
            d.lastAttackStartGameTime = now;
            d.active = thrust;
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

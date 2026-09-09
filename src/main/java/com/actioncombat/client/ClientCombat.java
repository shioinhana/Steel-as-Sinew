package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import com.actioncombat.combat.AttackCadence;
import com.actioncombat.combat.CombatAttributes;
import com.actioncombat.ModPayloads;
import com.actioncombat.combat.CombatMoves;
import com.actioncombat.combat.FpWeaponClips;
import com.actioncombat.combat.HandRest;
import com.actioncombat.combat.SwingSide;
import com.actioncombat.combat.WeaponCategory;
import com.actioncombat.entity.PlayerAnimatable;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side combat state: drawn flags, camera choreography, input handling, dummy entities.
 * <p>
 * Combat mode is explicit: {@code R} draws / sheathes. Perspective is the player's
 * own choice (vanilla F5) — this class never calls {@code setCameraType}. While
 * sheathed this class takes no input and hides nothing, so attacking, mining and
 * item use behave exactly as vanilla.
 * <p>
 * While drawn the mouse buttons become the two alternating swing sides — attack key
 * = left swing, use key = right swing — and guarding moves to its own keybind. The
 * camera is decoupled from the body: the player's yRot keeps following the mouse and
 * therefore acts as the camera yaw (which also makes WASD camera-relative, since
 * vanilla applies movement input relative to yRot), while the rendered body yaw is
 * owned by {@link BodyOrientation} and follows the aim while directional gait
 * clips provide forward, backward and sideways movement.
 */
public final class ClientCombat {
    /** entityId -> authoritative drawn flag (mirrored from the server). */
    private static final Map<Integer, Boolean> DRAWN_BY_ID = new ConcurrentHashMap<>();
    /** playerId -> animated dummy. */
    private static final Map<Integer, PlayerAnimatable> DUMMIES = new ConcurrentHashMap<>();
    /** playerId -> decoupled body-yaw state. */
    private static final Map<Integer, BodyOrientation> ORIENTATIONS = new ConcurrentHashMap<>();

    /** Dummies are client-only; ids start far above server-assigned entity ids. */
    private static int nextDummyId = 1_000_000_000;

    /** Local predicted drawn state for instant feedback. */
    private static boolean localDrawn;

    /**
     * R only toggles combat mode; it never touches {@code mc.options.setCameraType}.
     * Perspective is entirely the player's choice (vanilla F5). Drawn third person
     * is always the Genshin orbit cam; CameraMixin only nudges it while detached.
     */
    /** Camera rig chooser; owns the eased distance/offset/FOV values. */
    private static final CameraDirector DIRECTOR = new CameraDirector();
    /** Lateral camera offset read by CameraMixin; eased by the director. */
    private static float currentShoulderOffset;
    private static CameraDirector.State lastCameraState = CameraDirector.State.SHEATHED;
    private static CameraType cameraBeforeDraw;
    /** Vertical camera offset read by CameraMixin. */
    private static float currentHeightOffset;
    /** FOV adjustment contributed by the active rig. */
    private static float currentFovBias;

    // input state (swings fire on key release so tap vs charge is decidable)
    private static boolean lmbCaptured;
    private static long lmbDownNanos;
    private static boolean rmbCaptured;
    private static long rmbDownNanos;
    private static boolean localDefending;
    private static boolean localDefendHeld;
    private static boolean localDefendHold;
    private static long localDefendStartNanos;
    private static boolean localAiming;
    private static boolean localInspecting;
    private static long lastActionNanos;
    /** Local mirror of the server's active attack; gates the charge stance. */
    private static long localBusyUntilNanos;
    /** Side/hand of the current swing, consumed by the FP viewmodel. */
    private static SwingSide localAttackSide;
    private static int localAttackDepth;
    private static boolean localDualAttack;
    private static HandRest localLeftHand = HandRest.CENTER;
    private static HandRest localRightHand = HandRest.CENTER;
    private static boolean localParryReady;
    private static long localParryUntilNanos;
    /** Draw clip stays up on the FP viewmodel until this passes. */
    private static long fpDrawBusyUntilNanos;
    /** Authored first-person attack clip window, independent of the third-person swing. */
    private static long fpAttackUntilNanos;
    /**
     * The sheath clip outlives the camera push, so the dummy must be kept (and the
     * vanilla body hidden) until the put_away animation has actually finished.
     */
    private static long sheathUntilNanos;
    /** Real-time timestamp of the previous frame, for orientation easing. */
    private static long lastFrameNanos;
    /** Remembers a swing pressed during recovery so it replays when accepted again. */
    private static final InputBuffer INPUT_BUFFER = new InputBuffer();
    private static final AttackAcknowledgements ATTACK_ACKNOWLEDGEMENTS = new AttackAcknowledgements();
    private static final AttackCadence LOCAL_CADENCE = new AttackCadence();
    /** Real time the local swing began, for the cancel window. */
    private static long localAttackStartNanos;
    /** When the local attack clip ends, mirroring the server's lastAttackEndGameTime. */
    private static long localAttackEndNanos;
    private static int localAttackIntervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;
    /** Duration of the local swing, for the cancel window. */
    private static float localAttackSeconds;
    private static boolean shiftWasDown;
    private static long thrustReadyNanos;
    private static long thrustRecoveryNanos;
    private static boolean rapierLeftDown, rapierRightDown;
    private static int localRapierSlashIndex;
    private static long spearDodgeReadyNanos;
    private static long spearSprintStopNanos;
    private static long spearAdvanceAtNanos;
    private static int spearAdvanceTicks;
    private static Vec3 spearAdvanceStep = Vec3.ZERO;
    public static void onSpearRecovery() {
        if (mc().player == null || mc().player.isPassenger()) return;
        mc().player.setSprinting(false);
        spearSprintStopNanos = System.nanoTime() + 200_000_000L;
    }

    private static int localDodgeTicks;
    private static Vec3 localDodgeStep = Vec3.ZERO;
    /** Dual-wield Shift dash: the local max-speed boost lasts until this nanos deadline. */
    private static long dodgeBoostUntilNanos;
    private static boolean wasAimLocked;
    /** Rapier-only: X press timestamp when the player started a potential charge hold. */
    private static long rapierChargeStartNanos;
    /** Rapier-only: true once this X hold has armed a charge strike; the next
     *  released RMB spends it. {@code false} means no charge is primed. */
    private static boolean localChargeReady;
    /** True when the thrust in flight was the charged (强化) variant. */
    private static boolean localThrustCharged;
    /** Local mirror of the tightened rapier gates after a charged thrust lands. */
    private static long localRapierFastUntilNanos;
    /** Local mirror of the server's per-hand dual-wield streaks. */
    private static int localLeftStreak;
    private static int localRightStreak;

    // ===== rapier hit streak HUD (server-reported) =====
    /** Enemy the streak is counted on; -1 = none. */
    private static int rapierComboTargetId = -1;
    private static int rapierComboHits;
    /** When the server last reported a streak change; the streak expires like it does server-side. */
    private static long rapierComboUpdatedNanos;
    /** Local deadline of the AI-deprivation window started by the 4th hit. */
    private static long rapierSilenceUntilNanos;

    // ===== rapier thrust body motion =====
    /** The lunge starts when the blade extends and is never pulled back. */
    private static long lungeForwardAtNanos;
    private static Vec3 lungeDir = Vec3.ZERO;
    private static int lungeForwardTicks;

    // ===== katana stance (左手拔刀) =====
    /** entityId -> authoritative katana-stance flag (mirrored from the server). */
    private static final Map<Integer, Boolean> KATANA_BY_ID = new ConcurrentHashMap<>();
    /** Local predicted katana stance for instant feedback. */
    private static boolean localKatana;
    /** Entity id of the lock-on target; -1 = no lock. */
    private static int katanaLockId = -1;
    /** Last server-reported non-haste cadence tier (9 = base, 7 = hit streak). */
    private static int katanaSpeedTicks = CombatConstants.KATANA_ATTACK_INTERVAL_TICKS;
    /** Game time the guard haste ends; Long.MIN_VALUE when inactive. */
    private static long katanaHasteUntilGameTime = Long.MIN_VALUE;
    /** Game tick the next katana swing may start on (client prediction). */
    private static long katanaReadyAtTick = Long.MIN_VALUE;

    /** Skip the run_end transition if a swing finished less than this long ago. */
    private static final long RUN_END_SUPPRESS_NANOS = 500_000_000L;

    // ===== drawn state =====

    public static boolean isDrawn(int entityId) {
        return DRAWN_BY_ID.getOrDefault(entityId, false);
    }

    public static boolean isLocalDrawn() {
        return localDrawn;
    }

    // ===== FP viewmodel / camera accessors (read during rendering) =====

    public static boolean isLocalDualWield() {
        LocalPlayer player = mc().player;
        return player != null && player.getMainHandItem().is(ItemTags.SWORDS)
                && player.getOffhandItem().is(ItemTags.SWORDS);
    }

    public static WeaponCategory localWeaponCategory() {
        return mc().player == null ? WeaponCategory.SINGLE : WeaponCategory.of(mc().player);
    }

    public static boolean isLocalKatana() {
        return false;
    }

    public static boolean isKatana(int entityId) {
        return false;
    }

    /** Rapier lock-on: available while drawn with a single sword. Dual-wield does not lock. */
    public static boolean usesLockOn() {
        return localDrawn && localWeaponCategory() == WeaponCategory.SINGLE;
    }

    public static LivingEntity katanaLockTarget() {
        return lockTarget();
    }

    public static LivingEntity lockTarget() {
        if (!usesLockOn() || katanaLockId < 0 || mc().level == null) return null;
        var entity = mc().level.getEntity(katanaLockId);
        if (entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator()) {
            return living;
        }
        return null;
    }

    public static boolean isLocalDefending() {
        return localDefending;
    }

    public static boolean isLocalAiming() {
        return localAiming;
    }

    public static boolean isLocalInspecting() {
        return localInspecting;
    }

    public static boolean isLocalRunning() {
        LocalPlayer player = mc().player;
        return localDrawn && player != null
                && player.walkAnimation.speed() > CombatConstants.RUN_SPEED_THRESHOLD;
    }

    public static boolean isLocalWalking() {
        LocalPlayer player = mc().player;
        return localDrawn && player != null
                && player.walkAnimation.speed() > CombatConstants.WALK_SPEED_THRESHOLD
                && player.walkAnimation.speed() <= CombatConstants.RUN_SPEED_THRESHOLD;
    }

    public static boolean isLocalAttackActive() {
        return System.nanoTime() < localBusyUntilNanos;
    }

    public static boolean isLocalDodging() {
        return localDodgeTicks > 0;
    }

    /**
     * Attack-time camera lock is off. The orbit camera always belongs to the mouse
     * in third person; the body may still face a katana lock target independently.
     */
    public static boolean isAimLocked() {
        return false;
    }

    /** Viewmodel uses the authored FP clip length, which can differ from the body swing. */
    public static boolean isFpAttackActive() {
        return System.nanoTime() < fpAttackUntilNanos;
    }

    /** First person in combat: authored viewmodel only, no vanilla arms or items. */
    public static boolean shouldRenderFirstPersonViewmodel() {
        Minecraft mc = mc();
        return mc.player != null
                && mc.options.getCameraType().isFirstPerson()
                && (localDrawn || isSheathing());
    }

    public static SwingSide localAttackSide() {
        return localAttackSide;
    }

    public static int localAttackDepth() {
        return localAttackDepth;
    }

    public static boolean isLocalDualAttack() {
        return localDualAttack && isFpAttackActive();
    }

    /**
     * Playback speed that stretches a first-person attack clip onto the authoritative
     * swing, so the viewmodel and the third-person body start and finish together.
     * The FP rig only ships two attack clips while the third-person chains have four to
     * six of differing lengths, so without this the viewmodel idles through the tail of
     * the longer swings.
     *
     * @param clipSeconds authored length of the FP clip being played
     */
    public static float fpAttackSpeed(float clipSeconds) {
        if (clipSeconds <= 0.0F || localAttackSeconds <= 0.0F) return 1.0F;
        // clamped so a wildly mismatched clip degrades instead of looking broken
        return Mth.clamp(clipSeconds / localAttackSeconds, 0.25F, 4.0F);
    }

    public static boolean isFpDrawActive() {
        return System.nanoTime() < fpDrawBusyUntilNanos;
    }

    /** True while the put_away clip is still playing after leaving combat mode. */
    public static boolean isSheathing() {
        return System.nanoTime() < sheathUntilNanos;
    }

    public static float currentShoulderOffset() {
        return 0.0F;
    }

    /** Body, jump, dodge and hits all use this yaw: camera facing while drawn. */
    public static float facingYaw(LocalPlayer player) {
        if (player == null) return 0.0F;
        if (mc().options.getCameraType().isFirstPerson()) return player.getYRot();
        return CombatCamera.yaw();
    }

    /**
     * True when this dummy belongs to the local player and they are in first person.
     * The third-person body must not draw in that case: it sits on the camera and the
     * item viewmodel is what should play the first-person clips.
     */
    public static boolean shouldHideDummyInFirstPerson(int ownerPlayerId) {
        LocalPlayer player = mc().player;
        return player != null
                && ownerPlayerId == player.getId()
                && mc().options.getCameraType().isFirstPerson();
    }

    /** Ids of players whose vanilla body must stay hidden (drawn with a live dummy,
     *  or the local player whose sheath animation is still playing). */
    public static PlayerAnimatable dummyOf(int ownerPlayerId) {
        return DUMMIES.get(ownerPlayerId);
    }

    public static java.util.Set<Integer> dummyHiddenPlayerIds() {
        if (YsmStyle.useHostPlayerModel()) return java.util.Set.of();
        var ids = new java.util.HashSet<Integer>();
        for (var e : DRAWN_BY_ID.entrySet()) {
            if (e.getValue() && DUMMIES.containsKey(e.getKey())) ids.add(e.getKey());
        }
        LocalPlayer local = mc().player;
        if (isSheathing() && local != null && DUMMIES.containsKey(local.getId())) {
            ids.add(local.getId());
        }
        return ids;
    }

    /**
     * The sword to draw on a player's back, or empty if none should show.
     * <p>
     * Only while sheathed: once drawn the weapon is in hand (and the body is the geo
     * dummy anyway). Render states carry no entity reference, so the owning player is
     * matched by interpolated position the same way {@code ClientEvents} does.
     */
    public static ItemStack backWeaponFor(AvatarRenderState renderState) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return ItemStack.EMPTY;

        Player owner = ClientEvents.playerForRenderState(renderState);
        if (owner == null) return ItemStack.EMPTY;
        // drawn (or mid-sheath) players carry the weapon in hand instead
        if (isDrawn(owner.getId())) return ItemStack.EMPTY;
        if (owner == mc.player && (localDrawn || isSheathing())) return ItemStack.EMPTY;

        ItemStack main = owner.getMainHandItem();
        if (main.is(ItemTags.SWORDS)) return main;
        ItemStack off = owner.getOffhandItem();
        return off.is(ItemTags.SWORDS) ? off : ItemStack.EMPTY;
    }

    /** True while this player's hand weapon should be hidden (it is on their back). */
    public static boolean shouldHideHandWeapon(AvatarRenderState renderState) {
        return !backWeaponFor(renderState).isEmpty();
    }

    private static boolean isHoldingSword(Player player) {
        return player.getMainHandItem().is(ItemTags.SPEARS) || player.getMainHandItem().is(ItemTags.SWORDS)
                || player.getOffhandItem().is(ItemTags.SWORDS);
    }

    // ===== draw / sheathe =====

    /** R: enter or leave mod combat mode. Requires a sword to enter.
     *  One-handed draw is always the rapier; both hands drawn is dual-wield. */
    public static void onDrawKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (localDrawn) {
            startSheath(mc.player, true);
        } else if (isHoldingSword(mc.player)) {
            startDraw(mc.player, true, false);
        }
    }

    /** V: inspect the weapon on demand; any other action cancels it. */
    public static void onInspectKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !localDrawn) return;
        if (isLocalBusy() || localDefending) return;

        setInspecting(!localInspecting);
        if (!localInspecting) markAction();
    }

    private static void startDraw(LocalPlayer player, boolean sendToServer) {
        startDraw(player, sendToServer, false);
    }

    private static void startDraw(LocalPlayer player, boolean sendToServer, boolean katana) {
        localDrawn = true;
        localKatana = false;
        DRAWN_BY_ID.put(player.getId(), true);
        KATANA_BY_ID.put(player.getId(), false);
        katanaLockId = -1;
        localRapierSlashIndex = 0;
        katanaSpeedTicks = CombatConstants.KATANA_ATTACK_INTERVAL_TICKS;
        katanaHasteUntilGameTime = Long.MIN_VALUE;
        katanaReadyAtTick = Long.MIN_VALUE;
        localAttackSide = null;
        localAttackDepth = 0;
        localDualAttack = false;
        localAttackEndNanos = 0L;
        resetLocalHands();
        localParryReady = false;
        FpViewmodel.INSTANCE.clearAction();
        sheathUntilNanos = 0L;
        boolean dual = player.getMainHandItem().is(ItemTags.SWORDS)
                && player.getOffhandItem().is(ItemTags.SWORDS);
        WeaponCategory drawCategory = WeaponCategory.of(player);
        fpDrawBusyUntilNanos = System.nanoTime()
                + (long) (FpWeaponClips.of(drawCategory).drawSeconds() * 1.0E9F);

        orientationFor(player.getId()).reset(player.getYRot());
        CombatCamera.seedFrom(player);
        DIRECTOR.snapTo(CameraDirector.State.FREE);
        lastCameraState = CameraDirector.State.FREE;

        PlayerAnimatable dummy = ensureDummy(player);
        if (dummy != null) {
            dummy.drawn = true;
            dummy.weaponCategory = drawCategory;
            dummy.playAction(PlayerAnimatable.ANIM_DRAW,
                dummy.weaponCategory == WeaponCategory.DOUBLE ? 0.8F : 0.7333F);
        }
        markAction();
        shiftWasDown = true;
        localDodgeTicks = 0;
        cancelDodgeBoost(player);
        applyDrawnKeybinds(true, !dual);
        if (drawCategory == WeaponCategory.SINGLE) {
            LivingEntity nearest = nearestLockCandidate();
            if (nearest != null) setKatanaLock(nearest.getId(), true);
        }
        if (sendToServer) {
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDrawn(true, false));
        }
    }

    private static void startSheath(LocalPlayer player, boolean sendToServer) {
        localDrawn = false;
        localKatana = false;
        DRAWN_BY_ID.put(player.getId(), false);
        KATANA_BY_ID.put(player.getId(), false);
        if (katanaLockId >= 0) setKatanaLock(-1, false);
        applyDrawnKeybinds(false, false);
        CombatCamera.reset();
        wasAimLocked = false;
        lastCameraState = CameraDirector.State.SHEATHED;
        cameraBeforeDraw = null;

        boolean dual = player.getMainHandItem().is(ItemTags.SWORDS)
                && player.getOffhandItem().is(ItemTags.SWORDS);
        WeaponCategory sheathCat = WeaponCategory.of(player);
        FpWeaponClips.Clips fp = FpWeaponClips.of(sheathCat);
        float sheathSeconds = sheathCat == WeaponCategory.SINGLE
                ? CombatConstants.RAPIER_SHEATH_SECONDS : fp.drawSeconds();
        sheathUntilNanos = System.nanoTime() + (long) (sheathSeconds * 1.0E9F);

        lmbCaptured = false;
        rmbCaptured = false;
        localAiming = false;
        localInspecting = false;
        localAttackSide = null;
        localAttackDepth = 0;
        localDualAttack = false;
        localLeftHand = HandRest.CENTER;
        localRightHand = HandRest.CENTER;
        localParryReady = false;
        FpViewmodel.INSTANCE.clearAction();
        fpDrawBusyUntilNanos = 0L;
        fpAttackUntilNanos = 0L;
        INPUT_BUFFER.clear();
        stopDefending(false);

        PlayerAnimatable dummy = DUMMIES.get(player.getId());
        if (dummy != null) {
            dummy.drawn = false;
            dummy.aiming = false;
            dummy.inspecting = false;
            dummy.playAction(PlayerAnimatable.ANIM_PUT_AWAY,
                    dummy.weaponCategory == WeaponCategory.DOUBLE
                            ? 0.8F : CombatConstants.RAPIER_SHEATH_SECONDS);
            if (dummy.weaponCategory == WeaponCategory.SINGLE) {
                FpViewmodel.INSTANCE.playAction(fp.putAway(), CombatConstants.RAPIER_SHEATH_SECONDS);
            }
        }
        if (sendToServer) {
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDrawn(false, false));
        }
    }

    public static void onDrawState(int playerId, boolean drawn) {
        onDrawState(playerId, drawn, false);
    }

    public static void onDrawState(int playerId, boolean drawn, boolean katana) {
        DRAWN_BY_ID.put(playerId, drawn);
        KATANA_BY_ID.put(playerId, drawn && katana);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == playerId) {
            boolean changed = localDrawn != drawn || localKatana != (drawn && katana);
            if (!changed) return;
            // server-initiated state change (e.g. after respawn); mirror it locally
            if (drawn) {
                startDraw(mc.player, false, katana);
            } else {
                startSheath(mc.player, false);
            }
        } else {
            Player target = findPlayerById(playerId);
            if (target instanceof AbstractClientPlayer acp) {
                PlayerAnimatable dummy = ensureDummy(acp);
                if (dummy != null) {
                    dummy.drawn = drawn;
                    dummy.weaponCategory = katana ? WeaponCategory.KATANA : WeaponCategory.of(acp);
                    if (drawn) {
                        dummy.playAction(PlayerAnimatable.ANIM_DRAW,
                                dummy.weaponCategory == WeaponCategory.DOUBLE ? 0.8F : 0.7333F);
                    }
                }
            }
        }
    }

    /**
     * Server confirmed the local player's swing connected: punch the FOV, freeze the
     * animation clock briefly (hitstop) and kick the camera away from the swing.
     */
    public static void onHitConfirm(boolean hit) {
        if (!hit) return;
        lastHitNanos = System.nanoTime();
        // left swings kick the camera right and vice versa
        float sign = localAttackSide == SwingSide.RIGHT ? -1.0F : 1.0F;
        HitFeedback.trigger(sign);
    }

    /** When the local player last landed a hit, for the impact spark VFX. */
    private static long lastHitNanos;

    public static long lastHitNanos() {
        return lastHitNanos;
    }

    /**
     * Server-reported rapier hit streak. {@code silenceTicks} &gt; 0 means the 4th hit
     * just deprived that enemy's AI; the HUD counts the remainder down locally.
     */
    public static void onRapierCombo(int targetId, int hits, int silenceTicks) {
        rapierComboTargetId = targetId;
        rapierComboHits = Math.max(0, hits);
        rapierComboUpdatedNanos = System.nanoTime();
        rapierSilenceUntilNanos = silenceTicks > 0
                ? System.nanoTime() + silenceTicks * 50_000_000L
                : 0L;
    }

    /** Enemy the rapier streak is counted on, or -1. */
    public static int rapierComboTargetId() {
        return rapierComboTargetId;
    }

    /** Streak length, or 0 once the server's idle window has passed. */
    public static int rapierComboHits() {
        if (rapierComboHits == 0) return 0;
        long idle = System.nanoTime() - rapierComboUpdatedNanos;
        if (idle > (long) (CombatConstants.COMBO_RESET_SECONDS * 1.0E9F)) {
            rapierComboHits = 0;
            rapierComboTargetId = -1;
            return 0;
        }
        return rapierComboHits;
    }

    /** Seconds left of the AI-deprivation window, or 0 when none is active. */
    public static float rapierSilenceSeconds() {
        if (rapierSilenceUntilNanos == 0L) return 0.0F;
        long left = rapierSilenceUntilNanos - System.nanoTime();
        if (left <= 0L) {
            rapierSilenceUntilNanos = 0L;
            return 0.0F;
        }
        return left / 1.0E9F;
    }

    /** Carries the thrust forward as the blade extends. No pull-back. */
    private static void tickThrustLunge(LocalPlayer player) {
        if (lungeForwardAtNanos != 0L && System.nanoTime() >= lungeForwardAtNanos) {
            lungeForwardAtNanos = 0L;
            lungeForwardTicks = CombatConstants.RAPIER_LUNGE_TICKS;
        }
        if (lungeForwardTicks <= 0) return;
        double step = CombatConstants.RAPIER_LUNGE_DISTANCE / CombatConstants.RAPIER_LUNGE_TICKS;
        player.move(MoverType.SELF, lungeDir.scale(step));
        lungeForwardTicks--;
    }

    /**
     * 0..1 progress through the local player's current swing, or -1 when not swinging.
     * Drives the blade-trail VFX on the viewmodel.
     */
    public static float localSwingProgress() {
        return isLocalAttackActive() ? localProgress() : -1.0F;
    }

    /**
     * Katana per-tick lock upkeep: drop or replace a stale lock. Body facing is
     * applied on the dummy in {@link #syncDummy}; the camera is never touched.
     */
    private static void tickKatanaLock(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        LivingEntity lock = lockTarget();
        double retention = CombatConstants.LOCK_RANGE * CombatConstants.LOCK_RANGE;
        if (lock != null && lock.distanceToSqr(player) > retention) lock = null;
        if (katanaLockId >= 0 && lock == null) {
            LivingEntity nearest = nearestLockCandidate();
            setKatanaLock(nearest != null ? nearest.getId() : -1, true);
        }
    }

    private static int clientKatanaIntervalTicks() {
        if (mc().level == null) return CombatConstants.KATANA_ATTACK_INTERVAL_TICKS;
        long now = mc().level.getGameTime();
        if (katanaHasteUntilGameTime != Long.MIN_VALUE && now < katanaHasteUntilGameTime) {
            return CombatConstants.KATANA_HASTE_INTERVAL_TICKS;
        }
        return katanaSpeedTicks;
    }

    private static float yawTo(Player player, LivingEntity target) {
        return (float) Math.toDegrees(Math.atan2(
                -(target.getX() - player.getX()), target.getZ() - player.getZ()));
    }

    private static float movementHeading(LocalPlayer player, float cameraYaw) {
        if (player.input == null) return cameraYaw;
        var move = player.input.getMoveVector();
        if (move.lengthSquared() < 1.0E-4F) return cameraYaw;
        return Mth.wrapDegrees(cameraYaw + (float) Math.toDegrees(Math.atan2(-move.x, move.y)));
    }

    /** Server-confirmed katana cadence update (hit streak / whiff / guard haste). */
    public static void onKatanaSpeed(int intervalTicks, long hasteUntilGameTime) {
        katanaSpeedTicks = intervalTicks;
        katanaHasteUntilGameTime = hasteUntilGameTime;
    }

    // ===== katana lock-on =====

    private static void setKatanaLock(int entityId, boolean syncToServer) {
        if (katanaLockId == entityId) return;
        katanaLockId = entityId;
        if (syncToServer) {
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundKatanaLock(entityId));
        }
    }

    private static LivingEntity nearestLockCandidate() {
        LocalPlayer player = mc().player;
        if (player == null || mc().level == null) return null;
        double range = CombatConstants.LOCK_RANGE;
        LivingEntity nearest = null;
        double best = range * range;
        for (LivingEntity e : mc().level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range),
                en -> en != player && en.isAlive() && !en.isSpectator())) {
            double dist = e.distanceToSqr(player);
            if (dist < best && player.hasLineOfSight(e)) {
                best = dist;
                nearest = e;
            }
        }
        return nearest;
    }

    /**
     * Q / E while katana-drawn: cycle the lock through nearby enemies by their
     * left/right relation to the current target, seen from the camera.
     */
    public static void onLockSwitch(boolean toRight) {
        if (!usesLockOn() || mc().player == null || mc().level == null || mc().screen != null) return;
        LocalPlayer player = mc().player;
        double range = CombatConstants.LOCK_RANGE;
        java.util.List<LivingEntity> candidates = mc().level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range),
                e -> e != player && e.isAlive() && !e.isSpectator() && player.hasLineOfSight(e));
        if (candidates.isEmpty()) {
            setKatanaLock(-1, true);
            return;
        }
        LivingEntity current = lockTarget();
        if (current == null) { // no valid lock: take the nearest
            candidates.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(player)));
            setKatanaLock(candidates.get(0).getId(), true);
            return;
        }
        // Bearings are relative to the camera: positive delta = target sits further
        // right than the current one. Pick the closest candidate on the requested
        // side; wrap around to the far side when there is none.
        float camYaw = mc().options.getCameraType().isFirstPerson()
                ? player.getYRot() : CombatCamera.yaw();
        double curBearing = bearingTo(player, current, camYaw);
        LivingEntity best = null;
        double bestDelta = Double.MAX_VALUE;
        LivingEntity wrap = null;
        double wrapDelta = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            if (e.getId() == current.getId()) continue;
            double delta = Mth.wrapDegrees(bearingTo(player, e, camYaw) - curBearing);
            boolean side = toRight ? delta < 0 : delta > 0; // right = smaller bearing (yaw decreases rightward)
            double abs = Math.abs(delta);
            if (side && abs < bestDelta) { bestDelta = abs; best = e; }
            if (!side && (360.0 - abs) < wrapDelta) { wrapDelta = 360.0 - abs; wrap = e; }
        }
        LivingEntity next = best != null ? best : wrap;
        if (next != null) setKatanaLock(next.getId(), true);
    }

    /** Camera-relative bearing of an entity: world yaw from the player to it, minus camera yaw. */
    private static double bearingTo(LocalPlayer player, LivingEntity target, float camYaw) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        return Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - camYaw);
    }

    /** Another player (or this one) was hit: play the flinch on their dummy. */
    public static void onHurt(int playerId) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        if (dummy == null) return;
        dummy.playUpper(dummy.weaponCategory.hurt, 0.35F);
    }

    /** Server-driven swing clip — authoritative for every client. */
    public static void onAttackAnim(int playerId, SwingSide side, int depth, boolean dual, int intervalTicks) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        if (dummy == null) return;
        String anim = dual ? dummy.weaponCategory.dualOverlay()
                : dummy.weaponCategory.attackOverlay(side, depth);
        if (anim == null) return;
        // The payload carries the authoritative gate. A slow dual third swing (12) and
        // the fourth rapier slash (10) are valid, so never clamp to the katana range:
        // clamping shortened the local gate and made the client send attacks the
        // server then rejected, which desynced the whole combo.
        intervalTicks = Math.max(1, intervalTicks);
        // The authored clip is 0.4s: a faster gate must not cut it short, and a slow
        // gate (the dual third swing) keeps the pose up until the next swing is allowed.
        float seconds = Math.max(CombatConstants.ATTACK_DURATION_SECONDS,
                intervalTicks / 20.0F + CombatConstants.ATTACK_CANCEL_TAIL_SECONDS);

        LocalPlayer local = mc().player;
        boolean isLocal = local != null && local.getId() == playerId;
        if (isLocal) {
            if (dummy.weaponCategory == WeaponCategory.DOUBLE) {
                // Adopt the server's authoritative hand so one desync cannot cascade
                // into a whole combo playing on the wrong arm.
                if (dual) {
                    localLeftHand = HandRest.LEFT;
                    localRightHand = HandRest.RIGHT;
                } else if (depth == 0) {
                    localLeftHand = HandRest.of(side);
                } else {
                    localRightHand = HandRest.of(side);
                }
            }
            var prediction = ATTACK_ACKNOWLEDGEMENTS.confirmPrediction(side, depth, dual);
            if (prediction != null && prediction.startedAtNanos() == localAttackStartNanos) {
                LOCAL_CADENCE.synchronize(prediction.startedAtTick(), intervalTicks);
                localAttackIntervalTicks = intervalTicks;
                if (prediction.intervalTicks() != intervalTicks && isLocalBusy()
                        && dummy.hasUpperAction() && dummy.actionClip.startsWith("attack")) {
                    localAttackSeconds = seconds;
                    localBusyUntilNanos = localAttackStartNanos + (long) (seconds * 1.0E9F);
                    fpAttackUntilNanos = localBusyUntilNanos;
                    dummy.retimeAttack(intervalTicks);
                }
                return;
            }
            // 连续攻击/格挡后: never restart a clip that is already on screen. A stale
            // confirmation belongs to an older swing, and a matching current swing is
            // already playing; replaying either one is what made the pose jump.
            if (prediction != null) return;
            if (localAttackSide == side && localAttackDepth == depth && localDualAttack == dual
                    && dummy.hasUpperAction()) {
                localAttackIntervalTicks = intervalTicks;
                dummy.retimeAttack(intervalTicks);
                return;
            }
        }
        if (isLocal && !localDrawn) return;

        dummy.aiming = false;
        dummy.inspecting = false;
        dummy.defending = false;
        dummy.lastAttackNanos = System.nanoTime();
        dummy.playUpper(anim, seconds, intervalTicks);

        if (isLocal) {
            localAttackSide = side;
            localAttackDepth = depth;
            localDualAttack = dual;
            stopDefending(false);
            localAiming = false;
            setInspecting(false);
            localAttackSeconds = seconds;
            localAttackIntervalTicks = intervalTicks;
            LOCAL_CADENCE.synchronize(mc().level.getGameTime(), intervalTicks);
            localAttackStartNanos = System.nanoTime();
            localAttackEndNanos = localAttackStartNanos
                    + (long) (CombatConstants.ATTACK_DURATION_SECONDS * 1.0E9F);
            localBusyUntilNanos = localAttackStartNanos
                    + (long) (localAttackSeconds * 1.0E9F);
            FpWeaponClips.Clips fp = FpWeaponClips.of(dummy.weaponCategory);
            float fpSeconds = localAttackSeconds;
            fpAttackUntilNanos = localAttackStartNanos + (long) (fpSeconds * 1.0E9F);
            FpViewmodel.INSTANCE.playAction(dual ? fp.dualAttack() : fp.attackClip(side, depth), fpSeconds);
            INPUT_BUFFER.clear();
        }
    }

    public static void onParry(int playerId) {
        LocalPlayer local = mc().player;
        if (local == null || local.getId() != playerId) return;
        localParryReady = true;
        localParryUntilNanos = System.nanoTime()
                + (long) (CombatConstants.PARRY_FOLLOWUP_SECONDS * 1.0E9F);
        lastHitNanos = System.nanoTime();
        spawnParrySparks(local);
    }

    private static void spawnParrySparks(LocalPlayer player) {
        if (player.level() == null) return;
        Vec3 look = player.getLookAngle();
        Vec3 pos = player.getEyePosition().add(look.scale(1.15));
        for (int i = 0; i < 14; i++) {
            double ox = (player.getRandom().nextDouble() - 0.5) * 0.45;
            double oy = player.getRandom().nextDouble() * 0.35;
            double oz = (player.getRandom().nextDouble() - 0.5) * 0.45;
            player.level().addParticle(ParticleTypes.CRIT, pos.x, pos.y, pos.z, ox, oy, oz);
            player.level().addParticle(ParticleTypes.ENCHANTED_HIT, pos.x, pos.y, pos.z, ox * 0.6, oy, oz * 0.6);
        }
    }

    public static void onDefendState(int playerId, boolean defending) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        if (dummy != null) {
            if (defending) {
                dummy.defending = true;
                dummy.guardRecoverUntilNanos = 0L;
            } else {
                dummy.beginGuardRecover();
            }
        }
        LocalPlayer local = mc().player;
        if (local != null && local.getId() == playerId) {
            localDefending = defending;
        }
    }

    public static void onDodgeAnim(int playerId) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        if (dummy == null) return;
        if (dummy.weaponCategory == WeaponCategory.DOUBLE) {
            // Dual dodge is a speed boost: keep walking, jumping and attacking.
            return;
        }
        dummy.dodging = true;
        dummy.aiming = false;
        dummy.beginGuardRecover();
        dummy.playAction(dummy.weaponCategory.runStart, CombatConstants.DODGE_SECONDS);
        LocalPlayer local = mc().player;
        if (local != null && local.getId() == playerId) stopDefending(false);
    }

    /**
     * Drawn combat remaps sneak: vanilla Shift is dodge, C is crouch.
     * Called from {@code MovementInputUpdateEvent} after KeyboardInput writes the packet.
     */
    public static void onMovementInput(ClientInput input) {
        if (!localDrawn) return;
        Input keys = input.keyPresses;
        if (keys.shift() && !shiftWasDown) tryStartDodge(keys);
        shiftWasDown = keys.shift();
        boolean crouch = localWeaponCategory() != WeaponCategory.SPEAR && ModKeybinds.CROUCH.isDown();
        input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(),
                keys.jump(), crouch, keys.sprint() && System.nanoTime() >= spearSprintStopNanos);
    }

    private static void tryStartDodge(Input keys) {
        LocalPlayer player = mc().player;
        if (player == null || mc().screen != null) return;
        long now = System.nanoTime();
        WeaponCategory dodgeCat = localWeaponCategory();
        if (dodgeCat == WeaponCategory.DOUBLE) {
            // Dual-wield Shift is a 0.3s maximum-move-speed boost, not a scripted step:
            // no clip, no displacement and no cancelled action. Walking, jumping,
            // swinging and guarding all stay available and turn it into a dash.
            if (now < dodgeBoostUntilNanos) return;
            dodgeBoostUntilNanos = now + (long) (CombatConstants.DUAL_DASH_SECONDS * 1.0E9F);
            CombatAttributes.setDualDashBoost(player, true);
            markAction();
            ClientPacketDistributor.sendToServer(
                    new ModPayloads.ServerboundDodge(facingYaw(player), CombatMoves.packDirs(keys)));
            return;
        }
        if (localDodgeTicks > 0) return;
        if (!player.onGround()) return;
        // 闪避 has the top interrupt priority: it cancels whatever is playing now.
        INPUT_BUFFER.clear();
        int dirs = CombatMoves.packDirs(keys);
        float yaw = facingYaw(player);
        if (dodgeCat == WeaponCategory.SPEAR && System.nanoTime() < spearDodgeReadyNanos) return;
        Vec3 total = dodgeCat == WeaponCategory.SPEAR ? com.actioncombat.combat.SpearMoves.backstep(yaw)
                : CombatMoves.dodgeOffset(yaw, dirs);
        if (dodgeCat == WeaponCategory.SPEAR) spearDodgeReadyNanos = System.nanoTime() + 400_000_000L;
        if (dodgeCat == WeaponCategory.SINGLE)
            total = total.scale(CombatConstants.RAPIER_DODGE_DISTANCE / CombatConstants.DODGE_DISTANCE);
        else if (dodgeCat == WeaponCategory.KATANA)
            total = total.scale(CombatConstants.KATANA_DODGE_DISTANCE / CombatConstants.DODGE_DISTANCE);
        int ticks = Math.max(1, CombatConstants.DODGE_TICKS);
        localDodgeStep = total.scale(1.0 / ticks);
        localDodgeTicks = ticks;
        spearAdvanceTicks = 0;
        localBusyUntilNanos = 0L;
        fpAttackUntilNanos = 0L;
        localAttackSide = null;
        stopDefending(false);
        setAiming(false);
        PlayerAnimatable dummy = DUMMIES.get(player.getId());
        if (dummy != null) {
            dummy.dodging = true;
            dummy.clearAction();
            dummy.playAction(dummy.weaponCategory.runStart, CombatConstants.DODGE_SECONDS);
        }
        FpWeaponClips.Clips fp = FpWeaponClips.of(localWeaponCategory());
        if (fp.runStart() != null) {
            FpViewmodel.INSTANCE.playAction(fp.runStart(), CombatConstants.DODGE_SECONDS);
        }
        ClientPacketDistributor.sendToServer(
                new ModPayloads.ServerboundDodge(yaw, dirs));
        markAction();
    }

    // ===== input =====

    /** Attack key pressed while drawn: opens the LEFT swing, resolved on release.
     *  @return true if the vanilla interaction should be swallowed. */
    public static boolean onAttackPress() {
        if (!localDrawn) return false;
        if (localWeaponCategory() == WeaponCategory.SPEAR) {
            if (!rapierLeftDown) { rapierLeftDown = true; requestSwing(SwingSide.LEFT, ModKeybinds.CROUCH.isDown(), false); }
            return true;
        }
        if (localWeaponCategory() == WeaponCategory.SINGLE) {
            if (!rapierLeftDown) { rapierLeftDown = true; requestRapier(false); }
            return true;
        }
        lmbCaptured = true;
        lmbDownNanos = System.nanoTime();
        markAction();
        if (tryDualSlash()) return true;
        stopDefending(true);
        return true;
    }

    /** Use key pressed while drawn: opens the RIGHT swing, resolved on release.
     *  @return true if the vanilla interaction should be swallowed. */
    public static boolean onUsePress() {
        if (!localDrawn) return false;
        if (localWeaponCategory() == WeaponCategory.SPEAR) {
            if (!rapierRightDown) {
                rapierRightDown = true;
                if (ModKeybinds.CROUCH.isDown()) requestSwing(SwingSide.RIGHT, true, false);
                else onBlockKeyState(true);
            }
            return true;
        }
        if (localWeaponCategory() == WeaponCategory.SINGLE) {
            if (!rapierRightDown) { rapierRightDown = true; requestRapier(true); }
            return true;
        }
        rmbCaptured = true;
        rmbDownNanos = System.nanoTime();
        markAction();
        if (tryDualSlash()) return true;
        stopDefending(true);
        return true;
    }

    private static void requestRapier(boolean thrust) {
        if (mc().screen != null || (thrust && System.nanoTime() < thrustReadyNanos)) return;
        // 强化刺击 has the top interrupt priority: it may cancel any running action, so it
        // skips the chain and cancel gates. A plain poke still respects them.
        boolean chargedThrust = thrust && localChargeReady;
        if (!chargedThrust && !canActNow()) return;
        INPUT_BUFFER.clear();
        lmbCaptured = rmbCaptured = false;
        stopDefending(true);
        boolean spendCharge = false;
        if (thrust) {
            long now = System.nanoTime();
            thrustReadyNanos = now + 2_000_000_000L;
            if (chargedThrust) {
                // The server stops the dodge when the thrust interrupts it; mirror that
                // so the predicted displacement does not keep running ahead of the server.
                localDodgeTicks = 0;
                localDodgeStep = Vec3.ZERO;
        spearAdvanceTicks = 0;
            }
            spendCharge = localChargeReady;
            localChargeReady = false;
            rapierChargeStartNanos = 0L;
            // 强化刺击 is 0.20s, a normal poke 0.50s; contact and the lunge share half of it.
            localThrustCharged = spendCharge;
            float seconds = spendCharge
                    ? CombatConstants.RAPIER_CHARGED_THRUST_SECONDS
                    : CombatConstants.RAPIER_THRUST_SECONDS;
            float contact = seconds * CombatConstants.RAPIER_THRUST_CONTACT_SHARE;
            thrustRecoveryNanos = now + (long) (seconds * 1.0E9F);
            localAttackEndNanos = now + (long) (seconds * 1.0E9F);
            lungeDir = Vec3.directionFromRotation(0.0F, facingYaw(mc().player));
            lungeForwardAtNanos = now + (long) (contact * 1.0E9F);
            lungeForwardTicks = 0;
            playThrustVisual(mc().player.getId(), false, seconds);
        } else if (!playLocalAttack(SwingSide.LEFT, false, false)) {
            return;
        }
        ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAttack(
                (thrust ? SwingSide.RIGHT : SwingSide.LEFT).id(), false, false, spendCharge));
        markAction();
    }

    private static void playThrustVisual(int playerId, boolean hit, float seconds) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        String clip = hit ? "rapier_thrust_hit" : "rapier_thrust_miss";
        if (dummy != null) {
            dummy.defending = false;
            dummy.aiming = false;
            // Full-body: the gather-to-chest and lunge live on the legs/torso.
            // Upper overlay alone looked like the old short poke.
            dummy.playAction(clip, seconds);
        }
        if (mc().player != null && mc().player.getId() == playerId) {
            FpViewmodel.INSTANCE.playAction(clip, seconds);
            localAttackSeconds = Math.max(localAttackSeconds, seconds);
            localAttackSide = null;
            fpAttackUntilNanos = System.nanoTime() + (long)(seconds * 1.0E9);
        }
    }

    public static void onThrust(int playerId, boolean accepted, boolean hit, int cooldownTicks, int recoveryTicks) {
        boolean self = mc().player != null && mc().player.getId() == playerId;
        if (self) {
            thrustReadyNanos = System.nanoTime() + Math.max(0, cooldownTicks) * 50_000_000L;
            if (hit) thrustRecoveryNanos = 0L;
            else if (recoveryTicks > 0) {
                thrustRecoveryNanos = System.nanoTime() + recoveryTicks * 50_000_000L;
            }
        }
        if (!accepted) {
            if (self) {
                PlayerAnimatable dummy = DUMMIES.get(playerId);
                if (dummy != null) dummy.clearAction();
                FpViewmodel.INSTANCE.clearAction();
                thrustRecoveryNanos = 0L;
            }
            return;
        }
        if (self && hit && localThrustCharged) {
            localRapierFastUntilNanos = System.nanoTime()
                    + (long) (CombatConstants.RAPIER_FAST_WINDOW_SECONDS * 1.0E9F);
        }
        if (!self) playThrustVisual(playerId, hit, CombatConstants.RAPIER_THRUST_SECONDS);
        if (self && hit) onHitConfirm(true);
    }

    public static void onGuardBash(int playerId) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        if (dummy != null) dummy.playUpper("guard_bash_upper", 0.25F);
        if (mc().player != null && mc().player.getId() == playerId)
            FpViewmodel.INSTANCE.playAction("guard_bash", 0.25F);
    }

    /**
     * 格挡后攻击: with a guard reward armed, the next attack press is the dual cut.
     * Both mouse buttons together are no longer required.
     */
    private static boolean tryDualSlash() {
        if (!parryReadyNow()) return false;
        if (localWeaponCategory() != WeaponCategory.DOUBLE) return false;
        lmbCaptured = false;
        rmbCaptured = false;
        stopDefending(true);
        requestSwing(SwingSide.LEFT, false, true);
        return true;
    }

    private static boolean parryReadyNow() {
        if (!localParryReady) return false;
        if (System.nanoTime() >= localParryUntilNanos) {
            localParryReady = false;
            return false;
        }
        return true;
    }

    /** Dedicated block key, held. */
    public static void onBlockKeyState(boolean down) {
        if (!localDrawn) {
            if (localDefending) stopDefending(true);
            return;
        }
        if (down) {
            localDefendHeld = true;
            if (!localDefending) {
                if (!canActNow()) return;
                INPUT_BUFFER.clear();
                markAction();
                localDefending = true;
                localDefendHold = false;
                localDefendStartNanos = System.nanoTime();
                localBusyUntilNanos = 0L;
                fpAttackUntilNanos = 0L;
                localAttackSide = null;
                localAttackDepth = 0;
                localDualAttack = false;
                // The server resets both hands (and the rapier chain index) when a
                // guard starts; without this mirror the next swing picks another hand
                // than the server and its confirmation replayed a wrong clip.
                resetLocalHands();
                PlayerAnimatable dummy = DUMMIES.get(localId());
                if (dummy != null) {
                    dummy.defending = true;
                    dummy.guardRecoverUntilNanos = 0L;
                    dummy.clearAction();
                }
                FpViewmodel.INSTANCE.clearAction();
                ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDefending(true));
            }
        } else if (localDefendHeld) {
            localDefendHeld = false;
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDefending(false));
            if (localDefendHold || localWeaponCategory() == WeaponCategory.SPEAR) stopDefending(false);
        }
    }

    private static void tickInput(Minecraft mc, LocalPlayer player, long now) {
        boolean blocked = mc.screen != null;
        if (!mc.options.keyAttack.isDown()) rapierLeftDown = false;
        if (!mc.options.keyUse.isDown()) rapierRightDown = false;
        if (blocked) INPUT_BUFFER.clear();

        // each mouse button owns one swing side; release decides tap vs charge
        if (lmbCaptured) {
            boolean released = !mc.options.keyAttack.isDown();
            if (released || blocked) {
                boolean charged = released && !blocked
                        && now - lmbDownNanos >= chargeDelayNanos();
                lmbCaptured = false;
                setAiming(false);
                if (localDrawn && released && !blocked) {
                    sendSwing(SwingSide.LEFT, charged);
                }
            }
        }
        if (rmbCaptured) {
            boolean released = !mc.options.keyUse.isDown();
            if (released || blocked) {
                boolean charged = released && !blocked
                        && now - rmbDownNanos >= chargeDelayNanos();
                rmbCaptured = false;
                setAiming(false);
                if (localDrawn && released && !blocked) {
                    sendSwing(SwingSide.RIGHT, charged);
                }
            }
        }
        // holding either button past the delay enters the charge stance
        boolean charging = (lmbCaptured && now - lmbDownNanos >= chargeDelayNanos())
                || (rmbCaptured && now - rmbDownNanos >= chargeDelayNanos());
        if (charging && !isLocalBusy()) {
            setAiming(true);
        }

        // guard follows its own key, so it can be held while moving
        onBlockKeyState(!blocked && (localWeaponCategory() == WeaponCategory.SPEAR
                ? mc.options.keyUse.isDown() && !ModKeybinds.CROUCH.isDown() : ModKeybinds.BLOCK.isDown()));
        tickDefend(now);
        if (!blocked) tickBufferedSwing();

        // movement keeps the idle timer fresh
        if (player.walkAnimation.speed() > CombatConstants.WALK_SPEED_THRESHOLD) {
            markAction();
        }

        // auto-inspect after a long idle
        if (localDrawn && !localInspecting && !lmbCaptured && !rmbCaptured
                && !localDefending && !isLocalBusy()
                && player.walkAnimation.speed() <= CombatConstants.WALK_SPEED_THRESHOLD
                && now - lastActionNanos >= inspectDelayNanos()) {
            setInspecting(true);
        }
    }

    /**
     * Send a swing now if the server could plausibly accept it, otherwise buffer it.
     * The client cannot know the server's combo window exactly, so "plausibly" means
     * either idle or already past the cancel point of the current swing; anything
     * earlier is buffered and retried by {@link #tickBufferedSwing}.
     */
    private static void sendSwing(SwingSide side, boolean charged) {
        markAction();
        requestSwing(side, charged, false);
    }

    private static void requestSwing(SwingSide side, boolean charged, boolean dual) {
        // A fresh input replaces an older queued intent even when it can play now.
        INPUT_BUFFER.clear();
        if (canActNow(charged)) {
            if (playLocalAttack(side, charged, dual)) {
                ClientPacketDistributor.sendToServer(
                        new ModPayloads.ServerboundAttack(side.id(), charged, dual, false));
            }
        } else {
            INPUT_BUFFER.push(side, charged, dual, nextAttackAtNanos(charged));
        }
    }

    /**
     * Play the swing on the local dummy immediately so the clip does not wait on the
     * server round-trip. Depth follows the same alternation rule as the server; the
     * later ClientboundAttackAnim packet re-applies the authoritative clip, which is
     * a no-op when they agree.
     */
    private static boolean playLocalAttack(SwingSide side, boolean charged, boolean dual) {
        if (!canActNow(charged)) return false;
        PlayerAnimatable dummy = DUMMIES.get(localId());
        if (dummy == null) return false;
        WeaponCategory cat = dummy.weaponCategory;
        expireLocalHands();

        String anim;
        int hand = 0;
        if (dual) {
            if (!parryReadyNow()) return false;
            anim = cat.dualOverlay();
            localLeftHand = HandRest.LEFT;
            localRightHand = HandRest.RIGHT;
            localParryReady = false;
        } else if (cat == WeaponCategory.SPEAR) {
            hand = side == SwingSide.LEFT && charged ? 1 : 0;
            anim = cat.attackOverlay(side, hand);
        } else if (cat == WeaponCategory.SINGLE) {
            int variant = Math.floorMod(localRapierSlashIndex++, 4);
            SwingSide clipSide = (variant == 0 || variant == 2) ? SwingSide.LEFT : SwingSide.RIGHT;
            hand = (variant == 0 || variant == 1) ? 0 : 1;
            anim = cat.attackOverlay(clipSide, hand);
            if (anim == null) return false;
            side = clipSide;
        } else {
            // The server resets both hands (and the chain index) on a charged swing.
            if (charged) resetLocalHands();
            hand = HandRest.pickHand(side, localLeftHand, localRightHand);
            if (hand < 0) return false;
            if (hand == 0) localLeftHand = HandRest.of(side);
            else localRightHand = HandRest.of(side);
            anim = cat.attackOverlay(side, hand);
            if (anim == null) return false;
        }

        long startedAtTick = mc().level.getGameTime();
        int intervalTicks;
        if (cat == WeaponCategory.SPEAR) {
            intervalTicks = LOCAL_CADENCE.begin(startedAtTick, 8);
        } else if (cat == WeaponCategory.KATANA) {
            intervalTicks = clientKatanaIntervalTicks();
            katanaReadyAtTick = startedAtTick + intervalTicks;
        } else if (cat == WeaponCategory.DOUBLE && !dual) {
            // Mirror the server's per-hand streaks: two in a row on one hand go slow.
            intervalTicks = LOCAL_CADENCE.begin(startedAtTick,
                    AttackCadence.dualIntervalTicks(hand == 0, localLeftStreak, localRightStreak));
            if (hand == 0) {
                localLeftStreak++;
                localRightStreak = Math.max(0, localRightStreak - 1);
            } else {
                localRightStreak++;
                localLeftStreak = Math.max(0, localLeftStreak - 1);
            }
        } else {
            int rapierStep = cat == WeaponCategory.SINGLE
                    ? Math.floorMod(localRapierSlashIndex - 1, 4) : 0;
            boolean fast = cat == WeaponCategory.SINGLE
                    && System.nanoTime() < localRapierFastUntilNanos;
            intervalTicks = LOCAL_CADENCE.begin(startedAtTick,
                    AttackCadence.intervalTicksFor(cat, rapierStep, fast));
        }
        // The authored clip is 0.4s: a faster gate must not cut it short, and a slow
        // gate (the dual third swing) keeps the pose up until the next swing is allowed.
        float seconds = Math.max(CombatConstants.ATTACK_DURATION_SECONDS,
                intervalTicks / 20.0F + CombatConstants.ATTACK_CANCEL_TAIL_SECONDS);

        dummy.aiming = false;
        dummy.inspecting = false;
        dummy.defending = false;
        dummy.lastAttackNanos = System.nanoTime();
        dummy.playUpper(anim, seconds, intervalTicks);
        if (cat == WeaponCategory.SPEAR && side == SwingSide.RIGHT && !mc().player.isPassenger()) {
            spearAdvanceAtNanos = System.nanoTime() + 180_000_000L;
            spearAdvanceTicks = 4;
            spearAdvanceStep = Vec3.directionFromRotation(0, facingYaw(mc().player)).scale(0.1);
        }

        localAttackSide = side;
        localAttackDepth = hand;
        localDualAttack = dual;
        localAiming = false;
        setInspecting(false);
        stopDefending(false);
        localAttackSeconds = seconds;
        localAttackIntervalTicks = intervalTicks;
        localAttackStartNanos = System.nanoTime();
        localAttackEndNanos = localAttackStartNanos
                + (long) (CombatConstants.ATTACK_DURATION_SECONDS * 1.0E9F);
        localBusyUntilNanos = localAttackStartNanos + (long) (seconds * 1.0E9F);
        FpWeaponClips.Clips fp = FpWeaponClips.of(cat);
        float fpSeconds = seconds;
        String fpClip = dual ? fp.dualAttack() : fp.attackClip(side, hand);
        fpAttackUntilNanos = localAttackStartNanos + (long) (fpSeconds * 1.0E9F);
        FpViewmodel.INSTANCE.playAction(fpClip, fpSeconds);
        ATTACK_ACKNOWLEDGEMENTS.predict(side, hand, dual,
                startedAtTick, localAttackStartNanos, intervalTicks);
        return true;
    }

    /**
     * Mirrors {@link CombatData#resetHandsApart()}: both hands back to the open ready
     * stance and the rapier chain back to its first slash.
     */
    private static void resetLocalHands() {
        localLeftHand = HandRest.CENTER;
        localRightHand = HandRest.CENTER;
        localRapierSlashIndex = 0;
    }

    /**
     * The server resets the hands two seconds after the last clip ENDED, not after the
     * gate closed. Measuring from the busy window instead left the client holding a
     * hand the server had already forgotten, which mis-matched the next prediction.
     */
    private static void expireLocalHands() {
        if (localAttackEndNanos == 0L) return;
        long now = System.nanoTime();
        if (now < localAttackEndNanos) return;
        if (now - localAttackEndNanos > (long) (CombatConstants.COMBO_RESET_SECONDS * 1.0E9F)) {
            resetLocalHands();
        }
    }

    /** A swing keeps the recovery gate latched when it began. */
    private static boolean canActNow() {
        return canActNow(false);
    }

    private static boolean canActNow(boolean charged) {
        boolean cadenceOk = localWeaponCategory() == WeaponCategory.KATANA
                ? mc().level != null && mc().level.getGameTime() >= katanaReadyAtTick
                : mc().level != null && LOCAL_CADENCE.canStart(mc().level.getGameTime());
        return System.nanoTime() >= thrustRecoveryNanos
                && localDodgeTicks <= 0
                && mc().level != null
                && cadenceOk
                && System.nanoTime() >= nextAttackAtNanos(charged);
    }

    private static long nextAttackAtNanos(boolean charged) {
        if (localAttackStartNanos == 0L) return 0L;
        return localAttackStartNanos + localAttackIntervalTicks * 50_000_000L;
    }

    /** 0..1 progress through the local swing, or 1 when idle. */
    private static float localProgress() {
        if (localAttackSeconds <= 0.0F) return 1.0F;
        float elapsed = (System.nanoTime() - localAttackStartNanos) / 1.0E9F;
        return Math.min(elapsed / localAttackSeconds, 1.0F);
    }

    /** Replay a buffered swing as soon as the body is free enough to act. */
    private static void tickBufferedSwing() {
        if (!localDrawn || localDefending || !INPUT_BUFFER.hasPending()) return;
        if (!canActNow(INPUT_BUFFER.pendingCharged())) return;
        SwingSide side = INPUT_BUFFER.pendingSide();
        boolean charged = INPUT_BUFFER.pendingCharged();
        boolean dual = INPUT_BUFFER.pendingDual();
        if (playLocalAttack(side, charged, dual)) {
            INPUT_BUFFER.clear();
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAttack(side.id(), charged, dual, false));
            markAction();
        }
    }

    private static void setAiming(boolean aiming) {
        if (localAiming == aiming) return;
        localAiming = aiming;
        PlayerAnimatable dummy = DUMMIES.get(localId());
        if (dummy != null) dummy.aiming = aiming;
    }

    private static void setInspecting(boolean inspecting) {
        if (localInspecting == inspecting) return;
        localInspecting = inspecting;
        PlayerAnimatable dummy = DUMMIES.get(localId());
        if (dummy != null) dummy.inspecting = inspecting;
    }

    private static void tickDefend(long now) {
        if (!localDefending) return;
        if (localWeaponCategory() == WeaponCategory.SPEAR && localDefendHeld) return;
        float elapsed = (now - localDefendStartNanos) / 1.0E9F;
        if (localDefendHeld && elapsed >= CombatConstants.DEFEND_HOLD_THRESHOLD_SECONDS) {
            localDefendHold = true;
        }
        float max = localDefendHold
                ? CombatConstants.DEFEND_HOLD_SECONDS
                : CombatConstants.DEFEND_TAP_SECONDS;
        if (elapsed > max) stopDefending(localDefendHeld);
    }

    private static void stopDefending(boolean send) {
        if (!localDefending && !localDefendHeld) return;
        localDefending = false;
        localDefendHeld = false;
        localDefendHold = false;
        PlayerAnimatable dummy = DUMMIES.get(localId());
        if (dummy != null) dummy.beginGuardRecover();
        if (send) {
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDefending(false));
        }
    }

    private static void markAction() {
        lastActionNanos = System.nanoTime();
        setInspecting(false);
    }

    private static boolean isLocalBusy() {
        return System.nanoTime() < localBusyUntilNanos;
    }

    private static long chargeDelayNanos() {
        return (long) (CombatConstants.CHARGE_DELAY_SECONDS * 1.0E9F);
    }

    private static long inspectDelayNanos() {
        return (long) (CombatConstants.INSPECT_DELAY_SECONDS * 1.0E9F);
    }

    private static int localId() {
        LocalPlayer player = mc().player;
        return player != null ? player.getId() : -1;
    }

    // ===== dummy management =====

    private static Player findPlayerById(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getEntity(entityId) instanceof Player p ? p : null;
    }

    private static BodyOrientation orientationFor(int playerId) {
        return ORIENTATIONS.computeIfAbsent(playerId, id -> new BodyOrientation());
    }

    private static PlayerAnimatable ensureDummy(AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return DUMMIES.computeIfAbsent(player.getId(), id -> {
            PlayerAnimatable dummy = new PlayerAnimatable(
                    com.actioncombat.ModEntities.PLAYER_DUMMY.get(), mc.level);
            dummy.setId(nextDummyId++);
            dummy.ownerPlayerId = player.getId();
            dummy.skinTexture = player.getSkin().body().texturePath();
            dummy.bodyYaw = player.getYRot();
            dummy.yBodyRot = player.getYRot();
            dummy.yBodyRotO = player.getYRot();
            syncDummy(dummy, player, 0.0F);
            mc.level.addEntity(dummy);
            return dummy;
        });
    }

    /**
     * Copy the mirrored player's state onto its dummy.
     * <p>
     * The dummy's own yRot is what GeckoLib renders at, and it is deliberately not the
     * player's yRot: the player's yaw and orbit camera can differ, while the body
     * follows the aim. The residual angle between them becomes
     * the head/spine look overlay so the character still faces where it is aiming.
     */
    private static void syncDummy(PlayerAnimatable dummy, Player player, float deltaSeconds) {
        dummy.xo = dummy.getX();
        dummy.yo = dummy.getY();
        dummy.zo = dummy.getZ();
        dummy.setPos(player.getX(), player.getY(), player.getZ());
        dummy.ownerPlayerId = player.getId();
        // Keep the dummy in the level while drawn, but never let it occupy the camera.
        dummy.setInvisible(shouldHideDummyInFirstPerson(player.getId()));

        Minecraft mc = mc();
        boolean isLocalPlayer = mc != null && mc.player == player;
        boolean firstPerson = isLocalPlayer && mc.options.getCameraType().isFirstPerson();
        boolean lockBody = isLocalPlayer && !firstPerson && dummy.drawn && !isLocalDualWield();
        // Rapier owns its body yaw (movement heading / lock target / attack snap);
        // the orbit camera only drives the head look overlay.
        float cameraYaw = (isLocalPlayer && !firstPerson && dummy.drawn)
                ? CombatCamera.yaw()
                : player.getYRot();
        float bodyTarget = cameraYaw;
        boolean snapBody = firstPerson
                || dummy.hasFullBodyAction()
                || dummy.dodging;
        if (lockBody && player instanceof LocalPlayer local) {
            LivingEntity lock = lockTarget();
            boolean moving = local.input != null
                    && local.input.getMoveVector().lengthSquared() > 1.0E-4F;
            if (lock != null) {
                // Rapier lock-on: the body always faces the enemy, even while walking.
                bodyTarget = yawTo(player, lock);
                snapBody = true;
            } else if (dummy.hasFullBodyAction() || dummy.dodging) {
                bodyTarget = cameraYaw;
                snapBody = true;
            } else if (moving) {
                bodyTarget = movementHeading(local, cameraYaw);
                snapBody = false;
            } else {
                bodyTarget = orientationFor(player.getId()).bodyYaw(cameraYaw);
                snapBody = false;
            }
        } else if (!lockBody) {
            // Dual-wield: body follows the orbit camera so the slash matches the reticle.
            snapBody = firstPerson
                    || dummy.hasUpperAction()
                    || dummy.hasFullBodyAction()
                    || dummy.dodging;
        }
        float bodyYaw = dummy.drawn
                ? orientationFor(player.getId()).update(player, bodyTarget, snapBody, deltaSeconds)
                : bodyTarget;

        dummy.bodyYaw = bodyYaw;
        if (snapBody) {
            dummy.yRotO = bodyYaw;
            dummy.yBodyRotO = bodyYaw;
        } else {
            dummy.yRotO = dummy.getYRot();
            dummy.yBodyRotO = dummy.yBodyRot;
        }
        dummy.setYRot(bodyYaw);
        // GeckoLib's calculateYRot() reads yBodyRot/yBodyRotO for a LivingEntity, not
        // getYRot(). LivingEntity.tick() would normally keep those in step with yRot,
        // but the dummy's tick() is a deliberate no-op (it is posed entirely by client
        // code), so without this the render yaw is frozen at whatever it started at —
        // the body never turns to face its direction of travel.
        dummy.yBodyRot = bodyYaw;
        // the rig's own pitch stays neutral; the head overlay carries the aim pitch
        dummy.xRotO = dummy.getXRot();
        dummy.setXRot(0.0F);

        // Rapier: look at the locked enemy; otherwise keep the authored head, which
        // already faces along the blade. Dual-wield always looks straight ahead.
        boolean dualLook = dummy.weaponCategory == WeaponCategory.DOUBLE
                || (isLocalPlayer && isLocalDualWield());
        LivingEntity lookLock = (!dualLook && dummy.drawn && isLocalPlayer) ? lockTarget() : null;
        if (dualLook || lookLock == null) {
            dummy.lookYawOffset = 0.0F;
            dummy.lookPitchOffset = 0.0F;
        } else {
            Vec3 eyes = player.getEyePosition();
            Vec3 aim = lookLock.getEyePosition();
            double dx = aim.x - eyes.x, dy = aim.y - eyes.y, dz = aim.z - eyes.z;
            dummy.lookYawOffset = Mth.clamp(Mth.wrapDegrees(yawTo(player, lookLock) - bodyYaw),
                    -CombatConstants.LOOK_OVERLAY_MAX_YAW, CombatConstants.LOOK_OVERLAY_MAX_YAW);
            dummy.lookPitchOffset = Mth.clamp(
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))),
                    -CombatConstants.LOOK_OVERLAY_MAX_PITCH, CombatConstants.LOOK_OVERLAY_MAX_PITCH);
        }

        // mirror armour so the combat body is not naked (DummyArmorLayer draws it)
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            dummy.setItemSlot(slot, player.getItemBySlot(slot));
        }
        dummy.mainArm = player.getMainArm();

        dummy.moveSpeed = player.walkAnimation.speed();
        if (player.isSprinting() && !player.isPassenger()) {
            if (dummy.sprintStartNanos == 0) dummy.sprintStartNanos = System.nanoTime();
        } else dummy.sprintStartNanos = 0;
        dummy.spearSprintCarry = dummy.sprintStartNanos != 0 && System.nanoTime() - dummy.sprintStartNanos >= 1_000_000_000L;
        float yawRad = cameraYaw * Mth.DEG_TO_RAD;
        float fwdX = -Mth.sin(yawRad);
        float fwdZ = Mth.cos(yawRad);
        Vec3 vel = player.getDeltaMovement();
        dummy.moveForward = (float) (vel.x * fwdX + vel.z * fwdZ);
        dummy.moveStrafe = (float) (vel.x * fwdZ + vel.z * -fwdX);
        if (player instanceof LocalPlayer local) {
            net.minecraft.world.phys.Vec2 move = local.input.getMoveVector();
            float deltaYaw = (player.getYRot() - cameraYaw) * Mth.DEG_TO_RAD;
            dummy.moveStrafe = move.x * Mth.cos(deltaYaw) - move.y * Mth.sin(deltaYaw);
            dummy.moveForward = move.x * Mth.sin(deltaYaw) + move.y * Mth.cos(deltaYaw);
        }
        dummy.crouching = dummy.drawn && player == mc().player
                ? dummy.weaponCategory != WeaponCategory.SPEAR && ModKeybinds.CROUCH.isDown()
                : player.isShiftKeyDown();
        if (!dummy.hasAction()) dummy.dodging = false;
        boolean airborne = !player.onGround() && !player.isInWater();
        if (airborne && !dummy.airborne) {
            dummy.jumpStartNanos = System.nanoTime();
        }
        dummy.airborne = airborne;
        dummy.descending = player.getDeltaMovement().y < -0.08;
        dummy.skinTexture = player instanceof AbstractClientPlayer acp
                ? acp.getSkin().body().texturePath()
                : dummy.skinTexture;
        dummy.weaponCategory = WeaponCategory.of(player);

        updateLocomotionTransitions(dummy);
    }

    /** Fire the one-shot stop transitions when the gait changes. */
    private static void updateLocomotionTransitions(PlayerAnimatable dummy) {
        boolean running = dummy.drawn && dummy.moveSpeed > CombatConstants.RUN_SPEED_THRESHOLD;
        boolean walking = dummy.drawn && !running
                && dummy.moveSpeed > CombatConstants.WALK_SPEED_THRESHOLD;

        long now = System.nanoTime();
        boolean poseFree = dummy.drawn && !dummy.defending && !dummy.aiming && !dummy.airborne
                && now - dummy.lastAttackNanos > RUN_END_SUPPRESS_NANOS;

        dummy.running = running;
        dummy.walking = walking;
    }

    /** Per-tick upkeep: dummy lifecycle, input resolution. Perspective is the player's own choice. */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clearAll();
            return;
        }

        // dropping the sword leaves combat mode; picking one up does not enter it (R does)
        boolean hasSword = isHoldingSword(mc.player);
        if (localDrawn && !hasSword) {
            startSheath(mc.player, true);
        }

        // prune dummies whose players are gone
        DUMMIES.entrySet().removeIf(e -> {
            boolean keep = findPlayerById(e.getKey()) != null && (e.getKey() == mc.player.getId()
                    ? localDrawn || isSheathing()
                    : isDrawn(e.getKey()));
            if (!keep) {
                e.getValue().discard();
                ORIENTATIONS.remove(e.getKey());
            }
            return !keep;
        });

        // mirror drawn players that have no dummy yet (e.g. remote players drawn before we joined)
        if (localDrawn) {
            PlayerAnimatable dummy = ensureDummy(mc.player);
            if (dummy != null) dummy.drawn = true;
        }

        if (System.nanoTime() < spearSprintStopNanos && !mc.player.isPassenger()) mc.player.setSprinting(false);
        tickInput(mc, mc.player, System.nanoTime());
        if (localDrawn && !mc.options.getCameraType().isFirstPerson()) {
            // WASD stays camera-relative. Pitch is never copied back onto the
            // orbit camera, so attacking cannot yank the view around.
            float yaw = CombatCamera.yaw();
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.yHeadRot = yaw;
            mc.player.yHeadRotO = yaw;
        }
        if (usesLockOn() && mc.player != null) {
            tickKatanaLock(mc);
        }
        wasAimLocked = false;
        if (localDrawn && (isLocalDualWield() || localWeaponCategory() == WeaponCategory.SPEAR)) {
            var camera = mc.gameRenderer.getMainCamera();
            var eye = camera.position();
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAim(
                    camera.yRot(), camera.xRot(), eye.x, eye.y, eye.z));
        }
        CombatAttributes.setDualSwingSlow(mc.player,
                localDrawn && isLocalDualWield() && isLocalBusy());
        tickDodgeBoost(mc.player);
        tickThrustLunge(mc.player);
        if (spearAdvanceTicks > 0 && System.nanoTime() >= spearAdvanceAtNanos) {
            if (localDrawn && localWeaponCategory() == WeaponCategory.SPEAR && !mc.player.isPassenger())
                mc.player.move(MoverType.SELF, spearAdvanceStep);
            spearAdvanceTicks--;
        }
        if (localDodgeTicks > 0) {
            mc.player.move(MoverType.SELF, localDodgeStep);
            localDodgeTicks--;
            if (localDodgeTicks <= 0) {
                PlayerAnimatable dummy = DUMMIES.get(mc.player.getId());
                if (dummy != null) dummy.dodging = false;
            }
        }
    }

    /**
     * Dual-wield dash upkeep. The boost is re-asserted every tick because an
     * attribute sync from the server replaces the local modifier list, and dropped
     * as soon as its 0.3s window ends or combat mode ends.
     */
    private static void tickDodgeBoost(LocalPlayer player) {
        if (dodgeBoostUntilNanos == 0L) return;
        if (localDrawn && System.nanoTime() < dodgeBoostUntilNanos) {
            CombatAttributes.setDualDashBoost(player, true);
            return;
        }
        cancelDodgeBoost(player);
    }

    /** Drop the dual-wield dash boost immediately (sheath, draw, disconnect). */
    private static void cancelDodgeBoost(LocalPlayer player) {
        dodgeBoostUntilNanos = 0L;
        CombatAttributes.setDualDashBoost(player, false);
    }

    /**
     * Per-frame camera work: pick the rig for the current combat state and let the
     * director ease toward it. Called from ComputeFov (fires every frame, one tick ahead
     * of Camera.setup — imperceptible). Also maintains the offsets CameraMixin reads.
     *
     * @return the eased camera distance, or -1 while vanilla owns the camera
     */
    public static float updateCameraFrame(float deltaSeconds) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return -1.0F;

        CameraDirector.State state = cameraState(mc);
        CameraDirector.State previous = lastCameraState;
        lastCameraState = state;
        if (previous == CameraDirector.State.SHEATHED || state == CameraDirector.State.SHEATHED) {
            DIRECTOR.snapTo(state);
        } else {
            DIRECTOR.update(state, deltaSeconds);
        }
        currentShoulderOffset = 0.0F;
        currentHeightOffset = DIRECTOR.heightOffset();
        currentFovBias = DIRECTOR.fovBias();

        var attr = mc.player.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.CAMERA_DISTANCE);

        if (state == CameraDirector.State.SHEATHED) {
            // hand the camera back to vanilla, including the distance we borrowed
            if (attr != null && attr.getBaseValue() != CombatConstants.THIRD_PERSON_DISTANCE) {
                attr.setBaseValue(CombatConstants.THIRD_PERSON_DISTANCE);
            }
            currentShoulderOffset = 0.0F;
            currentHeightOffset = 0.0F;
            currentFovBias = 0.0F;
            return -1.0F;
        }

        float dist = DIRECTOR.distance();
        if (attr != null) {
            attr.setBaseValue(dist);
        }
        return dist;
    }

    /** Which camera rig the current state calls for. */
    private static CameraDirector.State cameraState(Minecraft mc) {
        if (!localDrawn) return CameraDirector.State.SHEATHED;
        if (isLocalAttackActive()) return CameraDirector.State.ATTACKING;
        if (localDefending) return CameraDirector.State.GUARDING;
        if (localInspecting) return CameraDirector.State.INSPECTING;
        if (mc.player != null && mc.player.isSprinting()) return CameraDirector.State.SPRINTING;
        return CameraDirector.State.FREE;
    }

    public static float currentHeightOffset() {
        return currentHeightOffset;
    }

    public static float currentFovBias() {
        return currentFovBias;
    }

    /** Per-frame dummy sync so the mirrored body follows the player smoothly. */
    public static void renderTickSync() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L
                ? 1.0F / 60.0F
                : Math.min((now - lastFrameNanos) / 1.0E9F, 0.1F);
        lastFrameNanos = now;

        // Movement keeps its own clock; impact feedback must not freeze the legs
        // or delay the fixed attack cadence.
        double advanceTicks = deltaSeconds * 20.0;

        for (Map.Entry<Integer, PlayerAnimatable> e : DUMMIES.entrySet()) {
            Player target = e.getKey() == mc.player.getId() ? mc.player : findPlayerById(e.getKey());
            if (target == null) continue;
            PlayerAnimatable dummy = e.getValue();
            dummy.animationTick += advanceTicks;
            syncDummy(dummy, target, deltaSeconds);
        }
    }

    /**
     * On draw, Q/E become lock-switch, R sheath, X block, C crouch. Vanilla drop /
     * inventory are unbound only if they currently occupy Q or E. Restored on sheath.
     */
    private static InputConstants.Key savedDropKey;
    private static InputConstants.Key savedInventoryKey;
    private static boolean drawnKeysApplied;

    private static void applyDrawnKeybinds(boolean drawn, boolean stealLockKeys) {
        Minecraft mc = Minecraft.getInstance();
        if (drawn) {
            if (drawnKeysApplied) return;
            savedDropKey = mc.options.keyDrop.getKey();
            savedInventoryKey = mc.options.keyInventory.getKey();
            InputConstants.Key q = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Q);
            InputConstants.Key e = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_E);
            InputConstants.Key r = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R);
            InputConstants.Key x = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_X);
            InputConstants.Key c = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_C);
            if (stealLockKeys) {
                stealIf(mc.options.keyDrop, q, e);
                stealIf(mc.options.keyInventory, q, e);
                ModKeybinds.LOCK_PREV.setKey(q);
                ModKeybinds.LOCK_NEXT.setKey(e);
            }
            ModKeybinds.DRAW.setKey(r);
            ModKeybinds.BLOCK.setKey(x);
            ModKeybinds.CROUCH.setKey(c);
            KeyMapping.resetMapping();
            drawnKeysApplied = true;
            return;
        }
        if (!drawnKeysApplied) return;
        if (savedDropKey != null) mc.options.keyDrop.setKey(savedDropKey);
        if (savedInventoryKey != null) mc.options.keyInventory.setKey(savedInventoryKey);
        KeyMapping.resetMapping();
        drawnKeysApplied = false;
        savedDropKey = savedInventoryKey = null;
    }

    private static void stealIf(KeyMapping mapping, InputConstants.Key... keys) {
        InputConstants.Key current = mapping.getKey();
        for (InputConstants.Key key : keys) {
            if (current.equals(key)) {
                mapping.setKey(InputConstants.UNKNOWN);
                return;
            }
        }
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void clearAll() {
        thrustReadyNanos = thrustRecoveryNanos = 0L;
        rapierLeftDown = rapierRightDown = false;
        rapierChargeStartNanos = 0L;
        localChargeReady = false;
        localThrustCharged = false;
        localRapierFastUntilNanos = 0L;
        localLeftStreak = 0;
        localRightStreak = 0;
        ATTACK_ACKNOWLEDGEMENTS.clear();
        LOCAL_CADENCE.reset();
        localAttackIntervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;
        DUMMIES.values().forEach(PlayerAnimatable::discard);
        DUMMIES.clear();
        DRAWN_BY_ID.clear();
        KATANA_BY_ID.clear();
        ORIENTATIONS.clear();
        localDrawn = false;
        localKatana = false;
        katanaLockId = -1;
        localRapierSlashIndex = 0;
        katanaSpeedTicks = CombatConstants.KATANA_ATTACK_INTERVAL_TICKS;
        katanaHasteUntilGameTime = Long.MIN_VALUE;
        katanaReadyAtTick = Long.MIN_VALUE;
        currentShoulderOffset = 0.0F;
        currentHeightOffset = 0.0F;
        currentFovBias = 0.0F;
        DIRECTOR.reset();
        lmbCaptured = false;
        rmbCaptured = false;
        localDefending = false;
        localAiming = false;
        localInspecting = false;
        localBusyUntilNanos = 0L;
        localAttackSide = null;
        localAttackDepth = 0;
        fpDrawBusyUntilNanos = 0L;
        sheathUntilNanos = 0L;
        lastFrameNanos = 0L;
        localAttackStartNanos = 0L;
        localAttackSeconds = 0.0F;
        INPUT_BUFFER.clear();
        HitFeedback.reset();
        CombatCamera.reset();
        wasAimLocked = false;
        lastCameraState = CameraDirector.State.SHEATHED;
        cameraBeforeDraw = null;
        shiftWasDown = false;
        localDodgeTicks = 0;
        localDodgeStep = Vec3.ZERO;
        spearAdvanceTicks = 0;
        lungeForwardAtNanos = 0L;
        lungeForwardTicks = 0;
        lungeDir = Vec3.ZERO;
        rapierComboTargetId = -1;
        rapierComboHits = 0;
        rapierComboUpdatedNanos = 0L;
        rapierSilenceUntilNanos = 0L;
        CombatAttributes.setDualSwingSlow(mc().player, false);
        cancelDodgeBoost(mc().player);
        applyDrawnKeybinds(false, false);
    }

    private ClientCombat() {}
}

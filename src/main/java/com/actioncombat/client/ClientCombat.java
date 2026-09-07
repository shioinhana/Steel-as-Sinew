package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import com.actioncombat.combat.AttackCadence;
import com.actioncombat.ModPayloads;
import com.actioncombat.combat.CombatMoves;
import com.actioncombat.combat.FpWeaponClips;
import com.actioncombat.combat.HandRest;
import com.actioncombat.combat.SwingSide;
import com.actioncombat.combat.WeaponCategory;
import com.actioncombat.entity.PlayerAnimatable;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.ItemTags;
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
    private static int localAttackIntervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;
    /** Duration of the local swing, for the cancel window. */
    private static float localAttackSeconds;
    private static boolean shiftWasDown;
    private static long thrustReadyNanos;
    private static long thrustRecoveryNanos;
    private static boolean rapierLeftDown, rapierRightDown;
    private static int localDodgeTicks;
    private static Vec3 localDodgeStep = Vec3.ZERO;
    private static boolean wasAimLocked;

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
        return WeaponCategory.of(isLocalDualWield());
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
     * During a swing only: mouse turns body and camera together (Genshin lock).
     * Guard / charge / dodge stay on the free orbit camera so you can walk-parry.
     */
    public static boolean isAimLocked() {
        if (!localDrawn) return false;
        return isLocalAttackActive();
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
        return player.getMainHandItem().is(ItemTags.SWORDS);
    }

    // ===== draw / sheathe =====

    /** R: enter or leave mod combat mode. Requires a sword to enter. */
    public static void onDrawKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (localDrawn) {
            startSheath(mc.player, true);
        } else if (isHoldingSword(mc.player)) {
            startDraw(mc.player, true);
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
        localDrawn = true;
        DRAWN_BY_ID.put(player.getId(), true);
        localAttackSide = null;
        localAttackDepth = 0;
        localDualAttack = false;
        localLeftHand = HandRest.CENTER;
        localRightHand = HandRest.CENTER;
        localParryReady = false;
        FpViewmodel.INSTANCE.clearAction();
        sheathUntilNanos = 0L;
        boolean dual = player.getMainHandItem().is(ItemTags.SWORDS)
                && player.getOffhandItem().is(ItemTags.SWORDS);
        fpDrawBusyUntilNanos = System.nanoTime()
                + (long) (FpWeaponClips.of(WeaponCategory.of(dual)).drawSeconds() * 1.0E9F);

        orientationFor(player.getId()).reset(player.getYRot());
        CombatCamera.seedFrom(player);
        DIRECTOR.snapTo(CameraDirector.State.FREE);
        lastCameraState = CameraDirector.State.FREE;

        PlayerAnimatable dummy = ensureDummy(player);
        if (dummy != null) {
            dummy.drawn = true;
            dummy.weaponCategory = WeaponCategory.of(dual);
            dummy.playAction(PlayerAnimatable.ANIM_DRAW,
                dummy.weaponCategory == WeaponCategory.DOUBLE ? 0.8F : 0.7333F);
        }
        markAction();
        shiftWasDown = true;
        localDodgeTicks = 0;
        if (sendToServer) {
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDrawn(true));
        }
    }

    private static void startSheath(LocalPlayer player, boolean sendToServer) {
        localDrawn = false;
        DRAWN_BY_ID.put(player.getId(), false);
        CombatCamera.reset();
        wasAimLocked = false;
        lastCameraState = CameraDirector.State.SHEATHED;
        cameraBeforeDraw = null;

        boolean dual = player.getMainHandItem().is(ItemTags.SWORDS)
                && player.getOffhandItem().is(ItemTags.SWORDS);
        FpWeaponClips.Clips fp = FpWeaponClips.of(WeaponCategory.of(dual));
        float sheathSeconds = fp.drawSeconds();
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
                    dummy.weaponCategory == WeaponCategory.DOUBLE ? 0.8F : 0.7333F);
        }
        if (sendToServer) {
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundSetDrawn(false));
        }
    }

    public static void onDrawState(int playerId, boolean drawn) {
        DRAWN_BY_ID.put(playerId, drawn);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == playerId) {
            boolean changed = localDrawn != drawn;
            localDrawn = drawn;
            if (!changed) return;
            // server-initiated state change (e.g. after respawn); mirror it locally
            if (drawn) {
                startDraw(mc.player, false);
            } else {
                startSheath(mc.player, false);
            }
        } else {
            Player target = findPlayerById(playerId);
            if (target instanceof AbstractClientPlayer acp) {
                PlayerAnimatable dummy = ensureDummy(acp);
                if (dummy != null) {
                    dummy.drawn = drawn;
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
        FovPunch.trigger();
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
     * 0..1 progress through the local player's current swing, or -1 when not swinging.
     * Drives the blade-trail VFX on the viewmodel.
     */
    public static float localSwingProgress() {
        return isLocalAttackActive() ? localProgress() : -1.0F;
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
        intervalTicks = Mth.clamp(intervalTicks, CombatConstants.ATTACK_INTERVAL_TICKS,
                CombatConstants.ATTACK_INTERVAL_MAX_TICKS);
        float seconds = intervalTicks / 20.0F + CombatConstants.ATTACK_CANCEL_TAIL_SECONDS;

        LocalPlayer local = mc().player;
        boolean isLocal = local != null && local.getId() == playerId;
        if (isLocal) {
            var prediction = ATTACK_ACKNOWLEDGEMENTS.confirmPrediction(side, depth, dual);
            if (prediction != null) {
                if (prediction.startedAtNanos() == localAttackStartNanos) {
                    LOCAL_CADENCE.synchronize(prediction.startedAtTick(), intervalTicks);
                    localAttackIntervalTicks = intervalTicks;
                    if (prediction.intervalTicks() != intervalTicks && isLocalBusy()
                            && dummy.hasUpperAction() && dummy.actionClip.startsWith("attack")) {
                        localAttackSeconds = seconds;
                        localBusyUntilNanos = localAttackStartNanos + (long) (seconds * 1.0E9F);
                        fpAttackUntilNanos = localBusyUntilNanos;
                        dummy.retimeAttack(intervalTicks);
                    }
                }
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
        boolean crouch = ModKeybinds.CROUCH.isDown();
        input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(),
                keys.jump(), crouch, keys.sprint());
    }

    private static void tryStartDodge(Input keys) {
        LocalPlayer player = mc().player;
        if (player == null || mc().screen != null) return;
        if (localDodgeTicks > 0 || !player.onGround()) return;
        if (!canActNow()) return;
        INPUT_BUFFER.clear();
        int dirs = CombatMoves.packDirs(keys);
        float yaw = facingYaw(player);
        Vec3 total = CombatMoves.dodgeOffset(yaw, dirs);
        if (localWeaponCategory() == WeaponCategory.SINGLE)
            total = total.scale(CombatConstants.RAPIER_DODGE_DISTANCE / CombatConstants.DODGE_DISTANCE);
        int ticks = Math.max(1, CombatConstants.DODGE_TICKS);
        localDodgeStep = total.scale(1.0 / ticks);
        localDodgeTicks = ticks;
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
        if (!canActNow() || mc().screen != null || (thrust && System.nanoTime() < thrustReadyNanos)) return;
        INPUT_BUFFER.clear();
        lmbCaptured = rmbCaptured = false;
        stopDefending(true);
        var camera = mc().gameRenderer.getMainCamera();
        var origin = camera.position();
        ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAim(camera.yRot(), camera.xRot(), origin.x, origin.y, origin.z));
        if (thrust) {
            thrustReadyNanos = System.nanoTime() + 2_000_000_000L;
            // Prediction shows contact immediately; the server decides hit/miss recovery.
            thrustRecoveryNanos = System.nanoTime() + 1_000_000_000L;
            playThrustVisual(mc().player.getId(), false);
        }
        ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAttack((thrust ? SwingSide.RIGHT : SwingSide.LEFT).id(), false, false));
        markAction();
    }

    private static void playThrustVisual(int playerId, boolean hit) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        String clip = hit ? "rapier_thrust_hit" : "rapier_thrust_miss";
        float seconds = hit ? 0.12F : 1.0F;
        if (dummy != null) {
            dummy.defending = false;
            dummy.aiming = false;
            dummy.playUpper(clip + "_upper", seconds);
        }
        if (mc().player != null && mc().player.getId() == playerId) {
            FpViewmodel.INSTANCE.playAction(clip, seconds);
            localAttackSide = null;
            fpAttackUntilNanos = System.nanoTime() + (long)(seconds * 1.0E9);
        }
    }

    public static void onThrust(int playerId, boolean accepted, boolean hit, int cooldownTicks, int recoveryTicks) {
        boolean self = mc().player != null && mc().player.getId() == playerId;
        if (self) {
            thrustReadyNanos = System.nanoTime() + Math.max(0, cooldownTicks) * 50_000_000L;
            thrustRecoveryNanos = System.nanoTime() + Math.max(0, recoveryTicks) * 50_000_000L;
        }
        if (accepted) {
            playThrustVisual(playerId, hit);
            if (self && hit) onHitConfirm(true);
        }
    }

    public static void onGuardBash(int playerId) {
        PlayerAnimatable dummy = DUMMIES.get(playerId);
        if (dummy != null) dummy.playUpper("guard_bash_upper", 0.25F);
        if (mc().player != null && mc().player.getId() == playerId)
            FpViewmodel.INSTANCE.playAction("guard_bash", 0.25F);
    }

    private static boolean tryDualSlash() {
        if (!parryReadyNow() || !lmbCaptured || !rmbCaptured) return false;
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
                localLeftHand = HandRest.CENTER;
                localRightHand = HandRest.CENTER;
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
            if (localDefendHold) stopDefending(false);
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
        onBlockKeyState(!blocked && ModKeybinds.BLOCK.isDown());
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
                        new ModPayloads.ServerboundAttack(side.id(), charged, dual));
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
        } else {
            if (charged) {
                localLeftHand = HandRest.CENTER;
                localRightHand = HandRest.CENTER;
            }
            hand = HandRest.pickHand(side, localLeftHand, localRightHand);
            if (hand < 0) return false;
            if (hand == 0) localLeftHand = HandRest.of(side);
            else localRightHand = HandRest.of(side);
            anim = cat.attackOverlay(side, hand);
            if (anim == null) return false;
        }

        long startedAtTick = mc().level.getGameTime();
        int intervalTicks = LOCAL_CADENCE.begin(startedAtTick);
        float seconds = intervalTicks / 20.0F + CombatConstants.ATTACK_CANCEL_TAIL_SECONDS;

        dummy.aiming = false;
        dummy.inspecting = false;
        dummy.defending = false;
        dummy.lastAttackNanos = System.nanoTime();
        dummy.playUpper(anim, seconds, intervalTicks);

        localAttackSide = side;
        localAttackDepth = hand;
        localDualAttack = dual;
        localAiming = false;
        setInspecting(false);
        stopDefending(false);
        localAttackSeconds = seconds;
        localAttackIntervalTicks = intervalTicks;
        localAttackStartNanos = System.nanoTime();
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

    private static void expireLocalHands() {
        if (isLocalBusy()) return;
        if (localAttackStartNanos == 0L) return;
        long idle = System.nanoTime() - localBusyUntilNanos;
        if (idle > (long) (CombatConstants.COMBO_RESET_SECONDS * 1.0E9F)) {
            localLeftHand = HandRest.CENTER;
            localRightHand = HandRest.CENTER;
        }
    }

    /** A swing keeps the recovery gate latched when it began. */
    private static boolean canActNow() {
        return canActNow(false);
    }

    private static boolean canActNow(boolean charged) {
        return System.nanoTime() >= thrustRecoveryNanos
                && localDodgeTicks <= 0 && mc().level != null
                && LOCAL_CADENCE.canStart(mc().level.getGameTime())
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
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAttack(side.id(), charged, dual));
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
        float cameraYaw = (isLocalPlayer && !firstPerson && dummy.drawn)
                ? CombatCamera.yaw()
                : player.getYRot();
        // Swing / draw / dodge snap to aim. Other states ease toward aim, with
        // directional gait clips moving the legs independently of the upper body.
        boolean lockToCamera = firstPerson
                || dummy.hasUpperAction()
                || dummy.hasFullBodyAction()
                || dummy.dodging;
        float bodyYaw = dummy.drawn
                ? orientationFor(player.getId()).update(player, cameraYaw, lockToCamera, deltaSeconds)
                : cameraYaw;

        dummy.bodyYaw = bodyYaw;
        if (lockToCamera) {
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

        if (YsmStyle.enabled()) {
            dummy.lookYawOffset = Mth.clamp(Mth.wrapDegrees(cameraYaw - bodyYaw),
                    -CombatConstants.LOOK_OVERLAY_MAX_YAW, CombatConstants.LOOK_OVERLAY_MAX_YAW);
            dummy.lookPitchOffset = Mth.clamp(player.getXRot(),
                    -CombatConstants.LOOK_OVERLAY_MAX_PITCH, CombatConstants.LOOK_OVERLAY_MAX_PITCH);
        } else {
            dummy.lookYawOffset = 0.0F;
            dummy.lookPitchOffset = 0.0F;
        }

        // mirror armour so the combat body is not naked (DummyArmorLayer draws it)
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            dummy.setItemSlot(slot, player.getItemBySlot(slot));
        }
        dummy.mainArm = player.getMainArm();

        dummy.moveSpeed = player.walkAnimation.speed();
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
                ? ModKeybinds.CROUCH.isDown()
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
        dummy.weaponCategory = WeaponCategory.of(
                player.getMainHandItem().is(ItemTags.SWORDS)
                        && player.getOffhandItem().is(ItemTags.SWORDS));

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
        if (localDrawn && !isHoldingSword(mc.player)) {
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

        tickInput(mc, mc.player, System.nanoTime());
        boolean locked = isAimLocked();
        if (localDrawn && !mc.options.getCameraType().isFirstPerson()) {
            float yaw = CombatCamera.yaw();
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.yHeadRot = yaw;
            mc.player.yHeadRotO = yaw;
            if (locked) {
                mc.player.setXRot(CombatCamera.pitch());
                mc.player.xRotO = CombatCamera.pitch();
            }
        }
        wasAimLocked = locked;
        if (localDrawn) {
            float aimYaw = facingYaw(mc.player);
            float aimPitch = mc.options.getCameraType().isFirstPerson()
                    ? mc.player.getXRot() : CombatCamera.pitch();
            var camera = mc.gameRenderer.getMainCamera();
            var eye = camera.position();
            ClientPacketDistributor.sendToServer(new ModPayloads.ServerboundAim(
                    camera.yRot(), camera.xRot(), eye.x, eye.y, eye.z));
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

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void clearAll() {
        thrustReadyNanos = thrustRecoveryNanos = 0L;
        rapierLeftDown = rapierRightDown = false;
        ATTACK_ACKNOWLEDGEMENTS.clear();
        LOCAL_CADENCE.reset();
        localAttackIntervalTicks = CombatConstants.ATTACK_INTERVAL_TICKS;
        DUMMIES.values().forEach(PlayerAnimatable::discard);
        DUMMIES.clear();
        DRAWN_BY_ID.clear();
        ORIENTATIONS.clear();
        localDrawn = false;
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
    }

    private ClientCombat() {}
}

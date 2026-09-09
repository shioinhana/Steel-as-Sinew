package com.actioncombat.entity;

import com.actioncombat.ActionCombat;
import com.actioncombat.CombatConstants;
import com.actioncombat.combat.AttackTimings;
import com.actioncombat.combat.CombatAnimationController;
import com.actioncombat.combat.SwingSide;
import com.actioncombat.combat.WeaponCategory;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import software.bernie.geckolib.util.GeckoLibUtil;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.object.PlayState;

/**
 * Client-side stand-in for the vanilla player model while in drawn-sword mode.
 * Kept free of client-only imports so the entity type can register on both sides.
 * State fields are written by client code every frame; the animation names come
 * from the animatable's WeaponCategory.
 * <p>
 * Two controllers run in parallel so locomotion and actions no longer fight over a
 * single pose:
 * <ul>
 *   <li>{@code locomotion} always drives the stance underneath (idle / walk / run /
 *       crouch / jump / fall).</li>
 *   <li>{@code action} stays silent until a swing, guard or flinch needs the
 *       upper body. Those clips omit leg bones, so they override torso/arms
 *       while the locomotion layer keeps walking underneath.</li>
 * </ul>
 * The head/spine look overlay is not an animation at all; it is applied per-frame in
 * {@link com.actioncombat.client.PlayerGeoRenderer} so it composes with both layers.
 */
public class PlayerAnimatable extends LivingEntity implements GeoEntity {
    public static final Identifier DEFAULT_SKIN =
            Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public static final String CONTROLLER_LOCOMOTION = "locomotion";
    public static final String CONTROLLER_ACTION = "action";

    // Triggerable clips whose names are identical in both animation files.
    public static final String ANIM_DRAW = "draw";
    public static final String ANIM_PUT_AWAY = "put_away";
    public static final String ANIM_RUN_END = "run_end";
    public static final String ANIM_WALK_END = "walk_end";
    public static final String ANIM_HURT = "hurt";
    public static final String ANIM_DEFEND_END = "defend_end";
    public static final String ANIM_JUMP_START = "jump_start";

    /** Skin texture of the mirrored player, set by client code before rendering. */
    public Identifier skinTexture = DEFAULT_SKIN;
    /** Entity id of the player this dummy mirrors. Not {@link #getId()}, which is a client-only dummy id. */
    public int ownerPlayerId = -1;
    public WeaponCategory weaponCategory = WeaponCategory.SINGLE;
    public boolean drawn;
    public boolean defending;
    public boolean aiming;
    public boolean inspecting;
    /** Tracks the run-hold state so the run_end transition fires exactly once. */
    public long sprintStartNanos;
    public boolean spearSprintCarry;
    public boolean running;
    /** Tracks the walk-hold state so the walk_end transition fires exactly once. */
    public boolean walking;
    public boolean crouching;
    public boolean dodging;
    /** Action-layer recover after a held guard, so the overlay does not freeze. */
    public long guardRecoverUntilNanos;
    /** Airborne state, split so jump reads as a launch and fall as a descent. */
    public boolean airborne;
    public boolean descending;
    /** Walk speed of the mirrored player, used to pick idle/walk/run animations. */
    public float moveSpeed;
    /** Body-relative forward (+W) and left-strafe (+A) of the mirrored player. */
    public float moveForward;
    public float moveStrafe;
    /** Nanos when this dummy last left the ground, for the two-beat jump. */
    public long jumpStartNanos;
    /** Set when an attack clip starts; suppresses run_end right after swings. */
    public long lastAttackNanos;
    /**
     * One-shot clip the action layer should play (draw / swing / hurt / sheath).
     * Driven from here rather than {@code triggerAnim}: GeckoLib re-invokes the
     * state handler every frame, and {@code isPlayingTriggeredAnimation()} is false
     * until the clip has actually posed a bone — so a handler that returns STOP
     * on that first frame silently eats the trigger.
     */
    public String actionClip;
    public long actionUntilNanos;
    /** True when {@link #actionClip} owns the whole body (draw/dodge/hurt). */
    public boolean actionFullBody;
    /** Body yaw chosen by BodyOrientation; the renderer uses it instead of the player's. */
    public float bodyYaw;
    /** Degrees the head/spine overlay should yaw and pitch to face the camera. */
    public float lookYawOffset;
    public float lookPitchOffset;
    /**
     * Locomotion clock advanced by client frames rather than this unticked entity.
     * Attack phases use elapsed real time so feedback never changes their cadence.
     */
    public double animationTick;
    /** Shared start for FP/3P non-attack actions on the locomotion clock. */
    public double actionStartAnimationTick;
    private long actionStartNanos;
    private AttackTimings.Timing actionTiming;
    private AttackTimings.Timing firstPersonActionTiming;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Which arm the mirrored player leads with, so armour and items sit correctly. */
    public HumanoidArm mainArm = HumanoidArm.RIGHT;

    public PlayerAnimatable(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
    }

    /**
     * A LivingEntity needs an attribute supplier, so the dummy declares a minimal one.
     * It is a render stand-in and is never spawned server-side, but the base class reads
     * attributes during construction.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    @Override
    public HumanoidArm getMainArm() {
        return mainArm;
    }

    /**
     * The dummy is posed entirely by client code every frame, so the LivingEntity tick
     * (movement, effects, damage timers, falling) has nothing to contribute and would
     * only risk side effects on an entity that is not really in the world. Position and
     * rotation are written directly by ClientCombat, so this stays empty.
     */
    @Override
    public void tick() {
    }

    /** Never a valid target: it exists only to be drawn. */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    /** Keeps the entity out of collision and push resolution for nearby entities. */
    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.Entity entity) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Full-body one-shot on the locomotion layer (draw, sheath, dodge, hurt). */
    public void playAction(String clip, float seconds) {
        playAction(clip, seconds, true);
    }

    /** Upper-body one-shot on the action overlay (swings). Legs keep walking. */
    public void playUpper(String clip, float seconds) {
        playAction(clip, seconds, false);
    }

    /** Attack playback uses the interval chosen when this swing was accepted. */
    public void playUpper(String clip, float seconds, int intervalTicks) {
        playAction(clip, seconds, false, intervalTicks);
    }

    public void playAction(String clip, float seconds, boolean fullBody) {
        playAction(clip, seconds, fullBody, CombatConstants.ATTACK_INTERVAL_TICKS);
    }

    private void playAction(String clip, float seconds, boolean fullBody, int intervalTicks) {
        if (clip == null || clip.isEmpty()) return;
        long started = System.nanoTime();
        boolean attack = clip.startsWith("attack");
        this.actionTiming = attack ? AttackTimings.of(this.weaponCategory, clip).withInterval(intervalTicks) : null;
        this.firstPersonActionTiming = attack
                ? AttackTimings.firstPersonOf(this.weaponCategory, clip).withInterval(intervalTicks) : null;
        this.actionClip = clip;
        float playback = attack
                ? AttackTimings.playbackSeconds(this.weaponCategory, this.actionTiming.length())
                : Math.max(seconds, 0.05F);
        this.actionUntilNanos = started + Math.round((double) playback * 1.0E9);
        this.actionFullBody = fullBody || (weaponCategory == WeaponCategory.SPEAR && attack);
        this.actionStartAnimationTick = this.animationTick;
        this.actionStartNanos = started;
    }

    /** Server acknowledgement can correct the interval without replaying the opening. */
    public void retimeAttack(int intervalTicks) {
        if (actionClip == null || !actionClip.startsWith("attack") || actionTiming == null) return;
        actionTiming = actionTiming.withInterval(intervalTicks);
        firstPersonActionTiming = firstPersonActionTiming.withInterval(intervalTicks);
        actionUntilNanos = actionStartNanos + Math.round((double)
                AttackTimings.playbackSeconds(this.weaponCategory, actionTiming.length()) * 1.0E9);
    }

    public double actionElapsedSeconds() {
        if (actionClip != null && (actionClip.startsWith("attack") || actionClip.startsWith("rapier_thrust"))) {
            double elapsed = Math.max(0.0, (System.nanoTime() - actionStartNanos) / 1.0E9);
            if (actionClip.startsWith("rapier_thrust")) {
                double playback = Math.max(0.05, (actionUntilNanos - actionStartNanos) / 1.0E9);
                return elapsed * CombatConstants.RAPIER_THRUST_SECONDS / playback;
            }
            return elapsed;
        }
        if (weaponCategory == WeaponCategory.SPEAR && actionClip != null)
            return Math.max(0.0, (System.nanoTime() - actionStartNanos) / 1.0E9);
        return Math.max(0.0, (animationTick - actionStartAnimationTick) / 20.0);
    }

    public double actionAnimationSeconds() {
        double elapsed = actionElapsedSeconds();
        return actionClip != null && actionTiming != null ? actionTiming.animationSeconds(elapsed) : elapsed;
    }

    public double firstPersonActionAnimationSeconds() {
        double elapsed = actionElapsedSeconds();
        return actionClip != null && firstPersonActionTiming != null
                ? firstPersonActionTiming.animationSeconds(elapsed) : elapsed;
    }

    public boolean hasAction() {
        if (actionClip == null) return false;
        if (System.nanoTime() >= actionUntilNanos) {
            actionClip = null;
            actionFullBody = false;
            actionTiming = null;
            firstPersonActionTiming = null;
            return false;
        }
        return true;
    }

    public boolean hasFullBodyAction() {
        return hasAction() && actionFullBody;
    }

    public boolean hasUpperAction() {
        return hasAction() && !actionFullBody;
    }

    public void clearAction() {
        actionClip = null;
        actionUntilNanos = 0L;
        actionFullBody = false;
        actionTiming = null;
        firstPersonActionTiming = null;
    }

    public void beginGuardRecover() {
        if (defending || guardRecovering()) {
            guardRecoverUntilNanos = System.nanoTime() + 220_000_000L;
        }
        defending = false;
    }

    public boolean guardRecovering() {
        return System.nanoTime() < guardRecoverUntilNanos;
    }

    /** Trigger a one-shot on the action overlay. */
    public void triggerAnim(String animName) {
        triggerAnim(CONTROLLER_ACTION, animName);
    }

    /** Trigger a one-shot on the locomotion layer (walk_end / run_end transitions). */
    public void triggerLocomotion(String animName) {
        triggerAnim(CONTROLLER_LOCOMOTION, animName);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(locomotionController(), actionController());
    }

    /** Shared by the third-person body and its first-person arm rig. */
    public String locomotionClip(long nowNanos) {
        WeaponCategory cat = weaponCategory;
        if (airborne) {
            boolean launching = !descending && nowNanos - jumpStartNanos < 280_000_000L;
            return descending ? cat.fall : (launching ? ANIM_JUMP_START : cat.jump);
        }
        if (cat == WeaponCategory.SPEAR && !airborne) {
            if (spearSprintCarry) return "run_carry";
            if (moveSpeed > CombatConstants.WALK_SPEED_THRESHOLD) return cat.gaitClip(false, false, moveForward, moveStrafe);
            return cat.idle;
        }
        if (moveSpeed > CombatConstants.WALK_SPEED_THRESHOLD) {
            return cat.gaitClip(moveSpeed > CombatConstants.RUN_SPEED_THRESHOLD,
                    crouching, moveForward, moveStrafe);
        }
        if (crouching) return cat.crouchIdle;
        return drawn && inspecting ? cat.inspect : cat.idle;
    }

    /**
     * Lower-body + stance layer. State machine: motion type first (air / crouch /
     * run / walk / idle), then play that clip. Swings never take this layer —
     * they overlay on {@link #actionController()}. Draw and dodge still own the
     * whole body here.
     */
    private AnimationController<PlayerAnimatable> locomotionController() {
        return new CombatAnimationController<PlayerAnimatable>(CONTROLLER_LOCOMOTION, 6, test -> {
            PlayerAnimatable self = test.animatable();

            if (self.hasFullBodyAction()) {
                test.controller().setTransitionTicks(0);
                return test.setAndContinue(RawAnimation.begin().thenPlay(self.actionClip));
            }
            if (self.weaponCategory == WeaponCategory.SPEAR && self.defending) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold("defend"));
            }
            test.controller().setTransitionTicks(self.airborne ? 2 : 4);
            String clip = self.locomotionClip(System.nanoTime());
            return test.setAndContinue(ANIM_JUMP_START.equals(clip)
                    ? RawAnimation.begin().thenPlay(clip)
                    : RawAnimation.begin().thenLoop(clip));
        }, self -> self.hasFullBodyAction() ? self.actionElapsedSeconds() : -1.0);
    }

    /**
     * Upper-body overlay: swings, guard, charge. Clips must omit leg bones so the
     * locomotion layer keeps walking underneath.
     */
    private AnimationController<PlayerAnimatable> actionController() {
        return new CombatAnimationController<PlayerAnimatable>(CONTROLLER_ACTION, 5, test -> {
            PlayerAnimatable self = test.animatable();
            WeaponCategory cat = self.weaponCategory;
            if (self.hasFullBodyAction()) {
                return PlayState.STOP;
            }
            if (self.hasUpperAction()) {
                test.controller().setTransitionTicks(0);
                return test.setAndContinue(RawAnimation.begin().thenPlay(self.actionClip));
            }
            if (self.defending && cat == WeaponCategory.SPEAR) return PlayState.STOP;
            if (self.defending) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(cat.defend));
            }
            if (self.guardRecovering()) {
                return test.setAndContinue(RawAnimation.begin().thenPlay(ANIM_DEFEND_END));
            }
            if (self.aiming) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(cat.aiming));
            }
            return PlayState.STOP;
        }, self -> self.hasUpperAction() ? self.actionAnimationSeconds() : -1.0);
    }

    /** Clip name for a swing, resolved through this dummy's weapon category. */
    public String attackClip(SwingSide side, int depth) {
        return weaponCategory.attackAnim(side, depth);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /** Armour and held items copied from the mirrored player, read by the render layers. */
    private final java.util.EnumMap<EquipmentSlot, ItemStack> equipment =
            new java.util.EnumMap<>(EquipmentSlot.class);

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return equipment.getOrDefault(slot, ItemStack.EMPTY);
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        equipment.put(slot, stack);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}

    static {
        ActionCombat.LOGGER.debug("PlayerAnimatable loaded");
    }
}

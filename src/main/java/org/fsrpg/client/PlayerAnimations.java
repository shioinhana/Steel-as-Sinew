package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.fsrpg.network.RpgAnimationPayload;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-actor FSVOXEL playback, fitted onto the original player's skin model. */
public final class PlayerAnimations {
    private static final Logger LOG = LoggerFactory.getLogger("FSRPG animation");
    private static final Map<UUID, Binding> bindings = new HashMap<>();
    private static final Map<AvatarRenderState, Frame> previews = new WeakHashMap<>();
    private static final PoseLayers.RenderClock renderClock = new PoseLayers.RenderClock();
    private static Object level;
    private static long appliedModels, weaponBatches, transitionFrames, renderSamples;
    private static double lastSampleTick;
    private static final class Binding {
        RpgAnimationPayload state;
        AnimationAssets.Loaded asset;
        PoseLayers.Playback playback;
        PoseLayers.Playback runPlayback, neutralPlayback;
        AnimationAssets.Loaded lowerAsset;
        PoseLayers.Playback lowerPlayback;
        PresentationMotion motion = new PresentationMotion();
        IdleInspection inspection;
        PoseLayers.Playback inspectPlayback;
        String inspectAction = "";
        double received, phaseReceived, started;
        PoseLayers.AttackClock attackClock;
        PoseLayers.PendingAction<RpgAnimationPayload> pendingAction;
        double sampledElapsedTicks = -1, sampledClipSeconds;
        Map<String, Pose> lastPose, transitionFrom, lastBase, swordPending;
        PoseLayers.OffsetDecay swordDecay;
        Map<String, Pose> legacyBase, legacyPending;
        PoseLayers.OffsetDecay legacyDecay;
        String legacyAction = "";
        double legacyTransitionStarted, legacyTransitionTicks;
        double transitionStarted, transitionTicks;
        AttackAim.Point lastAim, aimTransitionFrom;
        Vector3f lastSpearRear, lastSpearSourceRear;
        Quaternionf lastSpearChest, lastSpearSourceChest;
        Map<String, Matrix4f> lastSpearWorld;
        PoseRemap.Knots swordKnots;
        SpearMotion.State spearMotion = new SpearMotion.State();
        SpearTurn.State spearTurn = new SpearTurn.State();
        SpearTurn.State swordTurn = new SpearTurn.State();
        double lastSpearChestTick = Double.NaN;
        double aimTransitionStarted, aimTransitionTicks;
        boolean aimTransitionPending;
        float lastLowerWeight = 1, transitionLowerWeight = 1;
        Frame sampled;
        float sampledYaw, sampledWalkSpeed;
        PlayerModelType sampledSkin;
        Binding(RpgAnimationPayload state, AnimationAssets.Loaded asset, double renderTick, double elapsed, double phaseTick) {
            this.state = state; this.asset = asset;
            inspection = new IdleInspection(state.entityId().getMostSignificantBits() ^ state.entityId().getLeastSignificantBits() ^ System.nanoTime());
            playback = new PoseLayers.Playback(asset.full().rig(), state.action()); received = started = renderTick; phaseReceived = phaseTick;
            if (elapsed >= 0) attackClock = new PoseLayers.AttackClock(elapsed, received, state.windupTicks(), state.activeTicks());
            if (PoseLayers.breathingAction(state.action()) && !LegacyMotion.applies(asset.full())) {
                neutralPlayback = new PoseLayers.Playback(asset.full().rig(), state.action());
                String run = PoseLayers.runAction(asset.full().rig(), state.action());
                if (run != null) runPlayback = new PoseLayers.Playback(asset.full().rig(), run);
            }
            if (SwordMotion.applies(asset.full())) {
                try {
                    lowerAsset = AnimationAssets.load("spear.fsvoxel");
                    lowerPlayback = new PoseLayers.Playback(lowerAsset.full().rig(), PoseLayers.DEFAULT_STANCE);
                    // The authored motion window and the carry pose are clip properties.
                    var clip = asset.full().rig().clips().get(state.action());
                    if (clip != null) {
                        swordKnots = PoseRemap.knots(asset.full().rig(), clip);
                    }
                } catch (Exception failure) { throw new IllegalArgumentException("Unable to load current spear lower-body stance", failure); }
            }
        }
    }
    public record Frame(ModelDocument model, ModelDocument weapon, Map<String, Pose> local, Map<String, Matrix4f> world,
                        float lowerBodyWeight, float movingWeight, double sampledTick, String layer, float rightKnee, float leftKnee, PresentationMotion.Balance balance,
                        Locomotion.LowerBody supportedLower) {
        public Frame(ModelDocument model, ModelDocument weapon, Map<String, Pose> local, Map<String, Matrix4f> world) {
            this(model, weapon, local, world, 1, 0, 0, "full", 0, 0, new PresentationMotion.Balance(0,0,0), null);
        }
    }
    private PlayerAnimations() {}
    private static double now() { return renderClock.sample(); }
    /** Read-only presentation time shared by skin, armor, held items and inspection deadlines. */
    public static double presentationTick() { world(); return now(); }
    static void renderClock(RenderFrameEvent.Pre event) {
        world();
        var game = Minecraft.getInstance();
        // getGameTime() is synchronized by the server and can move backward by a tick.
        // The native timer delta preserves pause/tick-rate semantics without that correction.
        renderClock.advance(event.getPartialTick().getGameTimeDeltaTicks(),
                game.level != null && !game.isPaused() && game.level.tickRateManager().runsNormally());
    }
    private static void world() {
        Object next = Minecraft.getInstance().level;
        if (level != next) { bindings.clear(); previews.clear(); AnimationAssets.clear(); SkinnedArms.clear(); renderClock.reset(); level = next; }
    }
    public static void clear() { bindings.clear(); previews.clear(); AnimationAssets.clear(); SkinnedArms.clear(); renderClock.reset(); level = Minecraft.getInstance().level; }
    public static void reloadModels() {
        var states = bindings.values().stream().map(binding -> binding.state).toList();
        bindings.clear(); AnimationAssets.clear(); states.forEach(PlayerAnimations::accept);
    }
    public static long appliedModels() { return appliedModels; }
    public static long weaponBatches() { return weaponBatches; }
    public static long transitionFrames() { return transitionFrames; }
    public static long renderSamples() { return renderSamples; }
    public static double lastSampleTick() { return lastSampleTick; }
    static void weaponSubmitted(int count) { weaponBatches += count; }
    public static boolean active(UUID entity) { world(); return bindings.containsKey(entity); }
    public static String inspectionAction(UUID entity) {
        var binding = bindings.get(entity); return binding == null ? "" : binding.inspection.action();
    }
    public static double inspectionDeadline(UUID entity) {
        var binding = bindings.get(entity); return binding == null ? Double.NaN : binding.inspection.deadline();
    }
    public static void interruptInspection() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var binding = bindings.get(player.getUUID());
        if (binding != null) { binding.inspection.interrupt(now()); binding.sampled = null; }
    }
    private static Binding nextBinding(RpgAnimationPayload state, AnimationAssets.Loaded asset, Binding previous,
                                       double renderTick, double elapsed, double phaseReceived) {
        Binding next = new Binding(state, asset, renderTick, elapsed, phaseReceived);
        if (previous != null) {
            next.playback.inheritMovement(previous.playback); next.motion = previous.motion;
            if (next.lowerPlayback != null && previous.lowerPlayback != null)
                next.lowerPlayback.inheritMovement(previous.lowerPlayback);
            next.inspection = previous.inspection; next.inspection.interrupt(renderTick);
            next.lastAim = previous.lastAim;
            if (SwordMotion.applies(asset.full()) && previous.asset == asset)
                next.swordTurn = previous.swordTurn;
            if (state.style().equals("spear") && previous.state.style().equals("spear") && previous.asset == asset) {
                next.lastSpearRear = previous.lastSpearRear == null ? null : new Vector3f(previous.lastSpearRear);
                next.lastSpearSourceRear = previous.lastSpearSourceRear == null ? null : new Vector3f(previous.lastSpearSourceRear);
                next.lastSpearChest = previous.lastSpearChest == null ? null : new Quaternionf(previous.lastSpearChest);
                next.lastSpearSourceChest = previous.lastSpearSourceChest == null ? null : new Quaternionf(previous.lastSpearSourceChest);
                next.lastSpearChestTick = previous.lastSpearChestTick;
                next.lastSpearWorld = previous.lastSpearWorld;
                next.spearMotion = previous.spearMotion;
                next.spearTurn = previous.spearTurn;
            }
        }
        if (previous != null && previous.lastPose != null) {
            var clip = asset.full().rig().clips().get(state.action());
            next.transitionFrom = previous.lastPose;
            next.swordPending = previous.lastPose;
            next.swordDecay = null;
            next.transitionLowerWeight = previous.lastLowerWeight;
            next.transitionStarted = renderTick;
            next.transitionTicks = PoseLayers.transitionTicks(clip, ClientOptions.ACTION_BLEND.get(), state.windupTicks(),
                    state.phase().equals("WINDUP") || state.phase().equals("ACTIVE") || state.phase().equals("RECOVERY"));
            if (elapsed >= 0) next.transitionTicks = PoseLayers.entryWindupTicks(next.transitionTicks, elapsed, state.windupTicks());
        }
        if (previous != null && previous.lastAim != null && state.style().equals("spear")) {
            next.aimTransitionFrom = previous.lastAim;
            next.aimTransitionStarted = renderTick;
            next.aimTransitionTicks = Math.max(ClientOptions.ACTION_BLEND.get(), asset.full().rig().clips().get(state.action()).transition()) * 20;
            next.aimTransitionPending = true;
        }
        return next;
    }
    public static void accept(RpgAnimationPayload state) {
        world();
        if (!state.drawn()) { bindings.remove(state.entityId()); return; }
        if (state.windupTicks() < 0 || state.activeTicks() < 0 || state.recoveryTicks() < 0 || state.phaseRemaining() < 0) return;
        Binding previous = bindings.get(state.entityId());
        if (previous != null && previous.state.sequence() > state.sequence()) return;
        if (previous != null && previous.state.sequence() == state.sequence()
                && previous.state.action().equals(state.action()) && previous.state.phase().equals(state.phase())
                && state.phaseRemaining() > previous.state.phaseRemaining()) return;
        double receivedTick = now();
        try {
            AnimationAssets.Loaded asset = AnimationAssets.load(state.model());
            if (!asset.full().rig().clips().containsKey(state.action())) throw new IllegalArgumentException("Missing action " + state.action());
            boolean samePresentation = previous != null && previous.asset == asset && previous.state.style().equals(state.style());
            if (previous != null && previous.pendingAction != null) {
                if (state.sequence() < previous.pendingAction.sequence()) return;
                if (samePresentation && PoseLayers.ordinaryHandoff(state.phase())) {
                    if (previous.pendingAction.offer(state, state.sequence(), state.action(), state.phase(), state.phaseRemaining(), receivedTick))
                        previous.received = receivedTick;
                    return;
                }
                // Damage, dodging, guarding, and equipment changes retain their forced presentation.
                previous.pendingAction = null;
            }
            boolean replace = previous == null || !samePresentation
                    || !PoseLayers.continuesAction(previous.state.action(), previous.state.phase(), previous.state.sequence(),
                            state.action(), state.phase(), state.sequence());
            if (replace) {
                if (samePresentation && PoseLayers.ordinaryHandoff(state.phase()) && previous.attackClock != null
                        && PoseLayers.protectedActive(previous.state.phase(), previous.sampledElapsedTicks, previous.attackClock.sample(receivedTick),
                                previous.state.windupTicks(), previous.state.activeTicks())) {
                    previous.pendingAction = new PoseLayers.PendingAction<>(state, state.sequence(), state.action(), state.phase(),
                            state.phaseRemaining(), receivedTick, previous.state.windupTicks() + previous.state.activeTicks());
                    previous.received = receivedTick;
                    return;
                }
                double elapsed = PoseLayers.attackElapsed(state.phase(), state.phaseRemaining(), state.windupTicks(), state.activeTicks(), state.recoveryTicks());
                bindings.put(state.entityId(), nextBinding(state, asset, previous, receivedTick, elapsed, receivedTick));
            } else {
                double elapsed = PoseLayers.attackElapsed(state.phase(), state.phaseRemaining(), state.windupTicks(), state.activeTicks(), state.recoveryTicks());
                boolean measured = previous.state.sequence() != state.sequence() || !previous.state.phase().equals(state.phase())
                        || previous.state.phaseRemaining() != state.phaseRemaining();
                if (elapsed >= 0) {
                    if (previous.attackClock == null) previous.attackClock = new PoseLayers.AttackClock(elapsed, receivedTick, state.windupTicks(), state.activeTicks());
                    else if (measured) previous.attackClock.accept(elapsed, receivedTick);
                } else previous.attackClock = null;
                if (measured || elapsed < 0) previous.phaseReceived = receivedTick;
                previous.state = state; previous.received = receivedTick; previous.sampled = null;
            }
        } catch (Exception failure) {
            bindings.remove(state.entityId());
            LOG.warn("Unable to bind model {} action {}: {}", state.model(), state.action(), failure.getMessage());
        }
    }
    public static Frame frame(AvatarRenderState state) {
        Frame preview = previews.get(state); if (preview != null) return preview;
        world(); var game = Minecraft.getInstance(); if (game.level == null) return null;
        var entity = game.level.getEntity(state.id);
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return null;
        Binding binding = bindings.get(entity.getUUID()); if (binding == null) return null;
        double renderTick = now();
        if (renderTick - binding.received > 100) { bindings.remove(entity.getUUID()); return null; }
        if (binding.pendingAction != null && binding.pendingAction.ready(renderTick)) {
            var pending = binding.pendingAction;
            var nextState = pending.value();
            double elapsed = pending.elapsed(renderTick, nextState.windupTicks(), nextState.activeTicks(), nextState.recoveryTicks());
            binding = nextBinding(nextState, binding.asset, binding, renderTick, elapsed, pending.phaseReceivedTick());
            binding.received = pending.receivedTick();
            bindings.put(entity.getUUID(), binding);
        }
        // Skin, four armor models and held items share one visual sample within the rendered frame.
        if (binding.sampled != null && binding.sampled.sampledTick() == renderTick
                && binding.sampledYaw == state.yRot && binding.sampledWalkSpeed == state.walkAnimationSpeed
                && binding.sampledSkin == state.skin.model()) return binding.sampled;
        RpgAnimationPayload server = binding.state;
        double delta = Math.max(0, renderTick - binding.phaseReceived);
        double authoritativeElapsed = PoseLayers.attackElapsed(server.phase(), server.phaseRemaining(), server.windupTicks(), server.activeTicks(), server.recoveryTicks());
        if (authoritativeElapsed >= 0) authoritativeElapsed += delta;
        double elapsed = authoritativeElapsed < 0 ? -1 : binding.attackClock.sample(renderTick);
        if (binding.pendingAction != null) elapsed = binding.pendingAction.holdElapsed(elapsed, renderTick);
        var clip = binding.asset.full().rig().clips().get(server.action());
        double seconds;
        double breathSeconds = binding.motion.breathingSeconds(renderTick, ClientOptions.BREATH_SPEED.get());
        if (PoseLayers.breathingAction(server.action())) seconds = breathSeconds;
        else if (elapsed < 0) seconds = Math.max(0, renderTick - binding.started) / 20.0;
        else seconds = phaseTime(clip, Math.max(0, elapsed), server.windupTicks(), server.activeTicks(), server.recoveryTicks());
        binding.sampledElapsedTicks = elapsed; binding.sampledClipSeconds = seconds;
        boolean sword = SwordMotion.applies(binding.asset.full());
        if (LegacyMotion.applies(binding.asset.full()) && !sword) {
            seconds=LegacyMotion.actionSeconds(clip,server,elapsed,Math.max(0,renderTick-binding.started)/20);
            if (elapsed >= 0 && LegacyMotion.kind(binding.asset.full()).equals("dual_blade") && clip.id().contains("attack")) {
                double total = Math.max(1, server.windupTicks() + server.activeTicks() + server.recoveryTicks());
                seconds = PoseRemap.warp(binding.asset.full().rig(), clip).clipTime(elapsed / total);
            }
            binding.sampledClipSeconds=seconds;
            var pose=LegacyMotion.sample(binding.asset.full(),server,seconds,renderTick/20,state,living);
            // The imported dual strokes hand a held end pose straight to the next action. Without
            // a bridge the twin blades snap between clips; the sword path bridges below instead.
            if (!server.action().equals(binding.legacyAction)) {
                binding.legacyAction = server.action();
                if (binding.legacyBase != null) {
                    binding.legacyPending = binding.legacyBase;
                    binding.legacyDecay = null;
                    binding.legacyTransitionStarted = renderTick;
                    binding.legacyTransitionTicks = PoseLayers.transitionTicks(clip, ClientOptions.ACTION_BLEND.get(),
                            server.windupTicks(), elapsed >= 0);
                }
            }
            pose=legacyTransition(binding,pose,renderTick);
            binding.legacyBase = pose;
            pose=retargetSkin(state,pose);
            renderSamples++; lastSampleTick=renderTick;
            binding.sampled=new Frame(binding.asset.full(),null,pose,binding.asset.full().worldMatrices(pose),
                    1,Locomotion.movementTarget(state.walkAnimationSpeed),renderTick,clip.layer(),
                    LegacyMotion.knee(pose,"right"),LegacyMotion.knee(pose,"left"),
                    new PresentationMotion.Balance(0,0,0),null);
            binding.sampledYaw=state.yRot;binding.sampledWalkSpeed=state.walkAnimationSpeed;binding.sampledSkin=state.skin.model();
            return binding.sampled;
        }
        if (sword) {
            seconds = LegacyMotion.actionSeconds(clip, server, elapsed, Math.max(0, renderTick - binding.started) / 20);
            binding.sampledClipSeconds = seconds;
        }
        var sample = binding.playback.sample(seconds, renderTick / 20, renderTick, state.walkAnimationSpeed, -state.yRot,
                ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(), ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue());
        if (sword) {
            var upper = SwordMotion.sampleUpper(binding.asset.full(), clip, seconds, breathSeconds, entity.getXRot(), state.yRot);
            // Retiming changes only the source clock. Do not add the 0.6 counter-pose,
            // strike lean or locomotion sway to the authored torso/hand/item chain.
            if (elapsed >= 0 && binding.swordKnots != null) {
                var attack = PoseRemap.plan(binding.swordKnots, clip, elapsed, server.windupTicks(), server.activeTicks(), server.recoveryTicks());
                upper = SwordMotion.sampleUpper(binding.asset.full(), clip, attack.clipTime(), breathSeconds, entity.getXRot(), state.yRot);
                binding.sampledClipSeconds = attack.clipTime();
            }
            upper = SwordMotion.waist(binding.asset.full(), upper);
            var lower = binding.lowerPlayback.sample(renderTick / 20, renderTick / 20, renderTick, state.walkAnimationSpeed,
                    0, ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(), ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue());
            boolean full = SwordMotion.fullAction(clip);
            // The support solver carries the stance yaw with alternate turning steps below.
            // Keep this source stance square here so the turn is never counted twice.
            sample = new PoseLayers.Sample(SwordMotion.lower(binding.asset.full(), upper, binding.lowerAsset.full().worldMatrices(lower.pose())),
                    sample.movingWeight(), Locomotion.lowerWeight(sample.movingWeight(), full,
                            PoseLayers.movingLowerWeight(clip, ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue())), full ? "full" : "upper");
        }
        var pose = sample.pose();
        if(server.style().equals("dual_blade") && java.util.Set.of("dual_lr","dual_rl").contains(server.action())) {
            String inactive=server.action().startsWith("dual_l")?"right":"left";
            String idle="dual_idle_"+(server.leftSide()+1)+(server.rightSide()+1);
            var held=new PoseLayers.Playback(binding.asset.full().rig(),idle).sample(breathSeconds,renderTick/20,renderTick,state.walkAnimationSpeed,-state.yRot,
                ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(),ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue()).pose();
            pose=new HashMap<>(pose);
            for(String suffix:java.util.List.of("_arm","_forearm","_hand"))pose.put(inactive+suffix,held.get(inactive+suffix));
        }
        float lowerWeight = sample.lowerBodyWeight();
        float partial = game.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float bodyYaw = net.minecraft.util.Mth.rotLerp(partial,
                living.yBodyRotO, living.yBodyRot);
        // Entity age advances with client simulation, including RemotePlayer fixtures;
        // world-time synchronization must not discard or re-estimate this tick's velocity.
        binding.motion.observePosition(entity.tickCount, entity.getX(), entity.getZ(), entity.getX() - entity.xo, entity.getZ() - entity.zo);
        binding.motion.advance(renderTick, binding.motion.velocityX(), binding.motion.velocityZ(), bodyYaw,
                entity.isSprinting(), entity.onGround(), sample.movingWeight(), ClientOptions.MOTION_RESPONSE.get().floatValue());
        if (binding.neutralPlayback != null) {
            var neutral = binding.neutralPlayback.sample(0, renderTick / 20, renderTick, state.walkAnimationSpeed, -state.yRot,
                    ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(), ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue());
            pose = PresentationMotion.breathing(neutral.pose(), pose, ClientOptions.BREATH_SCALE.get().floatValue());
        }
        if (binding.runPlayback != null && server.phase().equals("READY") && binding.motion.runWeight() > .0001f) {
            var carry = binding.runPlayback.sample(renderTick / 20, renderTick / 20, renderTick, state.walkAnimationSpeed, -state.yRot,
                    ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(), ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue());
            pose = PresentationMotion.blend(pose, carry.pose(), binding.motion.runWeight());
        }
        boolean localInput = entity == game.player && (game.screen != null || game.options.keyUp.isDown()
                || game.options.keyDown.isDown() || game.options.keyLeft.isDown() || game.options.keyRight.isDown()
                || game.options.keyJump.isDown() || game.options.keyShift.isDown() || game.options.keyAttack.isDown()
                || game.options.keyUse.isDown());
        boolean inspectEligible = !sword && ClientOptions.IDLE_INSPECTIONS.get() && server.action().equals("rapier_idle")
                && server.phase().equals("READY") && entity.onGround() && !entity.isPassenger() && !living.isUsingItem()
                && !localInput && Math.hypot(binding.motion.velocityX(), binding.motion.velocityZ()) < .003
                && Math.abs(entity.getY() - entity.yo) < .003 && state.walkAnimationSpeed < .015
                && Math.abs(net.minecraft.util.Mth.wrapDegrees(entity.getYRot() - entity.yRotO)) < .5;
        var inspectionAsset = binding.asset;
        binding.inspection.update(renderTick, inspectEligible, ClientOptions.INSPECT_MIN.get(), ClientOptions.INSPECT_MAX.get(),
                id -> { var action = inspectionAsset.full().rig().clips().get(id); return action == null ? 0 : action.duration(); });
        String inspectAction = binding.inspection.action();
        if (!inspectAction.equals(binding.inspectAction)) {
            binding.inspectAction = inspectAction;
            binding.inspectPlayback = inspectAction.isEmpty() ? null : new PoseLayers.Playback(binding.asset.full().rig(), inspectAction);
            if (binding.lastPose != null) {
                binding.transitionFrom = binding.lastPose; binding.transitionLowerWeight = binding.lastLowerWeight;
                binding.transitionStarted = renderTick; binding.transitionTicks = ClientOptions.INSPECT_BLEND.get() * 20;
            }
        }
        if (binding.inspectPlayback != null) {
            pose = binding.inspectPlayback.sample((renderTick - binding.inspection.started()) / 20, renderTick / 20,
                    renderTick, state.walkAnimationSpeed, -state.yRot, ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(),
                    ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue()).pose();
        }
        if (binding.transitionFrom != null && binding.transitionTicks > 0) {
            // Phase corrections cannot pull an entry bridge to its endpoint in a single frame.
            float weight = PoseLayers.transitionWeight(renderTick, binding.transitionStarted, binding.transitionTicks);
            if (weight < 1) {
                // The correction exists to remove the step between the two clips, measured on the
                // new action's own first frame. That frame is already the handoff pose, so it is
                // presented as-is; applying the correction there would count the step twice.
                if (weight <= 0) pose = binding.lastPose == null ? pose : binding.lastPose;
                else {
                    if (binding.swordPending != null) {
                        binding.swordDecay = PoseLayers.OffsetDecay.of(binding.swordPending, pose, binding.lastBase);
                        binding.swordPending = null;
                    }
                    if (binding.swordDecay != null) pose = binding.swordDecay.blend(pose, weight);
                }
                transitionFrames++;
                lowerWeight = binding.transitionLowerWeight + (lowerWeight - binding.transitionLowerWeight) * weight;
            } else { binding.transitionFrom = null; binding.swordDecay = null; }
        }
        binding.lastPose = pose;
        binding.lastBase = pose;
        binding.lastLowerWeight = lowerWeight;
        float strideScale = ClientOptions.STRIDE_LENGTH.get().floatValue();
        if (sword) strideScale = Math.min(strideScale, SwordMotion.MAXIMUM_STRIDE_SCALE);
        float visualWalkPhase = binding.motion.gaitPhase(renderTick, sample.movingWeight(), lowerWeight, strideScale);
        float leanStrength = elapsed >= 0 ? ClientOptions.ATTACK_LEAN_WEIGHT.get().floatValue() : server.phase().equals("GUARDING") ? .6f : 1;
        if (!sword) pose = PresentationMotion.stride(pose, visualWalkPhase, entity.onGround() ? sample.movingWeight() : 0,
                ClientOptions.STRIDE_WEIGHT.get().floatValue() * (elapsed >= 0 ? .25f : 1));
        // Existing client values stay intact on disk; only the additive movement
        // layer is capped. Authored attack/stance rotations remain source-faithful.
        float leanDegrees = Math.min(ClientOptions.MOVEMENT_LEAN.get().floatValue(), SwordMotion.MAXIMUM_MOVEMENT_LEAN);
        float hipShift = ClientOptions.HIP_SHIFT.get().floatValue();
        if (!sword) pose = binding.motion.applyLean(pose, leanDegrees, leanStrength, ClientOptions.HEAD_LEAN_FOLLOW.get().floatValue());
        var balance = sword ? new PresentationMotion.Balance(0, 0, 0) : binding.motion.balance(hipShift, leanStrength);
        var hipBalance = balance;
        var restBody = sword ? SwordMotion.waistRest() : binding.asset.full().rig().parts().get("body").rest().position();
        hipBalance = PresentationMotion.withAuthoredHipShift(pose, restBody, balance);
        pose = PresentationMotion.shiftTorso(pose,balance);
        Locomotion.LowerBody supportedLower = null;
        // The lower body carries the aim too: aiming down folds both knees while the soles stay
        // planted, instead of leaving a straight-legged actor leaning over the pole.
        float aimCrouchCm = 0;
        double authoredPitch = binding.lastAim == null ? entity.getXRot() : binding.lastAim.pitch();
        if (entity.onGround() && !entity.isPassenger() && !state.isFallFlying && state.swimAmount <= .5f) {
            var lowerWorld = binding.asset.full().worldMatrices(pose);
            if (server.style().equals("spear"))
                aimCrouchCm = SpearMotion.aimCrouchCm(authoredPitch, ClientOptions.SPEAR_AIM_CROUCH.get().floatValue());
            supportedLower = Locomotion.supported(lowerWorld.get("right_leg"), lowerWorld.get("left_leg"), lowerWeight,
                    visualWalkPhase, state.walkAnimationSpeed, sample.movingWeight(),
                    binding.motion.lateralLean(), binding.motion.forwardLean(), strideScale,
                    ClientOptions.KNEE_BEND.get().floatValue(), hipBalance,
                    pose.get("body").position().y()-restBody.y(), binding.motion.gaitLane(), aimCrouchCm);
            if(server.style().equals("spear")) {
                var heading=binding.lastSpearChest==null?pose.get("body").fsQuaternion():binding.lastSpearChest;
                var forward=heading.transform(new Vector3f(0,0,1));
                float pelvisYaw=-(float)Math.atan2(forward.x,forward.z);
                var turnSettings=new SpearTurn.Settings(ClientOptions.SPEAR_PELVIS_RESPONSE.get().floatValue(),ClientOptions.SPEAR_TURN_STEP_TIME.get().floatValue(),
                        (float)Math.toRadians(ClientOptions.SPEAR_TURN_STEP_ANGLE.get()),ClientOptions.SPEAR_TURN_STEP_LIFT.get().floatValue()/6.25f);
                supportedLower=Locomotion.turn(supportedLower,binding.spearTurn.advance(renderTick,pelvisYaw,bodyYaw,sample.movingWeight(),turnSettings),sample.movingWeight());
            }
            if (sword) {
                float pelvisYaw = -SwordMotion.bodyYaw(binding.asset.full(), pose) * SwordMotion.STANCE_FOLLOW;
                var turn = binding.swordTurn.advance(renderTick, pelvisYaw, bodyYaw, sample.movingWeight());
                supportedLower = Locomotion.turn(supportedLower, turn, sample.movingWeight());
            }
            // One shared vertical translation keeps the unscaled legs grounded. The sword's
            // authored upper joint rotations, relative positions and official item locator remain intact.
            pose = PresentationMotion.shiftTorso(pose, new PresentationMotion.Balance(0, -supportedLower.pelvisDropCm(), 0));
        }
        if (server.style().equals("rapier") && binding.runPlayback != null && server.phase().equals("READY"))
            pose = PresentationMotion.uprightGrip(pose,binding.asset.full().worldMatrices(pose),binding.motion.runWeight());
        // The two-hand solve must see the real stock/slim shoulder positions.
        if(server.style().equals("dual_blade")) pose=PoseLayers.dualElbows(pose);
        pose = retargetSkin(state, pose);
        Vector3f spearSourceRear = null;
        Quaternionf spearSourceChest = null;
        if(server.style().equals("spear")) {
            var sourceWorld = binding.asset.full().worldMatrices(pose);
            boolean carrying=server.phase().equals("READY")||server.phase().equals("RECOVERY")||server.phase().equals("WINDUP");
            sourceWorld=carrying?SpearMotion.readySource(sourceWorld,ClientOptions.SPEAR_CARRY_DOWN.get().floatValue(),ClientOptions.SPEAR_CARRY_BACK.get().floatValue(),ClientOptions.SPEAR_CARRY_SIDE.get().floatValue()):
                    SpearMotion.underarmSource(sourceWorld,ClientOptions.SPEAR_CARRY_DOWN.get().floatValue(),ClientOptions.SPEAR_CARRY_BACK.get().floatValue(),ClientOptions.SPEAR_CARRY_SIDE.get().floatValue());
            spearSourceRear = sourceWorld.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
            spearSourceChest = sourceWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            double chestReturn = server.phase().equals("READY") || server.phase().equals("RECOVERY") ? ClientOptions.SPEAR_CHEST_RETURN.get() : 0;
            AttackAim.Point aim;
            if(server.aim()!=null && authoritativeElapsed>=0) {
                // The active hit direction stays tied to the server's contact trajectory.
                double fraction=PoseLayers.aimFraction(server.phase(), authoritativeElapsed, server.windupTicks(), server.activeTicks());
                aim=server.aim().sample(fraction);
            } else if(entity==game.player && CombatCamera.active() && game.screen==null) aim=CombatCamera.aimPoint();
            else {
                double relativeYaw = Math.clamp(AttackAim.wrap(entity.getYRot() - bodyYaw),
                        -SpearAimField.MAX_YAW_DEGREES, SpearAimField.MAX_YAW_DEGREES);
                double pitch = Math.clamp(entity.getXRot(), -SpearAimField.MAX_PITCH_DEGREES,
                        SpearAimField.MAX_PITCH_DEGREES);
                double distance = SpearAimField.MIN_FORWARD_DISTANCE /
                        (Math.cos(Math.toRadians(relativeYaw)) * Math.cos(Math.toRadians(pitch)));
                aim = new AttackAim.Point(AttackAim.wrap(bodyYaw + relativeYaw), pitch, distance);
            }
            if (binding.aimTransitionFrom != null) {
                if (server.phase().equals("ACTIVE")) {
                    // Windup must settle onto the authoritative direction before contact starts.
                    binding.aimTransitionFrom = null; binding.aimTransitionPending = false;
                } else {
                    if (binding.aimTransitionPending) {
                        binding.aimTransitionTicks = server.phase().equals("READY")
                                ? PoseLayers.aimTransitionTicks(binding.aimTransitionFrom, aim, binding.aimTransitionTicks, ClientOptions.SPEAR_AIM_RETURN.get())
                                : PoseLayers.aimTransitionTicks(binding.aimTransitionFrom, aim, binding.aimTransitionTicks);
                        // Complete the turn during anticipation, leaving a stable contact
                        // direction before an ACTIVE packet or render gap advances the arc.
                        if (server.phase().equals("WINDUP")) binding.aimTransitionTicks = PoseLayers.windupAimTicks(binding.aimTransitionTicks,
                                renderTick - binding.aimTransitionStarted, server.windupTicks() - authoritativeElapsed);
                        binding.aimTransitionPending = false;
                    }
                    float weight = server.phase().equals("WINDUP")
                            ? PoseLayers.windupAimWeight(renderTick, binding.aimTransitionStarted, binding.aimTransitionTicks)
                            : PoseLayers.transitionWeight(renderTick, binding.aimTransitionStarted, binding.aimTransitionTicks);
                    aim = PoseLayers.blendAim(binding.aimTransitionFrom, aim, weight);
                    if (weight >= 1) binding.aimTransitionFrom = null;
                }
            }
            // A shortest spherical bridge can bulge above a horizontal field edge.
            // Ready/windup/recovery stay inside the displayed field; contact keeps
            // its exact authoritative world trajectory.
            if (!server.phase().equals("ACTIVE")) aim = SpearAimField.constrain(aim, bodyYaw);
            binding.lastAim = aim;
            // Ready aiming can carry its latest feasible grip around the body.
            // An attack keeps the authored anchor so its stroke cannot accumulate
            // the solver's rear displacement across windup, contact and recovery.
            float gripFidelity = server.phase().equals("READY") ? SpearAimPose.authoredFidelity(aim,bodyYaw) : 1;
            var spearSeed = binding.spearMotion.prepare(sourceWorld,renderTick,chestReturn,gripFidelity);
            // The hands slide along the pole instead of moving as one rigid block: working the
            // weapon opens the grip, and a carried pole keeps the authored separation.
            float authoredSpacing = SpearGrip.authored(sourceWorld);
            float gripSpacing = server.phase().equals("READY") ? SpearGrip.clamp(authoredSpacing)
                    : SpearGrip.solve(authoredSpacing, (float)(aim.distance() * 100), spearSeed.authoredMotion());
            pose=SpearAimPose.apply(binding.asset.full(),pose,aim,bodyYaw,living.getEyeHeight(),spearSeed.rear(),spearSeed.chest(),
                    spearSeed.actual(),spearSeed.continuity(),spearSeed.authoredMotion(),new SpearConstraints.Limits(ClientOptions.SPEAR_ELBOW_LIMIT.get().floatValue(),
                            ClientOptions.SPEAR_WRIST_LIMIT.get().floatValue(),ClientOptions.SPEAR_CLEARANCE.get().floatValue(),
                            ClientOptions.SPEAR_NECK_LIMIT.get().floatValue(),ClientOptions.SPEAR_VERTICAL_BANK.get().floatValue(),true)
                            .withCarry(carrying).withGrip(gripSpacing));
        }
        var finalWorld = binding.asset.full().worldMatrices(pose);
        if (spearSourceRear != null) {
            binding.spearMotion.commit(finalWorld, renderTick);
            binding.lastSpearSourceRear = spearSourceRear;
            binding.lastSpearRear = finalWorld.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
            binding.lastSpearSourceChest = spearSourceChest;
            binding.lastSpearChest = finalWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            binding.lastSpearChestTick = Double.isNaN(binding.lastSpearChestTick) ? renderTick : Math.max(binding.lastSpearChestTick, renderTick);
            binding.lastSpearWorld = finalWorld;
        }
        renderSamples++; lastSampleTick = renderTick;
        binding.sampled = new Frame(binding.asset.full(), binding.asset.weapon(), pose, finalWorld,
                lowerWeight, sample.movingWeight(), renderTick, sample.layer(),
                supportedLower == null ? 0 : supportedLower.right().knee(),
                supportedLower == null ? 0 : supportedLower.left().knee(), hipBalance, supportedLower);
        binding.sampledYaw = state.yRot; binding.sampledWalkSpeed = state.walkAnimationSpeed; binding.sampledSkin = state.skin.model();
        return binding.sampled;
    }
    /** A detached editor state may use real skin/armor/items without mutating live actor playback. */
    public static void preview(AvatarRenderState state, ModelDocument document, Map<String, Pose> source) {
        if(LegacyMotion.applies(document)) {
            if (SwordMotion.applies(document)) source = SwordMotion.waist(document, source);
            var pose=retargetSkin(state,Map.copyOf(source));
            previews.put(state,new Frame(document,null,pose,document.worldMatrices(pose),1,0,now(),"full",
                    LegacyMotion.knee(pose,"right"),LegacyMotion.knee(pose,"left"),new PresentationMotion.Balance(0,0,0),null));
            return;
        }
        if(document.rig().id().equals("fsrpg.dual_blade"))source=PoseLayers.dualElbows(source);
        Map<String, Pose> pose = retargetSkin(state, Map.copyOf(source));
        var balance = new PresentationMotion.Balance(0, 0, 0);
        balance = PresentationMotion.withAuthoredHipShift(pose, document.rig().parts().get("body").rest().position(), balance);
        var w = document.worldMatrices(pose);
        var lower = Locomotion.supported(w.get("right_leg"), w.get("left_leg"), 1, 0, 0, 0, 0, 1, 1, 48, balance,
                pose.get("body").position().y()-document.rig().parts().get("body").rest().position().y(),0);
        pose = PresentationMotion.shiftTorso(pose, new PresentationMotion.Balance(0, -lower.pelvisDropCm(), 0));
        previews.put(state, new Frame(document, null, pose, document.worldMatrices(pose), 1, 0, now(), "full",
                lower.right().knee(), lower.left().knee(), balance, lower));
    }
    public static void clearPreview(AvatarRenderState state) { previews.remove(state); }
    /** The FP viewmodel shares the same authoritative action clock, sampled every rendered frame. */
    static RpgAnimationPayload localIntent() {
        world(); var player=Minecraft.getInstance().player;
        Binding binding=player==null?null:bindings.get(player.getUUID());return binding==null?null:binding.state;
    }
    static RpgAnimationPayload intent(UUID player) { Binding binding=bindings.get(player);return binding==null?null:binding.state; }
    static double localSeconds() {
        return localSeconds(false);
    }
    static double localSeconds(boolean firstPerson) {
        var player=Minecraft.getInstance().player;Binding b=player==null?null:bindings.get(player.getUUID());
        if(b==null)return 0;
        double renderTick=now(),elapsed=PoseLayers.attackElapsed(b.state.phase(),b.state.phaseRemaining(),b.state.windupTicks(),b.state.activeTicks(),b.state.recoveryTicks());
        if(elapsed>=0)elapsed=b.attackClock.sample(renderTick);
        var clip=b.asset.full().rig().clips().get(b.state.action());
        return LegacyMotion.actionSeconds(clip,b.state,elapsed,Math.max(0,renderTick-b.started)/20,firstPerson);
    }
    /** Velocity-preserving bridge for the imported clips, which hand held poses to each other. */
    private static Map<String, Pose> legacyTransition(Binding binding, Map<String, Pose> pose, double renderTick) {
        if (binding.legacyPending != null) {
            // The correction needs the new action's own first frame, so it is measured here.
            binding.legacyDecay = PoseLayers.OffsetDecay.of(binding.legacyPending, pose, binding.legacyBase);
            binding.legacyPending = null;
        }
        float weight = PoseLayers.transitionWeight(renderTick, binding.legacyTransitionStarted, binding.legacyTransitionTicks);
        if (weight >= 1) { binding.legacyDecay = null; return pose; }
        return binding.legacyDecay.blend(pose, weight);
    }

    private static Map<String, Pose> retargetSkin(AvatarRenderState state, Map<String, Pose> pose) {
        if (state.skin.model() == PlayerModelType.SLIM) {
            pose = new HashMap<>(pose);
            for (var side : new String[]{"right", "left"}) {
                String id = side + "_arm"; Pose original = pose.get(id);
                if (original != null) {
                    var at = original.position();
                    pose.put(id, new Pose(original.rotation(), new org.fsvoxel.animation.FsvoxAnimation.V(
                            at.x() + (side.equals("right") ? 3.125f : -3.125f), at.y(), at.z()), original.scale()));
                }
            }
        }
        return pose;
    }
    /** Action marker times, rather than magic constants, define the three editable combat windows. */
    public static double phaseTime(org.fsvoxel.animation.FsvoxAnimation.Clip clip, double ticks, double windup, double active, double recovery) {
        return PoseLayers.phaseTime(clip, ticks, windup, active, recovery);
    }
    public static void layers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            var renderer = event.getPlayerRenderer(skin);
            if (renderer != null) renderer.addLayer(new RapierWeaponLayer(renderer));
        }
    }
    /** FS frame: feet at 0, Y up, facing +Z. Native frame: head at 0, Y down, facing -Z. */
    private static Vector3f nativePoint(Vector3f point) { return new Vector3f(point.x / 6.25f, (150 - point.y) / 6.25f, -point.z / 6.25f); }
    private static Quaternionf nativeRotation(Matrix4f matrix) {
        Quaternionf q = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
        return new Quaternionf(q.x, -q.y, -q.z, q.w);
    }
    private static void setRotation(ModelPart part, Quaternionf rotation) {
        Vector3f euler = rotation.getEulerAnglesZYX(new Vector3f()); part.setRotation(euler.x, euler.y, euler.z);
    }
    private static void place(ModelPart part, Matrix4f matrix, Vector3f localPivot) {
        Vector3f at = nativePoint(matrix.transformPosition(new Vector3f(localPivot)));
        part.setPos(at.x, at.y, at.z); setRotation(part, nativeRotation(matrix));
    }
    private static void arm(ModelPart arm, Matrix4f shoulder) {
        Quaternionf rotation = nativeRotation(shoulder);
        Vector3f pivot = nativePoint(shoulder.getTranslation(new Vector3f()))
                .add(rotation.transform(new Vector3f(-SkinnedArms.centerX(arm), 2, 0)));
        arm.setPos(pivot.x, pivot.y, pivot.z); arm.xScale = arm.yScale = arm.zScale = 1;
        setRotation(arm, rotation);
    }
    private static Locomotion.PartPose nativePose(ModelPart part) {
        return new Locomotion.PartPose(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot);
    }
    private static void leg(ModelPart part, Matrix4f target, float weight) {
        if (weight <= 0) return;
        var vanilla = nativePose(part);
        place(part, target, new Vector3f());
        var composed = Locomotion.mix(vanilla, nativePose(part), weight);
        part.setPos(composed.x(), composed.y(), composed.z());
        part.setRotation(composed.xRot(), composed.yRot(), composed.zRot());
    }
    private static void balanceLeg(ModelPart part, PresentationMotion.Balance balance) {
        var balanced = PresentationMotion.balanceLeg(nativePose(part), balance);
        part.setPos(balanced.x(), balanced.y(), balanced.z());
        part.setRotation(balanced.xRot(), balanced.yRot(), balanced.zRot());
    }
    public static void apply(PlayerModel model, AvatarRenderState state) {
        applyHumanoid(model, state);
    }
    public static void applyHumanoid(HumanoidModel<?> model, AvatarRenderState state) {
        SkinnedArms.prepare(model, null);
        if (state.isSpectator || state.isFallFlying || state.swimAmount > .5f) return;
        Frame frame = frame(state); if (frame == null) return;
        SkinnedArms.prepare(model, frame);
        Map<String, Matrix4f> world = frame.world();
        boolean legacy=LegacyMotion.applies(frame.model());
        if (world.containsKey("body")) place(model.body, world.get("body"), new Vector3f(0, legacy && !SwordMotion.applies(frame.model()) ? 43.75f : 75, 0));
        if (world.containsKey("head")) place(model.head, world.get("head"), new Vector3f());
        if (frame.supportedLower() != null) {
            supportedLeg(model.rightLeg, frame.supportedLower().right().pose());
            supportedLeg(model.leftLeg, frame.supportedLower().left().pose());
        }
        else if(legacy && (!SwordMotion.applies(frame.model()) || previews.containsKey(state))) {
            if(world.containsKey("right_leg"))place(model.rightLeg,world.get("right_leg"),new Vector3f());
            if(world.containsKey("left_leg"))place(model.leftLeg,world.get("left_leg"),new Vector3f());
        }
        if (world.containsKey("right_arm")) arm(model.rightArm, world.get("right_arm"));
        if (world.containsKey("left_arm")) arm(model.leftArm, world.get("left_arm"));
        // 1.21.11 skin overlays are children of their corresponding limbs and inherit these transforms.
        appliedModels++;
    }
    private static void supportedLeg(ModelPart part, Locomotion.PartPose pose) {
        part.setPos(pose.x(), pose.y(), pose.z());
        part.setRotation(pose.xRot(), pose.yRot(), pose.zRot());
    }
}

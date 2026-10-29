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
import org.fsvoxel.animation.FsvoxAnimation;
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
    private static final Map<UUID,AirPose.State<Frame>> airPoses=new HashMap<>();
    private static final Map<UUID,AirInertia.State> airInertias=new HashMap<>();
    private static final Map<UUID,AirInertia.Offset> airOffsets=new HashMap<>();
    private static final Map<AvatarRenderState, Frame> previews = new WeakHashMap<>();
    private static final PoseLayers.RenderClock renderClock = new PoseLayers.RenderClock();
    private static Object level;
    private static long appliedModels, weaponBatches, transitionFrames, renderSamples;
    private static long renderFrameId, frameRequests, frameCacheHits, spearSolveCalls, spearSolveNanos, maxSpearSolveNanos, lastSpearSolveNanos;
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
        Map<String,Pose> inspectionBase;
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
        RapierMotion.State swordLean = new RapierMotion.State();
        double lastSpearChestTick = Double.NaN;
        double aimTransitionStarted, aimTransitionTicks;
        boolean aimTransitionPending;
        float lastLowerWeight = 1, transitionLowerWeight = 1;
        Frame sampled;
        long sampledRenderFrame = -1;
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
                    if (clip != null && !RapierMotion.cut(clip)) {
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
        world(); renderFrameId++;
        var game = Minecraft.getInstance();
        // getGameTime() is synchronized by the server and can move backward by a tick.
        // The native timer delta preserves pause/tick-rate semantics without that correction.
        renderClock.advance(event.getPartialTick().getGameTimeDeltaTicks(),
                game.level != null && !game.isPaused() && game.level.tickRateManager().runsNormally());
    }
    private static void world() {
        Object next = Minecraft.getInstance().level;
        if (level != next) { bindings.clear(); airPoses.clear(); airInertias.clear(); airOffsets.clear(); previews.clear(); AnimationAssets.clear(); SkinnedArms.clear(); renderClock.reset(); level = next; }
    }
    public static void clear() { bindings.clear(); airPoses.clear(); airInertias.clear(); airOffsets.clear(); previews.clear(); AnimationAssets.clear(); SkinnedArms.clear(); renderClock.reset(); level = Minecraft.getInstance().level; }
    public static void reloadModels() {
        AvatarAppearances.reload();
        try { WeaponInspections.reload(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("fsrpg")); }
        catch(Exception error){LOG.warn("Unable to reload weapon inspection catalogue; retaining previous validated data",error);}
        var states = bindings.values().stream().map(binding -> binding.state).toList();
        bindings.clear(); airPoses.clear(); airInertias.clear(); airOffsets.clear(); AnimationAssets.clear(); states.forEach(PlayerAnimations::accept);
    }
    public static long appliedModels() { return appliedModels; }
    public static long weaponBatches() { return weaponBatches; }
    public static long transitionFrames() { return transitionFrames; }
    public static long renderSamples() { return renderSamples; }
    public static long renderFrameId() { return renderFrameId; }
    public static long frameRequests() { return frameRequests; }
    public static long frameCacheHits() { return frameCacheHits; }
    public static long spearSolveCalls() { return spearSolveCalls; }
    public static long spearSolveNanos() { return spearSolveNanos; }
    public static long maxSpearSolveNanos() { return maxSpearSolveNanos; }
    public static long lastSpearSolveNanos() { return lastSpearSolveNanos; }
    public static long spearContinuationCalls() { return SpearAimPose.realtimeFallbackCalls(); }    public static double lastSampleTick() { return lastSampleTick; }
    static void weaponSubmitted(int count) { weaponBatches += count; }
    public static boolean active(UUID entity) { world(); return bindings.containsKey(entity); }
    public static String inspectionAction(UUID entity) {
        var binding = bindings.get(entity); return binding == null ? "" : binding.inspection.action();
    }
    /** The H mapping cycles only the current cosmetic catalogue, without any server combat intent. */
    public static boolean requestInspection() { return requestInspection(null); }
    public static boolean requestInspection(String action) {
        world(); var player=Minecraft.getInstance().player;
        Binding binding=player==null?null:bindings.get(player.getUUID());
        if(binding==null)return false;
        double tick=now(); updateInspection(binding,player,tick);
        var choices=WeaponInspections.actions(binding.state.style());
        boolean eligible=inspectionEligible(binding,player);
        boolean accepted=action==null?binding.inspection.request(tick,eligible,choices,WeaponInspections::duration)
                :binding.inspection.request(tick,eligible,choices,action,WeaponInspections::duration);
        if(accepted){binding.inspectionBase=null;binding.sampled=null;}
        return accepted;
    }
    private static boolean inspectionEligible(Binding binding,net.minecraft.world.entity.LivingEntity entity) {
        var game=Minecraft.getInstance(); var server=binding.state;
        boolean localInput=entity==game.player&&(game.options.getCameraType().isFirstPerson()||game.screen!=null||!game.isWindowActive()||game.options.keyUp.isDown()
                ||game.options.keyDown.isDown()||game.options.keyLeft.isDown()||game.options.keyRight.isDown()
                ||game.options.keyJump.isDown()||game.options.keyShift.isDown()||game.options.keyAttack.isDown()||game.options.keyUse.isDown());
        return server.drawn()&&server.phase().equals("READY")&&entity.isAlive()&&entity.onGround()
                &&!entity.isPassenger()&&!entity.isFallFlying()&&!entity.isInWater()&&!entity.isUsingItem()&&!entity.isCrouching()
                &&!localInput&&Math.hypot(entity.getX()-entity.xo,entity.getZ()-entity.zo)<.003
                &&Math.abs(entity.getY()-entity.yo)<.003&&Math.abs(net.minecraft.util.Mth.wrapDegrees(entity.getYRot()-entity.yRotO))<.5;
    }
    private static void updateInspection(Binding binding,net.minecraft.world.entity.LivingEntity entity,double tick) {
        binding.inspection.update(tick,inspectionEligible(binding,entity),ClientOptions.IDLE_INSPECTIONS.get(),
                ClientOptions.INSPECT_MIN.get(),ClientOptions.INSPECT_MAX.get(),WeaponInspections.actions(binding.state.style()),WeaponInspections::duration);
    }
    private static Map<String,Pose> inspectionPose(Binding binding,net.minecraft.world.entity.LivingEntity entity,
                                                   Map<String,Pose> pose,double renderTick) {
        updateInspection(binding,entity,renderTick);
        String inspection = binding.inspection.action();
        if (!inspection.isEmpty()) {
            if (binding.inspectionBase == null || !inspection.equals(binding.inspectAction))
                binding.inspectionBase = Map.copyOf(pose);
            binding.inspectAction = inspection;
            double inspectSeconds = Math.max(0,renderTick-binding.inspection.started())/20;
            double returnSeconds = Math.min(.3,WeaponInspections.duration(inspection)*.15);
            float returning = (float)Math.clamp((inspectSeconds-WeaponInspections.duration(inspection)+returnSeconds)/returnSeconds,0,1);
            returning=returning*returning*(3-2*returning);
            var inspectionBase = PresentationMotion.blend(binding.inspectionBase,pose,returning);
            pose = WeaponInspections.sample(binding.asset.full(),inspectionBase,inspection,inspectSeconds);
        } else { binding.inspectionBase = null; binding.inspectAction = ""; }
        return pose;
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
            if (SwordMotion.applies(asset.full()) && previous.asset == asset) {
                next.swordTurn = previous.swordTurn;
                next.swordLean = previous.swordLean;
            }
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
        var nextClip=asset.full().rig().clips().get(state.action());
        var previousClip=previous==null?null:previous.asset.full().rig().clips().get(previous.state.action());
        boolean fencingEntry=RapierMotion.fencing(nextClip)&&previous!=null&&!RapierMotion.directHandoff(previousClip,nextClip,
                previous.sampledElapsedTicks,previous.state.windupTicks(),previous.state.activeTicks());
        // Queued fencing phrases share the actual ACTIVE-end pose. A late click
        // after natural recovery starts from the real guard with a short entry blend.
        if (previous != null && previous.lastPose != null
                && (!(SwordMotion.applies(asset.full())&&PoseLayers.attackElapsed(state.phase(),state.phaseRemaining(),state.windupTicks(),state.activeTicks(),state.recoveryTicks())>=0)||fencingEntry)) {
            var clip = asset.full().rig().clips().get(state.action());
            next.transitionFrom = previous.lastPose;
            next.swordPending = previous.lastPose;
            next.swordDecay = null;
            next.transitionLowerWeight = previous.lastLowerWeight;
            next.transitionStarted = renderTick;
            next.transitionTicks = PoseLayers.transitionTicks(clip, ClientOptions.ACTION_BLEND.get(), state.windupTicks(),
                    state.phase().equals("WINDUP") || state.phase().equals("ACTIVE") || state.phase().equals("RECOVERY"));
            if (elapsed >= 0) next.transitionTicks = PoseLayers.entryWindupTicks(next.transitionTicks, elapsed, state.windupTicks());
            if(fencingEntry)next.transitionTicks=Math.min(1.25,next.transitionTicks);
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
        if (!state.drawn()) { bindings.remove(state.entityId()); airPoses.remove(state.entityId()); airInertias.remove(state.entityId()); airOffsets.remove(state.entityId()); return; }
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
                    if (previous.state.spearHeld() && !state.spearHeld()) {
                        // A hold can last seconds while the visible stroke stays extended.
                        // Release starts recovery there, rather than inheriting that wall time
                        // and skipping directly to the action's final/retracted frame.
                        elapsed = Math.max(elapsed,Math.min(previous.sampledElapsedTicks,state.windupTicks()+state.activeTicks()));
                        previous.attackClock = new PoseLayers.AttackClock(elapsed,receivedTick,state.windupTicks(),state.activeTicks());
                    }
                    else if (previous.attackClock == null) previous.attackClock = new PoseLayers.AttackClock(elapsed, receivedTick, state.windupTicks(), state.activeTicks());
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
        frameRequests++;
        Frame preview = previews.get(state); if (preview != null) return preview;
        world(); var game = Minecraft.getInstance(); if (game.level == null) return null;
        var entity = game.level.getEntity(state.id);
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return null;
        Binding binding = bindings.get(entity.getUUID()); if (binding == null) return null;
        double renderTick = now();
        if (renderTick - binding.received > 100) { bindings.remove(entity.getUUID()); airPoses.remove(entity.getUUID()); airInertias.remove(entity.getUUID()); airOffsets.remove(entity.getUUID()); return null; }
        binding = advancePending(entity.getUUID(), binding, renderTick);
        // Skin, four armor models and held items share one visual sample within the rendered frame.
        if (binding.sampled != null && binding.sampledRenderFrame == renderFrameId) {
            frameCacheHits++; return binding.sampled;
        }
        RpgAnimationPayload server = binding.state;
        double delta = Math.max(0, renderTick - binding.phaseReceived);
        double authoritativeElapsed = PoseLayers.attackElapsed(server.phase(), server.phaseRemaining(), server.windupTicks(), server.activeTicks(), server.recoveryTicks());
        if (authoritativeElapsed >= 0) authoritativeElapsed += delta;
        double elapsed = authoritativeElapsed < 0 ? -1 : binding.attackClock.sample(renderTick);
        if (binding.pendingAction != null) elapsed = binding.pendingAction.holdElapsed(elapsed, renderTick);
        if (server.style().equals("spear") && server.spearHeld() && elapsed >= 0)
            elapsed = Math.min(elapsed, server.windupTicks() + server.activeTicks());
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
            binding.sampledClipSeconds=seconds;
            var pose=LegacyMotion.sample(binding.asset.full(),server,seconds,renderTick/20,state,living);
            // The original dual action controller uses linear source time and zero transition.
            // Keep its authored upper/locomotion layers together without the later offset bridge.
            if (!LegacyMotion.kind(binding.asset.full()).equals("dual_blade")) {
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
            }
            pose=retargetSkin(state,binding.asset.full(),pose);
            pose=inspectionPose(binding,living,pose,renderTick);
            renderSamples++; lastSampleTick=renderTick;
            binding.sampledRenderFrame=renderFrameId;
            binding.sampled=airFrame(entity,server,new Frame(binding.asset.full(),null,pose,binding.asset.full().worldMatrices(pose),
                    1,Locomotion.movementTarget(state.walkAnimationSpeed),renderTick,clip.layer(),
                    LegacyMotion.knee(pose,"right"),LegacyMotion.knee(pose,"left"),
                    new PresentationMotion.Balance(0,0,0),null),state);
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
            // The authored shoulder, elbow and wrist chain is sampled once. The small
            // shared-waist travel response is applied after action transitions below.
            if(elapsed>=0&&RapierMotion.cut(clip)) {
                double cutSeconds=RapierMotion.clipTime(clip,elapsed,server.windupTicks(),server.activeTicks(),server.recoveryTicks());
                upper=SwordMotion.sampleUpper(binding.asset.full(),clip,cutSeconds,breathSeconds,entity.getXRot(),state.yRot);
                binding.sampledClipSeconds=cutSeconds;
            } else if (elapsed >= 0 && binding.swordKnots != null) {
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
        if (binding.transitionFrom != null && binding.transitionTicks > 0) {
            // Phase corrections cannot pull an entry bridge to its endpoint in a single frame.
            float weight = PoseLayers.transitionWeight(renderTick, binding.transitionStarted, binding.transitionTicks);
            if (weight < 1) {
                if (sword) {
                    // A new binding has no lastPose yet. Keep the actual outgoing joints at
                    // weight zero instead of flashing the target and then correcting back.
                    pose = RapierMotion.bridge(binding.transitionFrom, pose, weight);
                } else {
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
        RapierMotion.Lean swordLean=null;
        if(sword){
            var reaction=elapsed>=0?RapierMotion.recoil(clip,binding.sampledClipSeconds):new RapierMotion.Lean(0,0);
            // Use the supported feet's distance-driven phase; the persistent lean state
            // eases both starting/stopping and changes of attack or guard amplitude.
            var gait=RapierMotion.gait(visualWalkPhase,entity.onGround()?sample.movingWeight():0,
                    (float)Math.hypot(binding.motion.velocityX(),binding.motion.velocityZ()),
                    ClientOptions.STRIDE_WEIGHT.get().floatValue()*(elapsed>=0?.45f:server.phase().equals("GUARDING")?.6f:1));
            var target=RapierMotion.combine(binding.motion.lateralLean(),binding.motion.forwardLean(),leanDegrees,leanStrength,
                    RapierMotion.add(reaction,gait));
            swordLean=binding.swordLean.advance(renderTick,target,ClientOptions.MOTION_RESPONSE.get());
            pose=PresentationMotion.lean(pose,swordLean.lateral(),swordLean.forward(),ClientOptions.HEAD_LEAN_FOLLOW.get().floatValue());
        } else pose = binding.motion.applyLean(pose, leanDegrees, leanStrength, ClientOptions.HEAD_LEAN_FOLLOW.get().floatValue());
        var balance = sword ? RapierMotion.balance(swordLean,hipShift) : binding.motion.balance(hipShift, leanStrength);
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
            var rightSupport=sword?RapierMotion.fencingFoot(lowerWorld.get("right_leg"),false,clip,binding.sampledClipSeconds,sample.movingWeight()):lowerWorld.get("right_leg");
            var leftSupport=sword?RapierMotion.fencingFoot(lowerWorld.get("left_leg"),true,clip,binding.sampledClipSeconds,sample.movingWeight()):lowerWorld.get("left_leg");
            supportedLower = Locomotion.supported(rightSupport, leftSupport, lowerWeight,
                    visualWalkPhase, state.walkAnimationSpeed, sample.movingWeight(),
                    binding.motion.lateralLean(), binding.motion.forwardLean(), strideScale,
                    ClientOptions.KNEE_BEND.get().floatValue(), hipBalance,
                    pose.get("body").position().y()-restBody.y(), binding.motion.gaitLane(), aimCrouchCm);
            if (sword) {
                float pelvisYaw = -SwordMotion.bodyYaw(binding.asset.full(), pose) * SwordMotion.STANCE_FOLLOW;
                var turn = binding.swordTurn.advance(renderTick, pelvisYaw, bodyYaw, sample.movingWeight(), turnSettings());
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
        pose = retargetSkin(state, binding.asset.full(),pose);
        Vector3f spearSourceRear = null;
        Quaternionf spearSourceChest = null;
        if(server.style().equals("spear")) {
            var sourceWorld = binding.asset.full().worldMatrices(pose);
            boolean carrying=server.phase().equals("READY")||server.phase().equals("RECOVERY")||server.phase().equals("WINDUP");
            float backCm = ClientOptions.SPEAR_CARRY_BACK.get().floatValue() + ClientOptions.SPEAR_READY_RETRACT.get().floatValue()
                    * SpearStrokePose.retraction(server.phase(), elapsed, server.windupTicks(), server.activeTicks(), server.recoveryTicks());
            sourceWorld=carrying?SpearMotion.readySource(sourceWorld,ClientOptions.SPEAR_CARRY_DOWN.get().floatValue(),backCm,ClientOptions.SPEAR_CARRY_SIDE.get().floatValue()):
                    SpearMotion.underarmSource(sourceWorld,ClientOptions.SPEAR_CARRY_DOWN.get().floatValue(),backCm,ClientOptions.SPEAR_CARRY_SIDE.get().floatValue());
            spearSourceRear = sourceWorld.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
            spearSourceChest = sourceWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            if (server.phase().equals("GUARDING")) {
                // The spear is a staff, not a pointed warning: both hands hold it across the chest
                // while guarding. The right hand's palm -Y is the carrier shaft axis; turn it to the
                // body's lateral axis so the pole visibly blocks the incoming line instead of aiming.
                pose = spearGuardHands(pose, binding.asset.full());
                // The guard pose is authored, not solved: do not commit a solve that was never prepared.
                spearSourceRear = null;
                spearSourceChest = null;
            } else {
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
            // Keep the pole in the current forward field during packet latency and
            // spherical bridges as well. Legal authoritative contact directions are unchanged.
            aim = SpearAimField.constrain(aim, bodyYaw);
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
            long solveStarted=System.nanoTime();
            pose=SpearAimPose.apply(binding.asset.full(),pose,aim,bodyYaw,living.getEyeHeight(),spearSeed.rear(),spearSeed.chest(),
                    spearSeed.actual(),spearSeed.continuity(),spearSeed.authoredMotion(),new SpearConstraints.Limits(ClientOptions.SPEAR_ELBOW_LIMIT.get().floatValue(),
                            ClientOptions.SPEAR_WRIST_LIMIT.get().floatValue(),ClientOptions.SPEAR_CLEARANCE.get().floatValue(),
                            ClientOptions.SPEAR_NECK_LIMIT.get().floatValue(),ClientOptions.SPEAR_VERTICAL_BANK.get().floatValue(),true)
                            .withCarry(carrying).withGrip(gripSpacing));
            lastSpearSolveNanos=System.nanoTime()-solveStarted;
            spearSolveNanos+=lastSpearSolveNanos;spearSolveCalls++;
            maxSpearSolveNanos=Math.max(maxSpearSolveNanos,lastSpearSolveNanos);
            }
        }
        var finalWorld = binding.asset.full().worldMatrices(pose);
        var spearSolvedWorld = finalWorld;
        if (server.style().equals("spear") && supportedLower != null) {
            // Use this frame's solved chest, rather than the previous frame's cached heading:
            // a sudden target/root turn must obey the waist limit before it is rendered.
            var forward = finalWorld.get("body").transformDirection(new Vector3f(0,0,1));
            float pelvisYaw = -(float)Math.atan2(forward.x,forward.z);
            float previousDrop = supportedLower.pelvisDropCm();
            supportedLower = Locomotion.turn(supportedLower,
                    binding.spearTurn.advance(renderTick,pelvisYaw,bodyYaw,sample.movingWeight(),turnSettings()),sample.movingWeight());
            pose = PresentationMotion.shiftTorso(pose,new PresentationMotion.Balance(0,previousDrop-supportedLower.pelvisDropCm(),0));
            finalWorld = binding.asset.full().worldMatrices(pose);
        }
        if (spearSourceRear != null) {
            // Turning-step support is a common cosmetic translation after IK. Exclude
            // that translation from grip history so it cannot be accumulated as a stroke.
            binding.spearMotion.commit(spearSolvedWorld, renderTick);
            binding.lastSpearSourceRear = spearSourceRear;
            binding.lastSpearRear = finalWorld.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
            binding.lastSpearSourceChest = spearSourceChest;
            binding.lastSpearChest = finalWorld.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            binding.lastSpearChestTick = Double.isNaN(binding.lastSpearChestTick) ? renderTick : Math.max(binding.lastSpearChestTick, renderTick);
            binding.lastSpearWorld = finalWorld;
        }
        pose=inspectionPose(binding,living,pose,renderTick);
        if(supportedLower!=null&&binding.inspectionBase!=null&&!binding.inspection.action().isEmpty()){
            float previousDrop=supportedLower.pelvisDropCm();
            supportedLower=InspectionSupport.apply(supportedLower,binding.inspectionBase,pose);
            pose=PresentationMotion.shiftTorso(pose,new PresentationMotion.Balance(0,previousDrop-supportedLower.pelvisDropCm(),0));
        }
        finalWorld=binding.asset.full().worldMatrices(pose);
        renderSamples++; lastSampleTick = renderTick;
        binding.sampledRenderFrame=renderFrameId;
        binding.sampled = airFrame(entity,server,new Frame(binding.asset.full(), binding.asset.weapon(), pose, finalWorld,
                lowerWeight, sample.movingWeight(), renderTick, sample.layer(),
                supportedLower == null ? 0 : supportedLower.right().knee(),
                supportedLower == null ? 0 : supportedLower.left().knee(), hipBalance, supportedLower),state);
        return binding.sampled;
    }
    private static Frame airFrame(net.minecraft.world.entity.Entity entity,RpgAnimationPayload intent,Frame current,AvatarRenderState state) {
        boolean inAir=entity.isAlive()&&!entity.onGround()&&!entity.isPassenger()&&!entity.isInWater()&&!state.isFallFlying&&state.swimAmount<=.5f;
        var air=airPoses.computeIfAbsent(entity.getUUID(),id->new AirPose.State<Frame>());
        String airKey=intent.style()+"|"+System.identityHashCode(current.model())+"|"+state.skin.model()+"|"+entity.getId();
        Frame selected=air.sample(airKey,inAir,intent.phase(),current);
        if(inAir&&(AirPose.attack(intent.phase())||intent.phase().equals("GUARDING"))&&air.takeoff()!=null){
            // Continue the authored attack above the waist. Air is not permission for
            // vanilla legs (or a newly selected fall clip) to replace the takeoff stance.
            Frame lower=air.takeoff();
            // Grounded support includes a common torso drop. Airborne sampling no
            // longer runs that solver, and source root translations may advance;
            // keep the actual waist attached to the retained pelvis while the
            // authored torso rotations and hand/weapon chains continue normally.
            var aligned=AirPoseJoin.align(selected.model(),selected.local(),selected.world(),lower.world());
            var local=new HashMap<>(aligned);var world=new HashMap<>(selected.model().worldMatrices(aligned));
            for(String id:PoseLayers.masks(selected.model().rig()).get("lower")){
                if(lower.local().containsKey(id))local.put(id,lower.local().get(id));
                if(lower.world().containsKey(id))world.put(id,lower.world().get(id));
            }
            selected=new Frame(selected.model(),selected.weapon(),Map.copyOf(local),Map.copyOf(world),lower.lowerBodyWeight(),lower.movingWeight(),
                    current.sampledTick(),selected.layer(),lower.rightKnee(),lower.leftKnee(),selected.balance(),lower.supportedLower());
        }
        if(selected!=current)selected=new Frame(selected.model(),selected.weapon(),selected.local(),selected.world(),selected.lowerBodyWeight(),selected.movingWeight(),
                current.sampledTick(),selected.layer(),selected.rightKnee(),selected.leftKnee(),selected.balance(),selected.supportedLower());
        // Retain only the undecorated basis. Feeding the final inertia frame back
        // into AirPose would integrate a cosmetic rotation on every render frame.
        air.presented(selected);
        if(!entity.isAlive()){airInertias.remove(entity.getUUID());airOffsets.remove(entity.getUUID());return selected;}
        var inertia=airInertias.computeIfAbsent(entity.getUUID(),id->new AirInertia.State());
        inertia.observeMotion(entity.tickCount,entity.getX(),entity.getY(),entity.getZ(),
                entity.getX()-entity.xo,entity.getY()-entity.yo,entity.getZ()-entity.zo);
        float partial=Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float yaw=entity instanceof net.minecraft.world.entity.LivingEntity living
                ?net.minecraft.util.Mth.rotLerp(partial,living.yBodyRotO,living.yBodyRot):entity.getYRot();
        var offset=inertia.sample(airKey,current.sampledTick(),partial,inAir,intent.phase(),yaw,ClientOptions.AIR_INERTIA_STRENGTH.get().floatValue());
        airOffsets.put(entity.getUUID(),offset);
        if(!offset.active())return selected;
        var moved=AirInertia.apply(selected.model(),selected.local(),selected.world(),selected.supportedLower(),offset);
        return new Frame(selected.model(),selected.weapon(),moved.local(),moved.world(),selected.lowerBodyWeight(),selected.movingWeight(),
                current.sampledTick(),selected.layer(),selected.rightKnee(),selected.leftKnee(),selected.balance(),moved.lower());
    }
    public static AirInertia.Offset airInertiaOffset(UUID entity){return airOffsets.getOrDefault(entity,AirInertia.Offset.ZERO);}
    public static boolean airbornePoseHeld(UUID entity){var pose=airPoses.get(entity);return pose!=null&&pose.holding();}
    private static SpearTurn.Settings turnSettings() {
        return new SpearTurn.Settings(ClientOptions.SPEAR_PELVIS_RESPONSE.get().floatValue(),ClientOptions.SPEAR_TURN_STEP_TIME.get().floatValue(),
                (float)Math.toRadians(ClientOptions.SPEAR_TURN_STEP_ANGLE.get()),ClientOptions.SPEAR_TURN_STEP_LIFT.get().floatValue()/6.25f,
                (float)Math.toRadians(ClientOptions.WAIST_TWIST_LIMIT.get()),ClientOptions.TURN_ROOT_FOLLOW.get().floatValue());
    }
    /** A detached editor state may use real skin/armor/items without mutating live actor playback. */
    public static void preview(AvatarRenderState state, ModelDocument document, Map<String, Pose> source) {
        if(LegacyMotion.applies(document)) {
            if (SwordMotion.applies(document)) source = SwordMotion.waist(document, source);
            var pose=retargetSkin(state,document,Map.copyOf(source));
            previews.put(state,new Frame(document,null,pose,document.worldMatrices(pose),1,0,now(),"full",
                    LegacyMotion.knee(pose,"right"),LegacyMotion.knee(pose,"left"),new PresentationMotion.Balance(0,0,0),null));
            return;
        }
        if(document.rig().id().equals("fsrpg.dual_blade"))source=PoseLayers.dualElbows(source);
        Map<String, Pose> pose = retargetSkin(state, document,Map.copyOf(source));
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
    private static Binding advancePending(UUID entity, Binding binding, double tick) {
        if(binding.pendingAction==null||!binding.pendingAction.ready(tick))return binding;
        var pending=binding.pendingAction;var state=pending.value();
        var next=nextBinding(state,binding.asset,binding,tick,
                pending.elapsed(tick,state.windupTicks(),state.activeTicks(),state.recoveryTicks()),pending.phaseReceivedTick());
        next.received=pending.receivedTick();bindings.put(entity,next);return next;
    }
    /** The FP viewmodel shares the same authoritative action clock, sampled every rendered frame. */
    static RpgAnimationPayload localIntent() {
        world(); var player=Minecraft.getInstance().player;
        Binding binding=player==null?null:bindings.get(player.getUUID());
        return binding==null?null:advancePending(player.getUUID(),binding,now()).state;
    }
    static RpgAnimationPayload intent(UUID player) { Binding binding=bindings.get(player);return binding==null?null:binding.state; }
    static double localSeconds() {
        return localSeconds(false);
    }
    static double localElapsedTicks() {
        var player=Minecraft.getInstance().player;var b=player==null?null:bindings.get(player.getUUID());
        return b==null?-1:b.sampledElapsedTicks;
    }
    static double localSeconds(boolean firstPerson) {
        var player=Minecraft.getInstance().player;Binding b=player==null?null:bindings.get(player.getUUID());
        if(b==null)return 0;
        double renderTick=now(),elapsed=PoseLayers.attackElapsed(b.state.phase(),b.state.phaseRemaining(),b.state.windupTicks(),b.state.activeTicks(),b.state.recoveryTicks());
        if(elapsed>=0)elapsed=b.attackClock.sample(renderTick);
        // A first-person camera does not submit the local avatar. It must present
        // and release the same protected contact endpoint itself, or a queued
        // action waits forever for an invisible third-person model to sample it.
        if(b.pendingAction!=null)elapsed=b.pendingAction.holdElapsed(elapsed,renderTick);
        b.sampledElapsedTicks=elapsed;
        var clip=b.asset.full().rig().clips().get(b.state.action());
        return elapsed>=0&&RapierMotion.cut(clip)?RapierMotion.clipTime(clip,elapsed,b.state.windupTicks(),b.state.activeTicks(),b.state.recoveryTicks())
                :LegacyMotion.actionSeconds(clip,b.state,elapsed,Math.max(0,renderTick-b.started)/20,firstPerson);
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

    private static Map<String, Pose> retargetSkin(AvatarRenderState state,ModelDocument model, Map<String, Pose> pose) {
        return LegacyWaistFit.apply(model,PlayerShoulderFit.apply(model,pose,state.skin.model()==PlayerModelType.SLIM));
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
    /**
     * Guard is a staff hold, not an aim: pick a chest-height lateral shaft line and place both
     * hands on it with a closed two-bone solve, then turn each palm onto that shaft. The official
     * spear carrier follows the rear palm's -Y, so the pole is rendered across the body.
     */
    private static Map<String, Pose> spearGuardHands(Map<String, Pose> pose, ModelDocument model) {
        if (!pose.containsKey("body") || !pose.containsKey("right_arm") || !pose.containsKey("left_arm")) return pose;
        var world = model.worldMatrices(pose);
        Matrix4f body = world.get("body");
        if (body == null) return pose;
        Vector3f up = body.transformDirection(new Vector3f(0, 1, 0)).normalize();
        Vector3f forward = body.transformDirection(new Vector3f(0, 0, 1)).normalize();
        Vector3f lateral = body.transformDirection(new Vector3f(1, 0, 0));
        lateral.y = 0;
        if (lateral.lengthSquared() < 1e-6) return pose;
        lateral.normalize();
        Matrix4f rightArm = world.get("right_arm"), leftArm = world.get("left_arm");
        if (rightArm == null || leftArm == null) return pose;
        Vector3f rightShoulder = rightArm.getTranslation(new Vector3f());
        Vector3f leftShoulder = leftArm.getTranslation(new Vector3f());
        Vector3f center = new Vector3f(rightShoulder).add(leftShoulder).mul(.5f).fma(28, forward).fma(-8, up);
        Vector3f rightTarget = new Vector3f(center).fma(-27, lateral);
        Vector3f leftTarget = new Vector3f(center).fma(27, lateral);
        Quaternionf bodyRotation = new Quaternionf(body.getUnnormalizedRotation(new Quaternionf())).normalize();
        var result = new HashMap<>(pose);
        if (!guardArm(result, world, "right", bodyRotation, rightTarget, lateral, up)) return pose;
        var after = result;
        var world2 = model.worldMatrices(after);
        if (!guardArm(result, world2, "left", bodyRotation, leftTarget, new Vector3f(lateral).negate(), up)) return pose;
        return Map.copyOf(result);
    }

    /** Closed two-bone arm solve with a chest-forward elbow pole, then palm alignment to the shaft. */
    private static boolean guardArm(Map<String, Pose> pose, Map<String, Matrix4f> world, String side,
                                    Quaternionf bodyRotation, Vector3f target, Vector3f shaftDirection, Vector3f up) {
        String upperId = side + "_arm", foreId = side + "_forearm", handId = side + "_hand";
        Matrix4f upper = world.get(upperId), fore = world.get(foreId), hand = world.get(handId);
        if (upper == null || fore == null || hand == null) return false;
        Vector3f shoulder = upper.getTranslation(new Vector3f());
        Vector3f elbow = fore.getTranslation(new Vector3f());
        Vector3f wrist = hand.getTranslation(new Vector3f());
        float upperLength = shoulder.distance(elbow);
        float foreLength = elbow.distance(wrist);
        if (upperLength < 1e-3 || foreLength < 1e-3) return false;
        Vector3f toTarget = new Vector3f(target).sub(shoulder);
        float distance = toTarget.length();
        if (distance < 1e-4) return false;
        float clamped = Math.clamp(distance, Math.abs(upperLength - foreLength) + .01f, upperLength + foreLength - .01f);
        Vector3f direction = new Vector3f(toTarget).normalize();
        float along = (upperLength * upperLength - foreLength * foreLength + clamped * clamped) / (2 * clamped);
        float height = (float) Math.sqrt(Math.max(0, upperLength * upperLength - along * along));
        Vector3f pole = new Vector3f(up).fma(-new Vector3f(up).dot(direction), direction);
        if (pole.lengthSquared() < 1e-6) pole = new Vector3f(direction).cross(0, 0, 1);
        pole.normalize();
        Vector3f newElbow = new Vector3f(shoulder).fma(along, direction).fma(height, pole);
        Vector3f desiredUpper = new Vector3f(newElbow).sub(shoulder).normalize();
        Vector3f currentUpper = new Vector3f(elbow).sub(shoulder).normalize();
        Quaternionf upperLocal = rotateBone(upperId, pose, bodyRotation, currentUpper, desiredUpper);
        if (upperLocal == null) return false;
        Quaternionf newUpperWorld = new Quaternionf(bodyRotation).mul(upperLocal).normalize();
        Vector3f desiredFore = new Vector3f(target).sub(newElbow).normalize();
        Vector3f currentFore = new Vector3f(wrist).sub(elbow).normalize();
        Quaternionf foreLocal = rotateBone(foreId, pose, newUpperWorld, currentFore, desiredFore);
        if (foreLocal == null) return false;
        Quaternionf newForeWorld = new Quaternionf(newUpperWorld).mul(foreLocal).normalize();
        // Align the actual carrier shaft, not an assumed palm axis: the official spear display
        // transform puts the long axis on a diagonal inside the item model.
        Vector3f itemAxis = new Vector3f(0, -1, 0);
        if (side.equals("right")) {
            // Texture-space shaft tip is upper-left; with the generated-item UV origin this maps to
            // model (-X,-Y). The authored guard pose points it at body forward (dot 0.91), so this is
            // the axis that must be turned onto the body's lateral line for a staff guard.
            var carrier = HeldItemTransform.spearCarrier(hand);
            Vector3f measured = carrier.transformDirection(new Vector3f(-1, -1, 0).normalize());
            if (measured.lengthSquared() > 1e-6) itemAxis = measured.normalize();
        }
        Quaternionf forearmBefore = new Quaternionf(fore.getUnnormalizedRotation(new Quaternionf())).normalize();
        Quaternionf carried = new Quaternionf(newForeWorld).mul(new Quaternionf(forearmBefore).invert());
        Vector3f rotatedAxis = carried.transform(new Vector3f(itemAxis)).normalize();
        Quaternionf handLocal = rotateBone(handId, pose, newForeWorld, rotatedAxis, shaftDirection);
        if (handLocal != null && side.equals("right")) {
            Quaternionf handWorld = new Quaternionf(newForeWorld).mul(handLocal).normalize();
            Vector3f achieved = handWorld.transform(new Vector3f(itemAxis)).normalize();
            guardWristHeightCm = target.y;
            guardReachErrorCm = (float) (new Vector3f(newElbow).fma(foreLength, desiredFore).distance(target));
        }
        return handLocal != null;
    }
    private static volatile float guardWristHeightCm = Float.NaN, guardReachErrorCm = Float.NaN;
    /** Read-only DISPLAY5 acceptance data: both-hand reach and chest height. */
    public static float guardWristHeightCm() { return guardWristHeightCm; }
    public static float guardReachErrorCm() { return guardReachErrorCm; }

    private static Quaternionf rotateBone(String id, Map<String, Pose> pose, Quaternionf parentRotation,
                                          Vector3f currentWorldDirection, Vector3f desiredWorldDirection) {
        Pose original = pose.get(id);
        if (original == null || currentWorldDirection.lengthSquared() < 1e-12) return null;
        Quaternionf delta = new Quaternionf().rotationTo(currentWorldDirection.normalize(), desiredWorldDirection.normalize());
        Quaternionf currentLocal = new Quaternionf(original.fsQuaternion()).normalize();
        Quaternionf local = new Quaternionf(parentRotation).invert().mul(delta).mul(parentRotation).mul(currentLocal).normalize();
        pose.put(id, new Pose(FsvoxAnimation.fromQuaternion(local), original.position(), original.scale()));
        return local;
    }
    private static void supportedLeg(ModelPart part, Locomotion.PartPose pose) {
        part.setPos(pose.x(), pose.y(), pose.z());
        part.setRotation(pose.xRot(), pose.yRot(), pose.zRot());
    }
}

package org.fsrpg.movement;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fsrpg.network.RpgFootworkMotionPayload;
import org.fsrpg.progression.*;

/**
 * Server-authoritative historical footwork (0.1.1 ActionCombat): Rabbit / Flying Swan schools and
 * the Cloud Step / Light Body techniques. Clients only send intent; every distance, impulse,
 * invulnerability window, cooldown, stomp target and wall kick is decided here.
 */
public final class FootworkServer {
    private static final Set<String> KINDS = Set.of("style", "airJump", "highJump", "special");
    private static final Set<String> FIELDS = Set.of("kind", "request", "tick", "entity", "dimension", "x", "z");
    private final ProgressionService progression;
    private final Supplier<FootworkRules> rules;
    private final BiConsumer<ServerPlayer, Integer> forced;
    private final Map<UUID, FootworkState> states = new HashMap<>();
    private final Map<UUID, Long> lastGroundTick = new HashMap<>();
    /**
     * Set while this class calls {@code jumpFromGround()} itself. The vanilla jump event only
     * records its timing then; the footwork jump rebuilds horizontal motion and applies Light Body
     * exactly once, so the event must not add a second impulse.
     */
    private boolean rebuildingJump;

    public FootworkServer(ProgressionService progression, Supplier<FootworkRules> rules,
                          BiConsumer<ServerPlayer, Integer> forced) {
        this.progression = progression;
        this.rules = rules;
        this.forced = forced;
    }
    private FootworkState state(ServerPlayer p) { return states.computeIfAbsent(p.getUUID(), id -> new FootworkState()); }
    private FootworkRules rules() { return rules.get(); }

    /** Per-player transient clocks; real landing (false -> true) is the only air-jump reset. */
    public void tick(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator()) { remove(p.getUUID()); return; }
        FootworkState s = state(p);
        s.ground(p.onGround());
        if (p.onGround()) lastGroundTick.put(p.getUUID(), p.level().getGameTime());
    }
    public void remove(UUID id) {
        states.remove(id);
        lastGroundTick.remove(id);
    }
    /**
     * Old JumpTiming rule: every real player jump (vanilla or footwork) needs four ticks since the
     * previous one. Called from the {@code jumpFromGround} mixin before the launch is committed.
     */
    public boolean canJump(ServerPlayer p) {
        return state(p).jumpReady(p.level().getGameTime(), rules());
    }
    /** Vanilla and footwork jumps share the old 4-tick interval; Light Body II adds one impulse per jump. */
    public void onJump(ServerPlayer p) {
        FootworkState s = state(p);
        s.onJump(p.level().getGameTime());
        if (rebuildingJump || !FootworkPhysics.eligible(p)) return;
        ProgressionService.FootworkView view = progression.footworkView(p.getUUID());
        if (!view.enabled() || view.lightTier() < 2) return;
        applyImpulse(p, FootworkPhysics.directionOr(p, FootworkPhysics.intentDirection(p)), rules().jumpImpulseMps());
    }
    /** Rabbit's damage immunity window. Only the old event-level sources reach it (see RpgServer). */
    public boolean invulnerable(ServerPlayer p) {
        FootworkState s = states.get(p.getUUID());
        return s != null && s.invulnerable(p.level().getGameTime()) && progression.footworkView(p.getUUID()).enabled();
    }
    /** True while a fresh Rabbit dash or Flying Swan window allows the flash-thrust combination. */
    public boolean combinationWindow(ServerPlayer p) {
        FootworkState s = states.get(p.getUUID());
        return s != null && s.combinationWindow(p.level().getGameTime())
                && progression.footworkView(p.getUUID()).enabled();
    }
    /** Consumes the 5 s flash-thrust cooldown; call only after a real rapier-thrust contact. */
    public boolean takeFlashThrust(ServerPlayer p) {
        FootworkState s = states.get(p.getUUID());
        return s != null && progression.footworkView(p.getUUID()).enabled()
                && s.takeCombination(p.level().getGameTime(), rules());
    }
    /** Light Body II negates fall damage through the old fall event, never through global immunity. */
    public boolean fallImmune(ServerPlayer p) {
        ProgressionService.FootworkView view = progression.footworkView(p.getUUID());
        return view.enabled() && view.lightTier() >= 2;
    }
    public JsonObject snapshot(ServerPlayer p) {
        FootworkState s = state(p);
        long now = p.level().getGameTime();
        JsonObject json = new JsonObject();
        json.addProperty("jumpsUsed", s.jumpsUsed());
        json.addProperty("rabbitCooldownTicks", s.rabbitRemaining(now));
        json.addProperty("swanCooldownTicks", s.swanRemaining(now));
        json.addProperty("specialCooldownTicks", s.specialRemaining(now));
        json.addProperty("combinationCooldownTicks", s.combinationRemaining(now));
        json.addProperty("airDragFactor", rules().airDragFactor());
        json.addProperty("jumpIntervalTicks", rules().jumpIntervalTicks());
        return json;
    }

    /**
     * Validates and executes one client footwork intent. Malformed input throws (the caller reports
     * it); ordinary rejections (cooldown, wrong state) return false without a message.
     */
    public boolean action(ServerPlayer p, String raw) {
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("步法输入须为对象");
        JsonObject o = parsed.getAsJsonObject();
        if (!o.keySet().equals(FIELDS)) throw new IllegalArgumentException("步法输入字段错误");
        String kind = string(o, "kind");
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("未知步法输入");
        long request = integer(o, "request");
        long clientTick = integer(o, "tick");
        long entity = integer(o, "entity");
        String dimension = string(o, "dimension");
        double x = number(o, "x"), z = number(o, "z");
        double length = Math.hypot(x, z);
        if (!Double.isFinite(length) || Math.abs(x) > 1 || Math.abs(z) > 1) throw new IllegalArgumentException("非法步法方向");
        long now = p.level().getGameTime();
        if (request < 1) throw new IllegalArgumentException("非法步法请求");
        if (entity != p.getId() || !dimension.equals(p.level().dimension().identifier().toString())
                || Math.abs(clientTick - now) > 40) return false;
        ProgressionService.FootworkView view = progression.footworkView(p.getUUID());
        FootworkRules r = rules();
        if (!view.enabled() || !FootworkPhysics.eligible(p)) return false;
        FootworkState s = state(p);
        if (!s.request(request)) return false;
        Vec3 clientInput = length > 1.0E-6 ? new Vec3(x / length, 0, z / length) : Vec3.ZERO;
        // The freshest direction is the client's current-frame key vector, but it must agree with
        // the movement input the server already received; a spoofed backwards vector is rejected.
        Vec3 serverIntent = FootworkPhysics.intentDirection(p);
        if (clientInput.lengthSqr() > 1.0E-8 && serverIntent.lengthSqr() > 1.0E-8
                && clientInput.dot(serverIntent) < 0.5) return false;
        Vec3 input = clientInput.lengthSqr() > 1.0E-8 ? clientInput : serverIntent;
        boolean accepted = switch (kind) {
            case "style" -> style(p, s, view, r, input, now);
            case "airJump" -> {
                if (p.onGround() || !s.airJumpReady(view.cloudTier(), now, r)) yield false;
                jump(p, view, r, false, false, input);
                s.jumpAccepted(true, now, r);
                yield true;
            }
            case "highJump" -> {
                // Re-verify the launch conditions immediately before committing the authoritative jump.
                if (view.cloudTier() < 1 || !p.onGround() || !s.jumpReady(now, r)) yield false;
                jump(p, view, r, false, true, input);
                s.jumpAccepted(false, now, r);
                yield true;
            }
            case "special" -> special(p, s, view, r, now);
            default -> false;
        };
        if (accepted) {
            forced.accept(p, 2);
            sendMotion(p, s);
            p.level().sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + .1, p.getZ(), 6, .15, .05, .15, .01);
        }
        return accepted;
    }
    private boolean style(ServerPlayer p, FootworkState s, ProgressionService.FootworkView view, FootworkRules r, Vec3 input, long now) {
        if (view.style() == FootworkStyle.NONE || !s.styleReady(view.style(), now)) return false;
        if (view.style() == FootworkStyle.RABBIT) {
            if (input.lengthSqr() < 1.0E-8) return false; // old Rabbit requires directional input
            if (!rabbit(p, input, r.rabbitDistance())) return false;
        } else {
            Vec3 direction = FootworkPhysics.directionOr(p, input);
            if (direction.lengthSqr() < 1.0E-8) return false;
            Vec3 v = p.getDeltaMovement();
            FootworkMath.Motion next = FootworkMath.swan(new FootworkMath.Motion(v.x, v.y, v.z),
                    direction.x, direction.z, r.flyingSwanCurveLinear());
            p.setDeltaMovement(next.x(), next.y(), next.z());
        }
        s.styleAccepted(view.style(), view.lightTier(), now, r);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS, .6F, view.style() == FootworkStyle.RABBIT ? 1.4F : 1.8F);
        return true;
    }
    /**
     * Rabbit: a one-shot position change that keeps the exact velocity. The real collision path
     * clips the bounding box, pre-checks stop before unloaded chunks and the world border, and the
     * teleport keeps the server and client positions agreed.
     */
    private boolean rabbit(ServerPlayer p, Vec3 direction, double wantedDistance) {
        double distance = safeRabbitDistance(p, direction, wantedDistance);
        if (distance <= 0) return false;
        Vec3 before = p.position();
        Vec3 velocity = p.getDeltaMovement();
        double fallDistance = p.fallDistance;
        p.move(MoverType.SELF, direction.scale(distance));
        Vec3 after = p.position();
        p.setDeltaMovement(velocity);
        p.fallDistance = fallDistance;
        if (after.distanceToSqr(before) < 1.0E-8) return false;
        p.setOldPosAndRot();
        if (p.connection != null) p.connection.teleport(after.x, after.y, after.z, p.getYRot(), p.getXRot());
        p.setDeltaMovement(velocity);
        return true;
    }
    private double safeRabbitDistance(ServerPlayer p, Vec3 direction, double wanted) {
        double distance = 0;
        for (double step = .125; step <= wanted + 1.0E-7; step += .125) {
            double candidate = Math.min(step, wanted);
            Vec3 at = p.position().add(direction.scale(candidate));
            if (!p.level().hasChunkAt(BlockPos.containing(at))) break;
            if (!p.level().getWorldBorder().isWithinBounds(p.getBoundingBox().move(at.subtract(p.position())))) break;
            distance = candidate;
        }
        return distance;
    }
    /**
     * Cloud step II: prefer stomping a visible entity within {@code targetRange}, otherwise kick off
     * a wall within {@code wallRange}; both share one cooldown and neither consumes an air jump.
     */
    private boolean special(ServerPlayer p, FootworkState s, ProgressionService.FootworkView view, FootworkRules r, long now) {
        if (view.cloudTier() < 2 || p.onGround() || !s.specialReady(now, r)) return false;
        LivingEntity target = lookTarget(p, r.targetRange());
        if (target != null) {
            if (target instanceof ServerPlayer other && !p.canHarmPlayer(other)) target = null;
        }
        if (target != null) {
            target.hurtServer(p.level(), p.damageSources().playerAttack(p),
                    (float) Math.ceil(target.getMaxHealth() * r.targetDamageShare()));
            jump(p, view, r, false, false, FootworkPhysics.intentDirection(p));
        } else {
            // The wall must still be there at commit time; no stale client-side hit may kick us.
            if (!wallAhead(p, r.wallRange())) return false;
            jump(p, view, r, true, false, FootworkPhysics.intentDirection(p));
        }
        s.specialAccepted(now, r);
        return true;
    }
    /**
     * Refreshes only the vertical launch. Horizontal sprint impulses never stack on repeated cloud
     * jumps; wall kicks reverse the horizontal velocity exactly. Light Body II adds one authored
     * impulse (the vanilla jump event is muted while this method rebuilds motion).
     */
    private void jump(ServerPlayer p, ProgressionService.FootworkView view, FootworkRules r, boolean wall, boolean high, Vec3 input) {
        rebuildingJump = true;
        try {
            if (high) {
                p.jumpFromGround();
                Vec3 v = p.getDeltaMovement();
                p.setDeltaMovement(v.x, FootworkMath.chargedJumpVelocity(v.y, r.chargedJumpHeightMultiplier()), v.z);
            } else {
                Vec3 before = p.getDeltaMovement();
                p.setDeltaMovement(before.x, 0, before.z);
                p.jumpFromGround();
                double y = p.getDeltaMovement().y;
                p.setDeltaMovement(wall ? -before.x : before.x, y, wall ? -before.z : before.z);
            }
        } finally {
            rebuildingJump = false;
        }
        if (view.lightTier() >= 2) {
            Vec3 launched = p.getDeltaMovement();
            // Wall kicks boost along the already-reversed horizontal velocity; cloud jumps follow
            // the movement keys with velocity/yaw fallbacks.
            Vec3 along = wall
                    ? FootworkPhysics.directionOr(p, new Vec3(launched.x, 0, launched.z))
                    : FootworkPhysics.directionOr(p, input);
            applyImpulse(p, along, r.jumpImpulseMps());
        }
        p.setOnGround(false); // only real landing input restores the finite air-jump allowance
    }
    private static void applyImpulse(ServerPlayer p, Vec3 direction, double mps) {
        Vec3 v = p.getDeltaMovement();
        FootworkMath.Motion next = FootworkMath.impulse(new FootworkMath.Motion(v.x, v.y, v.z), direction.x, direction.z, mps);
        p.setDeltaMovement(next.x(), next.y(), next.z());
    }
    private static boolean wallAhead(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        var hit = p.level().clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(range)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK;
    }
    /** Closest visible living entity under the crosshair within range (block occlusion respected). */
    private static LivingEntity lookTarget(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(range));
        var wall = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double nearest = wall.getType() == HitResult.Type.BLOCK ? eye.distanceToSqr(wall.getLocation()) : Double.MAX_VALUE;
        AABB box = new AABB(eye, end).inflate(.3);
        LivingEntity selected = null;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != p && e.isAlive() && !e.isSpectator())) {
            var hit = e.getBoundingBox().inflate(.3).clip(eye, end);
            if (hit.isEmpty()) continue;
            double distance = eye.distanceToSqr(hit.get());
            if (distance <= nearest) { nearest = distance; selected = e; }
        }
        return selected;
    }
    /** Authoritative velocity packet; clients apply it but never decide it. */
    private static void sendMotion(ServerPlayer p, FootworkState s) {
        Vec3 v = p.getDeltaMovement();
        PacketDistributor.sendToPlayer(p, new RpgFootworkMotionPayload(p.getUUID(), p.getId(),
                p.level().dimension().identifier().toString(), s.nextSequence(), p.level().getGameTime(),
                true, v.x, v.y, v.z));
    }
    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid " + key);
        return v.getAsString();
    }
    private static double number(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid " + key);
        double value = v.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private static long integer(JsonObject o, String key) {
        number(o, key);
        try { return o.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid " + key); }
    }
}

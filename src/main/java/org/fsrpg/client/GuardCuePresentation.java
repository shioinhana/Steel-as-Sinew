package org.fsrpg.client;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.fsrpg.creature.GuardCueClock;
import org.fsrpg.network.RpgGuardCuePayload;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One gold star at the attacking creature; neither screen flashes nor client-side success prediction are used. */
public final class GuardCuePresentation {
    private static final int MAX_TRACKED = 256;
    private static final LinkedHashMap<UUID, Tracked> CLOCKS = new LinkedHashMap<>();
    private static Object world;
    private static long renderedFrames, visibleFlashes;
    private static Frame lastFrame;
    private static final CopyOnWriteArrayList<Consumer<Frame>> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Consumer<RpgGuardCuePayload>> PACKET_OBSERVERS = new CopyOnWriteArrayList<>();

    private static final class Tracked {
        final GuardCueClock clock = new GuardCueClock();
        LivingEntity entity;
        long lastVisibleSequence = -1;
    }
    /** Read-only native-render evidence, also useful to the packaged client review. */
    public record Frame(UUID entityId, long sequence, double serverGameTime, long flashGameTime,
                        long openGameTime, long closeGameTime, long contactGameTime,
                        float screenX, float screenY, double progress, long nativeNanoTime) {}

    private GuardCuePresentation() {}

    public static void accept(RpgGuardCuePayload payload) {
        var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || !game.player.getUUID().equals(payload.targetId())
                || payload.entityId() == null) return;
        enterWorld(game.level);
        var message = new GuardCueClock.Message(payload.sequence(), payload.phase(), payload.startedGameTime(), payload.serverGameTime(),
                payload.flashGameTime(), payload.openGameTime(), payload.closeGameTime(), payload.contactGameTime(), payload.flashDurationMillis());
        if (!CLOCKS.containsKey(payload.entityId()) && CLOCKS.size() >= MAX_TRACKED) CLOCKS.remove(CLOCKS.keySet().iterator().next());
        var tracked = CLOCKS.computeIfAbsent(payload.entityId(), ignored -> new Tracked());
        if (tracked.clock.accept(message, game.level.getGameTime())) {
            if (tracked.entity == null) tracked.entity = find(payload.entityId());
            for (var observer : PACKET_OBSERVERS) observer.accept(payload);
        }
    }

    public static void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || !game.player.isAlive()) { clear(); return; }
        enterWorld(game.level);
        for (var entry : CLOCKS.entrySet()) {
            var tracked = entry.getValue();
            if (tracked.clock.terminal()) continue;
            if (tracked.entity == null) tracked.entity = find(entry.getKey());
        }
    }

    public static void render(RenderGuiEvent.Post event) {
        var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || !game.player.isAlive()) { clear(); return; }
        enterWorld(game.level);
        lastFrame = null;
        float partial = game.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        boolean visible = !game.options.hideGui && !game.player.isSpectator() && game.getCameraEntity() == game.player && game.screen == null;
        for (var entry : CLOCKS.entrySet()) {
            var tracked = entry.getValue();
            // Sample even while a menu or F1 hides presentation: expiry must not replay when HUD returns.
            var sample = tracked.clock.sample(game.level.getGameTime(), partial);
            if (sample == null || !visible || tracked.entity == null || !tracked.entity.isAlive() || tracked.entity.isRemoved()) continue;
            var point = anchor(tracked.entity, partial);
            var screen = project(point, event.getGuiGraphics());
            if (screen == null || !unoccluded(point)) continue;
            boolean sword=ClientState.bool(ClientState.snapshot(),"swordCombatEnabled",false);
            boolean heavy=false;
            if(sword){
                var types=ClientState.object(ClientState.snapshot(),"swordCombatRules").get("heavyMobTypes");
                String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(tracked.entity.getType()).toString();
                if(types!=null&&types.isJsonArray())for(var id:types.getAsJsonArray())if(id.isJsonPrimitive()&&type.equals(id.getAsString()))heavy=true;
            }
            draw(event.getGuiGraphics(), screen.x, screen.y, sample.progress(), screen.z,sword,heavy);
            renderedFrames++;
            if (tracked.lastVisibleSequence != sample.sequence()) { tracked.lastVisibleSequence = sample.sequence(); visibleFlashes++; }
            lastFrame = new Frame(entry.getKey(), sample.sequence(), sample.serverGameTime(), sample.flashGameTime(), sample.openGameTime(),
                    sample.closeGameTime(), sample.contactGameTime(), screen.x, screen.y, sample.progress(), System.nanoTime());
            for (var observer : OBSERVERS) observer.accept(lastFrame);
        }
    }

    private static LivingEntity find(UUID id) {
        var level = Minecraft.getInstance().level;
        if (level != null) for (var entity : level.entitiesForRendering())
            if (entity instanceof LivingEntity living && entity.getUUID().equals(id)) return living;
        return null;
    }

    private static Vec3 anchor(LivingEntity entity, float partial) {
        // Attach to the upper chest on the right/weapon side; small creatures remain readable too.
        double yaw = Math.toRadians(Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot));
        double side = Math.min(.32, entity.getBbWidth() * .36);
        return entity.getPosition(partial).add(Math.cos(yaw) * side,
                Math.max(.3, entity.getBbHeight() * .76), Math.sin(yaw) * side);
    }

    private static Vector3f project(Vec3 point, GuiGraphics gui) {
        var game = Minecraft.getInstance();
        var camera = game.gameRenderer.getMainCamera();
        Vec3 relative = point.subtract(camera.position());
        var local = new Vector3f((float) relative.x, (float) relative.y, (float) relative.z)
                .rotate(new Quaternionf(camera.rotation()).conjugate());
        if (local.z >= -.05f) return null;
        float fov = ((org.fsrpg.mixin.GameRendererAccess) game.gameRenderer).fsrpg$fov(camera,
                game.getDeltaTracker().getGameTimeDeltaPartialTick(false), true);
        double scale = gui.guiHeight() / (2 * Math.tan(Math.toRadians(fov) / 2) * -local.z);
        float x = (float) (gui.guiWidth() * .5 + local.x * scale), y = (float) (gui.guiHeight() * .5 - local.y * scale);
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 4 || x > gui.guiWidth() - 4 || y < 4 || y > gui.guiHeight() - 4) return null;
        return new Vector3f(x, y, (float) Math.clamp(.85 + 1.5 / -local.z, .9, 1.45));
    }

    private static boolean unoccluded(Vec3 point) {
        var game = Minecraft.getInstance();
        return game.level.clip(new ClipContext(game.gameRenderer.getMainCamera().position(), point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, game.player)).getType() == HitResult.Type.MISS;
    }

    private static void draw(GuiGraphics gui, float x, float y, double progress, float scale,boolean sword,boolean heavy) {
        // One attack-release envelope, never a blinking loop. Milliseconds are supplied independently of guard eligibility.
        double opacity = Math.min(1, .4 + progress * 6) * Math.pow(1 - progress, .7);
        int accent=sword?(heavy?0x49bcff:0xdddddd):0xffc74e;
        int gold = (int) Math.round(235 * opacity) << 24 | accent;
        int cream = (int) Math.round(255 * opacity) << 24 | 0xfff6ce;
        float pulse = (float) (scale * (1 + .2 * Math.sin(Math.PI * progress)));
        var pose = gui.pose(); pose.pushMatrix(); pose.translate(x, y); pose.scale(pulse, pulse);
        int ringRadius = (int) Math.round(10 + progress * 10);
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            int px = (int) Math.round(Math.cos(a) * ringRadius), py = (int) Math.round(Math.sin(a) * ringRadius);
            gui.fill(px, py, px + 1, py + 1, (int) Math.round(120 * opacity) << 24 | accent);
        }
        for (int py = -18; py <= 18; py++) {
            double at = Math.abs(py);
            int half = (int) Math.max(at < 4 ? Math.sqrt(16 - at * at) : 0,
                    20 * Math.pow(Math.max(0, 1 - at / 18), 5));
            if (half == 0 && at > 15) continue;
            gui.fill(-half - 1, py, half + 2, py + 1, gold);
        }
        gui.fill(-2, -6, 3, 7, cream); gui.fill(-6, -2, 7, 3, cream);
        pose.popMatrix();
        if(sword){
            String hint=heavy?ClientHooks.parryKeyLabel()+" 振刀":ClientHooks.guardKeyLabel()+" 格挡";
            gui.drawString(Minecraft.getInstance().font,hint,(int)x-Minecraft.getInstance().font.width(hint)/2,(int)y+22,0xff000000|accent);
        }
    }

    public static Frame lastFrame() { return lastFrame; }
    public static AutoCloseable observeFrames(Consumer<Frame> observer) {
        java.util.Objects.requireNonNull(observer); OBSERVERS.add(observer); return () -> OBSERVERS.remove(observer);
    }
    public static AutoCloseable observePackets(Consumer<RpgGuardCuePayload> observer) {
        java.util.Objects.requireNonNull(observer); PACKET_OBSERVERS.add(observer); return () -> PACKET_OBSERVERS.remove(observer);
    }
    public static long renderedFrames() { return renderedFrames; }
    public static long visibleFlashes() { return visibleFlashes; }
    public static void clear() { CLOCKS.clear(); world = null; lastFrame = null; renderedFrames = visibleFlashes = 0; }
    private static void enterWorld(Object next) { if (world != next) { clear(); world = next; } }
}

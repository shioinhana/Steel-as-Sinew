package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import org.fsrpg.movement.FootworkPhysics;
import org.fsrpg.network.RpgFootworkMotionPayload;

/**
 * Edge-only intent for the historical footwork branch. The client never decides a distance,
 * cooldown, invulnerability window, stomp target or impulse: it sends direction edges and applies
 * the authoritative motion that comes back.
 */
public final class FootworkClient {
    private static LocalPlayer owner;
    private static Object level;
    private static boolean styleWasDown, specialWasDown, jumpWasDown;
    private static long request, sequence;
    private FootworkClient() {}

    public static void install() {
        FootworkPhysics.clientDrag = p -> {
            var game = Minecraft.getInstance();
            if (p != game.player) return 1;
            JsonObject view = footworkView();
            if (!ClientState.bool(view, "enabled", false) || ClientState.number(view, "lightTier", 0) < 1) return 1;
            double factor = ClientState.number(ClientState.object(ClientState.snapshot(), "footworkMotion"), "airDragFactor", 1);
            return Double.isFinite(factor) && factor >= 0 && factor <= 1 ? factor : 1;
        };
    }
    private static void context() {
        var game = Minecraft.getInstance();
        if (owner != game.player || level != game.level) { reset(); owner = game.player; level = game.level; }
    }
    private static JsonObject footworkView() {
        return ClientState.object(ClientState.object(ClientState.snapshot(), "progression"), "footwork");
    }
    /**
     * B = school trigger, G = airborne special / charged-jump modifier, Space keeps its vanilla
     * meaning except that a charged jump owns it while G is held on the ground.
     */
    public static void input(MovementInputUpdateEvent event, boolean styleDown, boolean specialDown) {
        var game = Minecraft.getInstance();
        if (game.player == null || event.getEntity() != game.player) return;
        context();
        boolean jumpDown = game.options.keyJump.isDown();
        boolean styleEdge = styleDown && !styleWasDown;
        boolean specialEdge = specialDown && !specialWasDown;
        boolean jumpEdge = jumpDown && !jumpWasDown;
        styleWasDown = styleDown; specialWasDown = specialDown; jumpWasDown = jumpDown;
        JsonObject view = footworkView();
        boolean enabled = ClientState.bool(view, "enabled", false);
        int cloud = (int) ClientState.number(view, "cloudTier", 0);
        String style = ClientState.text(view, "style", "NONE");
        if (game.screen == null && enabled && specialDown && cloud > 0 && game.player.onGround()) {
            // The server performs the charged jump; the local character must not also consume the
            // vanilla jump or the two impulses would stack.
            Input keys = event.getInput().keyPresses;
            event.getInput().keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(),
                    false, keys.shift(), keys.sprint());
        }
        if (game.screen != null || !game.isWindowActive() || !FootworkPhysics.eligible(game.player)) return;
        if (!enabled) return;
        Vec2 move = game.player.input.getMoveVector();
        if (styleEdge && !style.equals("NONE")) send("style", move);
        if (jumpEdge) {
            if (game.player.onGround()) {
                if (specialDown && cloud > 0) send("highJump", move);
            } else if (cloud > 0) {
                send("airJump", move);
            }
        }
        if (specialEdge && !game.player.onGround() && cloud >= 2) send("special", move);
    }
    public static void send(String kind) {
        var game = Minecraft.getInstance();
        Vec2 move = game.player == null ? Vec2.ZERO : game.player.input.getMoveVector();
        send(kind, move);
    }
    private static void send(String kind, Vec2 move) {
        context();
        var game = Minecraft.getInstance();
        if (game.player == null || game.level == null) return;
        Vec3 direction = worldDirection(move);
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("request", ++request);
        o.addProperty("tick", game.level.getGameTime());
        o.addProperty("entity", game.player.getId());
        o.addProperty("dimension", game.level.dimension().identifier().toString());
        o.addProperty("x", direction.x);
        o.addProperty("z", direction.z);
        if (game.getConnection() != null)
            game.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                    game.player.getYRot(), game.player.getXRot(), game.player.onGround(), game.player.horizontalCollision));
        ClientHooks.send("footwork", o.toString());
    }
    /** Raw movement-key direction (zero when no key is held) so Rabbit can require real input. */
    private static Vec3 worldDirection(Vec2 move) {
        var game = Minecraft.getInstance();
        if (game.player == null || move.lengthSquared() < .001f) return Vec3.ZERO;
        var world = OrbitMovement.relative(move.x, move.y, game.player.getYRot(), 0);
        Vec3 direction = new Vec3(world.left(), 0, world.forward());
        return direction.lengthSqr() < 1.0E-8 ? Vec3.ZERO : direction.normalize();
    }
    /** Applies one authoritative velocity change; duplicates, stale or spoofed packets are ignored. */
    public static void accept(RpgFootworkMotionPayload p) {
        context();
        var game = Minecraft.getInstance();
        if (game.player == null || game.level == null) return;
        if (!game.player.getUUID().equals(p.playerId()) || game.player.getId() != p.entityId()
                || !game.level.dimension().identifier().toString().equals(p.dimension())
                || p.sequence() <= sequence
                || Math.abs((double) game.level.getGameTime() - p.serverTick()) > 40
                || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())
                || Math.abs(p.x()) > 1.0E4 || Math.abs(p.y()) > 1.0E4 || Math.abs(p.z()) > 1.0E4
                || !game.player.isAlive()) return;
        sequence = p.sequence();
        double y = p.vertical() ? p.y() : game.player.getDeltaMovement().y;
        game.player.setDeltaMovement(p.x(), y, p.z());
        if (p.vertical() && p.y() > 0) game.player.setOnGround(false);
    }
    public static void reset() {
        owner = null; level = null;
        styleWasDown = specialWasDown = jumpWasDown = false;
        request = sequence = 0;
    }
}

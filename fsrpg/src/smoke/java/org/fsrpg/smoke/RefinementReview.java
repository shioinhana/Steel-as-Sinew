package org.fsrpg.smoke;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientHooks;
import org.fsrpg.client.ClientState;
import org.fsrpg.combat.CombatPhase;
import org.fsrpg.combat.CombatResolver;
import org.fsrpg.combat.HitOutcome;
import org.lwjgl.glfw.GLFW;

/** Real client key maps, network requests and a vanilla arrow, on the copied test world only. */
final class RefinementReview {
    private int tick;
    private volatile Arrow arrow;
    boolean tick() {
        var game = Minecraft.getInstance();
        if (tick >= 20 && tick < 42) {
            game.options.keyShift.setDown(tick < 35);
            RpgSmoke.guardKey(true);
        }
        switch (tick) {
            case 0 -> {
                game.options.hideGui = false;
                ClientHooks.send("draw", "");
                RpgSmoke.server(p -> { p.connection.teleport(.5, 80, .5, 0, 0); RpgSmoke.actor(p).setResources(100, 100); });
            }
            case 12 -> game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            case 28 -> {
                RpgSmoke.check(ClientState.text(ClientState.snapshot(), "phase", "").equals("GUARDING")
                        && ClientState.text(ClientState.snapshot(), "guardMode", "").equals("HEAVY"), "actual Shift+E enters heavy guard over network");
                RpgSmoke.check(game.screen == null, "heavy guard modifier keeps inventory closed");
                RpgSmoke.screenshot("refinement-heavy-guard");
                RpgSmoke.server(p -> {
                    var a = RpgSmoke.actor(p); a.setResources(100, 100);
                    var hit = CombatResolver.receiveIncoming(null, a, 55, 10, .3, true, true, false);
                    RpgSmoke.check(hit.outcome() == HitOutcome.BLOCKED && Math.abs(hit.damage() - 2) < .0001 && Math.abs(a.stamina() - 30) < .0001,
                            "integrated server uses heavy guard angle, reduction and stamina cost");
                    RpgServer.instance().action(p, "status", "");
                });
            }
            case 38 -> {
                RpgSmoke.check(ClientState.text(ClientState.snapshot(), "guardMode", "").equals("LIGHT"), "releasing Shift while holding E selects light guard");
                RpgSmoke.screenshot("refinement-light-guard");
                RpgSmoke.server(p -> {
                    var a = RpgSmoke.actor(p);
                    RpgSmoke.check(!a.inPerfectGuardWindow() && a.defenseLimitReached(), "mode change preserves entry clock and pressure history on server");
                });
            }
            case 42 -> { RpgSmoke.guardKey(false); game.options.keyShift.setDown(false); }
            case 55 -> RpgSmoke.server(p -> {
                var a = RpgSmoke.actor(p); a.setResources(100, 100);
                p.connection.teleport(.5, 80, .5, 0, 0);
                RpgServer.instance().action(p, "guard", "true");
                Arrow shot = new Arrow(p.level(), .5, 81.2, 1.9, new ItemStack(Items.ARROW), null);
                shot.setDeltaMovement(0, 0, -.8); shot.setNoGravity(true); p.level().addFreshEntity(shot); arrow = shot;
            });
            case 60 -> RpgSmoke.server(p -> {
                RpgSmoke.check(arrow != null && arrow.getOwner() == p && arrow.getDeltaMovement().z > 0, "new light guard deflects a real vanilla arrow");
                RpgSmoke.check(RpgSmoke.actor(p).techniqueSnapshot().skillId().equals("rapier_riposte"), "actual deflection opens server riposte window");
            });
            case 61 -> {
                RpgSmoke.check(ClientState.snapshot().getAsJsonObject("technique").get("skillId").getAsString().equals("rapier_riposte"), "riposte HUD receives authoritative opening");
                RpgSmoke.screenshot("refinement-riposte-ready"); RpgSmoke.mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            }
            case 65 -> RpgSmoke.server(p -> {
                var a = RpgSmoke.actor(p);
                RpgSmoke.check(a.currentSkill() != null && a.currentSkill().id().equals("rapier_riposte"), "actual right click selects conditional riposte");
                RpgSmoke.check(a.techniqueSnapshot().remainingTicks() == 0, "network riposte consumes its opening exactly once");
            });
            case 66 -> RpgSmoke.screenshot("refinement-riposte-active");
            case 82 -> ClientHooks.send("attack", "rapier_riposte");
            case 85 -> RpgSmoke.server(p -> RpgSmoke.check(RpgSmoke.actor(p).currentSkill() == null, "direct named-skill network request cannot bypass riposte prerequisite"));
            case 90 -> { RpgSmoke.server(p -> RpgSmoke.actor(p).setResources(100, 100)); ClientHooks.send("dodge", ""); }
            case 105 -> RpgSmoke.server(p -> RpgSmoke.check(RpgSmoke.actor(p).techniqueSnapshot().skillId().equals("rapier_reprise"), "completed actual collision dodge opens reprise"));
            case 106 -> RpgSmoke.mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            case 110 -> {
                RpgSmoke.server(p -> RpgSmoke.check(RpgSmoke.actor(p).currentSkill() != null && RpgSmoke.actor(p).currentSkill().id().equals("rapier_reprise"), "actual right click selects dodge follow-up"));
                RpgSmoke.screenshot("refinement-reprise-active");
            }
            case 133 -> RpgSmoke.server(p -> RpgSmoke.check(RpgSmoke.actor(p).phase() == CombatPhase.READY && RpgSmoke.actor(p).techniqueSnapshot().remainingTicks() == 0,
                    "technique recovers normally without a lingering buff"));
            case 136 -> ClientHooks.send("draw", "");
        }
        return ++tick >= 148;
    }
}

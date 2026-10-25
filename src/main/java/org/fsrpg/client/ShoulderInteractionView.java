package org.fsrpg.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Matrix4fc;

/** Last visible world frame; input revalidates its ray against current world/eye state. */
public final class ShoulderInteractionView {
    private static ClientLevel level;
    private static LocalPlayer player;
    private static ShoulderViewRay.Frame frame;
    private static long frames;
    private ShoulderInteractionView() {}

    public static void capture(Camera camera, Matrix4fc view, Matrix4fc projection, float partial) {
        var game=Minecraft.getInstance();
        if (!CombatCamera.active() || game.player==null || camera.entity()!=game.player) {
            frame=null; return;
        }
        level=game.level; player=game.player;
        var position=camera.position();
        frame=ShoulderViewRay.from(new ShoulderViewRay.Point(position.x,position.y,position.z),view,projection);
        frames++;
        // Runs before LevelRenderer extracts the block outline and entity hover.
        ShoulderInteraction.afterPick(partial);
    }
    public static ShoulderViewRay.Frame current() {
        var game=Minecraft.getInstance();
        return game.level==level&&game.player==player&&CombatCamera.active()?frame:null;
    }
    public static long frames() { return frames; }
}

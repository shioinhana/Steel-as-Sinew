package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.entity.Pose;

/** FSVOXEL samples the edited pose; Minecraft's real avatar/armor/held-item renderer draws the preview. */
public final class FsvoxelPreview {
    private ModelDocument model;
    private PoseLayers.Playback playback;
    private String error = "";
    private String loadedModel = "";
    private String loadedAction = "";
    private Path source;
    private static long renderedPreviews;
    public static long renderedPreviews() { return renderedPreviews; }

    String error() { return error; }
    String sourceLabel() { return source == null ? "资源包模型" : "config/fsrpg/models/" + source.getFileName(); }
    void load(JsonObject skill, boolean force) {
        String name = ClientState.text(skill, "model", "editor_rig.fsvoxel");
        String action = ClientState.text(skill, "action", "sword_left");
        if (!force && name.equals(loadedModel) && action.equals(loadedAction)) return;
        loadedModel = name; loadedAction = action; error = ""; model = null; playback = null;
        try {
            Path directory = FMLPaths.CONFIGDIR.get().resolve("fsrpg/models").toAbsolutePath().normalize();
            Path path = directory.resolve(name).normalize();
            if (name.isBlank() || !path.startsWith(directory) || name.contains("\\") || name.contains(":"))
                throw new IOException("模型路径必须位于 config/fsrpg/models");
            String text;
            if (Files.isRegularFile(path)) { source = path; text = Files.readString(path, StandardCharsets.UTF_8); }
            else {
                source = null;
                Identifier resource = Identifier.fromNamespaceAndPath("fsrpg", "fsvoxel/models/" + name);
                try (InputStream input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(resource).open()) {
                    text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                // A first-run editable template also makes subsequent external AI edits immediately reloadable.
                Files.createDirectories(path.getParent());
                if (!Files.exists(path)) Files.writeString(path, text, StandardCharsets.UTF_8);
                source = path;
            }
            model = ModelLoader.load(text);
            if (!model.rig().clips().containsKey(action)) throw new IOException("模型未定义动作 " + action);
            playback = new PoseLayers.Playback(model.rig(),action);
        } catch (IOException | RuntimeException exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            model = null; playback = null;
        }
    }

    void render(GuiGraphics gui, int x, int y, int width, int height, double tick,
                double windup, double active, double recovery) {
        if (model == null || playback == null || width <= 0 || height <= 0) return;
        var actionClip = model.rig().clips().get(loadedAction);
        double seconds=PlayerAnimations.phaseTime(actionClip,tick,windup,active,recovery);
        var pose=playback.sample(seconds,seconds,tick,0,0,ClientOptions.MOVING_FULL_BODY_LOWER_WEIGHT.get().floatValue(),
                ClientOptions.STANCE_FOOT_YAW_LIMIT.get().floatValue()).pose();
        var game = Minecraft.getInstance();
        if (game.player == null) return;
        var renderer = game.getEntityRenderDispatcher().getRenderer(game.player);
        if (!(renderer.createRenderState(game.player,1) instanceof AvatarRenderState state)) return;
        state.lightCoords=15728880;state.shadowPieces.clear();state.outlineColor=0;
        state.bodyRot=205;state.yRot=0;state.xRot=0;state.attackTime=0;state.isUsingItem=false;
        state.walkAnimationPos=0;state.walkAnimationSpeed=0;state.isCrouching=false;state.isVisuallySwimming=false;
        state.pose=Pose.STANDING;state.isSpectator=false;state.isFallFlying=false;state.swimAmount=0;
        state.boundingBoxWidth/=state.scale;state.boundingBoxHeight/=state.scale;state.scale=1;
        PlayerAnimations.preview(state,model,pose);
        float scale=Math.min(width/2.8f,height/2.15f);
        Quaternionf tilt=new Quaternionf().rotateX((float)Math.toRadians(12));
        gui.submitEntityRenderState(state,scale,new Vector3f(0,state.boundingBoxHeight/2,0),
                new Quaternionf().rotateZ((float)Math.PI).mul(tilt),tilt,x,y,x+width,y+height);
        renderedPreviews++;
    }
}

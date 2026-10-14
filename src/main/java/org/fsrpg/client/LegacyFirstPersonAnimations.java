package org.fsrpg.client;

import java.util.HashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.client.model.MinecraftMeshRenderer;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/** Camera-space projection of the old independent FP rig, driven by server visual intent. */
public final class LegacyFirstPersonAnimations {
    private static long samples,itemSubmissions;
    private static String lastAction="";
    private static double lastSeconds;
    private static String failedModel="";
    private LegacyFirstPersonAnimations() {}
    public static long samples(){return samples;}
    public static long itemSubmissions(){return itemSubmissions;}
    public static String lastAction(){return lastAction;}
    public static double lastSeconds(){return lastSeconds;}
    public static void onRenderHand(RenderHandEvent event) {
        var game=Minecraft.getInstance();var intent=PlayerAnimations.localIntent();
        if(game.player==null || !ClientState.drawn() || intent==null || !intent.model().startsWith("legacy_")
                || !game.options.getCameraType().isFirstPerson() || game.player.isSpectator())return;
        String modelName=intent.model().replace(".fsvoxel","_fp.fsvoxel");
        try {
            var model=AnimationAssets.load(modelName).full();String kind=LegacyMotion.kind(model);
            String action=intent.action();double seconds=PlayerAnimations.localSeconds(true);
            var archery=ClientState.object(ClientState.snapshot(),"archery");
            if(kind.equals("bow")) {
                String mode=ClientState.text(archery,"stance","RAPID").equals("SNIPE")?"snipe":"rapid";
                if(action.equals("bow_draw"))action="bow_"+mode+"_idle";
                if(action.endsWith("_charge")) {
                    var clip=model.rig().clips().get(action);
                    if(clip!=null)seconds=clip.duration()*LegacyMotion.bowChargeProgress();
                }
            }
            if(!model.rig().clips().containsKey(action))return;
            var clip=model.rig().clips().get(action);
            if(!kind.equals("bow") && java.util.Set.of("static_idle","idle_upper").contains(LegacyMotion.source(clip))) {
                String gait=LegacyMotion.gait(model,game.player,game.player.walkAnimation.speed(event.getPartialTick()),intent);
                if(model.rig().clips().containsKey(gait)){action=gait;clip=model.rig().clips().get(gait);seconds=PlayerAnimations.presentationTick()/20;}
            }
            // FP source durations occasionally differ from TP; both share the same normalized intent time.
            var tp=AnimationAssets.load(intent.model()).full().rig().clips().get(intent.action());
            if(tp!=null && action.equals(intent.action()) && !kind.equals("bow") && tp.duration()>0)seconds=seconds*clip.duration()/tp.duration();
            var pose=new HashMap<>(LegacyMotion.local(model,clip,seconds));
            if(kind.equals("dual_blade")) {
                var hip=pose.get("hip");var body=pose.get("body");
                if(hip!=null&&body!=null && !LegacyMotion.source(clip).equals("draw")) {
                    float yaw=hip.fsQuaternion().getEulerAnglesZYX(new Vector3f()).y;
                    var e=body.fsQuaternion().getEulerAnglesZYX(new Vector3f());e.y-=yaw;
                    pose.put("body",new FsvoxAnimation.Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationZYX(e.z,e.y,e.x)),body.position(),body.scale()));
                }
                offsetArm(pose,"left_arm",-.998f,.527f,.05f);
                offsetArm(pose,"right_arm",.998f,.553f,.05f);
            }
            var world=model.worldMatrices(pose);
            var poses=event.getPoseStack();var collector=event.getSubmitNodeCollector();
            event.setCanceled(true);
            if(event.getHand()!=InteractionHand.MAIN_HAND)return;
            poses.pushPose();
            if(kind.equals("bow"))poses.translate(.04f,-.52f,-.62f);
            else if(kind.equals("dual_blade"))poses.translate(0,-1.20f,-.60f);
            else poses.translate(0,-.71f,-.54f);
            var conversion=new Matrix4f().scaling(-.01f,.01f,-.01f);
            for(var draw:model.draws(pose)) {
                Geometry.Mesh mesh=flipV(draw.mesh().transformed(new Matrix4f(conversion).mul(draw.transform())));
                collector.submitCustomGeometry(poses,RenderTypes.entityCutoutNoCull(game.player.getSkin().body().texturePath()),
                        (at,buffer)->MinecraftMeshRenderer.prepare(mesh,at,-1,event.getPackedLight(),OverlayTexture.NO_OVERLAY).submit(buffer));
            }
            ItemStack main=game.player.getMainHandItem(),off=game.player.getOffhandItem();
            if(kind.equals("bow"))submitItem(event,world.get("right_item"),bowVisual(main),ItemDisplayContext.NONE,null);
            else {
                var mount=kind.equals("rapier")?new Matrix4f().scale(.85f).translate(-.020905148f,.443653694f,0).rotateZ((float)Math.toRadians(41.987212496f)):HeldItemTransform.legacyDualItem();
                submitItem(event,world.get("right_item"),main,kind.equals("rapier")?ItemDisplayContext.NONE:ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,mount);
                if(kind.equals("dual_blade"))submitItem(event,world.get("left_item"),off,ItemDisplayContext.THIRD_PERSON_LEFT_HAND,HeldItemTransform.legacyDualItem());
            }
            poses.popPose();samples++;lastAction=action;lastSeconds=seconds;
        } catch(Exception failure) {
            if(!failedModel.equals(modelName)){failedModel=modelName;LoggerFactory.getLogger(LegacyFirstPersonAnimations.class).warn("Unable to render legacy FP {}: {}",modelName,failure.getMessage());}
        }
    }
    private static Geometry.Mesh flipV(Geometry.Mesh mesh) {
        return new Geometry.Mesh(mesh.faces().stream().map(face->new Geometry.Face(face.material(),face.vertices().stream()
                .map(v->new Geometry.Vertex(v.x(),v.y(),v.z(),v.u(),1-v.v(),v.nx(),v.ny(),v.nz(),v.argb())).toList())).toList());
    }
    private static void offsetArm(java.util.Map<String,FsvoxAnimation.Pose> poses,String name,float x,float y,float z) {
        var old=poses.get(name);if(old==null)return;
        // Old renderer offsets are in Gecko model metres, before projection into FS centimetres.
        poses.put(name,new FsvoxAnimation.Pose(old.rotation(),old.position().add(new FsvoxAnimation.V(-x*100,y*100,-z*100)),old.scale()));
    }
    private static void submitItem(RenderHandEvent event,Matrix4f locator,ItemStack stack,ItemDisplayContext context,Matrix4f mount) {
        if(locator==null || stack.isEmpty())return;
        var game=Minecraft.getInstance();var item=new ItemStackRenderState();
        game.getItemModelResolver().updateForLiving(item,stack,context,game.player);
        if(item.isEmpty())return;
        var poses=event.getPoseStack();poses.pushPose();
        poses.mulPose(new Matrix4f().scaling(-.01f,.01f,-.01f).mul(locator).scale(-100,100,-100));
        if(mount!=null)poses.mulPose(mount);
        item.submit(poses,event.getSubmitNodeCollector(),event.getPackedLight(),OverlayTexture.NO_OVERLAY,0);poses.popPose();itemSubmissions++;
    }
    static ItemStack bowVisual(ItemStack stack) {
        var archery=ClientState.object(ClientState.snapshot(),"archery");
        var intent=PlayerAnimations.localIntent();
        String action=intent==null?ClientState.text(archery,"animationAction",""):intent.action();
        boolean drawing=action.endsWith("_charge");
        return bowVisual(stack,drawing,LegacyMotion.bowChargeProgress());
    }
    static ItemStack bowVisual(ItemStack stack,boolean drawing,double progress) {
        if(!(stack.getItem() instanceof net.minecraft.world.item.BowItem)||!drawing)return stack;
        var visual=stack.copy();visual.set(DataComponents.ITEM_MODEL,Identifier.fromNamespaceAndPath("fsrpg","bow_draw_"+(progress<.5?0:progress<.9?1:2)));
        return visual;
    }
}

package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.fsrpg.art.ArtCatalog;
import org.fsrpg.art.CreatureArt;
import org.fsvoxel.client.model.MinecraftMeshRenderer;

/** Artist-provided appearance on vanilla entities; vanilla spawning, AI and combat stay authoritative. */
public final class CreatureArtRenderer extends EntityRenderer<LivingEntity,CreatureArtRenderer.State> {
    public static final class State extends LivingEntityRenderState {
        public String clip="",variant="sword";
        public double seconds;
        public boolean loop=true;
        public final ItemStackRenderState rightItem=new ItemStackRenderState(),leftItem=new ItemStackRenderState();
    }
    private static final class Attack {float previous;double started=-100;}
    private final List<CreatureArt> models=new ArrayList<>();
    private final EntityRendererProvider.Context context;
    private final Map<LivingEntity,Attack> attacks=new WeakHashMap<>();
    private static final Map<String,Long> submitted=new LinkedHashMap<>();
    private static long renderedMeshes;
    public static long renderedMeshes(){return renderedMeshes;}
    public static Map<String,Long> submitted(){return Map.copyOf(submitted);}
    private CreatureArtRenderer(EntityRendererProvider.Context context,JsonObject profile) {
        super(context);this.context=context;shadowRadius=.5f;
        for(var name:profile.getAsJsonArray("models"))models.add(new CreatureArt(ArtCatalog.read("/assets/fsrpg/art/creatures/"+name.getAsString()+".json")));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        for(var v:ArtCatalog.DATA.getAsJsonArray("profiles")) {
            var p=v.getAsJsonObject();var key=Identifier.parse(p.get("entity").getAsString());
            if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key))throw new IllegalArgumentException("No entity for supplied art "+key);
            event.registerEntityRenderer((EntityType)BuiltInRegistries.ENTITY_TYPE.getValue(key),c->new CreatureArtRenderer(c,p));
        }
    }
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(LivingEntity entity,State state,float partial) {
        super.extractRenderState(entity,state,partial);
        state.bodyRot=Mth.rotLerp(partial,entity.yBodyRotO,entity.yBodyRot);state.scale=entity.getScale()*(entity.isBaby()?.5f:1);
        state.pose=entity.getPose();state.deathTime=entity.deathTime;state.hasRedOverlay=entity.hurtTime>0||entity.deathTime>0;
        String item=BuiltInRegistries.ITEM.getKey(entity.getMainHandItem().getItem()).getPath();
        state.variant=item.contains("crossbow")?"crossbow":item.contains("spear")?"spear":item.contains("axe")?"axe":"sword";
        var primary=models.getFirst();var attack=attacks.computeIfAbsent(entity,k->new Attack());float swing=entity.getAttackAnim(partial);
        double now=state.ageInTicks/20.;
        if(swing>.01f&&(attack.previous<=.01f||swing<attack.previous-.2f))attack.started=now;attack.previous=swing;
        String attackClip=primary.choose("attack",state.variant);var action=primary.clips.get(attackClip);
        if(action!=null&&now-attack.started<action.length()){state.clip=attackClip;state.seconds=now-attack.started;state.loop=false;}
        else {
            String phase=entity.walkAnimation.speed(partial)>.02f||entity.getDeltaMovement().lengthSqr()>.001?"walk":"idle";
            state.clip=primary.choose(phase,state.variant);if(state.clip.isEmpty()&&phase.equals("walk"))state.clip=primary.choose("fly",state.variant);
            state.seconds=now;state.loop=true;
        }
        context.getItemModelResolver().updateForTopItem(state.rightItem,entity.getMainHandItem(),ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,entity.level(),entity,entity.getId());
        context.getItemModelResolver().updateForTopItem(state.leftItem,entity.getOffhandItem(),ItemDisplayContext.THIRD_PERSON_LEFT_HAND,entity.level(),entity,entity.getId());
    }
    @Override public void submit(State state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera) {
        super.submit(state,poses,collector,camera);if(state.isInvisible)return;
        poses.pushPose();poses.mulPose(Axis.YP.rotationDegrees(180-state.bodyRot));poses.scale(state.scale,state.scale,state.scale);
        if(state.deathTime>0)poses.mulPose(Axis.ZP.rotationDegrees(Math.min(90,state.deathTime*4.5f)));
        int light=state.lightCoords,overlay=OverlayTexture.pack(0,state.hasRedOverlay);boolean first=true;
        for(var model:models) {
            String clip=model.clips.containsKey(state.clip)?state.clip:"";var sample=model.sample(clip,state.seconds,state.loop);
            submitted.merge(model.id+"/"+(clip.isEmpty()?"rest":clip),1L,Long::sum);
            for(int i=0;i<model.nodes.size();i++)if(sample.visible()[i]) {
                poses.pushPose();poses.mulPose(sample.matrices().get(i));
                for(var entry:model.nodes.get(i).meshes().entrySet()) {
                    var mesh=entry.getValue();var texture=Identifier.parse(model.textures.get(entry.getKey()));
                    collector.order(0).submitCustomGeometry(poses,RenderTypes.entityCutoutNoCull(texture),(pose,vertices)->MinecraftMeshRenderer.prepare(mesh,pose,-1,light,overlay).submit(vertices));renderedMeshes++;
                }
                poses.popPose();
            }
            if(first){hand(model,sample,"right",state.rightItem,poses,collector,light);hand(model,sample,"left",state.leftItem,poses,collector,light);first=false;}
        }
        poses.popPose();
    }
    private static void hand(CreatureArt model,CreatureArt.Sample sample,String side,ItemStackRenderState item,PoseStack poses,SubmitNodeCollector collector,int light) {
        if(item.isEmpty())return;
        int chosen=-1;boolean fore=false;
        for(int i=0;i<model.nodes.size();i++)if(model.nodes.get(i).name().equals(side+"_forearm")){chosen=i;fore=true;break;}
        if(chosen<0)for(int i=0;i<model.nodes.size();i++)if(model.nodes.get(i).name().equals(side+"_arm")){chosen=i;break;}
        if(chosen<0)return;
        poses.pushPose();poses.mulPose(sample.matrices().get(chosen));poses.translate(0,fore?-.30f:-.625f,0);
        poses.mulPose(Axis.XP.rotationDegrees(90));poses.translate(0,0,-.125f);
        item.submit(poses,collector,light,OverlayTexture.NO_OVERLAY,0);poses.popPose();
    }
}

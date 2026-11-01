package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
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
        public UUID entityId;
        public String combatPhase="",attackSource="LOCOMOTION";
        public long attackSequence=-1,startedGameTime=-1,contactGameTime=-1,endedGameTime=-1;
        public double serverElapsedTicks;
        public final ItemStackRenderState rightItem=new ItemStackRenderState(),leftItem=new ItemStackRenderState();
    }
    private static final class Attack {float previous;double started=-100;}
    private final List<CreatureArt> models=new ArrayList<>();
    private final EntityRendererProvider.Context context;
    private final Map<LivingEntity,Attack> attacks=new WeakHashMap<>();
    private static final Map<String,Long> submitted=new LinkedHashMap<>();
    private static long renderedMeshes;
    private static Consumer<JsonObject> renderObserver;
    public static long renderedMeshes(){return renderedMeshes;}
    public static Map<String,Long> submitted(){return Map.copyOf(submitted);}
    /** Development observer; pose hashing and JSON allocation are absent unless explicitly installed. */
    public static AutoCloseable observeRenderedAttack(Consumer<JsonObject> observer) {
        if(observer==null)throw new IllegalArgumentException("Missing creature render observer");
        if(renderObserver!=null)throw new IllegalStateException("A creature render observer is already installed");
        renderObserver=observer;
        return ()->{if(renderObserver==observer)renderObserver=null;};
    }
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
        state.variant=item.contains("crossbow")?"crossbow":item.equals("bow")?"bow":item.contains("spear")?"spear":item.contains("axe")?"axe":"sword";
        state.entityId=entity.getUUID();state.attackSequence=-1;state.combatPhase="";state.attackSource="LOCOMOTION";
        state.startedGameTime=state.contactGameTime=state.endedGameTime=-1;state.serverElapsedTicks=0;
        var primary=models.getFirst();
        var authoritative=CreatureCombatAnimation.sample(entity,partial);
        boolean controlled=CreatureCombatAnimation.controlled(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),state.variant);
        var attack=attacks.computeIfAbsent(entity,k->new Attack());float swing=entity.getAttackAnim(partial);
        double now=state.ageInTicks/20.;
        // Native swing compatibility is restricted to attacks outside the source-backed melee mapping.
        // In particular Mob byte 4 at the committed contact must never restart a controlled attack.
        if(!controlled&&swing>.01f&&(attack.previous<=.01f||swing<attack.previous-.2f))attack.started=now;
        if(controlled)attack.started=-100;
        attack.previous=swing;
        String attackClip=primary.choose("attack",state.variant);var action=primary.clips.get(attackClip);
        if(authoritative!=null&&primary.clips.containsKey(authoritative.clip())) {
            state.clip=authoritative.clip();state.seconds=authoritative.seconds();state.loop=false;state.attackSource="SERVER";
            state.combatPhase=authoritative.phase();state.attackSequence=authoritative.sequence();state.serverElapsedTicks=authoritative.elapsedTicks();
            state.startedGameTime=authoritative.startedGameTime();state.contactGameTime=authoritative.contactGameTime();state.endedGameTime=authoritative.endedGameTime();
        }
        else if(!controlled&&action!=null&&now-attack.started<action.length()){state.clip=attackClip;state.seconds=now-attack.started;state.loop=false;state.attackSource="VANILLA_COMPAT";}
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
        var observer=renderObserver;java.security.MessageDigest digest=null;
        if(observer!=null)try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        for(var model:models) {
            String clip=model.clips.containsKey(state.clip)?state.clip:"";var sample=model.sample(clip,state.seconds,state.loop);
            if(digest!=null) {
                digest.update((model.id+"/"+clip).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var buffer=java.nio.ByteBuffer.allocate(64);float[] matrix=new float[16];
                for(var value:sample.matrices()){buffer.clear();value.get(matrix);for(float coordinate:matrix)buffer.putFloat(coordinate);digest.update(buffer.array());}
            }
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
        if(observer!=null) {
            var row=new JsonObject();row.addProperty("entityUUID",state.entityId.toString());row.addProperty("sequence",state.attackSequence);
            row.addProperty("clip",state.clip);row.addProperty("phase",state.combatPhase);row.addProperty("sampleSeconds",state.seconds);
            row.addProperty("elapsedTicks",state.serverElapsedTicks);row.addProperty("startedGameTime",state.startedGameTime);
            row.addProperty("contactGameTime",state.contactGameTime);row.addProperty("endedGameTime",state.endedGameTime);
            row.addProperty("source",state.attackSource);row.addProperty("renderFrame",PlayerAnimations.renderFrameId());
            row.addProperty("poseHash",java.util.HexFormat.of().formatHex(digest.digest()));row.addProperty("renderedModels",models.size());
            row.addProperty("nativePoseSnapshotStage","CreatureArtRenderer.submit actual mesh submissions");observer.accept(row);
        }
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

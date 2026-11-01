package org.fsrpg.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLPaths;
import org.fsrpg.avatar.AvatarCatalog;
import org.fsrpg.avatar.AvatarPose;
import org.fsvoxel.client.model.MinecraftMeshRenderer;
import org.joml.Matrix4f;

/** Local appearance overrides. No model files or gameplay authority are sent to the server. */
public final class AvatarAppearances {
    private record Loaded(AvatarCatalog.Profile profile,List<Identifier> textures) {}
    private static AvatarCatalog.Snapshot catalog = new AvatarCatalog.Snapshot(Map.of(),List.of());
    private static Map<String,Loaded> loaded=Map.of();
    private static final Set<PlayerModel> bodyModels=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean initialized;
    private static long generation,submitted;
    private static long handSubmissions;
    private static String error="";
    private AvatarAppearances() {}
    public static java.nio.file.Path directory(){return FMLPaths.CONFIGDIR.get().resolve("fsrpg/avatars");}
    public static long submitted(){return submitted;}
    public static long handSubmissions(){return handSubmissions;}
    public static String lastError(){return error;}
    public static int count(){return loaded.size();}
    public static String selectedKey(UUID id,boolean local){var p=catalog.select(id,local);return p==null?"":p.key();}
    public static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event)->{if(!initialized){initialized=true;reload();}});
        NeoForge.EVENT_BUS.addListener(AvatarAppearances::commands);
    }
    public static boolean reload() {
        var pending=new LinkedHashMap<String,Loaded>();var registered=new java.util.ArrayList<Identifier>();
        try {
            seed();
            var next=AvatarCatalog.load(directory());long revision=++generation;
            for(var p:next.profiles().values()) {
                if(p.document()!=null)AvatarPose.validate(p.document());
                var pngs=p.document()==null?List.of(p.skinPng()):p.document().textures().stream().map(t->t.png()).toList();
                var ids=new java.util.ArrayList<Identifier>();
                for(int i=0;i<pngs.size();i++) {
                    var id=Identifier.fromNamespaceAndPath("fsrpg","avatar/"+revision+"/"+p.key()+"/"+i);
                    var pixels=NativeImage.read(pngs.get(i));
                    var texture=new DynamicTexture(id::toString,pixels);
                    Minecraft.getInstance().getTextureManager().register(id,texture);registered.add(id);ids.add(id);
                }
                pending.put(p.key(),new Loaded(p,List.copyOf(ids)));
            }
            var previous=loaded;loaded=Map.copyOf(pending);catalog=next;error="";initialized=true;
            for(var old:previous.values())for(var id:old.textures())Minecraft.getInstance().getTextureManager().release(id);
            return true;
        }catch(Exception failure) {
            for(var id:registered)Minecraft.getInstance().getTextureManager().release(id);
            error=failure.getMessage();org.slf4j.LoggerFactory.getLogger("FSRPG avatar").warn("Avatar reload failed; previous appearance retained",failure);
            return false;
        }
    }
    private static void seed() throws java.io.IOException {
        java.nio.file.Files.createDirectories(directory());
        for(String name:List.of("README.md","examples/player.bbmodel")) {
            var path=directory().resolve(name);
            if(!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createDirectories(path.getParent());
                try(var input=AvatarAppearances.class.getResourceAsStream("/defaults/avatars/"+name)) {
                    if(input!=null)java.nio.file.Files.copy(input,path);
                }catch(java.nio.file.FileAlreadyExistsException concurrent) { /* Preserve a user-created file. */ }
            }
        }
    }
    private static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("fsrpg-avatar")
                .executes(c->{message("玩家外观："+count()+" 个；目录 "+directory().toAbsolutePath());return 1;})
                .then(net.minecraft.commands.Commands.literal("reload").executes(c->{boolean ok=reload();
                    message(ok?"玩家外观已重载（"+count()+" 个）":"外观重载失败，保留上一份："+error);return ok?1:0;})));
    }
    private static void message(String value){var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal(value),false);}
    private static Loaded select(UUID id) {
        var player=Minecraft.getInstance().player;var p=catalog.select(id,player!=null&&player.getUUID().equals(id));
        return p==null?null:loaded.get(p.key());
    }
    private static Loaded select(AvatarRenderState state) {
        var level=Minecraft.getInstance().level;var e=level==null?null:level.getEntity(state.id);
        return e instanceof net.minecraft.world.entity.player.Player?select(e.getUUID()):null;
    }
    /** Called before the official renderer chooses the classic or slim model. */
    public static PlayerSkin skin(UUID id,PlayerSkin original) {
        var value=select(id);if(value==null||value.profile().skinPng()==null)return original;
        var texture=value.textures().getFirst();
        return new PlayerSkin(new ClientAsset.ResourceTexture(texture,texture),original.cape(),original.elytra(),
                value.profile().slim()?PlayerModelType.SLIM:PlayerModelType.WIDE,false);
    }
    public static void layers(EntityRenderersEvent.AddLayers event) {
        bodyModels.clear();
        for(var skin:event.getSkins()) {
            var renderer=event.getPlayerRenderer(skin);
            if(renderer!=null){bodyModels.add(renderer.getModel());renderer.addLayer(new Layer(renderer));}
        }
    }
    /** setupAnim runs again for deferred draws; reset all skip flags on every state. Armor models stay separate. */
    public static void prepare(PlayerModel model,AvatarRenderState state) {
        if(!bodyModels.contains(model))return;
        var value=select(state);boolean hide=value!=null&&value.profile().document()!=null&&!state.isSpectator;
        for(var part:model.root().getAllParts())part.skipDraw=hide;
    }
    private static Matrix4f delta(ModelPart part) {
        var r=part.getInitialPose();
        var rest=new Matrix4f().translation(r.x()/16,r.y()/16,r.z()/16).rotateZYX(r.zRot(),r.yRot(),r.xRot()).scale(r.xScale(),r.yScale(),r.zScale());
        return new Matrix4f().translation(part.x/16,part.y/16,part.z/16).rotateZYX(part.zRot,part.yRot,part.xRot)
                .scale(part.xScale,part.yScale,part.zScale).mul(rest.invert());
    }
    /** Vanilla/coin/pouch hand entry point: use the same shoulder carrier and retain the real item. */
    public static boolean firstPersonArm(PlayerModel model,ModelPart part,PoseStack poses,SubmitNodeCollector collector,int light) {
        for(var child:part.getAllParts())child.skipDraw=false;
        var player=Minecraft.getInstance().player;if(player==null)return false;
        var value=select(player.getUUID());if(value==null||value.profile().document()==null)return false;
        if(player.isInvisible())return true;
        String side=part==model.rightArm?"right":"left";
        part.resetPose();part.zRot=side.equals("right")?.1f:-.1f;
        var document=value.profile().document();var matrices=AvatarPose.sample(document,Map.of(side+"_arm",delta(part)),Map.of());
        submitArm(value,side,matrices,poses,collector,light);return true;
    }
    private static boolean armBone(org.fsrpg.avatar.AvatarDocument document,int index,String side) {
        for(int i=index;i>=0;i=document.bones().get(i).parent()) {
            String key=AvatarPose.binding(document.bones().get(i).name());
            if(key.equals(side+"_arm")||key.equals(side+"_forearm")||key.equals(side+"_hand"))return true;
            if(!key.isEmpty())return false;
        }
        return false;
    }
    private static void submitArm(Loaded value,String side,List<Matrix4f> matrices,PoseStack poses,SubmitNodeCollector collector,int light) {
        var document=value.profile().document();
        for(int i=0;i<document.bones().size();i++)if(armBone(document,i,side)) {
            poses.pushPose();poses.mulPose(matrices.get(i));
            for(var entry:document.bones().get(i).meshes().entrySet()) {
                var mesh=entry.getValue();var texture=value.textures().get(entry.getKey());
                collector.submitCustomGeometry(poses,RenderTypes.entityTranslucent(texture),
                        (pose,vertices)->MinecraftMeshRenderer.prepare(mesh,pose,-1,light,OverlayTexture.NO_OVERLAY).submit(vertices));handSubmissions++;
            }
            poses.popPose();
        }
    }
    /** Existing authored first-person actions continue to supply their exact joint positions. */
    static boolean firstPersonAction(Map<String,Matrix4f> source,PoseStack poses,SubmitNodeCollector collector,int light) {
        var player=Minecraft.getInstance().player;if(player==null)return false;
        var value=select(player.getUUID());if(value==null||value.profile().document()==null)return false;
        if(player.isInvisible())return true;
        var document=value.profile().document();var rest=document.restWorld();var matrices=new java.util.ArrayList<Matrix4f>();
        for(int i=0;i<document.bones().size();i++) {
            int bound=i;String key="";
            while(bound>=0){key=AvatarPose.binding(document.bones().get(bound).name());if(source.containsKey(key))break;bound=document.bones().get(bound).parent();}
            if(bound<0){matrices.add(new Matrix4f());continue;}
            var target=new Matrix4f(source.get(key));target.m30(target.m30()/100);target.m31(target.m31()/100);target.m32(target.m32()/100);
            matrices.add(new Matrix4f().scaling(-1,1,-1).mul(target).scale(-1,1,-1)
                    .mul(new Matrix4f(rest.get(bound)).invert()).mul(rest.get(i)));
        }
        submitArm(value,"right",matrices,poses,collector,light);submitArm(value,"left",matrices,poses,collector,light);return true;
    }
    private static final class Layer extends RenderLayer<AvatarRenderState,PlayerModel> {
        Layer(RenderLayerParent<AvatarRenderState,PlayerModel> parent){super(parent);}
        @Override public void submit(PoseStack poses,SubmitNodeCollector collector,int light,AvatarRenderState state,float yaw,float pitch) {
            var value=select(state);
            if(value==null||value.profile().document()==null||state.isSpectator||state.isInvisibleToPlayer&&!state.appearsGlowing())return;
            var model=getParentModel();var deltas=new LinkedHashMap<String,Matrix4f>();
            deltas.put("head",delta(model.head));deltas.put("body",delta(model.body));deltas.put("hip",delta(model.body));
            deltas.put("right_arm",delta(model.rightArm));deltas.put("left_arm",delta(model.leftArm));
            deltas.put("right_leg",delta(model.rightLeg));deltas.put("left_leg",delta(model.leftLeg));
            var actions=new LinkedHashMap<String,Matrix4f>();
            var frame=state.isFallFlying||state.swimAmount>.5f?null:PlayerAnimations.frame(state);
            if(frame!=null) {
                var restPose=new LinkedHashMap<String,org.fsvoxel.animation.FsvoxAnimation.Pose>();
                frame.model().rig().parts().forEach((name,part)->restPose.put(name,part.rest()));
                var rest=frame.model().worldMatrices(restPose);
                for(String key:List.of("right_forearm","left_forearm","right_hand","left_hand","right_shin","left_shin","right_foot","left_foot")) {
                    String source=key.replace("_shin","_foreleg");
                    if(frame.world().containsKey(source)&&rest.containsKey(source)) {
                        var d=new Matrix4f(frame.world().get(source)).mul(new Matrix4f(rest.get(source)).invert());
                        d.m30(d.m30()/100);d.m31(d.m31()/100);d.m32(d.m32()/100);
                        actions.put(key,new Matrix4f().scaling(-1,1,-1).mul(d).scale(-1,1,-1));
                    }
                }
            }
            var document=value.profile().document();var matrices=AvatarPose.sample(document,deltas,actions);
            int overlay=OverlayTexture.pack(0,state.hasRedOverlay),tint=state.isInvisible?654311423:-1;
            for(int i=0;i<document.bones().size();i++) {
                poses.pushPose();poses.mulPose(matrices.get(i));
                for(var entry:document.bones().get(i).meshes().entrySet()) {
                    var mesh=entry.getValue();var texture=value.textures().get(entry.getKey());
                    var type=RenderTypes.entityTranslucent(texture);
                    if(!state.isInvisibleToPlayer)collector.order(0).submitCustomGeometry(poses,type,(pose,vertices)->MinecraftMeshRenderer.prepare(mesh,pose,tint,light,overlay).submit(vertices));
                    if(state.appearsGlowing())collector.order(0).submitCustomGeometry(poses,RenderTypes.outline(texture),
                            (pose,vertices)->MinecraftMeshRenderer.prepare(mesh,pose,state.outlineColor,light,overlay).submit(vertices));
                    submitted++;
                }
                poses.popPose();
            }
        }
    }
}

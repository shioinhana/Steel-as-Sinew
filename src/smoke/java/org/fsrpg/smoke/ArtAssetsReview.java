package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.ImportedItems;
import org.fsrpg.RpgServer;
import org.fsrpg.art.ArtCatalog;
import org.fsrpg.client.ArtGalleryScreen;
import org.fsrpg.client.CreatureArtRenderer;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

final class ArtAssetsReview {
    private record View(int tab,int index,String profile,String clip,String file,boolean movie){}
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath();
    private final RenderFrameCapture movies=new RenderFrameCapture(root);
    private final List<View> views=new ArrayList<>();
    private final JsonArray itemChecks=new JsonArray();
    private final JsonArray captures=new JsonArray();
    private ArtGalleryScreen screen;
    private boolean initialized,done,waiting,recording,requested;
    private volatile boolean fixtureReady;
    private volatile Throwable failure;
    private volatile int worldReady=-1;
    private final List<Entity> spawned=new ArrayList<>();
    private int warmup,scene=-1,ticks,pending;
    private long started;
    private int oldScale,oldFps,oldDistance;
    private boolean oldPause,oldVsync;
    ArtAssetsReview(){
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre e)->{if(!done)e.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.Key e)->{if(!done)KeyMapping.releaseAll();});
    }
    private void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private void initialize(Minecraft game)throws Exception{
        started=System.nanoTime();Files.createDirectories(root.resolve("screenshots"));
        var env=new JsonObject();RpgSmoke.windowEnvironment(env);require("\\\\.\\DISPLAY5".equals(env.get("monitorAdapter").getAsString()),"Window outside DISPLAY5: "+env);
        var world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(world.getFileName().toString().startsWith("FSRPG Art Review ")&&Files.isRegularFile(world.resolve(".fsrpg-art-test-world")),"Must use marked copied art world");
        oldScale=game.options.guiScale().get();oldFps=game.options.framerateLimit().get();oldDistance=game.options.renderDistance().get();oldPause=game.options.pauseOnLostFocus;oldVsync=game.options.enableVsync().get();
        game.options.pauseOnLostFocus=false;game.options.guiScale().set(2);game.options.framerateLimit().set(60);game.options.enableVsync().set(false);game.options.renderDistance().set(3);
        var bounds=env.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle,1100,700);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+30,bounds.get("y").getAsInt()+40);game.resizeDisplay();
        GLFW.glfwSetWindowTitle(handle,"FSRPG 0.7.0 素材验收 · DISPLAY5");
        fixtureReady=true;
        screen=new ArtGalleryScreen();game.setScreen(screen);
        var profiles=ArtCatalog.DATA.getAsJsonArray("profiles");
        for(int i=0;i<profiles.size();i++){
            var p=profiles.get(i).getAsJsonObject();String id=p.get("id").getAsString();boolean first=true;
            for(var c:p.getAsJsonArray("clips")){String clip=c.getAsString();views.add(new View(0,i,id,clip,first?"creature-"+id:null,false));first=false;}
        }
        views.add(new View(0,0,"zombie","walk","motion-zombie",true));
        views.add(new View(0,0,"spider","walk","motion-spider",true));
        views.add(new View(0,0,"wither","fly","motion-wither",true));
        for(int i=0;i<ArtCatalog.DATA.getAsJsonArray("hud").size();i++)views.add(new View(1,i,null,null,"image-"+i,false));
        for(int i=0;i<5;i++)views.add(new View(5,i,null,null,"world-"+i,false));
        List<ItemStack> stacks=new ArrayList<>();for(var item:ImportedItems.ITEMS)stacks.add(item.toStack());
        for(var stack:stacks){
            var state=new ItemStackRenderState();game.getItemModelResolver().updateForTopItem(state,stack,ItemDisplayContext.GUI,game.level,game.player,1);
            require(!state.isEmpty(),"Empty item "+stack);var icon=state.pickParticleIcon(RandomSource.create(1));require(icon!=null&&!icon.contents().name().toString().contains("missingno"),"Missing item texture "+stack);
            var row=new JsonObject();row.addProperty("item",stack.getItem().toString());row.addProperty("texture",icon.contents().name().toString());itemChecks.add(row);
        }
        for(var image:ArtCatalog.DATA.getAsJsonArray("hud"))game.getResourceManager().getResourceOrThrow(Identifier.parse(image.getAsJsonObject().get("texture").getAsString()));
        initialized=true;
    }
    private void next(Minecraft game)throws Exception{
        scene++;ticks=0;requested=false;waiting=false;
        if(scene==views.size()){require(movies.finish(),"Pending capture writes");finish(null);return;}
        var view=views.get(scene);
        if(view.tab==5){game.setScreen(null);worldView(game,view.index);}
        else if(view.tab==0)screen.selectCreature(view.profile,view.clip);else screen.select(view.tab,view.index);
        if(view.movie){movies.start(view.file,"art-gallery/"+view.profile+"/"+view.clip,()->{var o=new JsonObject();o.addProperty("clip",screen.currentClip());o.addProperty("sourceSeconds",screen.seconds());return o;});recording=true;}
    }
    private void worldView(Minecraft game,int page){
        game.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        var uuid=game.player.getUUID();game.getSingleplayerServer().execute(()->{try{
            var level=game.getSingleplayerServer().overworld();var player=game.getSingleplayerServer().getPlayerList().getPlayer(uuid);
            for(var entity:spawned)entity.discard();spawned.clear();
            player.setGameMode(GameType.CREATIVE);player.getInventory().clearContent();RpgSmoke.actor(player).forceSheathed();RpgServer.instance().action(player,"status","");
            level.setDayTime(18000);level.setWeatherParameters(6000,0,false,false);
            for(int x=-22;x<=22;x++)for(int z=-12;z<=20;z++)level.setBlock(new BlockPos(x,79,z),Blocks.SEA_LANTERN.defaultBlockState(),2);
            player.connection.teleport(0,80,-9,0,6);
            if(page<5){
                var profiles=ArtCatalog.DATA.getAsJsonArray("profiles");int start=page*6,end=Math.min(start+6,profiles.size());
                for(int i=start;i<end;i++){
                    var profile=profiles.get(i).getAsJsonObject();var entity=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(profile.get("entity").getAsString())).create(level,EntitySpawnReason.COMMAND);
                    double x=(i-start-(end-start-1)/2.)*3.5;entity.setPos(x,80,5);entity.setYRot(180);entity.setInvulnerable(true);entity.setNoGravity(true);
                    if(entity instanceof Mob mob){mob.setNoAi(true);mob.setPersistenceRequired();}
                    if(entity instanceof LivingEntity living){living.yBodyRot=living.yBodyRotO=living.yHeadRot=living.yHeadRotO=180;
                        if(profile.get("id").getAsString().equals("pillager"))living.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.CROSSBOW));
                        else if(profile.get("id").getAsString().contains("piglin"))living.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.GOLDEN_SWORD));}
                    level.addFreshEntity(entity);spawned.add(entity);
                }
            }
            worldReady=page;
        }catch(Throwable e){failure=e;}});
    }
    private void tick(ClientTickEvent.Post event){
        if(done)return;var game=Minecraft.getInstance();if(game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try{
            if(!initialized){if(warmup++<60)return;initialize(game);return;}
            require(failure==null,"Fixture/capture failure "+failure);require((System.nanoTime()-started)/1e9<180,"Art smoke timeout");
            game.getFramerateLimitTracker().onInputReceived();KeyMapping.releaseAll();movies.assertHealthy();
            if(!fixtureReady)return;if(scene<0){next(game);return;}
            ticks++;var view=views.get(scene);
            if(waiting){if(pending>0)return;if(recording){movies.stop();recording=false;}next(game);return;}
            if(view.tab==5&&worldReady!=view.index)return;
            int duration=view.tab==5?50:view.movie?35:view.file!=null?5:3;
            if(ticks>=duration){if(view.file!=null)requested=true;else waiting=true;}
        }catch(Throwable e){finish(e);}
    }
    private void render(RenderFrameEvent.Post event){
        if(done||!initialized)return;
        try{movies.frame(event);if(requested){requested=false;waiting=true;var view=views.get(scene);pending++;
            Screenshot.grab(root.toFile(),view.file+".png",Minecraft.getInstance().getMainRenderTarget(),1,msg->{pending--;});
            var row=new JsonObject();row.addProperty("file","screenshots/"+view.file+".png");row.addProperty("scene",scene);captures.add(row);
        }}catch(Throwable e){failure=e;}
    }
    private void finish(Throwable error){
        if(done)return;done=true;
        try{
            if(error==null){for(var v:ArtCatalog.DATA.getAsJsonArray("creatures")){var row=v.getAsJsonObject();for(var c:row.getAsJsonArray("clips"))require(CreatureArtRenderer.submitted().containsKey(row.get("id").getAsString()+"/"+c.getAsJsonObject().get("id").getAsString()),"Source clip never rendered "+row.get("id")+" "+c);}
                for(var v:movies.summary().getAsJsonArray("clips"))require(v.getAsJsonObject().get("sourceFps").getAsDouble()>=30,"Recording below 30fps");}
        }catch(Throwable e){error=e;}
        try{
            var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.addProperty("completedViews",scene);report.addProperty("expectedViews",views.size());report.addProperty("renderedMeshes",CreatureArtRenderer.renderedMeshes());report.add("submitted",RpgConfig.JSON.toJsonTree(CreatureArtRenderer.submitted()));report.add("items",itemChecks);report.add("captures",captures);RpgSmoke.windowEnvironment(report);
            var source=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("jar",source.toString());report.addProperty("jarSha256",Files.isRegularFile(source)?java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("art-review.json"),RpgConfig.JSON.toJson(report));System.out.println("ART REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable e){e.printStackTrace();}
        var game=Minecraft.getInstance();if(initialized){game.options.guiScale().set(oldScale);game.options.framerateLimit().set(oldFps);game.options.renderDistance().set(oldDistance);game.options.pauseOnLostFocus=oldPause;game.options.enableVsync().set(oldVsync);}game.stop();
    }
}

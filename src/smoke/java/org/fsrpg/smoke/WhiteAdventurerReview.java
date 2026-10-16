package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.ImportedItems;
import org.fsrpg.art.ArtCatalog;
import org.fsrpg.config.RpgConfig;
import org.fsvoxel.client.model.ItemQuadBridge;
import org.lwjgl.glfw.GLFW;

/** Static replacement acceptance through the existing badge ID, native item models and held items. */
final class WhiteAdventurerReview {
    private static final String SOURCE_SHA = "3def8bca4337a495ba82017a60d41c517af36aaa09f4e193d983c7ac7bf32b7c";
    private static final String TEXTURE_SHA = "8aca3d45624fd7c8a1173f6f95ecfdad393fd7987cc7c2b17438f8f84d40707b";
    private final Path root = Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final JsonArray itemModels = new JsonArray(), screenshots = new JsonArray();
    private final Map<String,Long> nativeSubmissions = new LinkedHashMap<>();
    private final JsonObject source = new JsonObject();
    private volatile boolean serverReady;
    private volatile Throwable failure;
    private boolean initialized, done, pendingCapture, oldPause, oldVsync, oldActive;
    private int warmup, ticks, pending, oldScale, oldFps, oldDistance, nativeFrames;
    private CameraType oldCamera;
    private ImportedAssetsScreen inventoryModels;
    private long started;

    WhiteAdventurerReview() {
        NeoForge.EVENT_BUS.addListener(this::tick); NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> { if (!done) event.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> { if (!done) KeyMapping.releaseAll(); });
    }
    private void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private ItemStack badge() { return ImportedItems.ITEMS.get(ImportedItems.IDS.indexOf("silver_adventurer_badge")).toStack(); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private void initialize(Minecraft game) throws Exception {
        Files.createDirectories(root.resolve("screenshots")); var environment = new JsonObject(); RpgSmoke.windowEnvironment(environment);
        require("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()),"Asset window outside DISPLAY5");
        var world = game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var named = System.getProperty("fsrpg.whiteAdventurerWorld","");
        require(named.startsWith("FSRPG White Adventurer Review ") && named.equals(world.getFileName().toString())
                && Files.isRegularFile(world.resolve(".fsrpg-white-adventurer-test-world")),"Only explicitly marked copied badge world may be modified");
        var art = ArtCatalog.read("/assets/fsrpg/art/item_replacements/silver_adventurer_badge.json");
        require(SOURCE_SHA.equals(art.get("sourceSha256").getAsString()),"Source SHA changed");
        require(art.get("animations").getAsInt()==0,"Static source unexpectedly contains invented animations");
        int faces = art.get("faces").getAsInt();
        require(faces == 1014,"Original 169-cuboid geometry faces changed");
        var texture = Identifier.parse(art.getAsJsonArray("textures").get(0).getAsJsonObject().get("id").getAsString());
        try (var stream = game.getResourceManager().getResourceOrThrow(texture).open()) { require(TEXTURE_SHA.equals(sha(stream.readAllBytes())),"Original embedded PNG bytes changed"); }
        var nativeModel = ArtCatalog.read("/assets/fsrpg/models/item/silver_adventurer_badge.json");
        require(nativeModel.getAsJsonArray("elements").size() == 169,"Native item must retain all 169 source cubes");
        source.addProperty("sourceSha256",SOURCE_SHA); source.addProperty("textureSha256",TEXTURE_SHA);
        source.addProperty("nativeElements",169); source.addProperty("originalFaces",faces); source.addProperty("sourceAnimations",0);
        var stack = badge(); require(stack.getMaxStackSize() == 1,"Existing badge stack size changed");
        require("fsrpg:silver_adventurer_badge".equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),"Existing badge ID changed");
        require(!BuiltInRegistries.ITEM.containsKey(Identifier.parse("fsrpg:white_adventurer_badge")),"Duplicate new badge item must not be registered");
        require(ImportedItems.IDS.size()==9,"Existing imported item registrations changed");
        for (var context : new ItemDisplayContext[]{ItemDisplayContext.GUI,ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,ItemDisplayContext.GROUND}) {
            var state = new ItemStackRenderState(); game.getItemModelResolver().updateForTopItem(state,stack,context,game.level,game.player,1);
            require(!state.isEmpty(),"Native model is empty: "+context); var particle = state.pickParticleIcon(RandomSource.create(1));
            require(particle != null && !particle.contents().name().toString().contains("missingno"),"Missing texture: "+context);
            var bounds=state.getModelBoundingBox();double width=bounds.maxX-bounds.minX,height=bounds.maxY-bounds.minY,depth=bounds.maxZ-bounds.minZ;
            double maximumSide=Math.max(width,Math.max(height,depth));
            require(Double.isFinite(maximumSide)&&width>0&&height>0&&depth>0,"Invalid official model bounds: "+context);
            if(context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                require(maximumSide>=.05&&maximumSide<=.35,"Badge must fit beside the third-person palm (0.05..0.35m), got "+maximumSide);
            if(context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                require(maximumSide>=.10&&maximumSide<=.60,"First-person badge must have a readable, bounded display (0.10..0.60m), got "+maximumSide);
            var value = new JsonObject();value.addProperty("context",context.toString());value.addProperty("texture",particle.contents().name().toString());
            value.addProperty("nativeModelSize",bounds.getSize());value.addProperty("widthMeters",width);value.addProperty("heightMeters",height);
            value.addProperty("depthMeters",depth);value.addProperty("maximumSideMeters",maximumSide);itemModels.add(value);
        }
        oldScale=game.options.guiScale().get();oldFps=game.options.framerateLimit().get();oldDistance=game.options.renderDistance().get();
        oldPause=game.options.pauseOnLostFocus;oldVsync=game.options.enableVsync().get();oldCamera=game.options.getCameraType();oldActive=game.isWindowActive();
        game.options.guiScale().set(2);game.options.framerateLimit().set(60);game.options.renderDistance().set(3);game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);
        game.options.setCameraType(CameraType.FIRST_PERSON);game.setWindowActive(true);game.getTutorial().setStep(TutorialSteps.NONE);
        var bounds=environment.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle,1100,700);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+30,bounds.get("y").getAsInt()+40);
        GLFW.glfwSetWindowTitle(handle,"FSRPG 白色冒险者牌 · DISPLAY5");game.resizeDisplay();initialized=true;started=System.nanoTime();
        ItemQuadBridge.setRenderObserver(submission -> { if (submission.quads().size() >= 1000) nativeSubmissions.merge(submission.displayContext().toString(),1L,Long::sum); });
        var uuid=game.player.getUUID();game.getSingleplayerServer().execute(() -> { try {
            var player=game.getSingleplayerServer().getPlayerList().getPlayer(uuid);player.setGameMode(GameType.CREATIVE);player.setInvulnerable(true);
            player.getInventory().clearContent();player.setItemSlot(EquipmentSlot.MAINHAND,badge());
            RpgSmoke.actor(player).forceSheathed();RpgServer.instance().action(player,"status","");
            player.level().setDayTime(6000);player.level().setWeatherParameters(6000,0,false,false);
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++) {
                player.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                for(int y=80;y<85;y++)player.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
            }
            player.connection.teleport(.5,80,.5,0,8);serverReady=true;
        } catch(Throwable error) { failure=error; } });
    }
    private void tick(ClientTickEvent.Post event) {
        var game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try {
            if(!initialized){if(++warmup>=60)initialize(game);return;}
            if(failure!=null)throw new IllegalStateException("Badge fixture failed",failure);
            require(System.nanoTime()-started<45_000_000_000L,"Badge review timeout");game.getFramerateLimitTracker().onInputReceived();KeyMapping.releaseAll();
            if(!serverReady)return;ticks++;
            if(ticks==35){require(game.player.getMainHandItem().is(badge().getItem()),"Real local player does not hold the existing silver badge");request("badge-first-person");}
            if(ticks==50)game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            if(ticks==85)request("badge-third-person");
            if(ticks==100)game.setScreen(new InventoryScreen(game.player));
            if(ticks==140)request("badge-native-inventory");
            if(ticks==155){inventoryModels=new ImportedAssetsScreen();game.setScreen(inventoryModels);}
            if(ticks==185){require(inventoryModels.drawnFrames()>0,"Native inventory item previews were never rendered");request("badge-all-existing-item-models");}
            if(ticks>=200&&pending==0&&!pendingCapture){require(nativeFrames>30,"Insufficient real rendered frames");finish(null);}
        }catch(Throwable error){finish(error);}
    }
    private void request(String name){pendingCapture=true;source.addProperty("pendingScreenshot",name);}
    private void render(RenderFrameEvent.Post event) {
        if(done||!initialized)return;nativeFrames++;
        if(!pendingCapture)return;
        try {
            pendingCapture=false;String name=source.remove("pendingScreenshot").getAsString();pending++;
            Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message -> pending--);
            screenshots.add("screenshots/"+name+".png");
        }catch(Throwable error){failure=error;}
    }
    private void finish(Throwable error) {
        if(done)return;done=true;
        try {
            var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());report.add("source",source);
            report.addProperty("staticArtOnly",true);report.addProperty("nativeRenderFrames",nativeFrames);report.add("nativeItemModels",itemModels);
            report.add("observedNativeModelSubmissionsByContext",RpgConfig.JSON.toJsonTree(nativeSubmissions));report.add("screenshots",screenshots);RpgSmoke.windowEnvironment(report);
            var jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());report.addProperty("modJar",jar.toString());
            report.addProperty("modJarSha256",Files.isRegularFile(jar)?sha(Files.readAllBytes(jar)):"loose-classes");
            RpgConfig.atomicWrite(root.resolve("white-adventurer-review.json"),RpgConfig.JSON.toJson(report));
            System.out.println("WHITE ADVENTURER REVIEW COMPLETE passed="+(error==null)+" "+root);if(error!=null)error.printStackTrace();
        }catch(Throwable reporting){reporting.printStackTrace();}
        ItemQuadBridge.setRenderObserver(null);var game=Minecraft.getInstance();KeyMapping.releaseAll();
        if(initialized){game.options.guiScale().set(oldScale);game.options.framerateLimit().set(oldFps);game.options.renderDistance().set(oldDistance);game.options.pauseOnLostFocus=oldPause;game.options.enableVsync().set(oldVsync);game.options.setCameraType(oldCamera);game.setWindowActive(oldActive);}
        game.stop();
    }
}

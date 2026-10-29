package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.AvatarAppearances;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.LegacyFirstPersonAnimations;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** Real-client appearance loading, visible outer layers and atomic reload, in a copied world only. */
final class AvatarAppearanceReview {
    private enum Scene { CLASSIC, SLIM, SLIM_HAND, BLOCKBENCH, BEDROCK, BEDROCK_REAR, BB_HAND, BB_ACTION_TP, BB_ACTION_FP, BAD_RELOAD, REMOVED }
    private static final Field MOUSE_GRABBED=field(MouseHandler.class,"mouseGrabbed");
    private final Path root=Path.of(System.getProperty("fsrpg.smokeEvidence")).toAbsolutePath().normalize();
    private final Path fixtures=root.getParent().resolve("fixtures");
    private final JsonArray checks=new JsonArray(),screenshots=new JsonArray(),scenes=new JsonArray();
    private final RenderFrameCapture movie=new RenderFrameCapture(root);
    private final AtomicInteger pending=new AtomicInteger();
    private Path avatars,backup;
    private Map<String,String> originalFiles;
    private boolean initialized,optionsChanged,backedUp,done,finishing,recording,oldMouse,oldPause,oldVsync,oldActive,oldHide;
    private int warmup,ticks,local,sceneIndex,nativeFrames,oldFps,oldDistance,oldScale,oldFov;
    private CameraType oldCamera;
    private long started,submittedBefore,handBefore,animationBefore,itemBefore,officialBefore,clipStarted;
    private int attackTick;
    private boolean requestedAttack,sawAttack,actionShot;
    private String capture;
    private Scene scene=Scene.CLASSIC;
    private volatile boolean prepared;
    private volatile Throwable failure;
    private volatile boolean actionFixtureReady,serverAcceptedAttack;
    private volatile long attackSequence;

    AvatarAppearanceReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event)->{if(!done)event.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event)->{if(!done)KeyMapping.releaseAll();});
    }
    private static Field field(Class<?> type,String name) {
        try{var result=type.getDeclaredField(name);result.setAccessible(true);return result;}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void check(boolean value,String message){require(value,message);checks.add(message);System.out.println("AVATAR PASS "+message);}
    private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private void monitor() {
        var environment=new JsonObject();RpgSmoke.windowEnvironment(environment);
        require("\\\\.\\DISPLAY5".equals(environment.get("monitorAdapter").getAsString()),"Avatar acceptance left DISPLAY5: "+environment);
    }
    private static Map<String,String> snapshot(Path directory)throws Exception {
        var result=new LinkedHashMap<String,String>();
        try(var files=Files.walk(directory)) {
            for(var file:files.filter(Files::isRegularFile).sorted().toList()) {
                require(!Files.isSymbolicLink(file),"Avatar review does not move linked user assets");
                result.put(directory.relativize(file).toString(),sha(file));
            }
        }
        return result;
    }
    private void initialize(Minecraft game)throws Exception {
        Files.createDirectories(root.resolve("screenshots"));monitor();
        var world=game.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name=System.getProperty("fsrpg.avatarWorld","");
        check(name.startsWith("FSRPG Avatar Review ")&&world.getFileName().toString().equals(name)
                &&Files.isRegularFile(world.resolve(".fsrpg-avatar-test-world")),"Only the named marked copied avatar test world is modified");
        avatars=AvatarAppearances.directory().toAbsolutePath().normalize();Files.createDirectories(avatars);
        originalFiles=snapshot(avatars);backup=root.resolve("original-avatars-"+System.currentTimeMillis());
        require(!Files.exists(backup),"Refusing to reuse an avatar backup directory");
        Files.move(avatars,backup);backedUp=true;Files.createDirectory(avatars);
        check(originalFiles.equals(snapshot(backup)),"Development avatar assets backed up byte-for-byte before any replacement");
        createFixtures();
        oldFps=game.options.framerateLimit().get();oldDistance=game.options.renderDistance().get();oldScale=game.options.guiScale().get();oldFov=game.options.fov().get();
        oldPause=game.options.pauseOnLostFocus;oldVsync=game.options.enableVsync().get();oldActive=game.isWindowActive();oldHide=game.options.hideGui;
        oldCamera=game.options.getCameraType();oldMouse=MOUSE_GRABBED.getBoolean(game.mouseHandler);optionsChanged=true;
        game.options.framerateLimit().set(40);game.options.renderDistance().set(3);game.options.guiScale().set(2);game.options.fov().set(50);
        game.options.pauseOnLostFocus=false;game.options.enableVsync().set(false);game.options.hideGui=false;game.setWindowActive(true);
        MOUSE_GRABBED.setBoolean(game.mouseHandler,true);game.getTutorial().setStep(TutorialSteps.NONE);KeyMapping.releaseAll();CombatCamera.reset();
        var environment=new JsonObject();RpgSmoke.windowEnvironment(environment);var bounds=environment.getAsJsonObject("monitorBounds");long handle=game.getWindow().handle();
        GLFW.glfwSetWindowSize(handle,1100,760);GLFW.glfwSetWindowPos(handle,bounds.get("x").getAsInt()+30,bounds.get("y").getAsInt()+35);
        GLFW.glfwSetWindowTitle(handle,"FSRPG 玩家BB／原版与基岩立体皮肤 · DISPLAY5");game.resizeDisplay();
        initialized=true;started=System.nanoTime();
        var id=game.player.getUUID();game.getSingleplayerServer().execute(()->{try {
            var player=game.getSingleplayerServer().getPlayerList().getPlayer(id);player.setGameMode(GameType.CREATIVE);player.setInvulnerable(true);player.getInventory().clearContent();
            var oldVehicle=player.getVehicle();player.stopRiding();
            if(oldVehicle!=null && oldVehicle.getPassengers().isEmpty())oldVehicle.discard();
            // The marked copied camera world may retain its horse/boat fixtures.
            // Clear only entities inside this review's bounded rebuilt platform.
            for(var leftover:player.level().getEntities(player,new net.minecraft.world.phys.AABB(-7,78,-7,8,88,8),
                    entity->!(entity instanceof net.minecraft.world.entity.player.Player)))leftover.discard();
            require(!player.isPassenger(),"Avatar fixture must start with a dismounted server player");
            player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.APPLE));player.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            RpgSmoke.actor(player).forceSheathed();RpgServer.instance().action(player,"status","");
            player.level().setDayTime(6000);player.level().setWeatherParameters(6000,0,false,false);
            for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++) {
                player.level().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                for(int y=80;y<=86;y++)player.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
            }
            player.connection.teleport(.5,80,.5,0,7);player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);prepared=true;
        }catch(Throwable error){failure=error;}});
        prepare(game);
    }
    private void createFixtures()throws Exception {
        Files.createDirectories(fixtures);byte[] png=skin();
        for(String folder:List.of("classic","slim","blockbench","bedrock")) {
            Files.createDirectories(fixtures.resolve(folder));Files.write(fixtures.resolve(folder+"/skin.png"),png);
        }
        Files.writeString(fixtures.resolve("classic/avatar.json"),"{\"type\":\"skin\",\"slim\":false}\n");
        Files.writeString(fixtures.resolve("slim/avatar.json"),"{\"type\":\"skin\",\"slim\":true}\n");
        Files.writeString(fixtures.resolve("blockbench/model.bbmodel"),RpgConfig.JSON.toJson(bbmodel()));
        var official=root.getParent().getParent().resolve("avatar-source-review/humanoid.custom.geo.json");
        require(Files.isRegularFile(official),"Missing official Mojang geometry fixture: "+official);
        Files.copy(official,fixtures.resolve("bedrock/geometry.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(fixtures.resolve("bedrock/avatar.json"),"{\"type\":\"bedrock\",\"geometry\":\"geometry.humanoid.custom\"}\n");
        var provenance=new JsonObject();provenance.addProperty("geometrySource","https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/models/entity/humanoid.custom.geo.json");
        provenance.addProperty("geometrySha256",sha(official));provenance.addProperty("texture","Locally generated asymmetric Java 64x64 UV diagnostic skin; red right arm, blue left arm, checker outer layers");
        Files.writeString(fixtures.resolve("provenance.json"),RpgConfig.JSON.toJson(provenance));
    }
    private static byte[] skin()throws Exception {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        fill(image,0,0,32,16,0xffd5a67b,false);fill(image,16,16,24,16,0xff39964a,false);
        fill(image,40,16,16,16,0xffd52828,false);fill(image,32,48,16,16,0xff294ccc,false);
        fill(image,0,16,16,16,0xff9222ad,false);fill(image,16,48,16,16,0xff23b8b0,false);
        fill(image,32,0,32,16,0xffffd42a,true);fill(image,16,32,24,16,0xffeeeeee,true);
        fill(image,40,32,16,16,0xffff6e38,true);fill(image,48,48,16,16,0xff65b6ff,true);
        fill(image,0,32,16,16,0xffd371f0,true);fill(image,0,48,16,16,0xff60f4e8,true);
        image.setRGB(10,10,0xff111111);image.setRGB(13,10,0xff111111);image.setRGB(11,13,0xff7a3d27);image.setRGB(12,13,0xff7a3d27);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
    private static void fill(BufferedImage image,int x0,int y0,int w,int h,int color,boolean checker) {
        for(int y=y0;y<y0+h;y++)for(int x=x0;x<x0+w;x++)if(!checker || (x/2+y/2)%2==0)image.setRGB(x,y,color);
    }
    private static JsonArray array(float... values){var result=new JsonArray();for(float value:values)result.add(value);return result;}
    private static JsonObject bbmodel() {
        var document=new JsonObject();var meta=new JsonObject();meta.addProperty("format_version","5.0");meta.addProperty("model_format","free");document.add("meta",meta);
        var textures=new JsonArray();var texture=new JsonObject();texture.addProperty("name","skin.png");texture.addProperty("relative_path","skin.png");texture.addProperty("width",64);texture.addProperty("height",64);textures.add(texture);document.add("textures",textures);
        var groups=new JsonArray();var elements=new JsonArray();var outliner=new JsonArray();
        String[] names={"head","body","right_arm","left_arm","right_leg","left_leg","hat"};
        float[][] from={{-4,24,-4},{-4,12,-2},{4,12,-2},{-8,12,-2},{0,0,-2},{-4,0,-2},{-4,24,-4}};
        float[][] to={{4,32,4},{4,24,2},{8,24,2},{-4,24,2},{4,12,2},{0,12,2},{4,32,4}};
        float[][] pivot={{0,24,0},{0,24,0},{5,22,0},{-5,22,0},{2,12,0},{-2,12,0},{0,24,0}};
        float[][] uv={{8,8,16,16},{20,20,28,32},{44,20,48,32},{36,52,40,64},{4,20,8,32},{20,52,24,64},{40,8,48,16}};
        for(int i=0;i<names.length;i++) {
            String groupId="avatar-bone-"+i,elementId="avatar-cube-"+i;var group=new JsonObject();group.addProperty("name",names[i]);group.addProperty("uuid",groupId);group.add("origin",array(pivot[i]));groups.add(group);
            var cube=new JsonObject();cube.addProperty("uuid",elementId);cube.addProperty("type","cube");cube.add("from",array(from[i]));cube.add("to",array(to[i]));if(i==6)cube.addProperty("inflate",.55);
            var faces=new JsonObject();for(String side:List.of("up","down","north","south","west","east")) {var face=new JsonObject();face.add("uv",array(uv[i]));face.addProperty("texture",0);faces.add(side,face);}cube.add("faces",faces);elements.add(cube);
            var outline=new JsonObject();outline.addProperty("uuid",groupId);var children=new JsonArray();children.add(elementId);outline.add("children",children);outliner.add(outline);
        }
        document.add("groups",groups);document.add("elements",elements);document.add("outliner",outliner);return document;
    }
    private void profile(String source)throws Exception {
        Path current=avatars.resolve("player");
        if(Files.exists(current)) {
            Path old=root.resolve("switched-profiles-"+started);Files.createDirectories(old);Files.move(current,old.resolve(String.format("%02d-%s",sceneIndex,scene.name().toLowerCase())));
        }
        if(source==null)return;
        Path folder=fixtures.resolve(source);Files.createDirectory(current);
        try(var paths=Files.list(folder)) {for(Path file:paths.toList())Files.copy(file,current.resolve(file.getFileName()));}
    }
    private void prepare(Minecraft game)throws Exception {
        local=0;submittedBefore=AvatarAppearances.submitted();handBefore=AvatarAppearances.handSubmissions();
        requestedAttack=sawAttack=actionShot=actionFixtureReady=serverAcceptedAttack=false;attackTick=0;
        if(scene==Scene.CLASSIC)profile("classic");
        if(scene==Scene.SLIM)profile("slim");
        if(scene==Scene.BLOCKBENCH || scene==Scene.BB_HAND || actionScene())profile("blockbench");
        if(scene==Scene.BEDROCK)profile("bedrock");
        if(scene==Scene.REMOVED)profile(null);
        if(scene==Scene.BAD_RELOAD) {
            Files.writeString(avatars.resolve("player/model.bbmodel"),"{\"meta\":{\"format_version\":\"5.0\"},\"elements\":[]}");
            check(!AvatarAppearances.reload()&&!AvatarAppearances.lastError().isBlank(),"Malformed BB reload reports its error and refuses replacement");
            check(AvatarAppearances.count()==1&&AvatarAppearances.selectedKey(game.player.getUUID(),true).equals("player"),"Failed reload retains the complete previous valid appearance");
        } else check(AvatarAppearances.reload(),"External files load through production reload: "+scene);
        CameraType camera=scene==Scene.SLIM_HAND || scene==Scene.BB_HAND || scene==Scene.BB_ACTION_FP?CameraType.FIRST_PERSON:scene==Scene.BEDROCK_REAR?CameraType.THIRD_PERSON_BACK:CameraType.THIRD_PERSON_FRONT;
        CombatCamera.reset();game.options.setCameraType(camera);
        if(scene==Scene.SLIM_HAND || scene==Scene.BB_HAND) {
            var id=game.player.getUUID();game.getSingleplayerServer().execute(()->{try {
                var player=game.getSingleplayerServer().getPlayerList().getPlayer(id);player.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            }catch(Throwable error){failure=error;}});
        }
        if(scene==Scene.BLOCKBENCH || scene==Scene.BEDROCK) {
            movie.start(scene.name().toLowerCase()+"-appearance","Actual external model in the live world; RenderFrameEvent.Post",this::observation);recording=true;clipStarted=System.nanoTime();
        }
        if(actionScene()) {
            var id=game.player.getUUID();game.getSingleplayerServer().execute(()->{try {
                var player=game.getSingleplayerServer().getPlayerList().getPlayer(id);var actor=RpgSmoke.actor(player);
                actor.forceSheathed();player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
                RpgServer.instance().progression().learnFree(id,"rapier");actor.setStyle(RpgServer.instance().config().current().styles().get("rapier"));
                actor.setResources(actor.maxStamina(),100);RpgServer.instance().action(player,"status","");RpgServer.instance().action(player,"draw","");actionFixtureReady=true;
            }catch(Throwable error){failure=error;}});
        }
    }
    private boolean actionScene(){return scene==Scene.BB_ACTION_TP || scene==Scene.BB_ACTION_FP;}
    private static String phase(){return ClientState.text(ClientState.snapshot(),"phase","");}
    private JsonObject observation() {
        var game=Minecraft.getInstance();var value=new JsonObject();value.addProperty("scene",scene.name());value.addProperty("nativeNanos",System.nanoTime());
        value.addProperty("selected",AvatarAppearances.selectedKey(game.player.getUUID(),true));value.addProperty("profiles",AvatarAppearances.count());value.addProperty("submittedGeometry",AvatarAppearances.submitted());
        value.addProperty("submittedHands",AvatarAppearances.handSubmissions());value.addProperty("nativePlayerAnimations",PlayerAnimations.appliedModels());value.addProperty("nativeFirstPersonAnimations",LegacyFirstPersonAnimations.samples());
        value.addProperty("nativeFirstPersonItems",LegacyFirstPersonAnimations.itemSubmissions());value.addProperty("officialHeldItems",OfficialHeldItems.submissions());
        value.addProperty("combatPhase",phase());value.addProperty("serverAttackSequence",attackSequence);
        value.addProperty("skinType",game.player.getSkin().model().toString());value.addProperty("camera",game.options.getCameraType().toString());return value;
    }
    private void tick(ClientTickEvent.Post event) {
        var game=Minecraft.getInstance();if(done||game.level==null||game.player==null||game.getSingleplayerServer()==null)return;
        try {
            if(!initialized){if(++warmup>=60)initialize(game);return;}
            if(failure!=null)throw new IllegalStateException("Avatar fixture failed",failure);
            require(System.nanoTime()-started<90_000_000_000L,"Avatar review exceeded ninety seconds");movie.assertHealthy();
            game.getFramerateLimitTracker().onInputReceived();game.setWindowActive(true);MOUSE_GRABBED.setBoolean(game.mouseHandler,true);KeyMapping.releaseAll();ticks++;
            if(finishing){if(pending.get()==0&&capture==null&&movie.finish()){validate();finish(null);}return;}
            if(!prepared)return;
            local++;
            if(actionScene()){actionTick(game);return;}
            if(local==24) {
                check(!game.player.isPassenger(),"Standing/hand appearance is unobstructed by any inherited mount in "+scene);
                if(scene==Scene.CLASSIC)check(game.player.getSkin().model()==PlayerModelType.WIDE,"Classic PNG selects the official four-pixel-arm renderer");
                if(scene==Scene.SLIM || scene==Scene.SLIM_HAND)check(game.player.getSkin().model()==PlayerModelType.SLIM,"Slim PNG selects the official three-pixel-arm renderer in "+scene);
                if(scene==Scene.SLIM_HAND || scene==Scene.BB_HAND)check(game.player.getMainHandItem().isEmpty(),"Empty actual main hand enters the original first-person arm renderer in "+scene);
                if(List.of(Scene.BLOCKBENCH,Scene.BEDROCK,Scene.BEDROCK_REAR,Scene.BAD_RELOAD).contains(scene))
                    check(AvatarAppearances.submitted()>submittedBefore,"Actual imported mesh geometry submitted in "+scene);
                if(scene==Scene.BB_HAND)check(AvatarAppearances.handSubmissions()>handBefore,"The imported BB arm replaces the actual first-person hand");
                if(scene==Scene.REMOVED)check(AvatarAppearances.count()==0&&AvatarAppearances.selectedKey(game.player.getUUID(),true).isEmpty(),"Removing the profile restores the ordinary player skin");
                capture=String.format("%02d-%s",sceneIndex,scene.name().toLowerCase());scenes.add(observation());
                if(recording){movie.stop();recording=false;}
            }
            if(local>=28&&capture==null&&pending.get()==0) {
                nextScene(game);
            }
        }catch(Throwable error){finish(error);}
    }
    private void nextScene(Minecraft game)throws Exception {
        if(++sceneIndex>=Scene.values().length)finishing=true;
        else {scene=Scene.values()[sceneIndex];prepare(game);}
    }
    private void actionTick(Minecraft game)throws Exception {
        require(local<240,"Actual drawn attack did not complete the appearance test: "+scene+" "+observation());
        game.options.setCameraType(scene==Scene.BB_ACTION_FP?CameraType.FIRST_PERSON:CameraType.THIRD_PERSON_FRONT);
        if(!requestedAttack) {
            if(!actionFixtureReady || !ClientState.drawn() || !phase().equals("READY") || !game.player.getMainHandItem().is(Items.IRON_SWORD))return;
            check(!game.player.isPassenger(),"Actual sword appearance scene uses a dismounted player in "+scene);
            animationBefore=scene==Scene.BB_ACTION_FP?LegacyFirstPersonAnimations.samples():PlayerAnimations.appliedModels();
            itemBefore=LegacyFirstPersonAnimations.itemSubmissions();officialBefore=OfficialHeldItems.submissions();handBefore=AvatarAppearances.handSubmissions();submittedBefore=AvatarAppearances.submitted();
            requestedAttack=true;var id=game.player.getUUID();
            movie.start(scene.name().toLowerCase().replace('_','-')+"-attack","Real server draw and left attack, existing production player animation and actual ItemStack rendering",this::observation);recording=true;clipStarted=System.nanoTime();
            game.getSingleplayerServer().execute(()->{try {
                var player=game.getSingleplayerServer().getPlayerList().getPlayer(id);var actor=RpgSmoke.actor(player);long before=actor.snapshot().swingSequence();
                RpgServer.instance().action(player,"attack","left");attackSequence=actor.snapshot().swingSequence();serverAcceptedAttack=attackSequence>before&&actor.currentSkill()!=null;
            }catch(Throwable error){failure=error;}});
            return;
        }
        attackTick++;
        if(List.of("WINDUP","ACTIVE").contains(phase())) {
            sawAttack=true;
            if(!actionShot && phase().equals("ACTIVE")) {actionShot=true;capture=String.format("%02d-%s",sceneIndex,scene.name().toLowerCase());scenes.add(observation());}
        }
        if(attackTick>=20 && serverAcceptedAttack && sawAttack && actionShot && capture==null && pending.get()==0) {
            if(scene==Scene.BB_ACTION_FP) {
                check(LegacyFirstPersonAnimations.samples()>animationBefore,"Real first-person BB sword attack uses the existing production animation sampler");
                check(LegacyFirstPersonAnimations.itemSubmissions()>itemBefore,"Real first-person BB sword attack retains the official actual ItemStack model");
                check(AvatarAppearances.handSubmissions()>handBefore,"Real first-person BB sword attack submits the imported arm geometry");
            } else {
                check(PlayerAnimations.appliedModels()>animationBefore,"Real third-person BB sword attack uses the existing production player pose sampler");
                check(OfficialHeldItems.submissions()>officialBefore,"Real third-person BB sword attack retains the official actual ItemStack model");
                check(AvatarAppearances.submitted()>submittedBefore,"Real third-person BB sword attack submits the imported body geometry");
            }
            check(serverAcceptedAttack&&sawAttack,"Server-authorized left attack reaches the actual rendered combat phase: "+scene);
            movie.stop();recording=false;nextScene(game);
        }
    }
    private void render(RenderFrameEvent.Post event) {
        if(done||!initialized)return;nativeFrames++;
        try {
            if(recording){monitor();movie.frame(event);require(System.nanoTime()-clipStarted<4_000_000_000L,"Appearance capture exceeded four seconds");}
            if(capture==null)return;monitor();String name=capture;capture=null;pending.incrementAndGet();
            Screenshot.grab(root.toFile(),name+".png",Minecraft.getInstance().getMainRenderTarget(),1,message->pending.decrementAndGet());
            var value=observation();value.addProperty("file","screenshots/"+name+".png");value.addProperty("nativeFrame",nativeFrames);screenshots.add(value);
        }catch(Throwable error){failure=error;}
    }
    private void validate()throws Exception {
        check(scenes.size()==Scene.values().length&&screenshots.size()==Scene.values().length,"All eleven Java, BB, Bedrock, first-person, real sword attack and reload scenes completed");
        double duration=0;var clips=movie.summary().getAsJsonArray("clips");check(clips.size()==4,"BB and official Bedrock appearances and both real sword attack views have native-frame recording");
        for(var item:clips) {
            var clip=item.getAsJsonObject();duration+=clip.get("captureDurationSeconds").getAsDouble();
            check(clip.get("sourceFps").getAsDouble()>=30&&clip.get("capturedFrames").getAsInt()==clip.get("writtenFrames").getAsInt(),"All actual native frames written at at least 30 Hz: "+clip.get("id"));
        }
        check(duration<15,"Total source recording duration is below fifteen seconds");
        for(var value:screenshots)check(Files.size(root.resolve(value.getAsJsonObject().get("file").getAsString()))>1000,"Native screenshot exists: "+value.getAsJsonObject().get("file"));
    }
    private void restore()throws Exception {
        if(!backedUp)return;
        Path finalTest=root.resolve("final-test-avatars-"+started);require(!Files.exists(finalTest),"Final test asset backup already exists");
        if(Files.exists(avatars))Files.move(avatars,finalTest);Files.move(backup,avatars);backedUp=false;
        check(originalFiles.equals(snapshot(avatars)),"Original development avatar directory restored byte-for-byte");
        check(AvatarAppearances.reload(),"Original development appearance assignments restored after the test");
    }
    private void finish(Throwable error) {
        if(done)return;done=true;if(recording){movie.stop();recording=false;}
        try{restore();}catch(Throwable restore){if(error==null)error=restore;else error.addSuppressed(restore);}
        try {
            var report=new JsonObject();report.addProperty("passed",error==null);report.addProperty("error",error==null?null:error.toString());
            report.addProperty("world",System.getProperty("fsrpg.avatarWorld"));report.addProperty("checks",checks.size());report.add("checksPassed",checks);report.add("scenes",scenes);report.add("screenshots",screenshots);
            report.addProperty("nativeFrames",nativeFrames);report.addProperty("osPointerCaptured",false);report.add("renderCapture",movie.summary());RpgSmoke.windowEnvironment(report);
            var jar=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());String jarSha=Files.isRegularFile(jar)?sha(jar):"loose-classes";
            report.addProperty("modJar",jar.toString());report.addProperty("modJarSha256",jarSha);report.addProperty("jarSha256",jarSha);
            RpgConfig.atomicWrite(root.resolve("avatar-appearance-review.json"),RpgConfig.JSON.toJson(report));
            System.out.println("AVATAR APPEARANCE REVIEW COMPLETE passed="+(error==null));if(error!=null)error.printStackTrace();
        }catch(Throwable report){report.printStackTrace();}
        var game=Minecraft.getInstance();KeyMapping.releaseAll();
        if(optionsChanged)try {
            MOUSE_GRABBED.setBoolean(game.mouseHandler,oldMouse);CombatCamera.reset();game.options.setCameraType(oldCamera);game.options.framerateLimit().set(oldFps);game.options.renderDistance().set(oldDistance);
            game.options.guiScale().set(oldScale);game.options.fov().set(oldFov);game.options.pauseOnLostFocus=oldPause;game.options.enableVsync().set(oldVsync);game.options.hideGui=oldHide;game.setWindowActive(oldActive);
        }catch(Throwable restore){restore.printStackTrace();}
        game.stop();
    }
}

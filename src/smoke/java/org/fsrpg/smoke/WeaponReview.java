package org.fsrpg.smoke;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.*;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.RpgServer;
import org.fsrpg.client.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.lwjgl.glfw.GLFW;

/** Separate packaged-client smoke for the three camera modes and new server mechanics. */
final class WeaponReview {
    static final Path ROOT=Path.of(System.getProperty("fsrpg.smokeEvidence","../../verification/weapons-r2")).toAbsolutePath().normalize();
    final RenderFrameCapture movies=new RenderFrameCapture(ROOT);
    final List<String> passed=new java.util.concurrent.CopyOnWriteArrayList<>();
    final ArrayDeque<String> shots=new ArrayDeque<>();
    volatile Throwable failure;
    volatile Zombie hit,miss;
    int tick, warmupTicks; boolean done;
    float lockedYaw, spearBaseYaw;
    long offhandBefore;
    boolean priorActive;
    final JsonArray dualInputs = new JsonArray();
    String lastDualInput = "";
    int dualInputSequence;
    final WeaponMotionReview motion = new WeaponMotionReview(this);
    WeaponReview() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e)->{if(!done)e.setCanceled(true);});
        NeoForge.EVENT_BUS.addListener((InputEvent.Key e)->{if(!done)for(var key:Minecraft.getInstance().options.keyMappings)if(key.getKey().getValue()==e.getKey()){key.setDown(false);while(key.consumeClick()){}}});
    }
    void check(boolean value,String message){if(!value)throw new AssertionError(message);passed.add(message);System.out.println("WEAPON PASS "+message);}
    void server(Consumer<ServerPlayer> work){var g=Minecraft.getInstance();g.getSingleplayerServer().execute(()->{try{work.accept(g.getSingleplayerServer().getPlayerList().getPlayer(g.player.getUUID()));}catch(Throwable e){failure=e;}});}
    CombatActor actor(ServerPlayer p){return RpgSmoke.actor(p);}
    void action(ServerPlayer p,String a,String b){RpgServer.instance().action(p,a,b);}
    void attack(ServerPlayer p,String button,List<AttackAim.Point> points){var j=new JsonObject();j.addProperty("button",button);j.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(points)));action(p,"aimAttack",j.toString());}
    void dualClick(String button) {
        lastDualInput=button;dualInputSequence++;
        var input=new JsonObject();input.addProperty("sequence",dualInputSequence);input.addProperty("clientReviewTick",tick);
        input.addProperty("button",button);input.addProperty("expectedSkill",button.equals("left")?"dual_ll":"dual_rr");
        input.addProperty("path","CombatCamera.attack -> client intent -> authoritative server");dualInputs.add(input);
        CombatCamera.attack(button);
    }
    void observeDualInput(JsonObject observation) {
        observation.addProperty("dualInputButton",lastDualInput);observation.addProperty("dualInputSequence",dualInputSequence);
    }
    void checkDualSkill(ServerPlayer player,String id,String message) {
        check(actor(player).currentSkill()!=null&&actor(player).currentSkill().id().equals(id),message);
    }
    void start(String id) throws Exception {
        var g=Minecraft.getInstance();var observation=RenderObservations.sampler(g.player);
        movies.start(id,"actual Minecraft render frames / new weapon controls",()->{var o=observation.get();o.addProperty("style",CombatCamera.style());o.addProperty("cameraYaw",CombatCamera.yaw());o.addProperty("actorYaw",g.player.getYRot());o.addProperty("cursorX",CombatCamera.cursorX());o.addProperty("cursorY",CombatCamera.cursorY());observeDualInput(o);return o;});
    }
    void render(RenderFrameEvent.Post event){if(done)return;try{movies.frame(event);while(!shots.isEmpty())Screenshot.grab(ROOT.toFile(),shots.poll()+".png",Minecraft.getInstance().getMainRenderTarget(),1,c->{});}catch(Throwable e){failure=e;}}
    void tick(ClientTickEvent.Post event) {
        var g=Minecraft.getInstance();if(done||g.player==null||g.level==null||g.getSingleplayerServer()==null)return;
        try {
            if(failure!=null)throw new RuntimeException(failure);
            if(g.isPaused())g.setScreen(null);
            g.getFramerateLimitTracker().onInputReceived();movies.assertHealthy();
            // Let copied-world chunks, shaders and the real skin pipeline settle before recording.
            // Captured clips still contain one image for every actual rendered frame.
            if(tick==20 && warmupTicks++<240)return;
            int t=++tick;
            if(t==20) {
                Files.createDirectories(ROOT.resolve("screenshots"));KeyMapping.releaseAll();g.options.pauseOnLostFocus=false;
                g.options.framerateLimit().set(60);g.options.enableVsync().set(false);g.options.fov().set(60);g.options.guiScale().set(2);g.options.renderDistance().set(4);g.resizeDisplay();g.getTutorial().setStep(TutorialSteps.NONE);
                GLFW.glfwSetWindowTitle(g.getWindow().handle(),"FSRPG 双刀 / 长枪验证 · 屏幕二");
                server(p->{
                    p.setGameMode(GameType.SURVIVAL);p.setHealth(20);
                    for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)p.level().setBlock(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState(),3);
                    p.connection.teleport(.5,80,.5,0,0);
                    for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))p.setItemSlot(slot,ItemStack.EMPTY);
                    p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.IRON_SWORD));
                    action(p,"weaponStyle","dual_blade");actor(p).setResources(100,100);if(!actor(p).drawn())action(p,"draw","");
                });
            }
            if(t==42){g.options.setCameraType(CameraType.THIRD_PERSON_BACK);check(ClientState.drawn()&&CombatCamera.style().equals("dual_blade"),"dual style is enabled and synchronized");lockedYaw=g.player.getYRot();offhandBefore=OfficialHeldItems.offhandAttachedSubmissions();start("dual-direction-cycle");}
            if(t==46){CombatCamera.mouse(500,0);check(Math.abs(g.player.getYRot()-lockedYaw)<.01,"free orbit does not turn idle dual actor");check(Math.abs(CombatCamera.yaw()-lockedYaw)>20,"dual camera orbits behind actor");shots.add("dual-idle-orbit");}
            if(t==52)dualClick("left");
            if(t==54){lockedYaw=g.player.getYRot();CombatCamera.mouse(-300,0);check(Math.abs(g.player.getYRot()-lockedYaw)<.01,"camera orbit leaves attacking direction locked");dualClick("left");}
            if(t==61)server(p->checkDualSkill(p,"dual_ll","second left click repeats the left-hand cut"));
            if(t==66)dualClick("left");
            if(t==68)server(p->checkDualSkill(p,"dual_ll","third consecutive left click starts another left-hand cut without direction exhaustion"));
            if(t==76)server(p->{check(actor(p).leftSide()==-1&&actor(p).rightSide()==-1,"dual cuts keep neutral ready hand placement");actor(p).setResources(100,100);});
            if(t==80){shots.add("dual-left-repeat-return");dualClick("right");check(Math.abs(g.player.getYRot()-lockedYaw)<.01,"standing follow-up retains facing after recovery despite camera orbit");}
            if(t==82)dualClick("right");
            if(t==89)server(p->checkDualSkill(p,"dual_rr","second right click repeats the right-hand cut"));
            if(t==94)dualClick("right");
            if(t==96)server(p->checkDualSkill(p,"dual_rr","third consecutive right click starts another right-hand cut without direction exhaustion"));
            if(t==108){shots.add("dual-right-repeat-return");check(OfficialHeldItems.offhandAttachedSubmissions()>offhandBefore,"real offhand sword follows separate left wrist");}
            if(t==110)g.options.keyUp.setDown(true);
            if(t==121){g.options.keyUp.setDown(false);check(Math.abs(net.minecraft.util.Mth.wrapDegrees(g.player.getYRot()-CombatCamera.yaw()))<5,"camera-relative movement releases facing lock and turns actor");}
            if(t==124){movies.stop();server(p->{action(p,"draw","");});}
            if(t==140)server(p->{p.connection.teleport(.5,80,.5,0,0);p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);action(p,"weaponStyle","spear");actor(p).setResources(100,100);action(p,"draw","");});
            if(t==160){g.options.setCameraType(CameraType.THIRD_PERSON_BACK);CombatCamera.reset();spearBaseYaw=g.player.getYRot();System.out.println("SPEAR BASE YAW "+spearBaseYaw);start("spear-cursor-boundary");}
            if(t>=162&&t<170)CombatCamera.mouse(12,-3);
            if(t==171){System.out.println("SPEAR INSIDE YAW "+g.player.getYRot());check(Math.abs(g.player.getYRot()-spearBaseYaw)<.1,"inside spear rectangle cursor moves without turning player");check(CombatCamera.cursorX()>20,"spear cursor traverses screen rectangle");shots.add("spear-aim-mid");}
            if(t>=174&&t<184)CombatCamera.mouse(100,0);
            if(t==186){check(Math.abs(g.player.getYRot()-spearBaseYaw)>20,"continued boundary motion turns spear actor");shots.add("spear-boundary");}
            if(t==187){priorActive=g.isWindowActive();g.setWindowActive(true);KeyMapping.set(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(0),true);}
            if(t>=188&&t<197)CombatCamera.mouse(-14,-4);
            if(t==197)KeyMapping.set(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(0),false);
            if(t==200)server(p->check(actor(p).currentSkill()!=null&&actor(p).currentSkill().id().equals("spear_sweep")&&actor(p).attackAim().points().size()>1,"held client gesture sends sampled path and starts authoritative sweep"));
            if(t==203)g.setWindowActive(priorActive);
            if(t==205){movies.stop();server(p->{p.connection.teleport(.5,80,.5,0,0);});CombatCamera.reset();}
            if(t==218) {
                g.options.setCameraType(CameraType.THIRD_PERSON_FRONT);g.options.hideGui=true;
                check(Math.abs(net.minecraft.util.Mth.wrapDegrees(g.player.getYRot()))<.1,"server teleport resets spear camera without restoring obsolete yaw");
                // Review from an oblique front angle so the target cannot obscure both grips.
                var reviewCamera=new net.minecraft.world.entity.decoration.ArmorStand(g.level,.5,80,.5);
                reviewCamera.snapTo(.5,80,.5,-55,0);reviewCamera.yHeadRot=reviewCamera.yHeadRotO=-55;reviewCamera.setOldPosAndRot();g.setCameraEntity(reviewCamera);
                server(p->{
                    actor(p).setResources(100,100);
                    hit=target(p,.5,83.8);miss=target(p,2.4,83.5);
                });start("spear-point-and-sweep");
            }
            if(t==230){shots.add("spear-two-hand-idle");server(p->attack(p,"left",List.of(new AttackAim.Point(0,0))));}
            if(t==235)shots.add("spear-thrust-active");
            if(t==252)server(p->{check(hit.getHealth()<20,"precise spear ray hits clicked forward target");check(miss.getHealth()==20,"point thrust does not hit unrelated off-axis target");hit.setHealth(20);actor(p).setResources(100,100);attack(p,"sweep",List.of(new AttackAim.Point(0,0),new AttackAim.Point(-32,0)));});
            if(t==262)shots.add("spear-sweep-active");
            if(t==282)server(p->{check(hit.getHealth()<20&&miss.getHealth()<20,"sweep visits both ends of recorded path");hit.discard();miss.discard();});
            if(t==300){movies.stop();g.setCameraEntity(g.player);check(OfficialHeldItems.lastItemId().equals("minecraft:diamond_spear"),"actual vanilla spear reaches official ItemStack renderer");server(p->action(p,"draw",""));}
            if(t==317)server(p->{p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));action(p,"weaponStyle","rapier");action(p,"draw","");});
            if(t==338){g.options.setCameraType(CameraType.THIRD_PERSON_BACK);g.options.hideGui=false;shots.add("rapier-shoulder-camera");check(CombatCamera.style().equals("rapier"),"rapier shoulder camera retains existing style");}
            if(t==350)server(p->action(p,"draw",""));
            if(t==370)server(p->{
                p.connection.teleport(.5,80,.5,0,0);p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.IRON_SWORD));
                action(p,"weaponStyle","dual_blade");actor(p).setResources(100,100);action(p,"draw","");
            });
            if(t==392){g.options.setCameraType(CameraType.THIRD_PERSON_FRONT);g.options.hideGui=true;start("dual-front-armor");shots.add("dual-front-idle");}
            if(t==400||t==402)dualClick("left");
            if(t==423)shots.add("dual-front-left");
            if(t==427)server(p->{p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));actor(p).setResources(100,100);});
            if(t==430||t==432)dualClick("right");
            if(t==454)shots.add("dual-front-right-armor");
            if(t==465)movies.stop();
            if(t>=480 && motion.tick() && movies.finish()) {
                var capture=movies.summary();for(var c:capture.getAsJsonArray("clips")){var j=c.getAsJsonObject();check(j.get("sourceFps").getAsDouble()>=30,"actual render capture >=30fps "+j.get("id").getAsString());}
                motion.verify(capture);
                report(true,null);done=true;g.stop();
            }
        }catch(Throwable e){try{report(false,e.toString());}catch(Exception ignored){}e.printStackTrace();done=true;g.stop();}
    }
    Zombie target(ServerPlayer p,double x,double z){var mob=EntityType.ZOMBIE.create(p.level(),EntitySpawnReason.COMMAND);mob.setNoAi(true);mob.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));mob.setPos(x,80,z-80);p.level().addFreshEntity(mob);return mob;}
    RpgConfig.Definitions definitions(){try{var f=RpgServer.class.getDeclaredField("config");f.setAccessible(true);return ((RpgConfig)f.get(RpgServer.instance())).current();}catch(Exception e){throw new RuntimeException(e);}}
    void report(boolean ok,String error)throws Exception {
        var report=new JsonObject();report.addProperty("passed",ok);report.addProperty("error",error);report.add("checks",RpgConfig.JSON.toJsonTree(passed));
        report.add("dualInputs",dualInputs);
        var source=Path.of(RpgServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.addProperty("jarSha256",Files.isRegularFile(source)?java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))):"");
        RpgSmoke.windowEnvironment(report);
        report.addProperty("glVendor",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
        report.addProperty("glRenderer",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        report.addProperty("javaExecutable",ProcessHandle.current().info().command().orElse(""));
        RpgConfig.atomicWrite(ROOT.resolve("game-smoke.json"),RpgConfig.JSON.toJson(report));
    }
}

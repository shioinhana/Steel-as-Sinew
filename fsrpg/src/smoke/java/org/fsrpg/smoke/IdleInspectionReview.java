package org.fsrpg.smoke;

import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientHooks;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.config.RpgConfig;

/** Unmodified default 10–30 second waits on a real local player, with real keyboard interruption. */
final class IdleInspectionReview {
    private int tick, episode, checks, tail;
    private double scheduled = Double.NaN, firstSeen, activeAt = Double.NaN;
    private boolean recording, ending;
    private boolean inspectionScreenshotTaken;
    private int firstSeenClientTick;
    private long firstSeenNanos;
    private String action = "";
    private final Set<String> completed = new HashSet<>();
    private final JsonArray timeline = new JsonArray();
    private final long beganNanos = System.nanoTime();
    private static final Path TIMELINE = Path.of(System.getProperty("fsrpg.smokeEvidence", "../../verification/rapier-r6"))
            .toAbsolutePath().normalize().resolve("idle-inspection-timeline.json");
    int checks() { return checks; }
    private void check(boolean ok,String label) { RpgSmoke.check(ok,label); checks++; }
    private void record(String event, double presentationTick, double deadline, String current) throws Exception {
        var game=Minecraft.getInstance();var player=game.player;
        var entry=new JsonObject();entry.addProperty("event",event);entry.addProperty("episode",episode);
        entry.addProperty("clientTick",tick);entry.addProperty("worldTick",game.level.getGameTime());
        entry.addProperty("presentationTick",presentationTick);entry.addProperty("wallSeconds",(System.nanoTime()-beganNanos)/1e9);
        if(Double.isFinite(deadline))entry.addProperty("deadline",deadline);
        if(Double.isFinite(scheduled))entry.addProperty("expectedDeadline",scheduled);
        entry.addProperty("inspection",current);entry.addProperty("serverPhase",ClientState.text(ClientState.snapshot(),"phase",""));
        entry.addProperty("grounded",player.onGround());entry.addProperty("usingItem",player.isUsingItem());
        entry.addProperty("screenOpen",game.screen!=null);
        entry.addProperty("movementXZ",Math.hypot(player.getX()-player.xo,player.getZ()-player.zo));
        entry.addProperty("movementY",Math.abs(player.getY()-player.yo));
        entry.addProperty("walkAnimationSpeed",player.walkAnimation.speed(game.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
        entry.addProperty("inputHeld",game.options.keyUp.isDown()||game.options.keyDown.isDown()||game.options.keyLeft.isDown()
                ||game.options.keyRight.isDown()||game.options.keyJump.isDown()||game.options.keyShift.isDown()
                ||game.options.keyAttack.isDown()||game.options.keyUse.isDown());
        timeline.add(entry);
        var report=new JsonObject();report.addProperty("clock","PlayerAnimations.presentationTick; independent client tick count and wall-clock evidence");
        report.addProperty("minimumWaitSeconds",10);report.addProperty("maximumWaitSeconds",30);
        report.add("events",timeline);Files.createDirectories(TIMELINE.getParent());Files.writeString(TIMELINE,RpgConfig.JSON.toJson(report));
    }
    boolean tick(RenderFrameCapture movie) throws Exception {
        var game=Minecraft.getInstance();var player=game.player;
        tick++;
        if(tick==1) RpgSmoke.server(p->{
            p.connection.teleport(.5,80,.5,0,3);p.getFoodData().setFoodLevel(20);
            RpgSmoke.actor(p).setResources(100,100);
            if(!RpgSmoke.actor(p).drawn())RpgServer.instance().action(p,"draw","");
        });
        game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        game.options.hideGui=true;game.options.fov().set(42);
        double now=PlayerAnimations.presentationTick();
        if(tick>2700) {
            record("timeout",now,PlayerAnimations.inspectionDeadline(player.getUUID()),PlayerAnimations.inspectionAction(player.getUUID()));
            throw new AssertionError("idle inspection default timer failed to finish three episodes; see idle-inspection-timeline.json");
        }
        if(ending) {
            tail++;
            game.options.keyRight.setDown(tail<12);
            if(tail==5){check(PlayerAnimations.inspectionAction(player.getUUID()).isEmpty(),"actual movement immediately interrupts inspection");record("movement-interrupted",now,PlayerAnimations.inspectionDeadline(player.getUUID()),PlayerAnimations.inspectionAction(player.getUUID()));}
            if(tail==15) RpgSmoke.mouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
            if(tail==19)check(!ClientState.text(ClientState.snapshot(),"phase","").equals("READY"),"attack remains responsive after inspection interruption");
            if(tail>=35&&tail<=49)RpgSmoke.guardKey(true);
            if(tail==48)check(ClientState.text(ClientState.snapshot(),"phase","").equals("GUARDING"),"inspection to movement to attack to guard retains server input timing");
            if(tail==50)RpgSmoke.guardKey(false);
            if(tail==65) {movie.stop();ClientHooks.send("draw","");}
            return tail>=85;
        }
        double deadline=PlayerAnimations.inspectionDeadline(player.getUUID());
        String current=PlayerAnimations.inspectionAction(player.getUUID());
        if(tick==1||tick%20==0)record("observation",now,deadline,current);
        if(Double.isFinite(deadline) && Double.isNaN(scheduled)) {
            scheduled=deadline;if(episode==0){firstSeen=now;firstSeenClientTick=tick;firstSeenNanos=System.nanoTime();}
            record("deadline-scheduled",now,deadline,current);
            check(deadline-firstSeen>=198&&deadline-firstSeen<=601,"actual default idle wait in 10–30 seconds episode="+episode);
        }
        if(Double.isFinite(scheduled)&&action.isEmpty()&&current.isEmpty()&&Double.isNaN(activeAt)
                &&(!Double.isFinite(deadline)||Math.abs(deadline-scheduled)>.001)) {
            record("deadline-reset-before-start",now,deadline,current);
            throw new AssertionError("stationary inspection deadline reset before start; see idle-inspection-timeline.json");
        }
        if(!recording && Double.isFinite(scheduled) && now>=scheduled-20) {
            var observe=RenderObservations.sampler(player);
            movie.start("idle-inspection-"+(episode+1),"actual default idle timer, official sword rendering and interruption",()->{
                var sample=observe.get();
                sample.addProperty("inspection",PlayerAnimations.inspectionAction(player.getUUID()));
                sample.addProperty("serverPhase",ClientState.text(ClientState.snapshot(),"phase",""));
                sample.addProperty("presentationTick",PlayerAnimations.presentationTick());
                double sampledDeadline=PlayerAnimations.inspectionDeadline(player.getUUID());
                if(Double.isFinite(sampledDeadline))sample.addProperty("inspectionDeadline",sampledDeadline);
                sample.addProperty("inspectionFixtureClientTick",tick);
                return sample;
            });recording=true;
        }
        if(!current.isEmpty()&&action.isEmpty()) {
            action=current;activeAt=now;inspectionScreenshotTaken=false;
            record("inspection-started",now,deadline,current);
            check(now>=scheduled-1&&now<=scheduled+2&&now-firstSeen>=198,"real inspection starts at scheduled game time");
            int actualWait=tick-firstSeenClientTick;
            check(actualWait>=198&&actualWait<=603,"independent client ticks confirm actual 10–30 second inspection wait episode="+episode+" waited="+actualWait
                    +" wallSeconds="+((System.nanoTime()-firstSeenNanos)/1e9));
            check(ClientState.text(ClientState.snapshot(),"phase","").equals("READY"),"cosmetic inspection does not start a server attack");
        }
        if(!action.isEmpty()) {
            if(!inspectionScreenshotTaken&&now-activeAt>=38){RpgSmoke.screenshot("moving/inspection-"+(episode+1));inspectionScreenshotTaken=true;}
            if(episode==2 && now-activeAt>=42) {record("movement-interrupt-requested",now,deadline,current);ending=true;game.options.keyRight.setDown(true);return false;}
            if(current.isEmpty() && now-activeAt>50) {
                check(ClientState.text(ClientState.snapshot(),"phase","").equals("READY"),"full inspection returns to ready without combat state changes");
                check(ClientState.number(ClientState.snapshot(),"stamina",0)>=99,"inspection consumes no stamina");
                completed.add(action);
                if(episode==1)check(completed.size()==2,"both different inspection animations completed in actual client");
                record("inspection-finished",now,deadline,current);
                activeAt=firstSeen=now;firstSeenClientTick=tick;firstSeenNanos=System.nanoTime();action="";episode++;
            }
        } else if(recording && episode>0 && now-activeAt>=15) {
            movie.stop();recording=false;scheduled=Double.NaN;activeAt=Double.NaN;
        }
        return false;
    }
}

package org.fsrpg.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Old 100-point arrow prediction and 300-point optical scope; all unlocks and speeds are server data. */
public final class ArcheryPresentation {
    private static final List<Particle> trail=new ArrayList<>();
    private static CameraType cameraBeforeScope;
    private static boolean scoped,releasing;
    private static Object world;
    private static int tickCounter;
    private static String previousAction="";
    private static double magnification=1,releaseMagnification=1;
    private static long trajectorySamples,landingMarkers;
    private static Vec3 lastLanding;
    private static double lastPreviewSpeed;
    private static float lastFov;
    private ArcheryPresentation() {}
    public static boolean scoped(){return scoped;}
    public static double magnification(){return magnification;}
    public static long trajectorySamples(){return trajectorySamples;}
    public static long landingMarkers(){return landingMarkers;}
    public static Vec3 lastLanding(){return lastLanding;}
    public static double lastPreviewSpeed(){return lastPreviewSpeed;}
    public static float lastFov(){return lastFov;}
    public static void tick(ClientTickEvent.Post event) {
        var game=Minecraft.getInstance();
        if(world!=game.level){reset();world=game.level;}
        var bow=ClientState.object(ClientState.snapshot(),"archery");
        boolean active=game.player!=null&&game.level!=null&&game.player.isAlive()&&!game.player.isSpectator()
                &&ClientState.drawn()&&ClientState.bool(bow,"active",false)&&ClientState.text(bow,"stance","").equals("SNIPE");
        var intent=PlayerAnimations.localIntent();
        String action=intent==null?ClientState.text(bow,"animationAction",""):intent.action();
        boolean release=action.endsWith("_release")&&PlayerAnimations.localSeconds()<.3-1e-6;
        releasing=release;
        if(release&&!previousAction.endsWith("_release"))releaseMagnification=magnification;
        previousAction=action;
        boolean next=active&&game.screen==null&&ClientState.bool(bow,"scopeUnlocked",false)
                &&(ClientState.bool(bow,"charging",false)||release);
        if(next&&!scoped)cameraBeforeScope=game.options.getCameraType();
        if(next) {
            game.options.setCameraType(CameraType.FIRST_PERSON);
            double full=ClientState.number(bow,"scopeFullMagnification",1);
            magnification=release?Math.max(1,releaseMagnification):1+(Math.max(1,full)-1)*LegacyMotion.bowChargeProgress();
        } else if(scoped) {
            // The general combat camera restores the pre-draw camera on sheath/logout.
            // Restore our temporary scope only while that combat camera is still active.
            if(ClientState.drawn()&&game.player!=null&&game.level!=null&&game.options.getCameraType().isFirstPerson())
                game.options.setCameraType(cameraBeforeScope==null?CameraType.THIRD_PERSON_BACK:cameraBeforeScope);
            cameraBeforeScope=null;magnification=1;
        }
        if(next!=scoped)clearTrail();scoped=next;
        if(!active||game.screen!=null||!ClientState.bool(bow,"trajectoryUnlocked",false)||game.options.hideGui) {clearTrail();return;}
        if((tickCounter++&1)==1)return;
        clearTrail();
        double power=ClientState.bool(bow,"charging",false)?LegacyMotion.bowChargeProgress():1;
        double speed=ClientState.number(bow,"fullSnipeArrowSpeed",0)*Math.max(.25,power);
        if(!Double.isFinite(speed)||speed<=0)return;
        lastPreviewSpeed=speed;
        Vec3 look=game.player.getLookAngle();
        Vec3 position=game.player.getEyePosition().add(0,-.1,0).add(look.scale(.4));
        Vec3 velocity=look.scale(speed),landing=null;
        for(int step=0;step<140;step++) {
            Vec3 nextPosition=position.add(velocity);
            var hit=game.level.clip(new ClipContext(position,nextPosition,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,game.player));
            if(hit.getType()!=HitResult.Type.MISS){landing=hit.getLocation();break;}
            position=nextPosition;
            // Vanilla arrow order, exactly as the original SnipeTrajectory: move, drag, gravity.
            velocity=new Vec3(velocity.x*.99,velocity.y*.99-.05,velocity.z*.99);
            if(!game.options.getCameraType().isFirstPerson()&&step%3==0)particle(ParticleTypes.END_ROD,position);
            if(position.y<game.level.getMinY())break;
        }
        lastLanding=landing;trajectorySamples++;
        if(landing!=null) {
            for(int i=0;i<10;i++) {
                double angle=i/10.0*Math.PI*2;
                particle(ParticleTypes.CRIT,landing.add(Math.cos(angle)*.45,.08,Math.sin(angle)*.45));
            }
            particle(ParticleTypes.FLAME,landing.add(0,.1,0));landingMarkers++;
        }
    }
    private static void particle(net.minecraft.core.particles.ParticleOptions type,Vec3 at) {
        var p=Minecraft.getInstance().particleEngine.createParticle(type,at.x,at.y,at.z,0,0,0);
        if(p!=null){p.setLifetime(3);trail.add(p);}
    }
    private static void clearTrail(){trail.forEach(Particle::remove);trail.clear();}
    public static void reset() {
        var game=Minecraft.getInstance();
        if(scoped&&ClientState.drawn()&&game.level!=null&&game.options.getCameraType().isFirstPerson()&&cameraBeforeScope!=null)
            game.options.setCameraType(cameraBeforeScope);
        scoped=releasing=false;cameraBeforeScope=null;previousAction="";magnification=releaseMagnification=1;lastLanding=null;clearTrail();
    }
    /** Divide the focal length, rather than the FOV angle, by the optical magnification. */
    public static float magnifiedFov(float fov,double zoom) {
        if(!Double.isFinite(zoom)||zoom<=1)return fov;
        return (float)Math.toDegrees(2*Math.atan(Math.tan(Math.toRadians(fov)*.5)/zoom));
    }
    public static void fov(ViewportEvent.ComputeFov event) {
        if(scoped&&event.usedConfiguredFov()) {
            // Charge uses the rendering sub-tick, so optical motion stays smooth between server ticks.
            if(!releasing) {
                var bow=ClientState.object(ClientState.snapshot(),"archery");
                double full=ClientState.number(bow,"scopeFullMagnification",1);
                magnification=1+(Math.max(1,full)-1)*LegacyMotion.bowChargeProgress();
            }
            lastFov=magnifiedFov(event.getFOV(),magnification);event.setFOV(lastFov);
        }
    }
}

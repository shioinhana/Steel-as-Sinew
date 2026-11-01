package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsrpg.network.RpgAnimationPayload;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Source-faithful legacy sampling, separate from the new spear's procedural layers. */
final class LegacyMotion {
    private LegacyMotion() {}
    static boolean applies(ModelDocument model) { return model.rig().id().startsWith("fsrpg.legacy_"); }
    static String kind(ModelDocument model) {
        return model.rig().id().contains("dual_blade") ? "dual_blade" : model.rig().id().contains("bow") ? "bow" : "rapier";
    }
    static String source(Clip clip) {
        for (String tag : clip.tags()) if (tag.startsWith("source:")) return tag.substring(7);
        return clip.id();
    }
    static double actionSeconds(Clip clip, RpgAnimationPayload payload, double elapsed, double runningSeconds) {
        return actionSeconds(clip,payload,elapsed,runningSeconds,false);
    }
    static double actionSeconds(Clip clip, RpgAnimationPayload payload, double elapsed, double runningSeconds,boolean firstPerson) {
        double chamber=firstPerson?.14:.04,extended=firstPerson?.25:.20;
        if(RapierMotion.flow(clip)&&elapsed>=0)
            return RapierMotion.clipTime(clip,elapsed,payload.windupTicks(),payload.activeTicks(),payload.recoveryTicks());
        var game=Minecraft.getInstance();
        if(payload.action().startsWith("bow_") && payload.action().endsWith("_charge")
                && game.player!=null && payload.entityId().equals(game.player.getUUID()))return clip.duration()*bowChargeProgress();
        if(source(clip).startsWith("rapier_thrust") && elapsed>=0) {
            if(elapsed<payload.windupTicks())return chamber*elapsed/Math.max(1,payload.windupTicks());
            elapsed-=payload.windupTicks();
            if(elapsed<payload.activeTicks())return chamber+(extended-chamber)*elapsed/Math.max(1,payload.activeTicks());
            return extended+(.5-extended)*Math.clamp((elapsed-payload.activeTicks())/Math.max(1,payload.recoveryTicks()),0,1);
        }
        // An old attack's authored timing occupies the complete authoritative action interval.
        // No current-version 35/65 percent contact markers may alter the imported stroke.
        if (elapsed >= 0) return clip.duration() * Math.clamp(elapsed /
                Math.max(1, payload.windupTicks()+payload.activeTicks()+payload.recoveryTicks()), 0, 1);
        if (payload.action().equals("rapier_full_charge")) return chamber;
        if (payload.action().equals("rapier_charge")) {
            var charge = ClientState.object(ClientState.snapshot(), "charge");
            double end=clip.tags().contains("rapier_charge:0100")?clip.duration():chamber;
            return end * Math.clamp(ClientState.number(charge,"elapsedTicks",runningSeconds*20)/7,0,1);
        }
        return runningSeconds;
    }
    static double bowChargeProgress() {
        var game=Minecraft.getInstance();var archery=ClientState.object(ClientState.snapshot(),"archery");
        double progress=ClientState.number(archery,"chargeProgress",0);
        if(game.level!=null && ClientState.bool(archery,"charging",false) && archery.has("chargeStartTick")) {
            double elapsed=game.level.getGameTime()-ClientState.number(archery,"chargeStartTick",game.level.getGameTime())
                    +game.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            progress=Math.max(progress,elapsed/Math.max(1,ClientState.number(archery,"requiredDrawTicks",10)));
        }
        return Math.clamp(progress,0,1);
    }
    static String gait(ModelDocument model, LivingEntity entity, float speed, RpgAnimationPayload payload) {
        String prefix = "legacy_"+kind(model)+"_";
        // AirPose keeps the actual takeoff pose. A binding first created in air uses
        // a neutral authored stance instead of inventing a guard-like jump/fall pose.
        if (!entity.onGround()) return prefix+"static_idle";
        if (speed > .035f) {
            double radians=Math.toRadians(entity.getYRot());
            double dx=entity.getX()-entity.xo,dz=entity.getZ()-entity.zo;
            double forward=dz*Math.cos(radians)-dx*Math.sin(radians);
            double left=dx*Math.cos(radians)+dz*Math.sin(radians);
            String direction=Math.abs(left)>Math.abs(forward)?(left>0?"_left":"_right"):(forward<0?"_back":"");
            String name=entity.isCrouching()?"crouch_walk":entity.isSprinting()?"run_hold":"walk_hold";
            if(model.rig().clips().containsKey(prefix+name+direction))return prefix+name+direction;
            return prefix+name;
        }
        if(entity.isCrouching())return prefix+"crouch_idle";
        return prefix+"static_idle";
    }
    static Map<String,Pose> sample(ModelDocument model, RpgAnimationPayload payload, double seconds,
                                  double locomotionSeconds, AvatarRenderState state, LivingEntity entity) {
        var rig=model.rig(); Clip action=rig.clips().get(payload.action());
        String source=source(action);
        boolean idle=source.equals("static_idle") || source.equals("idle_upper");
        boolean fullAction=source.equals("draw") || source.equals("put_away") || source.equals("draw_bow")
                || source.equals("put_away_bow") || source.startsWith("rapier_dodge_");
        String base=gait(model,entity,state.walkAnimationSpeed,payload);
        if(!rig.clips().containsKey(base))base="legacy_"+kind(model)+"_static_idle";
        Map<String,Pose> pose = new HashMap<>(local(model,rig.clips().get(base),locomotionSeconds));
        if (!idle) {
            var sampled=local(model,action,seconds);
            if(fullAction)pose=new HashMap<>(sampled);
            else for(String id:action.tracks().keySet())pose.put(id,sampled.get(id));
        }
        // PlayerGeoRenderer's old runtime overlays are part of the old presentation,
        // including hip-yaw cancellation and the bow's side-on root frame.
        if(kind(model).equals("bow") && !fullAction) {
            replaceRotation(pose,"root",new Vector3f(0,(float)-Math.PI/2,0));
            replaceRotation(pose,"hip",new Vector3f());
            rotate(pose,"body",0,0,-Math.toRadians(Math.clamp(entity.getXRot(),-80,80)));
        } else if(kind(model).equals("rapier") && !fullAction) {
            var hip=pose.get("hip");
            float hipYaw=hip==null?0:hip.fsQuaternion().getEulerAnglesZYX(new Vector3f()).y;
            rotate(pose,"body",0,-hipYaw+Math.toRadians(65),0);
        }
        if(!kind(model).equals("bow")) rotate(pose,"head",-Math.toRadians(entity.getXRot()),Math.toRadians(state.yRot),0);
        return Map.copyOf(pose);
    }
    static Map<String,Pose> local(ModelDocument model,Clip clip,double seconds) {
        return LegacyPlayback.sample(model,clip,seconds);
    }
    private static void rotate(Map<String,Pose> pose,String id,double x,double y,double z) {
        Pose original=pose.get(id);if(original==null)return;
        Vector3f e=original.fsQuaternion().getEulerAnglesZYX(new Vector3f()).add((float)x,(float)y,(float)z);
        replaceRotation(pose,id,e);
    }
    private static void replaceRotation(Map<String,Pose> pose,String id,Vector3f e) {
        Pose original=pose.get(id);if(original==null)return;
        var q=new Quaternionf().rotationZYX(e.z,e.y,e.x);
        pose.put(id,new Pose(FsvoxAnimation.fromQuaternion(q),original.position(),original.scale()));
    }
    static float knee(Map<String,Pose> pose,String side) {
        Pose p=pose.get(side+"_foreleg");
        return p==null?0:Math.max(0,p.fsQuaternion().getEulerAnglesZYX(new Vector3f()).x);
    }
}

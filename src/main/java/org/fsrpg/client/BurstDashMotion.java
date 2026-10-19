package org.fsrpg.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import org.fsrpg.network.RpgBurstDashPayload;

/** Client's ordinary travel consumes one server-approved horizontal step; gravity/jump/collisions stay vanilla. */
public final class BurstDashMotion {
    private static long serverTick=-1,receivedTick,sequence;
    private static double x,z;
    private static boolean active;
    private static ClientLevel acceptedLevel;
    private static LivingEntity acceptedPlayer;
    private BurstDashMotion() {}
    public static void accept(RpgBurstDashPayload payload) {
        var game=Minecraft.getInstance();if(game.level==null||game.player==null)return;
        if(acceptedLevel!=game.level||acceptedPlayer!=game.player)reset();
        if(payload.serverTick()<serverTick)return;
        if(!Double.isFinite(payload.x())||!Double.isFinite(payload.z())||Math.hypot(payload.x(),payload.z())>32)return;
        serverTick=payload.serverTick();sequence=payload.sequence();x=payload.x();z=payload.z();active=payload.active();receivedTick=game.level.getGameTime();acceptedLevel=game.level;acceptedPlayer=game.player;
    }
    public static void beforeMove(LivingEntity entity) {
        var game=Minecraft.getInstance();
        if(active&&(acceptedLevel!=game.level||acceptedPlayer!=game.player)){reset();return;}
        if(!active||entity!=game.player||game.level==null||!entity.isAlive()||entity.isPassenger()||entity.isFallFlying())return;
        long age=game.level.getGameTime()-receivedTick;if(age<0||age>4){reset();return;}
        var old=entity.getDeltaMovement();entity.setDeltaMovement(x,old.y,z);
    }
    public static boolean active(){return active;}
    public static long sequence(){return sequence;}
    public static void reset(){active=false;serverTick=-1;sequence=0;x=z=0;acceptedLevel=null;acceptedPlayer=null;}
}

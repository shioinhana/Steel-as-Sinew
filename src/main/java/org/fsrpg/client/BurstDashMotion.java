package org.fsrpg.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.fsrpg.network.RpgBurstDashPayload;

/** Apply an authorization once on receipt; subsequent movement is entirely ordinary vanilla physics. */
public final class BurstDashMotion {
    private static final GroundImpulseReceipt receipt=new GroundImpulseReceipt();
    private static ClientLevel requestedLevel;
    private static net.minecraft.client.player.LocalPlayer requestedPlayer;
    private static long lastAppliedTick=-1;
    private static double lastX,lastZ;
    private BurstDashMotion(){}
    private static GroundImpulseReceipt.Context context(){var game=Minecraft.getInstance();return game.player==null||game.level==null?null:
            new GroundImpulseReceipt.Context(game.player.getUUID(),game.player.getId(),game.level.dimension().identifier().toString());}
    /** Adds the context before sending a press or its held-sprint renewal. */
    public static String prepareIntent(String argument){
        var game=Minecraft.getInstance();
        // A quick physical tap still receives its one already-paid impulse after the release packet.
        if(argument.equals("up"))return argument;
        String[] parts=argument.trim().split("\\s+");
        if(parts.length!=4||!parts[0].equals("down")||game.player==null||game.level==null)throw new IllegalArgumentException("invalid grounded Shift intent");
        if(requestedLevel!=game.level||requestedPlayer!=game.player){reset();requestedLevel=game.level;requestedPlayer=game.player;}
        long request=Long.parseLong(parts[3]);receipt.expect(context(),request,game.level.getGameTime());
        return argument+" "+game.level.getGameTime()+" "+game.player.getId()+" "+game.level.dimension().identifier();
    }
    public static void accept(RpgBurstDashPayload payload){
        var game=Minecraft.getInstance();
        if(game.player==null||game.level==null||requestedLevel!=game.level||requestedPlayer!=game.player){reset();return;}
        if(!game.player.isAlive()||game.player.isPassenger()||game.player.isFallFlying()||game.screen!=null||!game.isWindowActive()){receipt.cancel();return;}
        var packetContext=new GroundImpulseReceipt.Context(payload.playerId(),payload.entityId(),payload.dimension());
        if(!receipt.accept(context(),packetContext,payload.requestId(),payload.sequence(),payload.serverTick(),game.level.getGameTime(),payload.x(),payload.z()))return;
        var old=game.player.getDeltaMovement();game.player.setDeltaMovement(payload.x(),old.y,payload.z());
        lastX=payload.x();lastZ=payload.z();lastAppliedTick=game.level.getGameTime();
    }
    public static boolean approved(long requestId){var game=Minecraft.getInstance();return requestedLevel==game.level&&requestedPlayer==game.player&&receipt.approved(requestId);}
    /** Diagnostic only, never a request to override this or a later move. */
    public static boolean active(){var game=Minecraft.getInstance();return game.level==requestedLevel&&game.player==requestedPlayer&&game.level!=null&&game.level.getGameTime()==lastAppliedTick;}
    public static long sequence(){return receipt.sequence();}
    public static void cancelPending(){receipt.cancel();}
    public static long applicationCount(){return receipt.applications();}
    public static long lastAppliedTick(){return lastAppliedTick;}
    public static double lastAppliedX(){return lastX;}
    public static double lastAppliedZ(){return lastZ;}
    public static void reset(){receipt.reset();requestedLevel=null;requestedPlayer=null;lastAppliedTick=-1;lastX=lastZ=0;}
}

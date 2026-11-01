package org.fsrpg.client;

import net.minecraft.client.Minecraft;
import org.fsrpg.network.RpgSwordMotionPayload;

/** Consume server collision endpoints. No client pose or mouse input authorizes translation. */
public final class SwordMotionClient {
    private static final SwordMotionReceipt receipt=new SwordMotionReceipt();
    private static final SwordTravelPresentation.State presentation=new SwordTravelPresentation.State();
    private static Object level,player;
    private static long lastTick=-1;
    private static double travelFraction=1;
    private static long actionSequence=Long.MIN_VALUE;
    private static boolean active;
    private static final java.util.LinkedHashMap<Long,Travel> travels=new java.util.LinkedHashMap<>();
    private record Scalar(double from,double target,double at) {
        double sample(double tick){return from+(target-from)*Math.clamp(tick-at,0,1);}
        Scalar advance(double next,double tick){return next==target?this:new Scalar(sample(tick),next,tick);}
    }
    private record Travel(Scalar expected,Scalar actual,double fraction,boolean active) {}
    private SwordMotionClient() {}
    public static void accept(RpgSwordMotionPayload value) {
        var game=Minecraft.getInstance();
        if(game.player==null||game.level==null){reset();return;}
        if(level!=game.level||player!=game.player){reset();level=game.level;player=game.player;}
        if(!value.playerId().equals(game.player.getUUID())||value.entityId()!=game.player.getId()
                ||!value.dimension().equals(game.level.dimension().identifier().toString())||!game.player.isAlive()
                ||game.player.isPassenger())return;
        if(!receipt.accept(value.sequence(),value.serverTick(),value.x(),value.y(),value.z()))return;
        active=value.active();
        double receivedAt=PlayerAnimations.presentationTick();
        Travel old=travels.get(value.sequence());
        travels.put(value.sequence(),new Travel(old==null?new Scalar(0,value.expectedAdvance(),receivedAt):old.expected.advance(value.expectedAdvance(),receivedAt),
                old==null?new Scalar(0,value.actualAdvance(),receivedAt):old.actual.advance(value.actualAdvance(),receivedAt),value.travelFraction(),value.active()));
        while(travels.size()>8)travels.remove(travels.keySet().iterator().next());
        presentation.accept(value.sequence(),value.serverTick(),receivedAt,value.appliedX(),value.appliedY(),value.appliedZ(),value.active());
        actionSequence=value.sequence();
        travelFraction=Double.isFinite(value.travelFraction())?Math.clamp(value.travelFraction(),0,1):1;
        var endpoint=new net.minecraft.world.phys.Vec3(value.x(),value.y(),value.z());
        game.player.setPos(endpoint);
        // The receipt and its visual debt are one presentation update. A later
        // vanilla ACK targets this same endpoint, so it cannot expose an early
        // teleport followed by a backwards visual correction on the next frame.
        game.player.setOldPosAndRot(endpoint,game.player.yRotO,game.player.xRotO);
        var velocity=game.player.getDeltaMovement();
        game.player.setDeltaMovement(0,velocity.y,0);
        lastTick=value.serverTick();
    }
    public static long lastAppliedTick(){return lastTick;}
    public static double travelFraction(){return travelFraction;}
    public static long actionSequence(){return actionSequence;}
    public static boolean active(){return active;}
    public static double expectedAdvance(){return expectedAdvance(actionSequence);}
    public static double actualAdvance(){return actualAdvance(actionSequence);}
    public static boolean hasTravel(long sequence){return travels.containsKey(sequence);}
    public static double expectedAdvance(long sequence){var t=travels.get(sequence);return t==null?0:t.expected.sample(PlayerAnimations.presentationTick());}
    public static double actualAdvance(long sequence){var t=travels.get(sequence);return t==null?0:t.actual.sample(PlayerAnimations.presentationTick());}
    public static double targetAdvance(long sequence){var t=travels.get(sequence);return t==null?0:t.expected.target;}
    public static double travelFraction(long sequence){var t=travels.get(sequence);return t==null?1:t.fraction;}
    public static boolean active(long sequence){var t=travels.get(sequence);return t!=null&&t.active;}
    /** Shared visual displacement only; authority, collision, reach and vanilla input remain untouched. */
    public static net.minecraft.world.phys.Vec3 renderOffset(net.minecraft.world.entity.Entity entity) {
        var game=Minecraft.getInstance();
        if(entity!=game.player||entity!=player||game.level!=level||!entity.isAlive()||entity.isPassenger())return net.minecraft.world.phys.Vec3.ZERO;
        var offset=presentation.sample(PlayerAnimations.presentationTick());
        return new net.minecraft.world.phys.Vec3(offset.x(),offset.y(),offset.z());
    }
    public static void reset(){receipt.reset();presentation.reset();travels.clear();level=player=null;lastTick=-1;travelFraction=1;actionSequence=Long.MIN_VALUE;active=false;}
}

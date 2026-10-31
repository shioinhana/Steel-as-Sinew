package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Collision-resolved server endpoint. This is an authoritative receipt, never a client movement request. */
public record RpgSwordMotionPayload(UUID playerId,int entityId,String dimension,long sequence,long serverTick,
        double x,double y,double z,boolean active,double travelFraction,double expectedAdvance,double actualAdvance,
        double appliedX,double appliedY,double appliedZ) implements CustomPacketPayload {
    public RpgSwordMotionPayload {
        if(playerId==null||dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||sequence<0||serverTick<0)throw new IllegalArgumentException("invalid sword motion identity");
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(y)>30_000_000||Math.abs(z)>30_000_000)throw new IllegalArgumentException("invalid sword motion endpoint");
        if(!Double.isFinite(travelFraction)||travelFraction<0||travelFraction>1||!Double.isFinite(expectedAdvance)||expectedAdvance<0||expectedAdvance>3||!Double.isFinite(actualAdvance)||actualAdvance<0||actualAdvance>3)throw new IllegalArgumentException("invalid sword motion progress");
        if(!Double.isFinite(appliedX)||!Double.isFinite(appliedY)||!Double.isFinite(appliedZ)||Math.abs(appliedX)>3||Math.abs(appliedY)>3||Math.abs(appliedZ)>3)throw new IllegalArgumentException("invalid applied sword motion");
        if(!active&&(appliedX!=0||appliedY!=0||appliedZ!=0))throw new IllegalArgumentException("inactive sword motion cannot apply displacement");
    }
    public RpgSwordMotionPayload(UUID playerId,int entityId,String dimension,long sequence,long serverTick,double x,double y,double z,boolean active,double travelFraction,double expectedAdvance,double actualAdvance){this(playerId,entityId,dimension,sequence,serverTick,x,y,z,active,travelFraction,expectedAdvance,actualAdvance,0,0,0);}
    public RpgSwordMotionPayload(UUID playerId,int entityId,String dimension,long sequence,long serverTick,double x,double y,double z,boolean active,double travelFraction){this(playerId,entityId,dimension,sequence,serverTick,x,y,z,active,travelFraction,0,0);}
    public static final Type<RpgSwordMotionPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("fsrpg","sword_motion"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RpgSwordMotionPayload> CODEC=StreamCodec.of(
        (b,p)->{b.writeUUID(p.playerId);b.writeVarInt(p.entityId);b.writeUtf(p.dimension,128);b.writeVarLong(p.sequence);b.writeVarLong(p.serverTick);b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);b.writeBoolean(p.active);b.writeDouble(p.travelFraction);b.writeDouble(p.expectedAdvance);b.writeDouble(p.actualAdvance);b.writeDouble(p.appliedX);b.writeDouble(p.appliedY);b.writeDouble(p.appliedZ);},
        b->new RpgSwordMotionPayload(b.readUUID(),b.readVarInt(),b.readUtf(128),b.readVarLong(),b.readVarLong(),b.readDouble(),b.readDouble(),b.readDouble(),b.readBoolean(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

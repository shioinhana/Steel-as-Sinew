package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One server-approved velocity change. Teleport positions still use vanilla's acknowledged teleport. */
public record RpgFootworkMotionPayload(UUID playerId,int entityId,String dimension,long sequence,long serverTick,
                                      boolean vertical,double x,double y,double z) implements CustomPacketPayload {
    public static final Type<RpgFootworkMotionPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("fsrpg","footwork_motion"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RpgFootworkMotionPayload> CODEC=StreamCodec.of(
        (b,p)->{b.writeUUID(p.playerId);b.writeVarInt(p.entityId);b.writeUtf(p.dimension,128);b.writeVarLong(p.sequence);b.writeVarLong(p.serverTick);b.writeBoolean(p.vertical);b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);},
        b->new RpgFootworkMotionPayload(b.readUUID(),b.readVarInt(),b.readUtf(128),b.readVarLong(),b.readVarLong(),b.readBoolean(),b.readDouble(),b.readDouble(),b.readDouble()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

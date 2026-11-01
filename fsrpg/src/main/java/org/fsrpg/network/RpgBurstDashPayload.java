package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One authorized XZ velocity assignment, scoped to the physical press, avatar and dimension. No Y is sent. */
public record RpgBurstDashPayload(UUID playerId,int entityId,String dimension,long requestId,long sequence,long serverTick,double x,double z) implements CustomPacketPayload {
    public static final Type<RpgBurstDashPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("fsrpg","burst_dash"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RpgBurstDashPayload> CODEC=StreamCodec.of(
            (buf,p)->{buf.writeUUID(p.playerId);buf.writeVarInt(p.entityId);buf.writeUtf(p.dimension,128);buf.writeVarLong(p.requestId);buf.writeVarLong(p.sequence);buf.writeVarLong(p.serverTick);buf.writeDouble(p.x);buf.writeDouble(p.z);},
            buf->new RpgBurstDashPayload(buf.readUUID(),buf.readVarInt(),buf.readUtf(128),buf.readVarLong(),buf.readVarLong(),buf.readVarLong(),buf.readDouble(),buf.readDouble()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

package org.fsrpg.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Approved horizontal movement only; vertical physics is never sent or reset by a burst. */
public record RpgBurstDashPayload(long sequence,long serverTick,double x,double z,boolean active) implements CustomPacketPayload {
    public static final Type<RpgBurstDashPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("fsrpg","burst_dash"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RpgBurstDashPayload> CODEC=StreamCodec.of(
            (buf,p)->{buf.writeVarLong(p.sequence);buf.writeVarLong(p.serverTick);buf.writeDouble(p.x);buf.writeDouble(p.z);buf.writeBoolean(p.active);},
            buf->new RpgBurstDashPayload(buf.readVarLong(),buf.readVarLong(),buf.readDouble(),buf.readDouble(),buf.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

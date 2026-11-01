package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Marks only an actually committed guarded damage event; it never changes health or velocity. */
public record RpgGuardImpactPayload(UUID targetId,int causeId,int directId,boolean damageFollows) implements CustomPacketPayload {
    public static final Type<RpgGuardImpactPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("fsrpg","guard_impact"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RpgGuardImpactPayload> CODEC=StreamCodec.of(
            (b,v)->{b.writeUUID(v.targetId);b.writeVarInt(v.causeId);b.writeVarInt(v.directId);b.writeBoolean(v.damageFollows);},
            b->new RpgGuardImpactPayload(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

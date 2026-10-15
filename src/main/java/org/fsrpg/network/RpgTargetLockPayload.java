package org.fsrpg.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Small server-authored lock update; no victim or damage requests travel upstream. */
public record RpgTargetLockPayload(String json) implements CustomPacketPayload {
    public static final Type<RpgTargetLockPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("fsrpg","target_lock"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RpgTargetLockPayload> CODEC=StreamCodec.of(
            (buffer,value)->buffer.writeUtf(value.json,2048),buffer->new RpgTargetLockPayload(buffer.readUtf(2048)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

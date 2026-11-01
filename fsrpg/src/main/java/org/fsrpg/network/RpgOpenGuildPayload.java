package org.fsrpg.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent only after the server validates a real guild interaction. */
public record RpgOpenGuildPayload(boolean open) implements CustomPacketPayload {
    public static final Type<RpgOpenGuildPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fsrpg", "open_guild"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpgOpenGuildPayload> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeBoolean(value.open), buf -> new RpgOpenGuildPayload(buf.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

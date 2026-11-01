package org.fsrpg.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Only intent is sent by clients; timing, resources and hits belong to the server. */
public record RpgActionPayload(String action, String argument) implements CustomPacketPayload {
    public static final Type<RpgActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fsrpg", "action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpgActionPayload> CODEC = StreamCodec.of(
        (buf, value) -> { buf.writeUtf(value.action, 32); buf.writeUtf(value.argument, 4096); },
        buf -> new RpgActionPayload(buf.readUtf(32), buf.readUtf(4096)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

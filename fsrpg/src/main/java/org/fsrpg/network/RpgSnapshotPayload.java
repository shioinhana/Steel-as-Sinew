package org.fsrpg.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RpgSnapshotPayload(String json) implements CustomPacketPayload {
    public static final Type<RpgSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fsrpg", "snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpgSnapshotPayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeUtf(value.json, 262144), buf -> new RpgSnapshotPayload(buf.readUtf(262144)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

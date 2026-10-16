package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-authoritative mob melee begin/contact/cancel/end, including source-clip timing. */
public record RpgCreatureAttackPayload(UUID entityId, long sequence, String clip, String phase,
        long startedGameTime, long serverGameTime, int windupTicks, int recoveryTicks,
        double contactSeconds, double durationSeconds) implements CustomPacketPayload {
    public static final Type<RpgCreatureAttackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fsrpg", "creature_attack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpgCreatureAttackPayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            buf.writeUUID(value.entityId); buf.writeVarLong(value.sequence); buf.writeUtf(value.clip, 128); buf.writeUtf(value.phase, 16);
            buf.writeVarLong(value.startedGameTime); buf.writeVarLong(value.serverGameTime);
            buf.writeVarInt(value.windupTicks); buf.writeVarInt(value.recoveryTicks);
            buf.writeDouble(value.contactSeconds); buf.writeDouble(value.durationSeconds);
        }, buf -> new RpgCreatureAttackPayload(buf.readUUID(), buf.readVarLong(), buf.readUtf(128), buf.readUtf(16),
                buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

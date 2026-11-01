package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Cosmetic attack warning addressed only to the attack's intended player. All schedule times are server world ticks. */
public record RpgGuardCuePayload(UUID entityId, UUID targetId, long sequence, String phase,
        long startedGameTime, long serverGameTime, long flashGameTime, long openGameTime,
        long closeGameTime, long contactGameTime, int flashDurationMillis) implements CustomPacketPayload {
    public static final Type<RpgGuardCuePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fsrpg", "guard_cue"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpgGuardCuePayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            buf.writeUUID(value.entityId); buf.writeUUID(value.targetId); buf.writeVarLong(value.sequence); buf.writeUtf(value.phase, 16);
            buf.writeVarLong(value.startedGameTime); buf.writeVarLong(value.serverGameTime); buf.writeVarLong(value.flashGameTime);
            buf.writeVarLong(value.openGameTime); buf.writeVarLong(value.closeGameTime); buf.writeVarLong(value.contactGameTime);
            buf.writeVarInt(value.flashDurationMillis);
        }, buf -> new RpgGuardCuePayload(buf.readUUID(), buf.readUUID(), buf.readVarLong(), buf.readUtf(16),
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

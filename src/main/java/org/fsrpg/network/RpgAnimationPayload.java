package org.fsrpg.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-authoritative visual intent, also sent to other players tracking the actor. */
public record RpgAnimationPayload(UUID entityId, String style, String model, String action, String phase,
        int phaseRemaining, int windupTicks, int activeTicks, int recoveryTicks, long sequence, boolean drawn, int leftSide, int rightSide, org.fsrpg.combat.AttackAim aim, boolean spearHeld)
        implements CustomPacketPayload {
    public RpgAnimationPayload(UUID entityId,String style,String model,String action,String phase,int phaseRemaining,int windupTicks,int activeTicks,int recoveryTicks,long sequence,boolean drawn,int leftSide,int rightSide,org.fsrpg.combat.AttackAim aim) {
        this(entityId,style,model,action,phase,phaseRemaining,windupTicks,activeTicks,recoveryTicks,sequence,drawn,leftSide,rightSide,aim,false);
    }
    public RpgAnimationPayload(UUID entityId,String style,String model,String action,String phase,int phaseRemaining,int windupTicks,int activeTicks,int recoveryTicks,long sequence,boolean drawn) {
        this(entityId,style,model,action,phase,phaseRemaining,windupTicks,activeTicks,recoveryTicks,sequence,drawn,-1,-1,null);
    }
    public static final Type<RpgAnimationPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fsrpg", "animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpgAnimationPayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            buf.writeUUID(value.entityId); buf.writeUtf(value.style, 128); buf.writeUtf(value.model, 256);
            buf.writeUtf(value.action, 128); buf.writeUtf(value.phase, 32); buf.writeVarInt(value.phaseRemaining);
            buf.writeVarInt(value.windupTicks); buf.writeVarInt(value.activeTicks); buf.writeVarInt(value.recoveryTicks);
            buf.writeVarLong(value.sequence); buf.writeBoolean(value.drawn);
            buf.writeInt(value.leftSide);buf.writeInt(value.rightSide);
            buf.writeUtf(value.aim==null?"":org.fsrpg.config.RpgConfig.JSON.toJson(value.aim),4096);
            buf.writeBoolean(value.spearHeld);
        }, buf -> new RpgAnimationPayload(buf.readUUID(), buf.readUtf(128), buf.readUtf(256), buf.readUtf(128),
                buf.readUtf(32), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(), buf.readBoolean(),buf.readInt(),buf.readInt(),readAim(buf.readUtf(4096)),buf.readBoolean()));
    private static org.fsrpg.combat.AttackAim readAim(String value) { return value.isEmpty()?null:org.fsrpg.config.RpgConfig.JSON.fromJson(value,org.fsrpg.combat.AttackAim.class); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package org.fsrpg;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraft.server.level.ServerPlayer;
import org.fsrpg.network.*;

@Mod("fsrpg")
public final class FsRpg {
    public FsRpg(IEventBus bus) {
        ImportedItems.register(bus);
        bus.addListener(FsRpg::network);
        RpgServer.register(NeoForge.EVENT_BUS);
    }
    private static void network(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("10");
        registrar.playToClient(RpgBurstDashPayload.TYPE,RpgBurstDashPayload.CODEC);
        registrar.playToClient(RpgSnapshotPayload.TYPE, RpgSnapshotPayload.CODEC);
        registrar.playToClient(RpgAnimationPayload.TYPE, RpgAnimationPayload.CODEC);
        registrar.playToClient(RpgTargetLockPayload.TYPE, RpgTargetLockPayload.CODEC);
        registrar.playToClient(RpgCreatureAttackPayload.TYPE, RpgCreatureAttackPayload.CODEC);
        registrar.playToClient(RpgGuardCuePayload.TYPE,RpgGuardCuePayload.CODEC);
        registrar.playToServer(RpgActionPayload.TYPE, RpgActionPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && RpgServer.instance() != null)
                RpgServer.instance().action(player, payload.action(), payload.argument());
        });
    }
}

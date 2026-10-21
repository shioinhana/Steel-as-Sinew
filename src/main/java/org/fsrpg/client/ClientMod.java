package org.fsrpg.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/** Separate entry point keeps all Minecraft client classes off the dedicated server. */
@Mod(value = "fsrpg", dist = Dist.CLIENT)
public final class ClientMod {
    public ClientMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientOptions.SPEC);
        try { WeaponInspections.reload(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("fsrpg")); }
        catch(Exception error){org.slf4j.LoggerFactory.getLogger("FSRPG client").warn("Unable to load weapon inspection catalogue; keeping the validated catalogue",error);}
        ClientHooks.register(modBus);
        InspectionInput.register(modBus);
        FootworkClient.install();
    }
}

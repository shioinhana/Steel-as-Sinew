package org.fsrpg.client;

import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.fsrpg.creature.CreatureAnimationClock;
import org.fsrpg.creature.CreatureCombatCatalog;
import org.fsrpg.network.RpgCreatureAttackPayload;

/** Presentation follows server world ticks; packets for the same sequence never restart a clip. */
public final class CreatureCombatAnimation {
    private static final CreatureCombatCatalog BUNDLED = CreatureCombatCatalog.bundled();
    private static final LinkedHashMap<UUID, CreatureAnimationClock> CLOCKS = new LinkedHashMap<>();
    private static Object world;

    private CreatureCombatAnimation() {}

    public static void accept(RpgCreatureAttackPayload payload) {
        var level = Minecraft.getInstance().level;
        if (level == null || payload.entityId() == null) return;
        enterWorld(level);
        var message = new CreatureAnimationClock.Message(payload.sequence(), payload.clip(), payload.phase(),
                payload.startedGameTime(), payload.serverGameTime(), payload.windupTicks(), payload.recoveryTicks(),
                payload.contactSeconds(), payload.durationSeconds());
        if (!CLOCKS.containsKey(payload.entityId()) && CLOCKS.size() >= 4096) CLOCKS.remove(CLOCKS.keySet().iterator().next());
        CLOCKS.computeIfAbsent(payload.entityId(), ignored -> new CreatureAnimationClock()).accept(message, level.getGameTime());
    }

    public static CreatureAnimationClock.Sample sample(LivingEntity entity, float partialTick) {
        enterWorld(entity.level());
        var clock = CLOCKS.get(entity.getUUID());
        return clock == null ? null : clock.sample(entity.level().getGameTime(), partialTick);
    }

    /** Bundled coverage suppresses native byte-4 swing restarts; timings always come from the server payload. */
    public static boolean controlled(String entityType, String variant) { return BUNDLED.find(entityType, variant).isPresent(); }

    public static void clear() { CLOCKS.clear(); world = null; }

    private static void enterWorld(Object next) {
        if (world != next) { CLOCKS.clear(); world = next; }
    }
}

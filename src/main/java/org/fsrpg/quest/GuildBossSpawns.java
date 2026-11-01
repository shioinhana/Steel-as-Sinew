package org.fsrpg.quest;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.*;
import org.fsrpg.config.RpgConfig;

/** Optional configured boss sites. An existing UUID stays reserved while its chunk is unloaded. */
public final class GuildBossSpawns {
    public record Site(String id, boolean enabled, String entityType, String name, String dimension,
            double x, double y, double z, int level, boolean elite, int respawnSeconds) {}
    public record Entry(UUID entity, long nextSpawnAt) {}
    private final MinecraftServer server;
    private final Path ledger;
    private final List<Site> sites;
    private final Map<String,Entry> entries = new LinkedHashMap<>();
    public GuildBossSpawns(MinecraftServer server, Path config, Path worldData) throws IOException {
        this.server = server; ledger = worldData.resolve("boss-sites.json");
        Path file = config.resolve("guild_bosses.json");
        if (!Files.exists(file)) {
            try (var in = GuildBossSpawns.class.getResourceAsStream("/defaults/guild_bosses.json")) {
                if (in == null) throw new IOException("Missing boss site seed");
                Files.copy(in, file);
            }
        }
        if (Files.size(file) > 65536) throw new IOException("guild_bosses.json exceeds 64 KiB");
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonArray();
        for (var site : json) RpgConfig.validateRecord(site.getAsJsonObject(),Site.class);
        Site[] parsed = RpgConfig.JSON.fromJson(json, Site[].class);
        if (parsed == null || parsed.length > 64) throw new IOException("Invalid boss sites");
        sites = List.of(parsed); Set<String> ids = new HashSet<>();
        for (Site site : sites) {
            if (site == null || site.id() == null || !site.id().matches("[a-z0-9_.-]{1,64}") || !ids.add(site.id())
                    || site.name() == null || site.name().length() > 256 || !Double.isFinite(site.x()) || !Double.isFinite(site.y()) || !Double.isFinite(site.z())
                    || Math.abs(site.x()) > 30_000_000 || Math.abs(site.y()) > 30_000_000 || Math.abs(site.z()) > 30_000_000
                    || site.level() < 0 || site.level() > 1_000_000 || site.respawnSeconds() < 1 || site.respawnSeconds() > 31_536_000
                    || !BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(site.entityType()))) throw new IOException("Invalid boss site: " + site);
            Identifier.parse(site.dimension());
        }
        if (Files.exists(ledger)) {
            var saved = com.google.gson.JsonParser.parseString(Files.readString(ledger)).getAsJsonObject();
            for (var value : saved.entrySet()) entries.put(value.getKey(), RpgConfig.JSON.fromJson(value.getValue(), Entry.class));
        }
    }
    public void tick() throws IOException {
        for (Site site : sites) {
            if (!site.enabled()) continue;
            Entry entry = entries.get(site.id());
            if (entry != null && (entry.entity() != null || entry.nextSpawnAt() > System.currentTimeMillis())) continue;
            var world = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(site.dimension())));
            if (world == null || !world.hasChunkAt(BlockPos.containing(site.x(),site.y(),site.z()))) continue;
            // Recover a loaded entity if a prior process stopped between spawning and writing the ledger.
            Entity existing = null;
            for (Entity candidate : world.getAllEntities()) if (candidate.getTags().contains("fsrpg_boss_site_" + site.id())) { existing = candidate; break; }
            if (existing != null) { entries.put(site.id(),new Entry(existing.getUUID(),0)); save(); continue; }
            Entity boss = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(site.entityType())).create(world,EntitySpawnReason.COMMAND);
            if (!(boss instanceof LivingEntity)) throw new IOException("Boss site must spawn a living entity: " + site.id());
            boss.setPos(site.x(),site.y(),site.z());
            if (!site.name().isBlank()) boss.setCustomName(Component.literal(site.name()));
            boss.addTag("fsrpg:boss"); boss.addTag("fsrpg_boss_site_" + site.id());
            boss.getPersistentData().putInt("fsrpg_level",site.level());
            boss.getPersistentData().putBoolean("fsrpg_boss",true);
            boss.getPersistentData().putBoolean("fsrpg_elite",site.elite());
            if (boss instanceof Mob mob) mob.setPersistenceRequired();
            if (!world.addFreshEntity(boss)) throw new IOException("Boss spawn rejected: " + site.id());
            entries.put(site.id(),new Entry(boss.getUUID(),0)); save();
        }
    }
    public void died(Entity victim) throws IOException {
        for (Site site : sites) {
            Entry entry = entries.get(site.id());
            if (entry != null && victim.getUUID().equals(entry.entity())) {
                entries.put(site.id(),new Entry(null,System.currentTimeMillis()+site.respawnSeconds()*1000L)); save();
            }
        }
    }
    private void save() throws IOException { RpgConfig.atomicWrite(ledger,RpgConfig.JSON.toJson(entries)); }
}

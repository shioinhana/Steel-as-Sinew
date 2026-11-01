package org.fsrpg.quest;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** World interactions remain server side; a badge item never grants a title. */
public record GuildAccess(String npcTag, String npcName, List<String> boardBlocks, double examDistance) {
    public GuildAccess {
        npcTag = npcTag == null ? "fsrpg_guild" : npcTag;
        npcName = npcName == null ? "公会接待员" : npcName;
        boardBlocks = boardBlocks == null ? List.of("minecraft:lectern") : List.copyOf(boardBlocks);
        if (npcTag.isBlank() || npcTag.length() > 64 || npcName.length() > 128 || boardBlocks.size() > 32
                || !Double.isFinite(examDistance) || examDistance < 1 || examDistance > 16)
            throw new IllegalArgumentException("Invalid guild access settings");
    }
    public static GuildAccess load(Path directory, Gson gson) throws IOException {
        Path file = directory.resolve("guild_access.json");
        if (!Files.exists(file)) {
            Files.createDirectories(directory);
            try (var in = GuildAccess.class.getResourceAsStream("/defaults/guild_access.json")) {
                if (in == null) throw new IOException("Missing guild_access.json seed");
                Files.copy(in, file);
            }
        }
        if (Files.size(file) > 16384) throw new IOException("guild_access.json exceeds 16 KiB");
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        org.fsrpg.config.RpgConfig.validateRecord(json, GuildAccess.class);
        GuildAccess access = gson.fromJson(json, GuildAccess.class);
        if (access == null) throw new IOException("Empty guild_access.json");
        for (String id : access.boardBlocks)
            if (!BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.parse(id)))
                throw new IllegalArgumentException("Unknown guild board block " + id);
        return access;
    }
    public boolean isNpc(Entity entity) {
        return entity.isAlive() && (entity.getTags().contains(npcTag)
                || entity.hasCustomName() && entity.getCustomName().getString().equals(npcName));
    }
    public boolean nearNpc(ServerPlayer player) {
        return player.level().getEntities(player, player.getBoundingBox().inflate(examDistance), this::isNpc)
                .stream().anyMatch(e -> e.distanceToSqr(player) <= examDistance * examDistance);
    }
}

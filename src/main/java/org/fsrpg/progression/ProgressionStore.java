package org.fsrpg.progression;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Objects;

/** World-scoped cultivation persistence. Never share a store across Minecraft worlds. */
public final class ProgressionStore {
    private final Path file;
    private final Gson gson;
    public ProgressionStore(Path file, Gson gson) { this.file = Objects.requireNonNull(file).toAbsolutePath(); this.gson = Objects.requireNonNull(gson); }
    public Path file() { return file; }
    public ProgressionService.Snapshot load() throws IOException {
        if (!Files.exists(file)) return new ProgressionService.Snapshot(ProgressionService.SCHEMA_VERSION, Map.of());
        if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("cultivation save exceeds 64 MiB: " + file);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ProgressionService.Snapshot result = gson.fromJson(reader, ProgressionService.Snapshot.class);
            if (result == null) throw new IOException("empty cultivation save: " + file);
            return result;
        } catch (JsonParseException | IllegalArgumentException | NullPointerException invalid) { throw new IOException("invalid cultivation JSON: " + file, invalid); }
    }
    public synchronized void save(ProgressionService.Snapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, gson.toJson(snapshot), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}

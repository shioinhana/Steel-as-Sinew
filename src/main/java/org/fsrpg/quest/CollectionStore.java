package org.fsrpg.quest;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Objects;

/** World-scoped durable collection progress and pending-exchange outbox. */
public final class CollectionStore {
    private final Path file;
    private final Gson gson;
    public CollectionStore(Path file, Gson gson) { this.file = Objects.requireNonNull(file).toAbsolutePath(); this.gson = Objects.requireNonNull(gson); }
    public Path file() { return file; }
    public CollectionService.Snapshot load() throws IOException {
        if (!Files.exists(file)) return new CollectionService.Snapshot(CollectionService.SCHEMA_VERSION, Map.of(), Map.of());
        if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("collection progress exceeds 64 MiB: " + file);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            CollectionService.Snapshot snapshot = gson.fromJson(reader, CollectionService.Snapshot.class);
            if (snapshot == null) throw new IOException("collection progress file is empty: " + file);
            return snapshot;
        } catch (JsonParseException | IllegalArgumentException | NullPointerException invalid) { throw new IOException("invalid collection progress JSON: " + file, invalid); }
    }
    public synchronized void save(CollectionService.Snapshot snapshot) throws IOException {
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

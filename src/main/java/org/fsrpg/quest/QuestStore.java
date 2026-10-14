package org.fsrpg.quest;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/** One world-scoped JSON file containing UUID keyed progress, replaced atomically on supported filesystems. */
public final class QuestStore {
    private final Path file;
    private final Gson gson;

    public QuestStore(Path file, Gson gson) {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
        this.gson = Objects.requireNonNull(gson);
    }
    public Path file() { return file; }

    public QuestService.Snapshot load() throws IOException {
        if (!Files.exists(file)) return new QuestService.Snapshot(QuestService.SCHEMA_VERSION, Map.of());
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            QuestService.Snapshot snapshot = gson.fromJson(reader, QuestService.Snapshot.class);
            if (snapshot == null) throw new IOException("quest progress file is empty: " + file);
            return snapshot;
        } catch (JsonParseException | IllegalArgumentException error) {
            throw new IOException("invalid quest progress JSON: " + file, error);
        }
    }

    public synchronized void save(QuestService.Snapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, gson.toJson(snapshot), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

package live.qsmc.qsync.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Persists player data to disk when they disconnect from a synced server, so it
 * survives beyond the in-memory cache TTL and can be applied on their next login.
 *
 * <p>Each player gets one file: {@code <dataDir>/disconnect-data/<uuid>.json}.
 * Files are consumed (read + deleted) on the player's next initial join.
 * Old unclaimed files are pruned by {@link #cleanup()}.
 */
public class DisconnectDataStore {

    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000; // 7 days

    private final Path dataDir;

    public DisconnectDataStore(Path pluginDataDirectory) {
        this.dataDir = pluginDataDirectory.resolve("disconnect-data");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("[QSync] Failed to create disconnect-data directory: " + e.getMessage(), e);
        }
    }

    /** Writes (or overwrites) the serialized player data for this UUID. */
    public void store(UUID uuid, String jsonData) {
        try {
            Files.writeString(dataDir.resolve(uuid + ".json"), jsonData, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[QSync] Failed to persist disconnect data for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Returns the persisted data for this UUID and deletes the file, or
     * {@code null} if no file exists.
     */
    public String consume(UUID uuid) {
        Path file = dataDir.resolve(uuid + ".json");
        if (!Files.exists(file)) return null;
        try {
            String data = Files.readString(file, StandardCharsets.UTF_8);
            Files.delete(file);
            return data;
        } catch (IOException e) {
            System.err.println("[QSync] Failed to read disconnect data for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    /** Deletes files that have not been consumed within {@link #MAX_AGE_MS}. */
    public void cleanup() {
        try (var stream = Files.list(dataDir)) {
            long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
            stream.forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.delete(p);
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}

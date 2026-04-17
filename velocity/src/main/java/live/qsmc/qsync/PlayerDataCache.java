package live.qsmc.qsync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived cache that holds serialized player data while a player is in transit
 * between backend servers. Entries expire after {@link #EXPIRY_MS} milliseconds.
 */
public class PlayerDataCache {

    private static final long EXPIRY_MS = 30_000L;

    private final Map<UUID, CachedEntry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qsync-cache-cleaner");
        t.setDaemon(true);
        return t;
    });

    public PlayerDataCache() {
        cleaner.scheduleAtFixedRate(this::evictExpired, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Store serialized player data. The {@code jsonData} string is the JSON
     * representation of the {@code data} field received in a {@code SYNC_DATA} packet.
     */
    public void store(UUID uuid, String jsonData) {
        entries.put(uuid, new CachedEntry(jsonData, System.currentTimeMillis()));
    }

    /** Returns true if non-expired data exists for this player. */
    public boolean hasPending(UUID uuid) {
        CachedEntry entry = entries.get(uuid);
        if (entry == null) return false;
        if (isExpired(entry)) {
            entries.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * Removes and returns the cached data for this player, or {@code null} if
     * no entry exists or it has expired.
     */
    public String consume(UUID uuid) {
        CachedEntry entry = entries.remove(uuid);
        if (entry == null || isExpired(entry)) return null;
        return entry.data();
    }

    /** Drops any cached entry for this player without returning it. */
    public void invalidate(UUID uuid) {
        entries.remove(uuid);
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }

    private boolean isExpired(CachedEntry entry) {
        return System.currentTimeMillis() - entry.timestamp() > EXPIRY_MS;
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> now - e.getValue().timestamp() > EXPIRY_MS);
    }

    private record CachedEntry(String data, long timestamp) {}
}

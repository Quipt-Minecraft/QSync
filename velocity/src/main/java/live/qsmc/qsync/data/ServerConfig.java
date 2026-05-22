package live.qsmc.qsync.data;

import java.util.HashSet;
import java.util.Set;

/**
 * Configuration for which servers participate in player data synchronization.
 */
public class ServerConfig {

    private final Set<String> syncedServers = new HashSet<>();

    /**
     * Mark a server as synced. Players' data will be synchronized when
     * they move to/from this server.
     */
    public void markSynced(String serverName) {
        syncedServers.add(serverName.toLowerCase());
    }

    /**
     * Check if a server participates in data synchronization.
     */
    public boolean isSynced(String serverName) {
        return syncedServers.contains(serverName.toLowerCase());
    }

    /**
     * Get all synced servers.
     */
    public Set<String> getSyncedServers() {
        return new HashSet<>(syncedServers);
    }

    /**
     * Clear all synced servers.
     */
    public void clear() {
        syncedServers.clear();
    }

    /**
     * Initialize with default synced servers.
     * Can be customized via configuration file later.
     */
    public void initializeDefaults(String... servers) {
        for (String server : servers) {
            markSynced(server);
        }
    }
}


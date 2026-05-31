package live.qsmc.qsync.data;

import live.qsmc.quipt.core.QuiptIntegration;
import live.qsmc.quipt.core.config.Config;
import live.qsmc.quipt.core.config.ConfigTemplate;
import org.json.JSONArray;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration for which servers participate in player data synchronization.
 */
@ConfigTemplate(name = "servers", ext = ConfigTemplate.Extension.JSON)
public class ServerConfig extends Config {

    public JSONArray syncedServers = new JSONArray("[\"lobby\",\"bac\"]");

    public ServerConfig(File file, String name, ConfigTemplate.Extension extension, QuiptIntegration integration) {
        super(file, name, extension, integration);
    }

    /**
     * Mark a server as synced. Players' data will be synchronized when
     * they move to/from this server.
     */
    public void markSynced(String serverName) {
        syncedServers.put(serverName.toLowerCase());
    }

    /**
     * Check if a server participates in data synchronization.
     */
    public boolean isSynced(String serverName) {
        for(int i = 0; i < syncedServers.length(); i++) {
            if (syncedServers.getString(i).equalsIgnoreCase(serverName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all synced servers.
     */
    public JSONArray getSyncedServers() {
        return syncedServers;
    }

    /**
     * Clear all synced servers.
     */
    public void clear() {
        syncedServers.clear();
    }


}


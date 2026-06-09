package live.qsmc.qsync.data;

import live.qsmc.quipt.core.QuiptIntegration;
import live.qsmc.quipt.core.config.Config;
import live.qsmc.quipt.core.config.ConfigTemplate;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;

/**
 * Stores the last known server for each player.
 */
@ConfigTemplate(name = "last_servers", ext = ConfigTemplate.Extension.JSON)
public class LastServerConfig extends Config {

    public JSONObject lastServers = new JSONObject();

    public LastServerConfig(File file, String name, ConfigTemplate.Extension extension, QuiptIntegration integration) {
        super(file, name, extension, integration);
    }

    public void setLastServer(UUID uuid, String serverName) {
        lastServers.put(uuid.toString(), serverName);
    }

    public String getLastServer(UUID uuid) {
        return lastServers.optString(uuid.toString(), null);
    }
}

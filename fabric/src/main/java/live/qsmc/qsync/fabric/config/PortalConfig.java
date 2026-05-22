package live.qsmc.qsync.fabric.config;

import live.qsmc.qsync.fabric.QSyncFabric;
import live.qsmc.quipt.core.QuiptIntegration;
import live.qsmc.quipt.core.config.Config;
import live.qsmc.quipt.core.config.ConfigTemplate;
import live.qsmc.quipt.core.config.ConfigValue;
import live.qsmc.quipt.core.config.objects.ConfigMap;
import live.qsmc.quipt.core.config.objects.ConfigObject;

import java.io.File;

@ConfigTemplate(name = "portals", ext = ConfigTemplate.Extension.JSON)
public class PortalConfig extends Config {

    @ConfigValue
    public ConfigMap<Zone> zones;

    @ConfigValue
    public long arrival_cooldown_ms = 5000L;

    public PortalConfig(File file, String name, ConfigTemplate.Extension extension, QuiptIntegration integration) {
        super(file, name, extension, integration);
        zones = new ConfigMap<>(integration);
    }




    public static class Zone extends ConfigObject {

        public String world;
        public int min_x;
        public int min_y;
        public int min_z;
        public int max_x;
        public int max_y;
        public int max_z;
        public String target_server;

        public Zone(String world, int min_x, int min_y, int min_z, int max_x, int max_y, int max_z, String target_server){
            super(QSyncFabric.instance().integration());
            this.world = world;
            this.min_x = min_x;
            this.min_y = min_y;
            this.min_z = min_z;
            this.max_x = max_x;
            this.max_y = max_y;
            this.max_z = max_z;
            this.target_server = target_server;
        }

        public boolean contains(String worldId, int x, int y, int z) {
            if (!world.equals(worldId)) return false;
            if (x < Math.min(min_x, max_x) || x > Math.max(min_x, max_x)) return false;
            if (y < Math.min(min_y, max_y) || y > Math.max(min_y, max_y)) return false;
            return z >= Math.min(min_z, max_z) && z <= Math.max(min_z, max_z);
        }

    }
}
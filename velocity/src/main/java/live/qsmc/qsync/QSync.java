package live.qsmc.qsync;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import live.qsmc.velocity.QuiptProxy;

import java.nio.file.Path;

public class QSync extends QuiptProxy {

    /** Plugin-messaging channel used for all QSync packets between proxy and backends. */
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("qsync:data");
    private static QSync instance;

    private final PlayerDataCache cache = new PlayerDataCache();

    @Inject
    public QSync(ProxyServer server, @DataDirectory Path dataDirectory) {
        super(server, dataDirectory);
        instance = this;
    }

    public static QSync instance() {
        return instance;
    }

    @Override
    public void enable() {
        proxy().getChannelRegistrar().register(CHANNEL);
        proxy().getEventManager().register(this, new SyncListener(this, proxy(), cache));
        proxy().getEventManager().register(this, new PluginMessageHandler(cache));
        logger().log("Init", "QSync enabled — player data sync active on channel '{}'", CHANNEL.getId());
    }


}

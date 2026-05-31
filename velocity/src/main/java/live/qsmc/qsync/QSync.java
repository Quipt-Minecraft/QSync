package live.qsmc.qsync;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import live.qsmc.qsync.data.LastServerConfig;
import live.qsmc.qsync.data.PlayerDataCache;
import live.qsmc.qsync.data.PluginMessageHandler;
import live.qsmc.qsync.data.ServerConfig;
import live.qsmc.qsync.listeners.ChatListener;
import live.qsmc.qsync.listeners.JoinLeaveListener;
import live.qsmc.qsync.listeners.LastServerListener;
import live.qsmc.qsync.listeners.SyncListener;
import live.qsmc.quipt.velocity.QuiptProxy;

import java.nio.file.Path;

public class QSync extends QuiptProxy {

    /** Plugin-messaging channel used for all QSync packets between proxy and backends. */
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("qsync:data");
    private static QSync instance;

    private final PlayerDataCache cache = new PlayerDataCache();
    private final Path dataDirectory;

    @Inject
    public QSync(ProxyServer server, @DataDirectory Path dataDirectory) {
        super(server, dataDirectory);
        this.dataDirectory = dataDirectory;
        instance = this;
    }

    public static QSync instance() {
        return instance;
    }

    public ServerConfig getServerConfig() {
        return integration().configs().config(ServerConfig.class);
    }

    @Override
    public void enable() {
        System.out.println("[QSync] Initializing...");


        ServerConfig config = integration().configs().register(ServerConfig.class);
        config.save();

        proxy().getChannelRegistrar().register(CHANNEL);

        LastServerConfig lastServerConfig = integration().configs().register(LastServerConfig.class);
        lastServerConfig.save();

        proxy().getEventManager().register(this, new SyncListener(this, proxy(), cache, config));
        proxy().getEventManager().register(this, new PluginMessageHandler(cache, proxy()));
        proxy().getEventManager().register(this, new ChatListener(proxy()));
        proxy().getEventManager().register(this, new JoinLeaveListener(proxy()));
        proxy().getEventManager().register(this, new LastServerListener(proxy(), lastServerConfig));
        integration().logger().log("Init", "QSync enabled - player data sync, chat broadcast, and join/leave announcements active on channel '{}'", CHANNEL.getId());
        integration().logger().log("Init", "Synced servers: {}", config.getSyncedServers());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        cache.shutdown();
    }


}

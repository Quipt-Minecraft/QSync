package live.qsmc.qsync.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import live.qsmc.qsync.QSync;
import live.qsmc.qsync.data.LastServerConfig;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class LastServerListener {

    private final ProxyServer server;
    private final LastServerConfig config;
    private final Set<UUID> handledInitialJoin = new HashSet<>();

    public LastServerListener(ProxyServer server, LastServerConfig config) {
        this.server = server;
        this.config = config;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Only handle initial join
        if (!handledInitialJoin.contains(uuid)) {
            String lastServerName = config.getLastServer(uuid);
            if (lastServerName != null) {
                server.getServer(lastServerName).ifPresentOrElse(target -> {
                    event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
                    QSync.instance().integration().logger().log("LastServer", "Redirecting {} to last known server: {}", player.getUsername(), lastServerName);
                }, () -> {
                    QSync.instance().integration().logger().log("LastServer", "Last known server {} for {} is no longer available, falling back to default", lastServerName, player.getUsername());
                });
            }
            handledInitialJoin.add(uuid);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        config.setLastServer(player.getUniqueId(), serverName);
        config.save();

    }

    @Subscribe
    public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        handledInitialJoin.remove(event.getPlayer().getUniqueId());
    }
}

package live.qsmc.qsync.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import live.qsmc.qsync.QSync;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Listens for player join/leave events on the proxy and announces them.
 * This centralizes join/leave messages so they only appear once across the network.
 */
public class JoinLeaveListener {

    private final ProxyServer server;

    public JoinLeaveListener(ProxyServer server) {
        this.server = server;
    }

    /**
     * Announces when a player joins the proxy (initial connection).
     */
    @Subscribe
    public void onPlayerJoin(ServerConnectedEvent event) {
        if (event.getPreviousServer().isEmpty()) {
            Player player = event.getPlayer();
            String serverName = event.getServer().getServerInfo().getName();

            Component message = Component.text(player.getUsername() + " joined the game.", NamedTextColor.YELLOW);

            for (Player onlinePlayer : server.getAllPlayers()) {
                onlinePlayer.sendMessage(message);
            }

            QSync.instance().logger().log("JoinLeave", "{} joined the network on {}", player.getUsername(), serverName);
        }
    }

    /**
     * Announces when a player leaves the network entirely.
     */
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        Component message = Component.text(player.getUsername() + " left the game.", NamedTextColor.YELLOW);

        for (Player onlinePlayer : server.getAllPlayers()) {
            onlinePlayer.sendMessage(message);
        }

        QSync.instance().logger().log("JoinLeave", "{} left the network", player.getUsername());
    }
}


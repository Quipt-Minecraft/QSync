package live.qsmc.qsync;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

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

            Component message = Component.text("[" + serverName + "] " + player.getUsername() + " joined the network");

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

        Component message = Component.text(player.getUsername() + " left the network");

        for (Player onlinePlayer : server.getAllPlayers()) {
            onlinePlayer.sendMessage(message);
        }

        QSync.instance().logger().log("JoinLeave", "{} left the network", player.getUsername());
    }
}


package live.qsmc.qsync.listeners;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import live.qsmc.qsync.data.PacketType;
import live.qsmc.qsync.QSync;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Listens for player chat events and broadcasts them to all backend servers.
 * This centralizes chat so all players across the network see the same messages.
 */
public class ChatListener {

    private final ProxyServer server;

    public ChatListener(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String originServer = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("proxy");

        JsonObject packet = new JsonObject();
        packet.addProperty("type", PacketType.CHAT_MESSAGE);
        packet.addProperty("uuid", player.getUniqueId().toString());
        packet.addProperty("username", player.getUsername());
        packet.addProperty("sourceServer", originServer);
        packet.addProperty("message", message);

        byte[] data = packet.toString().getBytes(StandardCharsets.UTF_8);
        Set<String> forwardedServers = new HashSet<>();

        for (Player onlinePlayer : server.getAllPlayers()) {
            onlinePlayer.getCurrentServer().ifPresent(conn -> {
                String targetServer = conn.getServerInfo().getName();
                if (originServer.equals(targetServer) || !forwardedServers.add(targetServer)) {
                    return;
                }

                conn.sendPluginMessage(QSync.CHANNEL, data);
            });
        }

        if (!forwardedServers.isEmpty()) {
            QSync.instance().integration().logger().log("Chat", "Relayed chat from {} on {} to {} backend(s)",
                    player.getUsername(), originServer, forwardedServers.size());
        }
    }
}


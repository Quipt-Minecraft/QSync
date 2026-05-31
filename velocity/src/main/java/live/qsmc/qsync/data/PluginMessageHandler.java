package live.qsmc.qsync.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import live.qsmc.qsync.QSync;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Receives plugin messages from backend servers on the {@code qsync:data} channel.
 * Handles {@code SYNC_DATA} (caches player data for forwarding) and
 * {@code PORTAL_REQUEST} (switches the player to a different backend server).
 */
public class PluginMessageHandler {

    private final PlayerDataCache cache;
    private final ProxyServer proxy;

    public PluginMessageHandler(PlayerDataCache cache, ProxyServer proxy) {
        this.cache = cache;
        this.proxy = proxy;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        QSync.instance().integration().logger().log("PMH", "PluginMessage received: identifier={}, source={}",
                event.getIdentifier().getId(), event.getSource().getClass().getSimpleName());

        if (!event.getIdentifier().equals(QSync.CHANNEL)) return;
        // Only accept messages sent from a backend server, not from a player client
        if (!(event.getSource() instanceof ServerConnection)) return;

        // Mark as handled so Velocity does not forward this packet to the player
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String raw = new String(event.getData(), StandardCharsets.UTF_8);
        JsonObject packet;
        try {
            packet = new JsonParser().parse(raw).getAsJsonObject();
        } catch (Exception e) {
            QSync.instance().integration().logger().warn("PMH", "Received malformed QSync packet: {}", raw);
            return;
        }

        String type = packet.has("type") ? packet.get("type").getAsString() : null;
        if (PacketType.SYNC_DATA.equals(type)) {
            handleSyncData(packet);
        } else if (PacketType.PORTAL_REQUEST.equals(type)) {
            handlePortalRequest(packet);
        }
    }

    private void handleSyncData(JsonObject packet) {
        UUID uuid;
        try {
            uuid = UUID.fromString(packet.get("uuid").getAsString());
        } catch (IllegalArgumentException e) {
            QSync.instance().integration().logger().warn("PMH", "QSync SYNC_DATA packet contained invalid UUID");
            return;
        }

        String data = packet.get("data").toString();
        cache.store(uuid, data);
        QSync.instance().integration().logger().log("PMH", "Cached sync data for {}", uuid);
    }

    private void handlePortalRequest(JsonObject packet) {
        UUID uuid;
        try {
            uuid = UUID.fromString(packet.get("uuid").getAsString());
        } catch (Exception e) {
            QSync.instance().integration().logger().warn("PMH", "PORTAL_REQUEST contained invalid UUID");
            return;
        }

        String targetServerName = packet.has("target") ? packet.get("target").getAsString() : null;
        if (targetServerName == null) {
            QSync.instance().integration().logger().warn("PMH", "PORTAL_REQUEST missing target server name");
            return;
        }

        final UUID finalUuid = uuid;
        proxy.getPlayer(finalUuid).ifPresentOrElse(player ->
            proxy.getServer(targetServerName).ifPresentOrElse(targetServer -> {
                // Guard: don't switch if already on the target server
                boolean alreadyThere = player.getCurrentServer()
                        .map(c -> c.getServerInfo().getName().equalsIgnoreCase(targetServerName))
                        .orElse(false);
                if (alreadyThere) return;

                QSync.instance().integration().logger().log("Portal", "Switching {} → {}", player.getUsername(), targetServerName);
                player.createConnectionRequest(targetServer).fireAndForget();
            }, () -> QSync.instance().integration().logger().warn("Portal", "Target server '{}' not found for {}", targetServerName, finalUuid)),
        () -> QSync.instance().integration().logger().warn("Portal", "Player {} not found on proxy for PORTAL_REQUEST", finalUuid));
    }
}
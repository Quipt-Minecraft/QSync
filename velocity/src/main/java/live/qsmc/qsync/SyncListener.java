package live.qsmc.qsync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Listens for server-switch events and drives the sync handoff:
 * <ol>
 *   <li>{@link ServerPreConnectEvent} — request player data from the current (old) server</li>
 *   <li>{@link ServerConnectedEvent}  — after a short delay, forward cached data to the new server</li>
 *   <li>{@link DisconnectEvent}       — clean up any stale cache entry on full disconnect</li>
 * </ol>
 */
public class SyncListener {

    /** Milliseconds to wait after a server switch before sending SYNC_APPLY. */
    private static final long APPLY_DELAY_MS = 750L;

    private final QSync plugin;
    private final ProxyServer server;
    private final PlayerDataCache cache;

    public SyncListener(QSync plugin, ProxyServer server, PlayerDataCache cache) {
        this.plugin = plugin;
        this.server = server;
        this.cache = cache;
    }

    /**
     * When a player is about to switch servers, ask the current backend to serialize
     * and send back the player's data before they disconnect from it.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresentOrElse(conn -> {
            JsonObject packet = new JsonObject();
            packet.addProperty("type", PacketType.SYNC_REQUEST);
            packet.addProperty("uuid", player.getUniqueId().toString());
            conn.sendPluginMessage(QSync.CHANNEL, packet.toString().getBytes(StandardCharsets.UTF_8));
            QSync.instance().logger().log("Sync", "Sent SYNC_REQUEST for {} to {}", player.getUsername(), conn.getServerInfo().getName());
        }, () -> {
            QSync.instance().logger().log("Sync", "No current server for {} — skipping SYNC_REQUEST (initial join)", player.getUsername());
        });
    }

    /**
     * After the player has fully connected to the new server, wait briefly for the
     * old backend's SYNC_DATA response to arrive, then forward it as SYNC_APPLY.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        // Skip initial login — there is no previous server to sync from
        if (event.getPreviousServer().isEmpty()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        server.getScheduler()
                .buildTask(plugin, () -> {
                    String data = cache.consume(uuid);
                    if (data == null) {
                        QSync.instance().logger().log("Sync", "No sync data available for {} after server switch", player.getUsername());
                        return;
                    }

                    QSync.instance().logger().log("Sync", "Found cached data for {} ({} chars), forwarding SYNC_APPLY", player.getUsername(), data.length());

                    player.getCurrentServer().ifPresentOrElse(conn -> {
                        JsonObject packet = new JsonObject();
                        packet.addProperty("type", PacketType.SYNC_APPLY);
                        packet.addProperty("uuid", uuid.toString());
                        // Re-parse the stored JSON string so it embeds as a nested object, not an escaped string
                        JsonParser parser = new JsonParser();
                        packet.add("data", parser.parse(data));
                        conn.sendPluginMessage(QSync.CHANNEL, packet.toString().getBytes(StandardCharsets.UTF_8));
                        QSync.instance().logger().log("Sync", "Sent SYNC_APPLY for {} to {}", player.getUsername(), conn.getServerInfo().getName());
                    }, () -> {
                        QSync.instance().logger().log("Sync", "Player {} has no current server for SYNC_APPLY", player.getUsername());
                    });
                })
                .delay(APPLY_DELAY_MS, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /**
     * If a player fully disconnects from the network mid-transition, drop their
     * cached data so it doesn't linger until TTL expiry.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        cache.invalidate(event.getPlayer().getUniqueId());
    }
}

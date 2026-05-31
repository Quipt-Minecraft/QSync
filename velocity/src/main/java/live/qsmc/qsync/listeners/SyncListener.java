package live.qsmc.qsync.listeners;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import live.qsmc.qsync.data.PacketType;
import live.qsmc.qsync.data.PlayerDataCache;
import live.qsmc.qsync.data.ServerConfig;
import live.qsmc.qsync.QSync;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Listens for server-switch events and drives the sync handoff:
 * <ol>
 *   <li>{@link ServerPreConnectEvent} — request player data from the current (old) server if both are synced</li>
 *   <li>{@link ServerConnectedEvent}  — after a short delay, forward cached data to the new server if it's synced</li>
 *   <li>{@link DisconnectEvent}       — clean up any cache entry on full disconnect</li>
 * </ol>
 */
public class SyncListener {

    /** Milliseconds to wait after a server switch before sending SYNC_APPLY. */
    private static final long APPLY_DELAY_MS = 750L;

    private final QSync plugin;
    private final ProxyServer server;
    private final PlayerDataCache cache;
    private final ServerConfig serverConfig;

    public SyncListener(QSync plugin, ProxyServer server, PlayerDataCache cache, ServerConfig serverConfig) {
        this.plugin = plugin;
        this.server = server;
        this.cache = cache;
        this.serverConfig = serverConfig;
    }

    /**
     * When a player is about to switch servers, ask the current backend to serialize
     * and send back the player's data before they disconnect from it.
     * Only syncs if BOTH the current and target servers are marked as synced.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String targetServer = event.getResult().getServer().map(s -> s.getServerInfo().getName()).orElse(null);

        // Only proceed if target server is synced
        if (targetServer == null || !serverConfig.isSynced(targetServer)) {
            QSync.instance().integration().logger().log("Sync", "Skipping SYNC_REQUEST for {} — target server {} is not synced", player.getUsername(), targetServer);
            return;
        }

        player.getCurrentServer().ifPresentOrElse(conn -> {
            String currentServer = conn.getServerInfo().getName();

            // Only capture data if current server is also synced
            if (!serverConfig.isSynced(currentServer)) {
                QSync.instance().integration().logger().log("Sync", "Skipping SYNC_REQUEST for {} — current server {} is not synced", player.getUsername(), currentServer);
                return;
            }

            JsonObject packet = new JsonObject();
            packet.addProperty("type", PacketType.SYNC_REQUEST);
            packet.addProperty("uuid", player.getUniqueId().toString());
            conn.sendPluginMessage(QSync.CHANNEL, packet.toString().getBytes(StandardCharsets.UTF_8));
            QSync.instance().integration().logger().log("Sync", "Sent SYNC_REQUEST for {} to {}", player.getUsername(), conn.getServerInfo().getName());
        }, () -> {
            QSync.instance().integration().logger().log("Sync", "No current server for {} — skipping SYNC_REQUEST (initial join)", player.getUsername());
        });
    }

    /**
     * After the player has fully connected to the new server:
     * <ul>
     *   <li>Initial join (no previous server): skip sync apply per requirements.</li>
     *   <li>Server switch: wait briefly for the old backend's SYNC_DATA response,
     *       then forward it as SYNC_APPLY.</li>
     * </ul>
     * Only runs when the new server is marked as synced.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String newServer = event.getServer().getServerInfo().getName();
        boolean isInitialJoin = event.getPreviousServer().isEmpty();

        if (!serverConfig.isSynced(newServer)) {
            QSync.instance().integration().logger().log("Sync", "Skipping SYNC_APPLY for {} — target server {} is not synced (invalidating cache)", player.getUsername(), newServer);
            if (!isInitialJoin) cache.invalidate(uuid);
            return;
        }

        if (isInitialJoin) {
            QSync.instance().integration().logger().log("Sync", "Skipping SYNC_APPLY for {} — initial join (skipping playerdata sync per configuration)", player.getUsername());
            return;
        }

        // Normal server-switch path.
        server.getScheduler()
                .buildTask(plugin, () -> {
                    String data = cache.consume(uuid);
                    if (data == null) {
                        QSync.instance().integration().logger().log("Sync", "No sync data available for {} after server switch", player.getUsername());
                        return;
                    }
                    QSync.instance().integration().logger().log("Sync", "Found cached data for {} ({} chars), forwarding SYNC_APPLY", player.getUsername(), data.length());
                    sendSyncApply(player, uuid, data);
                })
                .delay(APPLY_DELAY_MS, TimeUnit.MILLISECONDS)
                .schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        cache.invalidate(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------


    private void sendSyncApply(Player player, UUID uuid, String data) {
        player.getCurrentServer().ifPresentOrElse(conn -> {
            JsonObject packet = new JsonObject();
            packet.addProperty("type", PacketType.SYNC_APPLY);
            packet.addProperty("uuid", uuid.toString());
            packet.add("data", new JsonParser().parse(data));
            conn.sendPluginMessage(QSync.CHANNEL, packet.toString().getBytes(StandardCharsets.UTF_8));
            QSync.instance().integration().logger().log("Sync", "Sent SYNC_APPLY for {} to {}", player.getUsername(), conn.getServerInfo().getName());
        }, () -> {
            QSync.instance().integration().logger().log("Sync", "Player {} has no current server for SYNC_APPLY", player.getUsername());
        });
    }
}

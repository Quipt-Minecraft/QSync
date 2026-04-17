package live.qsmc.qsync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Receives plugin messages from backend servers on the {@code qsync:data} channel
 * and stores the player data in the cache for forwarding.
 */
public class PluginMessageHandler {

    private final PlayerDataCache cache;

    public PluginMessageHandler(PlayerDataCache cache) {
        this.cache = cache;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        // Log ALL plugin messages for debugging
        QSync.instance().logger().log("Handler", "PluginMessage received: identifier={}, source={}",
                event.getIdentifier().getId(), event.getSource().getClass().getSimpleName());

        if (!event.getIdentifier().equals(QSync.CHANNEL)) return;
        // Only accept messages sent from a backend server, not from a player client
        if (!(event.getSource() instanceof ServerConnection)) return;

        // Mark as handled so Velocity does not forward this packet to the player
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String raw = new String(event.getData(), StandardCharsets.UTF_8);
        JsonObject packet;
        try {
            JsonParser parser = new JsonParser();
            packet = parser.parse(raw).getAsJsonObject();
        } catch (Exception e) {
            QSync.instance().logger().warn("Handler", "Received malformed QSync packet: {}", raw);
            return;
        }

        if (!PacketType.SYNC_DATA.equals(packet.get("type").getAsString())) return;

        UUID uuid;
        try {
            uuid = UUID.fromString(packet.get("uuid").getAsString());
        } catch (IllegalArgumentException e) {
            QSync.instance().logger().warn("Handler", "QSync SYNC_DATA packet contained invalid UUID");
            return;
        }

        // Store the raw JSON string of the data element so it can be embedded in SYNC_APPLY later
        String data = packet.get("data").toString();
        cache.store(uuid, data);
        QSync.instance().logger().debug("Handler", "Cached sync data for {}", uuid);
    }
}

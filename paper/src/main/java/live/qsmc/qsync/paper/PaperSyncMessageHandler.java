package live.qsmc.qsync.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class PaperSyncMessageHandler implements PluginMessageListener {

    private static final String FORMAT_PLAYER_DAT_GZIP_BASE64 = "PLAYER_DAT_GZIP_BASE64";

    private final QSyncPaper plugin;

    PaperSyncMessageHandler(QSyncPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player transportPlayer, byte[] message) {
        if (!QSyncPaper.CHANNEL.equals(channel)) {
            return;
        }

        plugin.getLogger().info("[QSync] Received plugin message on channel " + channel + " (" + message.length + " bytes)");

        JsonObject packet;
        try {
            packet = JsonParser.parseString(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ex) {
            plugin.getLogger().warning("Received malformed packet on qsync:data.");
            return;
        }

        String type = getString(packet, "type");
        String uuidText = getString(packet, "uuid");
        plugin.getLogger().info("[QSync] Packet type=" + type + " uuid=" + uuidText);
        if (type == null || uuidText == null) {
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Ignoring qsync packet with invalid UUID: " + uuidText);
            return;
        }

        switch (type) {
            case QSyncPaper.TYPE_SYNC_REQUEST -> handleSyncRequest(transportPlayer, targetUuid);
            case QSyncPaper.TYPE_SYNC_APPLY -> handleSyncApply(targetUuid, packet);
            default -> {
                // Ignore unknown packet types so future protocol additions do not break this backend.
            }
        }
    }

    private void handleSyncRequest(Player transportPlayer, UUID targetUuid) {
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer == null || !targetPlayer.isOnline()) {
            plugin.getLogger().warning("[QSync] SYNC_REQUEST ignored — player " + targetUuid + " is offline");
            return;
        }

        try {
            plugin.getLogger().info("[QSync] Capturing data for " + targetUuid);
            byte[] rawPlayerDat = PaperPlayerDataStore.capture(targetPlayer);
            plugin.getLogger().info("[QSync] Captured " + rawPlayerDat.length + " bytes for " + targetUuid);
            String encoded = java.util.Base64.getEncoder().encodeToString(rawPlayerDat);
            sendSyncData(transportPlayer, targetUuid.toString(), FORMAT_PLAYER_DAT_GZIP_BASE64, encoded);
            plugin.getLogger().info("[QSync] Sent SYNC_DATA for " + targetUuid + " via " + transportPlayer.getName());
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to capture data for " + targetUuid + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    void sendSyncData(Player transportPlayer, String targetUuid, String dataFormat, String encodedData) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", QSyncPaper.TYPE_SYNC_DATA);
        packet.addProperty("uuid", targetUuid);

        JsonObject data = new JsonObject();
        data.addProperty("format", dataFormat);
        data.addProperty("blob", encodedData);
        packet.add("data", data);

        transportPlayer.sendPluginMessage(plugin, QSyncPaper.CHANNEL, packet.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void handleSyncApply(UUID targetUuid, JsonObject packet) {
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer == null || !targetPlayer.isOnline()) {
            plugin.getLogger().warning("[QSync] SYNC_APPLY ignored — player " + targetUuid + " is offline");
            return;
        }

        if (!packet.has("data") || !packet.get("data").isJsonObject()) {
            plugin.getLogger().warning("[QSync] SYNC_APPLY has no 'data' object");
            return;
        }

        JsonObject data = packet.getAsJsonObject("data");
        String format = getString(data, "format");
        String blob = getString(data, "blob");
        if (!FORMAT_PLAYER_DAT_GZIP_BASE64.equals(format) || blob == null || blob.isBlank()) {
            plugin.getLogger().warning("Unsupported payload format for " + targetUuid + ": " + format);
            return;
        }

        try {
            byte[] rawPlayerDat = java.util.Base64.getDecoder().decode(blob);
            plugin.getLogger().info("[QSync] Applying " + rawPlayerDat.length + " bytes to " + targetUuid);
            PaperPlayerDataStore.apply(targetPlayer, rawPlayerDat);
            plugin.getLogger().info("[QSync] Successfully applied sync data to " + targetUuid);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to apply sync data for " + targetUuid + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static String getString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            return null;
        }
    }
}


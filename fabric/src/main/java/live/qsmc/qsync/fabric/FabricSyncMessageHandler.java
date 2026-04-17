package live.qsmc.qsync.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Base64;
import java.util.UUID;

final class FabricSyncMessageHandler {

    private static final String FORMAT_PLAYER_DAT_GZIP_BASE64 = "PLAYER_DAT_GZIP_BASE64";

    void onPayload(QSyncPayload payload, ServerPlayNetworking.Context context) {
        MinecraftServer server = context.server();
        ServerPlayerEntity transportPlayer = context.player();
        server.execute(() -> handlePacket(payload, transportPlayer, server));
    }

    private void handlePacket(QSyncPayload payload, ServerPlayerEntity transportPlayer, MinecraftServer server) {
        JsonObject packet;
        try {
            packet = JsonParser.parseString(payload.json()).getAsJsonObject();
        } catch (Exception ignored) {
            System.out.println("[QSync] Failed to parse payload JSON: " + payload.json().substring(0, Math.min(100, payload.json().length())));
            return;
        }

        String type = getString(packet, "type");
        String uuidText = getString(packet, "uuid");
        System.out.println("[QSync] Received packet type=" + type + " uuid=" + uuidText);
        if (type == null || uuidText == null) {
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException ignored) {
            return;
        }

        switch (type) {
            case QSyncFabric.TYPE_SYNC_REQUEST -> handleSyncRequest(transportPlayer, targetUuid, server);
            case QSyncFabric.TYPE_SYNC_APPLY -> handleSyncApply(targetUuid, packet, server);
            default -> {
                // Ignore unknown packet types.
            }
        }
    }

    private void handleSyncRequest(ServerPlayerEntity transportPlayer, UUID targetUuid, MinecraftServer server) {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target == null) {
            System.out.println("[QSync] SYNC_REQUEST ignored — player " + targetUuid + " not found on this server");
            return;
        }

        try {
            System.out.println("[QSync] Capturing data for " + targetUuid);
            byte[] rawPlayerDat = FabricPlayerDataCodec.capture(target);
            System.out.println("[QSync] Captured " + rawPlayerDat.length + " bytes for " + targetUuid);
            String encoded = Base64.getEncoder().encodeToString(rawPlayerDat);

            JsonObject response = new JsonObject();
            response.addProperty("type", QSyncFabric.TYPE_SYNC_DATA);
            response.addProperty("uuid", targetUuid.toString());

            JsonObject data = new JsonObject();
            data.addProperty("format", FORMAT_PLAYER_DAT_GZIP_BASE64);
            data.addProperty("blob", encoded);
            response.add("data", data);

            ServerPlayNetworking.send(transportPlayer, new QSyncPayload(response.toString()));
            System.out.println("[QSync] Sent SYNC_DATA for " + targetUuid + " (" + response.toString().length() + " chars)");
        } catch (Exception e) {
            System.out.println("[QSync] Failed to capture data for " + targetUuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleSyncApply(UUID targetUuid, JsonObject packet, MinecraftServer server) {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target == null || !packet.has("data") || !packet.get("data").isJsonObject()) {
            System.out.println("[QSync] SYNC_APPLY ignored — player " + targetUuid + " not found or no data");
            return;
        }

        JsonObject data = packet.getAsJsonObject("data");
        String format = getString(data, "format");
        String blob = getString(data, "blob");
        if (!FORMAT_PLAYER_DAT_GZIP_BASE64.equals(format) || blob == null || blob.isBlank()) {
            System.out.println("[QSync] SYNC_APPLY unsupported format: " + format);
            return;
        }

        try {
            byte[] rawPlayerDat = Base64.getDecoder().decode(blob);
            System.out.println("[QSync] Applying " + rawPlayerDat.length + " bytes to " + targetUuid);
            FabricPlayerDataCodec.apply(target, rawPlayerDat);
            System.out.println("[QSync] Successfully applied sync data to " + targetUuid);
        } catch (Exception e) {
            System.out.println("[QSync] Failed to apply sync data for " + targetUuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String getString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
            return null;
        }
    }
}

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
            return;
        }

        String type = getString(packet, "type");
        String uuidText = getString(packet, "uuid");
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
            return;
        }

        try {
            byte[] rawPlayerDat = FabricPlayerDataCodec.capture(target);
            String encoded = Base64.getEncoder().encodeToString(rawPlayerDat);

            JsonObject response = new JsonObject();
            response.addProperty("type", QSyncFabric.TYPE_SYNC_DATA);
            response.addProperty("uuid", targetUuid.toString());

            JsonObject data = new JsonObject();
            data.addProperty("format", FORMAT_PLAYER_DAT_GZIP_BASE64);
            data.addProperty("blob", encoded);
            response.add("data", data);

            ServerPlayNetworking.send(transportPlayer, new QSyncPayload(response.toString()));
        } catch (Exception ignored) {
            // Do not crash networking thread on malformed or unsupported data.
        }
    }

    private void handleSyncApply(UUID targetUuid, JsonObject packet, MinecraftServer server) {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target == null || !packet.has("data") || !packet.get("data").isJsonObject()) {
            return;
        }

        JsonObject data = packet.getAsJsonObject("data");
        String format = getString(data, "format");
        String blob = getString(data, "blob");
        if (!FORMAT_PLAYER_DAT_GZIP_BASE64.equals(format) || blob == null || blob.isBlank()) {
            return;
        }

        try {
            byte[] rawPlayerDat = Base64.getDecoder().decode(blob);
            FabricPlayerDataCodec.apply(target, rawPlayerDat);
        } catch (Exception ignored) {
            // Ignore one-off apply failures and keep the player session alive.
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

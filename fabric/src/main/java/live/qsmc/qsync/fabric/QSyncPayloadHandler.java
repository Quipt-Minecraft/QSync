package live.qsmc.qsync.fabric;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Handles raw custom_payload packets that arrive on the qsync:data channel.
 * This bypasses Fabric's networking API, which requires a Fabric-protocol handshake
 * that Velocity (a vanilla proxy) does not perform.
 */
public final class QSyncPayloadHandler {

    private static final Identifier CHANNEL = Identifier.of("qsync", "data");
    private static final FabricSyncMessageHandler HANDLER = new FabricSyncMessageHandler();

    private QSyncPayloadHandler() {}

    /**
     * Called from the Mixin. Returns true if we handled the packet (caller should cancel).
     */
    public static boolean handle(CustomPayload payload, ServerPlayNetworkHandler networkHandler) {
        // Check if this is our channel
        Identifier id = payload.getId().id();
        if (!CHANNEL.equals(id)) {
            return false;
        }

        System.out.println("[QSync] Mixin intercepted payload on qsync:data, type=" + payload.getClass().getSimpleName());

        String json;
        if (payload instanceof QSyncPayload qsyncPayload) {
            json = qsyncPayload.json();
        } else {
            // Payload arrived as UnknownCustomPayload or other type
            // Try to extract raw data via reflection
            json = extractJsonFromPayload(payload);
            if (json == null) {
                System.out.println("[QSync] Could not extract data from " + payload.getClass().getName());
                // Dump all fields for diagnostics
                for (var field : payload.getClass().getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(payload);
                        System.out.println("[QSync]   field " + field.getName() + " (" + field.getType().getSimpleName() + ") = " + (val != null ? val.getClass().getSimpleName() + ":" + val.toString().substring(0, Math.min(50, val.toString().length())) : "null"));
                    } catch (Exception e) {
                        System.out.println("[QSync]   field " + field.getName() + " - access error: " + e.getMessage());
                    }
                }
                return true;
            }
        }

        if (json == null || json.isEmpty()) {
            System.out.println("[QSync] Empty payload on qsync:data");
            return true;
        }

        ServerPlayerEntity player = networkHandler.player;
        MinecraftServer server = networkHandler.server;
        if (server != null) {
            server.execute(() -> HANDLER.onRawPayload(json, player, server));
        }

        return true;
    }

    private static String extractJsonFromPayload(CustomPayload payload) {
        // Try to extract raw data from any payload type via reflection
        try {
            for (var field : payload.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object val = field.get(payload);
                if (val instanceof byte[] bytes) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                if (val instanceof io.netty.buffer.ByteBuf buf && buf.readableBytes() > 0) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), bytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                if (val instanceof String str) {
                    return str;
                }
            }
        } catch (Exception e) {
            System.out.println("[QSync] Reflection failed extracting payload data: " + e.getMessage());
        }
        return null;
    }
}


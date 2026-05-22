package live.qsmc.qsync.fabric;

import com.google.gson.JsonObject;
import live.qsmc.qsync.fabric.config.PortalConfig;
import live.qsmc.quipt.core.config.ConfigManager;
import live.qsmc.quipt.core.config.factories.GenericFactory;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects when players enter a configured portal zone and requests a server switch via Velocity.
 *
 * <h3>Infinite-loop prevention</h3>
 * Every player is marked "just arrived" when they join this server. Portal detection is suppressed
 * for {@code arrival_cooldown_ticks} ticks (default 60 = 3 s). This means if a player's last
 * saved position on this server happens to be inside a portal zone, they won't be immediately
 * sent back again — the cooldown expires before detection resumes.
 *
 * <p>The cooldown is also re-armed the instant a portal trigger fires, preventing repeated
 * {@code PORTAL_REQUEST} packets while the server switch is in flight.
 */
final class PortalManager {

    /** System-time (ms) at which each player joined this server, used for the arrival cooldown. */
    private final Map<UUID, Long> recentArrivals = new ConcurrentHashMap<>();

    private final QSyncFabric mod;

    PortalManager(QSyncFabric mod) {
        this.mod = mod;
        ConfigManager manager = mod.integration().configs();
        manager.factory(new GenericFactory<>(PortalConfig.Zone.class));
        mod.integration().configs().register(PortalConfig.class);
    }

    private PortalConfig config() {
        return QSyncFabric.instance().integration().configs().config(PortalConfig.class);
    }

    /**
     * Call from {@code ServerPlayConnectionEvents.JOIN}.
     * Starts the arrival cooldown so the portal doesn't trigger immediately on join.
     */
    void onPlayerJoin(ServerPlayerEntity player) {
        recentArrivals.put(player.getUuid(), System.currentTimeMillis());
        mod.integration().logger().log("Portals", "{} joined -- portal cooldown active for {}s", player.getName().getString(), config().arrival_cooldown_ms / 1000.0);
    }

    /**
     * Call from {@code ServerPlayConnectionEvents.DISCONNECT}.
     * Cleans up the cooldown entry so memory doesn't grow unboundedly.
     */
    void onPlayerLeave(UUID uuid) {
        recentArrivals.remove(uuid);
    }

    /**
     * Call from {@code ServerTickEvents.END_SERVER_TICK}.
     * Iterates all online players and fires a portal teleport if they are in a zone and past cooldown.
     */
    void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            checkPlayer(player);
        }
    }

    private void checkPlayer(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();

        // Expire cooldown check
        Long arrivalTime = recentArrivals.get(uuid);
        if (arrivalTime != null) {
            if (System.currentTimeMillis() - arrivalTime < config().arrival_cooldown_ms) {
                return; // cooldown still active — skip portal check
            }
            recentArrivals.remove(uuid);
        }

        String worldId = player.getEntityWorld().getRegistryKey().getValue().toString();
        BlockPos pos = player.getBlockPos();

        for (PortalConfig.Zone zone : config().zones.values()) {
            if (zone.contains(worldId, pos.getX(), pos.getY(), pos.getZ())) {
                triggerPortal(player, zone.target_server);
                return;
            }
        }
    }

    /**
     * Sends a {@code PORTAL_REQUEST} plugin message to Velocity, which will perform the
     * actual server switch. Also re-arms the cooldown immediately so that repeated ticks
     * while the switch is processing don't send duplicate requests.
     */
    private void triggerPortal(ServerPlayerEntity player, String targetServer) {
        mod.integration().logger().log("Portals", "{} triggered portal → {}", player.getName().getString(), targetServer);

        // Re-arm cooldown before sending so subsequent ticks are suppressed
        recentArrivals.put(player.getUuid(), System.currentTimeMillis());

        JsonObject packet = new JsonObject();
        packet.addProperty("type", QSyncFabric.TYPE_PORTAL_REQUEST);
        packet.addProperty("uuid", player.getUuid().toString());
        packet.addProperty("target", targetServer);
        // Send S2C — Velocity intercepts this on the qsync:data channel before it reaches the client
        ServerPlayNetworking.send(player, new QSyncPayload(packet.toString()));
    }
}

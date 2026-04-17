package live.qsmc.qsync.paper;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {
    }

    static byte[] capture(Player player) throws IOException {
        player.saveData();
        Path path = playerDataPath(player);
        if (!Files.exists(path)) {
            throw new IOException("Player data file does not exist after save: " + path);
        }
        return Files.readAllBytes(path);
    }

    static void apply(Player player, byte[] rawPlayerDat) throws IOException {
        Location safeLocation = player.getLocation().clone();
        Path path = playerDataPath(player);
        Files.createDirectories(path.getParent());
        Files.write(path, rawPlayerDat);

        // Load the synced file into the live player, then restore location for this server.
        player.loadData();
        player.teleport(safeLocation);
    }

    private static Path playerDataPath(Player player) {
        return player.getWorld()
                .getWorldFolder()
                .toPath()
                .resolve("playerdata")
                .resolve(player.getUniqueId() + ".dat");
    }
}


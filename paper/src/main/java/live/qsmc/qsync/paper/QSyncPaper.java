package live.qsmc.qsync.paper;

import live.qsmc.paper2.QuiptPlugin;
import org.bukkit.Bukkit;

public class QSyncPaper extends QuiptPlugin {

    static final String CHANNEL = "qsync:data";
    static final String TYPE_SYNC_REQUEST = "SYNC_REQUEST";
    static final String TYPE_SYNC_DATA = "SYNC_DATA";
    static final String TYPE_SYNC_APPLY = "SYNC_APPLY";

    private final PaperSyncMessageHandler messageHandler = new PaperSyncMessageHandler(this);

    @Override
    public void enable() {
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, messageHandler);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getLogger().info("QSync Paper backend enabled on channel '" + CHANNEL + "'.");
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, messageHandler);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }
}

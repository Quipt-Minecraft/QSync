package live.qsmc.qsync.fabric.listener;


import live.qsmc.quipt.core.events.Event;
import live.qsmc.quipt.core.events.EventData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.json.JSONObject;

public class QSyncMessageHandleEvent extends Event<QSyncMessageHandleEvent.Data> {
    public QSyncMessageHandleEvent(Data original) {
        super(original);
    }

    public static class Data extends EventData {
        private final MinecraftServer server;
        private final ServerPlayerEntity player;
        private final JSONObject input;
        public Data(MinecraftServer server, ServerPlayerEntity player, JSONObject input) {
            this.server = server;
            this.player = player;
            this.input = input;
        }

        public MinecraftServer server() {
            return server;
        }
        public ServerPlayerEntity player() {
            return player;
        }
        public JSONObject input() {
            return input;
        }
    }
}

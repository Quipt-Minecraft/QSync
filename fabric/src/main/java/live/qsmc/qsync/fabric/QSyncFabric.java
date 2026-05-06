package live.qsmc.qsync.fabric;

import live.qsmc.fabric2.QuiptMod;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.text.Text;

public class QSyncFabric extends QuiptMod {

    static final String TYPE_SYNC_REQUEST = "SYNC_REQUEST";
    static final String TYPE_SYNC_DATA = "SYNC_DATA";
    static final String TYPE_SYNC_APPLY = "SYNC_APPLY";
    static final String TYPE_CHAT_MESSAGE = "CHAT_MESSAGE";

    private final FabricSyncMessageHandler syncMessageHandler = new FabricSyncMessageHandler();

    @Override
    public void run(EntrypointContainer<QuiptMod> entrypoint) {
        super.run(entrypoint);
        PayloadTypeRegistry.playC2S().register(QSyncPayload.ID, QSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QSyncPayload.ID, QSyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QSyncPayload.ID, syncMessageHandler::onPayload);


        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> !isBackendJoinLeaveMessage(message));
    }

    @Override
    public void onInitialize() {

    }

    private static boolean isBackendJoinLeaveMessage(Text message) {
        String plain = message.getString();
        return plain.endsWith(" joined the game") || plain.endsWith(" left the game");
    }
}

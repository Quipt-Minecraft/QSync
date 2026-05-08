package live.qsmc.qsync.fabric;

import live.qsmc.qsync.fabric.listener.QSyncMessageListener;
import live.qsmc.qsync.fabric.listener.QSyncPayloadListener;
import live.qsmc.quipt.core.Quipt;
import live.qsmc.quipt.fabric.QuiptMod;
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

    private static QSyncFabric instance;

    private final QSyncMessageHandler syncMessageHandler = new QSyncMessageHandler();

    @Override
    public void run(EntrypointContainer<QuiptMod> entrypoint) {
        super.run(entrypoint);
        instance = this;
        Quipt.INSTANCE.events().register(new QSyncMessageListener());
        Quipt.INSTANCE.events().register(new QSyncPayloadListener());
        PayloadTypeRegistry.playC2S().register(QSyncPayload.ID, QSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QSyncPayload.ID, QSyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QSyncPayload.ID, syncMessageHandler::onPayload);


        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> !isBackendJoinLeaveMessage(message));
    }

    public static QSyncFabric instance() {
        return instance;
    }


    @Override
    public void onInitialize() {

    }

    private static boolean isBackendJoinLeaveMessage(Text message) {
        String plain = message.getString();
        return plain.endsWith(" joined the game") || plain.endsWith(" left the game");
    }
}

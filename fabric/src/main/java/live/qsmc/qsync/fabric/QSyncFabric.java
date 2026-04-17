package live.qsmc.qsync.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class QSyncFabric implements ModInitializer {

    static final String TYPE_SYNC_REQUEST = "SYNC_REQUEST";
    static final String TYPE_SYNC_DATA = "SYNC_DATA";
    static final String TYPE_SYNC_APPLY = "SYNC_APPLY";

    private final FabricSyncMessageHandler syncMessageHandler = new FabricSyncMessageHandler();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(QSyncPayload.ID, QSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QSyncPayload.ID, QSyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QSyncPayload.ID, syncMessageHandler::onPayload);
    }
}

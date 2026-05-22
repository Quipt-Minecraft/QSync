package live.qsmc.qsync.fabric;

import live.qsmc.qsync.fabric.commands.PortalCommand;
import live.qsmc.qsync.fabric.listener.QSyncMessageListener;
import live.qsmc.quipt.core.Quipt;
import live.qsmc.quipt.fabric.QuiptMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.text.Text;

public class QSyncFabric extends QuiptMod {

    static final String TYPE_SYNC_REQUEST = "SYNC_REQUEST";
    static final String TYPE_SYNC_DATA = "SYNC_DATA";
    static final String TYPE_SYNC_APPLY = "SYNC_APPLY";
    static final String TYPE_CHAT_MESSAGE = "CHAT_MESSAGE";
    static final String TYPE_PORTAL_REQUEST = "PORTAL_REQUEST";

    private static QSyncFabric instance;

    private final QSyncMessageHandler syncMessageHandler = new QSyncMessageHandler();
    private PortalManager portalManager;

    @Override
    public void run(EntrypointContainer<QuiptMod> entrypoint) {
        super.run(entrypoint);
        instance = this;
        Quipt.INSTANCE.events().register(new QSyncMessageListener());
        PayloadTypeRegistry.playC2S().register(QSyncPayload.ID, QSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QSyncPayload.ID, QSyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QSyncPayload.ID, syncMessageHandler::onPayload);

        portalManager = new PortalManager(this);

        // Check portal zones every tick for all online players
        ServerTickEvents.END_SERVER_TICK.register(portalManager::onServerTick);

        // Mark players as "just arrived" on join to suppress portal triggers briefly
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                portalManager.onPlayerJoin(handler.player));

        // Clean up cooldown entries when players leave
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                portalManager.onPlayerLeave(handler.player.getUuid()));

        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> !isBackendJoinLeaveMessage(message));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.getRoot().addChild(new PortalCommand(this).execute()));
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

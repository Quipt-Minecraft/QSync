package live.qsmc.qsync.fabric.listener;

import live.qsmc.core2.events.Event;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayNetworkHandler;

public class QSyncPayloadHandleEvent  extends Event<QSyncPayloadHandleEvent.Data> {
    public QSyncPayloadHandleEvent(QSyncPayloadHandleEvent.Data original) {
        super(original);
    }

    public record Data(CustomPayload payload, ServerPlayNetworkHandler networkHandler) implements Event.Data {
    }
}
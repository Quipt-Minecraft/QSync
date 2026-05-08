package live.qsmc.qsync.fabric.listener;


import live.qsmc.quipt.core.events.Event;
import org.json.JSONObject;

public class QSyncMessageHandleEvent extends Event<QSyncMessageHandleEvent.Data> {
    public QSyncMessageHandleEvent(Data original) {
        super(original);
    }

    public record Data(JSONObject input) implements Event.Data {
    }
}

package live.qsmc.qsync.fabric.listener;


import live.qsmc.quipt.core.events.EventListener;
import org.json.JSONObject;

public class QSyncMessageListener extends EventListener<QSyncMessageHandleEvent, QSyncMessageHandleEvent.Data, JSONObject> {

    public QSyncMessageListener() {
        super(QSyncMessageHandleEvent.class);
    }

    @Override
    public JSONObject handle(QSyncMessageHandleEvent event) {
        System.out.println("MESSAGE LISTENER TRIGGERED");
        return null;
    }
}

package live.qsmc.qsync.fabric.listener;

import live.qsmc.quipt.core.events.EventListener;
import org.json.JSONObject;

public class QSyncPayloadListener  extends EventListener<QSyncPayloadHandleEvent, QSyncPayloadHandleEvent.Data, JSONObject> {

    public QSyncPayloadListener() {
        super(QSyncPayloadHandleEvent.class);
    }


    @Override
    public JSONObject handle(QSyncPayloadHandleEvent event) {
        System.out.println("PAYLOAD LISTENER TRIGGERED");
        return null;

    }
}
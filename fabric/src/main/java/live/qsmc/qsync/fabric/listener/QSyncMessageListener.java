package live.qsmc.qsync.fabric.listener;


import live.qsmc.quipt.core.events.EventListener;
import org.json.JSONObject;

public class QSyncMessageListener extends EventListener<QSyncMessageHandleEvent, QSyncMessageHandleEvent.Data> {

    public QSyncMessageListener() {
        super(QSyncMessageHandleEvent.class);
    }

    @Override
    public void handle(QSyncMessageHandleEvent qSyncMessageHandleEvent) {
        System.out.println("MESSAGE LISTENER TRIGGERED");
    }
}

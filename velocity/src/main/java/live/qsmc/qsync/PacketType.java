package live.qsmc.qsync;

public final class PacketType {

    /** Proxy → backend: request the backend to serialize and return the player's data. */
    public static final String SYNC_REQUEST = "SYNC_REQUEST";

    /** Backend → proxy: the backend's serialized player data payload. */
    public static final String SYNC_DATA = "SYNC_DATA";

    /** Proxy → backend: instruct the backend to apply the provided player data. */
    public static final String SYNC_APPLY = "SYNC_APPLY";

    /** Proxy → backend: broadcast a player's chat message to every other backend. */
    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";


    private PacketType() {}
}

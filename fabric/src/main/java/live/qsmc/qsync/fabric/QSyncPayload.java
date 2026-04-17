package live.qsmc.qsync.fabric;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

record QSyncPayload(String json) implements CustomPayload {

    static final CustomPayload.Id<QSyncPayload> ID = new CustomPayload.Id<>(Identifier.of("qsync", "data"));

    /**
     * Codec that reads/writes raw UTF-8 bytes — NO VarInt string-length prefix.
     * This matches the format Velocity sends via {@code conn.sendPluginMessage()}.
     */
    static final PacketCodec<RegistryByteBuf, QSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeBytes(payload.json().getBytes(StandardCharsets.UTF_8)),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new QSyncPayload(new String(bytes, StandardCharsets.UTF_8));
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

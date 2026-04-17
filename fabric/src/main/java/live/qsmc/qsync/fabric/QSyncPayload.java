package live.qsmc.qsync.fabric;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

record QSyncPayload(String json) implements CustomPayload {

    static final CustomPayload.Id<QSyncPayload> ID = new CustomPayload.Id<>(Identifier.of("qsync", "data"));

    static final PacketCodec<RegistryByteBuf, QSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json()),
            buf -> new QSyncPayload(buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

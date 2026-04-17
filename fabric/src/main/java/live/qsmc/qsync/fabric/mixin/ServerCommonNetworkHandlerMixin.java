package live.qsmc.qsync.fabric.mixin;

import live.qsmc.qsync.fabric.QSyncPayloadHandler;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {

    @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
    private void qsync$onCustomPayload(CustomPayloadC2SPacket packet, CallbackInfo ci) {
        //noinspection ConstantValue
        if ((Object) this instanceof ServerPlayNetworkHandler handler) {
            if (QSyncPayloadHandler.handle(packet.payload(), handler)) {
                ci.cancel();
            }
        }
    }
}

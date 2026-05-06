package live.qsmc.qsync.fabric.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.storage.ReadView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerEntity.class)
public interface PlayerEntityAccessor {

    @Invoker("readCustomData")
    void qsync$invokeReadCustomData(ReadView readView);
}


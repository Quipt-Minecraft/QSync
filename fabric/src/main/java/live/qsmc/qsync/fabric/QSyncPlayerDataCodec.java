package live.qsmc.qsync.fabric;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.util.ErrorReporter;

import com.mojang.serialization.DynamicOps;
import net.minecraft.registry.RegistryOps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class QSyncPlayerDataCodec {

    private QSyncPlayerDataCodec() {}

    /**
     * Serializes the full player root NBT using the registry-aware WriteView.
     */
    static byte[] capture(ServerPlayerEntity player) throws IOException {
        RegistryWrapper.WrapperLookup registries = player.getRegistryManager();
        NbtWriteView writeView = NbtWriteView.create(ErrorReporter.EMPTY, registries);

        // writeData writes the full entity/player root payload.
        player.writeData(writeView);

        NbtCompound nbt = writeView.getNbt();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, baos);
        return baos.toByteArray();
    }

    /**
     * Restores the full synced payload to the live player while preserving destination
     * position/rotation.
     */
    static void apply(ServerPlayerEntity player, byte[] payload) throws Exception {
        NbtCompound nbt = NbtIo.readCompressed(
                new ByteArrayInputStream(payload), NbtSizeTracker.ofUnlimitedBytes());

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        boolean restored = tryReadData(player, nbt);
        if (!restored) {
            applyFallback(player, nbt);
        }

        player.refreshPositionAndAngles(x, y, z, yaw, pitch);
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0.0F;

        float health = player.getHealth();
        player.setHealth(Math.max(0.1F, Math.min(health, player.getMaxHealth())));

        forceClientGameModeSync(player);
        player.sendAbilitiesUpdate();
        player.currentScreenHandler.sendContentUpdates();
    }

    private static void forceClientGameModeSync(ServerPlayerEntity player) {
        if (player.networkHandler == null || player.getGameMode() == null) {
            return;
        }

        float modeId = player.getGameMode().getIndex();
        player.networkHandler.sendPacket(new GameStateChangeS2CPacket(
                GameStateChangeS2CPacket.GAME_MODE_CHANGED,
                modeId
        ));
    }

    private static boolean tryReadData(ServerPlayerEntity player, NbtCompound nbt) {
        try {
            RegistryWrapper.WrapperLookup registries = player.getRegistryManager();
            ReadView readView = NbtReadView.create(ErrorReporter.EMPTY, registries, nbt);

            // readData reads the full entity/player root payload.
            player.readData(readView);
            System.out.println("[QSync] readData applied successfully");
            return true;

        } catch (Exception e) {
            System.out.println("[QSync] readData path failed: " + e.getMessage() + " — will use fallback");
            return false;
        }
    }

    private static void applyFallback(ServerPlayerEntity player, NbtCompound nbt) {
        System.out.println("[QSync] Applying player data via manual fallback");
        DynamicOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, player.getRegistryManager());

        player.getInventory().clear();
        NbtList invNbt = nbt.getList("Inventory").orElseGet(NbtList::new);
        for (NbtElement elem : invNbt) {
            if (!(elem instanceof NbtCompound slotTag)) continue;
            int slot = slotTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack.OPTIONAL_CODEC.parse(ops, slotTag).result().ifPresent(stack -> {
                if (slot < player.getInventory().size()) player.getInventory().setStack(slot, stack);
            });
        }

        player.getEnderChestInventory().clear();
        NbtList enderNbt = nbt.getList("EnderItems").orElseGet(NbtList::new);
        for (NbtElement elem : enderNbt) {
            if (!(elem instanceof NbtCompound slotTag)) continue;
            int slot = slotTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack.OPTIONAL_CODEC.parse(ops, slotTag).result().ifPresent(stack -> {
                if (slot < player.getEnderChestInventory().size()) player.getEnderChestInventory().setStack(slot, stack);
            });
        }

        player.setHealth(Math.min(nbt.getFloat("Health", player.getMaxHealth()), player.getMaxHealth()));
        var hunger = player.getHungerManager();
        hunger.setFoodLevel(nbt.getInt("foodLevel", 20));
        hunger.setSaturationLevel(nbt.getFloat("foodSaturationLevel", 5.0f));

        player.experienceLevel = nbt.getInt("XpLevel", 0);
        player.experienceProgress = nbt.getFloat("XpP", 0.0f);
        player.totalExperience = nbt.getInt("XpTotal", 0);
        player.setScore(nbt.getInt("Score", 0));

        player.getInventory().setSelectedSlot(nbt.getInt("SelectedItemSlot", 0));

        player.clearStatusEffects();
        NbtList effectsNbt = nbt.getList("active_effects").orElseGet(NbtList::new);
        for (NbtElement elem : effectsNbt) {
            StatusEffectInstance.CODEC.parse(ops, elem).result().ifPresent(player::addStatusEffect);
        }
    }
}

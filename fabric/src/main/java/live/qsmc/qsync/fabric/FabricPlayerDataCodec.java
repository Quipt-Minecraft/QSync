package live.qsmc.qsync.fabric;

import com.mojang.serialization.DynamicOps;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class FabricPlayerDataCodec {

    private FabricPlayerDataCodec() {
    }

    /**
     * Serializes the player's current state to gzip-compressed vanilla .dat NBT bytes
     * entirely in-memory — no file I/O or protected method access required.
     */
    static byte[] capture(ServerPlayerEntity player) throws IOException {
NbtWriteView writeView = NbtWriteView.create(ErrorReporter.EMPTY);
        player.saveData(writeView);
        NbtCompound nbt = writeView.getNbt();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, baos);
        return baos.toByteArray();
    }

    /**
     * Applies the synced payload (raw gzip-compressed vanilla .dat NBT) to the
     * live player. Position and game-mode are preserved from the destination server.
     * <p>
     * Uses the Yarn 1.21 ReadView/TypedListReadView API throughout.
     */
    static void apply(ServerPlayerEntity player, byte[] payload) throws Exception {
        NbtCompound nbt = NbtIo.readCompressed(
                new ByteArrayInputStream(payload), NbtSizeTracker.ofUnlimitedBytes());

        DynamicOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, player.getRegistryManager());

        // ── Inventory ────────────────────────────────────────────────────────
        player.getInventory().clear();
        NbtList invNbt = nbt.getList("Inventory").orElseGet(NbtList::new);
        for (NbtElement elem : invNbt) {
            if (!(elem instanceof NbtCompound slotTag)) continue;
            int slot = slotTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack.OPTIONAL_CODEC.parse(ops, slotTag).result().ifPresent(stack -> {
                if (slot < player.getInventory().size()) {
                    player.getInventory().setStack(slot, stack);
                }
            });
        }

        // ── Ender chest ──────────────────────────────────────────────────────
        player.getEnderChestInventory().clear();
        NbtList enderNbt = nbt.getList("EnderItems").orElseGet(NbtList::new);
        for (NbtElement elem : enderNbt) {
            if (!(elem instanceof NbtCompound slotTag)) continue;
            int slot = slotTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack.OPTIONAL_CODEC.parse(ops, slotTag).result().ifPresent(stack -> {
                if (slot < player.getEnderChestInventory().size()) {
                    player.getEnderChestInventory().setStack(slot, stack);
                }
            });
        }

        // ── Health ───────────────────────────────────────────────────────────
        float health = nbt.getFloat("Health", player.getMaxHealth());
        player.setHealth(Math.min(health, player.getMaxHealth()));

        // ── Food and saturation ──────────────────────────────────────────────
        var hunger = player.getHungerManager();
        hunger.setFoodLevel(nbt.getInt("foodLevel", 20));
        hunger.setSaturationLevel(nbt.getFloat("foodSaturationLevel", 5.0f));

        // ── Experience ───────────────────────────────────────────────────────
        player.experienceLevel    = nbt.getInt("XpLevel", 0);
        player.experienceProgress = nbt.getFloat("XpP", 0.0f);
        player.totalExperience    = nbt.getInt("XpTotal", 0);
        player.setScore(nbt.getInt("Score", 0));

        // ── Hotbar slot ──────────────────────────────────────────────────────
        player.getInventory().setSelectedSlot(nbt.getInt("SelectedItemSlot", 0));

        // ── Status effects ───────────────────────────────────────────────────
        player.clearStatusEffects();
        NbtList effectsNbt = nbt.getList("active_effects").orElseGet(NbtList::new);
        for (NbtElement elem : effectsNbt) {
            StatusEffectInstance.CODEC.parse(ops, elem).result()
                    .ifPresent(player::addStatusEffect);
        }

        // Push all changes to the client
        player.sendAbilitiesUpdate();
        player.currentScreenHandler.sendContentUpdates();
    }
}

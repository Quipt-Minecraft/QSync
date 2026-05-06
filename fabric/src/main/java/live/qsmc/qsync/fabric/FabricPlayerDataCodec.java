package live.qsmc.qsync.fabric;

import live.qsmc.qsync.fabric.mixin.PlayerEntityAccessor;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
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

final class FabricPlayerDataCodec {

    private FabricPlayerDataCodec() {}

    // ── Capture ──────────────────────────────────────────────────────────────
    /**
     * Serializes the player's state to gzip-compressed NBT using the registry-aware
     * WriteView so registry-dependent item/effect codecs serialize correctly.
     */
    static byte[] capture(ServerPlayerEntity player) throws IOException {
        RegistryWrapper.WrapperLookup registries = player.getRegistryManager();
        NbtWriteView writeView = NbtWriteView.create(ErrorReporter.EMPTY, registries);
        player.saveData(writeView);
        NbtCompound nbt = writeView.getNbt();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, baos);
        return baos.toByteArray();
    }

    // ── Apply ─────────────────────────────────────────────────────────────────
    /**
     * Restores the synced payload to the live player.
     * <p>
     * Strategy (in order):
     * <ol>
     *   <li>Build an {@code NbtReadView} (the proper read-side counterpart to the
     *       {@code NbtWriteView} used during capture) and call the protected
     *       {@code readCustomData(ReadView)} through a mixin invoker. This is remap-safe
     *       in production jars and mirrors vanilla player-data loading.</li>
     *   <li>If that fails, fall back to explicit NBT key reads for core gameplay state.</li>
     * </ol>
     * Position and rotation of the <em>destination</em> server are preserved so the
     * player does not get teleported to the origin server's coordinates.
     */
    static void apply(ServerPlayerEntity player, byte[] payload) throws Exception {
        NbtCompound nbt = NbtIo.readCompressed(
                new ByteArrayInputStream(payload), NbtSizeTracker.ofUnlimitedBytes());

        // Snapshot destination-server position/rotation before any data restore.
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        boolean restored = tryReadCustomData(player, nbt);
        if (!restored) {
            applyFallback(player, nbt);
        }

        // Always put the player back at the destination's coordinates.
        player.refreshPositionAndAngles(x, y, z, yaw, pitch);
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0.0F;

        // Clamp health in case the origin server had a higher max-health.
        float health = player.getHealth();
        player.setHealth(Math.max(0.1F, Math.min(health, player.getMaxHealth())));

        player.sendAbilitiesUpdate();
        player.currentScreenHandler.sendContentUpdates();
    }

    // ── Primary path: NbtReadView + readCustomData(ReadView) ─────────────────

    private static boolean tryReadCustomData(ServerPlayerEntity player, NbtCompound nbt) {
        try {
            RegistryWrapper.WrapperLookup registries = player.getRegistryManager();
            ReadView readView = NbtReadView.create(ErrorReporter.EMPTY, registries, nbt);

            ((PlayerEntityAccessor) player).qsync$invokeReadCustomData(readView);
            System.out.println("[QSync] readCustomData applied successfully");
            return true;

        } catch (Exception e) {
            System.out.println("[QSync] readCustomData path failed: " + e.getMessage() + " — will use fallback");
            return false;
        }
    }

    // ── Fallback: explicit NBT-key restore ────────────────────────────────────

    private static void applyFallback(ServerPlayerEntity player, NbtCompound nbt) {
        System.out.println("[QSync] Applying player data via manual fallback");
        DynamicOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, player.getRegistryManager());

        // Inventory
        player.getInventory().clear();
        NbtList invNbt = nbt.getList("Inventory").orElseGet(NbtList::new);
        for (NbtElement elem : invNbt) {
            if (!(elem instanceof NbtCompound slotTag)) continue;
            int slot = slotTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack.OPTIONAL_CODEC.parse(ops, slotTag).result().ifPresent(stack -> {
                if (slot < player.getInventory().size()) player.getInventory().setStack(slot, stack);
            });
        }

        // Ender chest
        player.getEnderChestInventory().clear();
        NbtList enderNbt = nbt.getList("EnderItems").orElseGet(NbtList::new);
        for (NbtElement elem : enderNbt) {
            if (!(elem instanceof NbtCompound slotTag)) continue;
            int slot = slotTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack.OPTIONAL_CODEC.parse(ops, slotTag).result().ifPresent(stack -> {
                if (slot < player.getEnderChestInventory().size()) player.getEnderChestInventory().setStack(slot, stack);
            });
        }

        // Health / hunger / saturation
        player.setHealth(Math.min(nbt.getFloat("Health", player.getMaxHealth()), player.getMaxHealth()));
        var hunger = player.getHungerManager();
        hunger.setFoodLevel(nbt.getInt("foodLevel", 20));
        hunger.setSaturationLevel(nbt.getFloat("foodSaturationLevel", 5.0f));

        // Experience / score
        player.experienceLevel = nbt.getInt("XpLevel", 0);
        player.experienceProgress = nbt.getFloat("XpP", 0.0f);
        player.totalExperience = nbt.getInt("XpTotal", 0);
        player.setScore(nbt.getInt("Score", 0));

        // Hotbar selection
        player.getInventory().setSelectedSlot(nbt.getInt("SelectedItemSlot", 0));

        // Status effects
        player.clearStatusEffects();
        NbtList effectsNbt = nbt.getList("active_effects").orElseGet(NbtList::new);
        for (NbtElement elem : effectsNbt) {
            StatusEffectInstance.CODEC.parse(ops, elem).result().ifPresent(player::addStatusEffect);
        }
    }
}

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
import net.minecraft.util.math.Vec3d;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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
     * Applies the synced payload to the live player.
     * <p>
     * QSync prefers Minecraft's native player-NBT loader so the full persisted player
     * state is restored, including inventory, ender chest, effects, attributes and other
     * saved data. If the loader signature changes, a manual fallback still restores the
     * critical gameplay state needed for server switching.
     */
    static void apply(ServerPlayerEntity player, byte[] payload) throws Exception {
        NbtCompound nbt = NbtIo.readCompressed(
                new ByteArrayInputStream(payload), NbtSizeTracker.ofUnlimitedBytes());

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        Vec3d velocity = player.getVelocity();
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        boolean appliedFully = tryApplyFullPlayerData(player, nbt);
        if (!appliedFully) {
            applyFallback(player, nbt);
        }

        player.refreshPositionAndAngles(x, y, z, yaw, pitch);
        player.setVelocity(velocity);
        player.fallDistance = 0.0F;
        player.setHealth(Math.max(0.1F, Math.min(player.getHealth(), player.getMaxHealth())));
        player.sendAbilitiesUpdate();
        player.currentScreenHandler.sendContentUpdates();
    }

    private static boolean tryApplyFullPlayerData(ServerPlayerEntity player, NbtCompound nbt) {
        List<Exception> failures = new ArrayList<>();

        for (Method method : collectCandidateLoadMethods(player.getClass())) {
            Object argument;
            try {
                argument = createLoadArgument(method.getParameterTypes()[0], nbt);
            } catch (Exception e) {
                failures.add(e);
                continue;
            }

            if (argument == null) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(player, argument);
                return true;
            } catch (Exception e) {
                failures.add(e);
            }
        }

        if (!failures.isEmpty()) {
            Exception last = failures.get(failures.size() - 1);
            System.out.println("[QSync] Falling back to partial player restore after reflective load failure: " + last.getMessage());
        }
        return false;
    }

    private static List<Method> collectCandidateLoadMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        String[] candidateNames = {"readData", "loadData", "readCustomDataFromNbt", "readCustomData", "readNbt"};

        for (String candidateName : candidateNames) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals(candidateName) && method.getParameterCount() == 1) {
                        methods.add(method);
                    }
                }
            }
        }

        return methods;
    }

    private static Object createLoadArgument(Class<?> parameterType, NbtCompound nbt) throws Exception {
        if (parameterType.isAssignableFrom(NbtCompound.class)) {
            return nbt;
        }

        for (Method factory : parameterType.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers())) {
                continue;
            }
            if (!parameterType.isAssignableFrom(factory.getReturnType())) {
                continue;
            }

            Object[] args = buildFactoryArguments(factory.getParameterTypes(), nbt);
            if (args != null) {
                factory.setAccessible(true);
                return factory.invoke(null, args);
            }
        }

        return null;
    }

    private static Object[] buildFactoryArguments(Class<?>[] parameterTypes, NbtCompound nbt) {
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType.isAssignableFrom(NbtCompound.class)) {
                args[i] = nbt;
                continue;
            }
            if (parameterType.getName().equals(ErrorReporter.class.getName())) {
                args[i] = ErrorReporter.EMPTY;
                continue;
            }
            return null;
        }

        return args;
    }

    private static void applyFallback(ServerPlayerEntity player, NbtCompound nbt) {
        DynamicOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, player.getRegistryManager());

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

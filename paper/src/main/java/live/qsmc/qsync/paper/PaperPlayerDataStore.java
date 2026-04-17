package live.qsmc.qsync.paper;

import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

/**
 * Captures / applies player data via NMS reflection using MC 1.21.11's
 * TagValueOutput / TagValueInput API (Mojang-mapped).
 */
final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {}

    static byte[] capture(Player player) throws Exception {
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        // ProblemReporter.DISCARDING
        Class<?> problemReporterClass = Class.forName("net.minecraft.util.ProblemReporter");
        Object discarding = problemReporterClass.getField("DISCARDING").get(null);

        // TagValueOutput.createWithoutContext(ProblemReporter) -> TagValueOutput
        Class<?> tagValueOutputClass = Class.forName("net.minecraft.world.level.storage.TagValueOutput");
        Method createWithoutContext = tagValueOutputClass.getMethod("createWithoutContext", problemReporterClass);
        Object valueOutput = createWithoutContext.invoke(null, discarding);

        // Entity.saveWithoutId(ValueOutput) -> void
        Method saveWithoutId = findSingleParamMethod(nmsPlayer.getClass(), "saveWithoutId", valueOutput);
        if (saveWithoutId == null) {
            throw new NoSuchMethodException("Cannot find saveWithoutId that accepts " + valueOutput.getClass().getName());
        }
        saveWithoutId.setAccessible(true);
        saveWithoutId.invoke(nmsPlayer, valueOutput);

        // TagValueOutput.buildResult() -> CompoundTag
        Method buildResult = tagValueOutputClass.getMethod("buildResult");
        Object nbt = buildResult.invoke(valueOutput);

        // NbtIo.writeCompressed(CompoundTag, OutputStream)
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
        Method writeCompressed = findWriteCompressed(nbtIoClass, nbt);
        if (writeCompressed == null) {
            throw new NoSuchMethodException("Could not find NbtIo.writeCompressed");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeCompressed.invoke(null, nbt, baos);
        return baos.toByteArray();
    }

    static void apply(Player player, byte[] rawPlayerDat) throws Exception {
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        // NbtIo.readCompressed(InputStream, NbtAccounter) → CompoundTag
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
        Class<?> nbtAccounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
        Method unlimitedHeap = nbtAccounterClass.getMethod("unlimitedHeap");
        Object accounter = unlimitedHeap.invoke(null);
        Method readCompressed = nbtIoClass.getMethod("readCompressed",
                java.io.InputStream.class, nbtAccounterClass);
        Object nbt = readCompressed.invoke(null, new ByteArrayInputStream(rawPlayerDat), accounter);

        // ProblemReporter.DISCARDING
        Class<?> problemReporterClass = Class.forName("net.minecraft.util.ProblemReporter");
        Object discarding = problemReporterClass.getField("DISCARDING").get(null);

        // registryAccess from the player
        Object registryAccess = nmsPlayer.getClass().getMethod("registryAccess").invoke(nmsPlayer);

        // TagValueInput.create(ProblemReporter, HolderLookup.Provider, CompoundTag) -> ValueInput
        Class<?> tagValueInputClass = Class.forName("net.minecraft.world.level.storage.TagValueInput");
        Class<?> holderLookupProviderClass = Class.forName("net.minecraft.core.HolderLookup$Provider");
        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Method createInput = tagValueInputClass.getMethod("create", problemReporterClass, holderLookupProviderClass, compoundTagClass);
        Object valueInput = createInput.invoke(null, discarding, registryAccess, nbt);

        // Entity.readAdditionalSaveData(ValueInput) -> void
        Method readData = findSingleParamMethod(nmsPlayer.getClass(), "readAdditionalSaveData", valueInput);
        if (readData != null) {
            readData.setAccessible(true);
            readData.invoke(nmsPlayer, valueInput);
        }

        // --- Push to client ---
        try {
            nmsPlayer.getClass().getMethod("onUpdateAbilities").invoke(nmsPlayer);
        } catch (NoSuchMethodException ignored) {}

        try {
            Object menu = nmsPlayer.getClass().getField("inventoryMenu").get(nmsPlayer);
            menu.getClass().getMethod("broadcastChanges").invoke(menu);
        } catch (NoSuchMethodException | NoSuchFieldException ignored) {}
    }

    /**
     * Find a method by name with exactly one parameter, where the given argument is assignable to that parameter type.
     */
    private static Method findSingleParamMethod(Class<?> clazz, String name, Object arg) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(arg)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Find NbtIo.writeCompressed that accepts the given nbt object */
    private static Method findWriteCompressed(Class<?> nbtIoClass, Object nbt) {
        for (Method m : nbtIoClass.getMethods()) {
            if (m.getName().equals("writeCompressed") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0].isInstance(nbt)
                    && m.getParameterTypes()[1].isAssignableFrom(java.io.OutputStream.class)) {
                return m;
            }
        }
        return null;
    }
}

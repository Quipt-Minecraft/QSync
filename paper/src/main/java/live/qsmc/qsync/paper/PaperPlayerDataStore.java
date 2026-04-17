package live.qsmc.qsync.paper;

import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

/**
 * Captures / applies player data via NMS reflection using the MC 1.21.11 ValueOutput/ValueInput API.
 */
final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {}

    static byte[] capture(Player player) throws Exception {
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Object nbt = compoundTagClass.getConstructor().newInstance();

        // Find saveWithoutId by name + single param, accepting whatever type CompoundTag satisfies
        Method saveWithoutId = findSingleParamMethod(nmsPlayer.getClass(), "saveWithoutId", nbt);
        if (saveWithoutId == null) {
            throw new NoSuchMethodException("Cannot find saveWithoutId method that accepts CompoundTag");
        }

        saveWithoutId.setAccessible(true);
        Object result = saveWithoutId.invoke(nmsPlayer, nbt);
        // Old API returned CompoundTag, new API returns void — nbt is mutated in-place
        if (result != null && compoundTagClass.isInstance(result)) {
            nbt = result;
        }

        // NbtIo.writeCompressed
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

        // readAdditionalSaveData reads inventory, health, food, xp, effects, etc.
        Method readData = findSingleParamMethod(nmsPlayer.getClass(), "readAdditionalSaveData", nbt);
        if (readData != null) {
            readData.setAccessible(true);
            readData.invoke(nmsPlayer, nbt);
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
     * Walks the full class hierarchy.
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

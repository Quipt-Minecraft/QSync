package live.qsmc.qsync.paper;

import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

/**
 * Captures / applies player data via NMS reflection using the MC 1.21.11 ValueOutput/ValueInput API.
 * <p>
 * In 1.21.11, Entity.saveWithoutId(ValueOutput) replaces the old saveWithoutId(CompoundTag).
 * CompoundTag implements ValueOutput and ValueInput, so we can pass it directly.
 */
final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {}

    static byte[] capture(Player player) throws Exception {
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Object nbt = compoundTagClass.getConstructor().newInstance();

        // In 1.21.11: Entity.saveWithoutId(ValueOutput) -> void
        // CompoundTag implements ValueOutput, so we pass it directly
        Class<?> valueOutputClass = Class.forName("net.minecraft.nbt.ValueOutput");
        Method saveWithoutId = findMethodInHierarchy(nmsPlayer.getClass(), "saveWithoutId", valueOutputClass);

        if (saveWithoutId == null) {
            // Fallback: try CompoundTag param (older versions)
            saveWithoutId = findMethodInHierarchy(nmsPlayer.getClass(), "saveWithoutId", compoundTagClass);
        }

        if (saveWithoutId == null) {
            throw new NoSuchMethodException("Cannot find saveWithoutId with ValueOutput or CompoundTag param");
        }

        saveWithoutId.setAccessible(true);
        Object result = saveWithoutId.invoke(nmsPlayer, nbt);
        // Old API returned CompoundTag, new API returns void — nbt is mutated in-place
        if (result != null && compoundTagClass.isInstance(result)) {
            nbt = result;
        }

        // NbtIo.writeCompressed(CompoundTag, OutputStream)
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
        Method writeCompressed = findWriteCompressed(nbtIoClass, compoundTagClass);
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

        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");

        // In 1.21.11: Entity.readAdditionalSaveData(ValueInput) -> void
        // CompoundTag implements ValueInput, so we pass it directly
        // readAdditionalSaveData reads inventory, health, food, xp, effects, etc.
        Class<?> valueInputClass = Class.forName("net.minecraft.nbt.ValueInput");
        Method readData = findMethodInHierarchy(nmsPlayer.getClass(), "readAdditionalSaveData", valueInputClass);
        if (readData == null) {
            readData = findMethodInHierarchy(nmsPlayer.getClass(), "readAdditionalSaveData", compoundTagClass);
        }

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

    /** Walk the class hierarchy to find a declared method by name and param types. Returns null if not found. */
    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        // Also check interfaces
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != paramTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (!params[i].isAssignableFrom(paramTypes[i]) && !paramTypes[i].isAssignableFrom(params[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return m;
            }
        }
        return null;
    }

    /** Find NbtIo.writeCompressed - tries (CompoundTag, OutputStream) and (Tag, OutputStream) */
    private static Method findWriteCompressed(Class<?> nbtIoClass, Class<?> compoundTagClass) {
        try {
            return nbtIoClass.getMethod("writeCompressed", compoundTagClass, java.io.OutputStream.class);
        } catch (NoSuchMethodException e) {
            try {
                Class<?> tagClass = Class.forName("net.minecraft.nbt.Tag");
                return nbtIoClass.getMethod("writeCompressed", tagClass, java.io.OutputStream.class);
            } catch (Exception ignored) {}
        }
        return null;
    }
}

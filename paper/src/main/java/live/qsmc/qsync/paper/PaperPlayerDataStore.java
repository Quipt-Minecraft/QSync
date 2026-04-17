package live.qsmc.qsync.paper;

import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Captures / applies player data via NMS reflection using the MC 1.21.11 ValueOutput/ValueInput API.
 */
final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {}

    static byte[] capture(Player player) throws Exception {
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        // Find Entity.saveWithoutId with 1 parameter (the ValueOutput)
        Method saveWithoutId = findMethodByName(nmsPlayer.getClass(), "saveWithoutId", 1);
        if (saveWithoutId == null) {
            throw new NoSuchMethodException("Cannot find saveWithoutId(1 param) on entity hierarchy");
        }
        saveWithoutId.setAccessible(true);

        // The parameter type is ValueOutput — we need to create an instance
        Class<?> valueOutputClass = saveWithoutId.getParameterTypes()[0];

        // Create a ValueOutput. Try multiple strategies:
        // 1. CompoundTag might implement it directly
        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Object nbt = compoundTagClass.getConstructor().newInstance();

        Object valueOutput;
        if (valueOutputClass.isInstance(nbt)) {
            // CompoundTag implements ValueOutput directly
            valueOutput = nbt;
        } else {
            // Need to create a ValueOutput wrapping a CompoundTag
            // Try static factory methods on the ValueOutput type
            valueOutput = createValueOutput(valueOutputClass, compoundTagClass);
            if (valueOutput == null) {
                // Dump available info for diagnostics
                StringBuilder sb = new StringBuilder("Cannot create ValueOutput. Class: " + valueOutputClass.getName());
                sb.append("\nCompoundTag interfaces: ");
                for (Class<?> iface : compoundTagClass.getInterfaces()) {
                    sb.append(iface.getName()).append(", ");
                }
                sb.append("\nValueOutput is interface: ").append(valueOutputClass.isInterface());
                sb.append("\nValueOutput methods: ");
                for (Method m : valueOutputClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) {
                        sb.append("static ").append(m.getName()).append("(");
                        for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                        sb.append(")->").append(m.getReturnType().getSimpleName()).append("; ");
                    }
                }
                sb.append("\nValueOutput constructors: ");
                for (Constructor<?> c : valueOutputClass.getDeclaredConstructors()) {
                    sb.append("(");
                    for (Class<?> p : c.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                    sb.append("); ");
                }
                // Also check implementations/subclasses that wrap CompoundTag
                sb.append("\nCompoundTag superclass: ").append(compoundTagClass.getSuperclass().getName());
                throw new RuntimeException(sb.toString());
            }
        }

        saveWithoutId.invoke(nmsPlayer, valueOutput);

        // Extract CompoundTag from the valueOutput if it's a wrapper
        if (valueOutputClass.isInstance(nbt)) {
            // nbt was used directly as valueOutput, already mutated
        } else {
            // Try to get the CompoundTag out of the wrapper via getNbt(), getTag(), toCompound(), etc.
            nbt = extractCompoundTag(valueOutput, compoundTagClass);
            if (nbt == null) {
                throw new RuntimeException("Cannot extract CompoundTag from ValueOutput wrapper: " + valueOutput.getClass().getName());
            }
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

        // Find readAdditionalSaveData with 1 param
        Method readData = findMethodByName(nmsPlayer.getClass(), "readAdditionalSaveData", 1);
        if (readData != null) {
            readData.setAccessible(true);
            Class<?> paramType = readData.getParameterTypes()[0];

            Object valueInput;
            if (paramType.isInstance(nbt)) {
                valueInput = nbt;
            } else {
                // Create a ValueInput wrapper around the CompoundTag
                valueInput = createValueInput(paramType, nbt);
                if (valueInput == null) {
                    throw new RuntimeException("Cannot create ValueInput from CompoundTag for readAdditionalSaveData");
                }
            }
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

    /** Find a method by name with exactly N parameters, walking hierarchy. */
    private static Method findMethodByName(Class<?> clazz, String name, int paramCount) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Try to create a ValueOutput instance via static factories or constructors. */
    private static Object createValueOutput(Class<?> voClass, Class<?> compoundTagClass) {
        // Try static factory methods that return ValueOutput
        for (Method m : voClass.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && voClass.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    if (m.getParameterCount() == 0) {
                        return m.invoke(null);
                    }
                } catch (Exception ignored) {}
            }
        }
        // Try constructors
        for (Constructor<?> c : voClass.getDeclaredConstructors()) {
            try {
                c.setAccessible(true);
                if (c.getParameterCount() == 0) {
                    return c.newInstance();
                }
            } catch (Exception ignored) {}
        }
        // Try subclasses / known wrappers — check if there's an NbtValueOutput or similar
        // that takes a CompoundTag
        String[] wrapperNames = {
            "net.minecraft.nbt.NbtValueOutput",
            "net.minecraft.nbt.CompoundTagValueOutput",
            "net.minecraft.nbt.CompoundTag$ValueOutputImpl"
        };
        for (String name : wrapperNames) {
            try {
                Class<?> wrapperClass = Class.forName(name, true, voClass.getClassLoader());
                for (Constructor<?> c : wrapperClass.getDeclaredConstructors()) {
                    if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isAssignableFrom(compoundTagClass)) {
                        c.setAccessible(true);
                        return c.newInstance(compoundTagClass.getConstructor().newInstance());
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Try to create a ValueInput wrapper around a CompoundTag. */
    private static Object createValueInput(Class<?> viClass, Object nbt) {
        // Try static factory methods
        for (Method m : viClass.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && viClass.isAssignableFrom(m.getReturnType())
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(nbt)) {
                try {
                    m.setAccessible(true);
                    return m.invoke(null, nbt);
                } catch (Exception ignored) {}
            }
        }
        // Try constructors
        for (Constructor<?> c : viClass.getDeclaredConstructors()) {
            if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(nbt)) {
                try {
                    c.setAccessible(true);
                    return c.newInstance(nbt);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** Extract CompoundTag from a ValueOutput wrapper. */
    private static Object extractCompoundTag(Object wrapper, Class<?> compoundTagClass) {
        String[] getterNames = {"getNbt", "getTag", "toCompound", "toNbt", "getCompound", "build"};
        for (String name : getterNames) {
            try {
                Method m = wrapper.getClass().getMethod(name);
                if (compoundTagClass.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return m.invoke(wrapper);
                }
            } catch (Exception ignored) {}
        }
        // Try any no-arg method returning CompoundTag
        for (Method m : wrapper.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && compoundTagClass.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return m.invoke(wrapper);
                } catch (Exception ignored) {}
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

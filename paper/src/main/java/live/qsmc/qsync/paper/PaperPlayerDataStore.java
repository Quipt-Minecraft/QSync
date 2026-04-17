package live.qsmc.qsync.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Captures / applies player data using pure Bukkit API.
 * Serializes to a gzip-compressed JSON blob (not vanilla NBT).
 * The Velocity bridge treats the payload as opaque bytes, so format is ours to define
 * as long as the same backend (Paper) can read what it wrote.
 *
 * For cross-backend sync (Paper ↔ Fabric), the Velocity module must handle
 * format negotiation. This class only handles Paper ↔ Paper or Paper-capture → Fabric-apply
 * via the PLAYER_DAT_GZIP_BASE64 format wrapping vanilla NBT.
 */
final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {}

    /**
     * Capture player data to gzip-compressed vanilla-compatible NBT via NMS reflection,
     * with a fallback diagnostic if the method name has changed.
     */
    static byte[] capture(Player player) throws Exception {
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Object nbt = compoundTagClass.getConstructor().newInstance();

        // Try to find the save method - name varies by MC version
        java.lang.reflect.Method saveMethod = null;
        String[] candidates = {"saveWithoutId", "saveCompound", "serializeEntity", "save"};
        for (String name : candidates) {
            saveMethod = findMethodInHierarchy(nmsPlayer.getClass(), name, compoundTagClass);
            if (saveMethod != null) break;
        }

        if (saveMethod == null) {
            // Dump all single-arg methods accepting CompoundTag for diagnostics
            StringBuilder sb = new StringBuilder("Could not find save method. Candidates on entity hierarchy: ");
            for (Class<?> c = nmsPlayer.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0].getName().contains("CompoundTag")) {
                        sb.append(c.getSimpleName()).append(".").append(m.getName()).append("(").append(params[0].getSimpleName()).append("), ");
                    }
                }
            }
            throw new NoSuchMethodException(sb.toString());
        }

        saveMethod.setAccessible(true);
        nbt = saveMethod.invoke(nmsPlayer, nbt);

        // NbtIo.writeCompressed(CompoundTag, OutputStream)
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
        java.lang.reflect.Method writeCompressed = findWriteCompressed(nbtIoClass, compoundTagClass);
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
        java.lang.reflect.Method unlimitedHeap = nbtAccounterClass.getMethod("unlimitedHeap");
        Object accounter = unlimitedHeap.invoke(null);
        java.lang.reflect.Method readCompressed = nbtIoClass.getMethod("readCompressed",
                java.io.InputStream.class, nbtAccounterClass);
        Object nbt = readCompressed.invoke(null, new ByteArrayInputStream(rawPlayerDat), accounter);

        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");

        // --- Inventory ---
        Object inventory = nmsPlayer.getClass().getMethod("getInventory").invoke(nmsPlayer);
        Class<?> listTagClass = Class.forName("net.minecraft.nbt.ListTag");

        java.lang.reflect.Method getList = compoundTagClass.getMethod("getList", String.class);
        Object invList = getList.invoke(nbt, "Inventory");
        java.lang.reflect.Method loadInv = findMethodInHierarchy(inventory.getClass(), "load", listTagClass);
        if (loadInv != null) {
            loadInv.setAccessible(true);
            loadInv.invoke(inventory, invList);
        }

        // selected slot
        java.lang.reflect.Method getIntOr = compoundTagClass.getMethod("getIntOr", String.class, int.class);
        int selectedSlot = (int) getIntOr.invoke(nbt, "SelectedItemSlot", 0);
        inventory.getClass().getMethod("setSelectedSlot", int.class).invoke(inventory, selectedSlot);

        // --- Ender chest ---
        Object enderChest = nmsPlayer.getClass().getMethod("getEnderChestInventory").invoke(nmsPlayer);
        Object enderList = getList.invoke(nbt, "EnderItems");
        Object registryAccess = nmsPlayer.getClass().getMethod("registryAccess").invoke(nmsPlayer);
        java.lang.reflect.Method fromTag = findMethodInHierarchy(enderChest.getClass(), "fromTag", listTagClass,
                Class.forName("net.minecraft.core.HolderLookup$Provider"));
        if (fromTag != null) {
            fromTag.setAccessible(true);
            fromTag.invoke(enderChest, enderList, registryAccess);
        }

        // --- Health ---
        java.lang.reflect.Method getFloatOr = compoundTagClass.getMethod("getFloatOr", String.class, float.class);
        float maxHealth = (float) nmsPlayer.getClass().getMethod("getMaxHealth").invoke(nmsPlayer);
        float health = (float) getFloatOr.invoke(nbt, "Health", maxHealth);
        nmsPlayer.getClass().getMethod("setHealth", float.class)
                .invoke(nmsPlayer, Math.min(health, maxHealth));

        // --- Food ---
        Object foodData = nmsPlayer.getClass().getMethod("getFoodData").invoke(nmsPlayer);
        int foodLevel = (int) getIntOr.invoke(nbt, "foodLevel", 20);
        float saturation = (float) getFloatOr.invoke(nbt, "foodSaturationLevel", 5.0f);
        foodData.getClass().getMethod("setFoodLevel", int.class).invoke(foodData, foodLevel);
        foodData.getClass().getMethod("setSaturation", float.class).invoke(foodData, saturation);

        // --- XP ---
        nmsPlayer.getClass().getField("experienceLevel").setInt(nmsPlayer,
                (int) getIntOr.invoke(nbt, "XpLevel", 0));
        nmsPlayer.getClass().getField("experienceProgress").setFloat(nmsPlayer,
                (float) getFloatOr.invoke(nbt, "XpP", 0.0f));
        nmsPlayer.getClass().getField("totalExperience").setInt(nmsPlayer,
                (int) getIntOr.invoke(nbt, "XpTotal", 0));

        // --- Score ---
        nmsPlayer.getClass().getMethod("setScore", int.class)
                .invoke(nmsPlayer, (int) getIntOr.invoke(nbt, "Score", 0));

        // --- Push to client ---
        nmsPlayer.getClass().getMethod("onUpdateAbilities").invoke(nmsPlayer);
        Object menu = nmsPlayer.getClass().getField("inventoryMenu").get(nmsPlayer);
        menu.getClass().getMethod("broadcastChanges").invoke(menu);
    }

    /** Walk the class hierarchy to find a declared method by name and param types. Returns null if not found. */
    private static java.lang.reflect.Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /** Find NbtIo.writeCompressed - tries (CompoundTag, OutputStream) and (Tag, OutputStream) */
    private static java.lang.reflect.Method findWriteCompressed(Class<?> nbtIoClass, Class<?> compoundTagClass) {
        try {
            return nbtIoClass.getMethod("writeCompressed", compoundTagClass, java.io.OutputStream.class);
        } catch (NoSuchMethodException e) {
            // Try with Tag supertype
            try {
                Class<?> tagClass = Class.forName("net.minecraft.nbt.Tag");
                return nbtIoClass.getMethod("writeCompressed", tagClass, java.io.OutputStream.class);
            } catch (Exception ignored) {}
        }
        return null;
    }
}

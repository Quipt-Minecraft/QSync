package live.qsmc.qsync.paper;

import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * Captures / applies player data entirely in-memory via NMS reflection.
 * <p>
 * Paper 1.20.5+ uses Mojang-mapped NMS at runtime, so the class and method
 * names below are the official Mojang names (no obfuscation).
 */
final class PaperPlayerDataStore {

    private PaperPlayerDataStore() {
    }

    /**
     * Serializes the player's current state to gzip-compressed vanilla .dat NBT
     * bytes entirely in-memory — no file I/O required.
     */
    static byte[] capture(Player player) throws Exception {
        // CraftPlayer.getHandle() → ServerPlayer (net.minecraft.server.level.ServerPlayer)
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        // new CompoundTag()
        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Object nbt = compoundTagClass.getConstructor().newInstance();

        // ServerPlayer extends Entity → Entity.saveWithoutId(CompoundTag) : CompoundTag
        Method saveWithoutId = findMethod(nmsPlayer.getClass(), "saveWithoutId", compoundTagClass);
        saveWithoutId.setAccessible(true);
        nbt = saveWithoutId.invoke(nmsPlayer, nbt);

        // NbtIo.writeCompressed(CompoundTag, OutputStream)
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
        Method writeCompressed = nbtIoClass.getMethod("writeCompressed", compoundTagClass, OutputStream.class);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeCompressed.invoke(null, nbt, baos);
        return baos.toByteArray();
    }

    /**
     * Applies synced player data. Writes the raw .dat bytes to the playerdata
     * file and loads them via the Bukkit API.
     */
    static void apply(Player player, byte[] rawPlayerDat) throws Exception {
        // CraftPlayer.getHandle() → ServerPlayer
        Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);

        // NbtIo.readCompressed(InputStream, NbtAccounter) → CompoundTag
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
        Class<?> nbtAccounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
        Method unlimitedHeap = nbtAccounterClass.getMethod("unlimitedHeap");
        Object accounter = unlimitedHeap.invoke(null);
        Method readCompressed = nbtIoClass.getMethod("readCompressed",
                java.io.InputStream.class, nbtAccounterClass);
        Object nbt = readCompressed.invoke(null, new ByteArrayInputStream(rawPlayerDat), accounter);

        // Entity.load(CompoundTag) — reads ALL data including inventory, health, etc.
        // We use readAdditionalSaveData to skip position/motion/rotation loading.
        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");

        // --- Inventory ---
        Object inventory = nmsPlayer.getClass().getMethod("getInventory").invoke(nmsPlayer);
        Class<?> listTagClass = Class.forName("net.minecraft.nbt.ListTag");

        // CompoundTag.getList(String) → ListTag (Paper 1.21 Mojang-mapped)
        Method getList = compoundTagClass.getMethod("getList", String.class);
        Object invList = getList.invoke(nbt, "Inventory");
        Method loadInv = inventory.getClass().getMethod("load", listTagClass);
        loadInv.invoke(inventory, invList);

        // selected slot
        Method getIntOr = compoundTagClass.getMethod("getIntOr", String.class, int.class);
        int selectedSlot = (int) getIntOr.invoke(nbt, "SelectedItemSlot", 0);
        inventory.getClass().getMethod("setSelectedSlot", int.class).invoke(inventory, selectedSlot);

        // --- Ender chest ---
        Object enderChest = nmsPlayer.getClass().getMethod("getEnderChestInventory").invoke(nmsPlayer);
        Object enderList = getList.invoke(nbt, "EnderItems");
        // PlayerEnderChestContainer.fromTag(ListTag, HolderLookup.Provider)
        Object registryAccess = nmsPlayer.getClass().getMethod("registryAccess").invoke(nmsPlayer);
        Method fromTag = findMethod(enderChest.getClass(), "fromTag", listTagClass,
                Class.forName("net.minecraft.core.HolderLookup$Provider"));
        fromTag.setAccessible(true);
        fromTag.invoke(enderChest, enderList, registryAccess);

        // --- Health ---
        Method getFloatOr = compoundTagClass.getMethod("getFloatOr", String.class, float.class);
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

    /** Walk the class hierarchy to find a method by name and parameter types. */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }
}

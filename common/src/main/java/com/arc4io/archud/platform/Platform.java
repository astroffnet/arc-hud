package com.arc4io.archud.platform;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;

/**
 * То, что отличается между Forge и Fabric (безопасно для сервера — без клиентских классов).
 */
public interface Platform {
    String loaderName();

    Path configDir();

    boolean isModLoaded(String modId);

    /** Красивое имя мода по namespace (например "twilightforest" -> "The Twilight Forest"). */
    String modName(String namespace);

    int modCount();

    /** Рюкзак в слоте Curios / Trinkets или ItemStack.EMPTY. */
    ItemStack findEquippedBackpack(Player player);

    /** Первый предмет в слотах-аксессуарах (Curios / Trinkets: браслет, пояс, спина…) по условию, или ItemStack.EMPTY. */
    default ItemStack findAccessory(Player player, java.util.function.Predicate<ItemStack> filter) {
        return ItemStack.EMPTY;
    }

    /** {запасено, максимум} энергии предмета (Forge Energy, IC2-подобные NBT и т.д.) или null. */
    long[] getItemEnergy(ItemStack stack);
}

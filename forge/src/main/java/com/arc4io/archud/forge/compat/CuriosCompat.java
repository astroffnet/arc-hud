package com.arc4io.archud.forge.compat;

import com.arc4io.archud.item.BackpackItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

/**
 * Curios: поиск рюкзака в слотах-аксессуарах (спина и т.д.).
 * Класс загружается только если Curios установлен.
 */
public final class CuriosCompat {
    private CuriosCompat() {}

    public static ItemStack find(Player player, java.util.function.Predicate<ItemStack> filter) {
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(handler -> handler.findFirstCurio(filter))
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }

    public static ItemStack findBackpack(Player player) {
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(handler -> handler.findFirstCurio(stack -> stack.getItem() instanceof BackpackItem))
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }
}

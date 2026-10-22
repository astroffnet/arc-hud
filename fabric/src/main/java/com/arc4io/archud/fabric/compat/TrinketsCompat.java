package com.arc4io.archud.fabric.compat;

import com.arc4io.archud.item.BackpackItem;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Trinkets: поиск рюкзака в слоте спины. Загружается только при установленном Trinkets.
 */
public final class TrinketsCompat {
    private TrinketsCompat() {}

    public static ItemStack findBackpack(Player player) {
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(player);
        if (component.isEmpty()) return ItemStack.EMPTY;
        var equipped = component.get().getEquipped(stack -> stack.getItem() instanceof BackpackItem);
        return equipped.isEmpty() ? ItemStack.EMPTY : equipped.get(0).getB();
    }
}

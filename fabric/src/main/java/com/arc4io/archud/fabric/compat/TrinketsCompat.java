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

    public static ItemStack find(Player player, java.util.function.Predicate<ItemStack> filter) {
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(player);
        if (component.isEmpty()) return ItemStack.EMPTY;
        var equipped = component.get().getEquipped(filter);
        return equipped.isEmpty() ? ItemStack.EMPTY : equipped.get(0).getB();
    }

    public static ItemStack findBackpack(Player player) {
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(player);
        if (component.isEmpty()) return ItemStack.EMPTY;
        var equipped = component.get().getEquipped(stack -> stack.getItem() instanceof BackpackItem);
        return equipped.isEmpty() ? ItemStack.EMPTY : equipped.get(0).getB();
    }

    /** Tempad не выпадает из слота Trinkets после смерти. */
    public static void registerKeepTempad() {
        dev.emi.trinkets.api.event.TrinketDropCallback.EVENT.register((rule, stack, ref, entity) ->
                com.arc4io.archud.config.ArcConfig.get().tempadKeepOnDeath && com.arc4io.archud.server.TempadServer.isTempadStack(stack)
                        ? dev.emi.trinkets.api.TrinketEnums.DropRule.KEEP : rule);
    }
}

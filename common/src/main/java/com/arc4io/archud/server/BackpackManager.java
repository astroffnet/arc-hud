package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.item.BackpackItem;
import com.arc4io.archud.menu.BackpackContainer;
import com.arc4io.archud.menu.BackpackMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Открытие рюкзаков на сервере.
 */
public final class BackpackManager {
    public static final int OFFHAND_SLOT = 40;

    private BackpackManager() {}

    /** Клавиша рюкзака: сначала надетый (Curios/Trinkets), потом первый в инвентаре. */
    public static void openEquippedOrFirst(ServerPlayer player) {
        ItemStack equipped = ArcHud.platform().findEquippedBackpack(player);
        if (!equipped.isEmpty() && equipped.getItem() instanceof BackpackItem) {
            open(player, equipped, -1, p -> ArcHud.platform().findEquippedBackpack(p) == equipped);
            return;
        }
        Inventory inv = player.getInventory();
        if (inv.getItem(OFFHAND_SLOT).getItem() instanceof BackpackItem) {
            openFromInventory(player, OFFHAND_SLOT);
            return;
        }
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).getItem() instanceof BackpackItem) {
                openFromInventory(player, i);
                return;
            }
        }
        player.displayClientMessage(Component.translatable("archud.backpack.none"), true);
    }

    public static void openFromInventory(ServerPlayer player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof BackpackItem)) return;
        open(player, stack, slot, p -> p.getInventory().getItem(slot) == stack);
    }

    private static void open(ServerPlayer player, ItemStack stack, int lockedSlot, Predicate<net.minecraft.world.entity.player.Player> valid) {
        BackpackItem item = (BackpackItem) stack.getItem();
        int rows = item.tier().rows();
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new BackpackMenu(id, inv, new BackpackContainer(stack, rows * 9, valid), rows, lockedSlot),
                stack.getHoverName()));
        player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.7F, 1.15F);
    }
}

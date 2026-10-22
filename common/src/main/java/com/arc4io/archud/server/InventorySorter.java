package com.arc4io.archud.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Сортировка основного инвентаря (без хотбара): объединяет стаки и упорядочивает.
 * Порядок: блоки -> остальные предметы, внутри по id мода и предмета.
 */
public final class InventorySorter {
    private InventorySorter() {}

    public static void sort(ServerPlayer player) {
        if (player.isSpectator()) return;
        Inventory inv = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) items.add(s.copy());
            inv.setItem(i, ItemStack.EMPTY);
        }

        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : items) {
            for (ItemStack m : merged) {
                if (s.isEmpty()) break;
                if (ItemStack.isSameItemSameTags(m, s) && m.getCount() < m.getMaxStackSize()) {
                    int move = Math.min(s.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(move);
                    s.shrink(move);
                }
            }
            if (!s.isEmpty()) merged.add(s);
        }

        merged.sort(Comparator
                .comparingInt((ItemStack s) -> s.getItem() instanceof BlockItem ? 0 : 1)
                .thenComparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
                .thenComparingInt(s -> -s.getCount()));

        for (int i = 0; i < merged.size() && i < 27; i++) {
            inv.setItem(9 + i, merged.get(i));
        }
        // если вдруг что-то не влезло — вернуть игроку
        for (int i = 27; i < merged.size(); i++) {
            inv.placeItemBackInInventory(merged.get(i));
        }

        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        player.level().playSound(null, player.blockPosition(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.6F, 1.3F);
    }
}

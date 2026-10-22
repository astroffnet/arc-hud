package com.arc4io.archud.menu;

import com.arc4io.archud.item.BackpackItem;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Инвентарь рюкзака, хранится прямо в NBT предмета ("Items").
 */
public class BackpackContainer extends SimpleContainer {
    private final ItemStack backpack;
    private final Predicate<Player> validity;
    private boolean loading;

    public BackpackContainer(ItemStack backpack, int size, Predicate<Player> validity) {
        super(size);
        this.backpack = backpack;
        this.validity = validity;
        load();
    }

    private void load() {
        loading = true;
        CompoundTag tag = backpack.getTag();
        if (tag != null && tag.contains("Items")) {
            NonNullList<ItemStack> items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, items);
            for (int i = 0; i < items.size(); i++) {
                setItem(i, items.get(i));
            }
        }
        loading = false;
    }

    public void save() {
        NonNullList<ItemStack> items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < getContainerSize(); i++) {
            items.set(i, getItem(i));
        }
        ContainerHelper.saveAllItems(backpack.getOrCreateTag(), items, true);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (!loading) save();
    }

    @Override
    public boolean stillValid(Player player) {
        return validity.test(player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !(stack.getItem() instanceof BackpackItem);
    }

    @Override
    public void stopOpen(Player player) {
        super.stopOpen(player);
        // Защита: рюкзак внутри рюкзака выкидываем обратно игроку
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = getItem(i);
            if (s.getItem() instanceof BackpackItem) {
                ItemStack moved = removeItemNoUpdate(i);
                player.getInventory().placeItemBackInInventory(moved);
            }
        }
        save();
    }
}

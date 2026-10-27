package com.arc4io.archud.forge.inventory;

import com.arc4io.archud.ArcHud;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Слоты «Дополнительной экипировки» Legends Core (голова, тело, руки, ноги…) прямо в новом инвентаре.
 * Создаётся невидимое меню Legends, и его 9 слотов переносятся к нам — вся логика надевания/снятия
 * (проверки, конфликты областей, эффекты) остаётся самой Legends.
 */
public final class LegendsEquipment {
    public static final int COUNT = 9;
    private static final String[] REGIONS = {"head", "head_add_on", "body", "left_arm", "right_arm", "left_leg", "right_leg", "both_arms", "both_legs"};

    private LegendsEquipment() {}

    public static boolean available() {
        return ModList.get().isLoaded("legendsmod");
    }

    public static Component label(int i) {
        return Component.translatable("archud.inv.legends." + REGIONS[Math.max(0, Math.min(REGIONS.length - 1, i))]);
    }

    public static Component shortLabel(int i) {
        return Component.translatable("archud.inv.legends.short." + REGIONS[Math.max(0, Math.min(REGIONS.length - 1, i))]);
    }

    /** Слоты Legends (или «пустышки», если меню Legends не создать — чтобы число слотов совпало с сервером). */
    public static List<Slot> create(Inventory inv) {
        List<Slot> out = new ArrayList<>();
        try {
            Class<?> c = Class.forName("com.tihyo.legends.client.gui.screen.ExtraEquipmentMenu");
            Constructor<?> ctor = c.getConstructor(int.class, Inventory.class);
            AbstractContainerMenu hidden = (AbstractContainerMenu) ctor.newInstance(-1, inv);
            for (int i = 0; i < COUNT && i < hidden.slots.size(); i++) out.add(hidden.slots.get(i));
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: Legends extra equipment slots unavailable", t);
            out.clear();
        }
        if (out.size() < COUNT) {
            SimpleContainer dummy = new SimpleContainer(COUNT);
            for (int i = out.size(); i < COUNT; i++) {
                out.add(new Slot(dummy, i, 0, 0) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                });
            }
        }
        return out;
    }

    public static String region(int i) {
        return REGIONS[i].toUpperCase(Locale.ROOT);
    }
}

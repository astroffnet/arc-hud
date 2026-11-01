package com.arc4io.archud.client.recipes;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * Предмет, подсказку которого окно рисовало в последнем кадре (достижения, торговля, кнопки и панели модов…):
 * R/U работают над любым предметом с подсказкой, не только в ячейках.
 */
public final class TooltipStack {
    private static ItemStack stack = ItemStack.EMPTY;
    private static Screen screen;
    private static long at;

    private TooltipStack() {}

    public static void record(ItemStack s) {
        if (s == null || s.isEmpty()) return;
        stack = s;
        screen = Minecraft.getInstance().screen;
        at = Util.getMillis();
    }

    public static ItemStack last(Screen s) {
        if (s != screen || Util.getMillis() - at > 150) return ItemStack.EMPTY;
        return stack;
    }
}

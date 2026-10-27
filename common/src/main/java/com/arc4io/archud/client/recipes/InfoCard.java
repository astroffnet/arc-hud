package com.arc4io.archud.client.recipes;

import com.arc4io.archud.client.hud.Draw;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Страница-подсказка (addIngredientInfo у JEI): предмет(ы) сверху и текст описания. */
public final class InfoCard implements RecipeCard {
    private static final int W = 160;
    private final List<ItemStack> items;
    private final List<FormattedCharSequence> lines = new ArrayList<>();

    public InfoCard(List<ItemStack> items, List<Component> text) {
        this.items = items;
        var font = Minecraft.getInstance().font;
        for (Component c : text) {
            lines.addAll(font.split(c, W - 4));
        }
        while (lines.size() > 14) lines.remove(lines.size() - 1);
    }

    @Override
    public int width() {
        return W;
    }

    @Override
    public int height() {
        return 22 + lines.size() * 10;
    }

    private ItemStack shown() {
        return items.isEmpty() ? ItemStack.EMPTY : items.get((int) ((Util.getMillis() / 1000) % items.size()));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        int sx = (W - 18) / 2;
        Draw.panel(g, sx, 0, 18, 18, 0xE0070A0E, 0x50FFFFFF);
        g.renderItem(shown(), sx + 1, 1);
        var font = Minecraft.getInstance().font;
        int y = 22;
        for (FormattedCharSequence l : lines) {
            g.drawString(font, l, 2, y, 0xFFD8DEE4, false);
            y += 10;
        }
    }

    @Override
    public ItemStack stackAt(int mx, int my) {
        int sx = (W - 18) / 2;
        return mx >= sx && mx < sx + 18 && my >= 0 && my < 18 ? shown() : ItemStack.EMPTY;
    }

    @Override
    public List<Component> tooltip(int mx, int my) {
        ItemStack s = stackAt(mx, my);
        return s.isEmpty() ? null : Screen.getTooltipFromItem(Minecraft.getInstance(), s);
    }
}

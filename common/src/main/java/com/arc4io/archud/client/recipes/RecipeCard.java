package com.arc4io.archud.client.recipes;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Рецепт, который рисует себя сам (категории модов через API JEI, страницы-подсказки).
 * Координаты мыши — относительно левого верхнего угла карточки.
 */
public interface RecipeCard {
    int width();

    int height();

    void render(GuiGraphics g, int mouseX, int mouseY);

    /** Предмет под мышью (для перехода к его рецептам) или пустой. */
    ItemStack stackAt(int mouseX, int mouseY);

    /** Подсказка под мышью или null. */
    List<Component> tooltip(int mouseX, int mouseY);
}

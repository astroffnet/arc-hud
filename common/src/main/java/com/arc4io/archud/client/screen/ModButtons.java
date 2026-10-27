package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.mixin.ImageButtonAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Кнопки других модов для нового инвентаря. Моды добавляют свои кнопки в ванильный инвентарь (через события
 * открытия экрана или миксины). Здесь создаётся невидимый ванильный инвентарь, его «открывают» моды — и все их
 * кнопки переносятся в полосу «Моды» нового инвентаря. Если мода нет — нет и кнопки.
 * Кнопка книги рецептов, кнопки самого Arc HUD и кнопки, которые заменены встроенными слотами (Curios, Aether),
 * не переносятся.
 */
public final class ModButtons {
    /** Кнопка мода и сдвиг, на который её нужно перенести. */
    public static final class Entry {
        public final AbstractWidget widget;
        public int dx, dy;
        public int x, y, w, h;
        public final Component name;

        Entry(AbstractWidget widget, Component name) {
            this.widget = widget;
            this.name = name;
        }

        public void place(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        /** Нарисовать кнопку мода в своей ячейке. */
        public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
            dx = x + (w - widget.getWidth()) / 2 - widget.getX();
            dy = y + (h - widget.getHeight()) / 2 - widget.getY();
            g.pose().pushPose();
            g.pose().translate(dx, dy, 0);
            try {
                widget.render(g, mouseX - dx, mouseY - dy, pt);
            } catch (Throwable t) {
                // кнопка мода, которой нужен её родной экран — не страшно
            }
            g.pose().popPose();
        }

        public boolean inside(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        public boolean click(double mx, double my, int button) {
            try {
                return widget.mouseClicked(mx - dx, my - dy, button);
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("Arc HUD: mod button failed", t);
                return false;
            }
        }
    }

    private ModButtons() {}

    /** Собрать кнопки модов из «невидимого» ванильного инвентаря. skip — какие кнопки не брать (по имени класса). */
    public static List<Entry> harvest(Minecraft mc, int width, int height, Predicate<String> skip) {
        List<Entry> out = new ArrayList<>();
        if (mc.player == null) return out;
        try {
            InventoryScreen hidden = new InventoryScreen(mc.player);
            ModButtonsHarvest.active = true;
            // книга рецептов ванильного инвентаря при init подменяет player.containerMenu — тогда клиент
            // перестаёт принимать содержимое открытого меню (слоты аксессуаров пустые). Возвращаем как было.
            var menuBefore = mc.player.containerMenu;
            try {
                hidden.init(mc, width, height);
            } finally {
                ModButtonsHarvest.active = false;
                mc.player.containerMenu = menuBefore;
            }
            for (GuiEventListener l : hidden.children()) {
                if (!(l instanceof AbstractWidget w) || !w.visible) continue;
                String cls = w.getClass().getName();
                if (cls.startsWith("com.arc4io.archud.")) continue;
                if (skip.test(cls)) continue;
                if (w instanceof ImageButton ib) {
                    var tex = ((ImageButtonAccessor) ib).archud$texture();
                    if (tex != null && tex.getPath().contains("recipe_button")) continue; // книга рецептов
                }
                if (cls.startsWith("net.minecraft.") && !(w instanceof ImageButton)) continue;
                out.add(new Entry(w, name(w, cls)));
            }
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: cannot collect mod inventory buttons", t);
        }
        return out;
    }

    /** Подпись кнопки: её текст или известный мод по пакету. */
    private static Component name(AbstractWidget w, String cls) {
        if (!w.getMessage().getString().isBlank()) return w.getMessage();
        if (cls.startsWith("mc.craig.software.regen")) return Component.translatable("archud.inv.btn.regen");
        if (cls.startsWith("top.theillusivec4.curios")) return Component.literal("Curios");
        if (cls.startsWith("com.aetherteam")) return Component.literal("Aether");
        if (cls.startsWith("com.tihyo.legends")) return Component.literal("Legends");
        return Component.translatable("archud.inv.btn.mod");
    }
}

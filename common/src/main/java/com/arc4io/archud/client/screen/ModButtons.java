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

    /** Кнопка-ярлык: иконка предмета; нажатие закрывает инвентарь и «нажимает» клавишу мода. */
    private static final class KeyShortcut extends AbstractWidget {
        private final net.minecraft.world.item.ItemStack icon;
        private final net.minecraft.client.KeyMapping key;

        KeyShortcut(net.minecraft.world.item.ItemStack icon, net.minecraft.client.KeyMapping key, Component name) {
            super(0, 0, 18, 18, name);
            this.icon = icon;
            this.key = key;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (isHovered()) g.fill(getX(), getY(), getX() + 18, getY() + 18, 0x30FFFFFF);
            g.renderItem(icon, getX() + 1, getY() + 1);
        }

        @Override
        public void onClick(double mx, double my) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) mc.screen.onClose();
            var acc = (com.arc4io.archud.mixin.KeyMappingClickAccessor) key;
            acc.archud$setClicks(acc.archud$clicks() + 1);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput out) {
        }
    }

    /** Меню модов, у которых нет кнопки в инвентаре, но есть клавиша (Legends Core: мастерство, гильдия). */
    public static void addShortcuts(Minecraft mc, List<Entry> out) {
        if (!ArcHud.platform().isModLoaded("legendsmod")) return;
        String[][] list = {
                {"legendsmod.key.mastery", "legendsmod:book_of_legends", "archud.inv.btn.legends_mastery"},
                {"legendsmod.key.guild", "minecraft:white_banner", "archud.inv.btn.legends_guild"}};
        for (String[] e : list) {
            net.minecraft.client.KeyMapping km = null;
            for (net.minecraft.client.KeyMapping k : mc.options.keyMappings) if (k.getName().equals(e[0])) km = k;
            if (km == null) continue;
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(e[1]));
            Component name = Component.translatable(e[2]);
            out.add(new Entry(new KeyShortcut(new net.minecraft.world.item.ItemStack(item), km, name), name));
        }
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

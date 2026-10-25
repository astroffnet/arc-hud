package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.mixin.EnchantmentScreenInvoker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Стол зачарования в стиле Arc. Вместо «галактического» алфавита в каждой строке видно подсказку
 * (та же, что ванилла показывает во всплывающей подсказке), сколько лазурита и уровней нужно и сколько
 * спишется. Строки недоступных вариантов подсвечены красным. Книга анимируется как в ванилле,
 * геометрия кнопок та же — клики и подсказки обрабатывает сам ванильный экран (совместимо с модами).
 */
public class ArcEnchantmentScreen extends EnchantmentScreen {
    private static final int ROW_X = 60, ROW_Y = 14, ROW_W = 108, ROW_H = 19;
    private static final int LAPIS = 0xFF3A66D0;
    private static final int GREEN = 0xFF9CFF5A;
    private static final int RED = 0xFFFF6E6E;

    public ArcEnchantmentScreen(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int magic = 0xFFB388FF;
        int x = leftPos, y = topPos;
        // основная панель
        Draw.panel(g, x, y, imageWidth, imageHeight, 0xF0101419, Draw.alpha(accent, 0x90));
        g.fill(x + 3, y, x + imageWidth - 3, y + 1, Draw.alpha(magic, 0xE0));
        g.fillGradient(x + 1, y + 1, x + imageWidth - 1, y + 50, Draw.alpha(magic, 0x22), 0x00000000);

        // ниша книги
        Draw.panel(g, x + 7, y + 13, 50, 31, 0xC0060A10, Draw.alpha(magic, 0x60));
        g.fillGradient(x + 8, y + 14, x + 56, y + 43, 0x10000000, Draw.alpha(magic, 0x38));
        ((EnchantmentScreenInvoker) this).archud$renderBook(g, x, y, partialTick);

        // разделитель над инвентарём
        g.fill(x + 7, y + 80, x + imageWidth - 7, y + 81, Draw.alpha(accent, 0x40));
        g.fill(x + 6, y + 140, x + imageWidth - 6, y + 160, Draw.alpha(accent, 0x20));

        // ячейки
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) continue;
            int sx = x + slot.x - 1, sy = y + slot.y - 1;
            boolean special = i < 2;
            int border = slot == hoveredSlot ? Draw.alpha(accent, 0xA0)
                    : i == 0 ? Draw.alpha(magic, 0xC0) : i == 1 ? Draw.alpha(LAPIS, 0xC0) : 0x50FFFFFF;
            Draw.panel(g, sx, sy, 18, 18, special ? 0xE0182028 : 0xE00B0F14, border);
            if (i == 1 && !slot.hasItem()) {
                // «призрачный» лазурит в пустой ячейке
                g.renderItem(new ItemStack(Items.LAPIS_LAZULI), sx + 1, sy + 1);
                g.pose().pushPose();
                g.pose().translate(0, 0, 300);
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xB0182028);
                g.pose().popPose();
            }
        }

        // варианты зачарования
        int lapis = menu.getGoldCount();
        boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
        int level = minecraft.player == null ? 0 : minecraft.player.experienceLevel;
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            int rx = x + ROW_X, ry = y + ROW_Y + ROW_H * i;
            int cost = menu.costs[i];
            if (cost <= 0) {
                Draw.panel(g, rx, ry, ROW_W, ROW_H - 1, 0x80080B10, 0x30FFFFFF);
                continue;
            }
            any = true;
            int need = i + 1;
            boolean enoughLapis = creative || lapis >= need;
            boolean enoughLevel = creative || level >= cost;
            boolean can = enoughLapis && enoughLevel;
            boolean hov = can && mouseX >= rx && mouseX < rx + ROW_W && mouseY >= ry && mouseY < ry + ROW_H;
            int bg = !can ? 0xC0140C0E : hov ? Draw.alpha(magic, 0x48) : 0xE0141B24;
            int border = !can ? 0x60FF5252 : hov ? Draw.alpha(magic, 0xFF) : Draw.alpha(magic, 0x70);
            Draw.panel(g, rx, ry, ROW_W, ROW_H - 1, bg, border);
            if (hov) g.fill(rx + 1, ry + 1, rx + 3, ry + ROW_H - 2, Draw.alpha(magic, 0xFF));

            // значок лазурита с количеством
            g.fill(rx + 4, ry + 3, rx + 16, ry + 15, enoughLapis ? LAPIS : 0xFF3A3F48);
            Draw.outline(g, rx + 4, ry + 3, 12, 12, enoughLapis ? 0xFF7FA2FF : 0xFF5A606A);
            String n = Integer.toString(need);
            g.drawString(font, n, rx + 10 - font.width(n) / 2, ry + 5, enoughLapis ? 0xFFFFFFFF : 0xFF8C9AAB, true);

            // уровень справа
            String c = Integer.toString(cost);
            int cw = font.width(c);
            g.drawString(font, c, rx + ROW_W - 5 - cw, ry + 5, enoughLevel ? GREEN : RED, true);

            // подсказка: какое зачарование выпадет
            Enchantment e = Enchantment.byId(menu.enchantClue[i]);
            String name = e == null ? "???" : e.getFullname(menu.levelClue[i]).getString() + "…?";
            float maxW = (ROW_W - 24 - cw - 8) / 0.75F;
            if (font.width(name) > maxW) name = font.plainSubstrByWidth(name, (int) maxW - font.width("…")) + "…";
            int nameColor = !can ? 0xFF8C7A80 : e != null && e.isCurse() ? RED : 0xFFE8DCFF;
            Draw.small(g, font, name, rx + 20, ry + 3, nameColor, 0.75F);
            String sub = Component.translatable("archud.ench.cost", cost, need).getString();
            Draw.small(g, font, sub, rx + 20, ry + 11, can ? Draw.TEXT_DIM : Draw.alpha(RED, 0xC0), 0.55F);
        }
        if (!any) {
            String hint = Component.translatable(menu.getSlot(0).hasItem() ? "archud.ench.cannot" : "archud.ench.place").getString();
            Draw.smallCentered(g, font, hint, x + ROW_X + ROW_W / 2F, y + ROW_Y + ROW_H + 6, Draw.TEXT_DIM, 0.6F);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, Draw.alpha(0xFFB388FF, 0xFF), true);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Draw.TEXT_DIM, true);
        if (minecraft.player != null) {
            String info = Component.translatable("archud.ench.info", menu.getGoldCount(), minecraft.player.experienceLevel).getString();
            Draw.small(g, font, info, imageWidth - 7 - font.width(info) * 0.6F, 6, Draw.TEXT_DIM, 0.6F);
        }
    }
}

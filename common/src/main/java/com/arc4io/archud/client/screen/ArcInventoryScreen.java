package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/**
 * Инвентарь в стиле Arc: стеклянная панель, скруглённые ячейки, подсветка хотбара,
 * панель с моделью игрока и стрелка крафта. Сетка слотов та же, поэтому совместим с модами
 * (Curios, JEI, Waystones и т.п.). Отключается в настройках («Новый инвентарь»).
 */
public class ArcInventoryScreen extends InventoryScreen {
    public ArcInventoryScreen(Player player) {
        super(player);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos;
        // основная панель
        Draw.panel(g, x, y, imageWidth, imageHeight, 0xF0101419, Draw.alpha(accent, 0x90));
        g.fill(x + 3, y, x + imageWidth - 3, y + 1, Draw.alpha(accent, 0xE0));
        g.fillGradient(x + 1, y + 1, x + imageWidth - 1, y + 40, Draw.alpha(accent, 0x18), 0x00000000);
        // окно персонажа
        Draw.panel(g, x + 25, y + 7, 52, 72, 0xC0060A10, Draw.alpha(accent, 0x60));
        g.fillGradient(x + 26, y + 8, x + 76, y + 78, Draw.alpha(accent, 0x30), 0x10000000);
        // разделитель над основным инвентарём
        g.fill(x + 7, y + 80, x + imageWidth - 7, y + 81, Draw.alpha(accent, 0x40));
        // подложка хотбара
        g.fill(x + 6, y + 140, x + imageWidth - 6, y + 160, Draw.alpha(accent, 0x20));
        // ячейки
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            int sx = x + slot.x - 1, sy = y + slot.y - 1;
            boolean result = i == 0;
            int size = result ? 26 : 18;
            int off = result ? 4 : 0;
            Draw.panel(g, sx - off, sy - off, size, size, result ? 0xE0182028 : 0xE00B0F14,
                    result ? Draw.alpha(accent, 0xC0) : 0x50FFFFFF);
            if (!slot.hasItem() && !result) g.fill(sx + 1, sy + 1, sx + 17, sy + 2, 0x10FFFFFF);
        }
        // стрелка крафта
        int ax = x + 135, ay = y + 33;
        g.fill(ax, ay + 3, ax + 10, ay + 5, Draw.alpha(accent, 0xC0));
        for (int i = 0; i < 4; i++) g.fill(ax + 10 + i, ay + i, ax + 11 + i, ay + 8 - i, Draw.alpha(accent, 0xC0));
        // персонаж
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + 51, y + 75, 30,
                (float) (x + 51) - mouseX, (float) (y + 75 - 50) - mouseY, minecraft.player);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, Draw.alpha(Theme.accent(), 0xFF), true);
    }
}

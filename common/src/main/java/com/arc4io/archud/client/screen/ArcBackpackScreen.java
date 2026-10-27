package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.item.BackpackItem;
import com.arc4io.archud.item.BackpackTier;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Экран рюкзака: «кожаная/железная/алмазная» шапка с иконкой и заполненностью, ячейки в цвет рюкзака,
 * кнопки «Сортировать» и «Сложить похожее» (сервер с Arc HUD). Меню — ванильный сундук, поэтому
 * моды на сундуки (сортировщики, JEI и т. п.) продолжают работать.
 */
public class ArcBackpackScreen extends ContainerScreen {
    private final ItemStack icon;
    private final BackpackTier tier;
    private final int rows;

    public ArcBackpackScreen(ChestMenu menu, Inventory inv, Component title, ItemStack backpack) {
        super(menu, inv, title);
        this.icon = backpack.copy();
        this.tier = ((BackpackItem) backpack.getItem()).tier();
        this.rows = menu.getRowCount();
    }

    /** Открытый сундук — это рюкзак игрока? (название совпадает с рюкзаком в инвентаре и размер — с его уровнем). */
    public static ItemStack backpackFor(ContainerScreen screen, LocalPlayer player) {
        if (player == null) return null;
        String title = screen.getTitle().getString();
        int rows = screen.getMenu().getRowCount();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BackpackItem b && b.tier().rows() == rows && s.getHoverName().getString().equals(title)) return s;
        }
        return null;
    }

    private int tierColor() {
        return switch (tier) {
            case LEATHER -> 0xFFC08552;
            case IRON -> 0xFFC9D3DD;
            case DIAMOND -> 0xFF4FE3D6;
        };
    }

    @Override
    protected void init() {
        super.init();
        int bx = leftPos + imageWidth + 3;
        ArcButton sort = addRenderableWidget(new ArcButton(bx, topPos + 18, 18, 18, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.SORT_INVENTORY)).icon(Icons.SORT));
        sort.setTooltip(Tooltip.create(Component.translatable("archud.backpack.sort")));
        ArcButton dep = addRenderableWidget(new ArcButton(bx, topPos + 39, 18, 18, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.BACKPACK_DEPOSIT)).icon(Icons.ARROW));
        dep.setTooltip(Tooltip.create(Component.translatable("archud.backpack.deposit")));
        boolean server = com.arc4io.archud.client.ArcClientNet.modServer() || minecraft.hasSingleplayerServer();
        sort.visible = dep.visible = server && bx + 18 <= width;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int tc = tierColor();
        int x = leftPos, y = topPos;
        Draw.panel(g, x, y, imageWidth, imageHeight, 0xF00F1318, Draw.alpha(tc, 0x90));
        // шапка: «клапан» рюкзака
        g.fillGradient(x + 1, y + 1, x + imageWidth - 1, y + 16, Draw.alpha(tc, 0x40), Draw.alpha(tc, 0x08));
        g.fill(x + 3, y, x + imageWidth - 3, y + 1, Draw.alpha(tc, 0xF0));
        // строчка «шва» под шапкой
        for (int i = x + 6; i < x + imageWidth - 6; i += 4) g.fill(i, y + 16, i + 2, y + 17, Draw.alpha(tc, 0x50));

        // заполненность
        int used = 0, total = rows * 9;
        for (int i = 0; i < total; i++) if (menu.getSlot(i).hasItem()) used++;
        int barW = 44, bx = x + imageWidth - 8 - barW, by = y + 5;
        g.fill(bx, by, bx + barW, by + 3, 0x50000000);
        float f = used / (float) total;
        int fc = f > 0.9F ? 0xFFFF6E6E : f > 0.7F ? 0xFFFFC857 : tc;
        g.fill(bx, by, bx + Math.round(barW * f), by + 3, Draw.alpha(fc, 0xFF));

        // ячейки
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            boolean pack = i < total;
            int border = s == hoveredSlot ? Draw.alpha(accent, 0xC0) : pack ? Draw.alpha(tc, 0x55) : 0x40FFFFFF;
            Draw.panel(g, x + s.x - 1, y + s.y - 1, 18, 18, pack ? 0xE0151B22 : 0xE00B0F14, border);
        }
        // разделитель рюкзак / инвентарь
        int sepY = y + 18 + rows * 18 + 2;
        g.fill(x + 7, sepY, x + imageWidth - 7, sepY + 1, Draw.alpha(tc, 0x40));
        // разделитель хотбара
        int hotY = y + imageHeight - 25;
        g.fill(x + 7, hotY, x + imageWidth - 7, hotY + 1, 0x30FFFFFF);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int tc = tierColor();
        g.pose().pushPose();
        g.pose().translate(5, 2.5F, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
        // заголовок не заходит на полосу заполненности справа (44 px + отступы)
        int maxName = imageWidth - 19 - 8 - 44 - 5;
        String name = title.getString();
        if (font.width(name) > maxName) name = font.plainSubstrByWidth(name, maxName - font.width("…")) + "…";
        g.drawString(font, name, 19, 5, 0xFFEAF2FA, true);
        int used = 0, total = rows * 9;
        for (int i = 0; i < total; i++) if (menu.getSlot(i).hasItem()) used++;
        String cnt = used + " / " + total;
        Draw.small(g, font, cnt, imageWidth - 8 - font.width(cnt) * 0.6F, 10F, Draw.alpha(tc, 0xFF), 0.6F);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF9AA7B4, false);
    }
}

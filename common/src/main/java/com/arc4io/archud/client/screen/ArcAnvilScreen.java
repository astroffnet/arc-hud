package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.mixin.AnvilScreenAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Новая наковальня. Слева — поле имени, «предмет + материал → результат» и инвентарь; справа — карточка
 * результата: новое имя, все чары (новые и усиленные подсвечены), прочность до/после, штраф за прошлые
 * работы и стоимость (хватает ли уровней). Родитель — ванильный экран, так что моды на наковальню работают.
 */
public class ArcAnvilScreen extends AnvilScreen {
    private static final int RX = 180, RW = 132;
    private static final int STEEL = 0xFF9FB3C8;
    private static final int GREEN = 0xFF8CFF7A;
    private static final int RED = 0xFFFF6E6E;
    private static final int GOLD = 0xFFFFD36E;

    public ArcAnvilScreen(AnvilMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = RX + RW + 4;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos;
        Draw.panel(g, x, y, 176, imageHeight, 0xF0101419, Draw.alpha(STEEL, 0x80));
        g.fill(x + 3, y, x + 173, y + 1, Draw.alpha(STEEL, 0xE0));
        g.fillGradient(x + 1, y + 1, x + 175, y + 44, Draw.alpha(STEEL, 0x1C), 0);
        Draw.panel(g, x + RX - 2, y, RW + 6, imageHeight, 0xF00E1117, Draw.alpha(STEEL, 0x70));
        g.fill(x + RX + 1, y, x + RX + RW + 1, y + 1, Draw.alpha(STEEL, 0xE0));

        // поле имени
        EditBox name = ((AnvilScreenAccessor) this).archud$name();
        boolean canName = menu.getSlot(0).hasItem();
        int nx = x + 58, ny = y + 20;
        Draw.panel(g, nx, ny, 112, 16, canName ? 0xE0070A0E : 0x80070A0E,
                name != null && name.isFocused() ? Draw.alpha(accent, 0xE0) : Draw.alpha(STEEL, canName ? 0x70 : 0x30));
        if (name != null && name.getValue().isEmpty() && !name.isFocused() && canName) {
            Draw.small(g, font, Component.translatable("archud.anvil.rename").getString(), nx + 4, ny + 5, Draw.TEXT_DIM, 0.7F);
        }

        // слоты
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            int sx = x + s.x - 1, sy = y + s.y - 1;
            int border = s == hoveredSlot ? Draw.alpha(accent, 0xC0) : i < 2 ? Draw.alpha(STEEL, 0x90) : i == 2 ? Draw.alpha(resultColor(), 0xC0) : 0x40FFFFFF;
            if (i == 2) {
                Draw.panel(g, sx - 3, sy - 3, 24, 24, 0xE0141A21, border);
            } else Draw.panel(g, sx, sy, 18, 18, i < 2 ? 0xE0182028 : 0xE00B0F14, border);
        }
        // «+» и стрелка
        int cy = y + 47 + 8;
        int px = x + 52 + 8;
        g.fill(px - 4, cy - 1, px + 5, cy + 1, Draw.alpha(STEEL, 0xB0));
        g.fill(px - 1, cy - 4, px + 1, cy + 5, Draw.alpha(STEEL, 0xB0));
        boolean blocked = (menu.getSlot(0).hasItem() || menu.getSlot(1).hasItem()) && !menu.getSlot(2).hasItem();
        int ac = blocked ? RED : menu.getSlot(2).hasItem() ? resultColor() : 0x60FFFFFF;
        int ax1 = x + 100, ax2 = x + 126;
        g.fill(ax1, cy - 1, ax2, cy + 1, Draw.alpha(ac, 0xD0));
        for (int k = 0; k < 4; k++) g.fill(ax2 - 4 + k, cy - 4 + k, ax2 - 3 + k, cy + 5 - k, Draw.alpha(ac, 0xD0));
        if (blocked) { // крестик поверх стрелки
            int mx = (ax1 + ax2) / 2;
            for (int k = -3; k <= 3; k++) {
                g.fill(mx + k, cy + k - 6, mx + k + 1, cy + k - 5, RED);
                g.fill(mx + k, cy - k - 6, mx + k + 1, cy - k - 5, RED);
            }
        }
        g.fill(x + 7, y + 140, x + 169, y + 141, 0x30FFFFFF);

        renderResultCard(g, x + RX, y);
    }

    private int cost() {
        return menu.getCost();
    }

    private boolean creative() {
        return minecraft.player != null && minecraft.player.getAbilities().instabuild;
    }

    private boolean tooExpensive() {
        return cost() >= 40 && !creative();
    }

    private boolean affordable() {
        return creative() || minecraft.player != null && minecraft.player.experienceLevel >= cost();
    }

    private int resultColor() {
        if (!menu.getSlot(2).hasItem()) return STEEL;
        return tooExpensive() || !affordable() ? RED : GREEN;
    }

    private void renderResultCard(GuiGraphics g, int cx, int y) {
        g.drawString(font, Component.translatable("archud.anvil.result"), cx + 4, y + 6, 0xFFEAF2FA, false);
        ItemStack in = menu.getSlot(0).getItem();
        ItemStack out = menu.getSlot(2).getItem();
        int ty = y + 20;
        if (in.isEmpty()) {
            Draw.small(g, font, Component.translatable("archud.anvil.place").getString(), cx + 4, ty, Draw.TEXT_DIM, 0.65F);
            Draw.small(g, font, Component.translatable("archud.anvil.place2").getString(), cx + 4, ty + 8, Draw.TEXT_DIM, 0.65F);
            return;
        }
        ItemStack shown = out.isEmpty() ? in : out;
        // имя
        Draw.panel(g, cx + 2, ty - 3, RW - 2, 22, 0xC0080B10, Draw.alpha(resultColor(), 0x50));
        g.renderItem(shown, cx + 5, ty);
        String nm = shown.getHoverName().getString();
        float max = (RW - 30) / 0.75F;
        if (font.width(nm) > max) nm = font.plainSubstrByWidth(nm, (int) max - font.width("…")) + "…";
        Draw.small(g, font, nm, cx + 24, ty + 1, out.isEmpty() ? Draw.TEXT_DIM : 0xFFFFFFFF, 0.75F);
        if (!out.isEmpty() && !out.getHoverName().getString().equals(in.getHoverName().getString())) {
            Draw.small(g, font, Component.translatable("archud.anvil.renamed").getString(), cx + 24, ty + 9, GOLD, 0.55F);
        } else if (out.isEmpty() && menu.getSlot(1).hasItem()) {
            Draw.small(g, font, Component.translatable("archud.anvil.incompatible").getString(), cx + 24, ty + 9, RED, 0.55F);
        }
        ty += 24;

        // чары
        Map<Enchantment, Integer> before = EnchantmentHelper.getEnchantments(in);
        Map<Enchantment, Integer> after = EnchantmentHelper.getEnchantments(shown);
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : after.entrySet()) {
            Integer old = before.get(e.getKey());
            Component c = e.getKey().getFullname(e.getValue());
            String mark = old == null ? "+ " : old < e.getValue() ? "↑ " : "  ";
            lines.add(Component.literal(mark + c.getString()));
            colors.add(old == null ? GREEN : old < e.getValue() ? GOLD : e.getKey().isCurse() ? RED : 0xFFB8C4D0);
        }
        if (!lines.isEmpty()) {
            Draw.small(g, font, Component.translatable("archud.anvil.enchants").getString(), cx + 4, ty, STEEL, 0.6F);
            ty += 7;
            int maxRows = 6;
            for (int i = 0; i < Math.min(maxRows, lines.size()); i++) {
                String s = lines.get(i).getString();
                float lim = (RW - 8) / 0.65F;
                if (font.width(s) > lim) s = font.plainSubstrByWidth(s, (int) lim - font.width("…")) + "…";
                Draw.small(g, font, s, cx + 4, ty, colors.get(i), 0.65F);
                ty += 7;
            }
            if (lines.size() > maxRows) {
                Draw.small(g, font, "+" + (lines.size() - maxRows), cx + 4, ty, Draw.TEXT_DIM, 0.6F);
                ty += 7;
            }
            ty += 2;
        }

        // прочность
        if (shown.isDamageableItem()) {
            int maxD = shown.getMaxDamage();
            int was = maxD - in.getDamageValue();
            int now = maxD - shown.getDamageValue();
            Draw.small(g, font, Component.translatable("archud.anvil.durability").getString(), cx + 4, ty, STEEL, 0.6F);
            ty += 7;
            int bw = RW - 8;
            g.fill(cx + 4, ty, cx + 4 + bw, ty + 3, 0x50000000);
            g.fill(cx + 4, ty, cx + 4 + Math.round(bw * now / (float) maxD), ty + 3, Draw.alpha(now > was ? GREEN : STEEL, 0xC0));
            g.fill(cx + 4, ty, cx + 4 + Math.round(bw * was / (float) maxD), ty + 3, Draw.alpha(0xFFFFFF, 0x60));
            ty += 5;
            String d = was + " → " + now + " / " + maxD;
            Draw.small(g, font, d, cx + 4, ty, now > was ? GREEN : Draw.TEXT_DIM, 0.6F);
            ty += 9;
        }

        // штраф за прошлые работы
        int penalty = in.getBaseRepairCost();
        if (penalty > 0 && ty < y + imageHeight - 34) {
            Draw.small(g, font, Component.translatable("archud.anvil.penalty", penalty).getString(), cx + 4, ty, Draw.TEXT_DIM, 0.6F);
        }

        // стоимость — внизу карточки
        if (cost() > 0) {
            int by = y + imageHeight - 24;
            int col = tooExpensive() ? RED : affordable() ? GREEN : RED;
            Draw.panel(g, cx + 2, by, RW - 2, 18, 0xE00A0E13, Draw.alpha(col, 0xB0));
            String txt = tooExpensive() ? Component.translatable("container.repair.expensive").getString()
                    : Component.translatable("container.repair.cost", cost()).getString();
            g.drawCenteredString(font, txt, cx + 2 + (RW - 2) / 2, by + 5, col);
            if (!tooExpensive() && !creative() && minecraft.player != null) {
                String have = Component.translatable("archud.anvil.have", minecraft.player.experienceLevel).getString();
                Draw.small(g, font, have, cx + 4, by - 7, Draw.TEXT_DIM, 0.55F);
            }
        }
    }

    @Override
    protected void renderErrorIcon(GuiGraphics g, int x, int y) {
        // крестик рисуется в renderBg
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 7, 0xFFEAF2FA, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF9AA7B4, false);
    }
}

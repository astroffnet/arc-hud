package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Переработка инвентаря: кнопки «Сортировать» и «Рюкзак» рядом с окном,
 * панель характеристик игрока слева. Доп. слоты (спина, пояс, амулеты) дают Curios / Trinkets.
 */
public final class InventoryExtras {
    private static ArcButton sortBtn;
    private static ArcButton packBtn;

    private InventoryExtras() {}

    public static void onInit(Screen screen, Consumer<AbstractWidget> add) {
        sortBtn = null;
        packBtn = null;
        if (!(screen instanceof InventoryScreen) || !ArcConfig.get().inventoryButtons) return;
        sortBtn = new ArcButton(0, 0, 18, 18, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.SORT_INVENTORY)).icon(Icons.SORT);
        sortBtn.setTooltip(Tooltip.create(Component.translatable("archud.inv.sort")));
        packBtn = new ArcButton(0, 0, 18, 18, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.OPEN_BACKPACK)).icon(Icons.BACKPACK);
        packBtn.setTooltip(Tooltip.create(Component.translatable("archud.inv.backpack")));
        position((InventoryScreen) screen);
        add.accept(sortBtn);
        add.accept(packBtn);
    }

    private static int leftPos(InventoryScreen s) {
        if (s.getRecipeBookComponent().isVisible()) return s.getRecipeBookComponent().updateScreenPosition(s.width, 176);
        return (s.width - 176) / 2;
    }

    private static void position(InventoryScreen s) {
        int left = leftPos(s);
        int top = (s.height - 166) / 2;
        if (sortBtn != null) sortBtn.setPosition(left + 176 + 2, top + 4);
        if (packBtn != null) packBtn.setPosition(left + 176 + 2, top + 24);
    }

    public static void onRender(Screen screen, GuiGraphics g) {
        if (!(screen instanceof InventoryScreen inv)) return;
        position(inv);
        if (!ArcConfig.get().inventoryStats || inv.getRecipeBookComponent().isVisible()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        int left = leftPos(inv);
        int top = (inv.height - 166) / 2;
        int pw = 112;
        int x = left - pw - 4;
        if (x < 2) return;
        int accent = Theme.accent();

        String[][] rows = {
                {Component.translatable("archud.stat.health").getString(), HudRenderer.fmt(p.getHealth()) + " / " + HudRenderer.fmt(p.getMaxHealth())},
                {Component.translatable("archud.stat.armor").getString(), p.getArmorValue() + " (+" + HudRenderer.fmt(attr(p, Attributes.ARMOR_TOUGHNESS)) + ")"},
                {Component.translatable("archud.stat.damage").getString(), HudRenderer.fmt(attr(p, Attributes.ATTACK_DAMAGE))},
                {Component.translatable("archud.stat.attack_speed").getString(), String.format("%.2f", attr(p, Attributes.ATTACK_SPEED))},
                {Component.translatable("archud.stat.speed").getString(), String.format("%.2f", attr(p, Attributes.MOVEMENT_SPEED) * 43.17F)},
                {Component.translatable("archud.stat.luck").getString(), HudRenderer.fmt(attr(p, Attributes.LUCK))},
                {Component.translatable("archud.stat.knockback").getString(), Math.round(attr(p, Attributes.KNOCKBACK_RESISTANCE) * 100) + "%"},
                {Component.translatable("archud.stat.level").getString(), Integer.toString(p.experienceLevel)},
                {Component.translatable("archud.stat.food").getString(), p.getFoodData().getFoodLevel() + " / " + String.format("%.1f", p.getFoodData().getSaturationLevel())},
        };
        int ph = 18 + rows.length * 11 + 16;
        Draw.accentPanel(g, x, top, pw, ph, accent);
        g.drawString(mc.font, Component.translatable("archud.stat.title"), x + 6, top + 5, Draw.alpha(accent, 0xFF), true);
        int y = top + 18;
        for (String[] r : rows) {
            Draw.small(g, mc.font, r[0], x + 6, y + 1, Draw.TEXT_DIM, 0.75F);
            float vw = mc.font.width(r[1]) * 0.75F;
            Draw.small(g, mc.font, r[1], x + pw - 6 - vw, y + 1, Draw.TEXT, 0.75F);
            y += 11;
        }
        ItemStack back = ItemStack.EMPTY;
        try {
            back = ArcHud.platform().findEquippedBackpack(p);
        } catch (Exception ignored) {
        }
        Draw.icon(g, Icons.BACKPACK, x + 5, y + 2);
        Component bp = back.isEmpty() ? Component.translatable("archud.stat.no_backpack") : back.getHoverName();
        Draw.small(g, mc.font, bp.getString(), x + 17, y + 3, back.isEmpty() ? 0xFF6A7380 : 0xFFFFE082, 0.75F);
    }

    private static float attr(LocalPlayer p, Attribute a) {
        return p.getAttribute(a) == null ? 0F : (float) p.getAttributeValue(a);
    }
}

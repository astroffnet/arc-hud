package com.arc4io.archud.client.fx;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Карточка предмета во время осмотра (как название скина в CS2): имя, чары, прочность. */
public final class InspectCard {
    private InspectCard() {}

    public static void render(GuiGraphics g, Font font, int w, int h, float partialTick) {
        if (!WeaponInspect.active() || !ArcConfig.get().inspectCard) return;
        ItemStack s = WeaponInspect.item();
        if (s.isEmpty()) return;
        float t = WeaponInspect.progress(partialTick);
        float a = Math.min(1F, Math.min(t / 0.12F, (1F - t) / 0.12F));
        if (a <= 0.02F) return;
        int alpha = (int) (0xFF * a);
        int accent = Theme.accent();

        Component name = s.getHoverName();
        int nameColor = s.getRarity().color.getColor() == null ? 0xFFFFFF : s.getRarity().color.getColor();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(s).entrySet()) {
            lines.add(e.getKey().getFullname(e.getValue()).getString());
        }
        int width = font.width(name) + 40;
        for (String l : lines) width = Math.max(width, Math.round(font.width(l) * 0.7F) + 40);
        width = Math.max(140, Math.min(w / 2, width));
        int height = 30 + Math.min(lines.size(), 5) * 8 + (s.isDamageableItem() ? 10 : 0);
        int x = 12 + Math.round((1F - a) * -30), y = h - 70 - height;

        Draw.panel(g, x, y, width, height, Draw.alpha(0xE00B0F14, (int) (0xE0 * a)), Draw.alpha(accent, alpha));
        g.fill(x + 1, y + 1, x + 3, y + height - 1, Draw.alpha(0xFF000000 | nameColor, alpha));
        g.renderItem(s, x + 7, y + 6);
        g.drawString(font, name, x + 28, y + 6, Draw.alpha(0xFF000000 | nameColor, alpha), true);
        String sub = Component.translatable("archud.inspect.title").getString();
        Draw.small(g, font, sub, x + 28, y + 17, Draw.alpha(Draw.TEXT_DIM, alpha), 0.6F);
        int ly = y + 26;
        for (int i = 0; i < Math.min(lines.size(), 5); i++) {
            Draw.small(g, font, "✦ " + lines.get(i), x + 8, ly, Draw.alpha(0xFFB388FF, alpha), 0.7F);
            ly += 8;
        }
        if (lines.size() > 5) Draw.small(g, font, "+" + (lines.size() - 5), x + width - 20, ly - 8, Draw.alpha(Draw.TEXT_DIM, alpha), 0.6F);
        if (s.isDamageableItem()) {
            float dur = 1F - s.getDamageValue() / (float) s.getMaxDamage();
            int bw = width - 16;
            g.fill(x + 8, ly + 3, x + 8 + bw, ly + 5, Draw.alpha(0xFF20262E, alpha));
            int col = dur > 0.5F ? 0xFF9CFF5A : dur > 0.2F ? 0xFFFFD54F : 0xFFFF5252;
            g.fill(x + 8, ly + 3, x + 8 + Math.round(bw * dur), ly + 5, Draw.alpha(col, alpha));
            String pct = Math.round(dur * 100) + "%";
            Draw.small(g, font, pct, x + width - 8 - font.width(pct) * 0.6F, ly - 4, Draw.alpha(Draw.TEXT_DIM, alpha), 0.6F);
        }
    }
}

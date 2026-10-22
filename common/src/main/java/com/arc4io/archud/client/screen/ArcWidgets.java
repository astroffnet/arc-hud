package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Стиль Arc для всех кнопок и слайдеров игры (ванильных и модовых), через миксины.
 */
public final class ArcWidgets {
    private static final Map<AbstractWidget, float[]> HOVER = new WeakHashMap<>();

    private ArcWidgets() {}

    public static boolean enabled() {
        return ArcConfig.get().arcWidgets;
    }

    private static float hover(AbstractWidget w) {
        float[] h = HOVER.computeIfAbsent(w, k -> new float[1]);
        boolean on = w.active && w.isHoveredOrFocused();
        h[0] += ((on ? 1F : 0F) - h[0]) * 0.25F;
        return h[0];
    }

    /** Стеклянная кнопка: подсветка рамки и полоса снизу при наведении. */
    public static void drawButton(GuiGraphics g, AbstractWidget w, Component message, boolean danger) {
        Font font = Minecraft.getInstance().font;
        float hv = hover(w);
        int accent = danger ? 0xFFFF5252 : Theme.accent();
        int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
        int bg = Draw.mix(Draw.PANEL, Draw.PANEL_LIGHT, hv);
        int border = !w.active ? 0x40FFFFFF : Draw.mix(Draw.alpha(accent, 0x60), Draw.alpha(accent, 0xFF), hv);
        Draw.panel(g, x, y, bw, bh, bg, border);
        if (hv > 0.01F) g.fill(x + 2, y + bh - 2, x + 2 + Math.round((bw - 4) * hv), y + bh - 1, Draw.alpha(accent, 0xFF));
        int color = !w.active ? 0xFF6A7380 : Draw.mix(0xFFDCE6F0, 0xFFFFFFFF, hv);
        drawCenteredClipped(g, font, message, x, y, bw, bh, color);
    }

    /** Слайдер: дорожка с заливкой и ползунок. value 0..1. */
    public static void drawSlider(GuiGraphics g, AbstractWidget w, Component message, double value) {
        Font font = Minecraft.getInstance().font;
        float hv = hover(w);
        int accent = Theme.accent();
        int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
        Draw.panel(g, x, y, bw, bh, Draw.mix(Draw.PANEL, Draw.PANEL_LIGHT, hv * 0.6F), Draw.alpha(accent, 0x60 + (int) (0x9F * hv)));
        int fill = (int) ((bw - 4) * Math.max(0, Math.min(1, value)));
        g.fillGradient(x + 2, y + 2, x + 2 + fill, y + bh - 2, Draw.alpha(accent, 0x55), Draw.alpha(accent, 0x22));
        int kx = x + 2 + fill - 2;
        g.fill(kx, y + 1, kx + 4, y + bh - 1, Draw.alpha(accent, 0xFF));
        g.fill(kx + 1, y + 2, kx + 3, y + bh - 2, 0xFFFFFFFF);
        drawCenteredClipped(g, font, message, x, y, bw, bh, w.active ? 0xFFFFFFFF : 0xFF6A7380);
    }

    private static void drawCenteredClipped(GuiGraphics g, Font font, Component message, int x, int y, int bw, int bh, int color) {
        String s = message.getString();
        int max = bw - 8;
        if (font.width(message) <= max) {
            g.drawString(font, message, x + (bw - font.width(message)) / 2, y + (bh - 8) / 2, color, true);
        } else {
            String cut = font.plainSubstrByWidth(s, max - 6) + "…";
            g.drawString(font, cut, x + (bw - font.width(cut)) / 2, y + (bh - 8) / 2, color, true);
        }
    }
}

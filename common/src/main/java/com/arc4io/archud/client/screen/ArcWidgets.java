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

    // ------------------------------------------------------------------ фон меню вместо «земли»
    /** Тёмный фон Arc с сеткой и акцентным свечением — вместо земляного фона во всех меню. */
    public static void drawBackground(GuiGraphics g, int w, int h) {
        int accent = Theme.accent();
        g.fillGradient(0, 0, w, h, 0xFF0D1218, 0xFF05070A);
        // свечение сверху слева и снизу справа
        for (int i = 0; i < 6; i++) {
            int r = 40 + i * 30;
            int a = 0x0E - i * 2;
            if (a <= 0) break;
            g.fillGradient(0, 0, Math.min(w, r * 3), Math.min(h, r), Draw.alpha(accent, a), Draw.alpha(accent, 0));
        }
        g.fillGradient(0, h - 90, w, h, 0x00000000, Draw.alpha(accent, 0x10));
        // сетка
        for (int x = 0; x < w; x += 24) g.fill(x, 0, x + 1, h, 0x08FFFFFF);
        for (int y = 0; y < h; y += 24) g.fill(0, y, w, y + 1, 0x08FFFFFF);
    }

    // ------------------------------------------------------------------ поле ввода
    /** Рамка поля ввода (вместо белой/серой рамки и чёрной заливки). */
    public static void drawEditBox(GuiGraphics g, AbstractWidget w, int x1, int y1, int x2, int y2) {
        int accent = Theme.accent();
        boolean focus = w.isFocused();
        float hv = hover(w);
        int border = focus ? Draw.alpha(accent, 0xFF) : Draw.mix(0x50FFFFFF, Draw.alpha(accent, 0xA0), hv);
        Draw.panel(g, x1, y1, x2 - x1, y2 - y1, focus ? 0xE0101820 : 0xD00A0E13, border);
        if (focus) g.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, Draw.alpha(accent, 0xFF));
    }

    // ------------------------------------------------------------------ флажок
    public static void drawCheckbox(GuiGraphics g, AbstractWidget w, Component message, boolean selected, boolean label) {
        Font font = Minecraft.getInstance().font;
        float hv = hover(w);
        int accent = Theme.accent();
        int x = w.getX(), y = w.getY(), size = Math.min(20, w.getHeight());
        int bx = x + 2, by = y + (w.getHeight() - 16) / 2;
        Draw.panel(g, bx, by, 16, 16, Draw.mix(Draw.PANEL, Draw.PANEL_LIGHT, hv),
                selected ? Draw.alpha(accent, 0xFF) : Draw.mix(0x60FFFFFF, Draw.alpha(accent, 0xC0), hv));
        if (selected) {
            g.fill(bx + 3, by + 3, bx + 13, by + 13, Draw.alpha(accent, 0xFF));
            // галочка
            int c = 0xFF0B0F14;
            g.fill(bx + 5, by + 8, bx + 7, by + 10, c);
            g.fill(bx + 7, by + 9, bx + 9, by + 11, c);
            g.fill(bx + 9, by + 5, bx + 11, by + 9, c);
            g.fill(bx + 8, by + 8, bx + 10, by + 10, c);
        }
        if (label) {
            int color = !w.active ? 0xFF6A7380 : Draw.mix(0xFFDCE6F0, 0xFFFFFFFF, hv);
            g.drawString(font, message, x + 24, y + (w.getHeight() - 8) / 2, color, true);
        }
    }

    // ------------------------------------------------------------------ вкладки (создание мира и т.п.)
    public static void drawTab(GuiGraphics g, AbstractWidget w, Component message, boolean selected) {
        Font font = Minecraft.getInstance().font;
        float hv = hover(w);
        int accent = Theme.accent();
        int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
        if (selected) {
            g.fillGradient(x, y, x + bw, y + bh, Draw.alpha(accent, 0x10), Draw.alpha(accent, 0x45));
            g.fill(x, y + bh - 2, x + bw, y + bh, Draw.alpha(accent, 0xFF));
        } else if (hv > 0.01F) {
            g.fill(x, y, x + bw, y + bh, Draw.alpha(0xFFFFFFFF, (int) (0x14 * hv)));
            g.fill(x + bw / 2 - Math.round(bw / 2F * hv), y + bh - 1, x + bw / 2 + Math.round(bw / 2F * hv), y + bh, Draw.alpha(accent, 0xA0));
        }
        int color = selected ? 0xFFFFFFFF : !w.active ? 0xFF6A7380 : Draw.mix(0xFFAEB9C6, 0xFFFFFFFF, hv);
        drawCenteredClipped(g, font, message, x, y, bw, bh, color);
    }

    public static void drawTabBar(GuiGraphics g, int width) {
        g.fill(0, 0, width, 24, 0xE00A0E13);
        g.fill(0, 23, width, 24, Draw.alpha(Theme.accent(), 0x60));
    }

    // ------------------------------------------------------------------ при открытии экрана
    /** Убирает «земляной» фон у списков (миры, серверы, правила игры, паки…), чтобы был виден фон Arc. */
    public static void onScreenInit(net.minecraft.client.gui.screens.Screen screen) {
        if (!enabled()) return;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> list) {
                list.setRenderBackground(false);
                list.setRenderTopAndBottom(false);
            }
        }
    }
}

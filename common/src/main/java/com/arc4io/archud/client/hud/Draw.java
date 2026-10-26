package com.arc4io.archud.client.hud;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.gui.Font;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Примитивы отрисовки в стиле Arc: стеклянные панели, глянцевые полосы, иконки.
 */
public final class Draw {
    public static final ResourceLocation ICONS = ArcHud.id("textures/gui/icons.png");
    public static final int ICON = 9;

    public static final int PANEL = 0xB8101419;
    public static final int PANEL_LIGHT = 0xCC1A2029;
    public static final int TEXT = 0xFFEAF2FA;
    public static final int TEXT_DIM = 0xFF8C9AAB;

    private Draw() {}

    // ---------- цвет ----------
    public static int alpha(int color, int a) {
        return (Mth.clamp(a, 0, 255) << 24) | (color & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0, 1);
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    public static int brighten(int c, float amount) {
        return mix(c, (c & 0xFF000000) | 0xFFFFFF, amount);
    }

    public static int darken(int c, float amount) {
        return mix(c, c & 0xFF000000, amount);
    }

    public static int scaleAlpha(int c, float f) {
        int a = (int) (((c >>> 24) & 255) * Mth.clamp(f, 0, 1));
        return alpha(c, a);
    }

    // ---------- панели ----------
    /** Панель со скруглёнными (срезанными) углами и тонкой рамкой. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int bg, int border) {
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        g.fill(x + 1, y, x + w - 1, y + 1, border);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
        g.fill(x, y + 1, x + 1, y + h - 1, border);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
    }

    /** Панель с подсветкой сверху (accent-линия). */
    public static void accentPanel(GuiGraphics g, int x, int y, int w, int h, int accent) {
        panel(g, x, y, w, h, PANEL, alpha(accent, 0x55));
        g.fill(x + 2, y, x + w - 2, y + 1, alpha(accent, 0xCC));
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Горизонтальный градиент (рисуется полосками). */
    public static void hGradient(GuiGraphics g, int x1, int y1, int x2, int y2, int left, int right) {
        int w = x2 - x1;
        if (w <= 0) return;
        int step = Math.max(1, w / 32);
        for (int x = x1; x < x2; x += step) {
            float t = (x - x1) / (float) w;
            g.fill(x, y1, Math.min(x + step, x2), y2, mix(left, right, t));
        }
    }

    /**
     * Глянцевая полоса: фон, заливка вертикальным градиентом, блик сверху и бегущий отблеск.
     */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int top, int bottom) {
        g.fill(x, y, x + w, y + h, 0xC0050608);
        barFill(g, x, y, w, h, frac, top, bottom, false);
    }

    /** Только заливка (без фона). fromRight — заполнение справа налево. Возвращает [x1, x2] заливки. */
    public static int[] barFill(GuiGraphics g, int x, int y, int w, int h, float frac, int top, int bottom, boolean fromRight) {
        int fw = Math.round(w * Mth.clamp(frac, 0, 1));
        int x1 = fromRight ? x + w - fw : x;
        int x2 = fromRight ? x + w : x + fw;
        if (fw <= 0) return new int[]{x1, x1};
        g.fillGradient(x1, y, x2, y + h, top, bottom);
        g.fill(x1, y, x2, y + 1, alpha(brighten(top, 0.5F), 0xB0));
        // бегущий отблеск
        long t = System.currentTimeMillis() % 2600L;
        int sx = x + (int) ((w + 16) * (t / 2600F)) - 8;
        int s1 = Math.max(x1, sx), s2 = Math.min(x2, sx + 6);
        if (s2 > s1) g.fill(s1, y, s2, y + h, 0x30FFFFFF);
        return new int[]{x1, x2};
    }

    /** Сегментные засечки на полосе (каждые n единиц). */
    public static void ticks(GuiGraphics g, int x, int y, int w, int h, float max, float every) {
        if (max <= every || max > 200) return;
        for (float v = every; v < max; v += every) {
            int tx = x + Math.round(w * (v / max));
            g.fill(tx, y + 1, tx + 1, y + h, 0x40000000);
        }
    }

    // ---------- иконки и текст ----------
    public static void icon(GuiGraphics g, int index, int x, int y) {
        g.blit(ICONS, x, y, (index % 16) * 16, (index / 16) * 16, ICON, ICON, 256, 256);
    }

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, true);
    }

    public static void text(GuiGraphics g, Font font, Component s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, true);
    }

    /** Уменьшенный текст, выровненный по центру. */
    public static void smallCentered(GuiGraphics g, Font font, String s, float cx, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, -font.width(s) / 2, 0, color, true);
        g.pose().popPose();
    }

    public static void small(GuiGraphics g, Font font, String s, float x, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, color, true);
        g.pose().popPose();
    }

    /** Мелкий текст с переносом по ширине; возвращает высоту. */
    public static int wrapped(GuiGraphics g, Font font, String s, float x, float y, int maxW, int color, float scale) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(net.minecraft.network.chat.FormattedText.of(s), Math.max(10, (int) (maxW / scale)));
        float lh = 10 * scale;
        for (int i = 0; i < lines.size(); i++) {
            g.pose().pushPose();
            g.pose().translate(x, y + i * lh, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, lines.get(i), 0, 0, color, true);
            g.pose().popPose();
        }
        return Math.round(lines.size() * lh);
    }

    public static float pulse(long periodMs) {
        double t = (System.currentTimeMillis() % periodMs) / (double) periodMs;
        return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
    }

    public static float easeOut(float t) {
        t = Mth.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }
}

package com.arc4io.archud.client.extras;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Свой прицел: форма, цвет, длина, толщина, зазор, обводка, точка в центре, прозрачность.
 * Рисуется в настоящих пикселях экрана (не в единицах интерфейса), поэтому чёткий при любом масштабе.
 */
public final class Crosshair {
    /** 0 — ванильный; дальше формы. */
    public static final int STYLES = 11;
    public static final int[] COLORS = {0, 0xFFFFFFFF, 0xFF4FC3F7, 0xFF9CFF5A, 0xFFFFD54F, 0xFFFF5252, 0xFFFF8A65, 0xFFB388FF, 0xFFF06292, 0xFF26C6DA, 0xFF000000};

    private Crosshair() {}

    public static int color(ArcConfig cfg, boolean onTarget) {
        if (onTarget && cfg.crosshairHitColor) return 0xFFFF4D4D;
        int c = cfg.crosshairColor;
        if (c == 0) c = Draw.brighten(Theme.accent(), 0.4F);
        return c;
    }

    /** Нарисовать прицел с центром в (cx, cy) в единицах интерфейса; scale — пикселей на единицу. */
    public static void draw(GuiGraphics g, float cx, float cy, double scale, ArcConfig cfg, boolean onTarget) {
        int col = color(cfg, onTarget);
        int alpha = Math.max(20, Math.min(255, cfg.crosshairOpacity * 255 / 100));
        col = Draw.alpha(col, alpha);
        int outline = Draw.alpha(0xFF000000, alpha * 2 / 3);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale((float) (1 / scale), (float) (1 / scale), 1F);
        int len = Math.max(1, cfg.crosshairSize), t = Math.max(1, cfg.crosshairThickness), gap = Math.max(0, cfg.crosshairGap);
        if (cfg.crosshairOutline) shape(g, cfg.crosshairStyle, len, t, gap, outline, 1);
        shape(g, cfg.crosshairStyle, len, t, gap, col, 0);
        if (cfg.crosshairDot && cfg.crosshairStyle != 1) {
            int d = Math.max(1, t);
            int o = d / 2;
            if (cfg.crosshairOutline) g.fill(-o - 1, -o - 1, -o + d + 1, -o + d + 1, outline);
            g.fill(-o, -o, -o + d, -o + d, col);
        }
        g.pose().popPose();
    }

    /** Прямоугольник с расширением на e пикселей (для обводки). */
    private static void rect(GuiGraphics g, int x1, int y1, int x2, int y2, int c, int e) {
        g.fill(x1 - e, y1 - e, x2 + e, y2 + e, c);
    }

    private static void shape(GuiGraphics g, int style, int len, int t, int gap, int c, int e) {
        int h = t / 2;
        switch (style) {
            case 1 -> { // точка
                int d = Math.max(2, t * 2);
                rect(g, -d / 2, -d / 2, -d / 2 + d, -d / 2 + d, c, e);
            }
            case 2, 3 -> { // крест / T
                rect(g, -gap - len, -h, -gap, -h + t, c, e);
                rect(g, gap + 1, -h, gap + 1 + len, -h + t, c, e);
                rect(g, -h, gap + 1, -h + t, gap + 1 + len, c, e);
                if (style == 2) rect(g, -h, -gap - len, -h + t, -gap, c, e);
            }
            case 4 -> ring(g, gap + len / 2 + 2, t, c, e); // круг
            case 5 -> { // круг + крест внутри
                int r = gap + len + 1;
                ring(g, r, t, c, e);
                rect(g, -r + t + 1, -h, -gap, -h + t, c, e);
                rect(g, gap + 1, -h, r - t, -h + t, c, e);
            }
            case 6 -> { // уголки
                int r = gap + len;
                int a = Math.max(2, len / 2);
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        int x = sx * r, y = sy * r;
                        int x1 = sx < 0 ? x : x - a + 1, y1 = sy < 0 ? y : y - t + 1;
                        rect(g, Math.min(x, x1), y1, Math.max(x, x1) + (sx < 0 ? a : 1), y1 + t, c, e);
                        int vx1 = sx < 0 ? x : x - t + 1, vy1 = sy < 0 ? y : y - a + 1;
                        rect(g, vx1, Math.min(y, vy1), vx1 + t, Math.max(y, vy1) + (sy < 0 ? a : 1), c, e);
                    }
                }
            }
            case 7 -> { // шеврон (галочка вниз)
                int n = len + gap;
                for (int i = 0; i < n; i++) {
                    rect(g, -i - 1 - h, gap + i, -i - 1 - h + t, gap + i + t, c, e);
                    rect(g, i + 1 - h, gap + i, i + 1 - h + t, gap + i + t, c, e);
                }
            }
            case 8 -> { // диагональный крест (X)
                for (int i = gap; i < gap + len; i++) {
                    rect(g, i - h, i - h, i - h + t, i - h + t, c, e);
                    rect(g, -i - h, i - h, -i - h + t, i - h + t, c, e);
                    rect(g, i - h, -i - h, i - h + t, -i - h + t, c, e);
                    rect(g, -i - h, -i - h, -i - h + t, -i - h + t, c, e);
                }
            }
            case 9 -> { // квадрат
                int r = gap + len / 2 + 1;
                rect(g, -r, -r, r + 1, -r + t, c, e);
                rect(g, -r, r - t + 1, r + 1, r + 1, c, e);
                rect(g, -r, -r, -r + t, r + 1, c, e);
                rect(g, r - t + 1, -r, r + 1, r + 1, c, e);
            }
            case 10 -> { // ромб
                int r = gap + len / 2 + 2;
                for (int i = 0; i <= r; i++) {
                    int w = r - i;
                    rect(g, -w - h, -i - h, -w - h + t, -i - h + t, c, e);
                    rect(g, w - h, -i - h, w - h + t, -i - h + t, c, e);
                    rect(g, -w - h, i - h, -w - h + t, i - h + t, c, e);
                    rect(g, w - h, i - h, w - h + t, i - h + t, c, e);
                }
            }
            default -> {
            }
        }
    }

    /** Кольцо радиуса r толщиной t. */
    private static void ring(GuiGraphics g, int r, int t, int c, int e) {
        int outer = r + t + e, inner = Math.max(0, r - e);
        for (int y = -outer; y <= outer; y++) {
            int xo = (int) Math.floor(Math.sqrt(Math.max(0, outer * outer - y * y)));
            int xi = Math.abs(y) < inner ? (int) Math.ceil(Math.sqrt(Math.max(0, inner * inner - y * y))) : -1;
            if (xi < 0) g.fill(-xo, y, xo + 1, y + 1, c);
            else {
                g.fill(-xo, y, -xi + 1, y + 1, c);
                g.fill(xi, y, xo + 1, y + 1, c);
            }
        }
    }
}

package com.arc4io.archud.client.map;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Иконки меток в стиле игровых карт (Ведьмак, GTA): круглый значок с цветной обводкой и белой иконкой.
 * Набор в textures/gui/map_icons.png (клетки 32×32, 8 в ряд).
 */
public final class MapIcons {
    public static final ResourceLocation TEX = ArcHud.id("textures/gui/map_icons.png");
    public static final String[] IDS = {"house", "castle", "tower", "tent", "cave", "pickaxe", "sword", "skull", "chest", "star", "flag",
            "heart", "portal", "wheat", "tree", "mountain", "anchor", "crown", "key", "gem", "fire", "eye", "question", "warning", "moon",
            "shield", "bed", "boat"};

    private MapIcons() {}

    public static int index(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** Закрашенный круг. */
    public static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.floor(Math.sqrt(r * r - dy * dy + 0.5));
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** Только иконка (белая, с тёмным контуром) размером size. */
    public static void glyph(GuiGraphics g, String id, int x, int y, int size, int alpha) {
        int i = index(id);
        if (i < 0) return;
        RenderSystem.enableBlend();
        g.setColor(1F, 1F, 1F, alpha / 255F);
        g.blit(TEX, x, y, size, size, (i % 8) * 32, (i / 8) * 32, 32, 32, 256, 128);
        g.setColor(1F, 1F, 1F, 1F);
    }

    /** Круглый значок: тень, цветная обводка, тёмная середина и иконка. */
    public static void badge(GuiGraphics g, String id, int cx, int cy, int r, int color, int alpha) {
        disc(g, cx, cy, r + 1, Draw.alpha(0xFF000000, alpha * 3 / 4));
        disc(g, cx, cy, r, Draw.alpha(color, alpha));
        disc(g, cx, cy, r - 1, Draw.alpha(0xFF141A22, alpha * 15 / 16));
        int s = Math.max(6, (r - 1) * 2 - 1);
        glyph(g, id, cx - s / 2, cy - s / 2, s, alpha);
    }

    /** Метка на карте: со своей иконкой — круглый значок, иначе прежний ромб с буквами. */
    public static void marker(GuiGraphics g, Font font, Waypoint w, int x, int y, int r, boolean letters) {
        int color = 0xFF000000 | w.color;
        if (index(w.icon) >= 0) {
            badge(g, w.icon, x, y, r + 1, color, 0xFF);
            return;
        }
        MapView.diamond(g, x, y, r, color);
        if (letters) Draw.smallCentered(g, font, w.initials(), x + 0.5F, y - 2, 0xFF000000, 0.5F);
    }
}

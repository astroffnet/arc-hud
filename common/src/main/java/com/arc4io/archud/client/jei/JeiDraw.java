package com.arc4io.archud.client.jei;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import mezz.jei.api.gui.ITickTimer;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.drawable.IDrawableBuilder;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.drawable.IScalableDrawable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Картинки для плагинов JEI: куски текстур, анимированные полосы, стрелки, огонь, ячейки — в стиле Arc HUD. */
public final class JeiDraw {
    private JeiDraw() {}

    /** Простая картинка с рисованием через лямбду. */
    interface Painter {
        void paint(GuiGraphics g, int x, int y, int mt, int mb, int ml, int mr);
    }

    static IDrawableStatic drawable(int w, int h, Painter p) {
        return Stub.of(IDrawableStatic.class)
                .on("getWidth", 0, a -> w)
                .on("getHeight", 0, a -> h)
                .on("draw", 1, a -> {
                    p.paint((GuiGraphics) a[0], 0, 0, 0, 0, 0, 0);
                    return null;
                })
                .on("draw", 3, a -> {
                    p.paint((GuiGraphics) a[0], (Integer) a[1], (Integer) a[2], 0, 0, 0, 0);
                    return null;
                })
                .on("draw", 7, a -> {
                    p.paint((GuiGraphics) a[0], (Integer) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4], (Integer) a[5], (Integer) a[6]);
                    return null;
                })
                .build();
    }

    /** Кусок текстуры u,v,w,h (размер текстуры tw×th) с отступами. */
    static IDrawableStatic texture(ResourceLocation tex, int u, int v, int w, int h, int tw, int th, int pt, int pb, int pl, int pr) {
        return drawable(w + pl + pr, h + pt + pb, (g, x, y, mt, mb, ml, mr) -> {
            int dw = w - ml - mr, dh = h - mt - mb;
            if (dw <= 0 || dh <= 0) return;
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            g.blit(tex, x + pl + ml, y + pt + mt, u + ml, v + mt, dw, dh, tw, th);
        });
    }

    static IDrawableBuilder builder(ResourceLocation tex, int u0, int v0, int w0, int h0) {
        int[] s = {u0, v0, w0, h0, 256, 256, 0, 0, 0, 0}; // u v w h tw th pt pb pl pr
        IDrawableBuilder[] self = new IDrawableBuilder[1];
        self[0] = Stub.of(IDrawableBuilder.class)
                .on("setTextureSize", 2, a -> {
                    s[4] = (Integer) a[0];
                    s[5] = (Integer) a[1];
                    return self[0];
                })
                .on("addPadding", 4, a -> {
                    s[6] += (Integer) a[0];
                    s[7] += (Integer) a[1];
                    s[8] += (Integer) a[2];
                    s[9] += (Integer) a[3];
                    return self[0];
                })
                .on("trim", 4, a -> {
                    int t = (Integer) a[0], b = (Integer) a[1], l = (Integer) a[2], r = (Integer) a[3];
                    s[0] += l;
                    s[1] += t;
                    s[2] -= l + r;
                    s[3] -= t + b;
                    return self[0];
                })
                .on("build", 0, a -> texture(tex, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]))
                .on("buildAnimated", 3, a -> animated(texture(tex, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]),
                        timer((Integer) a[0], (Boolean) a[2]), (IDrawableAnimated.StartDirection) a[1]))
                .on("buildAnimated", 2, a -> animated(texture(tex, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]),
                        (ITickTimer) a[0], (IDrawableAnimated.StartDirection) a[1]))
                .build();
        return self[0];
    }

    /** Таймер по игровым тикам клиента (как у JEI): 0…max за ticks тиков. */
    static ITickTimer timer(int ticks, boolean countDown) {
        return tickTimer(ticks, 100, countDown);
    }

    static ITickTimer tickTimer(int ticks, int max, boolean countDown) {
        long start = Util.getMillis();
        return Stub.of(ITickTimer.class)
                .on("getMaxValue", 0, a -> max)
                .on("getValue", 0, a -> {
                    long period = Math.max(1, ticks) * 50L;
                    long t = (Util.getMillis() - start) % period;
                    int v = (int) (t * max / period);
                    return countDown ? max - v : v;
                })
                .build();
    }

    /** Анимация: картинка «проявляется» с одной стороны (стрелки процесса, огонь). */
    static IDrawableAnimated animated(IDrawableStatic d, ITickTimer timer, IDrawableAnimated.StartDirection dir) {
        return Stub.of(IDrawableAnimated.class)
                .on("getWidth", 0, a -> d.getWidth())
                .on("getHeight", 0, a -> d.getHeight())
                .on("draw", 3, a -> {
                    GuiGraphics g = (GuiGraphics) a[0];
                    int x = (Integer) a[1], y = (Integer) a[2];
                    int max = Math.max(1, timer.getMaxValue());
                    float f = Math.max(0F, Math.min(1F, timer.getValue() / (float) max));
                    int w = d.getWidth(), h = d.getHeight();
                    int hideW = Math.round(w * (1F - f)), hideH = Math.round(h * (1F - f));
                    switch (dir) {
                        case LEFT -> d.draw(g, x, y, 0, 0, 0, hideW);
                        case RIGHT -> d.draw(g, x, y, 0, 0, hideW, 0);
                        case TOP -> d.draw(g, x, y, 0, hideH, 0, 0);
                        case BOTTOM -> d.draw(g, x, y, hideH, 0, 0, 0);
                    }
                    return null;
                })
                .build();
    }

    static IDrawableStatic blank(int w, int h) {
        return drawable(w, h, (g, x, y, mt, mb, ml, mr) -> {});
    }

    static IDrawable item(ItemStack s) {
        return drawable(16, 16, (g, x, y, mt, mb, ml, mr) -> {
            g.renderItem(s, x, y);
            g.renderItemDecorations(Minecraft.getInstance().font, s, x, y);
        });
    }

    static IDrawableStatic sprite(TextureAtlas atlas, ResourceLocation id, int w, int h) {
        return drawable(w, h, (g, x, y, mt, mb, ml, mr) -> {
            TextureAtlasSprite sp = atlas.getSprite(id);
            g.blit(x, y, 0, w, h, sp);
        });
    }

    static IScalableDrawable scalableSprite(TextureAtlas atlas, ResourceLocation id) {
        return Stub.of(IScalableDrawable.class)
                .on("draw", 5, a -> {
                    GuiGraphics g = (GuiGraphics) a[0];
                    g.blit((Integer) a[1], (Integer) a[2], 0, (Integer) a[3], (Integer) a[4], atlas.getSprite(id));
                    return null;
                })
                .build();
    }

    // ------------------------------------------------------------------ элементы оформления в стиле Arc

    static IDrawableStatic slot() {
        return drawable(18, 18, (g, x, y, mt, mb, ml, mr) -> Draw.panel(g, x, y, 18, 18, 0xE0070A0E, 0x50FFFFFF));
    }

    static IDrawableStatic outputSlot() {
        return drawable(26, 26, (g, x, y, mt, mb, ml, mr) -> Draw.panel(g, x, y, 26, 26, 0xE0070A0E, Draw.alpha(Theme.accent(), 0xA0)));
    }

    /** Стрелка 24×17 (как у JEI); filled — залитая цветом темы. */
    static IDrawableStatic arrow(boolean filled) {
        return drawable(24, 17, (g, x, y, mt, mb, ml, mr) -> {
            int c = filled ? Draw.alpha(Theme.accent(), 0xFF) : 0x60FFFFFF;
            int right = 24 - mr;
            int cy = y + 8;
            int shaftEnd = Math.min(right, 16);
            if (shaftEnd > ml) g.fill(x + Math.max(ml, 1), cy - 1, x + shaftEnd, cy + 2, c);
            for (int k = 0; k < 7; k++) {
                int px = 16 + k;
                if (px >= right || px < ml) continue;
                g.fill(x + px, cy - 6 + k, x + px + 1, cy + 7 - k, c);
            }
        });
    }

    static IDrawableStatic plus() {
        return drawable(13, 13, (g, x, y, mt, mb, ml, mr) -> {
            int c = 0xA0FFFFFF;
            g.fill(x + 5, y + 1, x + 8, y + 12, c);
            g.fill(x + 1, y + 5, x + 12, y + 8, c);
        });
    }

    /** Огонь 14×14 (печи). */
    static IDrawableStatic flame(boolean filled) {
        return drawable(14, 14, (g, x, y, mt, mb, ml, mr) -> {
            int top = y + mt;
            int c1 = filled ? 0xFFFF8A3D : 0x40FFFFFF, c2 = filled ? 0xFFFFC857 : 0x30FFFFFF;
            for (int row = 0; row < 14; row++) {
                int ry = y + row;
                if (ry < top || row >= 14 - mb) continue;
                int half = row < 4 ? 1 + row / 2 : row < 10 ? 4 + (row - 4) / 2 : 6;
                g.fill(x + 7 - half, ry, x + 7 + half, ry + 1, row > 8 ? c2 : c1);
            }
        });
    }
}

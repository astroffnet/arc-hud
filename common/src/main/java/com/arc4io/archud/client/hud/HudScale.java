package com.arc4io.archud.client.hud;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Размер блоков HUD (50–200%), настраивается в «Расположении HUD».
 * Пока рисуется масштабированный блок, координаты обрезки (scissor) пересчитываются через {@link #scissor}.
 */
public final class HudScale {
    public static final float MIN = 0.5F, MAX = 2F, STEP = 0.05F;
    private static float tx, ty, s = 1F;

    private HudScale() {}

    public static float get(String id) {
        Float v = ArcConfig.get().hudScales.get(id);
        return v == null ? 1F : Math.max(MIN, Math.min(MAX, v));
    }

    public static void set(String id, float v) {
        v = Math.round(Math.max(MIN, Math.min(MAX, v)) / STEP) * STEP;
        if (Math.abs(v - 1F) < 0.001F) ArcConfig.get().hudScales.remove(id);
        else ArcConfig.get().hudScales.put(id, v);
    }

    /** Начать масштабированный блок: (x, y) — его левый верхний угол на экране. */
    static void begin(GuiGraphics g, float x, float y, float scale) {
        tx = x;
        ty = y;
        s = scale;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1F);
    }

    static void end(GuiGraphics g) {
        g.pose().popPose();
        tx = ty = 0;
        s = 1F;
    }

    /** Обрезка в координатах блока с учётом текущего масштаба. */
    public static void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        if (s == 1F && tx == 0 && ty == 0) {
            g.enableScissor(x1, y1, x2, y2);
            return;
        }
        g.enableScissor(Math.round(tx + x1 * s), Math.round(ty + y1 * s), Math.round(tx + x2 * s), Math.round(ty + y2 * s));
    }
}

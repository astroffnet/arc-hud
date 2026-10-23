package com.arc4io.archud.client.screen;

import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Автомасштаб экранов Arc HUD: если при выбранном «Масштабе интерфейса» экрану не хватает места
 * (маленькое окно, монитор 4:3, ноутбук, крупный масштаб), экран временно рисуется в ближайшем
 * меньшем масштабе, при котором всё помещается без наложений. При выходе в игру или на обычный
 * экран возвращается масштаб, выбранный игроком.
 */
public final class ScreenFit {
    private static boolean overridden;

    private ScreenFit() {}

    /**
     * Гарантирует экрану минимум minW×minH «GUI-пикселей». Вызывать в начале init().
     * Меняет screen.width / screen.height, если масштаб пришлось уменьшить.
     */
    public static void ensure(Screen screen, int minW, int minH) {
        Minecraft mc = Minecraft.getInstance();
        if (!ArcConfig.get().autoGuiScale || mc.screen != screen && mc.screen != null) return;
        Window w = mc.getWindow();
        int fbW = w.getWidth(), fbH = w.getHeight();
        if (fbW <= 0 || fbH <= 0) return;
        int userScale = w.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
        int scale = userScale;
        while (scale > 1 && (fbW / scale < minW || fbH / scale < minH)) scale--;
        if (scale == (int) w.getGuiScale()) return;
        w.setGuiScale(scale);
        overridden = scale != userScale;
        screen.width = w.getGuiScaledWidth();
        screen.height = w.getGuiScaledHeight();
    }

    /** Перед сменой экрана: если следующий экран не наш — вернуть масштаб игрока. */
    public static void onScreenChange(Screen next) {
        if (!overridden) return;
        if (next instanceof ArcScreen || next instanceof Fits) return; // наш экран сам выставит масштаб в init()
        restore();
    }

    public static void restore() {
        if (!overridden) return;
        overridden = false;
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        w.setGuiScale(w.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode()));
    }

    /** Отметка для полноэкранных экранов Arc HUD, которые сами вызывают ensure(). */
    public interface Fits {}
}

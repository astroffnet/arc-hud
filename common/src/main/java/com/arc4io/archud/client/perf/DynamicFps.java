package com.arc4io.archud.client.perf;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;

/** Ограничение FPS, когда игра в фоне (окно не в фокусе) или свёрнута. 0 — без ограничения. */
public final class DynamicFps {
    private DynamicFps() {}

    public static int limit(Minecraft mc) {
        ArcConfig cfg = ArcConfig.get();
        var w = mc.getWindow();
        boolean minimized = w.getWidth() <= 0 || w.getHeight() <= 0
                || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(w.getWindow(), org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0;
        if (minimized && (cfg.dynamicFps || Perf.on("s_minimized"))) return 1; // свёрнуто
        // меню без мира (главное меню, выбор мира, настройки до входа) — 30 кадров хватает
        if (Perf.on("s_menu_fps") && mc.level == null && mc.screen != null && mc.getOverlay() == null) return 30;
        if (!cfg.dynamicFps) return 0;
        if (!mc.isWindowActive()) return Math.max(1, cfg.backgroundFps);
        return 0;
    }
}

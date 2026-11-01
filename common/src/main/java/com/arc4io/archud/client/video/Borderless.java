package com.arc4io.archud.client.video;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * F11 — «полноэкранный в окне» (окно без рамки размером с монитор) вместо эксклюзивного полноэкранного режима:
 * мгновенное переключение, Alt+Tab без чёрного экрана и смены разрешения. Состояние запоминается между запусками.
 */
public final class Borderless {
    private static boolean on;
    private static int px, py, pw, ph;
    private static boolean restored;

    private Borderless() {}

    public static boolean active() {
        return on;
    }

    /** Нажата F11. Возвращает true, если переключили сами (вместо ванильного полноэкранного режима). */
    public static boolean toggle(Window w) {
        if (!ArcConfig.get().borderlessF11) return false;
        // из настоящего полноэкранного режима (включён в настройках видео) — сначала обычный выход
        if (w.isFullscreen()) {
            w.toggleFullScreen();
            Minecraft.getInstance().options.fullscreen().set(false);
            return true;
        }
        set(w, !on);
        ArcConfig.get().borderlessActive = on;
        ArcConfig.save();
        return true;
    }

    /** Первый тик клиента: вернуть «полноэкранный в окне», если игра была закрыта в нём. */
    public static void restore(Minecraft mc) {
        if (restored) return;
        restored = true;
        if (ArcConfig.get().borderlessF11 && ArcConfig.get().borderlessActive && !mc.getWindow().isFullscreen()) set(mc.getWindow(), true);
    }

    private static void set(Window w, boolean enable) {
        long h = w.getWindow();
        try {
            if (enable) {
                if (GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) GLFW.glfwRestoreWindow(h);
                int[] a = new int[1], b = new int[1];
                GLFW.glfwGetWindowPos(h, a, b);
                px = a[0];
                py = b[0];
                GLFW.glfwGetWindowSize(h, a, b);
                pw = a[0];
                ph = b[0];
                long mon = monitorFor(h);
                if (mon == 0) return;
                GLFWVidMode vm = GLFW.glfwGetVideoMode(mon);
                if (vm == null) return;
                GLFW.glfwGetMonitorPos(mon, a, b);
                GLFW.glfwSetWindowAttrib(h, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowMonitor(h, 0L, a[0], b[0], vm.width(), vm.height(), GLFW.GLFW_DONT_CARE);
                on = true;
            } else {
                GLFW.glfwSetWindowAttrib(h, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                int ww = pw > 100 ? pw : 854, hh = ph > 100 ? ph : 480;
                long mon = monitorFor(h);
                int x = px, y = py;
                if (pw <= 100 && mon != 0) {
                    GLFWVidMode vm = GLFW.glfwGetVideoMode(mon);
                    int[] a = new int[1], b = new int[1];
                    GLFW.glfwGetMonitorPos(mon, a, b);
                    if (vm != null) {
                        x = a[0] + (vm.width() - ww) / 2;
                        y = b[0] + (vm.height() - hh) / 2;
                    }
                }
                GLFW.glfwSetWindowMonitor(h, 0L, x, Math.max(y, 30), ww, hh, GLFW.GLFW_DONT_CARE);
                on = false;
            }
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: borderless fullscreen failed", t);
        }
    }

    /** Монитор, на котором находится центр окна (или основной). */
    private static long monitorFor(long h) {
        int[] x = new int[1], y = new int[1], ww = new int[1], hh = new int[1];
        GLFW.glfwGetWindowPos(h, x, y);
        GLFW.glfwGetWindowSize(h, ww, hh);
        int cx = x[0] + ww[0] / 2, cy = y[0] + hh[0] / 2;
        PointerBuffer mons = GLFW.glfwGetMonitors();
        if (mons != null) {
            for (int i = 0; i < mons.limit(); i++) {
                long m = mons.get(i);
                GLFWVidMode vm = GLFW.glfwGetVideoMode(m);
                if (vm == null) continue;
                int[] mx = new int[1], my = new int[1];
                GLFW.glfwGetMonitorPos(m, mx, my);
                if (cx >= mx[0] && cx < mx[0] + vm.width() && cy >= my[0] && cy < my[0] + vm.height()) return m;
            }
        }
        return GLFW.glfwGetPrimaryMonitor();
    }
}

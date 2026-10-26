package com.arc4io.archud.client.perf;

import com.arc4io.archud.mixin.LevelRendererAccessor;
import com.arc4io.archud.mixin.RenderChunkInfoAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Адаптивная дальность отрисовки (r_adaptive): держит заданный FPS при большой дальности прорисовки.
 * <p>
 * Алгоритм: сглаженное время кадра (EMA) сравнивается с целью. Если FPS ниже цели на 8% дольше 0,4 с — радиус
 * отрисовки уменьшается на 1 чанк; если выше цели на 12% (или упирается в лимит кадров) дольше 1,5 с — возвращается
 * на 1 чанк. Чанки за радиусом остаются загруженными, просто не рисуются и не перестраиваются, поэтому дальность
 * возвращается мгновенно, без подгрузки. Туман подтягивается к радиусу, чтобы край не был виден.
 * Не ниже трети дальности (минимум 5 чанков). Работает с ванильным рендером и OptiFine; Sodium/Embeddium
 * рисуют чанки своим кодом — там эта настройка не действует.
 */
public final class AdaptiveDistance {
    private static double emaMs = 16.6;
    private static long lastFrame, lowSince, highSince, lastChange;
    private static int radius = -1; // в чанках; -1 — не ограничено
    private static boolean inCompile;

    private AdaptiveDistance() {}

    /** Каждый кадр перед отрисовкой мира. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (lastFrame != 0) {
            double ms = (now - lastFrame) / 1e6;
            if (ms < 1000) emaMs = emaMs * 0.9 + ms * 0.1;
        }
        lastFrame = now;
        int target = Perf.get("r_adaptive");
        int rd = mc.options.getEffectiveRenderDistance();
        if (target <= 0 || mc.level == null) {
            if (radius >= 0) {
                radius = -1;
                refresh(mc);
            }
            return;
        }
        if (mc.isPaused()) return;
        if (radius < 0 || radius > rd) {
            radius = rd;
            refresh(mc);
        }
        // если кадры ограничены (лимит FPS / вертикальная синхронизация) — цель не выше лимита
        int cap = mc.options.enableVsync().get() ? Math.max(30, mc.getWindow().getRefreshRate())
                : mc.options.framerateLimit().get() >= 260 ? 100000 : mc.options.framerateLimit().get();
        double goal = Math.min(target, cap * 0.95);
        double fps = 1000.0 / emaMs;
        long t = System.currentTimeMillis();
        int min = Math.min(rd, Math.max(5, rd / 3));
        if (fps < goal * 0.92) {
            highSince = 0;
            if (lowSince == 0) lowSince = t;
            if (t - lowSince > 400 && t - lastChange > 400 && radius > min) {
                radius--;
                lastChange = t;
                refresh(mc);
            }
        } else if (fps > goal * 1.12 || fps > cap * 0.97) {
            lowSince = 0;
            if (highSince == 0) highSince = t;
            if (t - highSince > 1500 && t - lastChange > 1500 && radius < rd) {
                radius++;
                lastChange = t;
                refresh(mc);
            }
        } else {
            lowSince = highSince = 0;
        }
    }

    /** Текущий радиус, если он меньше дальности прорисовки; иначе -1. */
    public static int limit() {
        Minecraft mc = Minecraft.getInstance();
        return radius >= 0 && radius < mc.options.getEffectiveRenderDistance() ? radius : -1;
    }

    private static void refresh(Minecraft mc) {
        try {
            ((LevelRendererAccessor) mc.levelRenderer).archud$needsFrustumUpdate().set(true);
        } catch (Throwable ignored) {
        }
    }

    /** После отбора видимых чанков: убрать те, что дальше радиуса. */
    @SuppressWarnings("unchecked")
    public static void filter(Object levelRenderer) {
        int r = limit();
        if (r < 0) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int cx = ((int) Math.floor(cam.x)) >> 4, cz = ((int) Math.floor(cam.z)) >> 4;
        long r2 = (long) (r + 1) * (r + 1);
        ((LevelRendererAccessor) levelRenderer).archud$renderChunksInFrustum().removeIf(o -> {
            BlockPos p = ((RenderChunkInfoAccessor) o).archud$chunk().getOrigin();
            long dx = (p.getX() >> 4) - cx, dz = (p.getZ() >> 4) - cz;
            return dx * dx + dz * dz > r2;
        });
    }

    // ------------------------------------------------------------------ лимит загрузки чанков
    public static void compileStart() {
        inCompile = true;
    }

    public static void compileEnd() {
        inCompile = false;
    }

    public static boolean budgetUploads() {
        return inCompile && Perf.on("r_upload_budget");
    }

    /** Дистанция тумана (в блоках) при ограниченном радиусе; -1 — не менять. */
    public static float fogEnd() {
        int r = limit();
        return r < 0 ? -1F : r * 16F;
    }

    public static String info() {
        int r = limit();
        return r < 0 ? "" : r + "/" + Minecraft.getInstance().options.getEffectiveRenderDistance();
    }
}

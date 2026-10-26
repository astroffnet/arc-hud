package com.arc4io.archud.client.crash;

import com.arc4io.archud.ArcHud;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

/**
 * Антикрашер: ошибка в игровом цикле клиента (тик, отрисовка, экран, пакет) не закрывает игру — пишется отчёт
 * в crash-reports, мир сохраняется и закрывается, состояние отрисовки сбрасывается, и открывается экран
 * «Игра упала» с кнопкой «В главное меню». То же для краша встроенного сервера (одиночный мир).
 * <p>
 * Если краши идут подряд (3 за 15 секунд) или игра ещё не загрузилась — обычный краш, чтобы не зациклиться.
 */
public final class AntiCrash {
    private static final Deque<Long> RECENT = new ArrayDeque<>();
    private static volatile CrashReport delayed;
    private static boolean recovering;
    /** Игра дошла до главного меню (раньше восстанавливаться некуда). */
    private static volatile boolean loaded;

    public static void markLoaded() {
        loaded = true;
    }

    private AntiCrash() {}

    public static boolean enabled() {
        try {
            return com.arc4io.archud.config.ArcConfig.get().antiCrash;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Отложенный краш (например, упал встроенный сервер): обработать в начале следующего тика клиента. */
    public static boolean delay(CrashReport report) {
        if (!enabled() || !loaded) return false;
        delayed = report;
        return true;
    }

    /** Перед тиком клиента. */
    public static void beforeTick(Minecraft mc) {
        CrashReport r = delayed;
        if (r == null) return;
        delayed = null;
        if (!recover(mc, r.getException(), r)) Minecraft.crash(r);
    }

    /** Попробовать восстановиться. false — восстановиться нельзя (пусть игра упадёт как обычно). */
    public static boolean recover(Minecraft mc, Throwable t, CrashReport given) {
        if (!enabled() || recovering || t instanceof OutOfMemoryError) return false;
        if (!loaded || mc.getOverlay() != null) return false;
        long now = System.currentTimeMillis();
        while (!RECENT.isEmpty() && now - RECENT.peekFirst() > 15_000) RECENT.pollFirst();
        if (RECENT.size() >= 2) return false;
        RECENT.addLast(now);
        recovering = true;
        try {
            CrashReport report = given != null ? given : t instanceof ReportedException re ? re.getReport() : new CrashReport("Arc HUD: recovered crash", t);
            File file = null;
            try {
                mc.fillReport(report);
            } catch (Throwable ignored) {
            }
            try {
                File dir = new File(mc.gameDirectory, "crash-reports");
                file = new File(dir, "crash-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + "-client-recovered.txt");
                report.saveToFile(file);
            } catch (Throwable e) {
                file = null;
            }
            ArcHud.LOGGER.error("Arc HUD anti-crash: recovered from a crash" + (file != null ? " (report: " + file + ")" : ""), t);
            resetRenderState(mc);
            boolean inWorld = mc.level != null;
            if (inWorld) {
                // закрыть мир; встроенный сервер при остановке сохраняет мир
                try {
                    mc.level.disconnect();
                } catch (Throwable ignored) {
                }
                try {
                    mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(Component.translatable("archud.crash.saving")));
                } catch (Throwable e) {
                    ArcHud.LOGGER.warn("Arc HUD anti-crash: clearLevel failed", e);
                }
            }
            try {
                mc.mouseHandler.releaseMouse();
            } catch (Throwable ignored) {
            }
            mc.setScreen(new CrashRecoveryScreen(t, file, inWorld));
            return true;
        } catch (Throwable fatal) {
            ArcHud.LOGGER.error("Arc HUD anti-crash: recovery failed", fatal);
            return false;
        } finally {
            recovering = false;
        }
    }

    /** Сбросить то, что могло остаться «посреди кадра»: матрицы, незаконченные буферы, подменённый буфер кадра. */
    private static void resetRenderState(Minecraft mc) {
        try {
            com.arc4io.archud.client.fx.RenderScale.emergencyRestore();
        } catch (Throwable ignored) {
        }
        try {
            PoseStack mv = RenderSystem.getModelViewStack();
            while (!mv.clear()) mv.popPose();
            mv.setIdentity();
            RenderSystem.applyModelViewMatrix();
        } catch (Throwable ignored) {
        }
        try {
            var b = Tesselator.getInstance().getBuilder();
            if (b.building()) b.end();
        } catch (Throwable ignored) {
        }
        try {
            mc.renderBuffers().bufferSource().endBatch();
        } catch (Throwable ignored) {
        }
        try {
            RenderSystem.disableScissor();
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            mc.getMainRenderTarget().bindWrite(true);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}

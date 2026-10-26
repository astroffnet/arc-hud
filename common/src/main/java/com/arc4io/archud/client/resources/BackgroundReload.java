package com.arc4io.archud.client.resources;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.LoadingOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ReloadInstance;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Перезагрузка ресурсов в фоне (как RRLS): смена ресурспака, F3+T, подгрузка нового мода — без экрана загрузки
 * на весь экран. Игра продолжает работать и отвечать, сверху — тонкая полоска прогресса. Первая загрузка игры
 * не трогается. С OptiFine выключено (он вмешивается в перезагрузку ресурсов).
 */
public final class BackgroundReload {
    private static ReloadInstance reload;
    private static Consumer<Optional<Throwable>> onFinish;
    private static float shown;
    private static long started;

    private BackgroundReload() {}

    public static boolean enabled() {
        return ArcConfig.get().backgroundReload && !com.arc4io.archud.compat.OptiFineCompat.present();
    }

    /** Из Minecraft.setOverlay: забрать перезагрузку себе вместо экрана загрузки. */
    @SuppressWarnings("unchecked")
    public static boolean take(Object overlay) {
        if (!(overlay instanceof LoadingOverlay lo) || !enabled() || reload != null) return false;
        LoadingOverlayAccessor a = (LoadingOverlayAccessor) lo;
        if (!a.archud$fadeIn()) return false; // первая загрузка игры — как обычно
        reload = a.archud$reload();
        onFinish = (Consumer<Optional<Throwable>>) a.archud$onFinish();
        shown = 0;
        started = System.currentTimeMillis();
        ArcHud.LOGGER.info("Arc HUD: reloading resources in the background");
        return true;
    }

    public static boolean active() {
        return reload != null;
    }

    /** Каждый кадр после отрисовки экрана: полоска прогресса и завершение. */
    public static void frame(Minecraft mc) {
        ReloadInstance r = reload;
        if (r == null) return;
        if (r.isDone()) {
            reload = null;
            Consumer<Optional<Throwable>> done = onFinish;
            onFinish = null;
            try {
                r.checkExceptions();
                done.accept(Optional.empty());
            } catch (Throwable t) {
                done.accept(Optional.of(t));
            }
            if (mc.screen != null) mc.screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            ArcHud.LOGGER.info("Arc HUD: background reload finished in {} ms", System.currentTimeMillis() - started);
            return;
        }
        shown += (r.getActualProgress() - shown) * 0.2F;
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0, 0, 500); // поверх всего интерфейса
            int w = mc.getWindow().getGuiScaledWidth();
            g.fill(0, 0, w, 2, 0x66000000);
            g.fill(0, 0, Math.round(w * Math.max(0.02F, shown)), 2, 0xFF4FC3F7);
            Component label = Component.translatable("archud.reload.bg", Math.round(shown * 100));
            int lw = mc.font.width(label);
            g.fill(w - lw - 10, 4, w - 2, 16, 0x99000000);
            g.drawString(mc.font, label, w - lw - 6, 6, 0xFFE0E6EE, false);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        } catch (Throwable ignored) {
        }
    }
}

package com.arc4io.archud.client.fx;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.MinecraftTargetAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import org.joml.Matrix4f;

/**
 * Масштаб рендера (в стиле AMD FSR 1): мир рисуется в уменьшенный буфер (например, 75% — это ~56% пикселей),
 * затем увеличивается до размера окна бикубическим фильтром с резкостью RCAS. Интерфейс рисуется в полном
 * разрешении поверх, поэтому текст остаётся чётким. Отключается в «Фантастической» графике и с шейдерпаками
 * (у них свои буферы, привязанные к размеру окна).
 */
public final class RenderScale {
    private static RenderTarget scaled, real;
    private static PostPass upscale;
    private static boolean failed, swapped;
    private static int lastW, lastH;
    /** Под какой размер сейчас подогнаны буферы LevelRenderer (обводка свечения); 0 — под окно. */
    private static int lrW, lrH, lrWinW, lrWinH;

    private RenderScale() {}

    public static float scale() {
        ArcConfig c = ArcConfig.get();
        if (failed || c.renderScale >= 100 || c.renderScale < 25) return 1F;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS || ShaderPacks.inUse()) return 1F;
        return c.renderScale / 100F;
    }

    /** Перед отрисовкой мира: подменить основной буфер уменьшенным. */
    public static void begin() {
        float s = scale();
        Minecraft mc = Minecraft.getInstance();
        if (s >= 1F) {
            // масштаб выключили — вернуть буферы мира к размеру окна
            if (lrW != 0) {
                lrW = lrH = 0;
                mc.levelRenderer.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            }
            return;
        }
        try {
            real = mc.getMainRenderTarget();
            int w = Math.max(1, Math.round(real.width * s)), h = Math.max(1, Math.round(real.height * s));
            if (scaled == null) scaled = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            else if (scaled.width != w || scaled.height != h) scaled.resize(w, h, Minecraft.ON_OSX);
            // буферы мира (обводка светящихся сущностей) — того же размера, иначе они переключают область вывода
            // на размер окна и всё, что рисуется после них (сущности, вода, рука), попадает не в тот масштаб
            int winW = mc.getWindow().getWidth(), winH = mc.getWindow().getHeight();
            if (lrW != w || lrH != h || lrWinW != winW || lrWinH != winH) {
                lrW = w;
                lrH = h;
                lrWinW = winW;
                lrWinH = winH;
                mc.levelRenderer.resize(w, h);
            }
            ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(scaled);
            swapped = true;
            scaled.bindWrite(true);
        } catch (Exception e) {
            fail(e);
        }
    }

    /** После отрисовки мира: вернуть основной буфер и увеличить картинку. */
    public static void end() {
        if (!swapped) return;
        swapped = false;
        Minecraft mc = Minecraft.getInstance();
        ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(real);
        try {
            if (upscale == null) {
                upscale = new PostPass(mc.getResourceManager(), "archud_upscale", scaled, real);
                lastW = lastH = -1;
            }
            if (real.width != lastW || real.height != lastH) {
                lastW = real.width;
                lastH = real.height;
                upscale.setOrthoMatrix(new Matrix4f().setOrtho(0F, lastW, 0F, lastH, 0.1F, 1000F));
            }
            ArcConfig c = ArcConfig.get();
            upscale.getEffect().safeGetUniform("Mode").set(c.upscaler == 0 ? 0F : 1F);
            upscale.getEffect().safeGetUniform("Sharpness").set(c.upscaleSharpness / 100F);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            upscale.process(mc.getFrameTime());
            real.bindWrite(true);
            RenderSystem.enableDepthTest();
        } catch (Exception e) {
            fail(e);
            real.bindWrite(true);
        }
    }

    private static void fail(Exception e) {
        failed = true;
        ArcHud.LOGGER.warn("Arc HUD: render scale disabled", e);
        Minecraft mc = Minecraft.getInstance();
        if (swapped && real != null) ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(real);
        swapped = false;
    }

    public static void reset() {
        if (upscale != null) upscale.close();
        upscale = null;
        failed = false;
    }

    /** Размер, в котором сейчас рисуется мир (для подписи в настройках). */
    public static String info() {
        Minecraft mc = Minecraft.getInstance();
        float s = scale();
        return Math.round(mc.getWindow().getWidth() * s) + "×" + Math.round(mc.getWindow().getHeight() * s);
    }
}

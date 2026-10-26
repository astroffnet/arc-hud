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
    private static boolean failed, swapped, temporalFrame;
    private static int lastW, lastH;
    /** Под какой размер сейчас подогнаны буферы LevelRenderer (обводка свечения); 0 — под окно. */
    private static int lrW, lrH, lrWinW, lrWinH;

    private RenderScale() {}

    private static boolean allowed() {
        if (failed) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.options.graphicsMode().get() != GraphicsStatus.FABULOUS && !ShaderPacks.inUse() && !com.arc4io.archud.client.gfx.GlContext.vulkan();
    }

    public static float scale() {
        ArcConfig c = ArcConfig.get();
        if (c.renderScale >= 100 || c.renderScale < 25 || !allowed()) return 1F;
        return c.renderScale / 100F;
    }

    /** Arc TSR (временной апскейл, аналог DLSS); при 100% работает как сглаживание (аналог DLAA). */
    public static boolean temporal() {
        return ArcConfig.get().upscaler == 2 && allowed();
    }

    /** Перед отрисовкой мира: подменить основной буфер уменьшенным. */
    public static void begin() {
        float s = scale();
        Minecraft mc = Minecraft.getInstance();
        boolean tsr = temporal();
        if (s >= 1F && !tsr) {
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
            if (scaled == null) {
                scaled = new TextureTarget(w, h, true, Minecraft.ON_OSX);
                Taau.invalidate();
            } else if (scaled.width != w || scaled.height != h) {
                scaled.resize(w, h, Minecraft.ON_OSX);
                Taau.invalidate();
            }
            // буферы мира (обводка светящихся сущностей) — того же размера, иначе они переключают область вывода
            // на размер окна и всё, что рисуется после них (сущности, вода, рука), попадает не в тот масштаб
            int winW = mc.getWindow().getWidth(), winH = mc.getWindow().getHeight();
            if (s >= 1F) {
                // TSR на 100%: буферы мира уже под окно
                if (lrW != 0) {
                    lrW = lrH = 0;
                    mc.levelRenderer.resize(winW, winH);
                }
            } else if (lrW != w || lrH != h || lrWinW != winW || lrWinH != winH) {
                lrW = w;
                lrH = h;
                lrWinW = winW;
                lrWinH = winH;
                mc.levelRenderer.resize(w, h);
            }
            ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(scaled);
            swapped = true;
            temporalFrame = tsr;
            if (tsr) Taau.beginFrame(w, h);
            scaled.bindWrite(true);
        } catch (Exception e) {
            fail(e);
        }
    }

    /** После отрисовки мира: вернуть основной буфер и увеличить картинку. */
    public static void end() {
        if (!swapped) return;
        swapped = false;
        Taau.endFrame();
        Minecraft mc = Minecraft.getInstance();
        ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(real);
        scaled.setFilterMode(9729); // линейная выборка для бикубического фильтра
        if (temporalFrame) {
            try {
                RenderSystem.disableBlend();
                RenderSystem.disableDepthTest();
                RenderSystem.resetTextureMatrix();
                Taau.resolve(mc, scaled, real, ArcConfig.get().upscaleSharpness / 100F, mc.getFrameTime());
                real.bindWrite(true);
            } catch (Exception e) {
                fail(e);
                real.bindWrite(true);
                RenderSystem.enableDepthTest();
            }
            return;
        }
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
        Taau.endFrame();
    }

    /** Антикрашер: если краш случился посреди кадра — вернуть настоящий буфер кадра. */
    public static void emergencyRestore() {
        Taau.endFrame();
        if (swapped && real != null) ((MinecraftTargetAccessor) Minecraft.getInstance()).archud$setMainRenderTarget(real);
        swapped = false;
    }

    public static void reset() {
        Taau.reset();
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

package com.arc4io.archud.client.fx;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import org.joml.Matrix4f;

/**
 * Встроенный лёгкий шейдер Arc (один проход после отрисовки мира, до интерфейса): FakeHDR, мягкое свечение,
 * насыщенность, контраст, температура, FXAA, резкость. Стоит ~0.2–0.5 мс на кадр даже на слабых видеокартах.
 * С шейдерпаками Iris / Oculus / OptiFine выключается сам (у них своя постобработка).
 */
public final class ArcEffects {
    private static PostPass effect, blit;
    private static RenderTarget swap;
    private static boolean failed;
    private static int w, h;

    private ArcEffects() {}

    public static boolean active() {
        ArcConfig c = ArcConfig.get();
        if (!c.shaderEnabled || failed || ShaderPacks.inUse() || com.arc4io.archud.client.gfx.GlContext.vulkan()) return false;
        return c.shaderHdr || c.shaderBloom || c.shaderFxaa || c.shaderSharpen > 0 || c.shaderVibrance != 0 || c.shaderContrast != 0
                || c.shaderTemperature != 0;
    }

    public static void process(float partialTick) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        try {
            if (effect == null) {
                swap = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
                effect = new PostPass(mc.getResourceManager(), "archud_effects", main, swap);
                blit = new PostPass(mc.getResourceManager(), "blit", swap, main);
                w = h = -1;
            }
            if (main.width != w || main.height != h) {
                w = main.width;
                h = main.height;
                swap.resize(w, h, Minecraft.ON_OSX);
                Matrix4f ortho = new Matrix4f().setOrtho(0F, w, 0F, h, 0.1F, 1000F);
                effect.setOrthoMatrix(ortho);
                blit.setOrthoMatrix(ortho);
            }
            ArcConfig c = ArcConfig.get();
            var fx = effect.getEffect();
            fx.safeGetUniform("Hdr").set(c.shaderHdr ? c.shaderHdrStrength / 100F : 0F);
            fx.safeGetUniform("Bloom").set(c.shaderBloom ? 1F : 0F);
            fx.safeGetUniform("Vibrance").set(c.shaderVibrance / 100F);
            fx.safeGetUniform("Contrast").set(c.shaderContrast / 100F);
            fx.safeGetUniform("Temperature").set(c.shaderTemperature / 100F);
            fx.safeGetUniform("Sharpen").set(c.shaderSharpen / 100F);
            fx.safeGetUniform("Fxaa").set(c.shaderFxaa ? 1F : 0F);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            effect.process(partialTick);
            blit.process(partialTick);
            main.bindWrite(true);
        } catch (Exception e) {
            failed = true;
            ArcHud.LOGGER.warn("Arc HUD: built-in shader unavailable", e);
            reset();
        }
    }

    /** После перезагрузки ресурсов шейдер пересоздаётся. */
    public static void reset() {
        if (effect != null) effect.close();
        if (blit != null) blit.close();
        if (swap != null) swap.destroyBuffers();
        effect = blit = null;
        swap = null;
    }

    public static void resetFailed() {
        failed = false;
    }
}

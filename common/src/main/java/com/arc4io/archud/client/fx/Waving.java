package com.arc4io.archud.client.fx;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * Шевеление травы, цветов, листвы и лиан: параметры для шейдера слоя cutout (assets/minecraft/shaders/core/
 * rendertype_cutout.vsh). Работает в ванильном рендере и с OptiFine без шейдеров; у Sodium / Embeddium свои
 * шейдеры чанков, у шейдерпаков — своё шевеление.
 */
public final class Waving {
    private Waving() {}

    /** Листва рисуется в слое cutout (без мип-текстур), чтобы шейдер мог отличить её от блока травы. */
    public static boolean leavesInCutout() {
        ArcConfig c = ArcConfig.get();
        return c.wavingLeaves && c.wavingStrength > 0;
    }

    /** Раз в кадр, перед отрисовкой мира. */
    public static void update() {
        ShaderInstance sh = Minecraft.getInstance().gameRenderer == null ? null : net.minecraft.client.renderer.GameRenderer.getRendertypeCutoutShader();
        if (sh == null) return;
        ArcConfig c = ArcConfig.get();
        float s = c.wavingStrength / 50F;
        float plants = c.wavingPlants ? s : 0F, leaves = c.wavingLeaves ? s : 0F;
        float time = (Util.getMillis() % 3_600_000L) / 1000F;
        sh.safeGetUniform("ArcWave").set(plants, leaves, c.wavingSpeed / 100F, time);
    }

    /** После смены настройки листвы — пересобрать чанки (листва переходит в другой слой). */
    public static void rebuildChunks() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null && mc.level != null) mc.levelRenderer.allChanged();
    }
}

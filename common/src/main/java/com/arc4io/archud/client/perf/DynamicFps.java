package com.arc4io.archud.client.perf;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;

/** Ограничение FPS, когда игра в фоне (окно не в фокусе) или свёрнута. 0 — без ограничения. */
public final class DynamicFps {
    private DynamicFps() {}

    public static int limit(Minecraft mc) {
        ArcConfig cfg = ArcConfig.get();
        if (!cfg.dynamicFps) return 0;
        var w = mc.getWindow();
        if (w.getWidth() <= 0 || w.getHeight() <= 0) return 1; // свёрнуто
        if (!mc.isWindowActive()) return Math.max(1, cfg.backgroundFps);
        return 0;
    }
}

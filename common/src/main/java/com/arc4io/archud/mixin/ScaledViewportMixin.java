package com.arc4io.archud.mixin;

import com.arc4io.archud.client.fx.RenderScale;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Масштаб рендера (Arc FSR / TSR): пока мир рисуется в уменьшенный буфер, любые попытки поставить область вывода
 * на размер окна (их делают OptiFine, Sodium/Embeddium и некоторые моды посреди отрисовки мира) переводятся
 * на размер уменьшенного буфера. Иначе сущности, вода и лава рисовались в другом масштабе и «висели в воздухе».
 */
@Mixin(GlStateManager.class)
public abstract class ScaledViewportMixin {
    private static boolean archud$inViewport;

    @Inject(method = "_viewport", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void archud$scaledViewport(int x, int y, int w, int h, CallbackInfo ci) {
        if (archud$inViewport) return;
        int[] fixed = RenderScale.fixViewport(x, y, w, h);
        if (fixed == null) return;
        archud$inViewport = true;
        try {
            GlStateManager._viewport(fixed[0], fixed[1], fixed[2], fixed[3]);
        } finally {
            archud$inViewport = false;
        }
        ci.cancel();
    }
}

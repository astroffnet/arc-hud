package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.GuiCache;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** На время записи окна в кэш (GuiCache) альфа-канал смешивается «поверх», чтобы картинку можно было наложить на мир. */
@Mixin(GlStateManager.class)
public abstract class GuiCacheBlendMixin {
    @Inject(method = "_blendFuncSeparate", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void archud$separate(int srcRgb, int dstRgb, int srcA, int dstA, CallbackInfo ci) {
        int[] o = GuiCache.blendOverride(srcRgb, dstRgb, srcA, dstA);
        if (o == null) return;
        GlStateManager._blendFuncSeparate(o[0], o[1], o[2], o[3]);
        ci.cancel();
    }

    @Inject(method = "_blendFunc", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void archud$simple(int src, int dst, CallbackInfo ci) {
        int[] o = GuiCache.blendOverride(src, dst, src, dst);
        if (o == null) return;
        GlStateManager._blendFuncSeparate(o[0], o[1], o[2], o[3]);
        ci.cancel();
    }
}

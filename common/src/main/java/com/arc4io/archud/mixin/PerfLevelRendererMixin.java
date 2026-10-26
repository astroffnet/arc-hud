package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: без отрисовки дождя/снега и без обводки светящихся сущностей (отдельный проход). */
@Mixin(LevelRenderer.class)
public abstract class PerfLevelRendererMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$rain(LightTexture light, float pt, double x, double y, double z, CallbackInfo ci) {
        if (Perf.on("w_rain")) ci.cancel();
    }

    @Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$outlines(CallbackInfoReturnable<Boolean> cir) {
        if (Perf.on("s_entity_outline")) cir.setReturnValue(false);
    }
}

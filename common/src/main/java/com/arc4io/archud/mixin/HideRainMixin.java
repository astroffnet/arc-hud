package com.arc4io.archud.mixin;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Скрыть дождь и снег (только картинка и брызги; погода на сервере не меняется). */
@Mixin(LevelRenderer.class)
public abstract class HideRainMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$rain(LightTexture light, float pt, double x, double y, double z, CallbackInfo ci) {
        if (ArcConfig.get().hideRain) ci.cancel();
    }

    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$rainTick(Camera camera, CallbackInfo ci) {
        if (ArcConfig.get().hideRain) ci.cancel();
    }
}

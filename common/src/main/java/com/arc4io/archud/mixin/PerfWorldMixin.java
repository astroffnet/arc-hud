package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: без звёзд и без вспышек неба от молний. */
@Mixin(ClientLevel.class)
public abstract class PerfWorldMixin {
    @Inject(method = "getStarBrightness", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$stars(float pt, CallbackInfoReturnable<Float> cir) {
        if (Perf.on("w_stars")) cir.setReturnValue(0F);
    }

    @Inject(method = "getSkyFlashTime", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$flash(CallbackInfoReturnable<Integer> cir) {
        if (Perf.on("w_lightning")) cir.setReturnValue(0);
    }
}

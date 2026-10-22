package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.DynamicFps;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Динамический FPS: когда окно игры не активно или свёрнуто — ограничение кадров (меньше нагрузка на ПК и нагрев). */
@Mixin(Minecraft.class)
public abstract class DynamicFpsMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$limit(CallbackInfoReturnable<Integer> cir) {
        int limit = DynamicFps.limit((Minecraft) (Object) this);
        if (limit > 0) cir.setReturnValue(limit);
    }
}

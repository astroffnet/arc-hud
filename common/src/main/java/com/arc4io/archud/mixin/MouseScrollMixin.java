package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.ArcCamera;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Alt + колесо мыши — расстояние камеры Arc. */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$scroll(long window, double x, double y, CallbackInfo ci) {
        if (net.minecraft.client.Minecraft.getInstance().screen != null) return;
        if (com.arc4io.archud.client.extras.Extras.zoomScroll(y) || ArcCamera.scroll(y)) ci.cancel();
    }
}

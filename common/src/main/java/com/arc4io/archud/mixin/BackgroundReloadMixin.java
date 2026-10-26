package com.arc4io.archud.mixin;

import com.arc4io.archud.client.resources.BackgroundReload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Фоновая перезагрузка ресурсов: экран загрузки не ставится — перезагрузку ведёт BackgroundReload. */
@Mixin(Minecraft.class)
public abstract class BackgroundReloadMixin {
    @Inject(method = "setOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$background(Overlay overlay, CallbackInfo ci) {
        if (overlay != null && BackgroundReload.take(overlay)) ci.cancel();
    }
}

package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.FirstPersonCam;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Бодикам: без прицела (как у настоящей нагрудной камеры). */
@Mixin(Gui.class)
public abstract class GuiCrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$crosshair(GuiGraphics g, CallbackInfo ci) {
        if (FirstPersonCam.hideCrosshair()) ci.cancel();
    }
}

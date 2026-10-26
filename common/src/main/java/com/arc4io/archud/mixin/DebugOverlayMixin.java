package com.arc4io.archud.mixin;

import com.arc4io.archud.client.hud.ArcDebugScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Своё меню F3 (графики Shift/Alt+F3 — ванильные). */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$render(GuiGraphics g, CallbackInfo ci) {
        if (!ArcDebugScreen.enabled()) return;
        ArcDebugScreen.render(g);
        ci.cancel();
    }
}

package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.GuiBatch;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Окна Arc HUD рисуются пакетно (см. GuiBatchMixin): меньше вызовов отрисовки — выше FPS при открытом окне. */
@Mixin(Screen.class)
public abstract class ScreenBatchMixin {
    @Inject(method = "renderWithTooltip", at = @At("HEAD"), require = 0)
    private void archud$begin(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (GuiBatch.allowed((Screen) (Object) this)) ((GuiGraphicsAccessor) g).archud$setManaged(true);
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"), require = 0)
    private void archud$end(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        GuiGraphicsAccessor a = (GuiGraphicsAccessor) g;
        if (a.archud$managed()) {
            g.flush();
            a.archud$setManaged(false);
        }
    }
}

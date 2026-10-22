package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Фон Arc вместо земляного во всех меню без мира (одиночная игра, сетевая игра, создание мира и их подменю). */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$background(GuiGraphics g, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        Screen self = (Screen) (Object) this;
        ArcWidgets.drawBackground(g, self.width, self.height);
        ci.cancel();
    }
}

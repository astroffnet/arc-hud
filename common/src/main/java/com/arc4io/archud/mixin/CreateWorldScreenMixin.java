package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Экран создания мира переопределяет фон («светлая земля») — тоже заменяем на фон Arc. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$background(GuiGraphics g, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        ArcWidgets.drawBackground(g, self.width, self.height);
        ci.cancel();
    }
}

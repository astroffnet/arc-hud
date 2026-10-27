package com.arc4io.archud.mixin;

import com.arc4io.archud.client.resources.ArcLoadingCover;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Экран перезагрузки ресурсов в стиле Arc HUD поверх красного экрана Mojang (логика загрузки — ванильная). */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayStyleMixin {
    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void archud$cover(GuiGraphics g, int mouseX, int mouseY, float pt, CallbackInfo ci) {
        ArcLoadingCover.draw((LoadingOverlay) (Object) this, g);
    }
}

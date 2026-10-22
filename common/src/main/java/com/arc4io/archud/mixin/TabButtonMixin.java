package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.TabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Вкладки (экран создания мира и др.) в стиле Arc. */
@Mixin(TabButton.class)
public abstract class TabButtonMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$render(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        TabButton self = (TabButton) (Object) this;
        ArcWidgets.drawTab(g, self, self.getMessage(), self.isSelected());
        ci.cancel();
    }
}

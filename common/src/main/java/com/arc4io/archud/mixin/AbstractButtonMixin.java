package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Кнопки (Button, CycleButton и все, кто не переопределяет отрисовку) в стиле Arc. */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void archud$render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        AbstractButton self = (AbstractButton) (Object) this;
        ArcWidgets.drawButton(graphics, self, self.getMessage(), false);
        ci.cancel();
    }
}

package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Слайдеры (громкость, FOV, яркость и т.д.) в стиле Arc. */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin {
    @Shadow
    protected double value;

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void archud$render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        AbstractSliderButton self = (AbstractSliderButton) (Object) this;
        ArcWidgets.drawSlider(graphics, self, self.getMessage(), value);
        ci.cancel();
    }
}

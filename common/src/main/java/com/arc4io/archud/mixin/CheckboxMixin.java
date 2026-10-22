package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Checkbox.class)
public abstract class CheckboxMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$render(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        Checkbox self = (Checkbox) (Object) this;
        ArcWidgets.drawCheckbox(g, self, self.getMessage(), self.selected(), ((CheckboxAccessor) self).archud$showLabel());
        ci.cancel();
    }
}

package com.arc4io.archud.mixin;

import com.arc4io.archud.client.extras.TooltipWrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Подсказки не уходят за экран (как TooltipFix): длинные строки переносятся по ширине свободного места. */
@Mixin(GuiGraphics.class)
public abstract class TooltipWrapMixin {
    @Inject(method = "renderTooltipInternal", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$wrap(Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner, CallbackInfo ci) {
        GuiGraphics self = (GuiGraphics) (Object) this;
        List<ClientTooltipComponent> wrapped = TooltipWrap.wrap(font, lines, x, self.guiWidth());
        if (wrapped == null) return;
        ci.cancel();
        TooltipWrap.inside = true;
        try {
            ((GuiGraphicsInvoker) self).archud$renderTooltipInternal(font, wrapped, x, y, positioner);
        } finally {
            TooltipWrap.inside = false;
        }
    }
}

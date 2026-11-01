package com.arc4io.archud.mixin;

import com.arc4io.archud.client.recipes.TooltipStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Запоминает предмет, чью подсказку только что нарисовали (для клавиш R/U над любым предметом). */
@Mixin(GuiGraphics.class)
public abstract class TooltipStackMixin {
    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), require = 0)
    private void archud$tooltipStack(Font font, ItemStack stack, int x, int y, CallbackInfo ci) {
        TooltipStack.record(stack);
    }
}

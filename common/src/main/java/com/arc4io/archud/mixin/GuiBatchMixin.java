package com.arc4io.archud.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пакетная отрисовка интерфейса. В 1.20.1 каждый fill/drawString сразу отправляется на видеокарту (отдельный
 * вызов отрисовки) — в окнах с сотнями плашек это тысячи вызовов за кадр и просадка FPS. В «управляемом» режиме
 * GuiGraphics копит геометрию; чтобы не нарушить порядок наложения, перед каждой текстурой (blit, рисуется сразу)
 * накопленное сбрасывается.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiBatchMixin {
    private boolean archud$managed() {
        return ((GuiGraphicsAccessor) (Object) this).archud$managed();
    }

    @Inject(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V", at = @At("HEAD"), require = 0)
    private void archud$flushBeforeBlit(CallbackInfo ci) {
        if (archud$managed()) ((GuiGraphics) (Object) this).flush();
    }

    @Inject(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFFFFFF)V", at = @At("HEAD"), require = 0)
    private void archud$flushBeforeBlitColored(CallbackInfo ci) {
        if (archud$managed()) ((GuiGraphics) (Object) this).flush();
    }
}

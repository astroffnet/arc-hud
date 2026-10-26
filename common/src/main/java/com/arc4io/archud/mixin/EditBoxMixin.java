package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Поля ввода в стиле Arc: вместо белой рамки и чёрной заливки — стеклянная панель с акцентом при фокусе. */
@Mixin(EditBox.class)
public abstract class EditBoxMixin {
    @Redirect(method = "renderWidget", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void archud$fill(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (!ArcWidgets.enabled()) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        if (color == 0xFF000000) return; // внутренняя заливка — уже нарисована вместе с рамкой
        ArcWidgets.drawEditBox(g, (EditBox) (Object) this, x1, y1, x2, y2);
    }

    /** Подсказка в пустом поле не выходит за его рамку (обрезается с «…» под ширину поля). */
    @Redirect(method = "renderWidget", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
    private int archud$hint(GuiGraphics g, net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text, int x, int y, int color) {
        EditBox self = (EditBox) (Object) this;
        int max = self.getX() + self.getWidth() - 4 - x;
        if (max > 8 && font.width(text) > max) {
            String s = font.plainSubstrByWidth(text.getString(), max - font.width("…")) + "…";
            return g.drawString(font, s, x, y, color);
        }
        return g.drawString(font, text, x, y, color);
    }
}

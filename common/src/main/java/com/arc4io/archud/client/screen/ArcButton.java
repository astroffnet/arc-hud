package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Кнопка в стиле Arc. Два варианта:
 *  - обычная (по центру, стеклянная);
 *  - menu (для главного экрана: текст слева, акцентная полоса растёт при наведении, появление «выездом»).
 */
public class ArcButton extends AbstractButton {
    private final Consumer<ArcButton> action;
    private float hover;
    private boolean menuStyle;
    private int appearDelay;
    private long created = Util.getMillis();
    private int icon = -1;
    private boolean danger;

    public ArcButton(int x, int y, int w, int h, Component message, Consumer<ArcButton> action) {
        super(x, y, w, h, message);
        this.action = action;
    }

    public ArcButton menu(int appearDelayMs) {
        this.menuStyle = true;
        this.appearDelay = appearDelayMs;
        this.created = Util.getMillis();
        return this;
    }

    public ArcButton icon(int iconIndex) {
        this.icon = iconIndex;
        return this;
    }

    public ArcButton danger() {
        this.danger = true;
        return this;
    }

    @Override
    public void onPress() {
        action.accept(this);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean hov = active && isHoveredOrFocused();
        hover += ((hov ? 1F : 0F) - hover) * 0.25F;
        int accent = danger ? 0xFFFF5252 : Theme.accent();
        int x = getX(), y = getY(), w = width, h = height;

        if (menuStyle) {
            float t = Draw.easeOut((Util.getMillis() - created - appearDelay) / 450F);
            if (t <= 0) return;
            int off = Math.round((1F - t) * -40F);
            int a = (int) (255 * t);
            g.pose().pushPose();
            g.pose().translate(off, 0, 0);
            Draw.hGradient(g, x, y, x + w, y + h, Draw.alpha(accent, (int) (0x10 + 0x60 * hover)), Draw.alpha(accent, 0));
            int barW = 2 + Math.round(4 * hover);
            g.fill(x, y + 2, x + barW, y + h - 2, Draw.alpha(accent, active ? a : a / 3));
            int tx = x + 12 + Math.round(6 * hover);
            if (icon >= 0) {
                Draw.icon(g, icon, tx, y + (h - 9) / 2);
                tx += 13;
            }
            int color = !active ? 0xFF6A7380 : Draw.mix(0xFFDCE6F0, 0xFFFFFFFF, hover);
            g.drawString(font, getMessage(), tx, y + (h - 8) / 2, Draw.alpha(color, Math.max(8, a)), true);
            g.pose().popPose();
            return;
        }

        int bg = Draw.mix(Draw.PANEL, Draw.PANEL_LIGHT, hover);
        int border = !active ? 0x40FFFFFF : Draw.mix(Draw.alpha(accent, 0x60), Draw.alpha(accent, 0xFF), hover);
        Draw.panel(g, x, y, w, h, bg, border);
        if (hover > 0.01F) g.fill(x + 2, y + h - 2, x + 2 + Math.round((w - 4) * hover), y + h - 1, Draw.alpha(accent, 0xFF));
        int color = !active ? 0xFF6A7380 : Draw.mix(0xFFDCE6F0, 0xFFFFFFFF, hover);
        int textW = font.width(getMessage());
        int iconW = icon >= 0 ? 12 : 0;
        int room = w - 6 - iconW;
        if (textW > room && room > 0) {
            // длинная подпись: уменьшить шрифт (до 60%), а если и так не влезает — обрезать с «…»
            float s = Math.max(0.6F, room / (float) textW);
            String text = getMessage().getString();
            if (textW * s > room) text = font.plainSubstrByWidth(text, (int) (room / s) - font.width("…")) + "…";
            float tw = font.width(text) * s;
            float tx = x + (w - tw - iconW) / 2F;
            if (icon >= 0) {
                Draw.icon(g, icon, Math.round(tx), y + (h - 9) / 2);
                tx += iconW;
            }
            g.pose().pushPose();
            g.pose().translate(tx, y + (h - 8 * s) / 2F, 0);
            g.pose().scale(s, s, 1F);
            g.drawString(font, text, 0, 0, color, true);
            g.pose().popPose();
            return;
        }
        int tx = x + (w - textW) / 2;
        if (icon >= 0) {
            tx -= 6;
            Draw.icon(g, icon, tx, y + (h - 9) / 2);
            tx += 12;
        }
        g.drawString(font, getMessage(), tx, y + (h - 8) / 2, color, true);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}

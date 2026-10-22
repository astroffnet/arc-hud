package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Базовый экран Arc: центральная стеклянная панель, заголовок, кнопка «Готово», возврат к родителю.
 */
public abstract class ArcScreen extends Screen {
    protected final Screen parent;
    protected int px, py, pw, ph;

    protected ArcScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected void layout(int maxW, int maxH) {
        pw = Math.min(width - 16, maxW);
        ph = Math.min(height - 16, maxH);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    protected void frame(GuiGraphics g) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawString(font, title, px + 10, py + 8, Draw.TEXT, true);
        g.fill(px + 8, py + 20, px + pw - 8, py + 21, Draw.alpha(accent, 0x40));
    }

    protected ArcButton addDone() {
        return addRenderableWidget(new ArcButton(px + pw - 108, py + ph - 24, 100, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    protected ArcButton addBack() {
        return addRenderableWidget(new ArcButton(px + 8, py + ph - 24, 100, 18, Component.translatable("gui.back"), b -> onClose()));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}

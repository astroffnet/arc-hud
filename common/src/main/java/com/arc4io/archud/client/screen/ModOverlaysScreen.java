package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.ArcHudApi;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Список HUD-элементов других модов (Forge-оверлеи) с возможностью скрыть любой.
 * Элементы, которые Arc HUD уже рисует в своём стиле, помечены «в Arc HUD».
 */
public class ModOverlaysScreen extends Screen implements com.arc4io.archud.client.screen.ScreenFit.Fits {
    private static final int ROW_H = 18;
    private final Screen parent;
    private final List<String> ids = new ArrayList<>();
    private double scroll;
    private int px, py, pw, ph, listY, listH;

    public ModOverlaysScreen(Screen parent) {
        super(Component.translatable("archud.overlays.title"));
        this.parent = parent;
        ids.addAll(ArcClient.platform().hudOverlayIds());
    }

    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 360, 240);
        pw = Math.min(width - 20, 420);
        ph = height - 30;
        px = (width - pw) / 2;
        py = 15;
        listY = py + 34;
        listH = ph - 34 - 30;
        addRenderableWidget(new ArcButton(px + pw - 126, py + ph - 24, 120, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawString(font, title, px + 10, py + 8, Draw.TEXT, true);
        Draw.small(g, font, Component.translatable("archud.overlays.hint").getString(), px + 10, py + 20, Draw.TEXT_DIM, 0.75F);

        if (ids.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("archud.overlays.empty"), px + pw / 2, listY + 20, Draw.TEXT_DIM);
        }
        double max = Math.max(0, ids.size() * ROW_H - listH);
        scroll = Mth.clamp(scroll, 0, max);
        g.enableScissor(px + 4, listY, px + pw - 4, listY + listH);
        int y = listY - (int) scroll;
        for (String id : ids) {
            boolean hidden = ArcConfig.get().hiddenOverlays.contains(id);
            boolean replaced = ArcHudApi.isReplaced(id);
            boolean hov = mouseX >= px + 6 && mouseX < px + pw - 6 && mouseY >= y && mouseY < y + ROW_H - 2 && mouseY >= listY && mouseY < listY + listH;
            Draw.panel(g, px + 6, y, pw - 12, ROW_H - 2, hov ? Draw.PANEL_LIGHT : Draw.PANEL, hidden ? 0x50FF5252 : Draw.alpha(accent, 0x50));
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
            g.drawString(font, id, px + 12, y + 4, hidden ? 0xFF6A7380 : Draw.TEXT, true);
            String mod = ArcHud.platform().modName(ns);
            Draw.small(g, font, mod, px + 16 + font.width(id), y + 5, 0xFF7FA7C9, 0.6F);
            Component state = replaced ? Component.translatable("archud.overlays.replaced")
                    : hidden ? Component.translatable("archud.overlays.hidden")
                    : Component.translatable("archud.overlays.shown");
            int sc = replaced ? Draw.alpha(accent, 0xFF) : hidden ? 0xFFFF8A80 : 0xFF6BFF7A;
            g.drawString(font, state, px + pw - 12 - font.width(state), y + 4, sc, true);
            y += ROW_H;
        }
        g.disableScissor();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= px + 6 && mx < px + pw - 6 && my >= listY && my < listY + listH) {
            int idx = (int) ((my - listY + scroll) / ROW_H);
            if (idx >= 0 && idx < ids.size()) {
                String id = ids.get(idx);
                List<String> hidden = ArcConfig.get().hiddenOverlays;
                if (!hidden.remove(id)) hidden.add(id);
                ArcConfig.save();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= delta * ROW_H * 2;
        return true;
    }

    @Override
    public void onClose() {
        ArcConfig.save();
        minecraft.setScreen(parent);
    }
}

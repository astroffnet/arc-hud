package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Выбор языка: поиск, сетка в две колонки, текущий язык подсвечен.
 */
public class ArcLanguageScreen extends ArcScreen {
    private static final int ROW = 18;
    private final Options options;
    private final List<Map.Entry<String, LanguageInfo>> all = new ArrayList<>();
    private final List<Map.Entry<String, LanguageInfo>> shown = new ArrayList<>();
    private EditBox search;
    private String selected;
    private double scroll;
    private int listY, listH;

    public ArcLanguageScreen(Screen parent, Options options) {
        super(Component.translatable("options.language.title"), parent);
        this.options = options;
    }

    @Override
    protected void init() {
        layout(420, height - 20);
        LanguageManager lm = minecraft.getLanguageManager();
        if (selected == null) selected = lm.getSelected();
        all.clear();
        all.addAll(lm.getLanguages().entrySet());
        search = addRenderableWidget(new EditBox(font, px + 10, py + 26, pw - 20, 16, Component.translatable("archud.controls.search")));
        search.setHint(Component.translatable("archud.controls.search"));
        search.setResponder(s -> refresh());
        listY = py + 48;
        listH = ph - 48 - 54;
        addRenderableWidget(options.forceUnicodeFont().createButton(options, px + 10, py + ph - 48, pw - 20));
        addRenderableWidget(new ArcButton(px + 8, py + ph - 24, 120, 18, Component.translatable("gui.cancel"), b -> onClose()));
        addRenderableWidget(new ArcButton(px + pw - 128, py + ph - 24, 120, 18, Component.translatable("gui.done"), b -> apply()));
        refresh();
    }

    private void refresh() {
        shown.clear();
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, LanguageInfo> e : all) {
            if (q.isEmpty() || e.getKey().contains(q) || e.getValue().toComponent().getString().toLowerCase(Locale.ROOT).contains(q)) shown.add(e);
        }
    }

    private void apply() {
        LanguageManager lm = minecraft.getLanguageManager();
        if (selected != null && !selected.equals(lm.getSelected())) {
            lm.setSelected(selected);
            options.languageCode = selected;
            minecraft.reloadResourcePacks();
            options.save();
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        int colW = (pw - 26) / 2;
        int rows = (shown.size() + 1) / 2;
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows * ROW - listH));
        g.enableScissor(px + 8, listY, px + pw - 8, listY + listH);
        for (int i = 0; i < shown.size(); i++) {
            int col = i % 2, row = i / 2;
            int x = px + 10 + col * (colW + 6);
            int y = listY + row * ROW - (int) scroll;
            if (y + ROW < listY || y > listY + listH) continue;
            Map.Entry<String, LanguageInfo> e = shown.get(i);
            boolean sel = e.getKey().equals(selected);
            boolean hov = mouseX >= x && mouseX < x + colW && mouseY >= y && mouseY < y + ROW - 2 && mouseY >= listY && mouseY < listY + listH;
            Draw.panel(g, x, y, colW, ROW - 2, sel ? Draw.alpha(accent, 0x50) : hov ? Draw.PANEL_LIGHT : Draw.PANEL,
                    sel ? Draw.alpha(accent, 0xFF) : 0x30FFFFFF);
            String name = e.getValue().toComponent().getString();
            if (font.width(name) > colW - 40) name = font.plainSubstrByWidth(name, colW - 44) + "…";
            g.drawString(font, name, x + 6, y + 4, sel ? 0xFFFFFFFF : 0xFFCFD8DC, true);
            Draw.small(g, font, e.getKey(), x + colW - 6 - font.width(e.getKey()) * 0.6F, y + 5, Draw.TEXT_DIM, 0.6F);
        }
        g.disableScissor();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (my >= listY && my < listY + listH) {
            int colW = (pw - 26) / 2;
            int col = mx < px + 10 + colW + 3 ? 0 : 1;
            int row = (int) ((my - listY + scroll) / ROW);
            int idx = row * 2 + col;
            if (idx >= 0 && idx < shown.size()) {
                selected = shown.get(idx).getKey();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= delta * ROW * 2;
        return true;
    }
}

package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Новое окно назначения клавиш: категории слева, поиск, список с подсветкой конфликтов,
 * назначение клавиш клавиатуры и кнопок мыши, сброс по одной и всех сразу.
 * Работает с клавишами всех модов (берёт их из Options.keyMappings).
 */
public class ArcControlsScreen extends Screen {
    private static final int ROW_H = 20;
    private final Screen parent;
    private final Options options;
    private final List<KeyMapping> all = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final List<KeyMapping> visible = new ArrayList<>();
    private String category; // null = все
    private EditBox search;
    private KeyMapping listening;
    private double scroll;
    private int px, py, pw, ph, listX, listY, listW, listH, catW;

    public ArcControlsScreen(Screen parent, Options options) {
        super(Component.translatable("controls.keybinds.title"));
        this.parent = parent;
        this.options = options;
    }

    @Override
    protected void init() {
        all.clear();
        KeyMapping[] maps = Arrays.copyOf(options.keyMappings, options.keyMappings.length);
        Arrays.sort(maps);
        all.addAll(Arrays.asList(maps));
        Set<String> cats = new LinkedHashSet<>();
        for (KeyMapping k : all) cats.add(k.getCategory());
        categories.clear();
        categories.addAll(cats);

        pw = Math.min(width - 20, 520);
        ph = height - 30;
        px = (width - pw) / 2;
        py = 15;
        catW = Math.min(140, pw / 3);
        listX = px + catW + 8;
        listY = py + 46;
        listW = pw - catW - 14;
        listH = ph - 46 - 30;

        String prev = search != null ? search.getValue() : "";
        search = new EditBox(font, listX, py + 24, listW, 16, Component.translatable("archud.controls.search"));
        search.setHint(Component.translatable("archud.controls.search").withStyle(s -> s.withColor(0x6A7380)));
        search.setValue(prev);
        search.setResponder(s -> {
            scroll = 0;
            refresh();
        });
        addRenderableWidget(search);

        int bw = 120;
        addRenderableWidget(new ArcButton(px + pw - bw * 2 - 12, py + ph - 24, bw, 18, Component.translatable("controls.resetAll"), b -> {
            for (KeyMapping k : all) options.setKey(k, k.getDefaultKey());
            KeyMapping.resetMapping();
        }).danger());
        addRenderableWidget(new ArcButton(px + pw - bw - 6, py + ph - 24, bw, 18, Component.translatable("gui.done"), b -> onClose()));
        refresh();
    }

    private void refresh() {
        visible.clear();
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        for (KeyMapping k : all) {
            if (category != null && !category.equals(k.getCategory())) continue;
            if (!q.isEmpty()) {
                String name = Component.translatable(k.getName()).getString().toLowerCase(Locale.ROOT);
                String key = k.getTranslatedKeyMessage().getString().toLowerCase(Locale.ROOT);
                if (!name.contains(q) && !key.contains(q)) continue;
            }
            visible.add(k);
        }
    }

    private Map<String, Integer> keyUsage() {
        Map<String, Integer> m = new HashMap<>();
        for (KeyMapping k : all) {
            if (k.isUnbound()) continue;
            m.merge(k.saveString(), 1, Integer::sum);
        }
        return m;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawString(font, title, px + 10, py + 8, Draw.TEXT, true);
        Draw.small(g, font, "Arc HUD", px + pw - 40, py + 9, Draw.alpha(accent, 0xFF), 0.75F);

        // категории
        int cy = py + 24;
        cy = categoryRow(g, null, Component.translatable("archud.controls.all"), cy, mouseX, mouseY, accent);
        for (String c : categories) {
            if (cy > py + ph - 34) break;
            cy = categoryRow(g, c, Component.translatable(c), cy, mouseX, mouseY, accent);
        }
        g.fill(px + catW + 3, py + 22, px + catW + 4, py + ph - 30, 0x30FFFFFF);

        // список
        Map<String, Integer> usage = keyUsage();
        int content = visible.size() * ROW_H;
        double maxScroll = Math.max(0, content - listH);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        String lastCat = null;
        int y = listY - (int) scroll;
        for (KeyMapping k : visible) {
            if (y + ROW_H >= listY && y <= listY + listH) {
                boolean hov = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H && mouseY >= listY && mouseY < listY + listH;
                if (hov) g.fill(listX, y, listX + listW, y + ROW_H - 1, 0x18FFFFFF);
                if (category == null && !k.getCategory().equals(lastCat)) {
                    g.fill(listX, y, listX + 2, y + ROW_H - 1, Draw.alpha(accent, 0xAA));
                }
                String name = Component.translatable(k.getName()).getString();
                int nameMax = listW - 140;
                if (font.width(name) > nameMax) name = font.plainSubstrByWidth(name, nameMax - 6) + "…";
                g.drawString(font, name, listX + 6, y + 6, Draw.TEXT, true);

                // кнопка клавиши
                int kx = listX + listW - 122, kw = 96;
                boolean conflict = !k.isUnbound() && usage.getOrDefault(k.saveString(), 0) > 1;
                boolean isListening = k == listening;
                boolean khov = mouseX >= kx && mouseX < kx + kw && mouseY >= y + 2 && mouseY < y + ROW_H - 2;
                int border = isListening ? Draw.mix(Draw.alpha(accent, 0x80), 0xFFFFFFFF, Draw.pulse(700))
                        : conflict ? 0xFFFF5252 : khov ? Draw.alpha(accent, 0xFF) : Draw.alpha(accent, 0x50);
                Draw.panel(g, kx, y + 2, kw, ROW_H - 4, khov ? Draw.PANEL_LIGHT : Draw.PANEL, border);
                Component keyText = isListening ? Component.literal("> ").append(k.getTranslatedKeyMessage()).append(" <")
                        : k.getTranslatedKeyMessage();
                int color = isListening ? 0xFFFFE082 : conflict ? 0xFFFF8A80 : k.isUnbound() ? 0xFF6A7380 : Draw.TEXT;
                String kt = keyText.getString();
                if (font.width(kt) > kw - 6) kt = font.plainSubstrByWidth(kt, kw - 10) + "…";
                g.drawCenteredString(font, kt, kx + kw / 2, y + 6, color);

                // сброс
                int rx = listX + listW - 22;
                boolean def = k.isDefault();
                boolean rhov = !def && mouseX >= rx && mouseX < rx + 18 && mouseY >= y + 2 && mouseY < y + ROW_H - 2;
                Draw.panel(g, rx, y + 2, 18, ROW_H - 4, rhov ? Draw.PANEL_LIGHT : Draw.PANEL, def ? 0x30FFFFFF : Draw.alpha(accent, 0x80));
                g.drawCenteredString(font, "↺", rx + 9, y + 6, def ? 0xFF4A525C : Draw.TEXT);
            }
            lastCat = k.getCategory();
            y += ROW_H;
        }
        g.disableScissor();

        // полоса прокрутки
        if (maxScroll > 0) {
            int barH = Math.max(16, (int) (listH * (listH / (double) content)));
            int barY = listY + (int) ((listH - barH) * (scroll / maxScroll));
            g.fill(listX + listW + 2, listY, listX + listW + 4, listY + listH, 0x30FFFFFF);
            g.fill(listX + listW + 2, barY, listX + listW + 4, barY + barH, Draw.alpha(accent, 0xCC));
        }

        if (listening != null) {
            Component hint = Component.translatable("archud.controls.listening");
            g.drawCenteredString(font, hint, px + pw / 2, py + ph - 20 - 20, 0xFFFFE082);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int categoryRow(GuiGraphics g, String cat, Component label, int y, int mx, int my, int accent) {
        boolean selected = cat == null ? category == null : cat.equals(category);
        boolean hov = mx >= px + 4 && mx < px + catW && my >= y && my < y + 16;
        if (selected) {
            g.fill(px + 4, y, px + catW, y + 16, Draw.alpha(accent, 0x40));
            g.fill(px + 4, y, px + 6, y + 16, Draw.alpha(accent, 0xFF));
        } else if (hov) {
            g.fill(px + 4, y, px + catW, y + 16, 0x18FFFFFF);
        }
        String s = label.getString();
        if (font.width(s) > catW - 14) s = font.plainSubstrByWidth(s, catW - 18) + "…";
        g.drawString(font, s, px + 10, y + 4, selected ? 0xFFFFFFFF : 0xFFB0BEC5, true);
        return y + 17;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (listening != null) {
            options.setKey(listening, InputConstants.Type.MOUSE.getOrCreate(button));
            listening = null;
            KeyMapping.resetMapping();
            return true;
        }
        // категории
        int cy = py + 24;
        if (mx >= px + 4 && mx < px + catW) {
            if (my >= cy && my < cy + 16) {
                category = null;
                scroll = 0;
                refresh();
                return true;
            }
            cy += 17;
            for (String c : categories) {
                if (my >= cy && my < cy + 16) {
                    category = c;
                    scroll = 0;
                    refresh();
                    return true;
                }
                cy += 17;
            }
        }
        // строки
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int idx = (int) ((my - listY + scroll) / ROW_H);
            if (idx >= 0 && idx < visible.size()) {
                KeyMapping k = visible.get(idx);
                int rowY = listY + idx * ROW_H - (int) scroll;
                int kx = listX + listW - 122;
                int rx = listX + listW - 22;
                if (mx >= kx && mx < kx + 96 && my >= rowY + 2 && my < rowY + ROW_H - 2) {
                    listening = k;
                    return true;
                }
                if (mx >= rx && mx < rx + 18 && !k.isDefault()) {
                    options.setKey(k, k.getDefaultKey());
                    KeyMapping.resetMapping();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            if (keyCode == InputConstants.KEY_ESCAPE) options.setKey(listening, InputConstants.UNKNOWN);
            else options.setKey(listening, InputConstants.getKey(keyCode, scanCode));
            listening = null;
            KeyMapping.resetMapping();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= delta * ROW_H * 2;
        return true;
    }

    @Override
    public void onClose() {
        options.save();
        minecraft.setScreen(parent);
    }
}

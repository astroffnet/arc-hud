package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.HudScale;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.config.HudAnchor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Расстановка блоков HUD по краям экрана. Экран прозрачный — в мире HUD видно вживую:
 * выбираешь блок в центре, кликаешь по нужной зоне экрана.
 */
public class HudLayoutScreen extends Screen implements com.arc4io.archud.client.screen.ScreenFit.Fits {
    private final Screen parent;
    /** Блок HUD в списке: поле зоны из конфига, панель отдельного мода или хотбар (только размер). */
    private interface Element {
        Component name();

        String scaleId();

        HudAnchor anchor();

        void setAnchor(HudAnchor a);

        default String panelId() {
            return null;
        }
    }

    private final List<Element> elements = new ArrayList<>();
    /** Поле расположения -> id размера блока. */
    private static final java.util.Map<String, String> SCALE_IDS = java.util.Map.of(
            "minimapAnchor", "minimap", "infoPanelAnchor", "info", "compassAnchor", "compass", "effectsAnchor", "effects",
            "gearAnchor", "gear", "pickupsAnchor", "pickups", "statusAnchor", "status", "addonPanelsAnchor", "addons");
    private int selected;
    private int scroll, visibleRows;
    private int px, py, pw, ph;
    private ArcButton hideBtn, sharedBtn;

    public HudLayoutScreen(Screen parent) {
        super(Component.translatable("archud.layout.title"));
        this.parent = parent;
        for (Field f : ArcConfig.class.getFields()) {
            if (f.getType() != HudAnchor.class) continue;
            elements.add(fieldElement(f));
            if (f.getName().equals("addonPanelsAnchor")) {
                // сразу за общей зоной — каждая панель мода отдельно (только моды, что установлены и добавили панель)
                for (com.arc4io.archud.client.hud.ArcHudApi.Panel p : com.arc4io.archud.client.hud.ArcHudApi.panels()) elements.add(panelElement(p));
            }
        }
        elements.add(new Element() { // хотбар и полосы — только размер
            public Component name() {
                return Component.translatable("archud.layout.bars");
            }

            public String scaleId() {
                return "bars";
            }

            public HudAnchor anchor() {
                return null;
            }

            public void setAnchor(HudAnchor a) {
            }
        });
    }

    private static Element fieldElement(Field f) {
        return new Element() {
            public Component name() {
                return Component.translatable("archud.config." + f.getName());
            }

            public String scaleId() {
                return SCALE_IDS.getOrDefault(f.getName(), f.getName());
            }

            public HudAnchor anchor() {
                try {
                    return (HudAnchor) f.get(ArcConfig.get());
                } catch (IllegalAccessException e) {
                    return HudAnchor.TOP_LEFT;
                }
            }

            public void setAnchor(HudAnchor a) {
                try {
                    f.set(ArcConfig.get(), a);
                } catch (IllegalAccessException ignored) {
                }
            }
        };
    }

    private static Element panelElement(com.arc4io.archud.client.hud.ArcHudApi.Panel p) {
        String id = p.id().toString();
        return new Element() {
            public Component name() {
                return Component.literal(" ▸ ").append(p.title());
            }

            public String scaleId() {
                return "addon:" + id;
            }

            public HudAnchor anchor() {
                return ArcConfig.get().panelAnchors.getOrDefault(id, ArcConfig.get().addonPanelsAnchor);
            }

            public void setAnchor(HudAnchor a) {
                ArcConfig.get().panelAnchors.put(id, a);
            }

            public String panelId() {
                return id;
            }
        };
    }

    static Component anchorName(HudAnchor a) {
        return Component.translatable("archud.anchor." + a.name().toLowerCase(java.util.Locale.ROOT));
    }

    private Element sel() {
        return elements.isEmpty() ? null : elements.get(Math.max(0, Math.min(selected, elements.size() - 1)));
    }

    private static boolean hidden(Element e) {
        return e != null && e.panelId() != null && ArcConfig.get().hiddenPanels.contains(e.panelId());
    }

    private void changeScale(int dir) {
        Element e = sel();
        if (e == null) return;
        HudScale.set(e.scaleId(), HudScale.get(e.scaleId()) + dir * HudScale.STEP);
        ArcConfig.save();
    }

    private void updateButtons() {
        Element e = sel();
        boolean panel = e != null && e.panelId() != null;
        if (hideBtn != null) {
            hideBtn.active = panel;
            hideBtn.setMessage(Component.translatable(hidden(e) ? "archud.layout.show" : "archud.layout.hide"));
        }
        if (sharedBtn != null) sharedBtn.active = panel && ArcConfig.get().panelAnchors.containsKey(e.panelId());
    }

    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 360, 240);
        pw = Math.max(180, Math.min(230, width / 3 - 8));
        visibleRows = Math.max(3, Math.min(elements.size(), (height - 8 - 102) / 16));
        scroll = Math.max(0, Math.min(scroll, elements.size() - visibleRows));
        ph = visibleRows * 16 + 102;
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        int hw = (pw - 16) / 2;
        hideBtn = addRenderableWidget(new ArcButton(px + 6, py + ph - 72, hw, 16, Component.translatable("archud.layout.hide"), b -> {
            Element e = sel();
            if (e == null || e.panelId() == null) return;
            if (!ArcConfig.get().hiddenPanels.remove(e.panelId())) ArcConfig.get().hiddenPanels.add(e.panelId());
            ArcConfig.save();
            updateButtons();
        }));
        sharedBtn = addRenderableWidget(new ArcButton(px + pw - 6 - hw, py + ph - 72, hw, 16, Component.translatable("archud.layout.shared"), b -> {
            Element e = sel();
            if (e == null || e.panelId() == null) return;
            ArcConfig.get().panelAnchors.remove(e.panelId());
            ArcConfig.save();
            updateButtons();
        }));
        // размер выбранного блока: − / + / сброс
        int cy = py + ph - 42;
        addRenderableWidget(new ArcButton(px + 6, cy, 20, 16, Component.literal("−"), b -> changeScale(-1)));
        addRenderableWidget(new ArcButton(px + pw - 50, cy, 20, 16, Component.literal("+"), b -> changeScale(1)));
        addRenderableWidget(new ArcButton(px + pw - 26, cy, 20, 16, Component.literal("↺"), b -> {
            Element e = sel();
            if (e == null) return;
            HudScale.set(e.scaleId(), 1F);
            ArcConfig.save();
        }));
        addRenderableWidget(new ArcButton(px + 6, py + ph - 22, pw - 12, 16, Component.translatable("gui.done"), b -> onClose()));
        updateButtons();
    }

    private int[] zone(HudAnchor a) {
        int zw = width / 3, zh = height / 3;
        return new int[]{a.col * zw + 2, a.row * zh + 2, zw - 4, zh - 4};
    }

    private HudAnchor zoneAt(double mx, double my) {
        int col = (int) (mx / (width / 3.0));
        int row = (int) (my / (height / 3.0));
        return HudAnchor.at(Math.min(2, col), Math.min(2, row));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int accent = Theme.accent();
        boolean overPanel = mouseX >= px && mouseX < px + pw && mouseY >= py && mouseY < py + ph;
        HudAnchor hover = overPanel ? null : zoneAt(mouseX, mouseY);
        Element selEl = sel();
        HudAnchor current = selEl == null ? null : selEl.anchor();

        for (HudAnchor a : HudAnchor.values()) {
            int[] z = zone(a);
            boolean isCurrent = a == current;
            boolean isHover = a == hover;
            int fill = isHover ? Draw.alpha(accent, 0x38) : isCurrent ? Draw.alpha(accent, 0x22) : 0x14FFFFFF;
            g.fill(z[0], z[1], z[0] + z[2], z[1] + z[3], fill);
            dashed(g, z[0], z[1], z[2], z[3], isCurrent || isHover ? Draw.alpha(accent, 0xFF) : 0x60FFFFFF);
            // подписи блоков в этой зоне
            int ty = a.row == 2 ? z[1] + z[3] - 12 : z[1] + 4;
            int step = a.row == 2 ? -10 : 10;
            g.drawCenteredString(font, anchorName(a), z[0] + z[2] / 2, a.row == 1 ? z[1] + z[3] / 2 - 4 : z[1] + z[3] / 2 - 4, isHover ? 0xFFFFFFFF : 0x90FFFFFF);
            for (Element f : elements) {
                if (f.anchor() != a) continue;
                if (f.panelId() != null && !ArcConfig.get().panelAnchors.containsKey(f.panelId()) && f != selEl) continue;
                String s = f.name().getString().trim() + (hidden(f) ? " ✕" : "");
                int col = f == selEl ? 0xFFFFE082 : hidden(f) ? 0xFF6E7A80 : 0xFFB0BEC5;
                int sx = a.col == 0 ? z[0] + 4 : a.col == 2 ? z[0] + z[2] - 4 - font.width(s) : z[0] + (z[2] - font.width(s)) / 2;
                g.drawString(font, s, sx, ty, col, true);
                ty += step;
            }
        }

        // панель выбора блока
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawCenteredString(font, title, px + pw / 2, py + 5, Draw.TEXT);
        Draw.smallCentered(g, font, Component.translatable("archud.layout.hint").getString(), px + pw / 2F, py + 16, Draw.TEXT_DIM, 0.6F);
        int y = py + 24;
        for (int i = scroll; i < Math.min(elements.size(), scroll + visibleRows); i++) {
            Element f = elements.get(i);
            boolean sel = i == selected;
            boolean hov = mouseX >= px + 4 && mouseX < px + pw - 4 && mouseY >= y && mouseY < y + 15;
            if (sel) g.fill(px + 4, y, px + pw - 4, y + 15, Draw.alpha(accent, 0x50));
            else if (hov) g.fill(px + 4, y, px + pw - 4, y + 15, 0x20FFFFFF);
            if (sel) g.fill(px + 4, y, px + 6, y + 15, Draw.alpha(accent, 0xFF));
            int pct = Math.round(HudScale.get(f.scaleId()) * 100);
            String an = hidden(f) ? Component.translatable("archud.layout.hidden").getString()
                    : (f.anchor() == null ? "" : anchorName(f.anchor()).getString() + " · ") + pct + "%";
            int anW = Math.round(font.width(an) * 0.6F);
            String nm = font.plainSubstrByWidth(f.name().getString(), pw - 22 - anW);
            g.drawString(font, nm, px + 10, y + 4, hidden(f) ? 0xFF7A868C : sel ? 0xFFFFFFFF : 0xFFCFD8DC, true);
            Draw.small(g, font, an, px + pw - 8 - anW, y + 5, Draw.alpha(accent, 0xFF), 0.6F);
            y += 16;
        }
        if (elements.size() > visibleRows) { // полоса прокрутки списка
            int trackH = visibleRows * 16 - 1;
            int thumbH = Math.max(8, trackH * visibleRows / elements.size());
            int thumbY = py + 24 + (trackH - thumbH) * scroll / Math.max(1, elements.size() - visibleRows);
            g.fill(px + pw - 4, py + 24, px + pw - 2, py + 24 + trackH, 0x30FFFFFF);
            g.fill(px + pw - 4, thumbY, px + pw - 2, thumbY + thumbH, Draw.alpha(accent, 0xC0));
        }
        // строка размера выбранного блока
        int cy = py + ph - 42;
        String sz = Component.translatable("archud.layout.size", Math.round(HudScale.get(selEl == null ? "bars" : selEl.scaleId()) * 100)).getString();
        g.drawCenteredString(font, sz, px + (pw - 24) / 2, cy + 4, 0xFFFFFFFF);
        Draw.smallCentered(g, font, Component.translatable("archud.layout.size_hint").getString(), px + pw / 2F, cy - 9, Draw.TEXT_DIM, 0.55F);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= px && mx < px + pw && my >= py + 24 && my < py + 24 + visibleRows * 16 && elements.size() > visibleRows) {
            scroll = Math.max(0, Math.min(elements.size() - visibleRows, scroll - (int) Math.signum(delta)));
            return true;
        }
        changeScale(delta > 0 ? 1 : -1);
        return true;
    }

    private static void dashed(GuiGraphics g, int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 6) {
            g.fill(x + i, y, x + Math.min(i + 3, w), y + 1, color);
            g.fill(x + i, y + h - 1, x + Math.min(i + 3, w), y + h, color);
        }
        for (int i = 0; i < h; i += 6) {
            g.fill(x, y + i, x + 1, y + Math.min(i + 3, h), color);
            g.fill(x + w - 1, y + i, x + w, y + Math.min(i + 3, h), color);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            int idx = (int) ((my - (py + 24)) / 16);
            if (my >= py + 24 && idx >= 0 && idx < visibleRows && scroll + idx < elements.size()) {
                selected = scroll + idx;
                updateButtons();
                return true;
            }
            return true;
        }
        HudAnchor a = zoneAt(mx, my);
        Element e = sel();
        if (a != null && e != null && e.anchor() != null) {
            e.setAnchor(a);
            ArcConfig.save();
            updateButtons();
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ArcConfig.save();
        minecraft.setScreen(parent);
    }
}

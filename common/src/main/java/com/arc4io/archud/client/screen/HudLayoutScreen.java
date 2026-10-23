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
    private final List<Field> elements = new ArrayList<>();
    /** Поле расположения -> id размера блока. Последняя строка (null) — хотбар и основные полосы. */
    private static final java.util.Map<String, String> SCALE_IDS = java.util.Map.of(
            "minimapAnchor", "minimap", "infoPanelAnchor", "info", "compassAnchor", "compass", "effectsAnchor", "effects",
            "gearAnchor", "gear", "pickupsAnchor", "pickups", "statusAnchor", "status", "addonPanelsAnchor", "addons");
    private int selected;
    private int px, py, pw, ph;

    public HudLayoutScreen(Screen parent) {
        super(Component.translatable("archud.layout.title"));
        this.parent = parent;
        for (Field f : ArcConfig.class.getFields()) {
            if (f.getType() == HudAnchor.class) elements.add(f);
        }
        elements.add(null); // хотбар и полосы — только размер
    }

    static Component anchorName(HudAnchor a) {
        return Component.translatable("archud.anchor." + a.name().toLowerCase(java.util.Locale.ROOT));
    }

    static Component elementName(Field f) {
        if (f == null) return Component.translatable("archud.layout.bars");
        return Component.translatable("archud.config." + f.getName());
    }

    private static String scaleId(Field f) {
        return f == null ? "bars" : SCALE_IDS.getOrDefault(f.getName(), f.getName());
    }

    private void changeScale(int dir) {
        if (elements.isEmpty()) return;
        String id = scaleId(elements.get(selected));
        HudScale.set(id, HudScale.get(id) + dir * HudScale.STEP);
        ArcConfig.save();
    }

    private HudAnchor get(Field f) {
        if (f == null) return null;
        try {
            return (HudAnchor) f.get(ArcConfig.get());
        } catch (IllegalAccessException e) {
            return HudAnchor.TOP_LEFT;
        }
    }

    private void set(Field f, HudAnchor a) {
        if (f == null) return;
        try {
            f.set(ArcConfig.get(), a);
            ArcConfig.save();
        } catch (IllegalAccessException ignored) {
        }
    }

    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 360, 240);
        pw = Math.max(170, Math.min(220, width / 3 - 8));
        ph = elements.size() * 16 + 78;
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        // размер выбранного блока: − / + / сброс
        int cy = py + ph - 42;
        addRenderableWidget(new ArcButton(px + 6, cy, 20, 16, Component.literal("−"), b -> changeScale(-1)));
        addRenderableWidget(new ArcButton(px + pw - 50, cy, 20, 16, Component.literal("+"), b -> changeScale(1)));
        addRenderableWidget(new ArcButton(px + pw - 26, cy, 20, 16, Component.literal("↺"), b -> {
            if (elements.isEmpty()) return;
            HudScale.set(scaleId(elements.get(selected)), 1F);
            ArcConfig.save();
        }));
        addRenderableWidget(new ArcButton(px + 6, py + ph - 22, pw - 12, 16, Component.translatable("gui.done"), b -> onClose()));
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
        HudAnchor current = elements.isEmpty() ? null : get(elements.get(selected));
        Field selField = elements.isEmpty() ? null : elements.get(selected);

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
            for (Field f : elements) {
                if (f == null || get(f) != a) continue;
                String s = elementName(f).getString();
                int col = f == selField ? 0xFFFFE082 : 0xFFB0BEC5;
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
        for (int i = 0; i < elements.size(); i++) {
            Field f = elements.get(i);
            boolean sel = i == selected;
            boolean hov = mouseX >= px + 4 && mouseX < px + pw - 4 && mouseY >= y && mouseY < y + 15;
            if (sel) g.fill(px + 4, y, px + pw - 4, y + 15, Draw.alpha(accent, 0x50));
            else if (hov) g.fill(px + 4, y, px + pw - 4, y + 15, 0x20FFFFFF);
            if (sel) g.fill(px + 4, y, px + 6, y + 15, Draw.alpha(accent, 0xFF));
            g.drawString(font, elementName(f), px + 10, y + 4, sel ? 0xFFFFFFFF : 0xFFCFD8DC, true);
            int pct = Math.round(HudScale.get(scaleId(f)) * 100);
            String an = (f == null ? "" : anchorName(get(f)).getString() + " · ") + pct + "%";
            Draw.small(g, font, an, px + pw - 8 - font.width(an) * 0.6F, y + 5, Draw.alpha(accent, 0xFF), 0.6F);
            y += 16;
        }
        // строка размера выбранного блока
        int cy = py + ph - 42;
        String sz = Component.translatable("archud.layout.size", Math.round(HudScale.get(scaleId(selField)) * 100)).getString();
        g.drawCenteredString(font, sz, px + (pw - 24) / 2, cy + 4, 0xFFFFFFFF);
        Draw.smallCentered(g, font, Component.translatable("archud.layout.size_hint").getString(), px + pw / 2F, cy - 9, Draw.TEXT_DIM, 0.55F);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
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
            if (my >= py + 24 && idx >= 0 && idx < elements.size()) {
                selected = idx;
                return true;
            }
            return true;
        }
        HudAnchor a = zoneAt(mx, my);
        if (a != null && !elements.isEmpty() && elements.get(selected) != null) {
            set(elements.get(selected), a);
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

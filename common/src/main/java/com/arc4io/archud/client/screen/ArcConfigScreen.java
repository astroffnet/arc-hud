package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.config.HudAnchor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Экран настроек Arc HUD: все переключатели из ArcConfig, поиск, две колонки, прокрутка.
 * Открывается из главного меню, из списка модов (Forge Mods / Mod Menu) и клавишей K.
 */
public class ArcConfigScreen extends Screen {
    private static final int ROW_H = 18;
    private final Screen parent;
    private final List<Field> fields = new ArrayList<>();
    private final List<Field> visible = new ArrayList<>();
    private EditBox search;
    private double scroll;
    private int px, py, pw, ph, listY, listH;

    public ArcConfigScreen(Screen parent) {
        super(Component.translatable("archud.config.title"));
        this.parent = parent;
        for (Field f : ArcConfig.class.getFields()) {
            if ((f.getType() == boolean.class || f.getType() == HudAnchor.class) && !Modifier.isStatic(f.getModifiers())) fields.add(f);
        }
    }

    @Override
    protected void init() {
        pw = Math.min(width - 20, 460);
        ph = height - 30;
        px = (width - pw) / 2;
        py = 15;
        listY = py + 46;
        listH = ph - 46 - 30;
        String prev = search != null ? search.getValue() : "";
        search = new EditBox(font, px + 10, py + 24, pw - 20, 16, Component.translatable("archud.controls.search"));
        search.setHint(Component.translatable("archud.controls.search").withStyle(s -> s.withColor(0x6A7380)));
        search.setValue(prev);
        search.setResponder(s -> {
            scroll = 0;
            refresh();
        });
        addRenderableWidget(search);
        addRenderableWidget(new ArcButton(px + pw - 126, py + ph - 24, 120, 18, Component.translatable("gui.done"), b -> onClose()));
        addRenderableWidget(new ArcButton(px + 6, py + ph - 24, 110, 18, Component.translatable("archud.config.controls"),
                b -> minecraft.setScreen(new ArcControlsScreen(this, minecraft.options))));
        addRenderableWidget(new ArcButton(px + 120, py + ph - 24, 130, 18, Component.translatable("archud.layout.open"),
                b -> {
                    ArcConfig.save();
                    minecraft.setScreen(new HudLayoutScreen(this));
                }));
        if (!com.arc4io.archud.client.ArcClient.platform().hudOverlayIds().isEmpty()) {
            addRenderableWidget(new ArcButton(px + 254, py + ph - 24, Math.max(60, pw - 254 - 132), 18, Component.translatable("archud.overlays.open"),
                    b -> {
                        ArcConfig.save();
                        minecraft.setScreen(new ModOverlaysScreen(this));
                    }));
        }
        refresh();
    }

    private Component label(Field f) {
        return Component.translatableWithFallback("archud.config." + f.getName(), HudRenderer.pretty(splitCamel(f.getName())));
    }

    private static String splitCamel(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private void refresh() {
        visible.clear();
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        for (Field f : fields) {
            if (q.isEmpty() || label(f).getString().toLowerCase(Locale.ROOT).contains(q) || f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                visible.add(f);
            }
        }
    }

    private boolean get(Field f) {
        try {
            return f.getType() != boolean.class || f.getBoolean(ArcConfig.get());
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private void toggle(Field f, int dir) {
        try {
            if (f.getType() == HudAnchor.class) {
                HudAnchor a = (HudAnchor) f.get(ArcConfig.get());
                f.set(ArcConfig.get(), (a == null ? HudAnchor.TOP_LEFT : a).next(dir));
            } else {
                f.setBoolean(ArcConfig.get(), !f.getBoolean(ArcConfig.get()));
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    private HudAnchor anchor(Field f) {
        try {
            return (HudAnchor) f.get(ArcConfig.get());
        } catch (IllegalAccessException e) {
            return HudAnchor.TOP_LEFT;
        }
    }

    private int colW() {
        return (pw - 26) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawString(font, title, px + 10, py + 8, Draw.TEXT, true);
        String count = visible.size() + " / " + fields.size();
        Draw.small(g, font, count, px + pw - 10 - font.width(count) * 0.75F, py + 9, Draw.TEXT_DIM, 0.75F);

        int rows = (visible.size() + 1) / 2;
        double maxScroll = Math.max(0, rows * ROW_H - listH);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        int cw = colW();
        g.enableScissor(px + 8, listY, px + pw - 8, listY + listH);
        for (int i = 0; i < visible.size(); i++) {
            int col = i % 2, row = i / 2;
            int x = px + 10 + col * (cw + 6);
            int y = listY + row * ROW_H - (int) scroll;
            if (y + ROW_H < listY || y > listY + listH) continue;
            Field f = visible.get(i);
            boolean on = get(f);
            boolean hov = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ROW_H - 2 && mouseY >= listY && mouseY < listY + listH;
            Draw.panel(g, x, y, cw, ROW_H - 2, hov ? Draw.PANEL_LIGHT : Draw.PANEL, on ? Draw.alpha(accent, 0x70) : 0x30FFFFFF);
            int reserve = 36;
            if (f.getType() == HudAnchor.class) {
                // выбор края экрана (ЛКМ — дальше, ПКМ — назад)
                String v = HudLayoutScreen.anchorName(anchor(f)).getString();
                int vw = Math.round(font.width(v) * 0.75F) + 10;
                reserve = vw + 10;
                int vx = x + cw - vw - 4;
                Draw.panel(g, vx, y + 2, vw, ROW_H - 6, Draw.PANEL_LIGHT, Draw.alpha(accent, 0xA0));
                Draw.small(g, font, v, vx + 5, y + 5, Draw.alpha(accent, 0xFF), 0.75F);
            } else {
                // переключатель
                int tx = x + cw - 26, ty = y + 4;
                g.fill(tx, ty, tx + 20, ty + 8, on ? Draw.alpha(accent, 0xC0) : 0xFF2A3038);
                int knob = on ? tx + 12 : tx;
                g.fill(knob, ty, knob + 8, ty + 8, 0xFFEAF2FA);
            }
            String s = label(f).getString();
            if (font.width(s) > cw - reserve) s = font.plainSubstrByWidth(s, cw - reserve - 4) + "…";
            g.drawString(font, s, x + 6, y + 4, on ? Draw.TEXT : 0xFF8C9AAB, true);
        }
        g.disableScissor();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (my >= listY && my < listY + listH) {
            int cw = colW();
            for (int i = 0; i < visible.size(); i++) {
                int col = i % 2, row = i / 2;
                int x = px + 10 + col * (cw + 6);
                int y = listY + row * ROW_H - (int) scroll;
                if (mx >= x && mx < x + cw && my >= y && my < y + ROW_H - 2) {
                    toggle(visible.get(i), button == 1 ? -1 : 1);
                    return true;
                }
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

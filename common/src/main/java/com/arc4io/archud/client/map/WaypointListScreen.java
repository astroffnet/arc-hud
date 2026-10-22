package com.arc4io.archud.client.map;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.client.screen.ArcScreen;
import com.arc4io.archud.compat.TempadCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Меню меток: все метки всех измерений — поиск, фильтр по типу и измерению, сортировка,
 * видимость, навигация, поделиться, дверь Tempad, телепорт, изменить, удалить (кнопка или DEL).
 */
public class WaypointListScreen extends ArcScreen {
    private enum Sort {DISTANCE, NAME, NEWEST}

    private static Sort sort = Sort.DISTANCE;
    private static boolean currentDimOnly;
    private static final int ROW = 24;
    private EditBox search;
    private Waypoint selected;
    private double scroll;
    private long lastClick;
    private List<Waypoint> rows = new ArrayList<>();
    private ArcButton editB, delB, navB, shareB, doorB, tpB, sortB, dimB;
    private String status = "";
    private long statusTime;

    public WaypointListScreen(Screen parent) {
        super(Component.translatable("archud.wpl.title"), parent);
    }

    private int listTop() {
        return py + 64;
    }

    private int listBottom() {
        return py + ph - 56;
    }

    @Override
    protected void init() {
        layout(440, Math.min(height - 16, 340));
        int x = px + 10, w = pw - 20;
        String old = search == null ? "" : search.getValue();
        search = addRenderableWidget(new EditBox(font, x, py + 26, w - 160, 16, Component.translatable("archud.controls.search")));
        search.setHint(Component.translatable("archud.controls.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(old);
        search.setResponder(s -> refresh());
        sortB = addRenderableWidget(new ArcButton(x + w - 156, py + 25, 78, 18, sortLabel(), b -> {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            b.setMessage(sortLabel());
            refresh();
        }));
        dimB = addRenderableWidget(new ArcButton(x + w - 74, py + 25, 74, 18, dimLabel(), b -> {
            currentDimOnly = !currentDimOnly;
            b.setMessage(dimLabel());
            refresh();
        }));
        int by = py + ph - 50;
        int bw = (w - 5 * 4) / 6;
        navB = addRenderableWidget(new ArcButton(x, by, bw, 18, Component.translatable("archud.map.menu.nav"), b -> {
            if (selected == null) return;
            if (Navigator.isTarget(selected)) Navigator.clear();
            else Navigator.set(selected);
            updateButtons();
        }));
        shareB = addRenderableWidget(new ArcButton(x + (bw + 4), by, bw, 18, Component.translatable("archud.map.menu.share"),
                b -> { if (selected != null) minecraft.setScreen(new ShareWaypointScreen(this, selected)); }));
        doorB = addRenderableWidget(new ArcButton(x + (bw + 4) * 2, by, bw, 18, Component.translatable("archud.wpl.door"), b -> {
            if (selected == null) return;
            TempadCompat.openDoor(selected);
            minecraft.setScreen(null);
        }));
        tpB = addRenderableWidget(new ArcButton(x + (bw + 4) * 3, by, bw, 18, Component.translatable("archud.wp.teleport"), b -> {
            if (selected == null || minecraft.getConnection() == null) return;
            String dim = selected.dimension;
            String cmd = dim.equals(WaypointManager.currentDimension())
                    ? "tp @s " + selected.x + " " + selected.y + " " + selected.z
                    : "execute in " + dim + " run tp @s " + selected.x + " " + selected.y + " " + selected.z;
            minecraft.getConnection().sendCommand(cmd);
            minecraft.setScreen(null);
        }));
        editB = addRenderableWidget(new ArcButton(x + (bw + 4) * 4, by, bw, 18, Component.translatable("selectServer.edit"),
                b -> { if (selected != null) minecraft.setScreen(new WaypointEditScreen(this, selected, false)); }));
        delB = addRenderableWidget(new ArcButton(x + (bw + 4) * 5, by, w - (bw + 4) * 5, 18, Component.translatable("archud.wp.delete"),
                b -> deleteSelected()).danger());
        int y2 = py + ph - 26;
        addRenderableWidget(new ArcButton(x, y2 + 2, 100, 18, Component.translatable("archud.wp.add_here"), b -> {
            Waypoint wp = WaypointManager.addHere();
            if (wp != null) minecraft.setScreen(new WaypointEditScreen(this, wp, false));
        }));
        addRenderableWidget(new ArcButton(x + 104, y2 + 2, 110, 18, Component.translatable("archud.wpl.clear_deaths"), b -> {
            int n = 0;
            for (Waypoint wp : new ArrayList<>(WaypointManager.user())) {
                if (wp.source == Waypoint.Source.DEATH) {
                    WaypointManager.remove(wp);
                    n++;
                }
            }
            flash(Component.translatable("archud.wpl.deleted_n", n).getString());
            refresh();
        }));
        addRenderableWidget(new ArcButton(px + pw - 108, y2 + 2, 100, 18, Component.translatable("gui.done"), b -> onClose()));
        refresh();
    }

    private Component sortLabel() {
        return Component.translatable("archud.wpl.sort." + sort.name().toLowerCase(Locale.ROOT));
    }

    private Component dimLabel() {
        return Component.translatable(currentDimOnly ? "archud.wpl.dim_here" : "archud.wpl.dim_all");
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        String dim = WaypointManager.currentDimension();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : WaypointManager.all()) {
            if (!q.isEmpty() && !w.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (currentDimOnly && !dim.equals(w.dimension)) continue;
            if (!WaypointManager.sourceVisible(w.source)) continue;
            out.add(w);
        }
        double px0 = minecraft.player == null ? 0 : minecraft.player.getX(), py0 = minecraft.player == null ? 0 : minecraft.player.getY(),
                pz0 = minecraft.player == null ? 0 : minecraft.player.getZ();
        switch (sort) {
            case DISTANCE -> out.sort(Comparator.comparingDouble(w -> dim.equals(w.dimension) ? w.distanceTo(px0, py0, pz0) : 1e12 + w.name.hashCode()));
            case NAME -> out.sort(Comparator.comparing(w -> w.name.toLowerCase(Locale.ROOT)));
            case NEWEST -> out.sort(Comparator.comparingLong((Waypoint w) -> -w.created));
        }
        rows = out;
        if (selected != null && !out.contains(selected)) selected = null;
        updateButtons();
    }

    private void updateButtons() {
        if (editB == null) return;
        boolean has = selected != null;
        editB.active = has;
        delB.active = has && deletable(selected);
        navB.active = has;
        navB.setMessage(Component.translatable(has && Navigator.isTarget(selected) ? "archud.map.menu.nav_stop" : "archud.map.menu.nav"));
        shareB.active = has;
        doorB.active = has && TempadCompat.canOpenDoor(selected);
        tpB.active = has && minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    static boolean deletable(Waypoint w) {
        return w.source == Waypoint.Source.USER || w.source == Waypoint.Source.DEATH || w.source == Waypoint.Source.SHARED;
    }

    private void deleteSelected() {
        if (selected == null) return;
        if (!deletable(selected)) {
            flash(Component.translatable("archud.wpl.cant_delete").getString());
            return;
        }
        int idx = rows.indexOf(selected);
        if (Navigator.isTarget(selected)) Navigator.clear();
        flash(Component.translatable("archud.wpl.deleted", selected.name).getString());
        WaypointManager.remove(selected);
        refresh();
        selected = rows.isEmpty() ? null : rows.get(Math.min(Math.max(0, idx), rows.size() - 1));
        updateButtons();
    }

    private void flash(String s) {
        status = s;
        statusTime = Util.getMillis();
    }

    private double maxScroll() {
        return Math.max(0, rows.size() * ROW - (listBottom() - listTop()));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_DELETE && !search.isFocused()) {
            deleteSelected();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && !rows.isEmpty()) {
            int i = selected == null ? -1 : rows.indexOf(selected);
            i = Mth.clamp(i + (key == GLFW.GLFW_KEY_UP ? -1 : 1), 0, rows.size() - 1);
            selected = rows.get(i);
            double top = i * ROW, h = listBottom() - listTop();
            if (top < scroll) scroll = top;
            if (top + ROW > scroll + h) scroll = top + ROW - h;
            updateButtons();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && selected != null && !search.isFocused()) {
            minecraft.setScreen(new WaypointEditScreen(this, selected, false));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int x = px + 10, w = pw - 20;
        if (mx >= x && mx < x + w && my >= listTop() && my < listBottom()) {
            int i = (int) ((my - listTop() + scroll) / ROW);
            if (i < 0 || i >= rows.size()) return true;
            Waypoint wp = rows.get(i);
            // «глаз» — видимость
            if (mx >= x + w - 22) {
                wp.visible = !wp.visible;
                if (wp.editable()) WaypointManager.save();
                return true;
            }
            boolean dbl = wp == selected && Util.getMillis() - lastClick < 300;
            selected = wp;
            lastClick = Util.getMillis();
            updateButtons();
            if (dbl) minecraft.setScreen(new WaypointEditScreen(this, wp, false));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Mth.clamp(scroll - delta * ROW, 0, maxScroll());
        return true;
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0) refresh();
    }

    private int ticks;

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        int x = px + 10, w = pw - 20, top = listTop(), bottom = listBottom();
        Draw.small(g, font, Component.translatable("archud.wpl.hint", rows.size()).getString(), x, py + 47, Draw.TEXT_DIM, 0.65F);
        Draw.panel(g, x - 2, top - 2, w + 4, bottom - top + 4, 0x70080B10, 0x30FFFFFF);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        g.enableScissor(x, top, x + w, bottom);
        int y = top - (int) scroll;
        String dim = WaypointManager.currentDimension();
        for (Waypoint wp : rows) {
            if (y + ROW >= top && y <= bottom) {
                boolean sel = wp == selected;
                boolean hov = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW - 1, bottom);
                g.fill(x, y, x + w, y + ROW - 1, sel ? Draw.alpha(accent, 0x38) : hov ? 0x1AFFFFFF : 0x0AFFFFFF);
                if (sel) g.fill(x, y, x + 2, y + ROW - 1, Draw.alpha(accent, 0xFF));
                if (Navigator.isTarget(wp)) g.fill(x + w - 26, y + 4, x + w - 25, y + ROW - 5, Draw.alpha(accent, 0xFF));
                MapView.diamond(g, x + 12, y + 11, 6, wp.visible ? wp.color : 0xFF555B63);
                Draw.smallCentered(g, font, wp.initials(), x + 12.5F, y + 8.5F, 0xFF0B0F14, 0.55F);
                String name = wp.name;
                if (font.width(name) > w - 170) name = font.plainSubstrByWidth(name, w - 176) + "…";
                g.drawString(font, name, x + 24, y + 3, wp.visible ? Draw.TEXT : Draw.TEXT_DIM, true);
                String src = Component.translatable("archud.wp.src." + wp.source.name().toLowerCase(Locale.ROOT)).getString();
                String dimName = HudRenderer.pretty(wp.dimension.substring(wp.dimension.indexOf(':') + 1));
                Draw.small(g, font, wp.x + ", " + wp.y + ", " + wp.z + "  ·  " + dimName + "  ·  " + src, x + 24, y + 14, Draw.TEXT_DIM, 0.6F);
                String dist = dim.equals(wp.dimension) && minecraft.player != null
                        ? Math.round(wp.distanceTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ())) + " m" : "—";
                g.drawString(font, dist, x + w - 30 - font.width(dist), y + 8, Draw.TEXT_DIM, true);
                g.drawString(font, wp.visible ? "◉" : "○", x + w - 16, y + 8, wp.visible ? Draw.alpha(accent, 0xFF) : 0xFF6A7380, false);
            }
            y += ROW;
        }
        g.disableScissor();
        if (rows.isEmpty()) g.drawCenteredString(font, Component.translatable("archud.wpl.empty"), x + w / 2, top + 30, Draw.TEXT_DIM);
        if (!status.isEmpty() && Util.getMillis() - statusTime < 2500) {
            Draw.small(g, font, status, px + pw - 10 - font.width(status) * 0.7F, py + 47, 0xFFFFD54F, 0.7F);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}

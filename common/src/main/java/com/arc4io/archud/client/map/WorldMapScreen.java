package com.arc4io.archud.client.map;

import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.compat.TempadCompat;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Полноэкранная карта мира.
 *  - ЛКМ перетаскивание, колесо — масштаб к курсору, WASD/стрелки — сдвиг, +/- — масштаб;
 *  - ПКМ — контекстное меню (метка здесь, навигация, телепорт, координаты в чат);
 *  - выбор измерения и слоя (карта сохраняется по измерениям — можно смотреть Незер из Верхнего мира);
 *  - переход к координатам, список меток с поиском, фильтрами по типу и сортировкой по расстоянию;
 *  - след игрока, слизневые чанки, сетка, экспорт видимой карты в PNG.
 */
public class WorldMapScreen extends Screen {
    private final Screen parent;
    private double centerX, centerZ;
    private float zoom = 2F;
    private boolean dragging, followPlayer = true, showList = true, showTrail = true, showSlime, showGrid = true, showZones = true;
    private double listScroll;
    private MapCache.Layer viewLayer;
    private final List<String[]> layers = new ArrayList<>();
    private int layerIndex;
    private EditBox gotoBox, searchBox;
    // контекстное меню
    private boolean menuOpen;
    private int menuX, menuY, menuWX, menuWZ;
    private Waypoint menuWaypoint;
    private java.util.UUID menuPlayer;
    private String menuPlayerName;
    private double[] menuPlayerPos;
    private final List<String> menuItems = new ArrayList<>();

    public WorldMapScreen(Screen parent) {
        super(Component.translatable("archud.map.title"));
        this.parent = parent;
    }

    private String currentDim() {
        return minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
    }

    private boolean viewingCurrent() {
        return viewLayer == null || viewLayer == MapCache.active();
    }

    private String viewDim() {
        return viewLayer == null ? currentDim() : viewLayer.dimension();
    }

    @Override
    protected void init() {
        if (minecraft.player != null && followPlayer) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        // слои: текущий + сохранённые
        layers.clear();
        MapCache.Layer act = MapCache.active();
        if (act != null) layers.add(new String[]{act.dimension(), act.level()});
        for (String[] l : MapCache.savedLayers(WaypointManager.worldKey())) {
            boolean dup = false;
            for (String[] e : layers) if (e[0].equals(l[0]) && e[1].equals(l[1])) dup = true;
            if (!dup) layers.add(l);
        }
        int y = height - 24;
        addRenderableWidget(new ArcButton(6, y, 70, 18, Component.translatable("archud.map.center"), b -> {
            followPlayer = true;
            viewLayer = null;
            layerIndex = 0;
        }));
        addRenderableWidget(new ArcButton(80, y, 104, 18, Component.translatable("archud.wp.add_here"), b -> {
            Waypoint w = WaypointManager.createHere();
            if (w != null) minecraft.setScreen(new WaypointEditScreen(this, w, true));
        }));
        addRenderableWidget(new ArcButton(188, y, 120, 18, layerLabel(), b -> {
            if (layers.isEmpty()) return;
            layerIndex = (layerIndex + 1) % layers.size();
            String[] l = layers.get(layerIndex);
            viewLayer = MapCache.layer(WaypointManager.worldKey(), l[0], l[1]);
            followPlayer = viewingCurrent() && followPlayer;
            b.setMessage(layerLabel());
        }));
        addRenderableWidget(new ArcButton(312, y, 22, 18, Component.literal("☰"), b -> showList = !showList));
        addRenderableWidget(new ArcButton(338, y, 22, 18, Component.literal("⋯"), b -> showTrail = !showTrail));
        addRenderableWidget(new ArcButton(364, y, 22, 18, Component.literal("▦"), b -> showGrid = !showGrid));
        addRenderableWidget(new ArcButton(390, y, 22, 18, Component.literal("◘"), b -> showSlime = !showSlime));
        addRenderableWidget(new ArcButton(416, y, 22, 18, Component.literal("◎"), b -> showZones = !showZones));
        addRenderableWidget(new ArcButton(442, y, 70, 18, Component.translatable("archud.map.export"), b -> export()));
        addRenderableWidget(new ArcButton(516, y, 80, 18, Component.translatable("archud.wpl.short"), b -> minecraft.setScreen(new WaypointListScreen(this))));
        gotoBox = addRenderableWidget(new EditBox(font, 6, height - 46, 120, 16, Component.translatable("archud.map.goto")));
        gotoBox.setHint(Component.translatable("archud.map.goto_hint"));
        addRenderableWidget(new ArcButton(130, height - 47, 54, 18, Component.translatable("archud.map.goto"), b -> gotoCoords(false)));
        addRenderableWidget(new ArcButton(188, height - 47, 80, 18, Component.translatable("archud.nav.go"), b -> gotoCoords(true)));
        int lw = listW();
        searchBox = addRenderableWidget(new EditBox(font, width - lw + 4, 34, lw - 8, 14, Component.translatable("archud.controls.search")));
        searchBox.setHint(Component.translatable("archud.controls.search"));
        searchBox.visible = showList;
        addRenderableWidget(new ArcButton(width - 76, height - 24, 70, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    private Component layerLabel() {
        if (layers.isEmpty()) return Component.translatable("archud.map.layer.none");
        String[] l = layers.get(Math.min(layerIndex, layers.size() - 1));
        String dim = HudRenderer.pretty(l[0].substring(l[0].indexOf(':') + 1));
        String lv;
        if (l[1].equals("surface")) lv = Component.translatable("archud.map.layer.surface").getString();
        else if (l[1].equals("cave")) lv = Component.translatable("archud.map.cave").getString();
        else {
            try {
                lv = "Y " + (Integer.parseInt(l[1].replace("cave_", "")) * 16);
            } catch (NumberFormatException e) {
                lv = l[1];
            }
        }
        return Component.literal(dim + " · " + lv);
    }

    private void gotoCoords(boolean navigate) {
        String[] parts = gotoBox.getValue().trim().split("[\\s,;]+");
        try {
            int x, y = minecraft.player != null ? minecraft.player.getBlockY() : 64, z;
            if (parts.length >= 3) {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } else {
                x = Integer.parseInt(parts[0]);
                z = Integer.parseInt(parts[1]);
            }
            followPlayer = false;
            centerX = x;
            centerZ = z;
            if (navigate) Navigator.navigateTo(x, y, z, viewDim());
        } catch (Exception ignored) {
        }
    }

    private int listW() {
        return showList ? Math.min(180, width / 4) : 0;
    }

    private double[] screenToWorld(double mx, double my) {
        float cx = (width - listW()) / 2F, cy = height / 2F;
        return new double[]{centerX + (mx - cx) / zoom, centerZ + (my - cy) / zoom};
    }

    @Override
    public void tick() {
        // плавный сдвиг клавишами
        long win = minecraft.getWindow().getWindow();
        double step = 12 / zoom;
        boolean typing = gotoBox.isFocused() || searchBox.isFocused();
        if (!typing) {
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_W) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_UP)) pan(0, -step);
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_S) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_DOWN)) pan(0, step);
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_A) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT)) pan(-step, 0);
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_D) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT)) pan(step, 0);
        }
        searchBox.visible = showList;
    }

    private void pan(double dx, double dz) {
        followPlayer = false;
        centerX += dx;
        centerZ += dz;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int accent = Theme.accent();
        int mapW = width - listW();
        g.fill(0, 0, width, height, 0xFF06080C);
        if (followPlayer && viewingCurrent() && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        float cx = mapW / 2F, cy = height / 2F;
        float radius = Math.max(mapW, height) / 1.4F;
        RenderSystem.enableBlend();
        g.enableScissor(0, 0, mapW, height);
        MapCache.Layer layer = viewLayer != null ? viewLayer : MapCache.active();
        if (viewLayer == null) MapView.drawTerrain(g, cx, cy, centerX, centerZ, zoom, 0F, radius);
        else if (layer != null) MapView.drawTerrain(g, layer, cx, cy, centerX, centerZ, zoom, 0F, radius);
        if (showGrid) drawGrid(g, mapW, cx, cy);
        if (showSlime) MapView.drawSlimeChunks(g, minecraft, cx, cy, centerX, centerZ, zoom, 0F, radius);
        if (showTrail) MapView.drawTrail(g, TrailManager.trail(viewDim()), cx, cy, centerX, centerZ, zoom, 0F, radius, accent);
        MapView.drawRemotePlayers(g, minecraft, viewDim(), cx, cy, centerX, centerZ, zoom, 0F, radius, true, showZones);
        if (viewingCurrent()) {
            MapView.drawEntities(g, minecraft, cx, cy, centerX, centerZ, zoom, 0F, radius, true);
            MapView.drawNavigatorLine(g, minecraft, cx, cy, centerX, centerZ, zoom, 0F, radius);
        }
        drawWaypointsFor(g, cx, cy, radius);
        if (viewingCurrent() && minecraft.player != null) {
            float[] ps = MapView.toScreen(minecraft.player.getX(), minecraft.player.getZ(), centerX, centerZ, zoom, 0F);
            MapView.drawPlayerArrow(g, cx + ps[0], cy + ps[1], minecraft.player.getYRot() + 180F, Draw.alpha(accent, 0xFF));
        }
        g.disableScissor();

        // шапка
        Draw.panel(g, 4, 4, Math.min(mapW - 8, 300), 24, Draw.PANEL, Draw.alpha(accent, 0x90));
        g.drawString(font, title.getString() + " · " + layerLabel().getString(), 10, 8, Draw.alpha(accent, 0xFF), true);
        if (mouseX < mapW) {
            double[] w = screenToWorld(mouseX, mouseY);
            int bx = (int) Math.floor(w[0]), bz = (int) Math.floor(w[1]);
            String info = bx + ", " + bz + "   ×" + String.format("%.2f", zoom);
            if (minecraft.player != null && viewingCurrent()) {
                double d = Math.sqrt(Math.pow(bx - minecraft.player.getX(), 2) + Math.pow(bz - minecraft.player.getZ(), 2));
                info += "   " + Math.round(d) + " m";
            }
            if (viewDim().equals("minecraft:overworld")) info += "   N: " + Math.floorDiv(bx, 8) + ", " + Math.floorDiv(bz, 8);
            else if (viewDim().equals("minecraft:the_nether")) info += "   O: " + bx * 8 + ", " + bz * 8;
            Draw.small(g, font, info, 10, 19, Draw.TEXT_DIM, 0.6F);
        }
        Draw.small(g, font, Component.translatable("archud.map.hint").getString(), 6, height - 56, Draw.TEXT_DIM, 0.6F);
        String sync = com.arc4io.archud.client.ArcClientNet.mapSyncActive()
                ? Component.translatable("archud.map.sync_on", com.arc4io.archud.client.ArcClientNet.players().size()).getString()
                : Component.translatable("archud.map.sync_off").getString();
        Draw.small(g, font, sync, 6, height - 63, com.arc4io.archud.client.ArcClientNet.mapSyncActive() ? 0xFF9CFF5A : Draw.TEXT_DIM, 0.6F);

        if (showList) renderList(g, mapW, mouseX, mouseY, accent);
        super.render(g, mouseX, mouseY, partialTick);
        if (menuOpen) renderMenu(g, mouseX, mouseY, accent);
    }

    private void drawGrid(GuiGraphics g, int mapW, float cx, float cy) {
        int step = zoom >= 4 ? 16 : 128;
        double left = centerX - cx / zoom, top = centerZ - cy / zoom;
        int color = step == 16 ? 0x16FFFFFF : 0x12FFFFFF;
        for (double wx = Math.floor(left / step) * step; wx < left + mapW / zoom; wx += step) {
            int sx = (int) (cx + (wx - centerX) * zoom);
            g.fill(sx, 0, sx + 1, height, color);
        }
        for (double wz = Math.floor(top / step) * step; wz < top + height / zoom; wz += step) {
            int sy = (int) (cy + (wz - centerZ) * zoom);
            g.fill(0, sy, mapW, sy + 1, color);
        }
    }

    private void drawWaypointsFor(GuiGraphics g, float cx, float cy, float radius) {
        String dim = viewDim();
        for (Waypoint w : WaypointManager.all()) {
            if (!w.visible || !dim.equals(w.dimension) || !WaypointManager.sourceVisible(w.source)) continue;
            float[] s = MapView.toScreen(w.x + 0.5, w.z + 0.5, centerX, centerZ, zoom, 0F);
            if (Math.abs(s[0]) > radius || Math.abs(s[1]) > radius) continue;
            int x = Math.round(cx + s[0]), y = Math.round(cy + s[1]);
            if (Navigator.isTarget(w)) MapView.diamond(g, x, y, 8, 0xFFFFFFFF);
            MapView.diamond(g, x, y, 6, w.color);
            Draw.smallCentered(g, font, w.initials(), x + 0.5F, y - 2, 0xFF000000, 0.5F);
            Draw.smallCentered(g, font, w.name, x, y - 13, 0xFFFFFFFF, 0.6F);
        }
        Waypoint nav = Navigator.target();
        if (nav != null && nav.source == Waypoint.Source.TEMP && dim.equals(nav.dimension)) {
            float[] s = MapView.toScreen(nav.x + 0.5, nav.z + 0.5, centerX, centerZ, zoom, 0F);
            MapView.diamond(g, Math.round(cx + s[0]), Math.round(cy + s[1]), 6, nav.color);
        }
    }

    private List<Waypoint> listed() {
        String q = searchBox == null ? "" : searchBox.getValue().toLowerCase(Locale.ROOT);
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : WaypointManager.all()) {
            if (!WaypointManager.sourceVisible(w.source)) continue;
            if (!q.isEmpty() && !w.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(w);
        }
        if (minecraft.player != null) {
            String dim = currentDim();
            double px = minecraft.player.getX(), py = minecraft.player.getY(), pz = minecraft.player.getZ();
            out.sort(Comparator.comparingDouble(w -> dim.equals(w.dimension) ? w.distanceTo(px, py, pz) : 1e12));
        }
        return out;
    }

    private void renderList(GuiGraphics g, int x, int mouseX, int mouseY, int accent) {
        int lw = listW();
        Draw.panel(g, x, 0, lw, height, 0xF00B0F14, Draw.alpha(accent, 0x70));
        g.drawString(font, Component.translatable("archud.wp.title"), x + 6, 6, Draw.TEXT, true);
        // фильтры по типу
        Waypoint.Source[] srcs = Waypoint.Source.values();
        int fx = x + 4;
        for (Waypoint.Source s : srcs) {
            if (s == Waypoint.Source.TEMP) continue;
            boolean on = WaypointManager.sourceVisible(s);
            String label = Component.translatable("archud.wp.src." + s.name().toLowerCase(Locale.ROOT)).getString();
            int tw = Math.round(font.width(label) * 0.55F) + 6;
            Draw.panel(g, fx, 18, tw, 12, on ? Draw.alpha(accent, 0x50) : Draw.PANEL, on ? Draw.alpha(accent, 0xFF) : 0x30FFFFFF);
            Draw.small(g, font, label, fx + 3, 21.5F, on ? 0xFFFFFFFF : Draw.TEXT_DIM, 0.55F);
            fx += tw + 2;
        }
        List<Waypoint> list = listed();
        int top = 52, bottom = height - 30;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, list.size() * 22 - (bottom - top)));
        g.enableScissor(x, top, x + lw, bottom);
        int y = top - (int) listScroll;
        String dim = currentDim();
        for (Waypoint wp : list) {
            boolean hov = mouseX >= x && mouseX < x + lw && mouseY >= y && mouseY < y + 21 && mouseY >= top && mouseY < bottom;
            if (hov) g.fill(x + 2, y, x + lw - 2, y + 21, 0x20FFFFFF);
            if (Navigator.isTarget(wp)) g.fill(x + 2, y, x + 4, y + 21, 0xFF000000 | wp.color);
            MapView.diamond(g, x + 11, y + 10, 5, wp.color);
            Draw.smallCentered(g, font, wp.initials(), x + 11.5F, y + 8, 0xFF000000, 0.5F);
            String n = wp.name;
            if (font.width(n) > lw - 26) n = font.plainSubstrByWidth(n, lw - 30) + "…";
            g.drawString(font, n, x + 20, y + 2, wp.visible ? Draw.TEXT : Draw.TEXT_DIM, true);
            String info = dim.equals(wp.dimension) && minecraft.player != null
                    ? Math.round(wp.distanceTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ())) + " m"
                    : HudRenderer.pretty(wp.dimension.substring(wp.dimension.indexOf(':') + 1));
            Draw.small(g, font, info + "  ·  " + Component.translatable("archud.wp.src." + wp.source.name().toLowerCase(Locale.ROOT)).getString(),
                    x + 20, y + 12, Draw.TEXT_DIM, 0.6F);
            y += 22;
        }
        g.disableScissor();
        Draw.small(g, font, Component.translatable("archud.map.list_hint").getString(), x + 4, height - 28, Draw.TEXT_DIM, 0.5F);
    }

    // ---------- контекстное меню ----------
    private void openMenu(int mx, int my, Waypoint wp) {
        double[] w = screenToWorld(mx, my);
        menuWX = (int) Math.floor(w[0]);
        menuWZ = (int) Math.floor(w[1]);
        menuX = mx;
        menuY = my;
        menuWaypoint = wp;
        menuItems.clear();
        menuPlayer = null;
        if (wp == null) findPlayerAt(mx, my);
        if (menuPlayer != null) {
            menuItems.add("nav_player");
            if (com.arc4io.archud.client.ArcClientNet.tempadDoors() && minecraft.player != null
                    && com.arc4io.archud.compat.TempadCompat.find(minecraft.player) != null) menuItems.add("tempad_player");
            if (minecraft.player != null && minecraft.player.hasPermissions(2) && minecraft.getConnection() != null) menuItems.add("tp_player");
            menuOpen = true;
            return;
        }
        if (wp != null) {
            menuItems.add("edit");
            menuItems.add(Navigator.isTarget(wp) ? "nav_stop" : "nav");
            menuItems.add("share");
            if (com.arc4io.archud.compat.TempadCompat.canOpenDoor(wp)) menuItems.add("tempad_door");
        } else {
            menuItems.add("add");
            menuItems.add("nav_here");
            menuItems.add("share_coords");
            if (com.arc4io.archud.client.ArcClientNet.tempadDoors() && minecraft.player != null
                    && com.arc4io.archud.compat.TempadCompat.find(minecraft.player) != null) menuItems.add("tempad_door_here");
        }
        if (minecraft.player != null && minecraft.player.hasPermissions(2)) menuItems.add("tp");
        menuOpen = true;
    }

    /** Игрок под курсором (виден рядом или известен серверу Arc HUD). */
    private void findPlayerAt(int mx, int my) {
        float cx = (width - listW()) / 2F, cy = height / 2F;
        String dim = viewDim();
        double best = 7 * 7;
        if (viewingCurrent() && minecraft.level != null) {
            for (net.minecraft.world.entity.player.Player p : minecraft.level.players()) {
                if (p == minecraft.player || com.arc4io.archud.client.ArcClientNet.isHidden(p.getUUID())) continue;
                float[] s = MapView.toScreen(p.getX(), p.getZ(), centerX, centerZ, zoom, 0F);
                double d = Math.pow(cx + s[0] - mx, 2) + Math.pow(cy + s[1] - my, 2);
                if (d < best) {
                    best = d;
                    menuPlayer = p.getUUID();
                    menuPlayerName = p.getGameProfile().getName();
                    menuPlayerPos = new double[]{p.getX(), p.getY(), p.getZ()};
                }
            }
        }
        for (var p : com.arc4io.archud.client.ArcClientNet.players()) {
            if (!dim.equals(p.dimension())) continue;
            float[] s = MapView.toScreen(p.x(), p.z(), centerX, centerZ, zoom, 0F);
            double d = Math.pow(cx + s[0] - mx, 2) + Math.pow(cy + s[1] - my, 2);
            if (d < best) {
                best = d;
                menuPlayer = p.id();
                menuPlayerName = p.name();
                menuPlayerPos = new double[]{p.x(), p.y(), p.z()};
            }
        }
    }

    private void renderMenu(GuiGraphics g, int mouseX, int mouseY, int accent) {
        int w = 150, h = menuItems.size() * 14 + 16;
        int x = Math.min(menuX, width - w - 2), y = Math.min(menuY, height - h - 2);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Draw.panel(g, x, y, w, h, 0xF0101419, Draw.alpha(accent, 0xFF));
        String head = menuPlayer != null ? "☺ " + menuPlayerName : menuWaypoint != null ? menuWaypoint.name : menuWX + ", " + menuWZ;
        Draw.small(g, font, head, x + 5, y + 4, Draw.alpha(accent, 0xFF), 0.7F);
        for (int i = 0; i < menuItems.size(); i++) {
            int iy = y + 13 + i * 14;
            boolean hov = mouseX >= x && mouseX < x + w && mouseY >= iy && mouseY < iy + 14;
            if (hov) g.fill(x + 2, iy, x + w - 2, iy + 14, Draw.alpha(accent, 0x50));
            g.drawString(font, Component.translatable("archud.map.menu." + menuItems.get(i)), x + 6, iy + 3, Draw.TEXT, true);
        }
        g.pose().popPose();
    }

    private boolean clickMenu(double mx, double my) {
        int w = 150, h = menuItems.size() * 14 + 16;
        int x = Math.min(menuX, width - w - 2), y = Math.min(menuY, height - h - 2);
        menuOpen = false;
        if (mx < x || mx >= x + w || my < y + 13 || my >= y + h) return true;
        int idx = (int) ((my - y - 13) / 14);
        if (idx < 0 || idx >= menuItems.size()) return true;
        int ty = surfaceY(menuWX, menuWZ);
        switch (menuItems.get(idx)) {
            case "edit" -> minecraft.setScreen(new WaypointEditScreen(this, menuWaypoint, false));
            case "nav" -> Navigator.set(menuWaypoint);
            case "nav_stop" -> Navigator.clear();
            case "share" -> minecraft.setScreen(new ShareWaypointScreen(this, menuWaypoint));
            case "tempad_door" -> {
                TempadCompat.openDoor(menuWaypoint);
                minecraft.setScreen(null);
            }
            case "add" -> {
                int n = WaypointManager.user().size() + 1;
                Waypoint wp = new Waypoint(Component.translatable("archud.wp.default", n).getString(), menuWX, ty, menuWZ,
                        Waypoint.PALETTE[(n - 1) % Waypoint.PALETTE.length], viewDim(), Waypoint.Source.USER);
                minecraft.setScreen(new WaypointEditScreen(this, wp, true));
            }
            case "tempad_door_here" -> {
                Waypoint t = new Waypoint(menuWX + ", " + menuWZ, menuWX, com.arc4io.archud.server.TeleportSafety.NO_HINT, menuWZ, 0xFFFF8800,
                        viewDim(), Waypoint.Source.TEMP);
                TempadCompat.openDoor(t);
                minecraft.setScreen(null);
            }
            case "nav_player" -> Navigator.navigateTo((int) Math.floor(menuPlayerPos[0]), (int) Math.floor(menuPlayerPos[1]),
                    (int) Math.floor(menuPlayerPos[2]), viewDim());
            case "tempad_player" -> {
                com.arc4io.archud.client.ArcClientNet.tempadDoorToPlayer(menuPlayer);
                minecraft.setScreen(null);
            }
            case "tp_player" -> {
                if (minecraft.getConnection() != null) minecraft.getConnection().sendCommand("tp @s " + menuPlayerName);
                minecraft.setScreen(null);
            }
            case "nav_here" -> Navigator.navigateTo(menuWX, ty, menuWZ, viewDim());
            case "share_coords" -> {
                if (minecraft.getConnection() != null) minecraft.getConnection().sendChat("x:" + menuWX + " y:" + ty + " z:" + menuWZ);
            }
            case "tp" -> {
                Waypoint t = menuWaypoint;
                if (t != null) com.arc4io.archud.client.ArcClientNet.safeTeleport(t.dimension, t.x, t.y, t.z);
                else com.arc4io.archud.client.ArcClientNet.safeTeleport(viewDim(), menuWX, com.arc4io.archud.server.TeleportSafety.NO_HINT, menuWZ);
                minecraft.setScreen(null);
            }
            default -> {
            }
        }
        return true;
    }

    private int surfaceY(int x, int z) {
        if (minecraft.level != null && viewingCurrent() && minecraft.level.hasChunk(x >> 4, z >> 4)) {
            return minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
        return minecraft.player != null ? minecraft.player.getBlockY() : 64;
    }

    private Waypoint waypointAt(double mx, double my) {
        float cx = (width - listW()) / 2F, cy = height / 2F;
        String dim = viewDim();
        for (Waypoint w : WaypointManager.all()) {
            if (!w.visible || !dim.equals(w.dimension) || !WaypointManager.sourceVisible(w.source)) continue;
            float[] s = MapView.toScreen(w.x + 0.5, w.z + 0.5, centerX, centerZ, zoom, 0F);
            if (Math.abs(cx + s[0] - mx) <= 6 && Math.abs(cy + s[1] - my) <= 6) return w;
        }
        return null;
    }

    // ---------- экспорт ----------
    private void export() {
        MapCache.Layer layer = viewLayer != null ? viewLayer : MapCache.active();
        if (layer == null) return;
        int mapW = width - listW();
        double minX = centerX - mapW / 2.0 / zoom, minZ = centerZ - height / 2.0 / zoom;
        int w = (int) Math.min(4096, Math.ceil(mapW / zoom)), h = (int) Math.min(4096, Math.ceil(height / zoom));
        if (w <= 0 || h <= 0) return;
        try (NativeImage img = new NativeImage(w, h, true)) {
            int bx0 = (int) Math.floor(minX), bz0 = (int) Math.floor(minZ);
            MapCache.forEach(layer, minX, minZ, minX + w, minZ + h, r -> {
                for (int lz = 0; lz < MapCache.REGION; lz++) {
                    for (int lx = 0; lx < MapCache.REGION; lx++) {
                        int px = r.rx() * MapCache.REGION + lx - bx0, pz = r.rz() * MapCache.REGION + lz - bz0;
                        if (px < 0 || pz < 0 || px >= w || pz >= h) continue;
                        img.setPixelRGBA(px, pz, MapCache.pixel(r, lx, lz));
                    }
                }
            });
            Path dir = minecraft.gameDirectory.toPath().resolve("screenshots");
            java.nio.file.Files.createDirectories(dir);
            Path out = dir.resolve("archud_map_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png");
            img.writeToFile(out);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("archud.map.exported", out.getFileName().toString()), false);
            }
            Util.getPlatform().openFile(dir.toFile());
        } catch (Exception e) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal("Export failed: " + e.getMessage()), false);
        }
    }

    // ---------- ввод ----------
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (menuOpen) return clickMenu(mx, my);
        if (super.mouseClicked(mx, my, button)) return true;
        int mapW = width - listW();
        if (mx >= mapW && showList) {
            // фильтры
            if (my >= 18 && my < 30) {
                int fx = mapW + 4;
                for (Waypoint.Source s : Waypoint.Source.values()) {
                    if (s == Waypoint.Source.TEMP) continue;
                    String label = Component.translatable("archud.wp.src." + s.name().toLowerCase(Locale.ROOT)).getString();
                    int tw = Math.round(font.width(label) * 0.55F) + 6;
                    if (mx >= fx && mx < fx + tw) {
                        WaypointManager.toggleSource(s);
                        return true;
                    }
                    fx += tw + 2;
                }
                return true;
            }
            int idx = (int) ((my - 52 + listScroll) / 22);
            List<Waypoint> list = listed();
            if (my >= 52 && idx >= 0 && idx < list.size()) {
                Waypoint w = list.get(idx);
                if (button == 1) openMenu((int) mx, (int) my, w);
                else if (w.dimension.equals(viewDim())) {
                    followPlayer = false;
                    centerX = w.x;
                    centerZ = w.z;
                }
            }
            return true;
        }
        Waypoint hit = waypointAt(mx, my);
        if (button == 1) {
            openMenu((int) mx, (int) my, hit);
            return true;
        }
        if (hit != null && button == 0) {
            minecraft.setScreen(new WaypointEditScreen(this, hit, false));
            return true;
        }
        if (button == 0) {
            dragging = true;
            followPlayer = false;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (showList && mx >= width - listW()) {
            listScroll -= delta * 22;
            return true;
        }
        zoomAt(mx, my, delta > 0 ? 1.25F : 0.8F);
        return true;
    }

    private void zoomAt(double mx, double my, float factor) {
        float old = zoom;
        zoom = Mth.clamp(zoom * factor, 0.0625F, 16F);
        float cx = (width - listW()) / 2F, cy = height / 2F;
        centerX += (mx - cx) / old - (mx - cx) / zoom;
        centerZ += (my - cy) / old - (my - cy) / zoom;
        if (old != zoom && (mx != cx || my != cy)) followPlayer = false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = gotoBox.isFocused() || searchBox.isFocused();
        if (!typing && ArcKeys.WORLD_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (!typing && (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD)) {
            zoomAt((width - listW()) / 2.0, height / 2.0, 1.25F);
            return true;
        }
        if (!typing && (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT)) {
            zoomAt((width - listW()) / 2.0, height / 2.0, 0.8F);
            return true;
        }
        if (gotoBox.isFocused() && keyCode == GLFW.GLFW_KEY_ENTER) {
            gotoCoords(false);
            return true;
        }
        if (!typing && keyCode == GLFW.GLFW_KEY_DELETE) {
            double mx = minecraft.mouseHandler.xpos() * width / minecraft.getWindow().getScreenWidth();
            double my = minecraft.mouseHandler.ypos() * height / minecraft.getWindow().getScreenHeight();
            Waypoint hit = null;
            if (showList && mx >= width - listW()) {
                int idx = (int) ((my - 52 + listScroll) / 22);
                List<Waypoint> list = listed();
                if (my >= 52 && idx >= 0 && idx < list.size()) hit = list.get(idx);
            } else hit = waypointAt(mx, my);
            if (hit != null && WaypointListScreen.deletable(hit)) {
                if (Navigator.isTarget(hit)) Navigator.clear();
                WaypointManager.remove(hit);
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable("archud.wpl.deleted", hit.name), true);
                }
            }
            return true;
        }
        if (!typing && keyCode == GLFW.GLFW_KEY_L) {
            minecraft.setScreen(new WaypointListScreen(this));
            return true;
        }
        if (menuOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            menuOpen = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    static boolean configured() {
        return ArcConfig.get().minimap;
    }
}

package com.arc4io.archud.client.map;

import com.arc4io.archud.client.ArcClientNet;
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
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Полноэкранная карта мира.
 *  - сверху: слой/измерение, поиск по координатам или нику, «Готово»;
 *  - слева: боковая панель с вкладками «Метки» (поиск, фильтры, список) и «Игроки» (все не скрытые игроки
 *    с лицом, расстоянием и здоровьем; клик — показать на карте);
 *  - справа: инструменты (масштаб, к себе, сетка, след, зоны прогрузки, слизневые чанки, экспорт PNG);
 *  - снизу: строка состояния (координаты под курсором, масштаб, расстояние, синхронизация);
 *  - на карте: ЛКМ — перетаскивание, колесо — масштаб к курсору, ПКМ — меню, WASD — сдвиг.
 */
public class WorldMapScreen extends Screen implements com.arc4io.archud.client.screen.ScreenFit.Fits {
    private static final int TOP = 26, BOTTOM = 14, RAIL = 28;

    // настройки вида — живут всю сессию
    private static boolean showSide = true, showTrail = true, showSlime, showGrid = true, showZones = true;
    private static int tab; // 0 — метки, 1 — игроки

    private final Screen parent;
    private double centerX, centerZ;
    private float zoom = 2F;
    private boolean dragging, followPlayer = true;
    private double listScroll, playerScroll;
    private MapCache.Layer viewLayer;
    private final List<String[]> layers = new ArrayList<>();
    private int layerIndex;
    private EditBox gotoBox, searchBox;
    private int listTop;
    // контекстное меню
    private boolean menuOpen;
    private int menuX, menuY, menuWX, menuWZ;
    private Waypoint menuWaypoint;
    private UUID menuPlayer;
    private String menuPlayerName;
    private double[] menuPlayerPos;
    private final List<String> menuItems = new ArrayList<>();

    public WorldMapScreen(Screen parent) {
        super(Component.translatable("archud.map.title"));
        this.parent = parent;
    }

    private UUID pendingFocus;

    /** Открыть карту сразу на игроке (из списка игроков в меню паузы). */
    public WorldMapScreen focusOn(UUID player) {
        this.pendingFocus = player;
        tab = 1;
        showSide = true;
        return this;
    }

    // ------------------------------------------------------------------ геометрия
    private int sideW() {
        return showSide ? Mth.clamp(width / 4, 150, 210) : 0;
    }

    private int mapX0() {
        return sideW();
    }

    private int mapY0() {
        return TOP;
    }

    private int mapY1() {
        return height - BOTTOM;
    }

    private float mapCX() {
        return (sideW() + width) / 2F;
    }

    private float mapCY() {
        return (TOP + height - BOTTOM) / 2F;
    }

    private boolean inMap(double mx, double my) {
        return mx >= mapX0() && mx < width - RAIL && my >= mapY0() && my < mapY1();
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

    // ------------------------------------------------------------------ виджеты
    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 600, 300);
        if (minecraft.player != null && followPlayer) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        layers.clear();
        MapCache.Layer act = MapCache.active();
        if (act != null) layers.add(new String[]{act.dimension(), act.level()});
        for (String[] l : MapCache.savedLayers(WaypointManager.worldKey())) {
            boolean dup = false;
            for (String[] e : layers) if (e[0].equals(l[0]) && e[1].equals(l[1])) dup = true;
            if (!dup) layers.add(l);
        }
        if (pendingFocus != null) {
            for (PRow r : players()) {
                if (r.id().equals(pendingFocus)) {
                    focusPlayer(r, false);
                    highlightUntil = Util.getMillis() + 6000;
                }
            }
            pendingFocus = null;
        }
        String go = gotoBox != null ? gotoBox.getValue() : "";
        String search = searchBox != null ? searchBox.getValue() : "";

        // ---- верхняя панель
        addRenderableWidget(tip(new ArcButton(4, 4, 18, 18, Component.literal("☰"), b -> {
            showSide = !showSide;
            rebuildWidgets();
        }).toggle(() -> showSide), "archud.map.tip_list"));
        int titleW = font.width(title) + 8;
        int lx = 26 + titleW;
        addRenderableWidget(tip(new ArcButton(lx, 4, 128, 18, layerLabel(), b -> {
            if (layers.isEmpty()) return;
            layerIndex = (layerIndex + 1) % layers.size();
            String[] l = layers.get(layerIndex);
            viewLayer = MapCache.layer(WaypointManager.worldKey(), l[0], l[1]);
            followPlayer = viewingCurrent() && followPlayer;
            b.setMessage(layerLabel());
        }), "archud.map.tip_layer"));
        int doneX = width - 64;
        addRenderableWidget(new ArcButton(doneX, 4, 60, 18, Component.translatable("gui.done"), b -> onClose()));
        int navW = 74, goW = 52;
        int boxR = doneX - 8 - navW - 4 - goW - 4;
        int boxW = Mth.clamp(boxR - (lx + 128 + 10), 80, 200);
        int boxX = boxR - boxW;
        gotoBox = addRenderableWidget(new EditBox(font, boxX, 5, boxW, 16, Component.translatable("archud.map.goto")));
        gotoBox.setHint(Component.translatable("archud.map.goto_hint"));
        gotoBox.setMaxLength(64);
        gotoBox.setValue(go);
        addRenderableWidget(new ArcButton(boxR + 4, 4, goW, 18, Component.translatable("archud.map.goto"), b -> gotoCoords(false)));
        addRenderableWidget(new ArcButton(boxR + 8 + goW, 4, navW, 18, Component.translatable("archud.nav.go"), b -> gotoCoords(true)));

        // ---- инструменты справа
        int rx = width - RAIL + 3, ry = TOP + 6;
        addRenderableWidget(tip(new ArcButton(rx, ry, 22, 20, Component.literal("+"), b -> zoomAt(mapCX(), mapCY(), 1.25F)), "archud.map.tip_zoom_in"));
        addRenderableWidget(tip(new ArcButton(rx, ry += 22, 22, 20, Component.literal("−"), b -> zoomAt(mapCX(), mapCY(), 0.8F)), "archud.map.tip_zoom_out"));
        addRenderableWidget(tip(new ArcButton(rx, ry += 22, 22, 20, Component.literal("⌖"), b -> {
            followPlayer = true;
            viewLayer = null;
            layerIndex = 0;
            rebuildWidgets();
        }).toggle(() -> followPlayer && viewingCurrent()), "archud.map.center"));
        ry += 30;
        addRenderableWidget(tip(new ArcButton(rx, ry, 22, 20, Component.literal("▦"), b -> showGrid = !showGrid).toggle(() -> showGrid), "archud.map.tip_grid"));
        addRenderableWidget(tip(new ArcButton(rx, ry += 22, 22, 20, Component.literal("⋯"), b -> showTrail = !showTrail).toggle(() -> showTrail), "archud.map.tip_trail"));
        addRenderableWidget(tip(new ArcButton(rx, ry += 22, 22, 20, Component.literal("◎"), b -> showZones = !showZones).toggle(() -> showZones), "archud.map.tip_zones"));
        if (minecraft.getSingleplayerServer() != null) {
            addRenderableWidget(tip(new ArcButton(rx, ry += 22, 22, 20, Component.literal("◘"), b -> showSlime = !showSlime).toggle(() -> showSlime), "archud.map.tip_slime"));
        }
        ry += 30;
        addRenderableWidget(tip(new ArcButton(rx, ry, 22, 20, Component.literal("⤓"), b -> export()), "archud.map.export"));

        // ---- боковая панель
        searchBox = null;
        if (showSide) {
            int sw = sideW();
            int half = (sw - 12) / 2;
            addRenderableWidget(new ArcButton(4, TOP + 4, half, 18, Component.translatable("archud.map.tab.waypoints"), b -> {
                tab = 0;
                rebuildWidgets();
            }).toggle(() -> tab == 0));
            addRenderableWidget(new ArcButton(8 + half, TOP + 4, sw - 12 - half, 18, Component.translatable("archud.map.tab.players"), b -> {
                tab = 1;
                rebuildWidgets();
            }).toggle(() -> tab == 1));
            if (tab == 0) {
                searchBox = addRenderableWidget(new EditBox(font, 5, TOP + 27, sw - 10, 14, Component.translatable("archud.controls.search")));
                searchBox.setHint(Component.translatable("archud.controls.search"));
                searchBox.setValue(search);
                int by = height - BOTTOM - 22;
                addRenderableWidget(new ArcButton(4, by, sw - 34, 18, Component.translatable("archud.wp.add_here"), b -> {
                    Waypoint w = WaypointManager.createHere();
                    if (w != null) minecraft.setScreen(new WaypointEditScreen(this, w, true));
                }));
                addRenderableWidget(tip(new ArcButton(sw - 26, by, 22, 18, Component.literal("≡"), b -> minecraft.setScreen(new WaypointListScreen(this))),
                        "archud.wpl.short"));
            }
        }
    }

    private static ArcButton tip(ArcButton b, String key) {
        b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(key)));
        return b;
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

    // ------------------------------------------------------------------ переход / поиск игроков
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
        } catch (Exception notCoords) {
            List<PRow> found = findPlayers(gotoBox.getValue());
            if (!found.isEmpty()) focusPlayer(found.get(0), navigate);
        }
    }

    /** Игрок для панели и поиска. known — позиция известна (виден рядом или его сообщил сервер Arc HUD). */
    private record PRow(UUID id, String name, String dim, double x, double y, double z, float hp, boolean known, boolean self) {}

    private PRow highlighted;
    private long highlightUntil;

    /** Все не скрытые игроки: вы, видимые рядом, известные серверу Arc HUD, затем остальные онлайн (без позиции). */
    private List<PRow> players() {
        List<PRow> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        if (minecraft.player != null && minecraft.level != null) {
            var me = minecraft.player;
            out.add(new PRow(me.getUUID(), me.getGameProfile().getName(), currentDim(), me.getX(), me.getY(), me.getZ(), me.getHealth(), true, true));
            seen.add(me.getUUID());
            for (net.minecraft.world.entity.player.Player p : minecraft.level.players()) {
                if (!seen.add(p.getUUID()) || ArcClientNet.isHidden(p.getUUID())) continue;
                out.add(new PRow(p.getUUID(), p.getGameProfile().getName(), currentDim(), p.getX(), p.getY(), p.getZ(), p.getHealth(), true, false));
            }
        }
        for (var rp : ArcClientNet.players()) {
            if (!seen.add(rp.id())) continue;
            out.add(new PRow(rp.id(), rp.name(), rp.dimension(), rp.x(), rp.y(), rp.z(), rp.health(), true, false));
        }
        if (minecraft.getConnection() != null) {
            for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                UUID id = info.getProfile().getId();
                if (id == null || !seen.add(id) || ArcClientNet.isHidden(id)) continue;
                out.add(new PRow(id, info.getProfile().getName(), "", 0, 0, 0, -1, false, false));
            }
        }
        String dim = currentDim();
        double px = minecraft.player == null ? 0 : minecraft.player.getX(), pz = minecraft.player == null ? 0 : minecraft.player.getZ();
        out.sort(Comparator.comparingInt((PRow r) -> r.self() ? 0 : r.known() ? (r.dim().equals(dim) ? 1 : 2) : 3)
                .thenComparingDouble(r -> r.known() && r.dim().equals(dim) ? Math.hypot(r.x() - px, r.z() - pz) : 0)
                .thenComparing(PRow::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private List<PRow> findPlayers(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<PRow> out = new ArrayList<>();
        if (q.isEmpty() || q.matches("[-\\d\\s,;]+")) return out;
        for (PRow r : players()) {
            if (r.self() || !r.known() || !r.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(r);
        }
        out.sort(Comparator.comparingInt((PRow f) -> f.name().toLowerCase(Locale.ROOT).startsWith(q) ? 0 : 1)
                .thenComparing(PRow::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void focusPlayer(PRow f, boolean navigate) {
        if (!f.known()) return;
        if (!f.dim().equals(currentDim())) viewLayer = MapCache.layer(WaypointManager.worldKey(), f.dim(), "surface");
        else viewLayer = null;
        followPlayer = f.self();
        centerX = f.x();
        centerZ = f.z();
        if (zoom < 1F) zoom = 1F;
        highlighted = f.self() ? null : f;
        highlightUntil = Util.getMillis() + 4000;
        if (gotoBox != null && !f.self()) gotoBox.setValue("");
        if (navigate && !f.self()) Navigator.navigateTo((int) Math.floor(f.x()), (int) Math.floor(f.y()), (int) Math.floor(f.z()), f.dim());
    }

    /** Подсказки игроков под полем поиска; возвращает true, если клик попал в подсказку. */
    private boolean clickSuggestion(double mx, double my) {
        if (gotoBox == null || !gotoBox.isFocused()) return false;
        List<PRow> found = findPlayers(gotoBox.getValue());
        int x = gotoBox.getX(), y = gotoBox.getY() + 18;
        for (int i = 0; i < Math.min(6, found.size()); i++) {
            int ry = y + i * 13;
            if (mx >= x && mx < x + gotoBox.getWidth() && my >= ry && my < ry + 12) {
                focusPlayer(found.get(i), false);
                return true;
            }
        }
        return false;
    }

    private void renderSuggestions(GuiGraphics g, int mouseX, int mouseY, int accent) {
        if (gotoBox != null && gotoBox.isFocused()) {
            List<PRow> found = findPlayers(gotoBox.getValue());
            int x = gotoBox.getX(), y = gotoBox.getY() + 18, w = gotoBox.getWidth();
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            for (int i = 0; i < Math.min(6, found.size()); i++) {
                PRow f = found.get(i);
                int ry = y + i * 13;
                boolean hov = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 12;
                Draw.panel(g, x, ry, w, 12, hov ? Draw.PANEL_LIGHT : 0xF00B0F14, Draw.alpha(accent, hov ? 0xFF : 0x70));
                face(g, f.id(), x + 2, ry + 2, 8);
                Draw.small(g, font, f.name(), x + 13, ry + 3, 0xFF80DEEA, 0.7F);
                String where = where(f);
                Draw.small(g, font, where, x + w - 3 - font.width(where) * 0.6F, ry + 3.5F, Draw.TEXT_DIM, 0.6F);
            }
            if (found.isEmpty() && !gotoBox.getValue().isBlank() && !gotoBox.getValue().trim().matches("[-\\d\\s,;]+")) {
                Draw.panel(g, x, y, w, 12, 0xF00B0F14, 0x40FFFFFF);
                Draw.small(g, font, Component.translatable("archud.map.no_player").getString(), x + 3, y + 3, Draw.TEXT_DIM, 0.65F);
            }
            g.pose().popPose();
        }
    }

    private String where(PRow f) {
        if (!f.known()) return Component.translatable("archud.map.players.unknown").getString();
        if (f.self()) return Component.translatable("archud.map.players.you").getString();
        if (f.dim().equals(currentDim()) && minecraft.player != null) {
            return Math.round(Math.hypot(f.x() - minecraft.player.getX(), f.z() - minecraft.player.getZ())) + " m";
        }
        return HudRenderer.pretty(f.dim().substring(f.dim().indexOf(':') + 1));
    }

    /** Лицо игрока (скин с сервера, иначе стандартный). */
    private void face(GuiGraphics g, UUID id, int x, int y, int size) {
        ResourceLocation skin = null;
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(id);
            if (info != null) skin = info.getSkinLocation();
        }
        if (skin == null) skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(id);
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    private double[] screenToWorld(double mx, double my) {
        return new double[]{centerX + (mx - mapCX()) / zoom, centerZ + (my - mapCY()) / zoom};
    }

    @Override
    public void tick() {
        long win = minecraft.getWindow().getWindow();
        double step = 12 / zoom;
        if (!typing()) {
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_W) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_UP)) pan(0, -step);
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_S) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_DOWN)) pan(0, step);
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_A) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT)) pan(-step, 0);
            if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_D) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT)) pan(step, 0);
        }
    }

    private boolean typing() {
        return (gotoBox != null && gotoBox.isFocused()) || (searchBox != null && searchBox.isFocused());
    }

    private void pan(double dx, double dz) {
        followPlayer = false;
        centerX += dx;
        centerZ += dz;
    }

    // ------------------------------------------------------------------ отрисовка
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int accent = Theme.accent();
        g.fill(0, 0, width, height, 0xFF06080C);
        if (followPlayer && viewingCurrent() && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        int x0 = mapX0(), y0 = mapY0(), y1 = mapY1();
        float cx = mapCX(), cy = mapCY();
        float radius = Math.max(width - x0, y1 - y0) / 1.4F;
        RenderSystem.enableBlend();
        g.enableScissor(x0, y0, width, y1);
        MapCache.Layer layer = viewLayer != null ? viewLayer : MapCache.active();
        if (viewLayer == null) MapView.drawTerrain(g, cx, cy, centerX, centerZ, zoom, 0F, radius);
        else if (layer != null) MapView.drawTerrain(g, layer, cx, cy, centerX, centerZ, zoom, 0F, radius);
        if (showGrid) drawGrid(g, x0, y0, y1, cx, cy);
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
        if (highlighted != null && Util.getMillis() < highlightUntil && highlighted.dim().equals(viewDim())) {
            float[] s = MapView.toScreen(highlighted.x(), highlighted.z(), centerX, centerZ, zoom, 0F);
            int hx = Math.round(cx + s[0]), hy = Math.round(cy + s[1]);
            int r = 6 + Math.round(Draw.pulse(700) * 6);
            Draw.outline(g, hx - r, hy - r, r * 2, r * 2, 0xFF80DEEA);
            Draw.smallCentered(g, font, highlighted.name(), hx, hy - r - 8, 0xFF80DEEA, 0.75F);
        }
        // роза ветров и масштабная линейка
        Draw.smallCentered(g, font, "N", (x0 + width - RAIL) / 2F, y0 + 4, 0xFFFF5252, 0.9F);
        scaleBar(g, x0 + 8, y1 - 10, accent);
        g.disableScissor();

        // ---- верхняя панель
        g.fill(0, 0, width, TOP, 0xF00B0F14);
        g.fill(0, TOP - 1, width, TOP, Draw.alpha(accent, 0x90));
        g.drawString(font, title, 26, 9, Draw.alpha(accent, 0xFF), true);

        // ---- инструменты справа
        g.fill(width - RAIL, TOP, width, y1, 0xC00B0F14);
        g.fill(width - RAIL, TOP, width - RAIL + 1, y1, Draw.alpha(accent, 0x50));

        // ---- строка состояния
        g.fill(0, y1, width, height, 0xF00B0F14);
        g.fill(0, y1, width, y1 + 1, Draw.alpha(accent, 0x50));
        String info;
        if (inMap(mouseX, mouseY)) {
            double[] w = screenToWorld(mouseX, mouseY);
            int bx = (int) Math.floor(w[0]), bz = (int) Math.floor(w[1]);
            info = "X " + bx + "  Z " + bz;
            if (minecraft.player != null && viewingCurrent()) info += "   ·   " + Math.round(Math.hypot(bx - minecraft.player.getX(), bz - minecraft.player.getZ())) + " m";
            if (viewDim().equals("minecraft:overworld")) info += "   ·   " + Component.translatable("archud.map.nether_short").getString() + " " + Math.floorDiv(bx, 8) + ", " + Math.floorDiv(bz, 8);
            else if (viewDim().equals("minecraft:the_nether")) info += "   ·   " + Component.translatable("archud.map.overworld_short").getString() + " " + bx * 8 + ", " + bz * 8;
        } else {
            info = Component.translatable("archud.map.hint").getString();
        }
        info += "   ·   ×" + String.format(Locale.ROOT, zoom >= 1 ? "%.1f" : "%.2f", zoom);
        Draw.small(g, font, info, 5, y1 + 4, Draw.TEXT_DIM, 0.7F);
        boolean sync = ArcClientNet.mapSyncActive();
        String st = sync ? Component.translatable("archud.map.sync_on", ArcClientNet.players().size()).getString()
                : Component.translatable("archud.map.sync_off").getString();
        if (ArcClientNet.waypointsOnServer()) st += "   ·   " + Component.translatable("archud.map.wp_on_server").getString();
        Draw.small(g, font, st, width - 5 - font.width(st) * 0.7F, y1 + 4, sync ? 0xFF9CFF5A : Draw.TEXT_DIM, 0.7F);

        // ---- боковая панель
        if (showSide) {
            int sw = sideW();
            g.fill(0, TOP, sw, y1, 0xF20B0F14);
            g.fill(sw - 1, TOP, sw, y1, Draw.alpha(accent, 0x70));
            if (tab == 0) renderWaypointsTab(g, sw, mouseX, mouseY, accent);
            else renderPlayersTab(g, sw, mouseX, mouseY, accent);
        }

        super.render(g, mouseX, mouseY, partialTick);
        renderSuggestions(g, mouseX, mouseY, accent);
        if (menuOpen) renderMenu(g, mouseX, mouseY, accent);
    }

    /** Линейка масштаба: круглое число блоков ≈ 60 пикселей. */
    private void scaleBar(GuiGraphics g, int x, int y, int accent) {
        double blocks = 60 / zoom;
        double[] nice = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};
        double b = nice[nice.length - 1];
        for (double n : nice) {
            if (n >= blocks * 0.6) {
                b = n;
                break;
            }
        }
        int px = (int) Math.round(b * zoom);
        g.fill(x - 1, y - 1, x + px + 1, y + 3, 0x90000000);
        g.fill(x, y, x + px, y + 2, Draw.alpha(accent, 0xFF));
        g.fill(x, y - 3, x + 1, y + 2, 0xFFFFFFFF);
        g.fill(x + px - 1, y - 3, x + px, y + 2, 0xFFFFFFFF);
        String label = b >= 1000 ? (int) (b / 1000) + " km" : (int) b + " m";
        Draw.small(g, font, label, x + px + 4, y - 2, 0xFFFFFFFF, 0.65F);
    }

    private void drawGrid(GuiGraphics g, int x0, int y0, int y1, float cx, float cy) {
        int step = zoom >= 4 ? 16 : zoom >= 0.5F ? 128 : 512;
        double left = centerX - (cx - x0) / zoom, top = centerZ - (cy - y0) / zoom;
        int color = step == 16 ? 0x16FFFFFF : 0x12FFFFFF;
        for (double wx = Math.floor(left / step) * step; wx < left + (width - x0) / zoom; wx += step) {
            int sx = (int) (cx + (wx - centerX) * zoom);
            g.fill(sx, y0, sx + 1, y1, color);
        }
        for (double wz = Math.floor(top / step) * step; wz < top + (y1 - y0) / zoom; wz += step) {
            int sy = (int) (cy + (wz - centerZ) * zoom);
            g.fill(x0, sy, width, sy + 1, color);
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

    // ------------------------------------------------------------------ вкладка «Метки»
    private List<Waypoint> listed() {
        String q = searchBox == null ? "" : searchBox.getValue().toLowerCase(Locale.ROOT);
        List<Waypoint.Source> allowed = WaypointManager.filterSources();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : WaypointManager.all()) {
            if (!WaypointManager.sourceVisible(w.source) || (!allowed.contains(w.source) && w.source != Waypoint.Source.TEMP)) continue;
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

    /** Фильтры-«чипы» с переносом строк: {x, y, w} для каждого типа из filterSources(). */
    private List<int[]> chips(int sw) {
        List<int[]> out = new ArrayList<>();
        int x = 5, y = TOP + 46;
        for (Waypoint.Source s : WaypointManager.filterSources()) {
            String label = Component.translatable("archud.wp.src." + s.name().toLowerCase(Locale.ROOT)).getString();
            int tw = Math.round(font.width(label) * 0.6F) + 8;
            if (x + tw > sw - 5 && x > 5) {
                x = 5;
                y += 14;
            }
            out.add(new int[]{x, y, tw});
            x += tw + 3;
        }
        return out;
    }

    private void renderWaypointsTab(GuiGraphics g, int sw, int mouseX, int mouseY, int accent) {
        List<Waypoint.Source> srcs = WaypointManager.filterSources();
        List<int[]> rects = chips(sw);
        for (int i = 0; i < srcs.size(); i++) {
            int[] r = rects.get(i);
            boolean on = WaypointManager.sourceVisible(srcs.get(i));
            String label = Component.translatable("archud.wp.src." + srcs.get(i).name().toLowerCase(Locale.ROOT)).getString();
            Draw.panel(g, r[0], r[1], r[2], 12, on ? Draw.alpha(accent, 0x50) : Draw.PANEL, on ? Draw.alpha(accent, 0xFF) : 0x30FFFFFF);
            Draw.small(g, font, label, r[0] + 4, r[1] + 3, on ? 0xFFFFFFFF : Draw.TEXT_DIM, 0.6F);
        }
        listTop = (rects.isEmpty() ? TOP + 46 : rects.get(rects.size() - 1)[1] + 12) + 6;
        int bottom = height - BOTTOM - 26;
        List<Waypoint> list = listed();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, list.size() * 24 - (bottom - listTop)));
        if (list.isEmpty()) {
            Draw.wrapped(g, font, Component.translatable("archud.map.wp_empty").getString(), 8, listTop + 6, sw - 16, Draw.TEXT_DIM, 0.65F);
        }
        g.enableScissor(0, listTop, sw, bottom);
        int y = listTop - (int) listScroll;
        String dim = currentDim();
        for (Waypoint wp : list) {
            if (y + 23 >= listTop && y < bottom) {
                boolean hov = mouseX >= 0 && mouseX < sw && mouseY >= y && mouseY < y + 23 && mouseY >= listTop && mouseY < bottom;
                g.fill(3, y, sw - 4, y + 23, hov ? 0x26FFFFFF : 0x0CFFFFFF);
                if (Navigator.isTarget(wp)) g.fill(3, y, 5, y + 23, 0xFF000000 | wp.color);
                MapView.diamond(g, 14, y + 11, 6, wp.color);
                Draw.smallCentered(g, font, wp.initials(), 14.5F, y + 9, 0xFF000000, 0.5F);
                String n = wp.name;
                int room = sw - 32;
                if (font.width(n) > room) n = font.plainSubstrByWidth(n, room - font.width("…")) + "…";
                g.drawString(font, n, 25, y + 3, wp.visible ? Draw.TEXT : Draw.TEXT_DIM, true);
                String info = dim.equals(wp.dimension) && minecraft.player != null
                        ? Math.round(wp.distanceTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ())) + " m"
                        : HudRenderer.pretty(wp.dimension.substring(wp.dimension.indexOf(':') + 1));
                Draw.small(g, font, info + "  ·  " + wp.x + " " + wp.y + " " + wp.z, 25, y + 13, Draw.TEXT_DIM, 0.6F);
            }
            y += 24;
        }
        g.disableScissor();
    }

    private Waypoint waypointRowAt(double mx, double my) {
        if (!showSide || tab != 0 || mx >= sideW() || my < listTop || my >= height - BOTTOM - 26) return null;
        int idx = (int) ((my - listTop + listScroll) / 24);
        List<Waypoint> list = listed();
        return idx >= 0 && idx < list.size() ? list.get(idx) : null;
    }

    // ------------------------------------------------------------------ вкладка «Игроки»
    private static final int PROW_H = 28;

    private void renderPlayersTab(GuiGraphics g, int sw, int mouseX, int mouseY, int accent) {
        List<PRow> list = players();
        int top = TOP + 28, bottom = height - BOTTOM - 4;
        boolean remoteHint = minecraft.getConnection() != null && !minecraft.isSingleplayer() && !ArcClientNet.positionsActive();
        if (remoteHint) bottom -= 24;
        playerScroll = Mth.clamp(playerScroll, 0, Math.max(0, list.size() * (PROW_H + 2) - (bottom - top)));
        g.enableScissor(0, top, sw, bottom);
        int y = top - (int) playerScroll;
        for (PRow r : list) {
            if (y + PROW_H >= top && y < bottom) {
                boolean hov = mouseX < sw && mouseY >= y && mouseY < y + PROW_H && mouseY >= top && mouseY < bottom;
                g.fill(3, y, sw - 4, y + PROW_H, r.self() ? Draw.alpha(accent, 0x30) : hov ? 0x26FFFFFF : 0x0CFFFFFF);
                if (r.self()) g.fill(3, y, 5, y + PROW_H, Draw.alpha(accent, 0xFF));
                face(g, r.id(), 9, y + 6, 16);
                if (!r.known()) g.fill(9, y + 6, 25, y + 22, 0x80000000);
                boolean talking = com.arc4io.archud.compat.VoiceCompat.isTalking(r.id());
                if (talking) MapView.talkRing(g, 17, y + 14);
                String n = r.name();
                int room = sw - 34 - (hov && r.known() && !r.self() ? 40 : 0);
                if (font.width(n) > room) n = font.plainSubstrByWidth(n, room - font.width("…")) + "…";
                g.drawString(font, n, 30, y + 5, r.known() ? (r.self() ? Draw.alpha(accent, 0xFF) : Draw.TEXT) : Draw.TEXT_DIM, true);
                String line = where(r);
                if (r.hp() >= 0) line += "   ❤ " + Math.round(r.hp());
                Draw.small(g, font, line, 30, y + 16, Draw.TEXT_DIM, 0.62F);
                if (hov && r.known() && !r.self()) {
                    // ◎ показать · ➤ навигация
                    rowIcon(g, sw - 42, y + 7, "◎", mouseX, mouseY, accent);
                    rowIcon(g, sw - 22, y + 7, "➤", mouseX, mouseY, accent);
                }
            }
            y += PROW_H + 2;
        }
        g.disableScissor();
        if (remoteHint) Draw.wrapped(g, font, Component.translatable("archud.map.players.need_server").getString(), 6, bottom + 4, sw - 12, Draw.TEXT_DIM, 0.6F);
    }

    private void rowIcon(GuiGraphics g, int x, int y, String icon, int mouseX, int mouseY, int accent) {
        boolean hov = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 14;
        Draw.panel(g, x, y, 16, 14, hov ? Draw.alpha(accent, 0x70) : Draw.PANEL, Draw.alpha(accent, hov ? 0xFF : 0x70));
        g.drawCenteredString(font, icon, x + 8, y + 3, 0xFFFFFFFF);
    }

    private boolean clickPlayers(double mx, double my) {
        List<PRow> list = players();
        int top = TOP + 28, sw = sideW();
        if (my < top) return false;
        int idx = (int) ((my - top + playerScroll) / (PROW_H + 2));
        if (idx < 0 || idx >= list.size()) return true;
        PRow r = list.get(idx);
        int y = top - (int) playerScroll + idx * (PROW_H + 2);
        if (r.known() && !r.self() && my >= y + 7 && my < y + 21 && mx >= sw - 22 && mx < sw - 6) {
            focusPlayer(r, true);
            return true;
        }
        focusPlayer(r, false);
        return true;
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
            menuItems.add("tp_smart");
            menuItems.add("nav_player");
            menuOpen = true;
            return;
        }
        if (wp != null) {
            menuItems.add("tp_smart");
            menuItems.add(Navigator.isTarget(wp) ? "nav_stop" : "nav");
            menuItems.add("edit");
            menuItems.add("share");
        } else {
            menuItems.add("tp_smart");
            menuItems.add("nav_here");
            menuItems.add("add");
            menuItems.add("share_coords");
        }
        menuOpen = true;
    }

    // ------------------------------------------------------------------ телепорт из меню (без создания метки)
    /** 2 — Tempad, 1 — команда (оператор), 0 — недоступно. */
    private int tpMode() {
        if (minecraft.player == null) return 0;
        if (com.arc4io.archud.client.ArcClientNet.tempadDoors() && com.arc4io.archud.compat.TempadCompat.find(minecraft.player) != null) return 2;
        if (minecraft.player.hasPermissions(2)) return 1;
        return 0;
    }

    private String tpKey() {
        String base = menuPlayer != null ? "tp_to_player" : menuWaypoint != null ? "tp_to_wp" : "tp_here";
        return base + switch (tpMode()) {
            case 2 -> "_tempad";
            case 1 -> "_op";
            default -> "_none";
        };
    }

    private void smartTeleport() {
        int mode = tpMode();
        if (mode == 0) {
            // объяснить, почему нельзя
            String why = !com.arc4io.archud.client.ArcClientNet.tempadDoors() ? "archud.tp.need_server_or_op" : "archud.tp.need_tempad";
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable(why), true);
            return;
        }
        if (menuPlayer != null) {
            if (mode == 2) com.arc4io.archud.client.ArcClientNet.tempadDoorToPlayer(menuPlayer);
            else if (minecraft.getConnection() != null) minecraft.getConnection().sendCommand("tp @s " + menuPlayerName);
        } else if (menuWaypoint != null) {
            if (mode == 2) TempadCompat.openDoor(menuWaypoint);
            else com.arc4io.archud.client.ArcClientNet.safeTeleport(menuWaypoint.dimension, menuWaypoint.x, menuWaypoint.y, menuWaypoint.z);
        } else {
            int noHint = com.arc4io.archud.server.TeleportSafety.NO_HINT;
            if (mode == 2) {
                TempadCompat.openDoor(new Waypoint(menuWX + ", " + menuWZ, menuWX, noHint, menuWZ, 0xFFFF8800, viewDim(), Waypoint.Source.TEMP));
            } else {
                com.arc4io.archud.client.ArcClientNet.safeTeleport(viewDim(), menuWX, noHint, menuWZ);
            }
        }
        minecraft.setScreen(null);
    }

    /** Игрок под курсором (виден рядом или известен серверу Arc HUD). */
    private void findPlayerAt(int mx, int my) {
        float cx = mapCX(), cy = mapCY();
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
            String item = menuItems.get(i);
            boolean dim = item.equals("tp_smart") && tpMode() == 0;
            g.drawString(font, Component.translatable("archud.map.menu." + (item.equals("tp_smart") ? tpKey() : item)), x + 6, iy + 3,
                    dim ? Draw.TEXT_DIM : item.equals("tp_smart") ? 0xFF80DEEA : Draw.TEXT, true);
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
            case "tp_smart" -> smartTeleport();
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
        float cx = mapCX(), cy = mapCY();
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
        int mapW = width - RAIL - mapX0(), mapH = mapY1() - mapY0();
        double minX = centerX - (mapCX() - mapX0()) / zoom, minZ = centerZ - (mapCY() - mapY0()) / zoom;
        int w = (int) Math.min(4096, Math.ceil(mapW / zoom)), h = (int) Math.min(4096, Math.ceil(mapH / zoom));
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
        if (clickSuggestion(mx, my)) return true;
        if (super.mouseClicked(mx, my, button)) return true;
        if (my < TOP || my >= mapY1() || mx >= width - RAIL) return true; // панели
        if (showSide && mx < sideW()) {
            if (tab == 1) return clickPlayers(mx, my);
            // фильтры
            List<Waypoint.Source> srcs = WaypointManager.filterSources();
            List<int[]> rects = chips(sideW());
            for (int i = 0; i < srcs.size(); i++) {
                int[] r = rects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + 12) {
                    WaypointManager.toggleSource(srcs.get(i));
                    return true;
                }
            }
            Waypoint w = waypointRowAt(mx, my);
            if (w != null) {
                if (button == 1) openMenu((int) mx, (int) my, w);
                else {
                    if (!w.dimension.equals(currentDim())) viewLayer = MapCache.layer(WaypointManager.worldKey(), w.dimension, "surface");
                    else viewLayer = null;
                    followPlayer = false;
                    centerX = w.x + 0.5;
                    centerZ = w.z + 0.5;
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
        if (showSide && mx < sideW()) {
            if (tab == 0) listScroll -= delta * 24;
            else playerScroll -= delta * (PROW_H + 2);
            return true;
        }
        if (!inMap(mx, my)) return true;
        zoomAt(mx, my, delta > 0 ? 1.25F : 0.8F);
        return true;
    }

    private void zoomAt(double mx, double my, float factor) {
        float old = zoom;
        zoom = Mth.clamp(zoom * factor, 0.0625F, 16F);
        float cx = mapCX(), cy = mapCY();
        centerX += (mx - cx) / old - (mx - cx) / zoom;
        centerZ += (my - cy) / old - (my - cy) / zoom;
        if (old != zoom && (mx != cx || my != cy)) followPlayer = false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = typing();
        if (menuOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            menuOpen = false;
            return true;
        }
        if (!typing && ArcKeys.WORLD_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (!typing && (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD)) {
            zoomAt(mapCX(), mapCY(), 1.25F);
            return true;
        }
        if (!typing && (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT)) {
            zoomAt(mapCX(), mapCY(), 0.8F);
            return true;
        }
        if (gotoBox.isFocused() && keyCode == GLFW.GLFW_KEY_ENTER) {
            gotoCoords(false);
            return true;
        }
        if (!typing && keyCode == GLFW.GLFW_KEY_TAB) {
            tab = 1 - tab;
            showSide = true;
            rebuildWidgets();
            return true;
        }
        if (!typing && keyCode == GLFW.GLFW_KEY_DELETE) {
            double mx = minecraft.mouseHandler.xpos() * width / minecraft.getWindow().getScreenWidth();
            double my = minecraft.mouseHandler.ypos() * height / minecraft.getWindow().getScreenHeight();
            Waypoint hit = showSide && mx < sideW() ? waypointRowAt(mx, my) : waypointAt(mx, my);
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

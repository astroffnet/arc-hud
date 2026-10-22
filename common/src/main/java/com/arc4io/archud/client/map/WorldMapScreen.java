package com.arc4io.archud.client.map;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Полноэкранная карта мира: перетаскивание ЛКМ, масштаб колесом, ПКМ — новая метка,
 * клик по метке — редактирование, список меток справа.
 */
public class WorldMapScreen extends Screen {
    private final Screen parent;
    private double centerX, centerZ;
    private float zoom = 2F;
    private boolean dragging;
    private boolean followPlayer = true;
    private boolean showList = true;
    private double listScroll;

    public WorldMapScreen(Screen parent) {
        super(Component.translatable("archud.map.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (minecraft.player != null && followPlayer) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        addRenderableWidget(new ArcButton(6, height - 24, 90, 18, Component.translatable("archud.map.center"), b -> {
            followPlayer = true;
            if (minecraft.player != null) {
                centerX = minecraft.player.getX();
                centerZ = minecraft.player.getZ();
            }
        }));
        addRenderableWidget(new ArcButton(100, height - 24, 110, 18, Component.translatable("archud.wp.add_here"), b -> {
            Waypoint w = WaypointManager.addHere();
            if (w != null) minecraft.setScreen(new WaypointEditScreen(this, w, false));
        }));
        addRenderableWidget(new ArcButton(214, height - 24, 90, 18, Component.translatable("archud.map.list"), b -> showList = !showList));
        addRenderableWidget(new ArcButton(width - 96, height - 24, 90, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    private int listW() {
        return showList ? Math.min(170, width / 4) : 0;
    }

    private double[] screenToWorld(double mx, double my) {
        float cx = (width - listW()) / 2F, cy = height / 2F;
        return new double[]{centerX + (mx - cx) / zoom, centerZ + (my - cy) / zoom};
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int accent = Theme.accent();
        int mapW = width - listW();
        g.fill(0, 0, width, height, 0xFF06080C);
        if (followPlayer && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        float cx = mapW / 2F, cy = height / 2F;
        RenderSystem.enableBlend();
        g.enableScissor(0, 0, mapW, height);
        MapView.drawTerrain(g, cx, cy, centerX, centerZ, zoom, 0F, Math.max(mapW, height) / 1.4F);
        // сетка регионов
        if (zoom >= 1F) {
            double left = centerX - cx / zoom, top = centerZ - cy / zoom;
            int step = 128;
            for (double wx = Math.floor(left / step) * step; wx < left + mapW / zoom; wx += step) {
                int sx = (int) (cx + (wx - centerX) * zoom);
                g.fill(sx, 0, sx + 1, height, 0x12FFFFFF);
            }
            for (double wz = Math.floor(top / step) * step; wz < top + height / zoom; wz += step) {
                int sy = (int) (cy + (wz - centerZ) * zoom);
                g.fill(0, sy, mapW, sy + 1, 0x12FFFFFF);
            }
        }
        MapView.drawEntities(g, minecraft, cx, cy, centerX, centerZ, zoom, 0F, Math.max(mapW, height));
        MapView.drawWaypoints(g, font, cx, cy, centerX, centerZ, zoom, 0F, Math.max(mapW, height), false, true);
        if (minecraft.player != null) {
            float[] ps = MapView.toScreen(minecraft.player.getX(), minecraft.player.getZ(), centerX, centerZ, zoom, 0F);
            MapView.drawPlayerArrow(g, cx + ps[0], cy + ps[1], minecraft.player.getYRot() + 180F, Draw.alpha(accent, 0xFF));
        }
        g.disableScissor();

        // шапка
        Draw.panel(g, 4, 4, Math.min(mapW - 8, 260), 22, Draw.PANEL, Draw.alpha(accent, 0x90));
        g.drawString(font, title, 10, 8, Draw.alpha(accent, 0xFF), true);
        double[] w = screenToWorld(mouseX, mouseY);
        if (mouseX < mapW) {
            String pos = (int) Math.floor(w[0]) + ", " + (int) Math.floor(w[1]);
            Draw.small(g, font, pos + "   ×" + String.format("%.2f", zoom), 10, 18, Draw.TEXT_DIM, 0.6F);
        }
        Draw.small(g, font, Component.translatable("archud.map.hint").getString(), 6, height - 32, Draw.TEXT_DIM, 0.6F);

        if (showList) renderList(g, mapW, mouseX, mouseY, accent);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics g, int x, int mouseX, int mouseY, int accent) {
        int lw = listW();
        Draw.panel(g, x, 0, lw, height, 0xF00B0F14, Draw.alpha(accent, 0x70));
        g.drawString(font, Component.translatable("archud.wp.title"), x + 6, 6, Draw.TEXT, true);
        List<Waypoint> list = WaypointManager.all();
        String dim = WaypointManager.currentDimension();
        int top = 20, bottom = height - 30;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, list.size() * 22 - (bottom - top)));
        g.enableScissor(x, top, x + lw, bottom);
        int y = top - (int) listScroll;
        for (Waypoint wp : list) {
            boolean hov = mouseX >= x && mouseX < x + lw && mouseY >= y && mouseY < y + 21 && mouseY >= top && mouseY < bottom;
            if (hov) g.fill(x + 2, y, x + lw - 2, y + 21, 0x20FFFFFF);
            MapView.diamond(g, x + 9, y + 10, 4, wp.color);
            String n = wp.name;
            if (font.width(n) > lw - 24) n = font.plainSubstrByWidth(n, lw - 28) + "…";
            g.drawString(font, n, x + 18, y + 2, wp.visible ? Draw.TEXT : Draw.TEXT_DIM, true);
            String info = dim.equals(wp.dimension) && minecraft.player != null
                    ? Math.round(wp.distanceTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ())) + " m"
                    : wp.dimension.substring(wp.dimension.indexOf(':') + 1);
            Draw.small(g, font, info + "  ·  " + Component.translatable("archud.wp.src." + wp.source.name().toLowerCase()).getString(),
                    x + 18, y + 12, Draw.TEXT_DIM, 0.6F);
            y += 22;
        }
        g.disableScissor();
    }

    private Waypoint waypointAt(double mx, double my) {
        float cx = (width - listW()) / 2F, cy = height / 2F;
        for (Waypoint w : WaypointManager.inDimension()) {
            float[] s = MapView.toScreen(w.x + 0.5, w.z + 0.5, centerX, centerZ, zoom, 0F);
            if (Math.abs(cx + s[0] - mx) <= 5 && Math.abs(cy + s[1] - my) <= 5) return w;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int mapW = width - listW();
        if (mx >= mapW && showList) {
            int idx = (int) ((my - 20 + listScroll) / 22);
            List<Waypoint> list = WaypointManager.all();
            if (my >= 20 && idx >= 0 && idx < list.size()) {
                Waypoint w = list.get(idx);
                if (button == 1) minecraft.setScreen(new WaypointEditScreen(this, w, false));
                else if (w.dimension.equals(WaypointManager.currentDimension())) {
                    followPlayer = false;
                    centerX = w.x;
                    centerZ = w.z;
                }
            }
            return true;
        }
        Waypoint hit = waypointAt(mx, my);
        if (hit != null) {
            minecraft.setScreen(new WaypointEditScreen(this, hit, false));
            return true;
        }
        if (button == 1) {
            double[] w = screenToWorld(mx, my);
            int x = (int) Math.floor(w[0]), z = (int) Math.floor(w[1]);
            int y = minecraft.level != null && minecraft.level.hasChunk(x >> 4, z >> 4)
                    ? minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
                    : minecraft.player != null ? minecraft.player.getBlockY() : 64;
            int n = WaypointManager.user().size() + 1;
            Waypoint wp = new Waypoint(Component.translatable("archud.wp.default", n).getString(), x, y, z,
                    Waypoint.PALETTE[(n - 1) % Waypoint.PALETTE.length], WaypointManager.currentDimension(), Waypoint.Source.USER);
            minecraft.setScreen(new WaypointEditScreen(this, wp, true));
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
        float old = zoom;
        zoom = Mth.clamp(zoom * (delta > 0 ? 1.25F : 0.8F), 0.125F, 16F);
        // масштаб к курсору
        float cx = (width - listW()) / 2F, cy = height / 2F;
        centerX += (mx - cx) / old - (mx - cx) / zoom;
        centerZ += (my - cy) / old - (my - cy) / zoom;
        if (old != zoom) followPlayer = false;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (com.arc4io.archud.client.ArcKeys.WORLD_MAP.matches(keyCode, scanCode)) {
            onClose();
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
}

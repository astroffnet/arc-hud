package com.arc4io.archud.client.map;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Метки в мире — как экранные маркеры поверх игры (а не 3D-объекты в мире):
 *  - всегда читаются: не прячутся в блоках, одинакового размера на любом расстоянии;
 *  - ромб с буквами и расстояние, тонкая «нить» к земле в точке метки;
 *  - подписи не налезают друг на друга: ближние важнее, остальные сдвигаются или сворачиваются до значка;
 *  - метка у прицела раскрывается в карточку (имя, расстояние, высота, координаты, тип);
 *  - метки за краем экрана (и цель навигатора) прижимаются к краю со стрелкой.
 * Во время отрисовки мира запоминаются матрицы камеры, а в HUD точки проецируются на экран.
 */
public final class WaypointWorldRenderer {
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJ = new Matrix4f();
    private static Vec3 camPos = Vec3.ZERO;
    private static long capturedFrame = -1;
    private static long frame;

    private record Marker(Waypoint w, float x, float y, float groundX, float groundY, boolean onScreen, double dist, boolean target,
                          float edgeAngle, float fade) {}

    private WaypointWorldRenderer() {}

    /** Вызывается во время отрисовки мира: запоминаем матрицы для проекции. */
    public static void capture(PoseStack pose, Matrix4f projection, Camera camera) {
        VIEW.set(pose.last().pose());
        PROJ.set(projection);
        camPos = camera.getPosition();
        capturedFrame = frame;
    }

    /** {x, y, w} в GUI-координатах; w ≤ 0 — точка позади камеры. */
    private static float[] project(double wx, double wy, double wz, int sw, int sh) {
        Vector4f v = new Vector4f((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z), 1F);
        VIEW.transform(v);
        PROJ.transform(v);
        float w = v.w();
        if (Math.abs(w) < 1e-5F) w = 1e-5F;
        float nx = v.x() / w, ny = v.y() / w;
        return new float[]{(nx * 0.5F + 0.5F) * sw, (1F - (ny * 0.5F + 0.5F)) * sh, w};
    }

    /** Рисуется из HUD каждый кадр. */
    public static void renderOverlay(GuiGraphics g) {
        frame++;
        ArcConfig cfg = ArcConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.worldWaypoints || mc.level == null || mc.player == null || mc.options.hideGui) return;
        if (capturedFrame < 0 || frame - capturedFrame > 3) return; // матрицы не обновлялись
        int sw = g.guiWidth(), sh = g.guiHeight();
        Font font = mc.font;
        float cx = sw / 2F, cy = sh / 2F;

        List<Marker> markers = new ArrayList<>();
        for (Waypoint w : WaypointManager.inDimension()) {
            double wx = w.x + 0.5, wy = w.y + 1.6, wz = w.z + 0.5;
            double dist = Math.sqrt(camPos.distanceToSqr(w.x + 0.5, w.y + 0.5, w.z + 0.5));
            boolean target = Navigator.isTarget(w);
            if (cfg.waypointMaxDistance > 0 && dist > cfg.waypointMaxDistance && !target) continue;
            if (dist < 2.5 && !target) continue;
            float[] p = project(wx, wy, wz, sw, sh);
            float[] base = project(w.x + 0.5, w.y, w.z + 0.5, sw, sh);
            boolean behind = p[2] <= 0;
            boolean on = !behind && p[0] >= 0 && p[0] <= sw && p[1] >= 0 && p[1] <= sh;
            float angle = 0;
            if (!on) {
                if (!cfg.waypointEdgeArrows) continue; // вне поля зрения — не показываем
                float dx = p[0] - cx, dy = p[1] - cy;
                if (behind) {
                    dx = -dx;
                    dy = -dy;
                    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) dy = 1;
                }
                angle = (float) Math.atan2(dy, dx);
                float m = 14;
                float kx = (cx - m) / Math.max(1e-3F, Math.abs(dx)), ky = (cy - m) / Math.max(1e-3F, Math.abs(dy));
                float k = Math.min(kx, ky);
                p[0] = cx + dx * k;
                p[1] = cy + dy * k;
            }
            // чем дальше от центра взгляда, тем прозрачнее: у краёв экрана метки почти не видны и не отвлекают
            float fade = 1F;
            if (on) {
                float r = Math.max(Math.abs(p[0] - cx) / cx, Math.abs(p[1] - cy) / cy);
                fade = r < 0.55F ? 1F : Math.max(0F, 1F - (r - 0.55F) / 0.4F);
                if (fade <= 0.02F) continue;
            }
            markers.add(new Marker(w, p[0], p[1], base[0], base[1], on && base[2] > 0, dist, target, angle, fade));
        }
        if (markers.isEmpty()) return;

        // фокус — ближе всего к прицелу
        Marker focus = null;
        float best = 26 * 26;
        for (Marker m : markers) {
            if (!m.onScreen()) continue;
            float d = (m.x() - cx) * (m.x() - cx) + (m.y() - cy) * (m.y() - cy);
            if (d < best) {
                best = d;
                focus = m;
            }
        }
        // дальние рисуем первыми, ближние и цель — поверх
        markers.sort(Comparator.comparingDouble((Marker m) -> -m.dist()));
        g.pose().pushPose();
        g.pose().translate(0, 0, -50); // под остальным HUD
        for (Marker m : markers) drawIcon(g, font, m, cfg);

        // подписи: приоритет — цель, затем ближние; без наложений
        List<Marker> byPriority = new ArrayList<>(markers);
        byPriority.sort(Comparator.comparingDouble((Marker m) -> m.target() ? -1 : m.dist()));
        List<int[]> taken = new ArrayList<>();
        for (Marker m : markers) taken.add(new int[]{(int) m.x() - 6, (int) m.y() - 6, 12, 12}); // значки
        for (Marker m : byPriority) {
            if (m == focus || !cfg.waypointLabels || m.fade() < 0.6F) continue;
            String name = m.w().name;
            if (font.width(name) > 110) name = font.plainSubstrByWidth(name, 104) + "…";
            String dist = formatDist(m.dist());
            float scale = 0.75F;
            int lw = Math.round(Math.max(font.width(name), font.width(dist)) * scale) + 6, lh = 18;
            int lx = Math.round(m.x() - lw / 2F);
            int[] tries = {-26, 9, -44, 27, -62};
            boolean placed = false;
            for (int off : tries) {
                int ly = Math.round(m.y()) + off;
                int[] r = {lx, ly, lw, lh};
                if (ly < 0 || ly + lh > sh || overlaps(r, taken, m)) continue;
                taken.add(r);
                drawLabel(g, font, m, name, dist, lx, ly, lw, scale);
                placed = true;
                break;
            }
            if (!placed) {
                // нет места — только расстояние мелко под значком, если свободно
                int dw = Math.round(font.width(dist) * 0.6F) + 2;
                int[] r = {Math.round(m.x() - dw / 2F), Math.round(m.y()) + 7, dw, 7};
                if (!overlaps(r, taken, m)) {
                    taken.add(r);
                    Draw.smallCentered(g, font, dist, m.x(), m.y() + 8, 0xFFE0E6EE, 0.6F);
                }
            }
        }
        if (focus != null) drawFocus(g, font, focus, sw, sh);
        g.pose().popPose();
    }

    private static boolean overlaps(int[] r, List<int[]> taken, Marker self) {
        for (int[] t : taken) {
            // собственный значок не мешает своей подписи
            if (t[2] == 12 && t[3] == 12 && Math.abs(t[0] + 6 - self.x()) < 1 && Math.abs(t[1] + 6 - self.y()) < 1) continue;
            if (r[0] < t[0] + t[2] && r[0] + r[2] > t[0] && r[1] < t[1] + t[3] && r[1] + r[3] > t[1]) return true;
        }
        return false;
    }

    private static String formatDist(double d) {
        return d >= 1000 ? String.format("%.1f km", d / 1000) : Math.round(d) + " m";
    }

    private static int alphaFor(Marker m) {
        // ближе 6 блоков — плавно растворяется, чтобы не мешать
        double near = m.target() ? 1.0 : Math.min(1.0, Math.max(0.25, (m.dist() - 2.5) / 4.0));
        return (int) (0xFF * near * m.fade());
    }

    private static void drawIcon(GuiGraphics g, Font font, Marker m, ArcConfig cfg) {
        int a = alphaFor(m);
        int color = 0xFF000000 | m.w().color;
        int x = Math.round(m.x()), y = Math.round(m.y());
        if (m.onScreen() && cfg.waypointBeams) {
            // нить к земле: от значка вниз до точки метки
            int gx = Math.round(m.groundX()), gy = Math.round(m.groundY());
            if (gy > y + 6 && Math.abs(gx - x) < 3) {
                g.fillGradient(x, y + 6, x + 1, gy, Draw.alpha(color, a * 3 / 4), Draw.alpha(color, 0));
                g.fill(gx - 1, gy - 1, gx + 2, gy + 1, Draw.alpha(color, a / 2));
            }
        }
        if (!m.onScreen()) {
            // стрелка к краю
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(m.edgeAngle()));
            for (int i = 0; i < 4; i++) g.fill(9 + i, -3 + i, 10 + i, 4 - i, Draw.alpha(color, a));
            g.pose().popPose();
        }
        int r = m.target() ? 7 : 6;
        if (m.target()) {
            float pulse = Draw.pulse(1200);
            MapView.diamond(g, x, y, r + 2 + Math.round(pulse * 2), Draw.alpha(Theme.accent(), (int) (0x90 * (1 - pulse))));
        }
        MapView.diamond(g, x, y, r + 1, Draw.alpha(0xFF000000, a * 3 / 4));
        MapView.diamond(g, x, y, r, Draw.alpha(color, a));
        Draw.smallCentered(g, font, m.w().initials(), x + 0.5F, y - 2.5F, Draw.alpha(0xFF0B0F14, a), 0.55F);
    }

    private static void drawLabel(GuiGraphics g, Font font, Marker m, String name, String dist, int x, int y, int w, float scale) {
        int a = alphaFor(m);
        int color = 0xFF000000 | m.w().color;
        g.fill(x, y, x + w, y + 18, Draw.alpha(0xFF0B0F14, a * 3 / 4));
        g.fill(x, y, x + 1, y + 18, Draw.alpha(color, a));
        Draw.small(g, font, name, x + 4, y + 2, Draw.alpha(0xFFFFFFFF, a), scale);
        Draw.small(g, font, dist, x + 4, y + 10, Draw.alpha(0xFFB8C4D0, a), 0.65F);
        // соединительная черта к значку
        int mx = Math.round(m.x());
        if (y > m.y()) g.fill(mx, Math.round(m.y()) + 6, mx + 1, y, Draw.alpha(color, a / 2));
        else g.fill(mx, y + 18, mx + 1, Math.round(m.y()) - 6, Draw.alpha(color, a / 2));
    }

    private static void drawFocus(GuiGraphics g, Font font, Marker m, int sw, int sh) {
        Minecraft mc = Minecraft.getInstance();
        Waypoint w = m.w();
        int color = 0xFF000000 | w.color;
        List<String> lines = new ArrayList<>();
        lines.add(formatDist(m.dist()) + "   " + heightText(w.y - (mc.player == null ? w.y : mc.player.getBlockY())));
        lines.add("X " + w.x + "  Y " + w.y + "  Z " + w.z);
        String src = Component.translatable("archud.wp.src." + w.source.name().toLowerCase(java.util.Locale.ROOT)).getString();
        if (w.sharedBy != null) src += " · " + w.sharedBy;
        lines.add(src);
        int width = font.width(w.name) + 8;
        for (String l : lines) width = Math.max(width, Math.round(font.width(l) * 0.7F) + 8);
        int h = 14 + lines.size() * 8 + 2;
        int x = Math.round(m.x() - width / 2F), y = Math.round(m.y()) - 12 - h;
        if (y < 2) y = Math.round(m.y()) + 12;
        x = Math.max(2, Math.min(sw - width - 2, x));
        Draw.panel(g, x, y, width, h, 0xE00B0F14, Draw.alpha(color, 0xFF));
        g.fill(x + 1, y + 1, x + width - 1, y + 2, Draw.alpha(color, 0xFF));
        g.drawString(font, w.name, x + 4, y + 4, 0xFFFFFFFF, true);
        int ly = y + 15;
        for (String l : lines) {
            Draw.small(g, font, l, x + 4, ly, 0xFFC8D2DC, 0.7F);
            ly += 8;
        }
    }

    private static String heightText(int dy) {
        if (Math.abs(dy) < 2) return "≈";
        return (dy > 0 ? "↑" : "↓") + Math.abs(dy);
    }
}

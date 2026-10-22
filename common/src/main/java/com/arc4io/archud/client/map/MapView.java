package com.arc4io.archud.client.map;

import com.arc4io.archud.client.hud.Draw;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Общая отрисовка карты (мини-карта и полноэкранная карта мира).
 * Координаты экрана: центр (cx, cy), масштаб zoom — пикселей на блок, rotation — поворот в градусах.
 */
public final class MapView {
    private MapView() {}

    public static void drawTerrain(GuiGraphics g, float cx, float cy, double centerX, double centerZ, float zoom, float rotation, float radiusPx) {
        double r = radiusPx / zoom * 1.5 + 2;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (rotation != 0F) g.pose().mulPose(Axis.ZP.rotationDegrees(rotation));
        g.pose().scale(zoom, zoom, 1F);
        MapCache.forEach(centerX - r, centerZ - r, centerX + r, centerZ + r, region -> {
            ResourceLocation tex = MapCache.texture(region);
            g.pose().pushPose();
            g.pose().translate((float) (region.rx() * MapCache.REGION - centerX), (float) (region.rz() * MapCache.REGION - centerZ), 0F);
            g.blit(tex, 0, 0, 0, 0, MapCache.REGION, MapCache.REGION, MapCache.REGION, MapCache.REGION);
            g.pose().popPose();
        });
        g.pose().popPose();
    }

    /** Мировая точка -> смещение на экране от центра. */
    public static float[] toScreen(double wx, double wz, double centerX, double centerZ, float zoom, float rotation) {
        double dx = (wx - centerX) * zoom, dz = (wz - centerZ) * zoom;
        if (rotation != 0F) {
            double a = Math.toRadians(rotation);
            double c = Math.cos(a), s = Math.sin(a);
            double rx = dx * c - dz * s, rz = dx * s + dz * c;
            dx = rx;
            dz = rz;
        }
        return new float[]{(float) dx, (float) dz};
    }

    public static void drawEntities(GuiGraphics g, Minecraft mc, float cx, float cy, double centerX, double centerZ, float zoom, float rotation, float half) {
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e instanceof ItemEntity || e.isInvisible()) continue;
            float[] s = toScreen(e.getX(), e.getZ(), centerX, centerZ, zoom, rotation);
            if (Math.abs(s[0]) > half - 2 || Math.abs(s[1]) > half - 2) continue;
            int color;
            int size = 3;
            if (e instanceof Player) {
                color = 0xFFFFFFFF;
                size = 4;
            } else if (e instanceof Enemy) color = 0xFFFF5252;
            else if (e instanceof Animal || e instanceof WaterAnimal) color = 0xFF9CFF5A;
            else continue;
            int x = Math.round(cx + s[0]) - size / 2, y = Math.round(cy + s[1]) - size / 2;
            g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xC0000000);
            g.fill(x, y, x + size, y + size, color);
        }
    }

    /** Ромб метки. */
    public static void diamond(GuiGraphics g, int x, int y, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(x - w - 1, y + i, x + w + 2, y + i + 1, 0xC0000000);
        }
        for (int i = -r + 1; i <= r - 1; i++) {
            int w = r - 1 - Math.abs(i);
            g.fill(x - w, y + i, x + w + 1, y + i + 1, color);
        }
    }

    public static void drawWaypoints(GuiGraphics g, Font font, float cx, float cy, double centerX, double centerZ, float zoom, float rotation,
                                     float half, boolean clampToEdge, boolean labels) {
        for (Waypoint w : WaypointManager.inDimension()) {
            float[] s = toScreen(w.x + 0.5, w.z + 0.5, centerX, centerZ, zoom, rotation);
            float m = Math.max(Math.abs(s[0]), Math.abs(s[1]));
            boolean outside = m > half - 4;
            if (outside && !clampToEdge) continue;
            if (outside) {
                float k = (half - 4) / m;
                s[0] *= k;
                s[1] *= k;
            }
            int x = Math.round(cx + s[0]), y = Math.round(cy + s[1]);
            diamond(g, x, y, outside ? 3 : 4, w.color);
            if (labels && !outside) {
                String n = w.name;
                Draw.smallCentered(g, font, n, x, y - 11, 0xFFFFFFFF, 0.6F);
            }
        }
    }

    /** Стрелка игрока (направление yaw: 0 = юг). */
    public static void drawPlayerArrow(GuiGraphics g, float cx, float cy, float angleDeg, int color) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
        // треугольник из полосок
        for (int i = 0; i < 7; i++) {
            int w = i;
            g.fill(-w - 1, -4 + i, w + 2, -3 + i, 0xFF000000);
        }
        for (int i = 0; i < 6; i++) {
            int w = i;
            g.fill(-w, -3 + i, w + 1, -2 + i, color);
        }
        g.fill(-2, 2, 3, 4, 0xFF000000);
        g.pose().popPose();
    }
}

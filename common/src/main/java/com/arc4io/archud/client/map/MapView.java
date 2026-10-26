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
        MapCache.Layer act = MapCache.active();
        if (act == null) return;
        MapCache.Layer under = MapScanner.underlay();
        if (under != null && under != act) {
            // под землёй: поверхность (пройденный путь) затемнена, пещеры поверх
            drawTerrain(g, under, cx, cy, centerX, centerZ, zoom, rotation, radiusPx);
            int r = (int) Math.ceil(radiusPx * 1.5F) + 2;
            g.fill((int) cx - r, (int) cy - r, (int) cx + r, (int) cy + r, 0xB0000000);
        }
        drawTerrain(g, act, cx, cy, centerX, centerZ, zoom, rotation, radiusPx);
    }

    public static void drawTerrain(GuiGraphics g, MapCache.Layer layer, float cx, float cy, double centerX, double centerZ, float zoom, float rotation, float radiusPx) {
        double r = radiusPx / zoom * 1.5 + 2;
        MapCache.touch(layer);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (rotation != 0F) g.pose().mulPose(Axis.ZP.rotationDegrees(rotation));
        g.pose().scale(zoom, zoom, 1F);
        MapCache.forEach(layer, centerX - r, centerZ - r, centerX + r, centerZ + r, region -> {
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
        drawEntities(g, mc, cx, cy, centerX, centerZ, zoom, rotation, half, true);
    }

    public static void drawEntities(GuiGraphics g, Minecraft mc, float cx, float cy, double centerX, double centerZ, float zoom, float rotation,
                                    float half, boolean names) {
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e instanceof ItemEntity || e.isInvisible()) continue;
            if (e instanceof Player && com.arc4io.archud.client.ArcClientNet.isHidden(e.getUUID())) continue; // скрыл себя с карты
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
            if (e instanceof Player && com.arc4io.archud.compat.VoiceCompat.isTalking(e.getUUID())) talkRing(g, x + size / 2, y + size / 2);
            if (names && e instanceof Player pl) {
                Draw.smallCentered(g, mc.font, pl.getGameProfile().getName(), x + 2, y - 6, 0xFFFFFFFF, 0.5F);
            }
        }
    }

    /** Кольцо «говорит» (голосовой чат). */
    public static void talkRing(GuiGraphics g, int x, int y) {
        int c = 0xFF66FF99;
        g.fill(x - 4, y - 5, x + 5, y - 4, c);
        g.fill(x - 4, y + 5, x + 5, y + 6, c);
        g.fill(x - 5, y - 4, x - 4, y + 5, c);
        g.fill(x + 5, y - 4, x + 6, y + 5, c);
    }

    /**
     * Игроки, о которых сообщил сервер Arc HUD (за пределами прорисовки), и зоны прогруженных ими чанков.
     */
    public static void drawRemotePlayers(GuiGraphics g, Minecraft mc, String dim, float cx, float cy, double centerX, double centerZ, float zoom,
                                         float rotation, float half, boolean names, boolean zones) {
        if (mc.level == null) return;
        com.arc4io.archud.config.ArcConfig cfg = com.arc4io.archud.config.ArcConfig.get();
        if (zones && rotation == 0F) {
            int vd = com.arc4io.archud.client.ArcClientNet.viewDistance();
            if (mc.player != null && dim.equals(mc.level.dimension().location().toString())) {
                zone(g, mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4, vd, cx, cy, centerX, centerZ, zoom, 0x2A4FC3F7, 0x804FC3F7);
            }
            for (var p : com.arc4io.archud.client.ArcClientNet.players()) {
                if (!dim.equals(p.dimension())) continue;
                zone(g, (int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4, vd, cx, cy, centerX, centerZ, zoom, 0x2AFFB74D, 0x80FFB74D);
            }
        }
        if (!cfg.showRemotePlayers) return;
        for (var p : com.arc4io.archud.client.ArcClientNet.players()) {
            if (!dim.equals(p.dimension())) continue;
            if (mc.level.getPlayerByUUID(p.id()) != null && dim.equals(mc.level.dimension().location().toString())) continue; // уже виден
            float[] s = toScreen(p.x(), p.z(), centerX, centerZ, zoom, rotation);
            boolean edge = Math.abs(s[0]) > half - 3 || Math.abs(s[1]) > half - 3;
            if (edge) {
                float k = (half - 3) / Math.max(Math.abs(s[0]), Math.abs(s[1]));
                s[0] *= k;
                s[1] *= k;
            }
            int x = Math.round(cx + s[0]) - 2, y = Math.round(cy + s[1]) - 2;
            g.fill(x - 1, y - 1, x + 5, y + 5, 0xC0000000);
            g.fill(x, y, x + 4, y + 4, 0xFF80DEEA);
            if (com.arc4io.archud.compat.VoiceCompat.isTalking(p.id())) talkRing(g, x + 2, y + 2);
            if (names && !edge) Draw.smallCentered(g, mc.font, p.name(), x + 2, y - 6, 0xFF80DEEA, 0.5F);
        }
    }

    private static void zone(GuiGraphics g, int pcx, int pcz, int vd, float cx, float cy, double centerX, double centerZ, float zoom, int fill, int border) {
        double minX = (pcx - vd) * 16.0, minZ = (pcz - vd) * 16.0, maxX = (pcx + vd + 1) * 16.0, maxZ = (pcz + vd + 1) * 16.0;
        int x0 = Math.round(cx + (float) ((minX - centerX) * zoom)), y0 = Math.round(cy + (float) ((minZ - centerZ) * zoom));
        int x1 = Math.round(cx + (float) ((maxX - centerX) * zoom)), y1 = Math.round(cy + (float) ((maxZ - centerZ) * zoom));
        g.fill(x0, y0, x1, y1, fill);
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
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
            boolean target = Navigator.isTarget(w);
            if (target) diamond(g, x, y, outside ? 5 : 7, 0xFFFFFFFF);
            diamond(g, x, y, outside ? 3 : (labels ? 6 : 4), w.color);
            if (labels && !outside) {
                Draw.smallCentered(g, font, w.initials(), x + 0.5F, y - 2, 0xFF000000, 0.5F);
                Draw.smallCentered(g, font, w.name, x, y - 13, 0xFFFFFFFF, 0.6F);
            }
        }
    }

    private static com.mojang.blaze3d.pipeline.RenderTarget circleTarget;

    /**
     * Круглая маска. Содержимое рисуется ОДИН раз во внеэкранный буфер размером с окно, затем переносится
     * на экран тонкими полосками по кругу одним вызовом отрисовки. (Раньше содержимое перерисовывалось
     * для каждой полосы — ~40 раз за кадр вместе с рельефом, следом, слизневыми чанками и сеткой.)
     */
    public static void circleBands(GuiGraphics g, int ix, int iy, int is, Runnable draw) {
        Minecraft mc = Minecraft.getInstance();
        com.mojang.blaze3d.pipeline.RenderTarget main = mc.getMainRenderTarget();
        g.flush();
        if (circleTarget == null) {
            circleTarget = new com.mojang.blaze3d.pipeline.TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
        } else if (circleTarget.width != main.width || circleTarget.height != main.height) {
            circleTarget.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        circleTarget.setClearColor(0F, 0F, 0F, 0F);
        circleTarget.clear(Minecraft.ON_OSX); // очищается только область ножниц — ровно квадрат карты
        circleTarget.bindWrite(true);
        draw.run();
        g.flush();
        main.bindWrite(true);

        org.joml.Matrix4f m = g.pose().last().pose();
        double gs = mc.getWindow().getGuiScale();
        float fw = circleTarget.width, fh = circleTarget.height;
        float r = is / 2F, ccx = ix + r, ccy = iy + r;
        float step = 0.5F;
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, circleTarget.getColorTextureId());
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        // в буфере цвет уже умножен на альфу (смешивание GUI пишет альфу отдельно)
        com.mojang.blaze3d.systems.RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.vertex.BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        bb.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        org.joml.Vector4f v = new org.joml.Vector4f();
        for (float y = iy; y < iy + is; y += step) {
            float mid = y + step / 2F - ccy;
            float hw = (float) Math.sqrt(Math.max(0, r * r - mid * mid));
            if (hw <= 0.05F) continue;
            float x0 = ccx - hw, x1 = ccx + hw, y1 = Math.min(y + step, iy + is);
            vert(bb, m, v, x0, y1, gs, fw, fh);
            vert(bb, m, v, x1, y1, gs, fw, fh);
            vert(bb, m, v, x1, y, gs, fw, fh);
            vert(bb, m, v, x0, y, gs, fw, fh);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.end());
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
    }

    private static void vert(com.mojang.blaze3d.vertex.BufferBuilder bb, org.joml.Matrix4f m, org.joml.Vector4f v, float x, float y,
                             double gs, float fw, float fh) {
        m.transform(v.set(x, y, 0F, 1F));
        float u = (float) (v.x() * gs / fw), t = 1F - (float) (v.y() * gs / fh);
        bb.vertex(m, x, y, 0F).uv(u, t).endVertex();
    }

    public static void circleRing(GuiGraphics g, float cx, float cy, float r, int color) {
        for (int a = 0; a < 360; a += 2) {
            double rad = Math.toRadians(a);
            int x = Math.round(cx + (float) Math.cos(rad) * r), y = Math.round(cy + (float) Math.sin(rad) * r);
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    /** Сетка чанков. */
    public static void drawChunkGrid(GuiGraphics g, float cx, float cy, double centerX, double centerZ, float zoom, float rotation, float half) {
        double r = half / zoom * 1.5;
        int x0 = (int) Math.floor((centerX - r) / 16) * 16, x1 = (int) Math.ceil((centerX + r) / 16) * 16;
        int z0 = (int) Math.floor((centerZ - r) / 16) * 16, z1 = (int) Math.ceil((centerZ + r) / 16) * 16;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (rotation != 0F) g.pose().mulPose(Axis.ZP.rotationDegrees(rotation));
        for (int x = x0; x <= x1; x += 16) {
            float sx = (float) ((x - centerX) * zoom);
            g.fill((int) sx, (int) (-r * zoom), (int) sx + 1, (int) (r * zoom), 0x22FFFFFF);
        }
        for (int z = z0; z <= z1; z += 16) {
            float sz = (float) ((z - centerZ) * zoom);
            g.fill((int) (-r * zoom), (int) sz, (int) (r * zoom), (int) sz + 1, 0x22FFFFFF);
        }
        g.pose().popPose();
    }

    private static final java.util.Map<Long, Boolean> SLIME = new java.util.HashMap<>();
    private static long slimeSeed;

    /** Слизневый ли чанк — с кэшем (проверка создаёт генератор случайных чисел на каждый чанк). */
    private static boolean isSlime(long seed, int x, int z) {
        if (seed != slimeSeed || SLIME.size() > 50000) {
            SLIME.clear();
            slimeSeed = seed;
        }
        return SLIME.computeIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL),
                k -> net.minecraft.world.level.levelgen.WorldgenRandom.seedSlimeChunk(x, z, seed, 987234911L).nextInt(10) == 0);
    }

    /** Слизневые чанки (только одиночная игра — нужен сид мира). */
    public static void drawSlimeChunks(GuiGraphics g, Minecraft mc, float cx, float cy, double centerX, double centerZ, float zoom, float rotation, float half) {
        if (mc.getSingleplayerServer() == null || mc.level == null || !mc.level.dimension().location().toString().equals("minecraft:overworld")) return;
        long seed = mc.getSingleplayerServer().getWorldData().worldGenOptions().seed();
        double r = half / zoom * 1.5;
        int c0x = (int) Math.floor((centerX - r) / 16), c1x = (int) Math.floor((centerX + r) / 16);
        int c0z = (int) Math.floor((centerZ - r) / 16), c1z = (int) Math.floor((centerZ + r) / 16);
        if ((c1x - c0x) * (c1z - c0z) > 2500) return;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (rotation != 0F) g.pose().mulPose(Axis.ZP.rotationDegrees(rotation));
        g.pose().scale(zoom, zoom, 1F);
        for (int x = c0x; x <= c1x; x++) {
            for (int z = c0z; z <= c1z; z++) {
                if (!isSlime(seed, x, z)) continue;
                float sx = (float) (x * 16 - centerX), sz = (float) (z * 16 - centerZ);
                g.pose().pushPose();
                g.pose().translate(sx, sz, 0);
                g.fill(0, 0, 16, 16, 0x3066FF66);
                g.pose().popPose();
            }
        }
        g.pose().popPose();
    }

    /** След игрока. */
    public static void drawTrail(GuiGraphics g, java.util.Deque<int[]> trail, float cx, float cy, double centerX, double centerZ, float zoom,
                                 float rotation, float half, int color) {
        int i = 0, n = trail.size();
        for (int[] pt : trail) {
            i++;
            float[] s = toScreen(pt[0] + 0.5, pt[1] + 0.5, centerX, centerZ, zoom, rotation);
            if (Math.abs(s[0]) > half || Math.abs(s[1]) > half) continue;
            int a = 40 + (int) (160F * i / Math.max(1, n));
            int x = Math.round(cx + s[0]), y = Math.round(cy + s[1]);
            g.fill(x, y, x + 1, y + 1, Draw.alpha(color, a));
        }
    }

    /** Пунктир к цели навигатора. */
    public static void drawNavigatorLine(GuiGraphics g, Minecraft mc, float cx, float cy, double centerX, double centerZ, float zoom, float rotation, float half) {
        if (mc.level == null) return;
        double[] t = Navigator.convertedTarget(mc.level.dimension().location().toString());
        if (t == null) return;
        float[] s = toScreen(t[0], t[1], centerX, centerZ, zoom, rotation);
        float len = (float) Math.sqrt(s[0] * s[0] + s[1] * s[1]);
        if (len < 4) return;
        float max = Math.min(len, half * 1.5F);
        int color = 0xFF000000 | Navigator.target().color;
        for (float d = 6; d < max; d += 5) {
            int x = Math.round(cx + s[0] / len * d), y = Math.round(cy + s[1] / len * d);
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
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

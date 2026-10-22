package com.arc4io.archud.client.map;

import com.arc4io.archud.ArcHud;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Кэш изученной местности: регионы 128x128 блоков (8x8 чанков), каждый — своя текстура.
 * Ключ кэша = измерение (+ режим пещер), при смене кэш сбрасывается.
 */
public final class MapCache {
    public static final int REGION = 128;

    public static final class Region {
        final int rx, rz;
        final NativeImage image = new NativeImage(REGION, REGION, true);
        DynamicTexture texture;
        ResourceLocation id;
        boolean dirty = true;

        Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }

        public int rx() {
            return rx;
        }

        public int rz() {
            return rz;
        }
    }

    private static final Map<Long, Region> REGIONS = new HashMap<>();
    private static String key = "";
    private static int counter;

    private MapCache() {}

    public static String key() {
        return key;
    }

    public static void reset(String newKey) {
        key = newKey;
        Minecraft mc = Minecraft.getInstance();
        for (Region r : REGIONS.values()) {
            if (r.id != null) mc.getTextureManager().release(r.id);
            else r.image.close();
        }
        REGIONS.clear();
        MapScanner.clearScanned();
    }

    private static long pack(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }

    static Region region(int rx, int rz, boolean create) {
        long k = pack(rx, rz);
        Region r = REGIONS.get(k);
        if (r == null && create) {
            r = new Region(rx, rz);
            REGIONS.put(k, r);
        }
        return r;
    }

    /** Записывает цвета чанка (256 пикселей ABGR, индекс z*16+x). */
    static void putChunk(int cx, int cz, int[] abgr) {
        int bx = cx * 16, bz = cz * 16;
        Region r = region(Math.floorDiv(bx, REGION), Math.floorDiv(bz, REGION), true);
        int lx = Math.floorMod(bx, REGION), lz = Math.floorMod(bz, REGION);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                r.image.setPixelRGBA(lx + x, lz + z, abgr[z * 16 + x]);
            }
        }
        r.dirty = true;
    }

    /** Возвращает ARGB цвет в точке (для подсказок), 0 — неизвестно. */
    public static int colorAt(int x, int z) {
        Region r = region(Math.floorDiv(x, REGION), Math.floorDiv(z, REGION), false);
        if (r == null) return 0;
        int abgr = r.image.getPixelRGBA(Math.floorMod(x, REGION), Math.floorMod(z, REGION));
        return abgr;
    }

    /** Текстура региона (создаётся/обновляется при необходимости). */
    public static ResourceLocation texture(Region r) {
        if (r.texture == null) {
            r.texture = new DynamicTexture(r.image);
            r.id = ArcHud.id("map/r" + (counter++));
            Minecraft.getInstance().getTextureManager().register(r.id, r.texture);
            r.dirty = false;
        } else if (r.dirty) {
            r.texture.upload();
            r.dirty = false;
        }
        return r.id;
    }

    /** Все регионы, пересекающие прямоугольник в блоках. */
    public static void forEach(double minX, double minZ, double maxX, double maxZ, Consumer<Region> action) {
        int rx0 = Math.floorDiv((int) Math.floor(minX), REGION), rx1 = Math.floorDiv((int) Math.floor(maxX), REGION);
        int rz0 = Math.floorDiv((int) Math.floor(minZ), REGION), rz1 = Math.floorDiv((int) Math.floor(maxZ), REGION);
        if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 4096) return;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                Region r = region(rx, rz, false);
                if (r != null) action.accept(r);
            }
        }
    }

    public static int regionCount() {
        return REGIONS.size();
    }
}

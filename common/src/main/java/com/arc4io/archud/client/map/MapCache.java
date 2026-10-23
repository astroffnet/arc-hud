package com.arc4io.archud.client.map;

import com.arc4io.archud.ArcHud;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Кэш изученной местности.
 * Слой = мир + измерение + уровень (поверхность или слой пещер по 16 блоков).
 * Каждый слой хранит регионы 128x128 блоков (текстура на регион) и сохраняется на диск
 * в config/archud/map/&lt;мир&gt;/&lt;измерение&gt;/&lt;слой&gt;/r_X_Z.png — карта не теряется после перезахода,
 * а все измерения можно смотреть на карте мира даже находясь в другом.
 */
public final class MapCache {
    public static final int REGION = 128;

    public static final class Region {
        final int rx, rz;
        final NativeImage image;
        DynamicTexture texture;
        ResourceLocation id;
        boolean dirty = true;
        boolean unsaved;

        Region(int rx, int rz, NativeImage image) {
            this.rx = rx;
            this.rz = rz;
            this.image = image;
        }

        public int rx() {
            return rx;
        }

        public int rz() {
            return rz;
        }
    }

    public static final class Layer {
        final String world, dim, level;
        final Map<Long, Region> regions = new HashMap<>();
        final Set<Long> missing = new HashSet<>();
        long lastUsed;

        Layer(String world, String dim, String level) {
            this.world = world;
            this.dim = dim;
            this.level = level;
        }

        public String key() {
            return world + "|" + dim + "|" + level;
        }

        public String dimension() {
            return dim;
        }

        public String level() {
            return level;
        }

        Path dir() {
            return ArcHud.platform().configDir().resolve("archud").resolve("map").resolve(world)
                    .resolve(dim.replace(':', '~')).resolve(level);
        }
    }

    private static final Map<String, Layer> LAYERS = new HashMap<>();
    private static Layer active;
    private static int counter;
    private static long lastSave;

    private MapCache() {}

    private static long pack(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }

    public static Layer active() {
        return active;
    }

    /** Переключает активный слой (для сканирования). Старые слои остаются в памяти/на диске. */
    public static Layer activate(String world, String dim, String level) {
        ensureShutdownHook();
        String key = world + "|" + dim + "|" + level;
        Layer l = LAYERS.computeIfAbsent(key, k -> new Layer(world, dim, level));
        if (active != l) {
            if (active != null) saveLayer(active);
            boolean otherPlace = active == null || !active.world.equals(world) || !active.dim.equals(dim);
            active = l;
            if (otherPlace) MapScanner.clearScanned();
        }
        l.lastUsed = System.currentTimeMillis();
        return l;
    }

    public static Layer layer(String world, String dim, String level) {
        return LAYERS.computeIfAbsent(world + "|" + dim + "|" + level, k -> new Layer(world, dim, level));
    }

    /** Измерения и слои, сохранённые на диске для мира (для переключателя на карте мира). */
    public static List<String[]> savedLayers(String world) {
        List<String[]> out = new ArrayList<>();
        Path base = ArcHud.platform().configDir().resolve("archud").resolve("map").resolve(world);
        if (!Files.isDirectory(base)) return out;
        try (Stream<Path> dims = Files.list(base)) {
            for (Path d : (Iterable<Path>) dims::iterator) {
                if (!Files.isDirectory(d)) continue;
                try (Stream<Path> levels = Files.list(d)) {
                    for (Path lv : (Iterable<Path>) levels::iterator) {
                        if (Files.isDirectory(lv)) out.add(new String[]{d.getFileName().toString().replace('~', ':'), lv.getFileName().toString()});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static Region region(Layer layer, int rx, int rz, boolean create) {
        long k = pack(rx, rz);
        Region r = layer.regions.get(k);
        if (r != null) return r;
        if (!layer.missing.contains(k)) {
            r = load(layer, rx, rz);
            if (r != null) {
                layer.regions.put(k, r);
                return r;
            }
            layer.missing.add(k);
        }
        if (create) {
            r = new Region(rx, rz, new NativeImage(REGION, REGION, true));
            layer.regions.put(k, r);
            layer.missing.remove(k);
        }
        return r;
    }

    private static Region load(Layer layer, int rx, int rz) {
        Path f = layer.dir().resolve("r_" + rx + "_" + rz + ".png");
        if (!Files.exists(f)) return null;
        try (InputStream in = Files.newInputStream(f)) {
            NativeImage img = NativeImage.read(in);
            if (img.getWidth() != REGION || img.getHeight() != REGION) {
                img.close();
                return null;
            }
            return new Region(rx, rz, img);
        } catch (Exception e) {
            return null;
        }
    }

    /** Записывает цвета чанка (256 пикселей ABGR, индекс z*16+x) в активный слой. */
    static void putChunk(int cx, int cz, int[] abgr) {
        if (active != null) putChunk(active, cx, cz, abgr);
    }

    static void putChunk(Layer layer, int cx, int cz, int[] abgr) {
        int bx = cx * 16, bz = cz * 16;
        Region r = region(layer, Math.floorDiv(bx, REGION), Math.floorDiv(bz, REGION), true);
        int lx = Math.floorMod(bx, REGION), lz = Math.floorMod(bz, REGION);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                r.image.setPixelRGBA(lx + x, lz + z, abgr[z * 16 + x]);
            }
        }
        r.dirty = true;
        r.unsaved = true;
    }

    /** Чанк от другого игрока (общая карта): пишет в слой поверхности измерения, не трогая прозрачные пиксели. */
    public static void putRemote(String world, String dim, int cx, int cz, int[] abgr) {
        Layer layer = layer(world, dim, "surface");
        layer.lastUsed = System.currentTimeMillis();
        int bx = cx * 16, bz = cz * 16;
        Region r = region(layer, Math.floorDiv(bx, REGION), Math.floorDiv(bz, REGION), true);
        int lx = Math.floorMod(bx, REGION), lz = Math.floorMod(bz, REGION);
        boolean changed = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int v = abgr[z * 16 + x];
                if (v == 0) continue;
                if (r.image.getPixelRGBA(lx + x, lz + z) != v) {
                    r.image.setPixelRGBA(lx + x, lz + z, v);
                    changed = true;
                }
            }
        }
        if (changed) {
            r.dirty = true;
            r.unsaved = true;
        }
    }

    /** Цвет (ABGR) в точке активного слоя, 0 — неизвестно. */
    public static int colorAt(int x, int z) {
        if (active == null) return 0;
        Region r = region(active, Math.floorDiv(x, REGION), Math.floorDiv(z, REGION), false);
        return r == null ? 0 : r.image.getPixelRGBA(Math.floorMod(x, REGION), Math.floorMod(z, REGION));
    }

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

    public static void forEach(double minX, double minZ, double maxX, double maxZ, Consumer<Region> action) {
        if (active != null) forEach(active, minX, minZ, maxX, maxZ, action);
    }

    public static void forEach(Layer layer, double minX, double minZ, double maxX, double maxZ, Consumer<Region> action) {
        int rx0 = Math.floorDiv((int) Math.floor(minX), REGION), rx1 = Math.floorDiv((int) Math.floor(maxX), REGION);
        int rz0 = Math.floorDiv((int) Math.floor(minZ), REGION), rz1 = Math.floorDiv((int) Math.floor(maxZ), REGION);
        if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 4096) return;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                Region r = region(layer, rx, rz, false);
                if (r != null) action.accept(r);
            }
        }
    }

    /** Все регионы слоя в памяти (для экспорта). */
    public static Iterable<Region> regions(Layer layer) {
        return layer.regions.values();
    }

    public static int pixel(Region r, int x, int z) {
        return r.image.getPixelRGBA(x, z);
    }

    private static void saveLayer(Layer layer) {
        try {
            Path dir = layer.dir();
            for (Region r : layer.regions.values()) {
                if (!r.unsaved) continue;
                Files.createDirectories(dir);
                r.image.writeToFile(dir.resolve("r_" + r.rx + "_" + r.rz + ".png"));
                r.unsaved = false;
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось сохранить карту", e);
        }
    }

    /** Периодическое сохранение (раз в 30 сек) и выгрузка давно неиспользуемых слоёв. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastSave < 15000) return;
        lastSave = now;
        for (Layer l : LAYERS.values()) saveLayer(l);
        LAYERS.values().removeIf(l -> {
            if (l == active || now - l.lastUsed < 120000) return false;
            release(l);
            return true;
        });
    }

    private static boolean hookAdded;

    /** Сохранить всё несохранённое (например, при закрытии игры). */
    public static synchronized void saveAll() {
        for (Layer l : new java.util.ArrayList<>(LAYERS.values())) saveLayer(l);
    }

    /** При закрытии окна игры (без выхода из мира) — тоже сохранить карту. */
    static void ensureShutdownHook() {
        if (hookAdded) return;
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                saveAll();
            } catch (Throwable ignored) {
            }
        }, "Arc HUD map save"));
    }

    /** Выход из мира: сохранить всё и освободить текстуры. */
    public static void unloadAll() {
        for (Layer l : LAYERS.values()) {
            saveLayer(l);
            release(l);
        }
        LAYERS.clear();
        active = null;
    }

    private static void release(Layer l) {
        Minecraft mc = Minecraft.getInstance();
        for (Region r : l.regions.values()) {
            if (r.id != null) mc.getTextureManager().release(r.id);
            else r.image.close();
        }
        l.regions.clear();
    }

    public static void touch(Layer l) {
        l.lastUsed = System.currentTimeMillis();
    }
}

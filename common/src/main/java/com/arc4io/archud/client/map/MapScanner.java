package com.arc4io.archud.client.map;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/**
 * Сканирует загруженные чанки вокруг игрока и раскрашивает их для карты:
 * цвет блока с учётом оттенка биома (трава, листва, вода), тени рельефа,
 * глубина воды, режим пещер (в Незере и под землёй).
 */
public final class MapScanner {
    private static final Map<Long, Long> SCANNED = new HashMap<>();      // поверхность
    private static final Map<Long, Long> SCANNED_CAVE = new HashMap<>(); // пещеры
    private static final int RADIUS = 9;
    private static final int CAVE_RADIUS = 6;
    private static boolean cave, scanningCave, undergroundState;
    private static int caveY, lastCaveScanY = Integer.MIN_VALUE, ticksUnder, ticksAbove;
    private static MapCache.Layer surfaceLayer, caveLayer;
    private static int slowTick, cleanup;

    private MapScanner() {}

    static void clearScanned() {
        // слой сменился (другое измерение / мир) — пересканировать
        SCANNED.clear();
        SCANNED_CAVE.clear();
    }

    public static boolean caveMode() {
        return cave;
    }

    /** Слой поверхности, который рисуется затемнённым под пещерным (чтобы пройденный путь оставался виден). */
    public static MapCache.Layer underlay() {
        return cave && surfaceLayer != null ? surfaceLayer : null;
    }

    private static boolean inWorld;

    /** Вызывается каждый тик, даже вне мира: при выходе сохраняет и выгружает карту. */
    public static void worldCheck(Minecraft mc) {
        boolean now = mc.level != null;
        if (inWorld && !now) {
            MapCache.unloadAll();
            if (com.arc4io.archud.client.perf.Perf.on("s_gc")) System.gc(); // освободить память мира сразу, а не во время следующей игры
            Navigator.clear();
            TrailManager.clear();
            com.arc4io.archud.compat.TempadCompat.reset();
            com.arc4io.archud.compat.WaystonesCompat.reset();
            surfaceLayer = caveLayer = null;
            SCANNED.clear();
            SCANNED_CAVE.clear();
        }
        inWorld = now;
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level == null || p == null) return;

        BlockPos pos = p.blockPosition();
        boolean ceiling = level.dimensionType().hasCeiling();
        // под землёй: нет неба над головой (свет неба ≈ 0) и глубоко под поверхностью (листва не считается).
        // С задержкой, чтобы карта не «мигала» в лесу, под мостом или в доме.
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.containing(p.getEyePosition()));
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        boolean under = sky <= 1 && pos.getY() < surface - 4;
        if (under) {
            ticksUnder++;
            ticksAbove = 0;
        } else {
            ticksAbove++;
            ticksUnder = 0;
        }
        if (!undergroundState && ticksUnder >= 40) undergroundState = true;
        if (undergroundState && ticksAbove >= 20) undergroundState = false;
        cave = ceiling || (ArcConfig.get().minimapCaveMode && undergroundState); // в Незере и подобных — всегда слой у игрока
        caveY = pos.getY();

        String world = WaypointManager.worldKey(), dim = level.dimension().location().toString();
        MapCache.Layer act = MapCache.activate(world, dim, cave ? "cave" : "surface");
        surfaceLayer = ceiling ? null : MapCache.layer(world, dim, "surface");
        caveLayer = cave ? act : null;
        if (surfaceLayer != null) MapCache.touch(surfaceLayer);
        MapCache.tick();

        long now = Util.getMillis();
        int pcx = pos.getX() >> 4, pcz = pos.getZ() >> 4;
        // Сканирование ограничено временем, а не числом чанков: все прогруженные, но ещё не отсканированные
        // чанки (в пределах прорисовки) успевают попасть на карту до выгрузки — даже при полёте на элитрах.
        // При низком FPS бюджет меньше, но не ноль: раньше тики пропускались, и чанки выгружались неотсканированными.
        long budgetNs = 3_000_000L;
        if (ArcConfig.get().adaptiveMapScan) {
            int fps = mc.getFps();
            budgetNs = fps < 25 ? 1_000_000L : fps < 45 ? 2_000_000L : 3_000_000L;
        }
        if (com.arc4io.archud.client.perf.Perf.on("a_map_scan")) budgetNs /= 2; // оптимизация: вдвое меньше времени на карту
        long deadline = System.nanoTime() + budgetNs;
        int far = Math.max(4, Math.min(32, mc.options.getEffectiveRenderDistance()));
        if (surfaceLayer != null) {
            scanArea(mc, level, surfaceLayer, SCANNED, pcx, pcz, far, Integer.MAX_VALUE, now, false, true, deadline);
            // ближние — периодически обновляются (постройки, вырубка); пару чанков даже без бюджета
            scanArea(mc, level, surfaceLayer, SCANNED, pcx, pcz, RADIUS, cave ? 2 : 4, now, false, false, Long.MAX_VALUE);
        }
        if (++cleanup % 200 == 0) {
            // забыть далёкие чанки — при возвращении они пересканируются и карта обновится
            SCANNED.keySet().removeIf(k -> Math.max(Math.abs((int) (k >> 32) - pcx), Math.abs((int) (long) k - pcz)) > far + 8);
        }
        if (cave) {
            if (Math.abs(caveY - lastCaveScanY) >= 6) {
                SCANNED_CAVE.clear(); // другая глубина — обновить пещеры вокруг
                lastCaveScanY = caveY;
            }
            scanArea(mc, level, caveLayer, SCANNED_CAVE, pcx, pcz, CAVE_RADIUS, 5, now, true, false, Long.MAX_VALUE);
        }
    }

    private static void scanArea(Minecraft mc, ClientLevel level, MapCache.Layer layer, Map<Long, Long> scanned, int pcx, int pcz, int radius,
                                 int budget, long now, boolean caveScan, boolean onlyNew, long deadline) {
        // по кольцам от игрока: обходится только периметр кольца
        for (int r = 0; r <= radius && budget > 0; r++) {
            long interval = r <= 1 ? 700L : r <= 3 ? 2500L : 15000L;
            int n = r == 0 ? 1 : 8 * r;
            for (int i = 0; i < n && budget > 0; i++) {
                int dx, dz;
                if (r == 0) {
                    dx = 0;
                    dz = 0;
                } else {
                    int side = i / (2 * r), off = i % (2 * r);
                    switch (side) {
                        case 0 -> { dx = -r + off; dz = -r; }
                        case 1 -> { dx = r; dz = -r + off; }
                        case 2 -> { dx = r - off; dz = r; }
                        default -> { dx = -r; dz = r - off; }
                    }
                }
                int cx = pcx + dx, cz = pcz + dz;
                long k = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                Long last = scanned.get(k);
                if (onlyNew ? last != null : last != null && now - last < interval) continue;
                if (!level.hasChunk(cx, cz)) continue;
                if (System.nanoTime() > deadline) return;
                // чанк на краю прогрузки без соседа с севера даст ложные тени — подождём соседа
                if (onlyNew && !caveScan && !level.hasChunk(cx, cz - 1) && r < radius) continue;
                scanned.put(k, now);
                scanningCave = caveScan;
                scanChunk(mc, level, layer, cx, cz);
                budget--;
            }
        }
    }

    private static void scanChunk(Minecraft mc, ClientLevel level, MapCache.Layer layer, int cx, int cz) {
        int[] out = new int[256];
        int[] heights = new int[17 * 16];
        BlockState[] states = new BlockState[17 * 16];
        int[] water = new int[17 * 16];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        for (int dz = -1; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int wx = cx * 16 + dx, wz = cz * 16 + dz;
                int i = (dz + 1) * 16 + dx;
                int y = topY(level, m, wx, wz, minY);
                BlockState st = level.getBlockState(m.set(wx, y, wz));
                int depth = 0;
                if (st.getFluidState().is(FluidTags.WATER)) {
                    while (depth < 12 && level.getBlockState(m.set(wx, y - depth - 1, wz)).getFluidState().is(FluidTags.WATER)) depth++;
                }
                heights[i] = y;
                states[i] = st;
                water[i] = depth;
            }
        }
        BlockColors colors = mc.getBlockColors();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int i = (dz + 1) * 16 + dx;
                int wx = cx * 16 + dx, wz = cz * 16 + dz;
                BlockState st = states[i];
                m.set(wx, heights[i], wz);
                if (st.isAir()) {
                    out[dz * 16 + dx] = 0; // пустота (Энд, Эфир, небесные измерения) — прозрачно
                    continue;
                }
                int rgb;
                float shade = 1F;
                if (st.getFluidState().is(FluidTags.WATER)) {
                    rgb = BiomeColors.getAverageWaterColor(level, m);
                    rgb = mul(rgb, 0.85F);
                    shade = 1.05F - Math.min(12, water[i]) * 0.045F;
                } else {
                    int tint = -1;
                    if (st.is(BlockTags.LEAVES) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.GRASS) || st.is(Blocks.TALL_GRASS)
                            || st.is(Blocks.VINE) || st.is(Blocks.FERN) || st.is(Blocks.LILY_PAD) || st.is(Blocks.SUGAR_CANE)) {
                        tint = colors.getColor(st, level, m, 0);
                    }
                    MapColor mcol = st.getMapColor(level, m);
                    rgb = tint != -1 ? mul(tint, st.is(BlockTags.LEAVES) ? 0.8F : 0.92F) : mcol.col;
                    if (mcol == MapColor.NONE && tint == -1) rgb = 0x101010;
                    int north = heights[i - 16];
                    int hDiff = heights[i] - north;
                    shade = hDiff > 0 ? 1.12F : hDiff < 0 ? 0.82F : 1F;
                    if (scanningCave) shade *= 0.95F;
                }
                rgb = mul(rgb, shade);
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                out[dz * 16 + dx] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
        }
        MapCache.putChunk(layer, cx, cz, out);
        if (!scanningCave) com.arc4io.archud.client.ArcClientNet.offerChunk(level.dimension().location().toString(), cx, cz, out);
    }

    private static int topY(ClientLevel level, BlockPos.MutableBlockPos m, int x, int z, int minY) {
        if (scanningCave) {
            int y = caveY + 1;
            int limit = 48;
            // если внутри потолка — опускаемся до воздуха
            while (limit-- > 0 && y > minY && !level.getBlockState(m.set(x, y, z)).isAir()) y--;
            // затем — до первого твёрдого блока
            limit = 64;
            while (limit-- > 0 && y > minY && level.getBlockState(m.set(x, y, z)).isAir()) y--;
            return y;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (y < minY) return minY;
        int limit = 6;
        while (limit-- > 0 && y > minY && level.getBlockState(m.set(x, y, z)).isAir()) y--;
        return y;
    }

    private static int mul(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }
}

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
    private static final Map<Long, Long> SCANNED = new HashMap<>();
    private static final int RADIUS = 9;
    private static boolean cave;
    private static int caveY;

    private MapScanner() {}

    static void clearScanned() {
        SCANNED.clear();
    }

    public static boolean caveMode() {
        return cave;
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level == null || p == null || !ArcConfig.get().minimap) return;

        BlockPos pos = p.blockPosition();
        boolean underground = !level.canSeeSky(pos.above()) && p.getY() < level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) - 6;
        cave = ArcConfig.get().minimapCaveMode && (level.dimensionType().hasCeiling() || underground);
        caveY = pos.getY();
        String key = level.dimension().location() + (cave ? "|cave" + Math.floorDiv(caveY, 16) : "");
        if (!key.equals(MapCache.key())) MapCache.reset(key);

        long now = Util.getMillis();
        int pcx = pos.getX() >> 4, pcz = pos.getZ() >> 4;
        int budget = 6;
        for (int r = 0; r <= RADIUS && budget > 0; r++) {
            long interval = r <= 1 ? 700L : r <= 3 ? 2500L : 15000L;
            for (int dx = -r; dx <= r && budget > 0; dx++) {
                for (int dz = -r; dz <= r && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = pcx + dx, cz = pcz + dz;
                    if (!level.hasChunk(cx, cz)) continue;
                    long k = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    Long last = SCANNED.get(k);
                    if (last != null && now - last < interval) continue;
                    SCANNED.put(k, now);
                    scanChunk(mc, level, cx, cz);
                    budget--;
                }
            }
        }
    }

    private static void scanChunk(Minecraft mc, ClientLevel level, int cx, int cz) {
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
                    if (cave) shade *= 0.95F;
                }
                rgb = mul(rgb, shade);
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                out[dz * 16 + dx] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
        }
        MapCache.putChunk(cx, cz, out);
    }

    private static int topY(ClientLevel level, BlockPos.MutableBlockPos m, int x, int z, int minY) {
        if (cave) {
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

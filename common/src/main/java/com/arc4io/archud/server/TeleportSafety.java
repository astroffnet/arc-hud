package com.arc4io.archud.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Поиск безопасного места для телепорта (карта, двери Tempad): твёрдый пол, два блока свободного места,
 * без лавы/огня/магмы/кактусов и не над пустотой. Чанки при необходимости загружаются (генерируются).
 * Ищет в самой точке, затем по спирали вокруг (до 16 блоков). null — безопасного места нет.
 */
public final class TeleportSafety {
    public static final int NO_HINT = Integer.MIN_VALUE;
    private static final int RADIUS = 16;

    private TeleportSafety() {}

    public static BlockPos find(ServerLevel level, int x, int yHint, int z) {
        for (int r = 0; r <= RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = column(level, x + dx, yHint, z + dz);
                    if (p != null) return p;
                }
            }
        }
        return null;
    }

    private static BlockPos column(ServerLevel level, int x, int yHint, int z) {
        level.getChunk(x >> 4, z >> 4); // загрузить / сгенерировать
        int min = level.getMinBuildHeight() + 1;
        int max = level.dimensionType().hasCeiling()
                ? Math.min(level.getMaxBuildHeight(), level.getMinBuildHeight() + level.getLogicalHeight()) - 3
                : level.getMaxBuildHeight() - 2;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        if (yHint != NO_HINT) {
            int y0 = Math.max(min, Math.min(max, yHint));
            if (safe(level, m, x, y0, z)) return new BlockPos(x, y0, z);
            for (int d = 1; d <= 24; d++) {
                if (y0 + d <= max && safe(level, m, x, y0 + d, z)) return new BlockPos(x, y0 + d, z);
                if (y0 - d >= min && safe(level, m, x, y0 - d, z)) return new BlockPos(x, y0 - d, z);
            }
            if (level.dimensionType().hasCeiling()) return null;
        }
        if (!level.dimensionType().hasCeiling()) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (top <= level.getMinBuildHeight()) return null; // пустота (Энд, небесные измерения)
            for (int y = Math.min(max, top + 1); y >= Math.max(min, top - 6); y--) {
                if (safe(level, m, x, y, z)) return new BlockPos(x, y, z);
            }
            return null;
        }
        // измерения с потолком (Незер): первая пещера снизу вверх, начиная с уровня моря
        int start = Math.max(min, Math.min(max, level.getSeaLevel() + 1));
        for (int y = start; y <= max; y++) if (safe(level, m, x, y, z)) return new BlockPos(x, y, z);
        for (int y = start - 1; y >= min; y--) if (safe(level, m, x, y, z)) return new BlockPos(x, y, z);
        return null;
    }

    /** Ноги в (x,y,z): пол y-1 твёрдый и не опасный, y и y+1 свободны и без лавы/огня. */
    private static boolean safe(ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        BlockState floor = level.getBlockState(m.set(x, y - 1, z));
        if (!floor.isFaceSturdy(level, m, Direction.UP) || dangerous(floor)) return false;
        for (int i = 0; i < 2; i++) {
            BlockState s = level.getBlockState(m.set(x, y + i, z));
            if (!s.getCollisionShape(level, m).isEmpty() || dangerous(s)) return false;
            if (s.getFluidState().is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private static boolean dangerous(BlockState s) {
        return s.is(Blocks.MAGMA_BLOCK) || s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.CACTUS)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.LAVA)
                || s.getFluidState().is(FluidTags.LAVA);
    }
}

package com.arc4io.archud.server;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * AntiESP: сервер не отправляет игроку сущности (игроков, мобов, предметы, стойки…), которые полностью
 * скрыты от него непрозрачными блоками. Читы с подсветкой сущностей сквозь стены (ESP, «рентген»-текстуры)
 * ничего не видят — данных о сущности у клиента просто нет. Как только сущность становится видна
 * (из-за угла, через окно), она появляется.
 *
 * Видимость — прямой луч от глаз игрока к нескольким точкам сущности (центр, верх, низ, углы рамки) через
 * сетку блоков; луч перекрывают только непрозрачные полные блоки (стекло, листва, решётки — прозрачны).
 * Всегда видны: сущности ближе 4 блоков, транспорт/пассажиры игрока, светящиеся (эффект свечения),
 * большие сущности (дракон) и то, на что игрок смотрит в режиме наблюдателя. Результат кэшируется на 4 тика.
 */
public final class AntiEsp {
    private static final Map<Long, long[]> CACHE = new HashMap<>(); // ключ (игрок, сущность) -> {тик, видно}
    private static long tick;

    private AntiEsp() {}

    public static boolean enabled() {
        return ArcConfig.get().antiEsp;
    }

    public static void tick() {
        tick++;
        if (tick % 200 == 0) CACHE.entrySet().removeIf(e -> tick - e.getValue()[0] > 40);
    }

    /** true — сущность скрыта от игрока (не отправлять). */
    public static boolean hidden(Entity e, ServerPlayer viewer) {
        if (!enabled() || e == viewer || viewer.isSpectator()) return false;
        if (e.isCurrentlyGlowing() || e instanceof EnderDragon || e instanceof HangingEntity) return false;
        if (e.getRootVehicle() == viewer.getRootVehicle()) return false;
        if (viewer.getCamera() != viewer) return false;
        Vec3 eye = viewer.getEyePosition();
        AABB box = e.getBoundingBox();
        if (box.getSize() > 6) return false;
        double dx = e.getX() - eye.x, dy = e.getY() - eye.y, dz = e.getZ() - eye.z;
        if (dx * dx + dy * dy + dz * dz < 16) return false;
        long key = ((long) viewer.getId() << 32) ^ (e.getId() & 0xFFFFFFFFL);
        long[] c = CACHE.get(key);
        if (c != null && tick - c[0] < 4) return c[1] == 0;
        boolean visible = visible(viewer.serverLevel(), eye, box);
        CACHE.put(key, new long[]{tick, visible ? 1 : 0});
        return !visible;
    }

    private static boolean visible(ServerLevel level, Vec3 eye, AABB b) {
        double cx = (b.minX + b.maxX) / 2, cz = (b.minZ + b.maxZ) / 2;
        double[][] pts = {
                {cx, (b.minY + b.maxY) / 2, cz}, {cx, b.maxY - 0.05, cz}, {cx, b.minY + 0.05, cz},
                {b.minX, b.maxY, b.minZ}, {b.maxX, b.maxY, b.maxZ}, {b.minX, b.maxY, b.maxZ}, {b.maxX, b.maxY, b.minZ},
                {b.minX, b.minY, b.minZ}, {b.maxX, b.minY, b.maxZ}};
        for (double[] p : pts) if (clear(level, eye.x, eye.y, eye.z, p[0], p[1], p[2])) return true;
        return false;
    }

    /** Луч через сетку блоков (Amanatides–Woo); false — встретился непрозрачный полный блок. */
    private static boolean clear(ServerLevel level, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = Mth.floor(x0), y = Mth.floor(y0), z = Mth.floor(z0);
        int ex = Mth.floor(x1), ey = Mth.floor(y1), ez = Mth.floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy),
                tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LevelChunk chunk = null;
        int steps = 0;
        while (steps++ < 400) {
            if (x == ex && y == ey && z == ez) return true;
            if (tmx < tmy && tmx < tmz) {
                x += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += sy;
                tmy += tdy;
            } else {
                z += sz;
                tmz += tdz;
            }
            if (x == ex && y == ey && z == ez) return true;
            if (chunk == null || chunk.getPos().x != (x >> 4) || chunk.getPos().z != (z >> 4)) {
                chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return true; // не загружено — не скрываем
            }
            BlockState st = chunk.getBlockState(m.set(x, y, z));
            if (st.isSolidRender(level, m)) return false;
        }
        return true;
    }
}

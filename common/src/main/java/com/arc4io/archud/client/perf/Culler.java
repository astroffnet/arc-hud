package com.arc4io.archud.client.perf;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Оптимизация отрисовки:
 *  - occlusion culling: сущности и блок-сущности (сундуки, таблички, баннеры…), полностью закрытые
 *    непрозрачными блоками от камеры, не рисуются (лучи от камеры к углам хитбокса, результат кэшируется);
 *  - дальность для «декора»: рамки, картины, стойки для брони дальше заданного расстояния не рисуются;
 *  - вне поля зрения (FOV) сущности уже отсекает сама игра (frustum culling) — здесь отсекаются те,
 *    что в поле зрения, но за стенами.
 * Проверки идут с бюджетом на кадр, чтобы сама оптимизация не стоила FPS.
 */
public final class Culler {
    private static final int MAX_RAY = 128;

    /** id сущности / позиция блок-сущности -> (тик проверки << 1 | видимость). */
    private static final Long2LongOpenHashMap ENTITIES = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap BLOCK_ENTITIES = new Long2LongOpenHashMap();
    private static int budget;
    private static long frameTick = -1;
    private static ClientLevel lastLevel;
    private static Boolean otherCullingMod;
    public static int culledEntities, culledBlockEntities, shownCulledEntities, shownCulledBlockEntities;

    private Culler() {}

    private static boolean disabledByMod() {
        if (otherCullingMod == null) {
            otherCullingMod = ArcHud.platform().isModLoaded("entityculling");
            if (otherCullingMod) ArcHud.LOGGER.info("Arc HUD: найден EntityCulling — своё отсечение сущностей отключено");
        }
        return otherCullingMod;
    }

    private static void frame(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            ENTITIES.clear();
            BLOCK_ENTITIES.clear();
            lastLevel = level;
        }
        long t = level == null ? 0 : level.getGameTime();
        if (t != frameTick) {
            frameTick = t;
            budget = 120;
            shownCulledEntities = culledEntities;
            shownCulledBlockEntities = culledBlockEntities;
            culledEntities = culledBlockEntities = 0;
            if (ENTITIES.size() > 4096) ENTITIES.clear();
            if (BLOCK_ENTITIES.size() > 8192) BLOCK_ENTITIES.clear();
        }
    }

    /** false — сущность можно не рисовать. */
    public static boolean shouldRenderEntity(Entity e) {
        ArcConfig cfg = ArcConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return true;
        Entity cam = mc.getCameraEntity();
        if (e == cam || e == mc.player || e.hasPassenger(mc.player) || mc.player.isPassengerOfSameVehicle(e)) return true;
        frame(mc);
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        // дальность для декоративных сущностей
        if (cfg.decorCullDistance > 0 && (e instanceof HangingEntity || e instanceof ArmorStand)) {
            double d = cfg.decorCullDistance;
            if (e.distanceToSqr(eye) > d * d) {
                culledEntities++;
                return false;
            }
        }
        if (!cfg.entityCulling || disabledByMod() || e.isCurrentlyGlowing()) return true;
        if (e instanceof Player && !cfg.cullPlayers) return true;
        AABB box = e.getBoundingBoxForCulling();
        if (box.getSize() > 24) return true; // огромные (драконы, боссы модов) — не трогаем
        boolean visible = cached(ENTITIES, e.getId(), 2, () -> visible(mc.level, eye, box.inflate(0.3)));
        if (!visible) culledEntities++;
        return visible;
    }

    public static boolean shouldRenderBlockEntity(BlockEntity be) {
        ArcConfig cfg = ArcConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.blockEntityCulling || mc.level == null || disabledByMod()) return true;
        frame(mc);
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        BlockPos pos = be.getBlockPos();
        AABB box = new AABB(pos).inflate(0.05);
        boolean visible = cached(BLOCK_ENTITIES, pos.asLong(), 5, () -> visible(mc.level, eye, box));
        if (!visible) culledBlockEntities++;
        return visible;
    }

    private static boolean cached(Long2LongOpenHashMap map, long key, int recheck, java.util.function.BooleanSupplier compute) {
        long tick = frameTick;
        long v = map.getOrDefault(key, -1L);
        if (v != -1L && tick - (v >> 1) < recheck) return (v & 1L) == 1L;
        if (budget <= 0) return v == -1L || (v & 1L) == 1L; // нет бюджета — прошлый результат (по умолчанию видимо)
        budget--;
        boolean vis = compute.getAsBoolean();
        map.put(key, (tick << 1) | (vis ? 1L : 0L));
        return vis;
    }

    /** Виден ли хоть один из 9 контрольных точек хитбокса (центр и 8 углов). */
    private static boolean visible(ClientLevel level, Vec3 eye, AABB box) {
        if (box.contains(eye)) return true;
        double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
        if (eye.distanceToSqr(cx, cy, cz) > (double) MAX_RAY * MAX_RAY) return true;
        if (clear(level, eye, cx, cy, cz)) return true;
        double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
        for (double x : xs) for (double y : ys) for (double z : zs) if (clear(level, eye, x, y, z)) return true;
        return false;
    }

    /** Луч без непрозрачных блоков (3D DDA по вокселям). */
    private static boolean clear(ClientLevel level, Vec3 from, double tx, double ty, double tz) {
        double dx = tx - from.x, dy = ty - from.y, dz = tz - from.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) return true;
        dx /= len;
        dy /= len;
        dz /= len;
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int ex = (int) Math.floor(tx), ey = (int) Math.floor(ty), ez = (int) Math.floor(tz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? (x + 1 - from.x) : (from.x - x)) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? (y + 1 - from.y) : (from.y - y)) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? (z + 1 - from.z) : (from.z - z)) * tdz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < MAX_RAY * 3; i++) {
            if (x == ex && y == ey && z == ez) return true;
            if (tmx < tmy && tmx < tmz) {
                if (tmx > len) return true;
                x += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
                if (tmy > len) return true;
                y += sy;
                tmy += tdy;
            } else {
                if (tmz > len) return true;
                z += sz;
                tmz += tdz;
            }
            if (x == ex && y == ey && z == ez) return true;
            m.set(x, y, z);
            if (!level.isLoaded(m)) return true;
            if (level.getBlockState(m).isSolidRender(level, m)) return false;
        }
        return true;
    }
}

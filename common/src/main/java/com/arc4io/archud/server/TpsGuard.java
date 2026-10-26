package com.arc4io.archud.server;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Оптимизации TPS для мира на своём компьютере (одиночная игра / «Открыть для сети»). Работают только когда сервер
 * не успевает (тик дольше 45 мс), так что в обычной игре поведение ванильное:
 * <ul>
 *   <li>t_activation — дальние мобы и предметы (вне боя) обновляются раз в 4 тика (как «activation range» у Spigot);</li>
 *   <li>t_dyn_sim — дальность симуляции временно уменьшается, пока сервер отстаёт, и возвращается, когда догонит;</li>
 *   <li>t_spawn — естественный спавн мобов через тик;</li>
 *   <li>t_random — при сильном лаге (тик дольше 80 мс) случайные тики блоков (рост растений и т. п.) вдвое реже.</li>
 * </ul>
 */
public final class TpsGuard {
    private static volatile float mspt;
    private static int tick;
    private static volatile int simLimit; // 0 — без ограничения
    private static volatile int lastOptionSim = 12;
    private static long lagSince, okSince, lastSimChange;

    private TpsGuard() {}

    private static boolean local(MinecraftServer s) {
        return s != null && !s.isDedicatedServer();
    }

    /** Из конца тика сервера. */
    public static void tick(MinecraftServer s) {
        if (!local(s)) return;
        tick++;
        mspt = s.getAverageTickTime();
        long now = System.currentTimeMillis();
        int before = simLimit;
        updateSim(now);
        if (simLimit != before) {
            // встроенный сервер каждый тик сверяет дальность с настройкой и через миксин получит ограничение
            s.getPlayerList().setSimulationDistance(simulationDistance(lastOptionSim));
        }
    }

    private static void updateSim(long now) {
        if (!Perf.on("t_dyn_sim")) {
            simLimit = 0;
            return;
        }
        int base = Math.max(2, lastOptionSim);
        int cur = simLimit > 0 ? simLimit : base;
        if (mspt > 50F) {
            okSince = 0;
            if (lagSince == 0) lagSince = now;
            if (now - lagSince > 5000 && now - lastSimChange > 5000 && cur > 4) {
                simLimit = cur - 1;
                lastSimChange = now;
            }
        } else if (mspt < 30F) {
            lagSince = 0;
            if (okSince == 0) okSince = now;
            if (simLimit > 0 && now - okSince > 10_000 && now - lastSimChange > 10_000) {
                simLimit = simLimit + 1 >= base ? 0 : simLimit + 1;
                lastSimChange = now;
            }
        } else {
            lagSince = okSince = 0;
        }
    }

    public static boolean lagging() {
        return mspt > 45F;
    }

    public static float mspt() {
        return mspt;
    }

    /** Дальность симуляции для встроенного сервера с учётом временного ограничения. */
    public static int simulationDistance(int fromOptions) {
        int l = simLimit;
        return l > 0 && l < fromOptions ? l : fromOptions;
    }

    /** Из миксина встроенного сервера: дальность симуляции из настроек игры. */
    public static void optionSim(int v) {
        lastOptionSim = v;
    }

    public static int simLimit() {
        return simLimit;
    }

    /** Пропустить тик сущности (дальняя, вне боя, сервер отстаёт). */
    public static boolean skipEntity(ServerLevel level, Entity e) {
        if (!lagging() || !local(level.getServer())) return false;
        int range = Perf.get("t_activation");
        if (range <= 0) return false;
        if ((tick + e.getId()) % 4 == 0) return false;
        if (e instanceof Mob mob) {
            if (mob instanceof EnderDragon || mob instanceof WitherBoss) return false;
            if (mob.getTarget() != null || mob.hurtTime > 0 || mob.isVehicle() || mob.isLeashed() || mob.isPassenger()) return false;
        } else if (!(e instanceof ItemEntity) && !(e instanceof ExperienceOrb)) {
            return false; // снаряды, вагонетки, падающие блоки и т. п. — всегда
        }
        double r2 = (double) range * range;
        for (Player p : level.players()) {
            if (p.distanceToSqr(e) < r2) return false;
        }
        return true;
    }

    public static boolean skipSpawn() {
        return lagging() && (tick & 1) == 1 && Perf.on("t_spawn");
    }

    public static int randomTicks(int speed) {
        return mspt > 80F && Perf.on("t_random") ? speed / 2 : speed;
    }

    /** Клиент: плавность движения сущностей при низком TPS (интерполяция на весь интервал между обновлениями). */
    public static int lerpSteps(int steps) {
        if (!Perf.on("t_smooth_entities")) return steps;
        float m = mspt;
        if (m <= 55F) return steps;
        return Math.min(steps * 8, Math.round(steps * Math.min(8F, m / 50F)));
    }
}

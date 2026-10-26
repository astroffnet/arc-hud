package com.arc4io.archud.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AntiESP для Bukkit/Spigot/Paper: сущности, полностью скрытые от игрока непрозрачными блоками, прячутся
 * через Player#hideEntity (Spigot 1.18+) — клиент о них не знает, и читы с подсветкой сквозь стены ничего не видят.
 * Видимость — лучи от глаз к 9 точкам рамки сущности; перекрывают только непрозрачные полные блоки.
 * Всегда видны: ближе 4 блоков, транспорт/пассажиры, светящиеся, дракон, рамки/картины.
 */
final class AntiEsp {
    private final ArcHudPlugin plugin;
    /** Кого мы спрятали от кого: игрок → сущности. */
    private final Map<UUID, Set<UUID>> hiddenBy = new HashMap<>();
    private boolean supported = true;

    AntiEsp(ArcHudPlugin plugin) {
        this.plugin = plugin;
        try {
            Player.class.getMethod("hideEntity", org.bukkit.plugin.Plugin.class, Entity.class);
        } catch (NoSuchMethodException e) {
            supported = false;
            plugin.getLogger().warning("AntiESP needs Spigot/Paper 1.18+ (Player#hideEntity) — disabled");
        }
    }

    boolean hiddenByUs(Player viewer, Entity e) {
        Set<UUID> s = hiddenBy.get(viewer.getUniqueId());
        return s != null && s.contains(e.getUniqueId());
    }

    void tick() {
        boolean on = supported && plugin.getConfig().getBoolean("anti-esp", true);
        if (!on) {
            if (!hiddenBy.isEmpty()) showAll();
            return;
        }
        double r = plugin.getConfig().getDouble("anti-esp-range", 64);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<UUID> mine = hiddenBy.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            Location eye = viewer.getEyeLocation();
            Set<UUID> seen = new HashSet<>();
            for (Entity e : viewer.getWorld().getNearbyEntities(eye, r, r, r)) {
                if (e == viewer) continue;
                seen.add(e.getUniqueId());
                boolean hide = shouldHide(viewer, eye, e);
                if (hide && mine.add(e.getUniqueId())) viewer.hideEntity(plugin, e);
                else if (!hide && mine.remove(e.getUniqueId())) viewer.showEntity(plugin, e);
            }
            // ушли из радиуса / в другой мир — вернуть видимость (дальше решает ванильная дистанция)
            mine.removeIf(id -> {
                if (seen.contains(id)) return false;
                Entity e = Bukkit.getEntity(id);
                if (e != null) viewer.showEntity(plugin, e);
                return true;
            });
        }
    }

    private boolean shouldHide(Player viewer, Location eye, Entity e) {
        if (viewer.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        if (e.isGlowing() || e instanceof EnderDragon || e instanceof Hanging) return false;
        if (viewer.getVehicle() == e || e.getVehicle() == viewer || viewer.getPassengers().contains(e)) return false;
        BoundingBox b = e.getBoundingBox();
        if (b.getWidthX() > 6 || b.getHeight() > 6) return false;
        if (eye.distanceSquared(e.getLocation()) < 16) return false;
        World w = viewer.getWorld();
        double cx = b.getCenterX(), cz = b.getCenterZ();
        double[][] pts = {{cx, b.getCenterY(), cz}, {cx, b.getMaxY() - 0.05, cz}, {cx, b.getMinY() + 0.05, cz},
                {b.getMinX(), b.getMaxY(), b.getMinZ()}, {b.getMaxX(), b.getMaxY(), b.getMaxZ()}, {b.getMinX(), b.getMaxY(), b.getMaxZ()},
                {b.getMaxX(), b.getMaxY(), b.getMinZ()}, {b.getMinX(), b.getMinY(), b.getMinZ()}, {b.getMaxX(), b.getMinY(), b.getMaxZ()}};
        for (double[] p : pts) if (clear(w, eye.getX(), eye.getY(), eye.getZ(), p[0], p[1], p[2])) return false;
        return true;
    }

    /** Луч через сетку блоков; false — встретился непрозрачный полный блок (isOccluding). */
    private static boolean clear(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1), ez = (int) Math.floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy),
                tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        for (int steps = 0; steps < 400; steps++) {
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
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return true;
            if (w.getBlockAt(x, y, z).getType().isOccluding()) return false;
        }
        return true;
    }

    void forget(Player p) {
        hiddenBy.remove(p.getUniqueId());
        // если этот игрок был спрятан от других — вернуть
        for (Map.Entry<UUID, Set<UUID>> e : hiddenBy.entrySet()) {
            if (e.getValue().remove(p.getUniqueId())) {
                Player viewer = Bukkit.getPlayer(e.getKey());
                if (viewer != null) viewer.showEntity(plugin, p);
            }
        }
    }

    void showAll() {
        for (Map.Entry<UUID, Set<UUID>> e : hiddenBy.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null) continue;
            for (UUID id : e.getValue()) {
                Entity en = Bukkit.getEntity(id);
                if (en != null) viewer.showEntity(plugin, en);
            }
        }
        hiddenBy.clear();
    }
}

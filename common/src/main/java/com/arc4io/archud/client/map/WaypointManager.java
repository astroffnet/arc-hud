package com.arc4io.archud.client.map;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Метки: свои (сохраняются по миру/серверу в config/archud/waypoints), точка смерти,
 * а также метки модов-интеграций (вэйстоуны, локации Tempad).
 */
public final class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Waypoint> USER = new CopyOnWriteArrayList<>();
    private static final List<Supplier<List<Waypoint>>> PROVIDERS = new CopyOnWriteArrayList<>();
    private static List<Waypoint> modCache = List.of();
    /** Метки, которыми поделились другие игроки (до сохранения — только в памяти). */
    private static final List<Waypoint> SHARED = new CopyOnWriteArrayList<>();
    private static long modCacheTime;
    private static String worldKey = "";

    private WaypointManager() {}

    /** Точка возрождения: кровать или якорь возрождения (с сервера Arc HUD или запомненная, когда вы спали). */
    private static String respawnDim;
    private static net.minecraft.core.BlockPos respawnPos;
    private static boolean respawnAnchor, respawnFromServer;

    static {
        PROVIDERS.add(() -> {
            if (!ArcConfig.get().spawnWaypoint || respawnPos == null || respawnDim == null) return List.of();
            Waypoint w = new Waypoint(Component.translatable(respawnAnchor ? "archud.wp.anchor" : "archud.wp.bed").getString(),
                    respawnPos.getX(), respawnPos.getY(), respawnPos.getZ(), respawnAnchor ? 0xFFB388FF : 0xFFFF6E6E, respawnDim,
                    Waypoint.Source.SPAWN);
            return List.of(w);
        });
    }

    public static void setRespawn(String dim, net.minecraft.core.BlockPos pos, boolean anchor, boolean fromServer) {
        respawnDim = dim;
        respawnPos = pos;
        respawnAnchor = anchor;
        if (fromServer) respawnFromServer = true;
        modCacheTime = 0;
        // запомнить для этого мира (если сервер без Arc HUD)
        String key = worldKey();
        if (pos == null) ArcConfig.get().respawnPoints.remove(key);
        else ArcConfig.get().respawnPoints.put(key, dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|" + anchor);
        ArcConfig.save();
    }

    /** Без сервера Arc HUD: кровать запоминается, когда вы на ней спите. */
    private static void trackBed(Minecraft mc) {
        if (respawnFromServer || mc.player == null || mc.level == null || !mc.player.isSleeping()) return;
        mc.player.getSleepingPos().ifPresent(pos -> {
            String dim = mc.level.dimension().location().toString();
            if (!pos.equals(respawnPos) || !dim.equals(respawnDim)) setRespawn(dim, pos, false, false);
        });
    }

    private static void loadRespawn() {
        respawnFromServer = false;
        respawnPos = null;
        respawnDim = null;
        String v = ArcConfig.get().respawnPoints.get(worldKey);
        if (v == null) return;
        try {
            String[] p = v.split("\\|");
            respawnDim = p[0];
            respawnPos = new net.minecraft.core.BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            respawnAnchor = p.length > 4 && Boolean.parseBoolean(p[4]);
        } catch (Exception ignored) {
        }
    }

    /** Источник меток другого мода (вызывается раз в секунду). */
    public static void registerProvider(Supplier<List<Waypoint>> provider) {
        PROVIDERS.add(provider);
    }

    public static List<Waypoint> user() {
        return USER;
    }

    public static List<Waypoint> all() {
        long now = System.currentTimeMillis();
        if (now - modCacheTime > 1000) {
            modCacheTime = now;
            List<Waypoint> list = new ArrayList<>();
            for (Supplier<List<Waypoint>> p : PROVIDERS) {
                try {
                    List<Waypoint> l = p.get();
                    if (l != null) list.addAll(l);
                } catch (Throwable ignored) {
                }
            }
            modCache = list;
        }
        List<Waypoint> out = new ArrayList<>(USER);
        out.addAll(SHARED);
        out.addAll(modCache);
        return out;
    }

    public static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    public static String worldKey() {
        if (worldKey.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) return computeWorldKey(mc);
        }
        return worldKey.isEmpty() ? "unknown" : worldKey;
    }

    private static String computeWorldKey(Minecraft mc) {
        String k;
        if (mc.getSingleplayerServer() != null) k = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip;
            // в локальной сети порт каждый раз новый — без него карта и метки не терялись бы между сессиями
            if (mc.getCurrentServer().isLan() || ip.matches("(?i)(localhost|127\\..*|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*|25\\..*|26\\..*)(:\\d+)?")) {
                int c = ip.lastIndexOf(':');
                if (c > 0 && ip.indexOf(':') == c) ip = ip.substring(0, c);
            }
            k = "mp_" + ip;
        }
        else k = "unknown";
        return k.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    private static Path file() {
        return ArcHud.platform().configDir().resolve("archud").resolve("waypoints").resolve(worldKey + ".json");
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            worldKey = "";
            return;
        }
        String k = computeWorldKey(mc);
        if (!k.equals(worldKey)) {
            worldKey = k;
            load();
            loadRespawn();
        }
        trackBed(mc);
    }

    private static void load() {
        USER.clear();
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    List<Waypoint> list = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
                    if (list != null) USER.addAll(list);
                }
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось загрузить метки", e);
        }
    }

    public static void save() {
        if (worldKey.isEmpty()) return;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(USER), w);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось сохранить метки", e);
        }
    }

    /** Новая метка на месте игрока — ещё НЕ сохранена (сохраняется кнопкой «Готово» в редакторе, Esc — отмена). */
    public static Waypoint createHere() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        int n = (int) USER.stream().filter(w -> w.source == Waypoint.Source.USER).count() + 1;
        return new Waypoint(Component.translatable("archud.wp.default", n).getString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                Waypoint.PALETTE[(n - 1) % Waypoint.PALETTE.length], currentDimension(), Waypoint.Source.USER);
    }

    public static Waypoint addHere() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        int n = (int) USER.stream().filter(w -> w.source == Waypoint.Source.USER).count() + 1;
        Waypoint w = new Waypoint(Component.translatable("archud.wp.default", n).getString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                Waypoint.PALETTE[(n - 1) % Waypoint.PALETTE.length], currentDimension(), Waypoint.Source.USER);
        USER.add(w);
        save();
        return w;
    }

    public static void add(Waypoint w) {
        if (w.source == Waypoint.Source.SHARED) {
            SHARED.remove(w);
            w.source = Waypoint.Source.USER;
        }
        if (!USER.contains(w)) USER.add(w);
        save();
    }

    public static void remove(Waypoint w) {
        USER.remove(w);
        SHARED.remove(w);
        save();
    }

    private static long lastDeath;

    /** История смертей: последние 5 точек, новые — ярче. */
    public static void onDeath() {
        Minecraft mc = Minecraft.getInstance();
        if (!ArcConfig.get().deathWaypoint || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastDeath < 3000) return; // экран смерти может открываться повторно
        lastDeath = now;
        LocalPlayer p = mc.player;
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        Waypoint w = new Waypoint(Component.translatable("archud.wp.death_at", time).getString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                0xFFFF5252, currentDimension(), Waypoint.Source.DEATH);
        USER.add(w);
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint d : USER) if (d.source == Waypoint.Source.DEATH) deaths.add(d);
        deaths.sort(java.util.Comparator.comparingLong(d -> d.created));
        while (deaths.size() > 5) USER.remove(deaths.remove(0));
        for (Waypoint d : deaths) if (d != w) d.color = 0xFF8E3B3B;
        if (ArcConfig.get().navigateToDeath) Navigator.set(w);
        save();
    }

    public static List<Waypoint> inDimension() {
        String dim = currentDimension();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : all()) if (w.visible && dim.equals(w.dimension) && sourceVisible(w.source)) out.add(w);
        Waypoint nav = Navigator.target();
        if (nav != null && nav.source == Waypoint.Source.TEMP && dim.equals(nav.dimension)) out.add(nav);
        return Collections.unmodifiableList(out);
    }

    public static boolean sourceVisible(Waypoint.Source s) {
        return !ArcConfig.get().hiddenWaypointSources.contains(s.name());
    }

    public static void toggleSource(Waypoint.Source s) {
        List<String> h = ArcConfig.get().hiddenWaypointSources;
        if (!h.remove(s.name())) h.add(s.name());
        ArcConfig.save();
    }

    /** Метка от другого игрока. */
    public static void addShared(Waypoint w) {
        SHARED.removeIf(o -> o.x == w.x && o.y == w.y && o.z == w.z && o.dimension.equals(w.dimension));
        SHARED.add(w);
        while (SHARED.size() > 30) SHARED.remove(0);
    }

    public static void clearShared() {
        SHARED.clear();
    }

    /** Поделиться меткой с игроком (target = ник) или со всеми (target пустой). Без Arc HUD на сервере — в чат. */
    public static boolean shareWith(Waypoint w, String target) {
        if (!com.arc4io.archud.client.ArcClientNet.canShare()) {
            if (target == null || target.isEmpty()) share(w);
            else if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().sendCommand("msg " + target + " [" + w.name + "] x:" + w.x + " y:" + w.y + " z:" + w.z
                        + " (" + w.dimension.substring(w.dimension.indexOf(':') + 1) + ")");
            }
            return false;
        }
        com.arc4io.archud.client.ArcClientNet.shareWaypoint(w, target == null ? "" : target);
        return true;
    }

    /** Отправить координаты метки в чат. */
    public static void share(Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String dim = w.dimension.substring(w.dimension.indexOf(':') + 1);
        mc.getConnection().sendChat("[" + w.name + "] x:" + w.x + " y:" + w.y + " z:" + w.z + " (" + dim + ")");
    }
}

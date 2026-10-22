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

    static {
        // точка спавна мира (только в Верхнем мире)
        PROVIDERS.add(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (!ArcConfig.get().spawnWaypoint || mc.level == null) return List.of();
            net.minecraft.core.BlockPos sp = mc.level.getSharedSpawnPos();
            return List.of(new Waypoint(Component.translatable("archud.wp.spawn").getString(), sp.getX(), sp.getY(), sp.getZ(),
                    0xFF9CFF5A, "minecraft:overworld", Waypoint.Source.SPAWN));
        });
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
        else if (mc.getCurrentServer() != null) k = "mp_" + mc.getCurrentServer().ip;
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
        }
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

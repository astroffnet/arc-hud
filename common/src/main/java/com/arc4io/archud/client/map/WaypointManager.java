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
    private static long modCacheTime;
    private static String worldKey = "";

    private WaypointManager() {}

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
        out.addAll(modCache);
        return out;
    }

    public static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
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
        if (!USER.contains(w)) USER.add(w);
        save();
    }

    public static void remove(Waypoint w) {
        USER.remove(w);
        save();
    }

    public static void onDeath() {
        Minecraft mc = Minecraft.getInstance();
        if (!ArcConfig.get().deathWaypoint || mc.player == null) return;
        USER.removeIf(w -> w.source == Waypoint.Source.DEATH);
        LocalPlayer p = mc.player;
        USER.add(new Waypoint(Component.translatable("archud.wp.death").getString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                0xFFFF5252, currentDimension(), Waypoint.Source.DEATH));
        save();
    }

    public static List<Waypoint> inDimension() {
        String dim = currentDimension();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : all()) if (w.visible && dim.equals(w.dimension)) out.add(w);
        return Collections.unmodifiableList(out);
    }
}

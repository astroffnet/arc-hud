package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.network.ArcNet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Глобальные метки: их создаёт администратор (оператор, вошедший в аккаунт), и их сразу видят все игроки с Arc HUD.
 * Игроки не могут их удалить или отклонить — только администратор. Хранятся в мире: data/archud_global_waypoints.json.
 * Ещё здесь id мира (data/archud_world_id.txt) — по нему клиент понимает, что мир на сервере пересоздан,
 * и убирает свою старую сохранённую карту.
 */
public final class GlobalWaypoints {
    public static final class Entry {
        public String id, name, dim, icon, author;
        public int x, y, z, color;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Entry> LIST = new ArrayList<>();
    private static MinecraftServer loadedFor;
    private static String worldId = "";

    private GlobalWaypoints() {}

    public static void init() {
        ArcNet.onServer("wp_global_add", (player, buf) -> {
            String name = buf.readUtf(64);
            int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
            String dim = buf.readUtf(128);
            int color = buf.readInt();
            String icon = buf.readUtf(32);
            if (!canEdit(player)) {
                player.displayClientMessage(Component.translatable("archud.wp.global_no_permission").withStyle(ChatFormatting.RED), true);
                return;
            }
            ensure(player.getServer());
            if (LIST.size() >= 512) return;
            Entry e = new Entry();
            e.id = UUID.randomUUID().toString().substring(0, 13);
            e.name = name.isBlank() ? "★" : name;
            e.x = x;
            e.y = y;
            e.z = z;
            e.dim = dim;
            e.color = color;
            e.icon = icon;
            e.author = player.getGameProfile().getName();
            LIST.add(e);
            save(player.getServer());
            broadcast(player.getServer());
            player.displayClientMessage(Component.translatable("archud.wp.global_added", e.name).withStyle(ChatFormatting.GREEN), true);
        });
        ArcNet.onServer("wp_global_remove", (player, buf) -> {
            String id = buf.readUtf(64);
            if (!canEdit(player)) return;
            ensure(player.getServer());
            if (LIST.removeIf(e -> e.id.equals(id))) {
                save(player.getServer());
                broadcast(player.getServer());
            }
        });
    }

    /** Администратор: оператор (уровень 2+), прошедший вход в аккаунт. */
    public static boolean canEdit(ServerPlayer p) {
        return p.hasPermissions(2) && AuthGate.isAuthenticated(p) && ArcServer.isArcPlayer(p.getUUID());
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data");
    }

    private static void ensure(MinecraftServer server) {
        if (server == null || loadedFor == server) return;
        loadedFor = server;
        LIST.clear();
        worldId = "";
        try {
            Path f = dir(server).resolve("archud_global_waypoints.json");
            if (Files.exists(f)) {
                List<Entry> l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() {}.getType());
                if (l != null) for (Entry e : l) if (e != null && e.id != null && e.dim != null) LIST.add(e);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot load global waypoints", e);
        }
        try {
            Path f = dir(server).resolve("archud_world_id.txt");
            if (Files.exists(f)) worldId = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (worldId.isEmpty()) {
                worldId = UUID.randomUUID().toString();
                Files.createDirectories(f.getParent());
                Files.writeString(f, worldId, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot read world id", e);
        }
    }

    /** Id этого мира (создаётся один раз при первом запуске мира с Arc HUD). */
    public static String worldId(MinecraftServer server) {
        ensure(server);
        return worldId;
    }

    private static void save(MinecraftServer server) {
        try {
            Path f = dir(server).resolve("archud_global_waypoints.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(LIST), StandardCharsets.UTF_8);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save global waypoints", e);
        }
    }

    public static void send(ServerPlayer p) {
        ensure(p.getServer());
        FriendlyByteBuf buf = ArcNet.create("wp_global");
        buf.writeBoolean(canEdit(p));
        buf.writeVarInt(LIST.size());
        for (Entry e : LIST) {
            buf.writeUtf(e.id, 64);
            buf.writeUtf(e.name == null ? "" : e.name, 64);
            buf.writeInt(e.x);
            buf.writeInt(e.y);
            buf.writeInt(e.z);
            buf.writeUtf(e.dim, 128);
            buf.writeInt(e.color);
            buf.writeUtf(e.icon == null ? "" : e.icon, 32);
            buf.writeUtf(e.author == null ? "" : e.author, 32);
        }
        ArcNet.sendToPlayer(p, buf);
    }

    private static void broadcast(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (ArcServer.isArcPlayer(p.getUUID())) send(p);
        }
    }

    /** Права игрока могли измениться (/op, /deop) — переслать флаг «можно редактировать». */
    private static final java.util.Map<UUID, Boolean> LAST_PERM = new java.util.HashMap<>();

    public static void tick(MinecraftServer server, int tick) {
        if (tick % 40 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!ArcServer.isArcPlayer(p.getUUID())) continue;
            boolean can = canEdit(p);
            Boolean prev = LAST_PERM.put(p.getUUID(), can);
            if (prev != null && prev != can) send(p);
        }
    }

    public static void onLogout(ServerPlayer p) {
        LAST_PERM.remove(p.getUUID());
    }

    public static void reset() {
        loadedFor = null;
        LIST.clear();
        LAST_PERM.clear();
    }
}

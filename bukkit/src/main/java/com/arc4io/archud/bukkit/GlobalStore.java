package com.arc4io.archud.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Глобальные метки администратора (видят все игроки с Arc HUD, удалить может только администратор)
 * и id мира, по которому клиент узнаёт, что мир пересоздан, и убирает свою старую карту.
 * Хранятся в plugins/ArcHUD/global_waypoints.tsv и в папке основного мира (archud_world_id.txt).
 */
final class GlobalStore {
    static final class Entry {
        String id, name, dim, icon, author;
        int x, y, z, color;
    }

    private final ArcHudPlugin plugin;
    private final List<Entry> list = new ArrayList<>();
    private final Map<UUID, Boolean> lastPerm = new HashMap<>();
    private String worldId;

    GlobalStore(ArcHudPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("global_waypoints.tsv");
    }

    private void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length < 10) continue;
                Entry e = new Entry();
                e.id = p[0];
                e.name = p[1];
                e.x = Integer.parseInt(p[2]);
                e.y = Integer.parseInt(p[3]);
                e.z = Integer.parseInt(p[4]);
                e.dim = p[5];
                e.color = (int) Long.parseLong(p[6]);
                e.icon = p[7];
                e.author = p[8];
                list.add(e);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Cannot load global waypoints: " + ex);
        }
    }

    private void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (Entry e : list) {
                lines.add(String.join("\t", e.id, clean(e.name), Integer.toString(e.x), Integer.toString(e.y), Integer.toString(e.z), e.dim,
                        Integer.toString(e.color), clean(e.icon), clean(e.author), "-"));
            }
            Files.createDirectories(file().getParent());
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            plugin.getLogger().warning("Cannot save global waypoints: " + ex);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** Администратор: оператор (или право archud.global), вошедший в аккаунт. */
    boolean canEdit(Player p) {
        return (p.isOp() || p.hasPermission("archud.global")) && plugin.auth.isAuthenticated(p) && plugin.arcPlayers.contains(p.getUniqueId());
    }

    String worldId() {
        if (worldId != null) return worldId;
        worldId = "";
        try {
            Path f = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("archud_world_id.txt");
            if (Files.exists(f)) worldId = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (worldId.isEmpty()) {
                worldId = UUID.randomUUID().toString();
                Files.writeString(f, worldId, StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Cannot read world id: " + ex);
        }
        return worldId;
    }

    void onAdd(Player p, Buf buf) {
        String name = buf.readUtf(64);
        int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
        String dim = buf.readUtf(128);
        int color = buf.readInt();
        String icon = buf.readUtf(32);
        if (!canEdit(p)) {
            p.sendMessage("§cТолько администратор / Admins only");
            return;
        }
        if (list.size() >= 512) return;
        Entry e = new Entry();
        e.id = UUID.randomUUID().toString().substring(0, 13);
        e.name = name.isBlank() ? "★" : name;
        e.x = x;
        e.y = y;
        e.z = z;
        e.dim = dim;
        e.color = color;
        e.icon = icon;
        e.author = p.getName();
        list.add(e);
        save();
        broadcast();
        p.sendMessage("§a★ " + e.name);
    }

    void onRemove(Player p, Buf buf) {
        String id = buf.readUtf(64);
        if (!canEdit(p)) return;
        if (list.removeIf(e -> e.id.equals(id))) {
            save();
            broadcast();
        }
    }

    void send(Player p) {
        Buf buf = ArcHudPlugin.packet("wp_global").bool(canEdit(p)).varInt(list.size());
        for (Entry e : list) {
            buf.utf(e.id).utf(e.name == null ? "" : e.name).i32(e.x).i32(e.y).i32(e.z).utf(e.dim).i32(e.color)
                    .utf(e.icon == null ? "" : e.icon).utf(e.author == null ? "" : e.author);
        }
        plugin.send(p, buf);
        lastPerm.put(p.getUniqueId(), canEdit(p));
    }

    private void broadcast() {
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.arcPlayers.contains(p.getUniqueId())) send(p);
    }

    /** /op, /deop — переслать флаг «администратор». */
    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.arcPlayers.contains(p.getUniqueId())) continue;
            Boolean prev = lastPerm.get(p.getUniqueId());
            if (prev != null && prev != canEdit(p)) send(p);
        }
    }

    void forget(UUID id) {
        lastPerm.remove(id);
    }
}

package com.arc4io.archud.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Метки игроков: хранятся в plugins/ArcHUD/waypoints/&lt;ник&gt;.json.gz — по нику С УЧЁТОМ регистра
 * (как в моде: «Arc4io» → arc4io@1). Отдаются игроку после входа (/login), принимаются только от вошедших.
 */
final class WaypointStore {
    private static final int MAX_BYTES = 512 * 1024;
    private final ArcHudPlugin plugin;
    private final Map<UUID, ByteArrayOutputStream> parts = new HashMap<>();
    private final Map<UUID, Long> lastShare = new HashMap<>();

    WaypointStore(ArcHudPlugin plugin) {
        this.plugin = plugin;
    }

    /** Ключ файла по нику с учётом регистра (Windows/macOS не различают регистр имён файлов). */
    static String key(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_");
        BigInteger mask = BigInteger.ZERO;
        for (int i = 0; i < name.length(); i++) if (Character.isUpperCase(name.charAt(i))) mask = mask.setBit(i);
        return mask.signum() == 0 ? lower : lower + "@" + mask.toString(16);
    }

    private Path file(Player p) {
        return plugin.getDataFolder().toPath().resolve("waypoints").resolve(key(p.getName()) + ".json.gz");
    }

    void onSync(Player p, Buf buf) {
        if (!plugin.getConfig().getBoolean("waypoint-sync", true)) return;
        int part = buf.readVarInt(), total = buf.readVarInt();
        byte[] data = buf.readByteArray(30000);
        if (total <= 0 || total > MAX_BYTES / 1000 || part < 0 || part >= total) return;
        ByteArrayOutputStream acc = part == 0 ? new ByteArrayOutputStream() : parts.get(p.getUniqueId());
        if (acc == null) return;
        if (acc.size() + data.length > MAX_BYTES) {
            parts.remove(p.getUniqueId());
            return;
        }
        acc.writeBytes(data);
        if (part + 1 < total) {
            parts.put(p.getUniqueId(), acc);
            return;
        }
        parts.remove(p.getUniqueId());
        try {
            Path f = file(p);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, acc.toByteArray());
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("Cannot save waypoints of " + p.getName() + ": " + e);
        }
    }

    void sendSaved(Player p) {
        if (!plugin.getConfig().getBoolean("waypoint-sync", true)) return;
        byte[] data = null;
        try {
            Path f = file(p);
            if (Files.exists(f) && Files.size(f) <= MAX_BYTES) data = Files.readAllBytes(f);
        } catch (Exception e) {
            plugin.getLogger().warning("Cannot read waypoints of " + p.getName() + ": " + e);
        }
        Buf buf = ArcHudPlugin.packet("wp_saved").bool(data != null);
        if (data != null) buf.bytes(data);
        plugin.send(p, buf);
    }

    void onShare(Player p, Buf buf) {
        if (!plugin.getConfig().getBoolean("waypoint-sharing", true)) return;
        long now = System.currentTimeMillis();
        Long last = lastShare.get(p.getUniqueId());
        if (last != null && now - last < 750) return;
        lastShare.put(p.getUniqueId(), now);
        String target = buf.readUtf(32), name = buf.readUtf(64);
        int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
        String dim = buf.readUtf(128);
        int color = buf.readInt();
        int sent = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || (!target.isEmpty() && !o.getName().equalsIgnoreCase(target))) continue;
            if (plugin.arcPlayers.contains(o.getUniqueId())) {
                plugin.send(o, ArcHudPlugin.packet("wp_shared").utf(p.getName()).utf(name).i32(x).i32(y).i32(z).utf(dim).i32(color));
            } else {
                o.sendMessage("§b[" + p.getName() + "] ◆ " + name + " — x:" + x + " y:" + y + " z:" + z + " (" + dim + ")");
            }
            sent++;
        }
        p.sendMessage(sent > 0 ? "§a◆ ✔ " + sent : "§c◆ ✘ 0");
    }

    void forget(UUID id) {
        parts.remove(id);
        lastShare.remove(id);
    }
}

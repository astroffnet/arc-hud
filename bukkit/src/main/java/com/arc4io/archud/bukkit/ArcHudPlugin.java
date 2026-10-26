package com.arc4io.archud.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Arc HUD — серверная часть для Bukkit / Spigot / Paper (1.20.1 и новее).
 * Говорит на том же протоколе, что и мод (каналы archud:c2s / archud:s2c для Fabric и archud:main для Forge):
 * общая карта, позиции игроков, «скрыть меня», хранение и обмен метками, точка возрождения, телепорт
 * с карты (операторам), авторизация (/login), админ-панель, AntiESP.
 */
public final class ArcHudPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    static final String FABRIC_IN = "archud:c2s", FABRIC_OUT = "archud:s2c", FORGE = "archud:main";
    static final int PROTOCOL = 1;

    final Set<UUID> arcPlayers = new HashSet<>();
    final Set<UUID> hidden = new HashSet<>();
    private final Set<UUID> forgeClients = new HashSet<>();
    private final Map<UUID, Integer> pendingHello = new HashMap<>();
    private final Map<UUID, String> lastRespawn = new HashMap<>();

    MapStore map;
    WaypointStore waypoints;
    Auth auth;
    Admin admin;
    AntiEsp antiEsp;
    private int tick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        map = new MapStore(this);
        waypoints = new WaypointStore(this);
        auth = new Auth(this);
        admin = new Admin(this);
        antiEsp = new AntiEsp(this);
        var m = getServer().getMessenger();
        m.registerIncomingPluginChannel(this, FABRIC_IN, this);
        m.registerIncomingPluginChannel(this, FORGE, this);
        m.registerOutgoingPluginChannel(this, FABRIC_OUT);
        m.registerOutgoingPluginChannel(this, FORGE);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("Arc HUD server part " + getDescription().getVersion() + " enabled (map, players, waypoints, admin panel, AntiESP)");
    }

    @Override
    public void onDisable() {
        map.save();
        antiEsp.showAll();
    }

    // ------------------------------------------------------------------ сеть
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        try {
            Buf buf;
            if (channel.equals(FORGE)) {
                if (data.length < 1 || data[0] != 2) return; // 2 — «сырые» данные Arc HUD в SimpleChannel Forge
                forgeClients.add(player.getUniqueId());
                buf = Buf.read(Buf.read(data, 1).readByteArray(1 << 16), 0);
            } else {
                buf = Buf.read(data, 0);
            }
            String type = buf.readUtf(64);
            handle(player, type, buf);
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "bad packet from " + player.getName(), t);
        }
    }

    void send(Player p, Buf payload) {
        byte[] body = payload.bytes();
        if (forgeClients.contains(p.getUniqueId())) {
            Buf framed = Buf.write();
            byte[] head = {3};
            byte[] inner = framed.bytes(body).bytes();
            byte[] out = new byte[inner.length + 1];
            out[0] = head[0];
            System.arraycopy(inner, 0, out, 1, inner.length);
            p.sendPluginMessage(this, FORGE, out);
        } else {
            p.sendPluginMessage(this, FABRIC_OUT, body);
        }
    }

    static Buf packet(String type) {
        return Buf.write().utf(type);
    }

    private void handle(Player p, String type, Buf buf) {
        switch (type) {
            case "hello" -> {
                int protocol = buf.readVarInt();
                if (!auth.isAuthenticated(p)) pendingHello.put(p.getUniqueId(), protocol);
                else completeHello(p, protocol);
            }
            case "map_chunks" -> {
                if (arcPlayers.contains(p.getUniqueId())) map.onChunks(p, buf);
            }
            case "wp_share" -> {
                if (arcPlayers.contains(p.getUniqueId())) waypoints.onShare(p, buf);
            }
            case "wp_sync" -> {
                if (arcPlayers.contains(p.getUniqueId())) waypoints.onSync(p, buf);
            }
            case "privacy" -> {
                if (!arcPlayers.contains(p.getUniqueId())) return;
                if (buf.readBoolean()) hidden.add(p.getUniqueId());
                else hidden.remove(p.getUniqueId());
            }
            case "safe_tp" -> {
                if (arcPlayers.contains(p.getUniqueId())) safeTp(p, buf);
            }
            case "admin_open", "admin_refresh" -> admin.sendState(p);
            case "admin_action" -> admin.onAction(p, buf.readUtf(64), buf.readUtf(64), buf.readUtf(256));
            default -> {
            }
        }
    }

    private void completeHello(Player p, int protocol) {
        arcPlayers.add(p.getUniqueId());
        lastRespawn.remove(p.getUniqueId());
        var cfg = getConfig();
        send(p, packet("welcome").varInt(PROTOCOL).bool(cfg.getBoolean("map-sync", true)).bool(cfg.getBoolean("player-positions", true))
                .bool(cfg.getBoolean("waypoint-sharing", true)).bool(false));
        waypoints.sendSaved(p);
        if (protocol == PROTOCOL && cfg.getBoolean("map-sync", true)) map.queueUpload(p);
        admin.markDirty(p);
    }

    // ------------------------------------------------------------------ тик
    private void tick() {
        tick++;
        auth.tick();
        if (!pendingHello.isEmpty()) {
            pendingHello.entrySet().removeIf(e -> {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) return true;
                if (!auth.isAuthenticated(p)) return false;
                completeHello(p, e.getValue());
                return true;
            });
        }
        map.tick();
        if (tick % 20 == 0 && getConfig().getBoolean("player-positions", true)) sendPositions();
        if (tick % 40 == 0) sendRespawns();
        admin.tick(tick);
        if (tick % 5 == 0) antiEsp.tick();
        if (tick % (20 * 120) == 0) map.save();
    }

    private void sendPositions() {
        var all = Bukkit.getOnlinePlayers();
        int vd = Bukkit.getViewDistance();
        for (Player p : all) {
            if (!arcPlayers.contains(p.getUniqueId())) continue;
            Buf buf = packet("players").varInt(vd);
            var visible = new java.util.ArrayList<Player>();
            for (Player o : all) {
                if (o == p || hidden.contains(o.getUniqueId()) || o.isInvisible()
                        || o.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)
                        || o.getGameMode() == org.bukkit.GameMode.SPECTATOR || !p.canSee(o) && !antiEsp.hiddenByUs(p, o)) continue;
                visible.add(o);
            }
            buf.varInt(visible.size());
            for (Player o : visible) {
                Location l = o.getLocation();
                buf.uuid(o.getUniqueId()).utf(o.getName()).utf(dimId(o.getWorld())).f64(l.getX()).f64(l.getY()).f64(l.getZ())
                        .f32(l.getYaw()).f32((float) o.getHealth());
            }
            var hid = new java.util.ArrayList<UUID>();
            for (Player o : all) {
                if (o == p) continue;
                if (hidden.contains(o.getUniqueId()) || o.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) hid.add(o.getUniqueId());
            }
            buf.varInt(hid.size());
            for (UUID h : hid) buf.uuid(h);
            send(p, buf);
        }
    }

    @SuppressWarnings("deprecation")
    private void sendRespawns() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!arcPlayers.contains(p.getUniqueId())) continue;
            Location bed = p.getBedSpawnLocation();
            boolean anchor = bed != null && bed.getBlock().getType().name().equals("RESPAWN_ANCHOR");
            String key = bed == null ? "none" : dimId(bed.getWorld()) + "|" + bed.getBlockX() + "," + bed.getBlockY() + "," + bed.getBlockZ() + "|" + anchor;
            if (key.equals(lastRespawn.get(p.getUniqueId()))) continue;
            lastRespawn.put(p.getUniqueId(), key);
            Buf buf = packet("respawn").bool(bed != null);
            if (bed != null) buf.utf(dimId(bed.getWorld())).blockPos(bed.getBlockX(), bed.getBlockY(), bed.getBlockZ()).bool(anchor);
            send(p, buf);
        }
    }

    /** Телепорт с карты для операторов — на поверхность (безопасная высота). */
    private void safeTp(Player p, Buf buf) {
        String dim = buf.readUtf(128);
        int x = buf.readInt(), yHint = buf.readInt(), z = buf.readInt();
        if (!p.isOp() && !p.hasPermission("minecraft.command.teleport")) {
            p.sendMessage("§cНужны права оператора / Operator only");
            return;
        }
        World w = world(dim);
        if (w == null || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return;
        int y;
        if (w.getEnvironment() == World.Environment.NETHER) {
            y = yHint > 0 && yHint < 120 ? yHint : 64;
            for (int i = 0; i < 100 && (w.getBlockAt(x, y, z).getType().isSolid() || w.getBlockAt(x, y + 1, z).getType().isSolid()
                    || !w.getBlockAt(x, y - 1, z).getType().isSolid()); i++) y = 10 + (y + 3 - 10) % 110;
        } else {
            y = w.getHighestBlockYAt(x, z) + 1;
        }
        p.teleport(new Location(w, x + 0.5, y, z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch()));
        p.setFallDistance(0);
    }

    // ------------------------------------------------------------------ события
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        auth.onCommand(e.getPlayer(), e.getMessage());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        arcPlayers.remove(id);
        hidden.remove(id);
        forgeClients.remove(id);
        pendingHello.remove(id);
        lastRespawn.remove(id);
        auth.forget(id);
        map.forget(id);
        waypoints.forget(id);
        admin.forget(id);
        antiEsp.forget(e.getPlayer());
    }

    // ------------------------------------------------------------------ измерения
    /** Id измерения как в Minecraft: minecraft:overworld, minecraft:the_nether, … */
    static String dimId(World w) {
        try {
            Object key = w.getClass().getMethod("getKey").invoke(w);
            if (key != null) return key.toString();
        } catch (Throwable ignored) {
        }
        World main = Bukkit.getWorlds().get(0);
        String base = main.getName();
        if (w.getName().equals(base)) return "minecraft:overworld";
        if (w.getName().equals(base + "_nether")) return "minecraft:the_nether";
        if (w.getName().equals(base + "_the_end")) return "minecraft:the_end";
        return "minecraft:" + w.getName().toLowerCase(java.util.Locale.ROOT);
    }

    static World world(String dim) {
        for (World w : Bukkit.getWorlds()) if (dimId(w).equals(dim)) return w;
        return null;
    }
}

package com.arc4io.archud.bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Админ-панель Arc HUD на Bukkit/Spigot/Paper: тот же JSON и те же действия, что у мода.
 * Доступ — право archud.admin (по умолчанию операторам) и вход в аккаунт (/login).
 */
final class Admin implements Listener {
    private static final Gson GSON = new Gson();
    private static final String[] RULES = {"keepInventory", "doDaylightCycle", "doWeatherCycle", "mobGriefing", "doFireTick", "doMobSpawning",
            "doInsomnia", "doImmediateRespawn", "naturalRegeneration", "showDeathMessages", "announceAdvancements", "doMobLoot",
            "doTileDrops", "fallDamage", "fireDamage", "drowningDamage", "doPatrolSpawning", "doTraderSpawning", "forgiveDeadPlayers",
            "sendCommandFeedback"};
    private final ArcHudPlugin plugin;
    private final Set<UUID> god = new HashSet<>(), frozen = new HashSet<>();
    private final Set<String> muted = new HashSet<>();
    private final Deque<String> log = new ArrayDeque<>();
    private final Map<UUID, Boolean> lastAllowed = new HashMap<>();
    private final long startedAt = System.currentTimeMillis();

    Admin(ArcHudPlugin plugin) {
        this.plugin = plugin;
        muted.addAll(plugin.getConfig().getStringList("muted"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    boolean allowed(Player p) {
        return plugin.arcPlayers.contains(p.getUniqueId()) && p.hasPermission("archud.admin");
    }

    void markDirty(Player p) {
        lastAllowed.remove(p.getUniqueId());
    }

    void forget(UUID id) {
        lastAllowed.remove(id);
        frozen.remove(id);
    }

    void tick(int tick) {
        if (tick % 40 != 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.arcPlayers.contains(p.getUniqueId())) continue;
            boolean a = allowed(p);
            Boolean was = lastAllowed.put(p.getUniqueId(), a);
            if (was == null || was != a) plugin.send(p, ArcHudPlugin.packet("admin_allowed").bool(a));
        }
    }

    // ------------------------------------------------------------------ события (мут, заморозка, бессмертие)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (muted.contains(e.getPlayer().getName())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cВы заглушены администратором / You are muted");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId()) || e.getTo() == null) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location back = f.clone();
            back.setYaw(t.getYaw());
            back.setPitch(t.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && god.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ состояние
    @SuppressWarnings("deprecation")
    void sendState(Player admin) {
        if (!allowed(admin)) return;
        JsonObject o = new JsonObject();
        JsonObject srv = new JsonObject();
        srv.addProperty("motd", Bukkit.getMotd());
        srv.addProperty("version", Bukkit.getBukkitVersion());
        srv.addProperty("brand", Bukkit.getName());
        double tps = 20;
        double mspt = 50;
        try {
            double[] t = (double[]) Bukkit.class.getMethod("getTPS").invoke(null); // Paper
            tps = Math.min(20, t[0]);
            mspt = (double) Bukkit.class.getMethod("getAverageTickTime").invoke(null);
        } catch (Throwable ignored) {
        }
        srv.addProperty("mspt", Math.round(mspt * 10) / 10.0);
        srv.addProperty("tps", Math.round(tps * 10) / 10.0);
        Runtime rt = Runtime.getRuntime();
        srv.addProperty("memUsed", (rt.totalMemory() - rt.freeMemory()) >> 20);
        srv.addProperty("memMax", rt.maxMemory() >> 20);
        srv.addProperty("uptime", (System.currentTimeMillis() - startedAt) / 1000);
        srv.addProperty("players", Bukkit.getOnlinePlayers().size());
        srv.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        World ow = Bukkit.getWorlds().get(0);
        srv.addProperty("viewDistance", ow.getViewDistance());
        srv.addProperty("simDistance", ow.getSimulationDistance());
        srv.addProperty("whitelist", Bukkit.hasWhitelist());
        srv.addProperty("pvp", ow.getPVP());
        srv.addProperty("difficulty", ow.getDifficulty().name().toLowerCase());
        srv.addProperty("antiEsp", plugin.getConfig().getBoolean("anti-esp", true));
        srv.addProperty("level", admin.isOp() ? 4 : 2);
        o.add("server", srv);

        JsonObject world = new JsonObject();
        world.addProperty("time", ow.getTime());
        world.addProperty("day", ow.getFullTime() / 24000L + 1);
        world.addProperty("weather", ow.isThundering() ? "thunder" : ow.hasStorm() ? "rain" : "clear");
        JsonObject rules = new JsonObject();
        for (String r : RULES) {
            GameRule<?> gr = GameRule.getByName(r);
            if (gr != null && gr.getType() == Boolean.class) {
                Object v = ow.getGameRuleValue(gr);
                if (v instanceof Boolean b) rules.addProperty(r, b);
            }
        }
        world.add("rules", rules);
        o.add("world", world);

        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject j = new JsonObject();
            Location l = p.getLocation();
            j.addProperty("name", p.getName());
            j.addProperty("uuid", p.getUniqueId().toString());
            j.addProperty("op", p.isOp() ? 4 : 0);
            j.addProperty("mode", p.getGameMode().name().toLowerCase());
            j.addProperty("health", Math.round(p.getHealth()));
            j.addProperty("food", p.getFoodLevel());
            j.addProperty("xp", p.getLevel());
            j.addProperty("dim", ArcHudPlugin.dimId(p.getWorld()));
            j.addProperty("x", l.getBlockX());
            j.addProperty("y", l.getBlockY());
            j.addProperty("z", l.getBlockZ());
            j.addProperty("ping", p.getPing());
            j.addProperty("fly", p.getAllowFlight());
            j.addProperty("god", god.contains(p.getUniqueId()));
            j.addProperty("frozen", frozen.contains(p.getUniqueId()));
            j.addProperty("muted", muted.contains(p.getName()));
            j.addProperty("arc", plugin.arcPlayers.contains(p.getUniqueId()));
            j.addProperty("hidden", plugin.hidden.contains(p.getUniqueId()));
            j.addProperty("ip", p.getAddress() == null ? "?" : p.getAddress().getAddress().getHostAddress());
            players.add(j);
        }
        o.add("players", players);
        JsonArray bans = new JsonArray(), ipBans = new JsonArray(), wl = new JsonArray(), ops = new JsonArray(), mutedArr = new JsonArray(), logArr = new JsonArray();
        for (var e : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) bans.add(e.getTarget());
        for (var e : Bukkit.getBanList(BanList.Type.IP).getBanEntries()) ipBans.add(e.getTarget());
        for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) wl.add(p.getName() == null ? p.getUniqueId().toString() : p.getName());
        for (OfflinePlayer p : Bukkit.getOperators()) ops.add(p.getName() == null ? p.getUniqueId().toString() : p.getName());
        for (String m : muted) mutedArr.add(m);
        for (String l : log) logArr.add(l);
        o.add("bans", bans);
        o.add("ipBans", ipBans);
        o.add("whitelistNames", wl);
        o.add("ops", ops);
        o.add("mutedNames", mutedArr);
        o.add("log", logArr);
        plugin.send(admin, ArcHudPlugin.packet("admin_state").bytes(GSON.toJson(o).getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ действия
    void onAction(Player admin, String action, String target, String arg) {
        if (!allowed(admin)) {
            plugin.send(admin, ArcHudPlugin.packet("admin_result").utf("No permission"));
            return;
        }
        String name = target.replaceAll("[^A-Za-z0-9_]", "");
        String safeArg = arg.replace('\n', ' ').replace('§', ' ');
        Player t = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
        boolean ok = execute(admin, action, t, name, safeArg);
        if (ok) {
            String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + admin.getName() + ": " + action
                    + (name.isEmpty() ? "" : " " + name) + (safeArg.isEmpty() ? "" : " " + safeArg);
            log.addFirst(line);
            while (log.size() > 60) log.removeLast();
            plugin.getLogger().info("[admin] " + line);
        }
        plugin.send(admin, ArcHudPlugin.packet("admin_result").utf(ok ? "✔" : (t == null && action.startsWith("p_") ? name + " offline" : "?")));
        sendState(admin);
    }

    @SuppressWarnings("deprecation")
    private boolean execute(Player admin, String action, Player t, String name, String arg) {
        if (action.startsWith("p_") && name.isEmpty()) return false;
        World ow = admin.getWorld();
        switch (action) {
            case "p_kick" -> cmd(admin, "kick " + name + (arg.isEmpty() ? "" : " " + arg));
            case "p_ban" -> cmd(admin, "ban " + name + (arg.isEmpty() ? "" : " " + arg));
            case "p_banip" -> cmd(admin, "ban-ip " + name);
            case "p_op" -> cmd(admin, "op " + name);
            case "p_deop" -> cmd(admin, "deop " + name);
            case "p_survival", "p_creative", "p_adventure", "p_spectator" -> {
                if (t == null) return false;
                t.setGameMode(GameMode.valueOf(action.substring(2).toUpperCase()));
            }
            case "p_tpto" -> {
                if (t == null) return false;
                admin.teleport(t);
            }
            case "p_tphere" -> {
                if (t == null) return false;
                t.teleport(admin);
            }
            case "p_heal" -> {
                if (t == null) return false;
                t.setHealth(t.getMaxHealth()); // без Attribute: в 1.21.3+ перечисление атрибутов переделано
                t.setFireTicks(0);
            }
            case "p_feed" -> {
                if (t == null) return false;
                t.setFoodLevel(20);
                t.setSaturation(20);
            }
            case "p_kill" -> {
                if (t == null) return false;
                t.setHealth(0);
            }
            case "p_clearinv" -> {
                if (t == null) return false;
                t.getInventory().clear();
            }
            case "p_cleareffects" -> {
                if (t == null) return false;
                for (PotionEffect e : t.getActivePotionEffects()) t.removePotionEffect(e.getType());
            }
            case "p_fly" -> {
                if (t == null) return false;
                boolean on = !t.getAllowFlight();
                t.setAllowFlight(on);
                if (!on) t.setFlying(false);
            }
            case "p_god" -> {
                if (t == null) return false;
                if (!god.remove(t.getUniqueId())) god.add(t.getUniqueId());
            }
            case "p_freeze" -> {
                if (t == null) return false;
                if (!frozen.remove(t.getUniqueId())) frozen.add(t.getUniqueId());
            }
            case "p_mute" -> {
                if (!muted.remove(name)) muted.add(name);
                plugin.getConfig().set("muted", List.copyOf(muted));
                plugin.saveConfig();
            }
            case "p_smite" -> {
                if (t == null) return false;
                t.getWorld().strikeLightning(t.getLocation());
            }
            case "p_launch" -> {
                if (t == null) return false;
                t.setVelocity(t.getVelocity().add(new Vector(0, 2.5, 0)));
            }
            case "p_xp" -> {
                if (t == null) return false;
                t.giveExpLevels(10);
            }
            case "p_xpreset" -> {
                if (t == null) return false;
                t.setLevel(0);
                t.setExp(0);
            }
            case "p_speed" -> effect(t, PotionEffectType.SPEED, 1);
            case "p_nightvision" -> effect(t, PotionEffectType.NIGHT_VISION, 0);
            case "p_invis" -> effect(t, PotionEffectType.INVISIBILITY, 0);
            case "p_fireres" -> effect(t, PotionEffectType.FIRE_RESISTANCE, 0);
            case "p_regen" -> effect(t, PotionEffectType.REGENERATION, 1);
            case "p_wladd" -> Bukkit.getOfflinePlayer(name).setWhitelisted(true);
            case "p_wlremove" -> Bukkit.getOfflinePlayer(name).setWhitelisted(false);
            case "p_msg" -> {
                if (t == null) return false;
                t.sendMessage("§d[" + admin.getName() + " → you] " + arg);
            }
            case "p_spawnpoint" -> {
                if (t == null) return false;
                t.setBedSpawnLocation(t.getLocation(), true);
            }
            case "p_spectate" -> {
                if (t == null) return false;
                admin.setGameMode(GameMode.SPECTATOR);
                admin.teleport(t);
                admin.setSpectatorTarget(t);
            }
            case "p_extinguish" -> {
                if (t == null) return false;
                t.setFireTicks(0);
            }
            case "p_unban" -> Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            case "p_unbanip" -> Bukkit.getBanList(BanList.Type.IP).pardon(arg.replaceAll("[^0-9a-fA-F.:]", ""));
            case "p_tpspawn" -> {
                if (t == null) return false;
                t.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            case "w_day" -> ow.setTime(1000);
            case "w_noon" -> ow.setTime(6000);
            case "w_night" -> ow.setTime(13000);
            case "w_midnight" -> ow.setTime(18000);
            case "w_clear" -> {
                ow.setStorm(false);
                ow.setThundering(false);
            }
            case "w_rain" -> {
                ow.setStorm(true);
                ow.setThundering(false);
            }
            case "w_thunder" -> {
                ow.setStorm(true);
                ow.setThundering(true);
            }
            case "w_peaceful", "w_easy", "w_normal", "w_hard" -> {
                Difficulty d = Difficulty.valueOf(action.substring(2).toUpperCase());
                for (World w : Bukkit.getWorlds()) w.setDifficulty(d);
            }
            case "w_killmobs" -> {
                for (World w : Bukkit.getWorlds())
                    for (var e : w.getLivingEntities()) if (e instanceof org.bukkit.entity.Monster) e.remove();
            }
            case "w_killitems" -> {
                for (World w : Bukkit.getWorlds()) for (var e : w.getEntitiesByClass(org.bukkit.entity.Item.class)) e.remove();
            }
            case "w_setspawn" -> admin.getWorld().setSpawnLocation(admin.getLocation());
            case "w_rule" -> {
                if (!Arrays.asList(RULES).contains(arg)) return false;
                @SuppressWarnings("unchecked") GameRule<Boolean> gr = (GameRule<Boolean>) GameRule.getByName(arg);
                if (gr == null) return false;
                Boolean cur = ow.getGameRuleValue(gr);
                for (World w : Bukkit.getWorlds()) w.setGameRule(gr, cur == null || !cur);
            }
            case "w_pvp" -> {
                boolean on = !ow.getPVP();
                for (World w : Bukkit.getWorlds()) w.setPVP(on);
            }
            case "s_broadcast" -> {
                if (arg.isBlank()) return false;
                Bukkit.broadcastMessage("§6[Объявление] " + arg);
            }
            case "s_save" -> {
                for (World w : Bukkit.getWorlds()) w.save();
                Bukkit.savePlayers();
            }
            case "s_whitelist" -> Bukkit.setWhitelist(!Bukkit.hasWhitelist());
            case "s_reload" -> cmd(admin, "minecraft:reload");
            case "s_view_up", "s_view_down" -> {
                for (World w : Bukkit.getWorlds()) {
                    int v = w.getViewDistance() + (action.endsWith("up") ? 1 : -1);
                    try {
                        World.class.getMethod("setViewDistance", int.class).invoke(w, Math.max(2, Math.min(32, v))); // Paper
                    } catch (Throwable e) {
                        return false;
                    }
                }
            }
            case "s_sim_up", "s_sim_down" -> {
                for (World w : Bukkit.getWorlds()) {
                    int v = w.getSimulationDistance() + (action.endsWith("up") ? 1 : -1);
                    try {
                        World.class.getMethod("setSimulationDistance", int.class).invoke(w, Math.max(2, Math.min(32, v))); // Paper
                    } catch (Throwable e) {
                        return false;
                    }
                }
            }
            case "s_healall" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.setHealth(p.getMaxHealth());
                    p.setFoodLevel(20);
                }
            }
            case "s_kicknonops" -> {
                for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) if (!p.isOp() && p != admin) p.kickPlayer("Server closed for maintenance");
            }
            case "s_antiesp" -> {
                plugin.getConfig().set("anti-esp", !plugin.getConfig().getBoolean("anti-esp", true));
                plugin.saveConfig();
            }
            case "s_stop" -> {
                if (!admin.isOp()) return false;
                Bukkit.shutdown();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void effect(Player t, PotionEffectType type, int amp) {
        if (t != null) t.addPotionEffect(new PotionEffect(type, 20 * 60 * 5, amp));
    }

    /** Команда от имени администратора — права проверяет сервер. */
    private static void cmd(Player admin, String command) {
        admin.performCommand(command);
    }
}

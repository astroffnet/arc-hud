package com.arc4io.archud.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Авторизация (/login, /register). Пока игрок не вошёл, Arc HUD ничего ему не отдаёт и ничего не принимает.
 *  1. AuthMe (fr.xephi.authme.api.v3.AuthMeApi) и nLogin (com.nickuc.login.api.nLoginAPI) — через их API;
 *  2. есть команды /login или /register от другого плагина — вошёл после /login (/register) и последующего движения;
 *  3. авторизации нет — все считаются вошедшими.
 */
final class Auth {
    private static final Set<String> AUTH_COMMANDS = Set.of("login", "l", "log", "register", "reg", "auth");
    private final ArcHudPlugin plugin;
    private final Set<UUID> authed = new HashSet<>();
    private final Map<UUID, Location> attempt = new HashMap<>();
    private final Map<UUID, Long> attemptAt = new HashMap<>();
    private Object authMe;
    private Method authMeCheck;
    private Object nLogin;
    private Method nLoginCheck;
    private boolean apisChecked;

    Auth(ArcHudPlugin plugin) {
        this.plugin = plugin;
    }

    private void findApis() {
        if (apisChecked) return;
        apisChecked = true;
        try {
            Class<?> c = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            authMe = c.getMethod("getInstance").invoke(null);
            authMeCheck = c.getMethod("isAuthenticated", Player.class);
            plugin.getLogger().info("AuthMe detected: waypoints and map are sent after /login");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = Class.forName("com.nickuc.login.api.nLoginAPI");
            nLogin = c.getMethod("getApi").invoke(null);
            nLoginCheck = nLogin.getClass().getMethod("isAuthenticated", String.class);
            plugin.getLogger().info("nLogin detected: waypoints and map are sent after /login");
        } catch (Throwable ignored) {
        }
    }

    boolean isAuthenticated(Player p) {
        if (authed.contains(p.getUniqueId())) return true;
        findApis();
        try {
            if (authMe != null) return mark(p, (Boolean) authMeCheck.invoke(authMe, p));
            if (nLogin != null) return mark(p, (Boolean) nLoginCheck.invoke(nLogin, p.getName()));
        } catch (Throwable ignored) {
        }
        boolean authServer = Bukkit.getPluginCommand("login") != null || Bukkit.getPluginCommand("register") != null;
        return !authServer && mark(p, true);
    }

    private boolean mark(Player p, boolean ok) {
        if (ok) {
            authed.add(p.getUniqueId());
            attempt.remove(p.getUniqueId());
        }
        return ok;
    }

    void onCommand(Player p, String message) {
        if (authed.contains(p.getUniqueId())) return;
        String c = message.startsWith("/") ? message.substring(1) : message;
        int sp = c.indexOf(' ');
        String name = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
        if (!AUTH_COMMANDS.contains(name)) return;
        attempt.put(p.getUniqueId(), p.getLocation().clone());
        attemptAt.put(p.getUniqueId(), System.currentTimeMillis());
    }

    void tick() {
        if (attempt.isEmpty()) return;
        long now = System.currentTimeMillis();
        attempt.entrySet().removeIf(e -> {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) return true;
            long at = attemptAt.getOrDefault(e.getKey(), now);
            if (now - at < 800) return false;
            Location a = e.getValue(), l = p.getLocation();
            if (a.getWorld() != l.getWorld() || a.distanceSquared(l) > 1.5 * 1.5) {
                authed.add(e.getKey());
                return true;
            }
            return now - at > 10 * 60_000L;
        });
    }

    void forget(UUID id) {
        authed.remove(id);
        attempt.remove(id);
        attemptAt.remove(id);
    }
}

package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Авторизация на серверах с /login и /register (пиратские сервера): пока игрок не вошёл в аккаунт,
 * Arc HUD ничего ему не отдаёт (метки, карта, позиции игроков, админ-панель) и ничего от него не принимает —
 * иначе любой, зайдя под чужим ником, получил бы чужие метки или перезаписал их.
 *
 * Как определяется «вошёл»:
 *  1. API известных модов авторизации (EasyAuth и подобные — метод isAuthenticated у игрока);
 *  2. если на сервере нет команд /login, /register — авторизации нет, все считаются вошедшими;
 *  3. иначе — игрок выполнил /login (/l, /register, /reg) и после этого сдвинулся с места
 *     (моды авторизации держат игрока на месте до успешного входа). Хост «Открыть для сети» — всегда вошёл.
 */
public final class AuthGate {
    private static final Set<String> AUTH_COMMANDS = Set.of("login", "l", "log", "register", "reg", "auth");
    private static final Set<UUID> AUTHED = new HashSet<>();
    /** Игрок ввёл команду входа: {время, x, y, z}. */
    private static final Map<UUID, double[]> ATTEMPT = new HashMap<>();
    private static MinecraftServer cachedFor;
    private static boolean authServer;
    private static long checkedAt;

    private AuthGate() {}

    public static boolean isAuthenticated(ServerPlayer player) {
        if (player == null) return false;
        UUID id = player.getUUID();
        if (AUTHED.contains(id)) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        if (server.isSingleplayerOwner(player.getGameProfile())) return mark(id);
        Boolean api = viaApi(player);
        if (api != null) {
            if (api) mark(id);
            return api;
        }
        return !authServer(server) && mark(id);
    }

    private static boolean mark(UUID id) {
        AUTHED.add(id);
        ATTEMPT.remove(id);
        return true;
    }

    /** Есть ли на сервере команды входа (проверка раз в 30 секунд — плагины/моды могут регистрировать их позже). */
    public static boolean authServer(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (cachedFor != server || now - checkedAt > 30_000) {
            cachedFor = server;
            checkedAt = now;
            var root = server.getCommands().getDispatcher().getRoot();
            authServer = root.getChild("login") != null || root.getChild("register") != null;
        }
        return authServer;
    }

    // ------------------------------------------------------------------ API модов авторизации
    private static final Map<Class<?>, Method> API = new HashMap<>();

    /** EasyAuth и похожие: у игрока (через миксин мода) есть метод isAuthenticated / easyAuth$isAuthenticated. */
    private static Boolean viaApi(ServerPlayer player) {
        Class<?> c = player.getClass();
        Method m;
        if (API.containsKey(c)) m = API.get(c);
        else {
            m = null;
            for (String name : new String[]{"easyAuth$isAuthenticated", "isAuthenticated", "easyauth$isAuthenticated"}) {
                try {
                    Method found = c.getMethod(name);
                    if (found.getReturnType() == boolean.class) {
                        m = found;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
            API.put(c, m);
            if (m != null) ArcHud.LOGGER.info("Arc HUD: auth API detected ({}), waypoints are sent after login", m.getName());
        }
        if (m == null) return null;
        try {
            return (Boolean) m.invoke(player);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ команда входа + движение
    /** Вызывается перед выполнением любой команды игрока. */
    public static void onCommand(ServerPlayer player, String command) {
        if (player == null || command == null || AUTHED.contains(player.getUUID())) return;
        String c = command.startsWith("/") ? command.substring(1) : command;
        int sp = c.indexOf(' ');
        String name = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
        if (!AUTH_COMMANDS.contains(name)) return;
        ATTEMPT.put(player.getUUID(), new double[]{System.currentTimeMillis(), player.getX(), player.getY(), player.getZ()});
    }

    public static void tick(MinecraftServer server) {
        if (ATTEMPT.isEmpty()) return;
        long now = System.currentTimeMillis();
        ATTEMPT.entrySet().removeIf(e -> {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) return true;
            double[] a = e.getValue();
            if (now - a[0] < 800) return false;
            double dx = p.getX() - a[1], dz = p.getZ() - a[3];
            if (dx * dx + dz * dz > 1.5 * 1.5 || Math.abs(p.getY() - a[2]) > 1.5) {
                AUTHED.add(e.getKey());
                ArcHud.LOGGER.info("Arc HUD: {} logged in", p.getGameProfile().getName());
                return true;
            }
            return now - a[0] > 10 * 60_000L; // забыть попытку через 10 минут
        });
    }

    public static void onLogout(ServerPlayer player) {
        AUTHED.remove(player.getUUID());
        ATTEMPT.remove(player.getUUID());
    }

    public static void reset() {
        AUTHED.clear();
        ATTEMPT.clear();
        cachedFor = null;
    }
}

package com.arc4io.archud.client.net;

import com.arc4io.archud.config.ArcConfig;

/**
 * Вход в приглашения Steam без ссылок на классы steamworks4j (их может не быть — например, в среде разработки).
 */
public final class SteamGate {
    private static Boolean available;
    private static int startDelay = 200;

    private SteamGate() {}

    public static boolean available() {
        if (available == null) {
            boolean a;
            try {
                Class.forName("com.codedisaster.steamworks.SteamAPI", false, SteamGate.class.getClassLoader());
                a = true;
            } catch (Throwable t) {
                a = false;
            }
            available = a;
        }
        return available && ArcConfig.get().steamInvites;
    }

    /** Каждый тик клиента: подключиться к Steam через ~10 с после запуска (чтобы принимать приглашения) и разбирать события. */
    public static void tick() {
        if (!available()) return;
        try {
            if (startDelay > 0 && --startDelay == 0) SteamInvites.init();
            SteamInvites.tick();
        } catch (Throwable ignored) {
        }
    }

    /** Пригласить друзей Steam; false — Steam недоступен (причина в lastError). */
    public static boolean invite(String address, String worldName) {
        if (!available()) return false;
        try {
            return SteamInvites.invite(address, worldName);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void close() {
        if (!available()) return;
        try {
            SteamInvites.close();
        } catch (Throwable ignored) {
        }
    }

    public static String lastError() {
        try {
            return available() ? SteamInvites.lastError() : "steam_missing";
        } catch (Throwable t) {
            return "steam_missing";
        }
    }
}

package com.arc4io.archud.client.net;

import com.arc4io.archud.ArcHud;
import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Приглашения через Steam «классическим способом Spacewar» (тестовое приложение Steam с id 480, которое есть у всех):
 * хост создаёт лобби друзей, кладёт в него адрес игры (e4mc или адрес в сети) и открывает окно приглашения Steam;
 * друг принимает приглашение в оверлее Steam (с запущенным Minecraft) — Arc HUD читает адрес из лобби и подключается.
 * Работает через steamworks4j (MIT); нужен запущенный клиент Steam. Классы Steam загружаются только здесь.
 */
public final class SteamInvites {
    static final int SPACEWAR = 480;
    private static boolean ready, tried;
    private static SteamMatchmaking matchmaking;
    private static SteamFriends friends;
    private static SteamID lobby;
    private static String pendingAddress, pendingName;
    private static String lastError = "";

    private SteamInvites() {}

    /** Подключиться к Steam (один раз). */
    static boolean init() {
        if (ready) return true;
        if (tried) return false;
        tried = true;
        try {
            // Steam берёт id приложения из steam_appid.txt в рабочей папке процесса — классический приём Spacewar
            for (File dir : new File[]{new File("."), Minecraft.getInstance().gameDirectory}) {
                try {
                    Files.writeString(new File(dir, "steam_appid.txt").toPath(), Integer.toString(SPACEWAR), StandardCharsets.US_ASCII);
                } catch (Throwable ignored) {
                }
            }
            SteamAPI.loadLibraries();
            // SteamAPI_IsSteamRunning часто отвечает «нет» при запущенном Steam (запуск игры через лаунчер, от другого
            // пользователя или с другими правами), поэтому сразу пробуем подключиться, а «Steam не запущен» говорим,
            // только если процесса Steam действительно нет
            if (!SteamAPI.init()) {
                boolean proc = steamProcess();
                lastError = proc ? "steam_init" : "steam_not_running";
                tried = false; // попробуем снова при следующем нажатии
                ArcHud.LOGGER.warn("Arc HUD: SteamAPI.init() failed (steam process found: {}, IsSteamRunning: {})", proc, safeRunning());
                return false;
            }
            matchmaking = new SteamMatchmaking((SteamMatchmakingCallback) Proxy.newProxyInstance(SteamInvites.class.getClassLoader(),
                    new Class<?>[]{SteamMatchmakingCallback.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "onLobbyCreated" -> onLobbyCreated((SteamResult) a[0], (SteamID) a[1]);
                            case "onLobbyEnter" -> onLobbyEnter((SteamID) a[0]);
                            case "hashCode" -> {
                                return System.identityHashCode(p);
                            }
                            case "equals" -> {
                                return p == a[0];
                            }
                            case "toString" -> {
                                return "ArcSteamMatchmaking";
                            }
                            default -> {
                            }
                        }
                        return null;
                    }));
            friends = new SteamFriends((SteamFriendsCallback) Proxy.newProxyInstance(SteamInvites.class.getClassLoader(),
                    new Class<?>[]{SteamFriendsCallback.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "onGameLobbyJoinRequested" -> matchmaking.joinLobby((SteamID) a[0]);
                            case "onGameRichPresenceJoinRequested" -> {
                                String s = String.valueOf(a[1]);
                                if (s.startsWith("+archud_connect ")) offerJoin(s.substring(16).trim(), null);
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(p);
                            }
                            case "equals" -> {
                                return p == a[0];
                            }
                            case "toString" -> {
                                return "ArcSteamFriends";
                            }
                            default -> {
                            }
                        }
                        return null;
                    }));
            ready = true;
            ArcHud.LOGGER.info("Arc HUD: Steam connected (Spacewar {}), invites available", SPACEWAR);
            return true;
        } catch (Throwable t) {
            lastError = "steam_init";
            tried = false;
            ArcHud.LOGGER.warn("Arc HUD: Steam invites unavailable", t);
            return false;
        }
    }

    /** Есть ли среди процессов клиент Steam (steam.exe / steam / steamwebhelper). */
    private static boolean steamProcess() {
        try {
            return ProcessHandle.allProcesses().anyMatch(ph -> ph.info().command().map(c -> {
                String n = new File(c).getName().toLowerCase(java.util.Locale.ROOT);
                return n.equals("steam.exe") || n.equals("steam") || n.startsWith("steamwebhelper") || n.equals("steam_osx");
            }).orElse(false));
        } catch (Throwable t) {
            return true;
        }
    }

    private static String safeRunning() {
        try {
            return String.valueOf(SteamAPI.isSteamRunning(true));
        } catch (Throwable t) {
            return t.toString();
        }
    }

    static void tick() {
        if (!ready) return;
        try {
            SteamAPI.runCallbacks();
        } catch (Throwable t) {
            ready = false;
        }
    }

    /** Пригласить друзей в открытую игру: адрес кладётся в лобби, открывается окно приглашения Steam. */
    static boolean invite(String address, String worldName) {
        if (!init()) return false;
        pendingAddress = address;
        pendingName = worldName;
        try {
            friends.setRichPresence("connect", "+archud_connect " + address);
            friends.setRichPresence("status", "Minecraft: " + worldName);
        } catch (Throwable ignored) {
        }
        if (lobby != null && lobby.isValid()) {
            matchmaking.setLobbyData(lobby, "archud_addr", address);
            matchmaking.setLobbyData(lobby, "archud_name", worldName);
            friends.activateGameOverlayInviteDialog(lobby);
            return true;
        }
        matchmaking.createLobby(SteamMatchmaking.LobbyType.FriendsOnly, 16);
        return true;
    }

    /** Игра закрыта для сети — лобби больше не нужно. */
    static void close() {
        if (!ready) return;
        try {
            if (lobby != null) matchmaking.leaveLobby(lobby);
            friends.setRichPresence("connect", "");
        } catch (Throwable ignored) {
        }
        lobby = null;
    }

    private static void onLobbyCreated(SteamResult result, SteamID id) {
        if (result != SteamResult.OK || id == null) {
            lastError = "lobby";
            return;
        }
        lobby = id;
        matchmaking.setLobbyData(id, "archud_addr", pendingAddress == null ? "" : pendingAddress);
        matchmaking.setLobbyData(id, "archud_name", pendingName == null ? "" : pendingName);
        friends.activateGameOverlayInviteDialog(id);
    }

    private static void onLobbyEnter(SteamID id) {
        if (id == null || (lobby != null && id.equals(lobby))) return; // своё лобби
        String addr = matchmaking.getLobbyData(id, "archud_addr");
        String name = matchmaking.getLobbyData(id, "archud_name");
        matchmaking.leaveLobby(id);
        if (addr == null || addr.isBlank()) return;
        offerJoin(addr, name);
    }

    /** Подключиться к другу (с подтверждением, если сейчас открыт мир). */
    private static void offerJoin(String addr, String name) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            String title = name == null || name.isBlank() ? addr : name;
            Runnable go = () -> {
                if (mc.level != null) {
                    mc.level.disconnect();
                    mc.clearLevel();
                }
                ServerData data = new ServerData(title, addr, false);
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr), data, false);
            };
            if (mc.level == null) {
                go.run();
                return;
            }
            var back = mc.screen;
            mc.setScreen(new ConfirmScreen(yes -> {
                if (yes) go.run();
                else mc.setScreen(back);
            }, Component.translatable("archud.steam.join_title"), Component.translatable("archud.steam.join_text", title)));
        });
    }

    static String lastError() {
        return lastError;
    }
}

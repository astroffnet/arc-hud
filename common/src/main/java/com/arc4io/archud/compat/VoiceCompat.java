package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.ArcHudApi;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Голосовые чаты: Simple Voice Chat и Plasmo Voice (через рефлексию, оба необязательны).
 *  - панель HUD: подключение, микрофон/звук, «вы говорите» (и шёпот), группа, кто сейчас говорит;
 *  - на мини-карте и карте мира у говорящих игроков — зелёная рамка.
 */
public final class VoiceCompat {
    private static boolean svc, plasmo;
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static Set<UUID> talking = Set.of();
    private static long lastUpdate;
    private static String ownState = "";
    private static boolean ownTalking, ownWhisper;
    private static String group;

    private VoiceCompat() {}

    static void init() {
        svc = ArcHud.platform().isModLoaded("voicechat") && exists("de.maxhenkel.voicechat.voice.client.ClientManager");
        plasmo = ArcHud.platform().isModLoaded("plasmovoice") && exists("su.plo.voice.client.ModVoiceClient");
        if (!svc && !plasmo) return;
        ArcHudApi.registerPanel(ArcHud.id("voice"), Component.translatable("archud.voice.title"), 0xFF66FF99, VoiceCompat::rows);
        ArcHud.LOGGER.info("Arc HUD: интеграция с голосовым чатом включена ({})", svc ? "Simple Voice Chat" : "Plasmo Voice");
    }

    private static boolean exists(String name) {
        try {
            Integrations.cls(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean active() {
        return (svc || plasmo) && ArcConfig.get().voiceIntegration;
    }

    /** Говорит ли игрок прямо сейчас. */
    public static boolean isTalking(UUID id) {
        if (!active()) return false;
        update();
        return talking.contains(id);
    }

    // ------------------------------------------------------------------ опрос (раз в 100 мс)
    private static void update() {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 100) return;
        lastUpdate = now;
        Minecraft mc = Minecraft.getInstance();
        Set<UUID> set = new HashSet<>();
        ownTalking = ownWhisper = false;
        group = null;
        ownState = "";
        if (mc.level == null || mc.player == null) {
            talking = set;
            return;
        }
        try {
            if (svc) updateSvc(mc, set);
            else if (plasmo) updatePlasmo(mc, set);
        } catch (Throwable ignored) {
        }
        if (ownTalking) set.add(mc.player.getUUID());
        talking = set;
    }

    private static void updateSvc(Minecraft mc, Set<UUID> set) throws Exception {
        Class<?> cm = Integrations.cls("de.maxhenkel.voicechat.voice.client.ClientManager");
        Object states = call(null, cm, "getPlayerStateManager");
        if (states != null) {
            if (bool(states, "isDisconnected")) ownState = "disconnected";
            else if (bool(states, "isDisabled")) ownState = "sound_off";
            else if (bool(states, "isMuted")) ownState = "mic_off";
            else ownState = "on";
            Object g = call(states, null, "getGroup");
            if (g != null) {
                Object n = call(g, null, "getName");
                group = n == null ? null : n.toString();
            }
        }
        Object client = call(null, cm, "getClient");
        if (client == null) return;
        Object mic = call(client, null, "getMicThread");
        if (mic != null) {
            ownTalking = bool(mic, "isTalking");
            ownWhisper = ownTalking && bool(mic, "isWhispering");
        }
        Object cache = call(client, null, "getTalkCache");
        if (cache == null) return;
        Method isTalking = cache.getClass().getMethod("isTalking", UUID.class);
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            if ((boolean) isTalking.invoke(cache, p.getUUID())) set.add(p.getUUID());
        }
    }

    private static void updatePlasmo(Minecraft mc, Set<UUID> set) throws Exception {
        Class<?> cls = Integrations.cls("su.plo.voice.client.ModVoiceClient");
        Field f = cls.getField("INSTANCE");
        Object voice = f.get(null);
        if (voice == null) return;
        Object info = call(voice, null, "getServerInfo");
        boolean connected = info instanceof Optional<?> o && o.isPresent();
        Object config = call(voice, null, "getConfig");
        Object vcfg = config == null ? null : call(config, null, "getVoice");
        boolean micOff = entry(vcfg, "getMicrophoneDisabled");
        boolean soundOff = entry(vcfg, "getDisabled");
        ownState = !connected ? "disconnected" : soundOff ? "sound_off" : micOff ? "mic_off" : "on";
        Object act = call(voice, null, "getActivationManager");
        if (act != null) {
            Object list = call(act, null, "getActivations");
            if (list instanceof Collection<?> c) {
                for (Object a : c) {
                    if (bool(a, "isActive")) {
                        ownTalking = true;
                        break;
                    }
                }
            }
        }
        Object sources = call(voice, null, "getSourceManager");
        if (sources == null) return;
        Method byPlayer = sources.getClass().getMethod("getPlayerSources", UUID.class);
        byPlayer.setAccessible(true);
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            Object c = byPlayer.invoke(sources, p.getUUID());
            if (c instanceof Collection<?> col) {
                for (Object s : col) {
                    if (bool(s, "isActivated")) {
                        set.add(p.getUUID());
                        break;
                    }
                }
            }
        }
    }

    private static boolean entry(Object cfg, String getter) {
        if (cfg == null) return false;
        try {
            Object e = call(cfg, null, getter);
            if (e == null) return false;
            Object v;
            try {
                v = call(e, null, "value");
            } catch (NoSuchMethodException ex) {
                v = call(e, null, "getValue");
            }
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean bool(Object o, String name) {
        try {
            Object v = call(o, null, name);
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Вызов метода без аргументов (статического, если o == null). */
    private static Object call(Object o, Class<?> staticCls, String name) throws Exception {
        Class<?> c = o != null ? o.getClass() : staticCls;
        String key = c.getName() + "#" + name;
        Method m = METHODS.get(key);
        if (m == null) {
            m = c.getMethod(name);
            m.setAccessible(true);
            METHODS.put(key, m);
        }
        return m.invoke(o);
    }

    // ------------------------------------------------------------------ панель HUD
    private static List<ArcHudApi.PanelRow> rows() {
        if (!active()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        update();
        if (ownState.isEmpty()) return null;
        List<ArcHudApi.PanelRow> rows = new ArrayList<>();
        int stColor = switch (ownState) {
            case "on" -> 0xFF9CFF5A;
            case "mic_off" -> 0xFFFFB74D;
            default -> 0xFFFF5252;
        };
        rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.voice.state").getString(),
                Component.translatable("archud.voice.state." + ownState).getString(), -1F, stColor, false));
        if (ownTalking) {
            rows.add(new ArcHudApi.PanelRow(Component.translatable(ownWhisper ? "archud.voice.whisper" : "archud.voice.talking").getString(), "●",
                    -1F, 0xFF66FF99, true));
        }
        if (group != null && !group.isEmpty()) {
            rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.voice.group").getString(), group, -1F, 0xFF80DEEA, false));
        }
        int shown = 0;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !talking.contains(p.getUUID())) continue;
            if (shown++ >= 4) break;
            rows.add(new ArcHudApi.PanelRow("♪ " + p.getGameProfile().getName(), "", -1F, 0xFF66FF99, false));
        }
        return rows;
    }
}
